package com.glowled.app.ui

import com.glowled.app.R

/** The small coloured icon at the start of each settings row, by preference key. */
object RowIcons {
    const val RED = 0xFFFF453A.toInt()
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val TEAL = 0xFF00C7BE.toInt()
    const val CYAN = 0xFF32ADE6.toInt()
    const val BLUE = 0xFF0A84FF.toInt()
    const val INDIGO = 0xFF5E5CE6.toInt()
    const val PURPLE = 0xFFBF5AF2.toInt()
    const val PINK = 0xFFFF375F.toInt()
    const val BROWN = 0xFFAC8E68.toInt()
    const val GREY = 0xFF8E8E93.toInt()
    const val DARK_GREY = 0xFF636366.toInt()

    private val byKey = mapOf(
        // Style
        "use_notif_color" to (R.drawable.ic_s_bell to RED),
        "use_icon_color" to (R.drawable.ic_s_grid to BLUE),
        "multi_mode" to (R.drawable.ic_s_layers to INDIGO),
        "glow" to (R.drawable.ic_s_sun to ORANGE),
        "rotate_ring" to (R.drawable.ic_s_rotate to INDIGO),
        // Screen off
        "aod_enabled" to (R.drawable.ic_s_moon to INDIGO),
        "aod_on_screen_off" to (R.drawable.ic_s_lock to DARK_GREY),
        "aod_on_new" to (R.drawable.ic_s_bell to RED),
        "charge_aod" to (R.drawable.ic_s_bolt to GREEN),
        "show_app_info" to (R.drawable.ic_s_grid to BLUE),
        "aod_icons" to (R.drawable.ic_s_ring to GREY),
        "app_info_always" to (R.drawable.ic_s_pin to CYAN),
        "droplet" to (R.drawable.ic_s_drop to TEAL),
        "show_content" to (R.drawable.ic_s_eye to ORANGE),
        "peek_actions" to (R.drawable.ic_s_reply to GREEN),
        "double_tap_wake" to (R.drawable.ic_s_tap to PURPLE),
        "proximity" to (R.drawable.ic_s_phone to BROWN),
        "burn_in" to (R.drawable.ic_s_shield to PINK),
        "aod_timeout" to (R.drawable.ic_s_clock to BLUE),
        "pulse_interval" to (R.drawable.ic_s_repeat to ORANGE),
        "off_delay" to (R.drawable.ic_s_timer to GREY),
        "aod_method" to (R.drawable.ic_s_gear to DARK_GREY),
        // Screen on
        "overlay_enabled" to (R.drawable.ic_s_ring to BLUE),
        "overlay_lock_only" to (R.drawable.ic_s_lock to DARK_GREY),
        "charge_led" to (R.drawable.ic_s_bolt to GREEN),
        "battery_indicator" to (R.drawable.ic_s_battery to GREY),
        "progress_led" to (R.drawable.ic_s_download to BLUE),
        "progress_initials" to (R.drawable.ic_s_updown to BLUE),
        "progress_app_color" to (R.drawable.ic_s_drop to PINK),
        "progress_color" to (R.drawable.ic_s_palette to PURPLE),
        // Alerts
        "app_mode" to (R.drawable.ic_s_filter to BLUE),
        "ignore_low" to (R.drawable.ic_s_mute to GREY),
        "respect_dnd" to (R.drawable.ic_s_moon to INDIGO),
        "clear_on_unlock" to (R.drawable.ic_s_unlock to GREEN),
        // Battery
        "eco_anim" to (R.drawable.ic_s_leaf to GREEN),
        "age_fade" to (R.drawable.ic_s_clock to ORANGE),
        "motion_glance" to (R.drawable.ic_s_motion to PURPLE),
        "low_refresh" to (R.drawable.ic_s_gauge to BLUE),
        "fps_cap" to (R.drawable.ic_s_gauge to TEAL),
        "quiet_enabled" to (R.drawable.ic_s_moon to INDIGO),
        // Settings
        "app_theme" to (R.drawable.ic_s_theme to DARK_GREY),
        "haptics" to (R.drawable.ic_s_vibrate to PINK),
    )

    fun of(key: String): Pair<Int, Int>? = byKey[key]

    private val cycle = intArrayOf(ORANGE, GREEN, BLUE, INDIGO, CYAN, BROWN, GREY, PINK)

    /** A colour for rows without their own (cycled, so neighbours differ). */
    fun palette(i: Int) = cycle[i % cycle.size]
}
