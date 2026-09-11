package de.nereide.strohhalm.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.nereide.strohhalm.R
import de.nereide.strohhalm.StrohhalmApp
import de.nereide.strohhalm.domain.ForegroundHold
import de.nereide.strohhalm.domain.SyncProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Holds the process in the foreground for the duration of a sync, and shows what
 * it is doing.
 *
 * Its only job is process priority: the sync itself runs on the application
 * scope. Without it Android freezes the process when the app is backgrounded and
 * a long mirror dies mid-connection.
 *
 * The service ends its own notification. [ForegroundHold.release] still calls
 * `stopService`, but the system removes a foreground notification
 * asynchronously, and anything this service posts in the meantime survives as
 * an orphan. So [ForegroundSession] guarantees nothing is posted once the sync
 * has ended, and [finish] removes the notification here, synchronously with the
 * last thing this service does with it.
 */
class SyncForegroundService : Service(), ForegroundSession.Display {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifier: SyncNotifier
    private val session = ForegroundSession(this)
    private val runner get() = (applicationContext as StrohhalmApp).container.syncRunner

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = SyncNotifier(this)
        notifier.ensureChannels()

        // Required within seconds of startForegroundService, before anything
        // else; a sync that ended in the meantime finishes on the first
        // collected value right after.
        startForegroundCompat(notifier.progress(getString(R.string.notification_progress_starting)))

        // Main.immediate keeps every notification decision on one thread, in
        // the order the runner made them, so finish() cannot interleave with a
        // show() still in flight.
        scope.launch {
            runner.progress.collect { progress -> session.onProgress(progress) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            runner.cancel()
            session.onStopRequested(running = runner.running.value)
        }
        return START_NOT_STICKY
    }

    override fun show(progress: SyncProgress) {
        val text = if (progress.total > 0) {
            getString(R.string.progress_of, progress.task, progress.completed, progress.total)
        } else {
            getString(R.string.progress_indeterminate, progress.task)
        }
        NotificationManagerCompat.from(this)
            .runCatching { notify(NotificationIds.PROGRESS, notifier.progress(text)) }
    }

    override fun finish() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        // Belt and braces for the paths that do not go through finish(): the
        // system killing the service, or stopService arriving first.
        NotificationManagerCompat.from(this).runCatching { cancel(NotificationIds.PROGRESS) }
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationIds.PROGRESS,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationIds.PROGRESS, notification)
        }
    }

    companion object {
        /** Sent by the notification's Stop action. */
        const val ACTION_CANCEL = "de.nereide.strohhalm.action.CANCEL_SYNC"

        /**
         * [ForegroundHold] backed by this service.
         *
         * Starting is best-effort: Android forbids launching a foreground service
         * from the background in some states, and a sync that runs without the
         * hold is better than one that refuses to start at all.
         */
        fun hold(context: Context): ForegroundHold = object : ForegroundHold {
            private val appContext = context.applicationContext

            override fun acquire() {
                runCatching {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, SyncForegroundService::class.java)
                    )
                }
            }

            override fun release() {
                runCatching {
                    appContext.stopService(Intent(appContext, SyncForegroundService::class.java))
                }
            }
        }
    }
}
