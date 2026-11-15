package com.uninorte.locator.obd

import android.util.Log
import java.io.EOFException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.io.IOException
import java.net.Socket
import java.net.SocketTimeoutException

internal object RegistroObd {
    fun escribir(mensaje: String) {
        // android.jar lanza RuntimeException al llamar Log desde las pruebas JVM.
        try { Log.d("ObdTiempos", mensaje) } catch (_: RuntimeException) { }
    }
}

// Una respuesta negativa también confirma que el adaptador sigue respondiendo.
internal class VigilanciaElm {
    private var ciclosSinRespuesta = 0
    fun registrarCiclo(huboRespuesta: Boolean): Boolean {
        ciclosSinRespuesta = if (huboRespuesta) 0 else ciclosSinRespuesta + 1
        return ciclosSinRespuesta >= 3
    }
}

// El reloj monótono y los streams permiten probar el protocolo sin Android.
class ClienteElm private constructor(
    private val entrada: InputStream, private val salida: OutputStream, private val socket: Socket?
) {
    constructor(entrada: InputStream, salida: OutputStream) : this(entrada, salida, null)
    constructor(socket: Socket) : this(socket.getInputStream(), socket.getOutputStream(), socket)

    companion object {
        const val TIEMPO_ESPERA_PID_MS = 1_200L
        private const val TIEMPO_RESINCRONIZACION_MS = 1_200L
    }

    private var pendienteDePrompt = false
    private var respuestaCompleta = false
    var ultimoPidRespondio = false
        private set

    var ultimoPidSinDatos = false
        private set

    private fun leerCaracter(fin: Long): Int? {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException()
        val restante = fin - System.nanoTime()
        if (restante <= 0) return null
        if (socket != null) {
            // La lectura bloqueante con límite permite detectar EOF aunque available() sea cero.
            socket.soTimeout = ((restante + 999_999) / 1_000_000).coerceAtMost(50).toInt()
            return try {
                entrada.read().also { if (it < 0) throw EOFException("ELM cerró el socket") }
            } catch (_: SocketTimeoutException) { null }
        }
        if (entrada.available() == 0) { Thread.sleep(1); return null }
        return entrada.read().also { if (it < 0) throw EOFException("ELM cerró el stream") }
    }

    private fun resincronizar(): Boolean {
        val fin = System.nanoTime() + TIEMPO_RESINCRONIZACION_MS * 1_000_000
        while (System.nanoTime() < fin) {
            if (leerCaracter(fin) == '>'.code) {
                pendienteDePrompt = false
                RegistroObd.escribir("Resincronización: prompt recibido; respuesta tardía descartada")
                return true
            }
        }
        RegistroObd.escribir("Resincronización: timeout esperando prompt; no se envía otro comando")
        return false
    }

    fun enviar(comando: String, timeoutMs: Long): String {
        respuestaCompleta = false
        if (pendienteDePrompt && !resincronizar()) return ""
        while (entrada.available() > 0) {
            if (entrada.read() < 0) throw EOFException("ELM cerró el stream")
        }
        salida.write((comando + "\r").toByteArray(Charsets.US_ASCII))
        salida.flush()
        pendienteDePrompt = true
        val fin = System.nanoTime() + timeoutMs * 1_000_000
        val respuesta = StringBuilder()
        while (System.nanoTime() < fin) {
            val caracter = leerCaracter(fin) ?: continue
            if (caracter == '>'.code) {
                pendienteDePrompt = false
                respuestaCompleta = true
                return respuesta.toString()
            }
            if (respuesta.length >= 8192) throw IOException("Respuesta ELM demasiado larga")
            respuesta.append(caracter.toChar())
        }
        // Sin prompt no se puede aceptar ni siquiera una trama aparentemente completa.
        return ""
    }

    fun inicializar(): String? {
        listOf("ATZ", "ATE0", "ATL0", "ATH0", "ATAT2", "ATSP0").forEach { enviar(it, 5_000) }
        enviar("0100", 10_000)
        return enviar("ATDPN", 2_000).lineSequence().map { it.trim() }
            .firstOrNull { it.matches(Regex("A?[0-9A-C]", RegexOption.IGNORE_CASE)) }
    }

    fun leerPid(pid: Int): Double? {
        val inicio = System.nanoTime()
        ultimoPidRespondio = false
        ultimoPidSinDatos = false
        var resultado = "error de socket"
        try {
            val respuesta = enviar(PidsObd.comando(pid), TIEMPO_ESPERA_PID_MS)
            // parsear exige la cabecera 41 XX del PID solicitado; otras cabeceras se descartan.
            val valor = PidsObd.parsear(pid, respuesta)
            val noData = respuesta.lineSequence().any {
                it.trim().uppercase(java.util.Locale.US) in setOf("NO DATA", "?", "N/A", "NOT SUPPORTED")
            }
            ultimoPidSinDatos = respuestaCompleta && respuesta.lineSequence().any { it.trim().equals("NO DATA", true) }
            ultimoPidRespondio = respuestaCompleta && (valor != null || noData)
            resultado = when {
                !respuestaCompleta -> "timeout"
                valor != null -> "ok"
                noData -> "no data"
                else -> "respuesta inválida o PID distinto"
            }
            return valor
        } finally {
            RegistroObd.escribir("PID %02X: %d ms, %s".format(java.util.Locale.US,
                pid, (System.nanoTime() - inicio) / 1_000_000, resultado))
        }
    }

    fun leerGps(): PosicionObd? = PosicionObd.parsear(enviar("ATGPS", 300))
}
