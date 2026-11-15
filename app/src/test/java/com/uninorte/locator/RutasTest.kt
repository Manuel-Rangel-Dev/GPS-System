package com.uninorte.locator

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

class RutasTest {
    private val ruta = """{"id":0,"distancia_m":13229,"tiempo_s":1317,"retraso_trafico_s":0,
        "litros":0.9939,"costo_cop":4191.4,"prediccion_id":4,"ruta_id":8,
        "modelo":"slope/linear_physics","factor":1.0508,"vel_media_kmh":36.2,
        "puntos":[[10.99,-74.8],[11.01,-74.81]]}"""
    private val recorrido = """{"id":"v2_20261111_132011","inicio":"2026-11-11 13:20:11",
        "fin":"2026-11-11 13:41:50","duracion_s":1299.0,"distancia_km":6.21,
        "vel_media_kmh":17.2,"litros":0.561,"costo_cop":2366.0,
        "litros_medidos":null,"costo_medido_cop":null,"modelo":"slope/linear_physics",
        "factor":1.047,"en_curso":false,"emulado":false}"""

    @Test fun parseaRutaRealConEstimado() {
        val resultado = parsearRutas("[$ruta]").single()
        assertEquals(13229.0, resultado.distanciaM, 0.0)
        assertEquals(1317.0, resultado.tiempoS, 0.0)
        assertEquals(0.0, resultado.retrasoS, 0.0)
        assertEquals(0.9939, resultado.litros!!, 0.0)
        assertEquals(4191.4, resultado.costoCop!!, 0.0)
        assertEquals(4L, resultado.prediccionId)
        assertEquals(8L, resultado.rutaId)
        assertEquals(listOf(10.99 to -74.8, 11.01 to -74.81), resultado.puntos)
    }

    @Test fun conservaNulosDeRuta() {
        val json = ruta.replace("0.9939", "null").replace("4191.4", "null")
            .replace("\"prediccion_id\":4", "\"prediccion_id\":null")
            .replace("\"ruta_id\":8", "\"ruta_id\":null")
        val resultado = parsearRutas("[$json]").single()
        assertNull(resultado.litros)
        assertNull(resultado.costoCop)
        assertNull(resultado.prediccionId)
        assertNull(resultado.rutaId)
    }

    @Test fun parseaRecorridoDelCelularSinMedidos() {
        val resultado = parsearRecorridos("""{"vid":"celular","recorridos":[$recorrido]}""").single()
        assertEquals("v2_20261111_132011", resultado.id)
        assertEquals("2026-11-11 13:20:11", resultado.inicio)
        assertEquals("2026-11-11 13:41:50", resultado.fin)
        assertEquals(1299.0, resultado.duracionS, 0.0)
        assertEquals(6.21, resultado.distanciaKm, 0.0)
        assertEquals(17.2, resultado.velMediaKmh!!, 0.0)
        assertEquals(0.561, resultado.litros, 0.0)
        assertEquals(2366.0, resultado.costoCop!!, 0.0)
        assertEquals("slope/linear_physics", resultado.modelo)
        assertEquals(1.047, resultado.factor!!, 0.0)
        assertNull(resultado.litrosMedidos)
        assertNull(resultado.costoMedidoCop)
        assertFalse(resultado.enCurso)
        assertFalse(resultado.emulado)
    }

    @Test fun parseaKiaMedidoEmuladoYEnCurso() {
        val kia = recorrido.replace("v2_20261111_132011", "kia_20261111_132011").replace("\"litros_medidos\":null", "\"litros_medidos\":0.55")
            .replace("\"costo_medido_cop\":null", "\"costo_medido_cop\":2310")
            .replace("false", "true").replace("\"fin\":\"2026-11-11 13:41:50\"", "\"fin\":null")
        val resultado = parsearRecorridos("""{"vid":"kia_k3","recorridos":[$kia]}""").single()
        assertEquals(0.55, resultado.litrosMedidos!!, 0.0)
        assertEquals(2310.0, resultado.costoMedidoCop!!, 0.0)
        assertTrue(resultado.emulado)
        assertTrue(resultado.enCurso)
        assertNull(resultado.fin)
    }

    private fun respuesta(nombre: String): String =
        requireNotNull(javaClass.getResourceAsStream("/respuestas/$nombre.json")) {
            "Falta la respuesta $nombre en el classpath"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun assertOpcionalesFinitos(recorridos: List<Recorrido>) {
        recorridos.forEach { r ->
            listOf(r.velMediaKmh, r.costoCop, r.litrosMedidos, r.costoMedidoCop, r.factor)
                .forEach { valor -> assertTrue("Número opcional inválido en ${r.id}", valor == null || valor.isFinite()) }
        }
    }

    @Test fun conservaCostoYVelocidadNulosSinPerderOtrasFilas() {
        val sinCosto = JSONObject(recorrido).put("id", "sin_costo")
            .put("costo_cop", JSONObject.NULL).put("vel_media_kmh", JSONObject.NULL)
        val resultado = parsearRecorridos("""{"recorridos":[$recorrido,$sinCosto,$recorrido]}""")
        assertEquals(3, resultado.size)
        assertEquals("sin_costo", resultado[1].id)
        assertNull(resultado[1].costoCop)
        assertNull(resultado[1].velMediaKmh)
        assertEquals(2366.0, resultado.first().costoCop!!, 0.0)
        assertEquals(2366.0, resultado.last().costoCop!!, 0.0)
    }

    @Test fun omiteFilasSinObligatoriosYConservaLasDemas() {
        listOf("id", "inicio", "duracion_s", "distancia_km", "litros").forEach { clave ->
            listOf(false, true).forEach { nulo ->
                val incompleto = JSONObject(recorrido)
                if (nulo) incompleto.put(clave, JSONObject.NULL) else incompleto.remove(clave)
                val resultado = parsearRecorridos("""{"recorridos":[$recorrido,$incompleto,$recorrido]}""")
                assertEquals("Campo $clave, null=$nulo", 2, resultado.size)
                assertEquals(listOf("v2_20261111_132011", "v2_20261111_132011"), resultado.map { it.id })
            }
        }
    }

    @Test fun leeRespuestaRealKiaConMedidosYEmulados() {
        val resultado = parsearRecorridos(respuesta("recorridos_kia"))
        assertEquals(8, resultado.size)
        assertEquals(setOf("emu_20261111_172208_083b", "emu_20261109_190742_f5a0",
            "emu_20261109_152843_e840"), resultado.filter { it.emulado }.map { it.id }.toSet())
        assertTrue(resultado.all { it.litrosMedidos != null && it.costoMedidoCop != null })
        assertOpcionalesFinitos(resultado)
        assertEquals(5, resumirRecorridos(resultado).cantidad)
    }

    @Test fun leeRespuestaRealVehiculoDosSinMedidos() {
        val resultado = parsearRecorridos(respuesta("recorridos_vehiculo2"))
        assertEquals(17, resultado.size)
        assertTrue(resultado.all { it.litrosMedidos == null && it.costoMedidoCop == null })
        assertOpcionalesFinitos(resultado)
    }

    @Test fun leeRespuestaRealRutasConPuntosEIds() {
        val resultado = parsearRutas(respuesta("rutas"))
        assertEquals(3, resultado.size)
        resultado.forEach { r ->
            assertTrue(r.puntos.isNotEmpty())
            assertTrue(r.retrasoS.isFinite())
            assertNotNull(r.prediccionId)
            assertNotNull(r.rutaId)
            assertNotNull(r.litros)
            assertNotNull(r.costoCop)
            listOf(r.litros, r.costoCop).forEach { valor ->
                assertTrue(valor == null || valor.isFinite())
            }
        }
    }

    @Test fun resumenExcluyeEmuladosDeCantidadYSumasEIgnoraCostosNulos() {
        val base = parsearRecorridos("""{"recorridos":[$recorrido]}""").single()
        val resultado = resumirRecorridos(listOf(
            base.copy(litros = 1.5, costoCop = 100.0),
            base.copy(litros = 2.0, costoCop = null),
            base.copy(emulado = true, litros = 99.0, costoCop = 9999.0),
            base.copy(emulado = true, litros = 88.0, costoCop = null)
        ))
        assertEquals(2, resultado.cantidad)
        assertEquals(3.5, resultado.litros, 0.0)
        assertEquals(100.0, resultado.costoCop, 0.0)
    }

    @Test fun resumenVacioSoloEmuladosYUnRecorridoSinCosto() {
        assertEquals(ResumenRecorridos(0, 0.0, 0.0), resumirRecorridos(emptyList()))
        val base = parsearRecorridos("""{"recorridos":[$recorrido]}""").single()
        assertEquals(ResumenRecorridos(0, 0.0, 0.0), resumirRecorridos(listOf(base.copy(emulado = true))))
        assertEquals(ResumenRecorridos(1, 0.561, 0.0), resumirRecorridos(listOf(base.copy(costoCop = null))))
    }

    @Test fun parseaListasVacias() {
        assertTrue(parsearRecorridos("""{"recorridos":[]}""").isEmpty())
        assertTrue(parsearRutas("[]").isEmpty())
    }

    @Test fun formateaPesosSinDecimales() {
        assertEquals("$ 4.191", formatearPesos(4191.4))
        assertEquals("$ 0", formatearPesos(0.0))
    }

    @Test fun formateaLitrosYKilometros() {
        assertEquals("0,99 L", formatearLitros(0.9939))
        assertEquals("13,2 km", formatearKm(13.229))
    }

    @Test fun redondeaMinutos() {
        assertEquals("22 min", formatearDuracion(1317.0))
        assertEquals("1 min", formatearDuracion(30.0))
        assertEquals("0 min", formatearDuracion(29.0))
    }

    @Test fun fechaLegibleConPeriodo() {
        assertEquals("mié 11 nov · 1:20 p. m.", formatearInicio("2026-11-11 13:20:11"))
        assertEquals("mié 11 nov · 12:05 a. m.", formatearInicio("2026-11-11 00:05:00"))
    }

    @Test fun formatosYUrlNoDependenDelIdiomaNiZonaDelCelular() {
        val idioma = Locale.getDefault()
        val zona = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("$ 4.191", formatearPesos(4191.4))
            assertEquals("0,99 L", formatearLitros(0.9939))
            assertEquals("mié 11 nov · 1:20 p. m.", formatearInicio("2026-11-11 13:20:11"))
            assertEquals("https://www.google.com/maps/dir/?api=1&origin=-4.12345678,-74.80000000" +
                "&destination=10.90000000,-75.10000000&travelmode=driving",
                urlGoogleMaps(-4.12345678, -74.8, 10.9, -75.1))
        } finally {
            Locale.setDefault(idioma)
            TimeZone.setDefault(zona)
        }
    }
}
