package com.uninorte.locator

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val HISTORICO_MIN_ANIO = 2020
internal enum class ModoFecha { DESDE, HASTA }

internal data class Hora12(val hh: Int, val mm: Int, val ss: Int, val pm: Boolean) {
    init { require(hh in 1..12 && mm in 0..59 && ss in 0..59) }
    fun a24(): LocalTime = LocalTime.of(hh % 12 + if (pm) 12 else 0, mm, ss)

    companion object {
        fun normalizar(hh: String, mm: String, ss: String, pm: Boolean) = Hora12(
            (hh.toIntOrNull() ?: 1).coerceIn(1, 12),
            (mm.toIntOrNull() ?: 0).coerceIn(0, 59),
            (ss.toIntOrNull() ?: 0).coerceIn(0, 59), pm)
    }
}

internal data class RangoFechas(
    val desde: LocalDate,
    val hasta: LocalDate,
    val horaDesde: Hora12 = Hora12(12, 0, 0, false),
    val horaHasta: Hora12 = Hora12(11, 59, 59, true),
    val modo: ModoFecha = ModoFecha.DESDE,
    val mes: YearMonth = YearMonth.from(hasta)
) {
    fun esFuturo(dia: LocalDate, hoy: LocalDate): Boolean = dia > hoy
    fun seleccionar(dia: LocalDate, hoy: LocalDate): RangoFechas {
        if (esFuturo(dia, hoy) || dia.year < HISTORICO_MIN_ANIO) return this
        return when (modo) {
            ModoFecha.DESDE -> copy(desde = dia, hasta = maxOf(hasta, dia), modo = ModoFecha.HASTA)
            ModoFecha.HASTA -> if (dia < desde) copy(desde = dia) else copy(hasta = dia)
        }
    }
    fun cambiarMes(delta: Long): RangoFechas {
        val nuevo = mes.plusMonths(delta)
        return if (nuevo.year < HISTORICO_MIN_ANIO) this else copy(mes = nuevo)
    }
    fun puedeRetroceder(): Boolean = mes > YearMonth.of(HISTORICO_MIN_ANIO, 1)
    fun esValido(): Boolean = !hasta.atTime(horaHasta.a24()).isBefore(desde.atTime(horaDesde.a24()))
    fun desdeApi(): String = desde.atTime(horaDesde.a24()).format(FORMATO_API)
    fun hastaApi(): String = hasta.atTime(horaHasta.a24()).format(FORMATO_API)

    companion object {
        private val FORMATO_API = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        fun inicial(hoy: LocalDate): RangoFechas = RangoFechas(hoy.minusDays(6), hoy)
    }
}
