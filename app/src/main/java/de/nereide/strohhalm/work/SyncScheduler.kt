package de.nereide.strohhalm.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.nereide.strohhalm.data.SyncInterval
import java.util.concurrent.TimeUnit

/**
 * Owns the registration of [SyncWorker].
 *
 * [Constraints.NONE] is deliberate: constraints defer work silently, and the
 * worker must run in order to report why it could not proceed.
 */
object SyncScheduler {

    const val UNIQUE_WORK_NAME = "de.nereide.strohhalm.work.SyncWorker"

    /**
     * Applies [interval], replacing any existing registration. `MANUAL` cancels
     * the periodic work entirely; manual syncs go through the runner directly.
     */
    fun apply(context: Context, interval: SyncInterval) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val minutes = interval.minutes
        if (minutes == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(Constraints.NONE)
            .build()
        // UPDATE rather than KEEP: re-applying must actually change the
        // interval. Unlike REPLACE it keeps the existing period's timing when
        // nothing but the request changed, so an app start does not restart
        // the countdown.
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}
