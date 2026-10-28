package com.glowled.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.glowled.app.core.LedController

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON" -> Unit
            else -> return
        }
        LedController.init(context)
        NotifyListenerService.rebind(context)
        // After an update the old binding is gone; make sure the listener comes back.
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) NotifyListenerService.forceReconnect(context)
    }
}

class PulseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LedController.init(context)
        LedController.onPulseAlarm()
    }
}
