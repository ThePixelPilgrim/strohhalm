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

/**
 * Notification channels and the ongoing notification backing the sync
 * foreground service.
 *
 * Failure notifications are not posted yet — syncing is manual, so the user is
 * already looking at the screen that reports the error. They arrive with the
 * background scheduler, which is when a silent failure would actually go unseen.
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
