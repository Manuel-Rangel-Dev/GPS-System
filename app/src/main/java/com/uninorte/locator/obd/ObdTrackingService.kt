package com.uninorte.locator.obd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.uninorte.locator.LocationTrackingService
import com.uninorte.locator.MainActivity
import com.uninorte.locator.OBD_TOKEN
import com.uninorte.locator.R
import com.uninorte.locator.UDP_DESTINATIONS
import com.uninorte.locator.crearRid
import com.uninorte.locator.obtenerFechaColombia
import com.uninorte.locator.obtenerHoraColombia
import com.uninorte.locator.obtenerUtc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

class ObdTrackingService : Service() {
    companion object {
        private const val CANAL = "recorrido_obd"
        private const val NOTIFICACION = 1002
        @Volatile var activo = false
            private set
        @Volatile var estado = EstadoObd()
            private set
        fun iniciar(contexto: Context) {
            if (activo || LocationTrackingService.isActive(contexto)) return
            ContextCompat.startForegroundService(contexto, Intent(contexto, ObdTrackingService::class.java))
        }
        fun detener(contexto: Context) { contexto.stopService(Intent(contexto, ObdTrackingService::class.java)) }
    }

    data class EstadoObd(val wifi: Int = R.string.obd_conectando, val protocolo: String? = null,
                         val movil: Boolean = false, val enviadas: Long = 0, val cola: Int = 0,
                         val descartadas: Long = 0, val ultimoEnvio: String? = null,
                         val consumo: Double? = null, val velocidad: Double? = null,
                         val rpm: Double? = null, val posicion: PosicionObd? = null,
                         val aviso: Int = R.string.obd_sin_gps)

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cola = ColaEnvio<PaqueteObd>()
    private lateinit var redes: RedesObd
    private lateinit var ubicacion: FusedLocationProviderClient
    private var bloqueo: PowerManager.WakeLock? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var ultimoFix: Location? = null
    private var iniciado = false
    private val callback = object : LocationCallback() {
        override fun onLocationResult(resultado: LocationResult) { ultimoFix = resultado.lastLocation }
    }

    // Dos productores de estado; evitar que un copy de lectura pise los contadores del envío.
    @Synchronized private fun actualizar(cambio: (EstadoObd) -> EstadoObd) { estado = cambio(estado) }

    override fun onCreate() {
        super.onCreate()
        ubicacion = LocationServices.getFusedLocationProviderClient(this)
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CANAL, getString(R.string.obd_titulo), NotificationManager.IMPORTANCE_LOW))
    }

    @Suppress("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Un recorrido siempre requiere una acción explícita; Android nunca lo reanuda.
        if (iniciado) return START_NOT_STICKY
        if (intent == null || LocationTrackingService.isActive(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            startForeground(NOTIFICACION, notificacion(getString(R.string.obd_conectando)))
            activo = true
            iniciado = true
            estado = EstadoObd()
            bloqueo = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uninorte:obd").apply { acquire() }
            val preferencias = getSharedPreferences("app_settings", MODE_PRIVATE)
            val emulador = preferencias.getBoolean("obd_emulador", false)
            redes = RedesObd(this, if (emulador) preferencias.getString("obd_ssid", "EMU_OBDII")!! else "WIFI_OBDII",
                if (emulador) preferencias.getString("obd_clave", "")!! else "")
            if (!emulador) ubicacion.requestLocationUpdates(
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000).setMinUpdateIntervalMillis(1_000).build(),
                callback, Looper.getMainLooper())
            val inicio = SystemClock.elapsedRealtime()
            val rid = crearRid(emulador)
            alcance.launch { leer(emulador, rid, inicio) }
            alcance.launch { enviar() }
        } catch (_: Exception) {
            actualizar { it.copy(aviso = R.string.obd_error_inicio) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun leer(emulador: Boolean, rid: String, inicio: Long) {
        var seq = 0L
        var turno = 0
        var fallos = 0
        var vacias = 0
        var terminada = false
        var redSocket: android.net.Network? = null
        val valores = MutableList<Double?>(15) { null }
        while (currentCoroutineContext().isActive) {
            try {
                redes.solicitarWifi()
                val red = redes.wifi
                if (red == null) {
                    socket?.close(); socket = null
                    actualizar { it.copy(wifi = if (redes.errorWifi) R.string.obd_wifi_error else R.string.obd_conectando, protocolo = null) }
                    delay(1_000); continue
                }
                if (redSocket != red) { socket?.close(); socket = null }
                if (socket == null) {
                    actualizar { it.copy(wifi = R.string.obd_conectando, protocolo = null) }
                    val host = if (emulador) redes.gateway(red) ?: throw java.io.IOException() else "192.168.0.10"
                    // Publicar antes de connect permite cancelar incluso durante la conexión TCP.
                    val nuevo = red.socketFactory.createSocket()
                    socket = nuevo
                    redSocket = red
                    nuevo.connect(InetSocketAddress(host, 35000), 5_000)
                    nuevo.tcpNoDelay = true
                    val protocolo = runInterruptible { ClienteElm(nuevo).inicializar() }
                    actualizar { it.copy(wifi = R.string.obd_conectada, protocolo = protocolo) }
                    fallos = 0
                }
                if (terminada) { delay(1_000); continue }
                val tFila = SystemClock.elapsedRealtime()
                val cliente = ClienteElm(socket!!)
                val lento = PidsObd.lentos[turno++ % PidsObd.lentos.size]
                var todosNoData = true
                var reconectar = false
                var umbralAlcanzado = false
                for ((i, pid) in PidsObd.pids.withIndex()) {
                    if (pid !in PidsObd.rapidos && pid != lento) continue
                    val respuesta = runInterruptible { cliente.enviar(PidsObd.comando(pid), 300) }
                    val valor = PidsObd.parsear(pid, respuesta)
                    valores[i] = valor
                    val noData = respuesta.contains("NO DATA", ignoreCase = true)
                    if (pid in PidsObd.rapidos && !noData) todosNoData = false
                    fallos = if (valor != null) 0 else fallos + 1
                    if (fallos >= 3) umbralAlcanzado = true
                    // NO DATA del emulador requiere leer los FAST completos durante tres filas.
                    if (fallos >= 3 && !(emulador && noData)) { reconectar = true; break }
                }
                if (reconectar || (umbralAlcanzado && !(emulador && todosNoData))) { socket?.close(); socket = null; continue }
                val posicion = if (emulador) runInterruptible { cliente.leerGps() } else posicionCelular()
                vacias = if (emulador && todosNoData) vacias + 1 else 0
                terminada = vacias >= 3
                actualizar { it.copy(consumo = if (todosNoData) null else PidsObd.consumo(valores),
                    velocidad = valores[1], rpm = valores[0], posicion = posicion,
                    aviso = when { terminada -> R.string.obd_ruta_terminada; posicion == null -> R.string.obd_sin_gps; else -> R.string.obd_sin_avisos }) }
                if (!(emulador && todosNoData)) {
                    val fila = PaqueteObd(rid, seq++, tFila - inicio, obtenerUtc(System.currentTimeMillis()), valores.toList(), posicion)
                    // Validar antes de encolar; nunca bloquear indefinidamente la cola por un paquete inválido.
                    fila.json(OBD_TOKEN)
                    cola.agregar(fila)
                }
                delay((1_000 - (SystemClock.elapsedRealtime() - tFila)).coerceAtLeast(1))
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                runCatching { socket?.close() }; socket = null
                actualizar { it.copy(wifi = R.string.obd_wifi_error, protocolo = null) }
                delay(1_000)
            }
        }
    }

    private fun posicionCelular(): PosicionObd? {
        val fix = ultimoFix ?: return null
        val edad = (SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
        if (edad !in 0..4_999) return null
        return PosicionObd(fix.latitude, fix.longitude, if (fix.hasAltitude()) fix.altitude else null,
            if (fix.hasAccuracy()) fix.accuracy.toDouble() else null,
            if (fix.hasSpeed()) fix.speed * 3.6 else null)
    }

    private suspend fun enviar() {
        var ultimaNotificacion = 0L
        while (currentCoroutineContext().isActive) {
            redes.solicitarCelular()
            val red = redes.redEnvio()
            val fila = cola.primera()
            if (red != null && fila != null) {
                var enviado = false
                try {
                    DatagramSocket().use { udp ->
                        red.bindSocket(udp)
                        val datos = fila.json(OBD_TOKEN).toByteArray(Charsets.UTF_8)
                        for (destino in UDP_DESTINATIONS) {
                            try {
                                val direccion = red.getByName(destino.ip)
                                udp.send(DatagramPacket(datos, datos.size, direccion, destino.port))
                                enviado = true
                            } catch (_: Exception) { /* Intentar los demás destinos. */ }
                        }
                    }
                } catch (_: Exception) { /* La fila permanece hasta disponer de red. */ }
                if (enviado) {
                    cola.confirmar(fila)
                    actualizar { it.copy(enviadas = it.enviadas + 1,
                        ultimoEnvio = obtenerFechaColombia(System.currentTimeMillis()) + " " + obtenerHoraColombia(System.currentTimeMillis())) }
                } else delay(500)
            } else delay(250)
            actualizar { it.copy(movil = redes.tieneCelular(), cola = cola.cantidad(), descartadas = cola.descartadas) }
            if (SystemClock.elapsedRealtime() - ultimaNotificacion >= 1_000) {
                ultimaNotificacion = SystemClock.elapsedRealtime()
                val texto = if (estado.aviso == R.string.obd_ruta_terminada) getString(estado.aviso)
                    else getString(R.string.obd_notificacion, getString(estado.wifi), estado.enviadas, estado.cola)
                getSystemService(NotificationManager::class.java).notify(NOTIFICACION, notificacion(texto))
            }
        }
    }

    private fun notificacion(texto: String): Notification = NotificationCompat.Builder(this, CANAL)
        .setContentTitle(getString(R.string.obd_titulo)).setContentText(texto)
        .setSmallIcon(R.mipmap.ic_launcher).setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()

    override fun onDestroy() {
        activo = false
        alcance.cancel()
        runCatching { socket?.close() }
        if (::redes.isInitialized) redes.cerrar()
        ubicacion.removeLocationUpdates(callback)
        bloqueo?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
