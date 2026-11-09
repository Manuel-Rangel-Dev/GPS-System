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
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.uninorte.locator.obd.ObdTrackingService
import java.util.Locale
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

    private var envioManualActivo = false
    private var inicioPendiente = false
    private lateinit var selectorVehiculo: MaterialButtonToggleGroup
    private lateinit var modoEmulador: SwitchMaterial
    private lateinit var redEmulador: EditText
    private lateinit var claveEmulador: EditText
    private var debugModeActivo = false

    private val permissionLauncherAuto = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val ok = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!ok) mostrarErrorPermisoPreciso()
        else if (tienePermisoRedLocal()) iniciarSeleccionado()
        else mostrarSnackbar(getString(R.string.obd_permiso_red))
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
        configurarVehiculos()
        actualizarEstadoDesdeServicio()

        findViewById<MaterialButton>(R.id.btnPlanificar).setOnClickListener {
            startActivity(Intent(this, PlanificarRutaActivity::class.java))
        }

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
            actualizarPanelVehiculo()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun onBotonToggleEnvioPresionado() {
        if (inicioPendiente) return
        if (LocationTrackingService.isActive(this)) {
            detenerEnvioAutomatico()
            return
        }
        if (!validarUbicacion()) return

        if (tienePermisos() && tienePermisoRedLocal()) {
            iniciarSeleccionado()
        } else {
            permissionLauncherAuto.launch(
                permisosRecorrido()
            )
        }
    }

    private fun permisosRecorrido(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 37 && Vehiculo.obtener(this@MainActivity) == Vehiculo.KIA_K3)
            add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }.toTypedArray()

    private fun tienePermisoRedLocal(): Boolean =
        Build.VERSION.SDK_INT < 37 || Vehiculo.obtener(this) != Vehiculo.KIA_K3 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun iniciarSeleccionado() {
        if (Vehiculo.obtener(this) == Vehiculo.KIA_K3) {
            if (!validarRedEmulador()) return
            inicioPendiente = true
            try { ObdTrackingService.iniciar(this) }
            catch (_: Exception) { inicioPendiente = false; mostrarSnackbar(getString(R.string.obd_error_inicio)) }
            solicitarPermisoNotificacionesSiHaceFalta()
        } else iniciarEnvioAutomatico()
        actualizarPanelVehiculo()
        btnToggleEnvio.postDelayed({ inicioPendiente = false; actualizarPanelVehiculo() }, 5_000)
    }

    private fun iniciarEnvioAutomatico() {
        if (ObdTrackingService.activo) return
        inicioPendiente = true
        try { LocationTrackingService.start(this) }
        catch (_: Exception) { inicioPendiente = false; mostrarSnackbar(getString(R.string.obd_error_inicio)) }
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
        if (Vehiculo.obtener(this) != Vehiculo.VEHICULO_2 || ObdTrackingService.activo || envioManualActivo) return
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
        if (Vehiculo.obtener(this) != Vehiculo.VEHICULO_2 || ObdTrackingService.activo || envioManualActivo) return
        envioManualActivo = true
        actualizarPanelVehiculo()
        lifecycleScope.launch {
            try { capturarYEnviarUbicacion() }
            finally { envioManualActivo = false; actualizarPanelVehiculo() }
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

            val payload = construirPayload(location, vid = VID_CELULAR)

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
        actualizarPanelVehiculo()
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

    private fun configurarVehiculos() {
        selectorVehiculo = findViewById(R.id.selectorVehiculo)
        modoEmulador = findViewById(R.id.modoEmulador)
        redEmulador = findViewById(R.id.redEmulador)
        claveEmulador = findViewById(R.id.claveEmulador)
        val preferencias = getSharedPreferences("app_settings", MODE_PRIVATE)
        // Un servicio activo prevalece sobre una preferencia restaurada por Android.
        if (LocationTrackingService.isActive(this)) Vehiculo.guardar(this, Vehiculo.VEHICULO_2)
        if (ObdTrackingService.activo) Vehiculo.guardar(this, Vehiculo.KIA_K3)
        selectorVehiculo.check(if (Vehiculo.obtener(this) == Vehiculo.KIA_K3) R.id.vehiculoKia else R.id.vehiculoDos)
        modoEmulador.isChecked = preferencias.getBoolean("obd_emulador", false)
        redEmulador.setText(preferencias.getString("obd_ssid", "EMU_OBDII"))
        claveEmulador.setText(preferencias.getString("obd_clave", ""))
        selectorVehiculo.addOnButtonCheckedListener { _, id, seleccionado ->
            if (seleccionado) {
                Vehiculo.guardar(this, if (id == R.id.vehiculoKia) Vehiculo.KIA_K3 else Vehiculo.VEHICULO_2)
                actualizarPanelVehiculo()
                invalidateOptionsMenu()
            }
        }
        modoEmulador.setOnCheckedChangeListener { _, marcado ->
            preferencias.edit().putBoolean("obd_emulador", marcado).apply()
            actualizarPanelVehiculo()
        }
        redEmulador.doAfterTextChanged { preferencias.edit().putString("obd_ssid", it.toString()).apply() }
        claveEmulador.doAfterTextChanged { preferencias.edit().putString("obd_clave", it.toString()).apply() }
        findViewById<MaterialButton>(R.id.btnRecorrido).setOnClickListener {
            if (ObdTrackingService.activo) {
                val pendientes = ObdTrackingService.estado.cola
                if (pendientes > 0) MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.obd_cola_finalizar, pendientes))
                    .setPositiveButton(R.string.obd_confirmar_finalizar) { _, _ -> ObdTrackingService.detener(this) }
                    .setNegativeButton(R.string.obd_continuar, null).show()
                else ObdTrackingService.detener(this)
            } else if (!LocationTrackingService.isActive(this) && !envioManualActivo) {
                if (validarRedEmulador()) onBotonToggleEnvioPresionado()
            }
        }
    }

    private fun validarRedEmulador(): Boolean {
        if (!modoEmulador.isChecked) return true
        val ssid = redEmulador.text.toString()
        val clave = claveEmulador.text.toString()
        val valido = ssid.toByteArray(Charsets.UTF_8).size in 1..32 &&
            (clave.isEmpty() || (clave.length in 8..63 && clave.all { it.code in 32..126 }))
        if (!valido) mostrarSnackbar(getString(R.string.obd_red_invalida))
        return valido
    }

    private fun actualizarPanelVehiculo() {
        if (!::selectorVehiculo.isInitialized) return
        val kia = Vehiculo.obtener(this) == Vehiculo.KIA_K3
        val activo = ObdTrackingService.activo
        if (activo || LocationTrackingService.isActive(this)) inicioPendiente = false
        val bloqueado = activo || LocationTrackingService.isActive(this) || envioManualActivo || inicioPendiente
        findViewById<View>(R.id.vehiculoKia).isEnabled = !bloqueado
        findViewById<View>(R.id.vehiculoDos).isEnabled = !bloqueado
        findViewById<View>(R.id.avisoSelector).visibility = if (bloqueado) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panelKia).visibility = if (kia) View.VISIBLE else View.GONE
        listOf(R.id.tvSubtitle, R.id.btnToggleEnvio, R.id.cardResult, R.id.tvStatus).forEach {
            findViewById<View>(it).visibility = if (kia) View.GONE else View.VISIBLE
        }
        btnDebugSend.visibility = if (!kia && debugModeActivo) View.VISIBLE else View.GONE
        btnDebugSend.isEnabled = !envioManualActivo
        modoEmulador.isEnabled = !activo && !inicioPendiente
        redEmulador.isEnabled = !activo && !inicioPendiente
        claveEmulador.isEnabled = !activo && !inicioPendiente
        redEmulador.visibility = if (modoEmulador.isChecked) View.VISIBLE else View.GONE
        claveEmulador.visibility = redEmulador.visibility
        findViewById<MaterialButton>(R.id.btnRecorrido).setText(if (activo) R.string.obd_finalizar else R.string.obd_iniciar)
        findViewById<TextView>(R.id.instruccionObd).text = if (Build.VERSION.SDK_INT < 29)
            getString(R.string.obd_instruccion_manual, if (modoEmulador.isChecked) redEmulador.text else "WIFI_OBDII")
            else getString(R.string.obd_instruccion)
        val estado = ObdTrackingService.estado
        val vacio = getString(R.string.obd_vacio)
        fun numero(valor: Double?) = valor?.let { String.format(Locale.getDefault(), "%.3f", it) } ?: vacio
        findViewById<TextView>(R.id.estadoObd).text = getString(R.string.obd_panel,
            getString(estado.wifi), estado.protocolo ?: getString(R.string.obd_sin_respuesta),
            getString(if (estado.movil) R.string.obd_disponible else R.string.obd_no_disponible),
            estado.enviadas, estado.cola, estado.descartadas, estado.ultimoEnvio ?: vacio,
            numero(estado.consumo), numero(estado.velocidad), numero(estado.rpm),
            estado.posicion?.let { getString(R.string.obd_posicion, it.lat, it.lng) } ?: vacio,
            numero(estado.posicion?.precision), getString(estado.aviso))
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_debug)?.isVisible = Vehiculo.obtener(this) == Vehiculo.VEHICULO_2
        return super.onPrepareOptionsMenu(menu)
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
