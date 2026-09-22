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
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
// CONFIGURACIÓN DE DESTINOS UDP Y ENTORNOS
// ============================================================================
enum class EnvironmentMode {
    PRODUCTION,
    TESTING
}

data class UdpDestination(val ip: String, val port: Int)

private const val IP_1 = "34.200.45.218"
private const val IP_2 = "52.70.149.97"
private const val IP_3 = "3.224.240.185"
private const val IP_4 = "18.233.233.208"

private fun getDestinationsForMode(mode: EnvironmentMode): List<UdpDestination> = when (mode) {
    EnvironmentMode.PRODUCTION -> listOf(
        UdpDestination(IP_1, 5000)
    )
    EnvironmentMode.TESTING -> listOf(
        UdpDestination(IP_1, 5001),
        UdpDestination(IP_2, 5000),
        UdpDestination(IP_3, 5000),
        UdpDestination(IP_4, 5000)
    )
}

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
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("America/Bogota")
    }.format(Date(timestampMillis))

private fun obtenerHoraColombia(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("America/Bogota")
    }.format(Date(timestampMillis))

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var toggleGroupMode: MaterialButtonToggleGroup
    private lateinit var btnModeProduction: MaterialButton
    private lateinit var btnModeTesting: MaterialButton
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
        val ok = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) iniciarEnvioAutomatico()
        else mostrarSnackbar(getString(R.string.error_permissions_location))
    }

    private val permissionLauncherDebug = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val ok = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) enviarUbicacionManual()
        else mostrarSnackbar(getString(R.string.error_permissions_location))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        toggleGroupMode = findViewById(R.id.toggleGroupMode)
        btnModeProduction = findViewById(R.id.btnModeProduction)
        btnModeTesting = findViewById(R.id.btnModeTesting)
        btnToggleEnvio = findViewById(R.id.btnToggleEnvio)
        btnDebugSend = findViewById(R.id.btnDebugSend)
        tvLatitude = findViewById(R.id.tvLatitude)
        tvLongitude = findViewById(R.id.tvLongitude)
        tvLastSent = findViewById(R.id.tvLastSent)
        tvStatus = findViewById(R.id.tvStatus)

        val savedMode = LocationTrackingService.savedMode(this)
        toggleGroupMode.check(
            if (savedMode == EnvironmentMode.TESTING) R.id.btnModeTesting else R.id.btnModeProduction
        )

        toggleGroupMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val mode = if (checkedId == R.id.btnModeTesting) EnvironmentMode.TESTING else EnvironmentMode.PRODUCTION
                LocationTrackingService.saveSelectedMode(this, mode)
            }
        }

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
        val mode = obtenerModoSeleccionado()
        LocationTrackingService.start(this, mode)
        btnToggleEnvio.text = getString(R.string.button_stop_sending)
        actualizarHabilitacionModo(false)
    }

    private fun detenerEnvioAutomatico() {
        LocationTrackingService.stop(this)
        btnToggleEnvio.text = getString(R.string.button_start_sending)
        tvStatus.text = ""
        actualizarHabilitacionModo(true)
    }

    private fun actualizarHabilitacionModo(habilitado: Boolean) {
        toggleGroupMode.isEnabled = habilitado
        btnModeProduction.isEnabled = habilitado
        btnModeTesting.isEnabled = habilitado
    }

    private fun obtenerModoSeleccionado(): EnvironmentMode =
        if (toggleGroupMode.checkedButtonId == R.id.btnModeTesting) {
            EnvironmentMode.TESTING
        } else {
            EnvironmentMode.PRODUCTION
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
        val mode = obtenerModoSeleccionado()
        val destinos = getDestinationsForMode(mode)
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

            val payload = JSONObject().apply {
                put("lat", location.latitude)
                put("lng", location.longitude)
                put("date", date)
                put("hour", time)
            }.toString()

            val ok = enviarPorUdp(destinos, payload)
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
        actualizarHabilitacionModo(!active)

        val savedMode = LocationTrackingService.savedMode(this)
        val targetButtonId = if (savedMode == EnvironmentMode.TESTING) R.id.btnModeTesting else R.id.btnModeProduction
        if (toggleGroupMode.checkedButtonId != targetButtonId) {
            toggleGroupMode.check(targetButtonId)
        }

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
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

    private fun mostrarSnackbar(mensaje: String) {
        Snackbar.make(btnToggleEnvio, mensaje, Snackbar.LENGTH_LONG).show()
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
        const val EXTRA_MODE = "extra_mode"

        private const val CHANNEL_ID = "location_tracking"
        private const val NOTIFICATION_ID = 1001
        private const val INTERVALO_ENVIO_MS = 5_000L
        private const val PREFS_NAME = "location_tracking"
        private const val PREF_ACTIVE = "active"
        private const val PREF_MODE = "mode"
        private const val PREF_LATITUDE = "latitude"
        private const val PREF_LONGITUDE = "longitude"
        private const val PREF_LAST_SENT = "last_sent"
        private const val PREF_STATUS = "status"

        fun start(context: Context, mode: EnvironmentMode) {
            val intent = Intent(context, LocationTrackingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
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

        fun savedMode(context: Context): EnvironmentMode {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(PREF_MODE, EnvironmentMode.PRODUCTION.name)
            return try {
                EnvironmentMode.valueOf(raw ?: EnvironmentMode.PRODUCTION.name)
            } catch (_: Exception) {
                EnvironmentMode.PRODUCTION
            }
        }

        fun saveSelectedMode(context: Context, mode: EnvironmentMode) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_MODE, mode.name)
                .apply()
        }

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
    private var trackingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTracking()
            ACTION_START -> {
                val modeStr = intent.getStringExtra(EXTRA_MODE) ?: savedMode(this).name
                val mode = try {
                    EnvironmentMode.valueOf(modeStr)
                } catch (_: Exception) {
                    EnvironmentMode.PRODUCTION
                }
                startForeground(NOTIFICATION_ID, createNotification(mode))
                startTracking(mode)
            }
            else -> if (isActive(this)) {
                val mode = savedMode(this)
                startForeground(NOTIFICATION_ID, createNotification(mode))
                startTracking(mode)
            } else {
                stopSelf()
            }
        }
        return START_STICKY
    }

    // Control del ciclo automático de captura y envío.
    private fun startTracking(mode: EnvironmentMode) {
        trackingJob?.cancel()
        saveSelectedMode(this, mode)
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_ACTIVE, true)
            .apply()

        val destinos = getDestinationsForMode(mode)
        trackingJob = serviceScope.launch {
            while (isActive) {
                captureAndSend(destinos)
                delay(INTERVALO_ENVIO_MS)
            }
        }
    }

    // Captura la ubicación, construye el JSON y envía a los destinos configurados.
    @Suppress("MissingPermission")
    private suspend fun captureAndSend(destinos: List<UdpDestination>) {
        if (!hasLocationPermission()) {
            saveStatus(getString(R.string.error_permissions_location))
            return
        }

        try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .build()
            val location = fusedLocationClient.getCurrentLocation(
                request,
                CancellationTokenSource().token
            ).await() ?: run {
                saveStatus(getString(R.string.error_location_unavailable))
                return
            }

            val date = obtenerFechaColombia(location.time)
            val time = obtenerHoraColombia(location.time)
            val payload = JSONObject().apply {
                put("lat", location.latitude)
                put("lng", location.longitude)
                put("date", date)
                put("hour", time)
            }.toString()

            val sent = enviarPorUdp(destinos, payload)
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
        trackingJob?.cancel()
        trackingJob = null
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putBoolean(PREF_ACTIVE, false)
            .apply()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Validación de permisos y persistencia del estado que lee la interfaz.
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun saveStatus(status: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(PREF_STATUS, status)
            .apply()
        updateNotification(status)
    }

    // Notificación persistente que informa que el servicio está ejecutándose.
    private fun createNotification(mode: EnvironmentMode): Notification {
        val modeTitle = if (mode == EnvironmentMode.TESTING) {
            getString(R.string.mode_testing)
        } else {
            getString(R.string.mode_production)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText("${getString(R.string.notification_tracking_text)} ($modeTitle)")
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
        val modeTitle = if (savedMode(this) == EnvironmentMode.TESTING) {
            getString(R.string.mode_testing)
        } else {
            getString(R.string.mode_production)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("${getString(R.string.notification_tracking_title)} ($modeTitle)")
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
        trackingJob?.cancel()
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
                context,
                LocationTrackingService.savedMode(context)
            )
        }
    }
}
