package com.glowled.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.glowled.app.BuildConfig
import com.glowled.app.R
import com.glowled.app.core.LedController
import com.glowled.app.core.Updater
import com.glowled.app.ui.UpdateActivity
import java.util.concurrent.TimeUnit

/**
 * Looks for a new release every few hours (only with a network connection, batched by the system)
 * and posts a notification with the changelog when one is out.
 */
class UpdateCheckJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val r = Updater.check(this)
            if (r != null) notifyIfNew(this, r)
            jobFinished(params, r == null)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 7101
        private const val NOTIF_ID = 7102
        const val CHANNEL = "updates"
        private val PERIOD_MS = TimeUnit.HOURS.toMillis(6)

        /** Schedules (or cancels) the periodic check to match the setting. */
        fun sync(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            val on = LedController.prefs.sp.getBoolean(Updater.K_AUTO, true)
            if (!on) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(PERIOD_MS, TimeUnit.HOURS.toMillis(1))
                    .setPersisted(true)
                    .build(),
            )
        }

        /** One notification per new version. */
        fun notifyIfNew(context: Context, r: Updater.Release) {
            val sp = LedController.prefs.sp
            if (!Updater.isNewer(r.version, BuildConfig.VERSION_NAME)) return
            if (sp.getString(Updater.K_NOTIFIED, null) == r.version) return
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33
            ) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notes = Updater.plainNotes(r.notes)
            nm.notify(
                NOTIF_ID,
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle(context.getString(R.string.upd_notif_title, r.version))
                    .setContentText(notes.lineSequence().firstOrNull { it.isNotBlank() } ?: "")
                    .setStyle(NotificationCompat.BigTextStyle().bigText(notes))
                    .setContentIntent(open)
                    .addAction(0, context.getString(R.string.upd_notif_action), open)
                    .setAutoCancel(true)
                    .build(),
            )
            sp.edit().putString(Updater.K_NOTIFIED, r.version).apply()
        }

        fun cancelNotification(context: Context) =
            context.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
    }
}
