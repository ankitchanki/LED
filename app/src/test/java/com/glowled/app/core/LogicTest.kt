package com.glowled.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationStoreTest {

    @Before
    fun reset() = NotificationStore.clear()

    @Test
    fun colorsAreNewestFirstAndDistinct() {
        NotificationStore.put(NotificationStore.Entry("a", "p1", 0xFFFF0000.toInt(), 1))
        NotificationStore.put(NotificationStore.Entry("b", "p2", 0xFF00FF00.toInt(), 3))
        NotificationStore.put(NotificationStore.Entry("c", "p1", 0xFFFF0000.toInt(), 2))
        assertArrayEquals(intArrayOf(0xFF00FF00.toInt(), 0xFFFF0000.toInt()), NotificationStore.colors())
    }

    @Test
    fun repostedSeenNotificationLightsAgain() {
        // e.g. "clear after unlock", then the same WhatsApp chat (same key) gets a new message.
        NotificationStore.put(NotificationStore.Entry("a", "p", 1, 1))
        NotificationStore.markAllSeen()
        NotificationStore.put(NotificationStore.Entry("a", "p", 1, 1))
        assertTrue(NotificationStore.isEmpty()) // a silent update stays seen
        assertTrue(NotificationStore.put(NotificationStore.Entry("a", "p", 1, 2), alert = true))
        assertFalse(NotificationStore.isEmpty())
    }

    @Test
    fun seenNotificationsDoNotLight() {
        NotificationStore.put(NotificationStore.Entry("a", "p", 1, 1))
        NotificationStore.markAllSeen()
        assertTrue(NotificationStore.isEmpty())
        NotificationStore.put(NotificationStore.Entry("b", "p", 2, 2))
        assertFalse(NotificationStore.isEmpty())
        assertArrayEquals(intArrayOf(2), NotificationStore.colors())
    }

    @Test
    fun appsAreGroupedNewestFirstWithCounts() {
        NotificationStore.put(NotificationStore.Entry("a1", "chat", 1, 1))
        NotificationStore.put(NotificationStore.Entry("b1", "mail", 2, 2))
        NotificationStore.put(NotificationStore.Entry("a2", "chat", 1, 3))
        val apps = NotificationStore.apps()
        org.junit.Assert.assertEquals(listOf("chat", "mail"), apps.map { it.pkg })
        org.junit.Assert.assertEquals(listOf(2, 1), apps.map { it.count })
        NotificationStore.markAllSeen()
        assertTrue(NotificationStore.apps().isEmpty())
    }

    @Test
    fun replaceAllReportsChangesOnly() {
        val list = listOf(NotificationStore.Entry("a", "p", 1, 1))
        assertTrue(NotificationStore.replaceAll(list))
        assertFalse(NotificationStore.replaceAll(list))
        assertTrue(NotificationStore.replaceAll(emptyList()))
    }

    @Test
    fun putReportsNewOrRecoloured() {
        assertTrue(NotificationStore.put(NotificationStore.Entry("a", "p", 1, 1)))
        assertFalse(NotificationStore.put(NotificationStore.Entry("a", "p", 1, 5)))
        assertTrue(NotificationStore.put(NotificationStore.Entry("a", "p", 2, 6)))
        assertTrue(NotificationStore.remove("a"))
        assertFalse(NotificationStore.remove("a"))
    }
}

class NotificationContentTest {
    @Test
    fun mediaKindFromText() {
        org.junit.Assert.assertEquals(NotificationStore.Kind.IMAGE, NotificationContent.guessKind("📷 Photo", null))
        org.junit.Assert.assertEquals(NotificationStore.Kind.VIDEO, NotificationContent.guessKind("🎥 Video", null))
        org.junit.Assert.assertEquals(NotificationStore.Kind.AUDIO, NotificationContent.guessKind("🎤 Voice message (0:12)", null))
        org.junit.Assert.assertEquals(NotificationStore.Kind.FILE, NotificationContent.guessKind("📄 report.pdf", null))
        org.junit.Assert.assertEquals(NotificationStore.Kind.TEXT, NotificationContent.guessKind("See you at 6", null))
    }

    @Test
    fun mediaKindFromMime() {
        org.junit.Assert.assertEquals(NotificationStore.Kind.IMAGE, NotificationContent.guessKind("", "image/jpeg"))
        org.junit.Assert.assertEquals(NotificationStore.Kind.VIDEO, NotificationContent.guessKind(null, "video/mp4"))
        org.junit.Assert.assertEquals(NotificationStore.Kind.AUDIO, NotificationContent.guessKind(null, "audio/ogg"))
    }

    @Test
    fun contentChangeCountsAsChange() {
        NotificationStore.clear()
        val a = NotificationStore.Entry("k", "p", 1, 1, title = "Ankit", lines = listOf("hi"))
        assertTrue(NotificationStore.put(a))
        assertFalse(NotificationStore.put(a.copy(time = 2)))
        assertTrue(NotificationStore.put(a.copy(lines = listOf("hi", "are you there?"))))
        org.junit.Assert.assertEquals(2, NotificationStore.entriesFor("p").first().lines.size)
    }
}

class QuietHoursTest {
    @Test
    fun sameDayWindow() {
        assertTrue(PowerGuard.inWindow(13 * 60, 12 * 60, 14 * 60))
        assertFalse(PowerGuard.inWindow(14 * 60, 12 * 60, 14 * 60))
    }

    @Test
    fun overnightWindow() {
        assertTrue(PowerGuard.inWindow(23 * 60 + 30, 23 * 60, 7 * 60))
        assertTrue(PowerGuard.inWindow(3 * 60, 23 * 60, 7 * 60))
        assertFalse(PowerGuard.inWindow(12 * 60, 23 * 60, 7 * 60))
    }

    @Test
    fun emptyWindowNeverMatches() = assertFalse(PowerGuard.inWindow(0, 600, 600))
}
