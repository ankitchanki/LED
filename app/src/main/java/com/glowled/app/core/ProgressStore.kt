package com.glowled.app.core

import android.app.Notification
import android.os.SystemClock

/**
 * Notifications that show a progress bar (downloads, uploads, installs), for the download ring.
 * Kept apart from [NotificationStore]: they never count as unread and never wake the screen.
 */
object ProgressStore {

    data class Item(
        val pkg: String, val fraction: Float, val color: Int, val updated: Long,
        val initial: String = "↕", val direction: TransferDirection = TransferDirection.UNKNOWN,
    )

    private val items = LinkedHashMap<String, Item>()

    /** Reads either a determinate progress bar or an indeterminate spinner. */
    fun fractionOf(n: Notification): Float? {
        val e = n.extras ?: return null
        return fractionOf(e.getInt(Notification.EXTRA_PROGRESS_MAX, 0), e.getInt(Notification.EXTRA_PROGRESS, 0),
            e.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false))
    }

    /** -1 is an active spinner, null means no active progress, otherwise 0..1. */
    fun fractionOf(max: Int, progress: Int, indeterminate: Boolean): Float? = when {
        indeterminate -> -1f
        max <= 0 || progress >= max -> null
        else -> (progress.toFloat() / max).coerceIn(0f, 1f)
    }

    /**
     * Records the progress of notification [key] ([fraction] null = no longer in progress).
     * Returns true when the visible ring would change (it moves in whole percent steps).
     */
    @Synchronized
    fun update(
        key: String, pkg: String, fraction: Float?, color: Int,
        initial: String = "↕", direction: TransferDirection = TransferDirection.UNKNOWN,
        now: Long = SystemClock.uptimeMillis(),
    ): Boolean {
        val old = current(now)
        if (fraction == null) return items.remove(key) != null
        items.remove(key)
        items[key] = Item(pkg, fraction, color, now, initial, direction)
        return old == null || old.pkg != pkg || percent(old.fraction) != percent(fraction) || old.color != color ||
            old.initial != initial || old.direction != direction
    }

    @Synchronized
    fun remove(key: String): Boolean = items.remove(key) != null

    @Synchronized
    fun retainKeys(activeKeys: Set<String>): Boolean = items.keys.retainAll(activeKeys)

    @Synchronized
    fun clear() = items.clear()

    /** The most recently updated transfer that is still moving, or null. */
    @Synchronized
    fun current(now: Long = SystemClock.uptimeMillis()): Item? =
        items.values.filter { now - it.updated < STALE_MS }.lastOrNull()

    @Synchronized
    fun expiryDelay(now: Long = SystemClock.uptimeMillis()): Long? =
        current(now)?.let { (STALE_MS - (now - it.updated)).coerceAtLeast(1L) }

    private fun percent(f: Float) = (f * 100).toInt()

    /** A transfer that hasn't moved for this long (stalled, paused) stops showing. */
    private const val STALE_MS = 3 * 60_000L
}
