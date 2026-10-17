package com.glowled.app.service

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.glowled.app.core.LedController
import com.glowled.app.core.NotificationStore

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
        LedController.onNotificationsChanged(false)
        // Ask the system to bind us again (OEM task killers often drop the binding).
        requestRebind(ComponentName(this, NotifyListenerService::class.java))
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val entry = toEntry(sbn, rankingMap)
        val existed = NotificationStore.contains(sbn.key)
        val changed = if (entry != null) NotificationStore.put(entry) else NotificationStore.remove(sbn.key)
        // A brand-new notification, or an update that alerts again (e.g. a new chat message), wakes the LED.
        val isNew = entry != null && (!existed || sbn.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE == 0)
        if (changed || isNew) LedController.onNotificationsChanged(newPost = isNew)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (NotificationStore.remove(sbn.key)) LedController.onNotificationsChanged(false)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        // DND / importance changes can alter what should be shown.
        rebuild()
    }

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        rebuild()
    }

    /** Re-evaluates every active notification (after settings change or reconnect). */
    fun rebuild() {
        val active = try {
            activeNotifications ?: emptyArray()
        } catch (e: Exception) {
            Log.w("GlowLED", "activeNotifications failed", e)
            return
        }
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
            if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return null
            if (map != null && map.getRanking(sbn.key, ranking)) {
                if (prefs.ignoreLowImportance && ranking.importance in 0..NotificationManager.IMPORTANCE_LOW) return null
                if (prefs.respectDnd && !ranking.matchesInterruptionFilter()) return null
                if (ranking.isSuspended) return null
            }
        }
        return NotificationStore.Entry(sbn.key, pkg, LedController.colors.colorFor(pkg, n), sbn.postTime)
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
