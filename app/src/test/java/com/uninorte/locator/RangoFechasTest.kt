package com.uninorte.locator

import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class RangoFechasTest {
    private val hoy = LocalDate.of(2026, 11, 21)

    @Test fun valoresInicialesConHoyInyectado() {
        val rango = RangoFechas.inicial(hoy)
        assertEquals(hoy.minusDays(6), rango.desde)
        assertEquals(hoy, rango.hasta)
        assertEquals(ModoFecha.DESDE, rango.modo)
        assertEquals(LocalTime.MIDNIGHT, rango.horaDesde.a24())
        assertEquals(LocalTime.of(23, 59, 59), rango.horaHasta.a24())
        assertEquals(YearMonth.of(2026, 11), rango.mes)
    }

    @Test fun seleccionDesdeLuegoHasta() {
        val desde = RangoFechas.inicial(hoy).seleccionar(hoy.minusDays(4), hoy)
        assertEquals(hoy.minusDays(4), desde.desde)
        assertEquals(ModoFecha.HASTA, desde.modo)
        val hasta = desde.seleccionar(hoy.minusDays(1), hoy)
        assertEquals(hoy.minusDays(1), hasta.hasta)
        assertEquals(desde.desde, hasta.desde)
    }

    @Test fun anteriorADesdeEnModoHastaCambiaSoloDesde() {
        val rango = RangoFechas.inicial(hoy).copy(modo = ModoFecha.HASTA)
        val nuevo = rango.seleccionar(hoy.minusDays(10), hoy)
        assertEquals(hoy.minusDays(10), nuevo.desde)
        assertEquals(rango.hasta, nuevo.hasta)
        assertEquals(ModoFecha.HASTA, nuevo.modo)
    }

    @Test fun desdeCorrigeHastaCuandoLoPasa() {
        val rango = RangoFechas.inicial(hoy).copy(hasta = hoy.minusDays(2))
        val nuevo = rango.seleccionar(hoy, hoy)
        assertEquals(hoy, nuevo.desde)
        assertEquals(hoy, nuevo.hasta)
    }

    @Test fun ignoraFuturosYFechasAntesDe2020() {
        val rango = RangoFechas.inicial(hoy)
        assertTrue(rango.esFuturo(hoy.plusDays(1), hoy))
        assertFalse(rango.esFuturo(hoy, hoy))
        ModoFecha.entries.forEach { modo ->
            val actual = rango.copy(modo = modo)
            assertEquals(actual, actual.seleccionar(hoy.plusDays(1), hoy))
            assertEquals(actual, actual.seleccionar(LocalDate.of(2019, 12, 31), hoy))
        }
    }

    @Test fun limiteDeNavegacionEnero2020() {
        val rango = RangoFechas.inicial(hoy).copy(mes = YearMonth.of(2020, 1))
        assertFalse(rango.puedeRetroceder())
        assertEquals(rango, rango.cambiarMes(-1))
        assertEquals(YearMonth.of(2020, 2), rango.cambiarMes(1).mes)
        assertTrue(rango.cambiarMes(1).puedeRetroceder())
    }

    @Test fun conversion12Horas() {
        assertEquals(LocalTime.of(0, 0), Hora12(12, 0, 0, false).a24())
        assertEquals(LocalTime.of(12, 0), Hora12(12, 0, 0, true).a24())
        assertEquals(LocalTime.of(13, 0), Hora12(1, 0, 0, true).a24())
    }

    @Test fun normalizaCamposVaciosYFueraDeRango() {
        assertEquals(Hora12(1, 0, 0, false), Hora12.normalizar("", "", "", false))
        assertEquals(Hora12(12, 59, 0, true), Hora12.normalizar("99", "77", "-1", true))
        assertEquals(1, Hora12.normalizar("0", "0", "0", false).hh)
    }

    @Test fun rechazaRangosInvertidosYPermiteIgualdad() {
        val rango = RangoFechas.inicial(hoy)
        assertFalse(rango.copy(desde = hoy, hasta = hoy.minusDays(1)).esValido())
        val mismoDia = rango.copy(desde = hoy, horaDesde = Hora12(2, 0, 0, true), horaHasta = Hora12(1, 0, 0, true))
        assertFalse(mismoDia.esValido())
        assertTrue(mismoDia.copy(horaHasta = mismoDia.horaDesde).esValido())
        assertTrue(rango.esValido())
    }

    @Test fun formatoParaConsulta() {
        val rango = RangoFechas.inicial(hoy)
        assertEquals("2026-11-15 00:00:00", rango.desdeApi())
        assertEquals("2026-11-21 23:59:59", rango.hastaApi())
    }
}
