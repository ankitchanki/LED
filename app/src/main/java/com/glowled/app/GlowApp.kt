package com.glowled.app

import android.app.Application
import com.glowled.app.core.LedController

class GlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LedController.init(this)
        applyAppTheme(LedController.prefs.appTheme)
    }
}

/** Dark (pure black) by default; light or follow the system on request. Recreates open screens. */
fun applyAppTheme(theme: String) {
    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
        when (theme) {
            "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            "system" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
        },
    )
}
