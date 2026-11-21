package com.uninorte.locator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED || !LocationTrackingService.isActive(context)) return
        if (Vehiculo.obtener(context) != Vehiculo.VEHICULO_2) {
            // No dejar un envío GPS antiguo marcado como activo en modo producto.
            LocationTrackingService.markInactive(context)
            return
        }
        try {
            LocationTrackingService.start(context)
        } catch (e: Exception) {
            // Android 12+ puede impedir iniciar el servicio desde el arranque.
            LocationTrackingService.markInactive(context)
        }
    }
}
