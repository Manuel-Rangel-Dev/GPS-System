package com.uninorte.locator

import com.uninorte.locator.obd.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ObdTest {
    private val ejemplos = listOf(
        "41 0C 1A F8" to 1726.0, "41 0D 30" to 48.0,
        "41 04 49" to (73 * 100.0 / 255), "41 11 2C" to (44 * 100.0 / 255),
        "41 49 35" to (53 * 100.0 / 255), "41 4C 16" to (22 * 100.0 / 255),
        "41 0B 2E" to 46.0, "41 43 00 4D" to (77 * 100.0 / 255),
        "41 44 80 00" to 1.0, "41 0E AA" to 21.0, "41 06 83" to 2.34375,
        "41 5E 00 48" to 3.6, "41 07 7D" to -2.34375,
        "41 05 69" to 65.0, "41 0F 5D" to 53.0)

    @Test fun decodificaLosQuincePidsDelContrato() {
        ejemplos.forEachIndexed { i, (respuesta, esperado) ->
            assertEquals(esperado, PidsObd.parsear(PidsObd.pids[i], respuesta)!!, 1e-9)
        }
        assertEquals(65535.0 / 32768, PidsObd.parsear(0x44, "41 44 FF FF")!!, 1e-9)
    }

    @Test fun parseaEcoEspaciosBusquedaYErrores() {
        assertEquals(1726.0, PidsObd.parsear(0x0C, "010C1\rSEARCHING...\r\n41 0c 1a f8\r>"))
        assertEquals(1664.0, PidsObd.parsear(0x0C, "41 0C 1A")) // B ausente: cero, como readPid.
        listOf("NO DATA", "?", "SEARCHING...", "41 0C ZZ FF", "41 0C 1").forEach {
            assertNull(PidsObd.parsear(0x0C, it))
        }
    }

    @Test fun parseaGpsYRechazaCoordenadasInvalidas() {
        val gps = PosicionObd.parsear("ATGPS\r42.2775583,-83.6987497,230.6,5.0\r\r>")!!
        assertEquals(42.2775583, gps.lat, 1e-9)
        assertEquals(5.0, gps.precision)
        listOf("?", "NO DATA", "91,0,0,5", "0,0,NaN,5", "0,0,0,-1", "0,0,0").forEach {
            assertNull(PosicionObd.parsear(it))
        }
    }

    @Test fun paqueteTieneOrdenNulosPrecisionYLimite() {
        val fila = PaqueteObd("emu_20261109_120000_ab12", 0, 123, "2026-11-09T17:00:00",
            ejemplos.map { it.second }, PosicionObd(42.2775583, -83.6987497, 230.6, 5.0, 48.0))
        val json = fila.json("a\"b\\c\n")
        assertTrue(json.startsWith("{\"tipo\":\"obd\",\"tok\":\"a\\\"b\\\\c\\u000a\",\"rid\":"))
        assertTrue(json.contains("\"lat\":42.2775583,\"lng\":-83.6987497,\"utc\":\"2026-11-09T17:00:00\",\"alt\":230.600,\"gkmh\":48.000,\"acc\":5.000"))
        var anterior = -1
        PidsObd.claves.forEach { clave ->
            val indice = json.indexOf("\"$clave\":")
            assertTrue(indice > anterior); anterior = indice
        }
        assertFalse(json.contains("\"sat\""))
        assertTrue(json.toByteArray().size < 600)
        val sinFix = fila.copy(valores = List(15) { null }, posicion = null).json()
        assertFalse(sinFix.contains("\"tok\"")); assertFalse(sinFix.contains("\"lat\""))
        assertTrue(sinFix.contains("\"utc\""))
        PidsObd.claves.forEach { assertTrue(sinFix.contains("\"$it\":null")) }
        assertTrue(fila.copy(valores = List(15) { Double.NaN }).json().contains("\"rpm\":null"))
    }

    @Test fun ridYFechasCompartenZonaHoraria() {
        // 2026-01-01 00:00 UTC es 2025-12-31 19:00 en Colombia.
        val instante = 1767225600000L
        assertTrue(crearRid(false, instante).matches(Regex("kia_20251231_190000_[a-f0-9]{4}")))
        assertTrue(crearRid(true, instante).matches(Regex("emu_20251231_190000_[a-f0-9]{4}")))
        assertEquals("2026-01-01T00:00:00", obtenerUtc(instante))
        assertEquals("2025-12-31", obtenerFechaColombia(instante))
        assertEquals("19:00:00", obtenerHoraColombia(instante))
    }

    @Test fun colaDescartaLaMasViejaSinConfirmarOtraFila() {
        val cola = ColaEnvio<Any>(2)
        val a = Any(); val b = Any(); val c = Any()
        cola.agregar(a); cola.agregar(b); cola.agregar(c)
        assertSame(b, cola.primera()); assertEquals(1L, cola.descartadas)
        cola.confirmar(a); assertEquals(2, cola.cantidad())
        cola.confirmar(b); assertSame(c, cola.primera())
        val completa = ColaEnvio<Int>()
        repeat(401) { completa.agregar(it) }
        assertEquals(400, completa.cantidad()); assertEquals(1, completa.primera())
    }

    @Test fun consumoUsaLambdaUnoSiFaltaOEsCero() {
        val valores = MutableList<Double?>(15) { null }
        valores[7] = 30.0; valores[0] = 1726.0
        assertEquals(5.19e-5 * 30 * 1726, PidsObd.consumo(valores)!!, 1e-9)
        valores[8] = 0.0
        assertEquals(5.19e-5 * 30 * 1726, PidsObd.consumo(valores)!!, 1e-9)
    }

    @Test fun clienteLeeFragmentosLimpiaPendientesYEsperaElPrompt() {
        // Stream controlado: libera la respuesta únicamente cuando se escribe un comando.
        val pendiente = java.util.ArrayDeque<Int>()
        pendiente.add('X'.code)
        var restante = ""
        var paso = 0
        val entrada = object : java.io.InputStream() {
            override fun available(): Int {
                if (pendiente.isEmpty() && restante.isNotEmpty()) {
                    pendiente.add(restante.first().code)
                    restante = restante.drop(1)
                }
                return pendiente.size
            }
            override fun read(): Int = if (pendiente.isEmpty()) -1 else pendiente.removeFirst()
        }
        val comandos = mutableListOf<String>()
        val salida = object : java.io.OutputStream() {
            val acumulado = StringBuilder()
            override fun write(b: Int) {
                if (b != '\r'.code) { acumulado.append(b.toChar()); return }
                comandos.add(acumulado.toString()); acumulado.clear()
                restante = when (paso++) {
                    0 -> "010C1\rSEARCHING...\r41 0C 1A F8\r\r>"
                    1 -> "NO DATA\r\r>"
                    2 -> "?\r\r>"
                    else -> ""
                }
            }
        }
        val cliente = ClienteElm(entrada, salida)
        assertEquals(1726.0, cliente.leerPid(0x0C))
        assertNull(cliente.leerPid(0x0D))
        assertNull(cliente.leerGps())
        assertEquals("", cliente.enviar("ATGPS", 5))
        assertEquals(listOf("010C1", "010D1", "ATGPS", "ATGPS"), comandos)
    }

    @Test fun clienteCompletoContraServidorTcpConRespuestasPartidas() {
        val ejecutor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { servidor ->
            val comandos = mutableListOf<String>()
            val tarea = ejecutor.submit {
                servidor.accept().use { conexion ->
                    var eco = true
                    val entrada = conexion.getInputStream()
                    val salida = conexion.getOutputStream()
                    val comando = StringBuilder()
                    while (true) {
                        val c = entrada.read()
                        if (c < 0) break
                        if (c != '\r'.code) { comando.append(c.toChar()); continue }
                        val texto = comando.toString(); comando.clear(); comandos.add(texto)
                        val cuerpo = when {
                            texto == "ATZ" -> "ELM327 v1.5"
                            texto == "ATDPN" -> "A6"
                            texto == "ATGPS" -> "42.2775583,-83.6987497,230.6,5.0"
                            texto == "0100" -> "41 00 BE 3F B8 13"
                            texto.startsWith("01") -> ejemplos[PidsObd.pids.indexOf(texto.substring(2, 4).toInt(16))].first
                            else -> "OK"
                        }
                        val respuesta = (if (eco) "$texto\r" else "") + cuerpo + "\r\r>"
                        if (texto == "ATE0") eco = false
                        // Dos escrituras separadas: el cliente no puede asumir un read por respuesta.
                        val mitad = respuesta.length / 2
                        salida.write(respuesta.take(mitad).toByteArray()); salida.flush()
                        Thread.sleep(3)
                        salida.write(respuesta.drop(mitad).toByteArray()); salida.flush()
                    }
                }
            }
            try {
                Socket("127.0.0.1", servidor.localPort).use { socket ->
                    val cliente = ClienteElm(socket)
                    assertEquals("A6", cliente.inicializar())
                    ejemplos.forEachIndexed { i, par -> assertEquals(par.second, cliente.leerPid(PidsObd.pids[i])!!, 1e-9) }
                    assertNotNull(cliente.leerGps())
                }
                tarea.get(10, TimeUnit.SECONDS)
                assertEquals(listOf("ATZ", "ATE0", "ATL0", "ATH0", "ATAT2", "ATSP0", "0100", "ATDPN"), comandos.take(8))
            } finally { ejecutor.shutdownNow() }
        }
    }

    @Test fun simuladorExternoLeeCincoFilasSiEstaConfigurado() {
        val puerto = System.getenv("SIM_PUERTO")
        assumeTrue("SIM_PUERTO no configurado", !puerto.isNullOrBlank())
        Socket("127.0.0.1", puerto!!.toInt()).use { socket ->
            val cliente = ClienteElm(socket)
            assertNotNull(cliente.inicializar())
            repeat(5) { fila ->
                val lento = PidsObd.lentos[fila % PidsObd.lentos.size]
                PidsObd.pids.filter { it in PidsObd.rapidos || it == lento }.forEach { assertNotNull(cliente.leerPid(it)) }
                assertNotNull(cliente.leerGps())
                Thread.sleep(1_000)
            }
        }
    }
}
