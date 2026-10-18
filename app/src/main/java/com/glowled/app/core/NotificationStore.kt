package com.glowled.app.core

/** In-memory list of notifications that should light the LED, most recent first. */
object NotificationStore {

    /** What a notification carries, so the always-on screen can label media messages. */
    enum class Kind { TEXT, IMAGE, VIDEO, AUDIO, FILE, OTHER }

    /**
     * One notification. Content ([title], [lines]) lives in memory only, for the tap-to-peek card;
     * it is never written to disk or sent anywhere. [hidden] = the app marked it secret.
     */
    data class Entry(
        val key: String,
        val pkg: String,
        val color: Int,
        val time: Long,
        val title: String? = null,
        val lines: List<String> = emptyList(),
        val kind: Kind = Kind.TEXT,
        val hidden: Boolean = false,
        val intent: android.app.PendingIntent? = null,
    ) {
        /** Same LED/peek appearance (ignores the PendingIntent identity). */
        fun sameLook(o: Entry?) = o != null && o.color == color && o.title == title && o.lines == lines && o.kind == kind
    }

    private val entries = LinkedHashMap<String, Entry>()
    private val seen = HashSet<String>()

    @Volatile
    var version = 0L
        private set

    @Synchronized
    fun put(e: Entry): Boolean {
        val old = entries[e.key]
        entries[e.key] = e
        val changed = !e.sameLook(old)
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
        val same = list.size == entries.size && list.all { it.sameLook(entries[it.key]) }
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

    data class AppInfo(val pkg: String, val color: Int, val count: Int)

    /** Apps with unseen notifications, newest first, with how many each has. */
    @Synchronized
    fun apps(): List<AppInfo> {
        val out = LinkedHashMap<String, AppInfo>()
        entries.values.sortedByDescending { it.time }.forEach {
            if (it.key in seen) return@forEach
            val prev = out[it.pkg]
            out[it.pkg] = if (prev == null) AppInfo(it.pkg, it.color, 1) else prev.copy(count = prev.count + 1)
        }
        return out.values.toList()
    }

    /** Post time of the newest unseen notification (0 if none). */
    @Synchronized
    fun newestTime(): Long = entries.values.filter { it.key !in seen }.maxOfOrNull { it.time } ?: 0L

    /** Unseen notifications of one app, newest first (for the peek card). */
    @Synchronized
    fun entriesFor(pkg: String): List<Entry> =
        entries.values.filter { it.pkg == pkg && it.key !in seen }.sortedByDescending { it.time }

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
