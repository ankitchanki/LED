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
    fun seenNotificationsDoNotLight() {
        NotificationStore.put(NotificationStore.Entry("a", "p", 1, 1))
        NotificationStore.markAllSeen()
        assertTrue(NotificationStore.isEmpty())
        NotificationStore.put(NotificationStore.Entry("b", "p", 2, 2))
        assertFalse(NotificationStore.isEmpty())
        assertArrayEquals(intArrayOf(2), NotificationStore.colors())
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
