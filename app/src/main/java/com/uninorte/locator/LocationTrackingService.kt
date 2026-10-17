package com.uninorte.locator

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

        fun markInactive(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_ACTIVE, false)
                .apply()
        }

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
    private var wakeLock: PowerManager.WakeLock? = null

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
                if (!entrarEnPrimerPlano()) return START_NOT_STICKY
                startTracking()
            }
            else -> if (isActive(this)) {
                if (!entrarEnPrimerPlano()) return START_NOT_STICKY
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
        adquirirWakeLock()

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

    // Entra en primer plano; si el sistema lo impide, deja el seguimiento inactivo y se detiene.
    private fun entrarEnPrimerPlano(): Boolean = try {
        startForeground(NOTIFICATION_ID, createNotification())
        true
    } catch (e: Exception) {
        markInactive(this)
        stopSelf()
        false
    }

    private fun adquirirWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uninorte:tracking").apply {
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun liberarWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
        liberarWakeLock()
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
        liberarWakeLock()
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
