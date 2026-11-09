package com.uninorte.locator.obd

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.io.IOException

// El reloj monótono y los streams permiten probar el protocolo sin Android.
class ClienteElm(private val entrada: InputStream, private val salida: OutputStream) {
    constructor(socket: Socket) : this(socket.getInputStream(), socket.getOutputStream())

    fun enviar(comando: String, timeoutMs: Long): String {
        while (entrada.available() > 0) entrada.read()
        salida.write((comando + "\r").toByteArray(Charsets.US_ASCII))
        salida.flush()
        val fin = System.nanoTime() + timeoutMs * 1_000_000
        val respuesta = StringBuilder()
        while (System.nanoTime() < fin) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
            if (entrada.available() == 0) { Thread.sleep(1); continue }
            val caracter = entrada.read()
            if (caracter < 0) throw IOException()
            if (caracter == '>'.code) return respuesta.toString()
            if (respuesta.length >= 8192) throw IOException()
            respuesta.append(caracter.toChar())
        }
        return respuesta.toString()
    }

    fun inicializar(): String? {
        listOf("ATZ", "ATE0", "ATL0", "ATH0", "ATAT2", "ATSP0").forEach { enviar(it, 5_000) }
        enviar("0100", 10_000)
        return enviar("ATDPN", 2_000).lineSequence().map { it.trim() }
            .firstOrNull { it.matches(Regex("A?[0-9A-C]", RegexOption.IGNORE_CASE)) }
    }

    fun leerPid(pid: Int): Double? = PidsObd.parsear(pid, enviar(PidsObd.comando(pid), 300))
    fun leerGps(): PosicionObd? = PosicionObd.parsear(enviar("ATGPS", 300))
}
