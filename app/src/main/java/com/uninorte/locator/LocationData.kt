package com.uninorte.locator

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class UdpDestination(val ip: String, val port: Int)

private const val IP_1 = "34.200.45.218"
private const val IP_2 = "52.70.149.97"
private const val IP_3 = "3.224.240.185"
private const val IP_4 = "18.233.233.208"

internal val UDP_DESTINATIONS = listOf(
    UdpDestination(IP_1, 5000),
    UdpDestination(IP_1, 5001),
    UdpDestination(IP_2, 5000),
    UdpDestination(IP_3, 5000),
    UdpDestination(IP_4, 5000)
)

// ============================================================================
// FUNCIONES COMPARTIDAS DE FECHA Y ENVIO UDP
// ============================================================================
internal suspend fun enviarPorUdp(destinos: List<UdpDestination>, payload: String): Boolean =
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

internal fun obtenerFechaColombia(timestampMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("America/Bogota")
    }.format(Date(timestampMillis))

internal fun obtenerHoraColombia(timestampMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("America/Bogota")
    }.format(Date(timestampMillis))

// Arma el JSON que se envía por UDP. lat/lng/date/hour son obligatorios para todos los
// servidores del equipo; acc, spd y seq son opcionales (los servidores viejos los ignoran).
internal fun construirPayload(location: Location, seq: Long? = null): String =
    JSONObject().apply {
        put("lat", location.latitude)
        put("lng", location.longitude)
        put("date", obtenerFechaColombia(location.time))
        put("hour", obtenerHoraColombia(location.time))
        if (location.hasAccuracy()) put("acc", Math.round(location.accuracy * 10) / 10.0)
        if (location.hasSpeed()) put("spd", Math.round(location.speed * 10) / 10.0)
        if (seq != null) put("seq", seq)
    }.toString()
