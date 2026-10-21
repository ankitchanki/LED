package com.glowled.app.core

import android.content.Context
import android.content.SharedPreferences

/** Typed access to all settings. Keys match res/xml/preferences.xml. */
class Prefs(context: Context) {
    // Same file androidx.preference used, so settings survive the upgrade.
    val sp: SharedPreferences = context.applicationContext.let {
        it.getSharedPreferences("${it.packageName}_preferences", Context.MODE_PRIVATE)
    }

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun str(key: String, def: String) = sp.getString(key, def) ?: def
    private fun strInt(key: String, def: Int) = str(key, def.toString()).toIntOrNull() ?: def

    /** Settings screens: "dark" (pure black, default), "light" or "system". */
    val appTheme get() = str("app_theme", "dark")
    /** Haptic feedback in the settings (sliders get stronger as the value goes up). */
    val haptics get() = bool("haptics", true)

    var enabled: Boolean
        get() = bool(K_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_ENABLED, v).apply()

    // Look
    val style: LedStyle get() = LedStyle.from(str("style", LedStyle.PULSE.key))
    val multiMode: MultiMode get() = MultiMode.from(str("multi_mode", MultiMode.SEGMENTS.key))
    var defaultColor: Int
        get() = int("default_color", DEFAULT_COLOR)
        set(v) = sp.edit().putInt("default_color", v).apply()
    val useNotificationColor get() = bool("use_notif_color", true)
    val useIconColor get() = bool("use_icon_color", true)
    val speed get() = int("speed", 5).coerceIn(1, 10)
    val thicknessDp get() = int("thickness", 3).coerceIn(1, 16)
    val gapDp get() = int("gap", 2).coerceIn(0, 20)
    val glow get() = bool("glow", true)
    /** Turn the split-by-app ring slowly around the camera. */
    val rotateRing get() = bool("rotate_ring", true)
    val ledAlpha get() = int("led_alpha", 100).coerceIn(10, 100)
    val edgeRadiusDp get() = int("edge_radius", 40).coerceIn(0, 120)

    // Screen-off (AOD) mode
    val aodEnabled get() = bool("aod_enabled", true)
    val aodOnScreenOff get() = bool("aod_on_screen_off", true)
    val aodOnNew get() = bool("aod_on_new", true)
    val aodTimeoutSec get() = strInt("aod_timeout", 120)
    val pulseIntervalMin get() = strInt("pulse_interval", 0)
    val pulseDurationSec get() = int("pulse_duration", 10).coerceIn(3, 120)
    val aodBrightness get() = int("aod_brightness", 3).coerceIn(1, 100)
    val proximity get() = bool("proximity", true)
    val doubleTapWake get() = bool("double_tap_wake", true)
    val showAppInfo get() = bool("show_app_info", true)
    val appInfoAlways get() = bool("app_info_always", false)
    /** Tap an app icon on the always-on screen to see who sent what. */
    val showContent get() = bool("show_content", true)
    /** Liquid droplet from the camera when a notification arrives. */
    val droplet get() = bool("droplet", true)
    /** Reply / Mark as read buttons on the message peek. */
    val peekActions get() = bool("peek_actions", true)
    /** Always-on icons as monochrome glyphs (One UI AOD style) instead of full-colour app icons. */
    val aodIcons get() = bool("aod_icons", true)

    /** "auto", "activity" (lock-screen window) or "overlay" (accessibility overlay). */
    val aodMethod get() = str("aod_method", "auto")
    val burnIn get() = bool("burn_in", true)
    val lowRefresh get() = bool("low_refresh", true)
    val fpsCap get() = strInt("fps_cap", 30)
    /** How long to wait after the screen goes off before lighting it again. */
    val offDelayMs get() = strInt("off_delay", 800)
    /** Always-on screen: animate for a few seconds, then hold a still ring (no redraws at all). */
    val ecoAnim get() = bool("eco_anim", true)
    /** The longer a notification waits, the dimmer its light (bright = new, at a glance). */
    val ageFade get() = bool("age_fade", true)
    /** After the light times out, show it again when the phone moves (hardware motion trigger). */
    val motionGlance get() = bool("motion_glance", true)

    // Screen-on overlay
    val overlayEnabled get() = bool("overlay_enabled", true)
    val overlayLockOnly get() = bool("overlay_lock_only", false)

    // Charging and download rings (screen on, also over the lock screen)
    val batteryIndicator get() = bool("battery_indicator", true)
    val progressInitials get() = bool("progress_initials", true)
    val progressAppColor get() = bool("progress_app_color", true)
    val uploadColor get() = int("upload_color", 0xFF00E676.toInt())
    val downloadColor get() = int("download_color", 0xFF2979FF.toInt())
    val chargeLed get() = bool("charge_led", true)
    val chargeColorLow get() = int("charge_color_low", 0xFFFF1744.toInt())
    val chargeColorMid get() = int("charge_color_mid", 0xFFFFC400.toInt())
    val chargeColorHigh get() = int("charge_color_high", 0xFF00E676.toInt())
    val progressLed get() = bool("progress_led", true)
    /** 0 = the downloading app's colour. */
    val progressColor get() = int("progress_color", 0)

    /** Battery-level colour: below 20 %, 20-79 %, 80 % and up. */
    fun chargeColor(level: Int) = when {
        level < CHARGE_LOW -> chargeColorLow
        level < CHARGE_HIGH -> chargeColorMid
        else -> chargeColorHigh
    }

    // Filters
    val appMode get() = str("app_mode", "all")
    val ignoreLowImportance get() = bool("ignore_low", true)
    val respectDnd get() = bool("respect_dnd", true)
    val clearOnUnlock get() = bool("clear_on_unlock", false)

    // Power
    val minBattery get() = int("min_battery", 10)
    val quietEnabled get() = bool("quiet_enabled", false)
    val quietStart get() = int("quiet_start", 23 * 60)
    val quietEnd get() = int("quiet_end", 7 * 60)

    // Cutout geometry, stored in natural-portrait display pixels at [cutRefWidth].
    val cutShape: CutoutShape get() = CutoutShape.from(str("cut_shape", CutoutShape.CIRCLE.key))
    val cutCx get() = sp.getFloat("cut_cx", -1f)
    val cutCy get() = sp.getFloat("cut_cy", -1f)
    val cutW get() = sp.getFloat("cut_w", -1f)
    val cutH get() = sp.getFloat("cut_h", -1f)
    val cutRefWidth get() = int("cut_ref_w", 0)
    val hasCutout get() = cutCx >= 0 && cutRefWidth > 0

    fun saveCutout(shape: CutoutShape, cx: Float, cy: Float, w: Float, h: Float, refWidth: Int) {
        val e = sp.edit()
        e.putString("cut_shape", shape.key)
        e.putFloat("cut_cx", cx)
        e.putFloat("cut_cy", cy)
        e.putFloat("cut_w", w)
        e.putFloat("cut_h", h)
        e.putInt("cut_ref_w", refWidth)
        e.apply()
    }

    // Per-app
    fun appEnabled(pkg: String): Boolean =
        sp.getBoolean("app_en_$pkg", appMode == "all")

    fun setAppEnabled(pkg: String, on: Boolean) = sp.edit().putBoolean("app_en_$pkg", on).apply()

    /** 0 means "automatic". */
    fun appColor(pkg: String): Int = sp.getInt("app_color_$pkg", 0)

    fun setAppColor(pkg: String, color: Int) {
        if (color == 0) sp.edit().remove("app_color_$pkg").apply()
        else sp.edit().putInt("app_color_$pkg", color).apply()
    }

    companion object {
        const val K_ENABLED = "enabled"
        const val DEFAULT_COLOR = 0xFF00E5FF.toInt()
        const val CHARGE_LOW = 20
        const val CHARGE_HIGH = 80
    }
}

enum class LedStyle(val key: String) {
    STATIC("static"), BREATHE("breathe"), PULSE("pulse"), BLINK("blink"),
    HEARTBEAT("heartbeat"), SPIN("spin"), RAINBOW("rainbow"), EDGE("edge"), EDGE_PULSE("edge_pulse"),
    ORBIT("orbit"), SPARKLE("sparkle");

    val isEdge get() = this == EDGE || this == EDGE_PULSE

    companion object {
        fun from(key: String) = entries.firstOrNull { it.key == key } ?: PULSE
    }
}

enum class MultiMode(val key: String) {
    CYCLE("cycle"), SEGMENTS("segments"), LATEST("latest");

    companion object {
        fun from(key: String) = entries.firstOrNull { it.key == key } ?: SEGMENTS
    }
}

enum class CutoutShape(val key: String) {
    CIRCLE("circle"), PILL("pill"), NOTCH("notch"), DOT("dot");

    companion object {
        fun from(key: String) = entries.firstOrNull { it.key == key } ?: CIRCLE
    }
}
