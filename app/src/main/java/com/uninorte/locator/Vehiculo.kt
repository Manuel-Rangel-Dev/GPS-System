package com.uninorte.locator

import android.content.Context

enum class Vehiculo { KIA_K3, VEHICULO_2;
    companion object {
        fun obtener(contexto: Context): Vehiculo = runCatching {
            valueOf(contexto.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                .getString("vehiculo", VEHICULO_2.name)!!)
        }.getOrDefault(VEHICULO_2)
        fun guardar(contexto: Context, vehiculo: Vehiculo) {
            contexto.getSharedPreferences("app_settings", Context.MODE_PRIVATE).edit()
                .putString("vehiculo", vehiculo.name).apply()
        }
    }
}
