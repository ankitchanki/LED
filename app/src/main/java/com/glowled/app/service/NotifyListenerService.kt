package com.glowled.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.glowled.app.core.LedController
import com.glowled.app.core.NotificationContent
import com.glowled.app.core.NotificationStore
import com.glowled.app.core.TransferInfo
import com.glowled.app.core.TransferDirection
import com.glowled.app.core.ProgressStore

/** Tracks which notifications should light the LED. Bound by the system, so no foreground service is needed. */
class NotifyListenerService : NotificationListenerService() {

    private val ranking = Ranking()

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        LedController.init(this)
        com.glowled.app.core.DebugLog.add("Notification access connected")
        rebuild()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        com.glowled.app.core.DebugLog.add("Notification access disconnected - rebinding")
        NotificationStore.clear()
        ProgressStore.clear()
        LedController.onNotificationsChanged(false)
        // Ask the system to bind us again (OEM task killers often drop the binding).
        requestRebind(ComponentName(this, NotifyListenerService::class.java))
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        trackProgress(sbn)
        val entry = toEntry(sbn, rankingMap)
        val existed = NotificationStore.contains(sbn.key)
        // A brand-new notification, or an update that alerts again (e.g. a new chat message), wakes the LED.
        val isNew = entry != null && (!existed || sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)
        val changed = if (entry != null) NotificationStore.put(entry, alert = isNew) else NotificationStore.remove(sbn.key)
        if (changed || isNew) LedController.onNotificationsChanged(newPost = isNew)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (ProgressStore.remove(sbn.key)) LedController.onProgressChanged()
        if (NotificationStore.remove(sbn.key)) LedController.onNotificationsChanged(false)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        // DND / importance changes can alter what should be shown.
        rebuild()
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        rebuild()
    }

    /** Download / upload progress bars feed the download ring around the camera. */
    private val transferInitials = mutableMapOf<String, String>()

    private fun trackProgress(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val pkg = sbn.packageName
        val fraction = if (pkg != packageName && LedController.prefs.appEnabled(pkg)) ProgressStore.fractionOf(n) else null
        val color = if (fraction != null) LedController.colors.colorFor(pkg, n) else 0
        val initial = if (fraction != null) transferInitials.getOrPut(pkg) {
            val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                catch (_: Exception) { pkg.substringAfterLast('.') }
            TransferInfo.initial(label)
        } else "↕"
        val extras = n.extras
        val direction = if (fraction == null || n.visibility == Notification.VISIBILITY_SECRET) TransferDirection.UNKNOWN else TransferDirection.fromText(
            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                .mapNotNull { extras?.getCharSequence(it)?.toString() }.joinToString(" "),
        )
        if (ProgressStore.update(sbn.key, pkg, fraction, color, initial, direction)) LedController.onProgressChanged()
    }

    /** Re-evaluates every active notification (after settings change or reconnect). */
    fun rebuild() {
        val active = try {
            activeNotifications ?: emptyArray()
        } catch (e: Exception) {
            Log.w("GlowLED", "activeNotifications failed", e)
            return
        }
        if (ProgressStore.retainKeys(active.map { it.key }.toSet())) LedController.onProgressChanged()
        active.forEach { trackProgress(it) }
        val map = try { currentRanking } catch (_: Exception) { null }
        val groups = active.groupingBy { it.groupKey }.eachCount()
        if (NotificationStore.replaceAll(active.mapNotNull { toEntry(it, map, groups) })) {
            LedController.onNotificationsChanged(false)
        }
    }

    private fun toEntry(
        sbn: StatusBarNotification,
        map: RankingMap?,
        groupCounts: Map<String, Int>? = null,
    ): NotificationStore.Entry? {
        val prefs = LedController.prefs
        val n = sbn.notification ?: return null
        val pkg = sbn.packageName
        val isTest = pkg == packageName && sbn.tag == TEST_TAG
        if (!isTest) {
            if (pkg == packageName) return null
            if (!prefs.appEnabled(pkg)) return null
            if (n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
                val children = groupCounts?.let { (it[sbn.groupKey] ?: 1) > 1 } ?: hasChildren(sbn)
                if (children) return null
            }
            if (ProgressStore.fractionOf(n) != null) return null
            if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return null
            if (map != null && map.getRanking(sbn.key, ranking)) {
                if (prefs.ignoreLowImportance && ranking.importance in 0..NotificationManager.IMPORTANCE_LOW) return null
                if (prefs.respectDnd && !ranking.matchesInterruptionFilter()) return null
                if (ranking.isSuspended) return null
            }
        }
        // Content is kept in memory only, for the tap-to-peek card on the always-on screen.
        val hidden = n.visibility == Notification.VISIBILITY_SECRET || !prefs.showContent
        val content = if (hidden) null else NotificationContent.read(n)
        return NotificationStore.Entry(
            key = sbn.key,
            pkg = pkg,
            color = LedController.colors.colorFor(pkg, n),
            time = sbn.postTime,
            title = content?.title,
            lines = content?.lines.orEmpty(),
            kind = content?.kind ?: NotificationStore.Kind.OTHER,
            hidden = hidden,
            intent = n.contentIntent,
            smallIcon = n.smallIcon,
            replyAction = replyActionOf(n),
            readAction = readActionOf(n),
            clearable = sbn.isClearable,
        )
    }

    /** The app's reply action: one that takes free-form text. */
    private fun replyActionOf(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    /** The app's mark-as-read action (declared semantically, or recognisable by its title). */
    private fun readActionOf(n: Notification): Notification.Action? {
        val actions = n.actions ?: return null
        return actions.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ }
            ?: actions.firstOrNull { a ->
                val t = a.title?.toString()?.lowercase().orEmpty()
                (READ_WORDS.containsMatchIn(t)) && "unread" !in t && a.remoteInputs.isNullOrEmpty()
            }
    }

    private fun hasChildren(summary: StatusBarNotification): Boolean {
        val group = summary.groupKey
        return try {
            activeNotifications?.any { it.key != summary.key && it.groupKey == group } == true
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val TEST_TAG = "glowled_test"
        private val READ_WORDS = Regex("\\bread\\b|\\blida\\b|\\blido\\b")

        @Volatile
        var instance: NotifyListenerService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val nm = context.getSystemService(NotificationManager::class.java)
            return nm.isNotificationListenerAccessGranted(ComponentName(context, NotifyListenerService::class.java))
        }

        fun rebind(context: Context) {
            if (isEnabled(context)) {
                requestRebind(ComponentName(context, NotifyListenerService::class.java))
            }
        }
    }
}
