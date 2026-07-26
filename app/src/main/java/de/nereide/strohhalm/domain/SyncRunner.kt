package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.Repo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Live progress for the repository currently being mirrored. */
data class SyncProgress(
    val repoId: Long,
    val repoName: String,
    val task: String,
    val completed: Int,
    val total: Int,
    /** When this repository's sync began, for an elapsed-time display. */
    val startedAt: Long = 0L,
) {
    /** Null when the engine does not know the total for this phase. */
    val fraction: Float?
        get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Runs mirror syncs and writes outcomes back to the repository rows.
 *
 * Work runs on a **process-wide scope**, never a `viewModelScope`. Mirroring a
 * large repository can take many minutes; if it were tied to a screen, simply
 * navigating back would cancel the clone mid-transfer and leave the row stuck at
 * SYNCING with no error recorded — because the code that would have recorded one
 * was cancelled too.
 *
 * A failure in one repository never aborts the others: partial backups beat none.
 */
class SyncRunner(
    private val repos: RepoRepository,
    private val mirror: GitMirror,
    private val scope: CoroutineScope,
    private val foreground: ForegroundHold = NoForegroundHold,
    private val access: MirrorAccess = MirrorAccess(),
) {

    private companion object {
        /**
         * Shown until JGit's monitor first fires. Deliberately names what is
         * happening rather than saying "preparing", which reads as stalled.
         */
        const val CONTACTING = "Contacting the server"
    }

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    /**
     * Ignored while a sync is already in flight — two clones into one directory
     * would collide — or while an archive holds the mirror.
     *
     * @return whether a sync actually started. A caller that changes its own
     *   state on the strength of the launch has to know: `false` means nothing
     *   is coming, and waiting for it would wait for ever.
     */
    fun launchSyncOne(id: Long): Boolean = launch {
        repos.all().firstOrNull { it.id == id }?.let { sync(it) }
    }

    /** @return whether a sync actually started; see [launchSyncOne]. */
    fun launchSyncAll(): Boolean = launch {
        repos.all().forEach { sync(it) }
    }

    private fun launch(block: suspend () -> Unit): Boolean {
        if (_running.value) return false
        // `_running` only knows about other syncs. An archive is reading the
        // same directories and is invisible here, so the mirror lock is the
        // only thing that can hold a fetch back — and it must, because a fetch
        // mid-archive writes a temporary pack into the mirror and rewrites its
        // refs, producing an archive that verifies and does not restore.
        if (!access.tryAcquire(MirrorAccess.Owner.SYNC)) return false
        _running.value = true
        // Acquired before the work starts and released only when it ends:
        // Android freezes cached processes, and a mirror of a large repository
        // runs far longer than the user will keep the app on screen.
        foreground.acquire()
        job = scope.launch {
            try {
                block()
            } finally {
                _progress.value = null
                foreground.release()
                access.release(MirrorAccess.Owner.SYNC)
                // Last, and the order is the point: `running` going false is the
                // signal every caller waits on, so the mirror must already be
                // free when they see it. Clearing the flag first leaves a window
                // in which a share or a queued sync asks for a lock this sync
                // has not let go of yet, and is silently refused.
                _running.value = false
            }
        }
        return true
    }

    /**
     * Stops the sync in flight.
     *
     * Cancelling the job is only half of it: the mirror spends its time inside
     * a blocking JGit call, which coroutine cancellation alone cannot unwind —
     * the flag would flip, the UI would clear, and the transfer would carry on
     * unseen. [JGitMirror] runs that call interruptibly so this actually stops
     * the work rather than just stopping the bookkeeping.
     */
    fun cancel() {
        job?.cancel()
    }

    /**
     * Clears rows left at SYNCING by a process that died mid-sync — a crash, or
     * the system reclaiming the app. Without this they stay SYNCING forever and
     * the UI implies work is happening when nothing is.
     */
    suspend fun resetStale() {
        repos.resetStaleSyncing(SyncError(SyncErrorCode.INTERRUPTED))
    }

    private suspend fun sync(repo: Repo) {
        repos.markSyncing(repo.id)
        val startedAt = System.currentTimeMillis()

        // Published immediately so the UI shows the repository and a running
        // clock straight away. JGit reports nothing until data flows, and for a
        // large repository the server spends minutes enumerating and compressing
        // objects first — during which silence must not look like a freeze.
        _progress.value = SyncProgress(
            repoId = repo.id,
            repoName = repo.displayName,
            task = CONTACTING,
            completed = 0,
            total = 0,
            startedAt = startedAt,
        )

        val outcome = try {
            mirror.sync(
                remoteUrl = repo.remoteUrl,
                destination = File(repo.localPath),
                pinnedFingerprint = repo.hostKeyFingerprint,
                progress = { task, completed, total ->
                    _progress.value = SyncProgress(
                        repoId = repo.id,
                        repoName = repo.displayName,
                        task = task.ifBlank { CONTACTING },
                        completed = completed,
                        total = total,
                        startedAt = startedAt,
                    )
                }
            )
        } catch (cancelled: CancellationException) {
            // The row must not be left claiming to sync, and the write has to
            // outlive the cancellation that triggered it — hence NonCancellable.
            // Rethrown so the scope still unwinds as cancelled.
            withContext(NonCancellable) {
                repos.markFailure(repo.id, SyncError(SyncErrorCode.CANCELLED))
            }
            throw cancelled
        } catch (t: Throwable) {
            MirrorOutcome.Failure(SyncErrors.fromException(t))
        }

        when (outcome) {
            is MirrorOutcome.Success ->
                repos.markSuccess(repo.id, outcome.sizeBytes, outcome.refCount)
            is MirrorOutcome.Failure ->
                repos.markFailure(repo.id, outcome.error)
        }
    }
}
