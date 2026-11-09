package com.uninorte.locator.obd

import java.util.Locale

// La posición y UTC son una instantánea al cerrar la fila, incluso si espera en cola.
data class PaqueteObd(val rid: String, val seq: Long, val tMs: Long, val utc: String,
                      val valores: List<Double?>, val posicion: PosicionObd?) {
    fun json(token: String = ""): String = buildString {
        require(rid.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        require(valores.size == 15)
        append("{\"tipo\":\"obd\"")
        if (token.isNotEmpty()) append(",\"tok\":${escapar(token)}")
        append(",\"rid\":${escapar(rid)},\"seq\":$seq,\"t_ms\":$tMs")
        posicion?.let { append(",\"lat\":${numero(it.lat, 7)},\"lng\":${numero(it.lng, 7)}") }
        append(",\"utc\":${escapar(utc)}")
        posicion?.let {
            it.alt?.let { v -> append(",\"alt\":${numero(v)}") }
            it.velocidad?.let { v -> append(",\"gkmh\":${numero(v)}") }
            it.precision?.let { v -> append(",\"acc\":${numero(v)}") }
        }
        PidsObd.claves.forEachIndexed { i, clave -> append(",\"$clave\":${numero(valores[i])}") }
        append('}')
    }.also { require(it.toByteArray(Charsets.UTF_8).size < 600) }

    companion object {
        private fun numero(valor: Double?, decimales: Int = 3): String =
            if (valor == null || !valor.isFinite()) "null" else String.format(Locale.US, "%.${decimales}f", valor)
        private fun escapar(texto: String): String = buildString {
            append('"')
            texto.forEach { c ->
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    else -> if (c < ' ') append("\\u%04x".format(Locale.US, c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}
