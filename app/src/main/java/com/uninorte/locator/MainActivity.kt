package com.uninorte.locator

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_SMS_SENT = "com.uninorte.locator.SMS_SENT"
        private const val ACTION_SMS_DELIVERED = "com.uninorte.locator.SMS_DELIVERED"
    }

    // Cliente de Google Play Services para obtener ubicación
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Referencias a las vistas (sin View Binding, para mantenerlo simple y explícito)
    private lateinit var etPhoneNumber: TextInputEditText
    private lateinit var btnSendLocation: MaterialButton
    private lateinit var tvLatitude: TextView
    private lateinit var tvLongitude: TextView
    private lateinit var tvLastSent: TextView
    private lateinit var tvStatus: TextView

    private val smsSentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val mensaje = when (resultCode) {
                Activity.RESULT_OK -> getString(R.string.status_sms_sent_to_operator)
                SmsManager.RESULT_ERROR_GENERIC_FAILURE -> getString(R.string.error_sms_generic)
                SmsManager.RESULT_ERROR_NO_SERVICE -> getString(R.string.error_sms_no_service)
                SmsManager.RESULT_ERROR_NULL_PDU -> getString(R.string.error_sms_null_pdu)
                SmsManager.RESULT_ERROR_RADIO_OFF -> getString(R.string.error_sms_radio_off)
                else -> getString(R.string.error_sms_with_code, resultCode)
            }
            tvStatus.text = mensaje
            mostrarSnackbar(mensaje)
        }
    }

    private val smsDeliveredReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val mensaje = if (resultCode == Activity.RESULT_OK) {
                getString(R.string.status_sms_delivered)
            } else {
                getString(R.string.status_sms_not_delivered)
            }
            tvStatus.text = mensaje
            mostrarSnackbar(mensaje)
        }
    }

    // -----------------------------------------------------------------
    // Lanzador de permisos: pedimos UBICACIÓN y SMS al mismo tiempo.
    // El resultado llega como un mapa <permiso, concedido true/false>.
    // -----------------------------------------------------------------
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val smsGranted = permissions[Manifest.permission.SEND_SMS] == true

        if (locationGranted && smsGranted) {
            // Ambos permisos concedidos: procedemos con el flujo normal
            iniciarCapturaYEnvio()
        } else {
            // Informamos claramente cuál permiso falta
            val mensaje = when {
                !locationGranted && !smsGranted -> getString(R.string.error_permissions_location_sms)
                !locationGranted -> getString(R.string.error_permission_location)
                else -> getString(R.string.error_permission_sms)
            }
            mostrarSnackbar(mensaje)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializamos el cliente de ubicación
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Enlazamos las vistas por ID
        etPhoneNumber = findViewById(R.id.etPhoneNumber)
        btnSendLocation = findViewById(R.id.btnSendLocation)
        tvLatitude = findViewById(R.id.tvLatitude)
        tvLongitude = findViewById(R.id.tvLongitude)
        tvLastSent = findViewById(R.id.tvLastSent)
        tvStatus = findViewById(R.id.tvStatus)
        registrarReceiversSms()

        btnSendLocation.setOnClickListener {
            onBotonEnviarPresionado()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(smsSentReceiver)
        unregisterReceiver(smsDeliveredReceiver)
        super.onDestroy()
    }

    // -----------------------------------------------------------------
    // Punto de entrada al presionar el botón: valida datos, luego
    // verifica permisos, y si todo está en orden, captura y envía.
    // -----------------------------------------------------------------
    private fun onBotonEnviarPresionado() {
        val numero = normalizarNumero(etPhoneNumber.text?.toString())

        // Validación: número no vacío
        if (numero == null) {
            mostrarSnackbar(getString(R.string.error_phone_empty))
            return
        }

        // Validación: número colombiano en formato internacional (+57 seguido de 10 dígitos)
        if (!esNumeroColombianoInternacional(numero)) {
            mostrarSnackbar(getString(R.string.error_phone_format))
            return
        }

        // Validación: GPS/ubicación por red activa en el dispositivo
        if (!isLocationEnabled()) {
            mostrarSnackbar(getString(R.string.error_location_disabled))
            return
        }

        // Verificamos permisos antes de continuar
        if (tienePermisos()) {
            iniciarCapturaYEnvio()
        } else {
            solicitarPermisos()
        }
    }

    // -----------------------------------------------------------------
    // Verifica si el proveedor de ubicación (GPS o red) está activo
    // -----------------------------------------------------------------
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

        val sms = ContextCompat.checkSelfPermission(
            this, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        return (fineLocation || coarseLocation) && sms
    }

    private fun solicitarPermisos() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.SEND_SMS
            )
        )
    }

    // -----------------------------------------------------------------
    // Captura la ubicación actual (una sola vez, no continua) y,
    // si tiene éxito, construye y envía el SMS.
    // -----------------------------------------------------------------
    @Suppress("MissingPermission") // Ya validamos permisos antes de llegar aquí
    private fun iniciarCapturaYEnvio() {
        tvStatus.text = getString(R.string.status_getting_location)
        btnSendLocation.isEnabled = false

        val cancellationTokenSource = com.google.android.gms.tasks.CancellationTokenSource()

        // Usamos una corrutina para esperar el resultado de forma ordenada
        lifecycleScope.launch {
            try {
                val currentLocationRequest = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .build()

                // getCurrentLocation obtiene una única lectura fresca (no continua)
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

                // Actualizamos la interfaz con las coordenadas obtenidas
                tvLatitude.text = getString(R.string.latitude_value, lat)
                tvLongitude.text = getString(R.string.longitude_value, lng)

                // Construimos y enviamos el mensaje
                val numero = normalizarNumero(etPhoneNumber.text.toString()) ?: return@launch
                val mensaje = construirMensaje(lat, lng, timestampUtc, timestampColombia)
                val smsEnviado = enviarSms(numero, mensaje)

                if (smsEnviado) {
                    tvLastSent.text = getString(R.string.last_sent_value, timestampColombia)
                    tvStatus.text = getString(R.string.status_sms_waiting_confirmation)
                    mostrarSnackbar(getString(R.string.sms_queued_to, numero))
                } else {
                    tvStatus.text = getString(R.string.status_send_failed)
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
    // Formatea la hora UTC del fix de ubicación. En GPS/GNSS esa hora
    // proviene de la referencia temporal de los satélites.
    // -----------------------------------------------------------------
    private fun obtenerTimestampUbicacion(timestampMillis: Long, timeZoneId: String): String {
        val formato = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        formato.timeZone = TimeZone.getTimeZone(timeZoneId)
        return "${formato.format(Date(timestampMillis))}000"
    }

    // -----------------------------------------------------------------
    // Construye el texto del SMS con coordenadas, enlace y timestamp
    // -----------------------------------------------------------------
    private fun construirMensaje(
        lat: Double,
        lng: Double,
        timestampUtc: String,
        timestampColombia: String
    ): String {
        return getString(R.string.sms_location_message, lat, lng, timestampUtc, timestampColombia)
    }

    // -----------------------------------------------------------------
    // Envía el SMS usando SmsManager. Si el mensaje es largo, se divide
    // automáticamente en varias partes con divideMessage/sendMultipart.
    // -----------------------------------------------------------------
    private fun enviarSms(numero: String, mensaje: String): Boolean {
        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val partes = smsManager.divideMessage(mensaje)
            val sentIntents = crearPendingIntents(partes.size, ACTION_SMS_SENT)
            val deliveredIntents = crearPendingIntents(partes.size, ACTION_SMS_DELIVERED)
            smsManager.sendMultipartTextMessage(numero, null, partes, sentIntents, deliveredIntents)
            true

        } catch (e: Exception) {
            mostrarSnackbar(getString(R.string.error_sms_with_detail, e.message ?: getString(R.string.error_unknown)))
            false
        }
    }

    private fun registrarReceiversSms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsSentReceiver, IntentFilter(ACTION_SMS_SENT), RECEIVER_NOT_EXPORTED)
            registerReceiver(smsDeliveredReceiver, IntentFilter(ACTION_SMS_DELIVERED), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(smsSentReceiver, IntentFilter(ACTION_SMS_SENT))
            registerReceiver(smsDeliveredReceiver, IntentFilter(ACTION_SMS_DELIVERED))
        }
    }

    private fun crearPendingIntents(cantidad: Int, accion: String): ArrayList<PendingIntent> {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return ArrayList(
            List(cantidad) { index ->
                PendingIntent.getBroadcast(
                    this,
                    index,
                    Intent(accion).setPackage(packageName),
                    flags
                )
            }
        )
    }

    private fun normalizarNumero(numero: String?): String? {
        val limpio = numero
            ?.trim()
            ?.replace(" ", "")
            ?.replace("-", "")

        return when {
            limpio.isNullOrEmpty() -> null
            limpio.matches(Regex("\\d{10}")) -> "+57$limpio"
            else -> limpio
        }
    }

    private fun esNumeroColombianoInternacional(numero: String): Boolean {
        return numero.matches(Regex("\\+57\\d{10}"))
    }

    private fun mostrarSnackbar(mensaje: String) {
        Snackbar.make(btnSendLocation, mensaje, Snackbar.LENGTH_LONG).show()
    }
}
