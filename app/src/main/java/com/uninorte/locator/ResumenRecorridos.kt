package com.uninorte.locator

internal data class ResumenRecorridos(val cantidad: Int, val litros: Double, val costoCop: Double)

internal fun resumirRecorridos(recorridos: List<Recorrido>): ResumenRecorridos {
    val reales = recorridos.filterNot { it.emulado }
    return ResumenRecorridos(
        cantidad = reales.size,
        litros = reales.sumOf { it.litros },
        costoCop = reales.sumOf { it.costoCop ?: 0.0 }
    )
}
