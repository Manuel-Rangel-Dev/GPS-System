package com.uninorte.locator

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    companion object {
        // Mismo puerto para TCP y UDP (regla de forwarding TCP/UDP combinada en el router)
        private const val PUERTO = 5000
        private const val TIMEOUT_CONEXION_MS = 3000
    }

    // Cliente de Google Play Services para obtener ubicación
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Referencias a las vistas
    private lateinit var etIpAddress: TextInputEditText
    private lateinit var btnSendLocation: MaterialButton
    private lateinit var tvLatitude: TextView
    private lateinit var tvLongitude: TextView
    private lateinit var tvLastSent: TextView
    private lateinit var tvStatus: TextView

    // -----------------------------------------------------------------
    // Lanzador de permisos: solo ubicación (ya no se pide SEND_SMS)
    // -----------------------------------------------------------------
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (locationGranted) {
            iniciarCapturaYEnvio()
        } else {
            mostrarSnackbar(getString(R.string.error_permissions_location))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        etIpAddress = findViewById(R.id.etIpAddress)
        btnSendLocation = findViewById(R.id.btnSendLocation)
        tvLatitude = findViewById(R.id.tvLatitude)
        tvLongitude = findViewById(R.id.tvLongitude)
        tvLastSent = findViewById(R.id.tvLastSent)
        tvStatus = findViewById(R.id.tvStatus)

        btnSendLocation.setOnClickListener {
            onBotonEnviarPresionado()
        }
    }

    // -----------------------------------------------------------------
    // Punto de entrada al presionar el botón: valida datos, luego
    // verifica permisos, y si todo está en orden, captura y envía.
    // -----------------------------------------------------------------
    private fun onBotonEnviarPresionado() {
        val ip = etIpAddress.text?.toString()?.trim()

        if (ip.isNullOrEmpty()) {
            mostrarSnackbar(getString(R.string.error_ip_empty))
            return
        }

        if (!esIpv4Valida(ip)) {
            mostrarSnackbar(getString(R.string.error_ip_format))
            return
        }

        if (!isLocationEnabled()) {
            mostrarSnackbar(getString(R.string.error_location_disabled))
            return
        }

        if (tienePermisos()) {
            iniciarCapturaYEnvio()
        } else {
            solicitarPermisos()
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun tienePermisos(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocation = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineLocation || coarseLocation
    }

    private fun solicitarPermisos() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    // -----------------------------------------------------------------
    // Captura la ubicación actual y, si tiene éxito, envía por
    // TCP y UDP en paralelo hacia la IP ingresada.
    // -----------------------------------------------------------------
    @Suppress("MissingPermission") // Ya validamos permisos antes de llegar aquí
    private fun iniciarCapturaYEnvio() {
        tvStatus.text = getString(R.string.status_getting_location)
        btnSendLocation.isEnabled = false

        val cancellationTokenSource = com.google.android.gms.tasks.CancellationTokenSource()

        lifecycleScope.launch {
            try {
                val currentLocationRequest = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .build()

                val location = fusedLocationClient.getCurrentLocation(
                    currentLocationRequest,
                    cancellationTokenSource.token
                ).await()

                if (location == null) {
                    tvStatus.text = ""
                    btnSendLocation.isEnabled = true
                    mostrarSnackbar(getString(R.string.error_location_unavailable))
                    return@launch
                }

                val lat = location.latitude
                val lng = location.longitude
                val timestampUtc = obtenerTimestampUbicacion(location.time, "UTC")
                val timestampColombia = obtenerTimestampUbicacion(location.time, "America/Bogota")

                tvLatitude.text = getString(R.string.latitude_value, lat)
                tvLongitude.text = getString(R.string.longitude_value, lng)

                val ip = etIpAddress.text.toString().trim()
                val mensaje = construirMensaje(lat, lng, timestampUtc, timestampColombia)

                tvStatus.text = getString(R.string.status_sending)

                // TCP y UDP se envían en paralelo, cada uno con su propio resultado
                val tcpDeferred = async { enviarPorTcp(ip, mensaje) }
                val udpDeferred = async { enviarPorUdp(ip, mensaje) }
                val tcpOk = tcpDeferred.await()
                val udpOk = udpDeferred.await()

                val resultado = getString(
                    R.string.status_send_result,
                    if (tcpOk) getString(R.string.status_ok) else getString(R.string.status_failed),
                    if (udpOk) getString(R.string.status_ok) else getString(R.string.status_failed)
                )
                tvStatus.text = resultado
                mostrarSnackbar(resultado)

                if (tcpOk || udpOk) {
                    tvLastSent.text = getString(R.string.last_sent_value, timestampColombia)
                }

            } catch (e: Exception) {
                tvStatus.text = ""
                mostrarSnackbar(getString(R.string.error_location_with_detail, e.message ?: getString(R.string.error_unknown)))
            } finally {
                btnSendLocation.isEnabled = true
            }
        }
    }

    // -----------------------------------------------------------------
    // Envío TCP: abre conexión, escribe el mensaje y cierra.
    // -----------------------------------------------------------------
    private suspend fun enviarPorTcp(ip: String, mensaje: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, PUERTO), TIMEOUT_CONEXION_MS)
                socket.getOutputStream().write(mensaje.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // -----------------------------------------------------------------
    // Envío UDP: arma el datagrama y lo manda sin confirmación.
    // -----------------------------------------------------------------
    private suspend fun enviarPorUdp(ip: String, mensaje: String): Boolean = withContext(Dispatchers.IO) {
        try {
            DatagramSocket().use { socket ->
                val direccion = InetAddress.getByName(ip)
                val datos = mensaje.toByteArray(Charsets.UTF_8)
                val paquete = DatagramPacket(datos, datos.size, direccion, PUERTO)
                socket.send(paquete)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun obtenerTimestampUbicacion(timestampMillis: Long, timeZoneId: String): String {
        val formato = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        formato.timeZone = TimeZone.getTimeZone(timeZoneId)
        return "${formato.format(Date(timestampMillis))}000"
    }

    private fun construirMensaje(
        lat: Double,
        lng: Double,
        timestampUtc: String,
        timestampColombia: String
    ): String {
        return "Latitud: %.6f\nLongitud: %.6f\nHora UTC: %s\nHora Colombia: %s"
            .format(Locale.US, lat, lng, timestampUtc, timestampColombia)
    }

    private fun esIpv4Valida(ip: String): Boolean {
        val regex = Regex(
            "^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])" +
                    "(\\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}$"
        )
        return regex.matches(ip)
    }

    private fun mostrarSnackbar(mensaje: String) {
        Snackbar.make(btnSendLocation, mensaje, Snackbar.LENGTH_LONG).show()
    }
}