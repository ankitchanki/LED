package com.glowled.app.core

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class TransferTest {
    @Before fun reset() = ProgressStore.clear()

    @Test fun directionRequiresUnambiguousEvidence() {
        assertEquals(TransferDirection.UPLOAD, TransferDirection.fromText("Uploading video · 42%"))
        assertEquals(TransferDirection.DOWNLOAD, TransferDirection.fromText("Downloading photo"))
        assertEquals(TransferDirection.UNKNOWN, TransferDirection.fromText("Syncing files · 42%"))
        assertEquals(TransferDirection.UNKNOWN, TransferDirection.fromText("Uploading and downloading"))
        assertEquals(TransferDirection.UPLOAD, TransferDirection.fromText("Enviando arquivo"))
        assertEquals(TransferDirection.DOWNLOAD, TransferDirection.fromText("डाउनलोड हो रहा है"))
    }

    @Test fun initialsAndColoursIdentifyAppsWithoutKeepingTitles() {
        assertEquals("W", TransferInfo.initial(" WhatsApp"))
        assertEquals("I", TransferInfo.initial("Instagram"))
        assertEquals("↕", TransferInfo.initial(""))
        assertEquals(0xFF25D366.toInt(), TransferInfo.appColor("com.whatsapp", 123))
        assertEquals(0xFFE1306C.toInt(), TransferInfo.appColor("com.instagram.android", 123))
        assertEquals(123, TransferInfo.appColor("other.app", 123))
    }

    @Test fun determinateIndeterminateAndFinishedProgressAreDistinct() {
        assertEquals(0.25f, ProgressStore.fractionOf(100, 25, false))
        assertEquals(0f, ProgressStore.fractionOf(100, -2, false))
        assertEquals(-1f, ProgressStore.fractionOf(0, 0, true))
        assertNull(ProgressStore.fractionOf(0, 0, false))
        assertNull(ProgressStore.fractionOf(100, 100, false))
        assertNull(ProgressStore.fractionOf(100, 120, false))
    }

    @Test fun transferCanChangeDirectionWithoutChangingItsPercentage() {
        assertTrue(ProgressStore.update("a", "chat", .4f, 1, "W", TransferDirection.UPLOAD, now = 1))
        assertTrue(ProgressStore.update("a", "chat", .4f, 1, "W", TransferDirection.DOWNLOAD, now = 2))
        assertEquals(TransferDirection.DOWNLOAD, ProgressStore.current(2)?.direction)
        assertFalse(ProgressStore.update("a", "chat", .401f, 1, "W", TransferDirection.DOWNLOAD, now = 3))
    }

    @Test fun completingTransferRestoresPreviousTransferAndStaleTransfersDisappear() {
        ProgressStore.update("a", "first", .2f, 1, now = 10)
        ProgressStore.update("b", "second", -1f, 2, now = 11)
        assertEquals("second", ProgressStore.current(11)?.pkg)
        assertTrue(ProgressStore.update("b", "second", null, 2, now = 12))
        assertEquals("first", ProgressStore.current(12)?.pkg)
        assertNull(ProgressStore.current(180_010))
    }

    @Test fun latestUpdateWinsEvenWhenClockTicksAreEqual() {
        ProgressStore.update("a", "first", .2f, 1, now = 10)
        ProgressStore.update("b", "second", .2f, 1, now = 10)
        assertEquals("second", ProgressStore.current(10)?.pkg)
        assertTrue(ProgressStore.update("a", "first", .2f, 1, now = 10))
        assertEquals("first", ProgressStore.current(10)?.pkg)
    }

    @Test fun unchangedPercentageRefreshesExpiryWithoutAnUnnecessaryRedraw() {
        ProgressStore.update("a", "chat", .2f, 1, now = 10)
        assertFalse(ProgressStore.update("a", "chat", .201f, 1, now = 100_000))
        assertNotNull(ProgressStore.current(180_010))
        assertEquals(99_990L, ProgressStore.expiryDelay(180_010))
        assertNull(ProgressStore.current(280_000))
    }

    @Test fun reconnectDropsRemovedTransfersWithoutLosingActiveOnes() {
        ProgressStore.update("a", "first", .2f, 1, now = 10)
        ProgressStore.update("b", "second", .5f, 1, now = 11)
        assertTrue(ProgressStore.retainKeys(setOf("a")))
        assertEquals("first", ProgressStore.current(12)?.pkg)
        assertFalse(ProgressStore.retainKeys(setOf("a")))
        assertTrue(ProgressStore.retainKeys(emptySet()))
        assertNull(ProgressStore.current(12))
    }

    @Test fun newStylesRoundTripAndUnknownSettingsRemainCompatible() {
        assertEquals(LedStyle.ORBIT, LedStyle.from("orbit"))
        assertEquals(LedStyle.SPARKLE, LedStyle.from("sparkle"))
        assertEquals(LedStyle.PULSE, LedStyle.from("unknown"))
    }
}
