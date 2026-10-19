package com.glowled.app.core

import android.app.Notification
import android.os.SystemClock

/**
 * Notifications that show a progress bar (downloads, uploads, installs), for the download ring.
 * Kept apart from [NotificationStore]: they never count as unread and never wake the screen.
 */
object ProgressStore {

    data class Item(val pkg: String, val fraction: Float, val color: Int, val updated: Long)

    private val items = LinkedHashMap<String, Item>()

    /** Reads the progress bar of [n], if it has a determinate one. */
    fun fractionOf(n: Notification): Float? {
        val e = n.extras ?: return null
        val max = e.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (max <= 0 || e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return null
        val p = e.getInt(Notification.EXTRA_PROGRESS, 0)
        if (p >= max) return null // finished
        return (p.toFloat() / max).coerceIn(0f, 1f)
    }

    /**
     * Records the progress of notification [key] ([fraction] null = no longer in progress).
     * Returns true when the visible ring would change (it moves in whole percent steps).
     */
    @Synchronized
    fun update(key: String, pkg: String, fraction: Float?, color: Int): Boolean {
        val old = items[key]
        if (fraction == null) return items.remove(key) != null
        items[key] = Item(pkg, fraction, color, SystemClock.uptimeMillis())
        return old == null || percent(old.fraction) != percent(fraction) || old.color != color
    }

    @Synchronized
    fun remove(key: String): Boolean = items.remove(key) != null

    @Synchronized
    fun clear() = items.clear()

    /** The most recently updated transfer that is still moving, or null. */
    @Synchronized
    fun current(now: Long = SystemClock.uptimeMillis()): Item? =
        items.values.filter { now - it.updated < STALE_MS }.maxByOrNull { it.updated }

    private fun percent(f: Float) = (f * 100).toInt()

    /** A transfer that hasn't moved for this long (stalled, paused) stops showing. */
    private const val STALE_MS = 3 * 60_000L
}
