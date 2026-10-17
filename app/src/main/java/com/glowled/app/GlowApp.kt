package com.glowled.app

import android.app.Application
import com.glowled.app.core.LedController

class GlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LedController.init(this)
    }
}
