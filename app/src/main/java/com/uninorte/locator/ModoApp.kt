package com.uninorte.locator

import android.content.Context

enum class ModoApp { PRODUCTO, DESARROLLADOR;
    companion object {
        fun obtener(contexto: Context): ModoApp = desdePreferencia(
            contexto.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getString("modo_app", PRODUCTO.name))

        fun desdePreferencia(guardado: String?): ModoApp = runCatching {
            valueOf(guardado ?: PRODUCTO.name)
        }.getOrDefault(PRODUCTO)

        fun guardar(contexto: Context, modo: ModoApp) {
            contexto.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
                .putString("modo_app", modo.name).apply()
        }
    }
}

fun vehiculoEfectivo(modo: ModoApp, guardado: Vehiculo): Vehiculo =
    if (modo == ModoApp.PRODUCTO) Vehiculo.KIA_K3 else guardado

fun emuladorEfectivo(modo: ModoApp, guardado: Boolean): Boolean =
    modo == ModoApp.DESARROLLADOR && guardado
