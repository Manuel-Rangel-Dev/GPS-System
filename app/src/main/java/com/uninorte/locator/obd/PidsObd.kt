package com.uninorte.locator.obd

import java.util.Locale

object PidsObd {
    val pids = listOf(0x0C, 0x0D, 0x04, 0x11, 0x49, 0x4C, 0x0B, 0x43, 0x44, 0x0E, 0x06, 0x5E, 0x07, 0x05, 0x0F)
    val claves = listOf("rpm", "vel", "carga", "tps", "pedal", "acel_cmd", "map", "carga_abs", "lambda", "avance", "stft", "fuel_lph", "ltft", "ect", "iat")
    val rapidos = setOf(0x0C, 0x0D, 0x0B, 0x43, 0x44, 0x06, 0x5E)
    val lentos = pids.filterNot { it in rapidos }
    fun comando(pid: Int) = "01%02X1".format(Locale.US, pid)

    fun parsear(pid: Int, respuesta: String): Double? {
        val limpia = respuesta.filterNot { it.isWhitespace() }.uppercase(Locale.US)
        val marca = "41%02X".format(Locale.US, pid)
        val inicio = limpia.indexOf(marca)
        if (inicio < 0) return null
        val datos = limpia.substring(inicio + marca.length)
        val a = datos.take(2).takeIf { it.length == 2 }?.toIntOrNull(16) ?: return null
        val b = if (datos.length >= 4) datos.substring(2, 4).toIntOrNull(16) ?: return null else 0
        return decodificar(pid, a, b)
    }

    fun decodificar(pid: Int, a: Int, b: Int = 0): Double? {
        val ab = a * 256.0 + b
        return when (pid) {
            0x0C -> ab / 4.0
            0x0D, 0x0B -> a.toDouble()
            0x04, 0x11, 0x49, 0x4C -> a * 100.0 / 255.0
            0x43 -> ab * 100.0 / 255.0
            0x44 -> ab / 32768.0
            0x0E -> a / 2.0 - 64.0
            0x06, 0x07 -> (a - 128) * 100.0 / 128.0
            0x05, 0x0F -> a - 40.0
            0x5E -> ab / 20.0
            else -> null
        }
    }

    fun consumo(valores: List<Double?>): Double? {
        val carga = valores[7] ?: return null
        val rpm = valores[0] ?: return null
        val lambda = valores[8]?.takeIf { it != 0.0 } ?: 1.0
        return 5.19e-5 * carga * rpm / lambda
    }
}

data class PosicionObd(val lat: Double, val lng: Double, val alt: Double? = null,
                       val precision: Double? = null, val velocidad: Double? = null) {
    companion object {
        fun parsear(respuesta: String): PosicionObd? {
            val numeros = respuesta.lineSequence().map { it.trim().removeSuffix(">") }
                .firstOrNull { it.count { c -> c == ',' } == 3 }
                ?.split(',')?.map { it.trim().toDoubleOrNull() } ?: return null
            if (numeros.any { it == null || !it.isFinite() }) return null
            val (lat, lng, alt, precision) = numeros.map { it!! }
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || precision < 0) return null
            return PosicionObd(lat, lng, alt, precision)
        }
    }
}
