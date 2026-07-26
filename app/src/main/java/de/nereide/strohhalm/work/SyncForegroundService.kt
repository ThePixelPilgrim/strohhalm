package de.nereide.strohhalm.work

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.nereide.strohhalm.R
import de.nereide.strohhalm.StrohhalmApp
import de.nereide.strohhalm.domain.ForegroundHold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Holds the process in the foreground for the duration of a sync, and shows what
 * it is doing.
 *
 * Its only job is process priority: the sync itself runs on the application
 * scope. Without it Android freezes the process when the app is backgrounded and
 * a long mirror dies mid-connection.
 */
class SyncForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private var notifier: SyncNotifier? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notifier = SyncNotifier(this).also { this.notifier = it }
        notifier.ensureChannels()

        startForegroundCompat(notifier.progress(getString(R.string.notification_progress_starting)))

        val container = (applicationContext as StrohhalmApp).container
        scope.launch {
            container.syncRunner.progress.collectLatest { progress ->
                val text = progress?.let {
                    if (it.total > 0) {
                        getString(R.string.progress_of, it.task, it.completed, it.total)
                    } else {
                        getString(R.string.progress_indeterminate, it.task)
                    }
                } ?: getString(R.string.notification_progress_starting)
                NotificationManagerCompat.from(this@SyncForegroundService)
                    .runCatching { notify(NotificationIds.PROGRESS, notifier.progress(text)) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
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
