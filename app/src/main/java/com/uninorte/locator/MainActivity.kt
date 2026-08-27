package com.uninorte.locator

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PUERTO_UDP = 5000
        private const val INTERVALO_ENVIO_MS = 10_000L
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var etIpAddress: TextInputEditText
    private lateinit var btnToggleEnvio: MaterialButton
    private lateinit var btnDebugSend: MaterialButton
    private lateinit var tvLatitude: TextView
    private lateinit var tvLongitude: TextView
    private lateinit var tvLastSent: TextView
    private lateinit var tvStatus: TextView

    private var envioAutomaticoJob: Job? = null
    private var debugModeActivo = false
    private var enviando = false

    private val permissionLauncherAuto = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val ok = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) iniciarEnvioAutomatico() else mostrarSnackbar(getString(R.string.error_permissions_location))
    }

    private val permissionLauncherDebug = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val ok = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) enviarUbicacionManual() else mostrarSnackbar(getString(R.string.error_permissions_location))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        etIpAddress = findViewById(R.id.etIpAddress)
        btnToggleEnvio = findViewById(R.id.btnToggleEnvio)
        btnDebugSend = findViewById(R.id.btnDebugSend)
        tvLatitude = findViewById(R.id.tvLatitude)
        tvLongitude = findViewById(R.id.tvLongitude)
        tvLastSent = findViewById(R.id.tvLastSent)
        tvStatus = findViewById(R.id.tvStatus)

        btnToggleEnvio.setOnClickListener { onBotonToggleEnvioPresionado() }
        btnDebugSend.setOnClickListener { onBotonDebugPresionado() }
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

    // ---------- Envío automático (ciclo de 10s) ----------

    private fun onBotonToggleEnvioPresionado() {
        if (enviando) {
            detenerEnvioAutomatico()
            return
        }
        if (!validarIpYUbicacion()) return

        if (tienePermisos()) {
            iniciarEnvioAutomatico()
        } else {
            permissionLauncherAuto.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun iniciarEnvioAutomatico() {
        enviando = true
        btnToggleEnvio.text = getString(R.string.button_stop_sending)

        envioAutomaticoJob = lifecycleScope.launch {
            while (isActive) {
                capturarYEnviarUbicacion(esDebug = false)
                delay(INTERVALO_ENVIO_MS)
            }
        }
    }

    private fun detenerEnvioAutomatico() {
        envioAutomaticoJob?.cancel()
        envioAutomaticoJob = null
        enviando = false
        btnToggleEnvio.text = getString(R.string.button_start_sending)
        tvStatus.text = ""
    }

    // ---------- Envío manual (modo Debug) ----------

    private fun onBotonDebugPresionado() {
        if (!validarIpYUbicacion()) return

        if (tienePermisos()) {
            enviarUbicacionManual()
        } else {
            permissionLauncherDebug.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun enviarUbicacionManual() {
        lifecycleScope.launch { capturarYEnviarUbicacion(esDebug = true) }
    }

    // ---------- Lógica común de captura + envío UDP ----------

    @Suppress("MissingPermission")
    private suspend fun capturarYEnviarUbicacion(esDebug: Boolean) {
        val ip = etIpAddress.text?.toString()?.trim() ?: return
        if (!esDebug) tvStatus.text = getString(R.string.status_getting_location)

        try {
            val request = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .build()

            val location = fusedLocationClient.getCurrentLocation(
                request, CancellationTokenSource().token
            ).await()

            if (location == null) {
                if (!esDebug) tvStatus.text = ""
                mostrarSnackbar(getString(R.string.error_location_unavailable))
                return
            }

            val lat = location.latitude
            val lng = location.longitude
            val fecha = obtenerFechaColombia(location.time)
            val hora = obtenerHoraColombia(location.time)

            tvLatitude.text = getString(R.string.latitude_value, lat)
            tvLongitude.text = getString(R.string.longitude_value, lng)
            if (!esDebug) tvStatus.text = getString(R.string.status_sending)

            val payload = construirPayloadJson(lat, lng, fecha, hora)
            val ok = enviarPorUdp(ip, payload)

            val resultado = if (ok) getString(R.string.status_send_ok) else getString(R.string.status_send_failed)
            if (!esDebug) tvStatus.text = resultado
            mostrarSnackbar(resultado)

            if (ok) tvLastSent.text = getString(R.string.last_sent_value, "$fecha $hora")

        } catch (e: Exception) {
            if (!esDebug) tvStatus.text = ""
            mostrarSnackbar(getString(R.string.error_location_with_detail, e.message ?: getString(R.string.error_unknown)))
        }
    }

    private suspend fun enviarPorUdp(ip: String, jsonPayload: String): Boolean = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                val direccion = InetAddress.getByName(ip)
                val datos = jsonPayload.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(datos, datos.size, direccion, PUERTO_UDP))
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun construirPayloadJson(lat: Double, lng: Double, fecha: String, hora: String): String {
        val json = JSONObject()
        json.put("lat", lat)
        json.put("lng", lng)
        json.put("date", fecha)
        json.put("hour", hora)
        return json.toString()
    }

    private fun obtenerFechaColombia(timestampMillis: Long): String {
        val formato = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        formato.timeZone = TimeZone.getTimeZone("America/Bogota")
        return formato.format(Date(timestampMillis))
    }

    private fun obtenerHoraColombia(timestampMillis: Long): String {
        val formato = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        formato.timeZone = TimeZone.getTimeZone("America/Bogota")
        return formato.format(Date(timestampMillis))
    }

    private fun validarIpYUbicacion(): Boolean {
        val ip = etIpAddress.text?.toString()?.trim()
        if (ip.isNullOrEmpty()) {
            mostrarSnackbar(getString(R.string.error_ip_empty)); return false
        }
        if (!esIpv4Valida(ip)) {
            mostrarSnackbar(getString(R.string.error_ip_format)); return false
        }
        if (!isLocationEnabled()) {
            mostrarSnackbar(getString(R.string.error_location_disabled)); return false
        }
        return true
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun tienePermisos(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun esIpv4Valida(ip: String): Boolean {
        val regex = Regex(
            "^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])" +
                    "(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$"
        )
        return regex.matches(ip)
    }

    private fun mostrarSnackbar(mensaje: String) {
        Snackbar.make(btnToggleEnvio, mensaje, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        envioAutomaticoJob?.cancel()
    }
}