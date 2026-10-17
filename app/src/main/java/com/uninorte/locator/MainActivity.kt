package com.uninorte.locator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

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
            val cancelacion = CancellationTokenSource()
            val location = try {
                fusedLocationClient.getCurrentLocation(request, cancelacion.token).await()
            } finally {
                cancelacion.cancel()
            }

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
