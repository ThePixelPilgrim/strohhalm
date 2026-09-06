package de.nereide.strohhalm.work

import de.nereide.strohhalm.data.SyncStatus
import de.nereide.strohhalm.domain.RepoRepository
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncRunner
import kotlinx.coroutines.flow.first

/**
 * One scheduled sync cycle, as plain Kotlin so its decisions are testable
 * without WorkManager or a device. [SyncWorker] is the thin Android shell
 * around it.
 *
 * The cycle is the manual "sync all" with three things added: it refuses when
 * [preconditions] says the device cannot sync, it stands aside when a sync is
 * already in flight, and it waits for the outcome so the worker can tell a
 * user who is not looking at the screen what went wrong.
 *
 * The sync itself still runs on the [SyncRunner]'s process-wide scope, not the
 * worker's. WorkManager may stop a worker after ten minutes; a mirror that is
 * still transferring at that point carries on, and only this cycle's report is
 * lost.
 */
class ScheduledSync(
    private val runner: SyncRunner,
    private val repos: RepoRepository,
    private val preconditions: suspend () -> SyncError?,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    sealed interface Outcome {
        /** The device cannot sync right now; nothing was contacted. */
        data class Blocked(val error: SyncError) : Outcome

        /** A manual sync is in flight. It will do the work; this cycle skips. */
        data object AlreadyRunning : Outcome

        /** No repository has a confirmed host key, so there was nothing to fetch. */
        data object NothingToSync : Outcome

        /** Every verified repository was attempted; these are the ones that failed. */
        data class Completed(val failures: List<Failure>) : Outcome
    }

    data class Failure(val repoName: String, val error: SyncError)

    suspend fun run(): Outcome {
        preconditions()?.let { return Outcome.Blocked(it) }
        if (runner.running.value) return Outcome.AlreadyRunning

        val startedAt = clock()
        if (!runner.launchSyncAll()) return Outcome.NothingToSync
        runner.running.first { !it }

        // Only failures stamped during this run: a repository skipped this
        // cycle keeps its old failure, and re-reporting it every cycle would
        // be noise. A cancellation is the user's doing, not a fault.
        val failures = repos.all()
            .filter { it.lastStatus == SyncStatus.FAILED }
            .filter { (it.lastAttemptAt ?: 0L) >= startedAt }
            .mapNotNull { repo ->
                val code = repo.lastErrorCode
                    ?.let { name -> SyncErrorCode.entries.firstOrNull { it.name == name } }
                    ?: SyncErrorCode.UNKNOWN
                if (code == SyncErrorCode.CANCELLED) return@mapNotNull null
                Failure(repo.displayName, SyncError(code, repo.lastErrorDetail))
            }
        return Outcome.Completed(failures)
    }
}
