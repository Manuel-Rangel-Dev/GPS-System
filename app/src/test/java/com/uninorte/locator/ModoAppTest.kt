package com.uninorte.locator

import org.junit.Assert.assertEquals
import org.junit.Test

class ModoAppTest {
    @Test fun modoPorDefectoYPreferenciasInvalidasSonProducto() {
        assertEquals(ModoApp.PRODUCTO, ModoApp.desdePreferencia(null))
        assertEquals(ModoApp.PRODUCTO, ModoApp.desdePreferencia(""))
        assertEquals(ModoApp.PRODUCTO, ModoApp.desdePreferencia("desconocido"))
    }

    @Test fun recuperaLosDosModosGuardados() {
        ModoApp.values().forEach { modo ->
            assertEquals(modo, ModoApp.desdePreferencia(modo.name))
        }
    }

    @Test fun vehiculoEfectivoEnLosCuatroCasos() {
        assertEquals(Vehiculo.KIA_K3, vehiculoEfectivo(ModoApp.PRODUCTO, Vehiculo.KIA_K3))
        assertEquals(Vehiculo.KIA_K3, vehiculoEfectivo(ModoApp.PRODUCTO, Vehiculo.VEHICULO_2))
        assertEquals(Vehiculo.KIA_K3, vehiculoEfectivo(ModoApp.DESARROLLADOR, Vehiculo.KIA_K3))
        assertEquals(Vehiculo.VEHICULO_2, vehiculoEfectivo(ModoApp.DESARROLLADOR, Vehiculo.VEHICULO_2))
    }

    @Test fun emuladorEfectivoEnLosCuatroCasos() {
        assertEquals(false, emuladorEfectivo(ModoApp.PRODUCTO, false))
        assertEquals(false, emuladorEfectivo(ModoApp.PRODUCTO, true))
        assertEquals(false, emuladorEfectivo(ModoApp.DESARROLLADOR, false))
        assertEquals(true, emuladorEfectivo(ModoApp.DESARROLLADOR, true))
    }

    @Test fun volverADesarrolladorRecuperaLasPreferencias() {
        val vehiculoGuardado = Vehiculo.VEHICULO_2
        val emuladorGuardado = true
        repeat(2) {
            assertEquals(Vehiculo.KIA_K3, vehiculoEfectivo(ModoApp.PRODUCTO, vehiculoGuardado))
            assertEquals(false, emuladorEfectivo(ModoApp.PRODUCTO, emuladorGuardado))
            assertEquals(vehiculoGuardado, vehiculoEfectivo(ModoApp.DESARROLLADOR, vehiculoGuardado))
            assertEquals(emuladorGuardado, emuladorEfectivo(ModoApp.DESARROLLADOR, emuladorGuardado))
        }
    }
}
