package com.glowled.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun comparesVersionsNumerically() {
        assertTrue(Updater.isNewer("2.8.0", "2.7.0"))
        assertTrue(Updater.isNewer("v2.10.0", "2.9.9"))
        assertTrue(Updater.isNewer("3.0", "2.99.1"))
        assertFalse(Updater.isNewer("2.7.0", "2.7.0"))
        assertFalse(Updater.isNewer("2.7.0", "2.7.0-debug"))
        assertFalse(Updater.isNewer("2.6.9", "2.7.0"))
    }

    @Test fun releaseNotesBecomePlainText() {
        val body = """
            # Glow LED v2.8.0

            ✅ **Signed build** (official Glow LED key). Installs over v1.3.0 and
            later and keeps your settings.

            ## In-app updates
            - **Settings → Updates** shows *What's new* and installs
              `GlowLED-latest.apk`.
            - Checks every few hours.

            ## Fixes
            Wrapped paragraph
            continues here.
        """.trimIndent()
        assertEquals(
            "In-app updates\n• Settings → Updates shows What's new and installs GlowLED-latest.apk.\n" +
                "• Checks every few hours.\n\nFixes\nWrapped paragraph continues here.",
            Updater.plainNotes(body),
        )
    }
}
