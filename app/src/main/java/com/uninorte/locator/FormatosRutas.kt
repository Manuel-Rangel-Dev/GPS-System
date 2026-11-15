package com.uninorte.locator

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

private val idiomaColombia = Locale("es", "CO")

private fun decimal(valor: Double, patron: String): String =
    DecimalFormat(patron, DecimalFormatSymbols(idiomaColombia)).format(valor)

internal fun formatearPesos(valor: Double): String = "$ " + decimal(valor, "#,##0")
internal fun formatearLitros(valor: Double): String = decimal(valor, "0.00") + " L"
internal fun formatearKm(valor: Double): String = decimal(valor, "0.0") + " km"
internal fun formatearDuracion(segundos: Double): String = "${(segundos / 60).roundToLong()} min"

internal fun formatearInicio(inicio: String): String {
    val zona = TimeZone.getTimeZone("America/Bogota")
    val entrada = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", idiomaColombia).apply {
        timeZone = zona
        isLenient = false
    }
    val fecha = runCatching { entrada.parse(inicio) }.getOrNull() ?: return inicio
    // Patrón fijo para que la puntuación de a. m./p. m. sea igual en Android y JVM.
    val dia = SimpleDateFormat("EEE d MMM", idiomaColombia).apply { timeZone = zona }
        .format(fecha).replace(".", "")
    val hora = SimpleDateFormat("h:mm", idiomaColombia).apply { timeZone = zona }.format(fecha)
    val periodo = SimpleDateFormat("H", idiomaColombia).apply { timeZone = zona }.format(fecha).toInt()
    return "$dia · $hora " + if (periodo < 12) "a. m." else "p. m."
}

internal fun urlGoogleMaps(origenLat: Double, origenLon: Double, destinoLat: Double, destinoLon: Double): String =
    String.format(Locale.US,
        "https://www.google.com/maps/dir/?api=1&origin=%.8f,%.8f&destination=%.8f,%.8f&travelmode=driving",
        origenLat, origenLon, destinoLat, destinoLon)
