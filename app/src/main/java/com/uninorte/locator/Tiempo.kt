package com.uninorte.locator

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.security.SecureRandom

internal fun formatearFecha(timestampMillis: Long, patron: String, zona: String): String =
    SimpleDateFormat(patron, Locale.US).apply { timeZone = TimeZone.getTimeZone(zona) }
        .format(Date(timestampMillis))

internal fun obtenerFechaColombia(timestampMillis: Long) =
    formatearFecha(timestampMillis, "yyyy-MM-dd", "America/Bogota")
internal fun obtenerHoraColombia(timestampMillis: Long) =
    formatearFecha(timestampMillis, "HH:mm:ss", "America/Bogota")
internal fun obtenerUtc(timestampMillis: Long) =
    formatearFecha(timestampMillis, "yyyy-MM-dd'T'HH:mm:ss", "UTC")
internal fun crearRid(emulador: Boolean, timestampMillis: Long = System.currentTimeMillis()): String =
    (if (emulador) "emu_" else "kia_") +
        formatearFecha(timestampMillis, "yyyyMMdd_HHmmss", "America/Bogota") +
        "_%04x".format(Locale.US, SecureRandom().nextInt(65536))
