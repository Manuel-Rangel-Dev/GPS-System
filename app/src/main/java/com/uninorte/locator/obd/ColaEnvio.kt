package com.uninorte.locator.obd

import java.util.ArrayDeque

// Operaciones atómicas: una confirmación nunca retira otra fila si hubo desborde.
class ColaEnvio<T>(private val capacidad: Int = 400) {
    init { require(capacidad > 0) }
    private val filas = ArrayDeque<T>()
    var descartadas = 0L
        private set
    @Synchronized fun agregar(fila: T) {
        if (filas.size == capacidad) { filas.removeFirst(); descartadas++ }
        filas.addLast(fila)
    }
    @Synchronized fun primera(): T? = filas.peekFirst()
    @Synchronized fun confirmar(fila: T) { if (filas.peekFirst() === fila) filas.removeFirst() }
    @Synchronized fun cantidad(): Int = filas.size
}
