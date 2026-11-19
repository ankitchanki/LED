package com.glowled.app.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.util.Locale
import kotlin.math.abs

data class BatteryInfo(
    val level: Int,
    val voltageMv: Int,
    val currentMa: Int,
    val powerWatts: Float,
    val isCharging: Boolean,
) {
    fun formatString(
        showPercent: Boolean,
        showVoltage: Boolean,
        showCurrent: Boolean,
        showPower: Boolean,
    ): String? {
        val row1 = mutableListOf<String>()
        val row2 = mutableListOf<String>()

        if (showPercent && level in 0..100) {
            row1.add("$level%")
        }
        if (showVoltage && voltageMv > 0) {
            val vVolts = if (voltageMv > 100) voltageMv / 1000f else voltageMv.toFloat()
            row1.add(String.format(Locale.US, "%.2fV", vVolts))
        }
        if (showCurrent && abs(currentMa) > 0) {
            row2.add(String.format(Locale.US, "%d mA", abs(currentMa)))
        }
        if (showPower && powerWatts > 0.05f) {
            row2.add(String.format(Locale.US, "%.1fW", powerWatts))
        }

        return when {
            row1.isNotEmpty() && row2.isNotEmpty() -> "${row1.joinToString(" · ")}\n${row2.joinToString(" · ")}"
            row1.isNotEmpty() -> row1.joinToString(" · ")
            row2.isNotEmpty() -> row2.joinToString(" · ")
            else -> null
        }
    }
}

object BatteryStats {
    fun getInfo(context: Context): BatteryInfo {
        val bm = context.getSystemService(BatteryManager::class.java)
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val level = if (rawLevel >= 0 && scale > 0) (rawLevel * 100 / scale).coerceIn(0, 100)
                    else (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100).coerceIn(0, 100)

        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) ?: 0
        val isCharging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING || bm?.isCharging == true

        var rawCurrent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        if (rawCurrent == Int.MIN_VALUE || rawCurrent == Int.MAX_VALUE) rawCurrent = 0
        var currentMa = when {
            abs(rawCurrent) > 10_000 -> rawCurrent / 1000
            else -> rawCurrent
        }

        if (currentMa == 0 && isCharging) {
            val avg = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE) ?: 0
            if (avg != Int.MIN_VALUE && avg != Int.MAX_VALUE && avg != 0) {
                currentMa = if (abs(avg) > 10_000) avg / 1000 else avg
            }
        }

        val effectiveCurrentMa = if (isCharging) abs(currentMa) else -abs(currentMa)

        val vVolts = if (voltageMv > 100) voltageMv / 1000f else voltageMv.toFloat()
        val cAmps = abs(effectiveCurrentMa) / 1000f
        val powerWatts = if (vVolts > 0f && cAmps > 0f) vVolts * cAmps else 0f

        return BatteryInfo(
            level = level,
            voltageMv = voltageMv,
            currentMa = effectiveCurrentMa,
            powerWatts = powerWatts,
            isCharging = isCharging,
        )
    }
}
