package com.glowled.app.core

/** In-memory list of notifications that should light the LED, most recent first. */
object NotificationStore {

    data class Entry(val key: String, val pkg: String, val color: Int, val time: Long)

    private val entries = LinkedHashMap<String, Entry>()
    private val seen = HashSet<String>()

    @Volatile
    var version = 0L
        private set

    @Synchronized
    fun put(e: Entry): Boolean {
        val old = entries[e.key]
        entries[e.key] = e
        val changed = old == null || old.color != e.color
        if (changed) version++
        return changed
    }

    @Synchronized
    fun contains(key: String): Boolean = entries.containsKey(key)

    @Synchronized
    fun remove(key: String): Boolean {
        seen.remove(key)
        val removed = entries.remove(key) != null
        if (removed) version++
        return removed
    }

    /** Replaces the whole set; returns true if anything visible changed. */
    @Synchronized
    fun replaceAll(list: List<Entry>): Boolean {
        val same = list.size == entries.size && list.all { entries[it.key]?.color == it.color }
        if (same) return false
        entries.clear()
        list.forEach { entries[it.key] = it }
        seen.retainAll(entries.keys)
        version++
        return true
    }

    @Synchronized
    fun clear() {
        entries.clear()
        seen.clear()
        version++
    }

    /** Marks everything currently posted as seen (used by "clear after unlock"). */
    @Synchronized
    fun markAllSeen() {
        seen.addAll(entries.keys)
        version++
    }

    @Synchronized
    fun isEmpty(): Boolean = entries.keys.all { it in seen }

    /** Distinct colors of unseen notifications, newest first (max [limit]). */
    @Synchronized
    fun colors(limit: Int = 6): IntArray {
        val out = LinkedHashSet<Int>()
        entries.values.sortedByDescending { it.time }.forEach {
            if (it.key !in seen) out.add(it.color)
        }
        return out.take(limit).toIntArray()
    }
}
