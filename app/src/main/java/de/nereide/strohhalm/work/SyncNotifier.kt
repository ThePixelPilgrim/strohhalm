package de.nereide.strohhalm.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import de.nereide.strohhalm.MainActivity
import de.nereide.strohhalm.R
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.ui.common.messageRes

/**
 * Notification channels, the ongoing notification backing the sync foreground
 * service, and the failure notifications posted by the scheduled sync.
 *
 * Failures are only posted by the scheduler. A manual sync has the user
 * looking at the screen that reports the error; a scheduled one runs while
 * they are not, which is when a silent failure would go unseen.
 */
class SyncNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                context.getString(R.string.channel_sync_name),
                // LOW: an ongoing progress notification should not make a sound
                // every time it updates.
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = context.getString(R.string.channel_sync_description) }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROBLEMS,
                context.getString(R.string.channel_problems_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = context.getString(R.string.channel_problems_description) }
        )
    }

    fun progress(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_SYNC)
            .setContentTitle(context.getString(R.string.notification_progress_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openApp())
            // A sync runs for minutes with the app off screen, so the
            // notification is where the user actually is when one goes wrong.
            // Making them reopen the app to stop it defeats the point.
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_stop_sync),
                stopSync(),
            )
            .build()

    /**
     * One notification per failure *category*, so a repository that keeps
     * failing the same way replaces its own notification instead of stacking.
     * [repoName] is null when the whole run was refused before any repository
     * was contacted.
     */
    fun notifyFailure(error: SyncError, repoName: String?) {
        val title = repoName
            ?.let { context.getString(R.string.notification_failure_title, it) }
            ?: context.getString(R.string.notification_blocked_title)
        val notification = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setContentTitle(title)
            .setContentText(context.getString(error.code.messageRes()))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                context.getString(error.code.messageRes())
            ))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        // POST_NOTIFICATIONS may have been declined; a sync must not die for it.
        runCatching { manager.notify(NotificationIds.forError(error.code), notification) }
    }

    /** Several repositories failed in one run: one summary rather than a stack. */
    fun notifyFailureCount(count: Int) {
        val notification = NotificationCompat.Builder(context, CHANNEL_PROBLEMS)
            .setContentTitle(context.getString(R.string.notification_failures_title, count))
            .setContentText(context.getString(R.string.notification_failures_body))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NotificationIds.FAILURE_SUMMARY, notification) }
    }

    /** A clean run retires every problem notification still showing. */
    fun clearFailures() {
        (NotificationIds.allErrorIds() + NotificationIds.FAILURE_SUMMARY).forEach { id ->
            runCatching { manager.cancel(id) }
        }
    }

    private fun stopSync(): PendingIntent =
        PendingIntent.getService(
            context,
            1,
            Intent(context, SyncForegroundService::class.java)
                .setAction(SyncForegroundService.ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )

    private companion object {
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_PROBLEMS = "problems"
    }
}
