package de.nereide.strohhalm.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.nereide.strohhalm.StrohhalmApp
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * The WorkManager shell around [ScheduledSync]: reads the device's state into
 * plain values, runs one cycle, and turns the outcome into notifications and
 * a WorkManager result.
 *
 * Registered with [androidx.work.Constraints.NONE] on purpose — see
 * [SyncPreconditions]. The worker always starts, checks its own conditions,
 * and reports what it found.
 *
 * It does not call `setForeground` itself: [de.nereide.strohhalm.domain.SyncRunner]
 * already raises [SyncForegroundService] for every sync, manual or scheduled,
 * and two foreground services sharing one notification id would fight over it.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val container get() = (applicationContext as StrohhalmApp).container
    private val notifier by lazy { SyncNotifier(applicationContext) }

    override suspend fun doWork(): Result {
        notifier.ensureChannels()
        val settings = container.settingsRepository
        val notifyEnabled = runCatching { settings.notifyOnFailure.first() }.getOrDefault(true)

        val cycle = ScheduledSync(
            runner = container.syncRunner,
            repos = container.repoRepository,
            preconditions = { checkDevice() },
        )

        return when (val outcome = cycle.run()) {
            is ScheduledSync.Outcome.Blocked -> {
                if (notifyEnabled) notifier.notifyFailure(outcome.error, repoName = null)
                // A lost permission will not come back on its own; the network
                // and free space might, so those get WorkManager's backoff retry
                // before the next scheduled slot.
                if (outcome.error.code == SyncErrorCode.PERMISSION_LOST) {
                    Result.failure()
                } else {
                    Result.retry()
                }
            }

            ScheduledSync.Outcome.AlreadyRunning,
            ScheduledSync.Outcome.NothingToSync -> Result.success()

            is ScheduledSync.Outcome.Completed -> {
                val failures = outcome.failures
                when {
                    failures.isEmpty() -> notifier.clearFailures()
                    !notifyEnabled -> Unit
                    failures.size == 1 ->
                        notifier.notifyFailure(failures.single().error, failures.single().repoName)
                    else -> notifier.notifyFailureCount(failures.size)
                }
                // Per-repository failures are recorded on the rows and shown
                // above; the worker did its job. `retry()` here would back off
                // exponentially and drift away from the chosen interval.
                Result.success()
            }
        }
    }

    private suspend fun checkDevice(): SyncError? {
        val root = runCatching { container.settingsRepository.requireStorageRoot() }.getOrNull()
        return SyncPreconditions.check(
            freeBytes = freeBytesAt(root),
            storageRootExists = root?.isDirectory == true,
            hasStoragePermission = hasStoragePermission(),
            hasNetwork = hasNetwork(),
        )
    }

    private fun freeBytesAt(root: File?): Long {
        val target = root?.takeIf { it.isDirectory } ?: Environment.getExternalStorageDirectory()
        return runCatching { StatFs(target.path).availableBytes }.getOrDefault(0L)
    }

    private fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    private fun hasNetwork(): Boolean {
        val cm = applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
