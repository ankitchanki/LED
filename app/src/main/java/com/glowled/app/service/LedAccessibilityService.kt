package com.glowled.app.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.glowled.app.core.LedController

/**
 * Used only as a trusted window host: it lets the LED draw over the lock screen and status bar,
 * start the always-on screen from the background, and turn the screen back off afterwards.
 * It subscribes to no accessibility events and reads no screen content.
 */
class LedAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LedController.init(this)
        com.glowled.app.core.DebugLog.add("Accessibility service connected")
        OverlayHost.hide() // re-create on the trusted window type
        LedController.updateOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        if (instance === this) {
            AodOverlay.forceRemove()
            OverlayHost.hide()
            instance = null
            LedController.updateOverlay()
        }
    }

    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        @Volatile
        var instance: LedAccessibilityService? = null
            private set
    }
}
