package com.uninorte.locator

internal data class ResumenRecorridos(val cantidad: Int, val litros: Double, val costoCop: Double,
    val litrosMedidos: Double? = null, val costoMedidoCop: Double? = null, val emulados: Int = 0)

internal fun resumirRecorridos(recorridos: List<Recorrido>): ResumenRecorridos {
    val reales = recorridos.filterNot { it.emulado }
    return ResumenRecorridos(
        cantidad = reales.size,
        litros = reales.sumOf { it.litros },
        costoCop = reales.sumOf { it.costoCop ?: 0.0 },
        litrosMedidos = reales.mapNotNull { it.litrosMedidos }.takeIf { it.isNotEmpty() }?.sum(),
        costoMedidoCop = reales.mapNotNull { it.costoMedidoCop }.takeIf { it.isNotEmpty() }?.sum(),
        emulados = recorridos.count { it.emulado }
    )
}
