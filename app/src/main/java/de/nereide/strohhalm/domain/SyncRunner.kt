package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
) {

    private val _progress = MutableStateFlow<SyncProgress?>(null)
    val progress: StateFlow<SyncProgress?> = _progress.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    /** Ignored while a sync is already in flight — two clones into one directory would collide. */
    fun launchSyncOne(id: Long) = launch {
        repos.all().firstOrNull { it.id == id }?.let { sync(it) }
    }

    fun launchSyncAll() = launch {
        repos.all().forEach { sync(it) }
    }

    private fun launch(block: suspend () -> Unit) {
        if (_running.value) return
        _running.value = true
        job = scope.launch {
            try {
                block()
            } finally {
                _running.value = false
                _progress.value = null
            }
        }
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
        val outcome = runCatching {
            mirror.sync(
                remoteUrl = repo.remoteUrl,
                destination = File(repo.localPath),
                pinnedFingerprint = repo.hostKeyFingerprint,
                progress = { task, completed, total ->
                    _progress.value = SyncProgress(
                        repoId = repo.id,
                        repoName = repo.displayName,
                        task = task,
                        completed = completed,
                        total = total,
                    )
                }
            )
        }.getOrElse { t -> MirrorOutcome.Failure(SyncErrors.fromException(t)) }

        when (outcome) {
            is MirrorOutcome.Success ->
                repos.markSuccess(repo.id, outcome.sizeBytes, outcome.refCount)
            is MirrorOutcome.Failure ->
                repos.markFailure(repo.id, outcome.error)
        }
    }
}
