package com.uninorte.locator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED &&
            LocationTrackingService.isActive(context)
        ) {
            try {
                LocationTrackingService.start(context)
            } catch (e: Exception) {
                // Android 12+ puede impedir iniciar el servicio desde el arranque.
                LocationTrackingService.markInactive(context)
            }
        }
    }
}
