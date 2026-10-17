package com.glowled.app.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import java.util.Calendar

/** Cheap checks that decide whether lighting the screen is worth the battery right now. */
object PowerGuard {

    fun batteryLevel(context: Context): Int {
        val bm = context.getSystemService(BatteryManager::class.java)
        val v = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (v in 0..100) return v
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 100
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level < 0) 100 else level * 100 / scale
    }

    fun isCharging(context: Context): Boolean =
        context.getSystemService(BatteryManager::class.java)?.isCharging == true

    fun inQuietHours(prefs: Prefs): Boolean {
        if (!prefs.quietEnabled) return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return inWindow(now, prefs.quietStart, prefs.quietEnd)
    }

    /** True if minute-of-day [now] is inside [start, end), handling windows that cross midnight. */
    fun inWindow(now: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> now in start until end
        else -> now >= start || now < end
    }

    fun inCall(context: Context): Boolean {
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE
    }

    /** Returns null if allowed, otherwise a short reason (shown on the dashboard). */
    fun blockReason(context: Context, prefs: Prefs): String? = when {
        inQuietHours(prefs) -> "quiet hours"
        !isCharging(context) && batteryLevel(context) < prefs.minBattery -> "low battery"
        inCall(context) -> "in a call"
        else -> null
    }
}
