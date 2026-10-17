package com.uninorte.locator

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ============================================================================
// CONFIGURACIÓN DE DESTINOS UDP
// ============================================================================
data class UdpDestination(val ip: String, val port: Int)

private const val IP_1 = "34.200.45.218"
private const val IP_2 = "52.70.149.97"
private const val IP_3 = "3.224.240.185"
private const val IP_4 = "18.233.233.208"

private val UDP_DESTINATIONS = listOf(
    UdpDestination(IP_1, 5000),
    UdpDestination(IP_1, 5001),
    UdpDestination(IP_2, 5000),
    UdpDestination(IP_3, 5000),
    UdpDestination(IP_4, 5000)
)

// ============================================================================
// FUNCIONES COMPARTIDAS DE FECHA Y ENVIO UDP
// ============================================================================
private suspend fun enviarPorUdp(destinos: List<UdpDestination>, payload: String): Boolean =
    withContext(Dispatchers.IO) {
        var atLeastOneSuccess = false
        try {
            DatagramSocket().use { socket ->
                val data = payload.toByteArray(Charsets.UTF_8)
                for (dest in destinos) {
                    try {
                        val address = InetAddress.getByName(dest.ip)
                        socket.send(DatagramPacket(data, data.size, address, dest.port))
                        atLeastOneSuccess = true
                    } catch (_: Exception) {
                        // Continuar con los demás destinos aunque falle uno puntual
                    }
                }
            }
            atLeastOneSuccess
        } catch (_: Exception) {
            false
        }
    }

private fun obtenerFechaColombia(timestampMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("America/Bogota")
    }.format(Date(timestampMillis))

private fun obtenerHoraColombia(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("America/Bogota")
    }.format(Date(timestampMillis))

// Arma el JSON que se envía por UDP. lat/lng/date/hour son obligatorios para todos los
// servidores del equipo; acc, spd y seq son opcionales (los servidores viejos los ignoran).
private fun construirPayload(location: Location, seq: Long? = null): String =
    JSONObject().apply {
        put("lat", location.latitude)
        put("lng", location.longitude)
        put("date", obtenerFechaColombia(location.time))
        put("hour", obtenerHoraColombia(location.time))
        if (location.hasAccuracy()) put("acc", Math.round(location.accuracy * 10) / 10.0)
        if (location.hasSpeed()) put("spd", Math.round(location.speed * 10) / 10.0)
        if (seq != null) put("seq", seq)
    }.toString()

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var btnToggleEnvio: MaterialButton
    private lateinit var btnDebugSend: MaterialButton
    private lateinit var tvLatitude: TextView
    private lateinit var tvLongitude: TextView
    private lateinit var tvLastSent: TextView
    private lateinit var tvStatus: TextView

    private var debugModeActivo = false

    private val permissionLauncherAuto = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val ok = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (ok) iniciarEnvioAutomatico()
        else mostrarErrorPermisoPreciso()
    }

    private val permissionLauncherDebug = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val ok = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (ok) enviarUbicacionManual()
        else mostrarErrorPermisoPreciso()
    }

    // Permiso de notificaciones (Android 13+). El resultado no bloquea nada: el servicio
    // funciona igual aunque se niegue; solo se pierde la notificación visible.
    private val permissionLauncherNotificaciones = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Sin importar si se concedió o no, sigue la petición de batería.
        solicitarExclusionBateriaSiHaceFalta()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        btnToggleEnvio = findViewById(R.id.btnToggleEnvio)
        btnDebugSend = findViewById(R.id.btnDebugSend)
        tvLatitude = findViewById(R.id.tvLatitude)
        tvLongitude = findViewById(R.id.tvLongitude)
        tvLastSent = findViewById(R.id.tvLastSent)
        tvStatus = findViewById(R.id.tvStatus)

        btnToggleEnvio.setOnClickListener { onBotonToggleEnvioPresionado() }
        btnDebugSend.setOnClickListener { onBotonDebugPresionado() }
        actualizarEstadoDesdeServicio()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    actualizarEstadoDesdeServicio()
                    delay(1_000L)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        actualizarEstadoDesdeServicio()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_debug) {
            debugModeActivo = !debugModeActivo
            btnDebugSend.visibility = if (debugModeActivo) View.VISIBLE else View.GONE
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun onBotonToggleEnvioPresionado() {
        if (LocationTrackingService.isActive(this)) {
            detenerEnvioAutomatico()
            return
        }
        if (!validarUbicacion()) return

        if (tienePermisos()) {
            iniciarEnvioAutomatico()
        } else {
            permissionLauncherAuto.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun iniciarEnvioAutomatico() {
        LocationTrackingService.start(this)
        btnToggleEnvio.text = getString(R.string.button_stop_sending)
        solicitarPermisoNotificacionesSiHaceFalta()
    }

    private fun solicitarPermisoNotificacionesSiHaceFalta() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncherNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            solicitarExclusionBateriaSiHaceFalta()
        }
    }

    // Pide una sola vez la exclusión de optimización de batería, para que el sistema
    // no limite el servicio con la pantalla apagada o el celular en el bolsillo.
    private fun solicitarExclusionBateriaSiHaceFalta() {
        val powerManager = getSystemService(PowerManager::class.java)
        val preferencias = getSharedPreferences("app_settings", MODE_PRIVATE)
        if (powerManager.isIgnoringBatteryOptimizations(packageName) ||
            preferencias.getBoolean("bateria_solicitada", false)
        ) {
            return
        }
        preferencias.edit().putBoolean("bateria_solicitada", true).apply()
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun detenerEnvioAutomatico() {
        LocationTrackingService.stop(this)
        btnToggleEnvio.text = getString(R.string.button_start_sending)
        tvStatus.text = ""
    }

    private fun onBotonDebugPresionado() {
        if (!validarUbicacion()) return

        if (tienePermisos()) {
            enviarUbicacionManual()
        } else {
            permissionLauncherDebug.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun enviarUbicacionManual() {
        lifecycleScope.launch {
            capturarYEnviarUbicacion()
        }
    }

    @Suppress("MissingPermission")
    private suspend fun capturarYEnviarUbicacion() {
        try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .build()
            val location = fusedLocationClient.getCurrentLocation(
                request,
                CancellationTokenSource().token
            ).await()

            if (location == null) {
                mostrarSnackbar(getString(R.string.error_location_unavailable))
                return
            }

            val date = obtenerFechaColombia(location.time)
            val time = obtenerHoraColombia(location.time)
            tvLatitude.text = getString(R.string.latitude_value, location.latitude)
            tvLongitude.text = getString(R.string.longitude_value, location.longitude)

            val payload = construirPayload(location)

            val ok = enviarPorUdp(UDP_DESTINATIONS, payload)
            val resultado = if (ok) {
                getString(R.string.status_send_ok)
            } else {
                getString(R.string.status_send_failed)
            }
            mostrarSnackbar(resultado)
            if (ok) tvLastSent.text = getString(R.string.last_sent_value, "$date $time")
        } catch (e: Exception) {
            mostrarSnackbar(
                getString(
                    R.string.error_location_with_detail,
                    e.message ?: getString(R.string.error_unknown)
                )
            )
        }
    }

    private fun actualizarEstadoDesdeServicio() {
        if (!::btnToggleEnvio.isInitialized) return
        val active = LocationTrackingService.isActive(this)
        btnToggleEnvio.text = getString(
            if (active) R.string.button_stop_sending else R.string.button_start_sending
        )

        val state = LocationTrackingService.savedState(this)
        state.latitude?.let { tvLatitude.text = getString(R.string.latitude_value, it) }
        state.longitude?.let { tvLongitude.text = getString(R.string.longitude_value, it) }
        state.lastSent?.let { tvLastSent.text = getString(R.string.last_sent_value, it) }
        state.status?.let { tvStatus.text = it }
    }

    private fun validarUbicacion(): Boolean {
        if (!isLocationEnabled()) {
            mostrarSnackbar(getString(R.string.error_location_disabled))
            return false
        }
        return true
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun tienePermisos(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun mostrarSnackbar(mensaje: String) {
        Snackbar.make(btnToggleEnvio, mensaje, Snackbar.LENGTH_LONG).show()
    }

    // Se muestra cuando el usuario no concedió ubicación precisa. El botón abre los ajustes de la app.
    private fun mostrarErrorPermisoPreciso() {
        Snackbar.make(btnToggleEnvio, getString(R.string.error_permissions_fine), Snackbar.LENGTH_LONG)
            .setAction(getString(R.string.action_open_settings)) {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                )
            }
            .show()
    }
}

// ============================================================================
// SERVICIO DE ENVIO AUTOMATICO EN SEGUNDO PLANO
// ============================================================================
// Este servicio mantiene activo el seguimiento aunque MainActivity se destruya,
// la aplicación quede en segundo plano o la pantalla se bloquee.
class LocationTrackingService : Service() {

    companion object {
        const val ACTION_START = "com.uninorte.locator.action.START"
        const val ACTION_STOP = "com.uninorte.locator.action.STOP"

        private const val CHANNEL_ID = "location_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val INTERVALO_ENVIO_MS = 5_000L
        private const val INTERVALO_MIN_MS = 4_000L
        private const val MAX_PRECISION_M = 40f
        private const val MAX_ANTIGUEDAD_FIX_MS = 10_000L
        private const val PREFS_NAME = "location_tracking"
        private const val PREF_ACTIVE = "active"
        private const val PREF_LATITUDE = "latitude"
        private const val PREF_LONGITUDE = "longitude"
        private const val PREF_LAST_SENT = "last_sent"
        private const val PREF_STATUS = "status"
        private const val PREF_SEQ = "seq"

        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LocationTrackingService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        fun isActive(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_ACTIVE, false)

        fun savedState(context: Context): TrackingState {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return TrackingState(
                latitude = preferences.getString(PREF_LATITUDE, null)?.toDoubleOrNull(),
                longitude = preferences.getString(PREF_LONGITUDE, null)?.toDoubleOrNull(),
                lastSent = preferences.getString(PREF_LAST_SENT, null),
                status = preferences.getString(PREF_STATUS, null)
            )
        }
    }

    data class TrackingState(
        val latitude: Double?,
        val longitude: Double?,
        val lastSent: String?,
        val status: String?
    )

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ultimoFixNanos = 0L

    // Android entrega cada fix nuevo una sola vez a este callback (corre en el hilo principal).
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            serviceScope.launch {
                for (location in result.locations) {
                    procesarUbicacion(location)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                startTracking()
            }
            else -> if (isActive(this)) {
                startForeground(NOTIFICATION_ID, createNotification())
                startTracking()
            } else {
                stopSelf()
            }
        }
        return START_STICKY
    }

    // Registra las actualizaciones continuas de ubicación.
    @Suppress("MissingPermission")
    private fun startTracking() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_ACTIVE, true)
            .apply()
        detenerActualizaciones()

        if (!hasLocationPermission()) {
            saveStatus(getString(R.string.error_permissions_location))
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVALO_ENVIO_MS)
            .setMinUpdateIntervalMillis(INTERVALO_MIN_MS)
            .setWaitForAccurateLocation(true)
            .build()
        try {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            saveStatus(getString(R.string.error_permissions_location))
        }
    }

    private fun detenerActualizaciones() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    // Contador persistente de paquetes enviados (permite medir pérdida de UDP en el servidor).
    private fun siguienteSeq(): Long {
        val preferencias = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val siguiente = preferencias.getLong(PREF_SEQ, 0L) + 1
        preferencias.edit().putLong(PREF_SEQ, siguiente).apply()
        return siguiente
    }

    // Envía por UDP un fix recibido del callback y guarda el estado que lee la interfaz.
    private suspend fun procesarUbicacion(location: Location) {
        try {
            // Fix repetido (el mismo que ya se procesó)
            if (location.elapsedRealtimeNanos == ultimoFixNanos) return
            ultimoFixNanos = location.elapsedRealtimeNanos

            // Precisión mala: se descarta (si no trae precisión, se deja pasar)
            if (location.hasAccuracy() && location.accuracy > MAX_PRECISION_M) {
                saveStatus(getString(R.string.status_discarded_accuracy, location.accuracy.toInt()))
                return
            }

            // Fix antiguo
            val antiguedadMs =
                (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
            if (antiguedadMs > MAX_ANTIGUEDAD_FIX_MS) {
                saveStatus(getString(R.string.status_discarded_stale, (antiguedadMs / 1000).toInt()))
                return
            }

            val date = obtenerFechaColombia(location.time)
            val time = obtenerHoraColombia(location.time)
            val payload = construirPayload(location, siguienteSeq())

            val sent = enviarPorUdp(UDP_DESTINATIONS, payload)
            val status = if (sent) {
                getString(R.string.status_send_ok)
            } else {
                getString(R.string.status_send_failed)
            }
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_LATITUDE, location.latitude.toString())
                .putString(PREF_LONGITUDE, location.longitude.toString())
                .putString(PREF_STATUS, status)
                .apply()
            if (sent) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(PREF_LAST_SENT, "$date $time")
                    .apply()
            }
            updateNotification(status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saveStatus(
                getString(
                    R.string.error_location_with_detail,
                    e.message ?: getString(R.string.error_unknown)
                )
            )
        }
    }

    private fun stopTracking() {
        detenerActualizaciones()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_ACTIVE, false)
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Validación de permisos y persistencia del estado que lee la interfaz.
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun saveStatus(status: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_STATUS, status)
            .apply()
        updateNotification(status)
    }

    // Notificación persistente que informa que el servicio está ejecutándose.
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText(getString(R.string.notification_tracking_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(status: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        detenerActualizaciones()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// ============================================================================
// REANUDACION DESPUES DE REINICIAR EL DISPOSITIVO
// ============================================================================
// Si el envío estaba activo antes del reinicio, este receptor vuelve a iniciar
// el servicio cuando Android termina de arrancar con el modo configurado.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED &&
            LocationTrackingService.isActive(context)
        ) {
            LocationTrackingService.start(
                context
            )
        }
    }
}
