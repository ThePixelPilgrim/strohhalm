package de.nereide.strohhalm

import de.nereide.strohhalm.domain.archive.ArchiveStore
import de.nereide.strohhalm.domain.archive.RefFingerprint
import de.nereide.strohhalm.domain.git.MirrorRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the archive cache honest, invisibly.
 *
 * Two triggers, and no others: a sync that moved the refs, and Android asking
 * for memory back. Everything here is deliberately unobservable from the UI —
 * application-scoped so navigating away cannot cancel it, off the sync's
 * critical path so a slow delete cannot delay the sync's completion write, and
 * emitting no state at all. A failure to delete is swallowed: a leftover file
 * is worth no user-facing noise, and the next prune takes it.
 *
 * @param mirrors the current repositories as slug-to-directory pairs. A lambda
 *   rather than a repository, so this class stays testable without Room.
 */
class ArchiveMaintenance(
    private val store: ArchiveStore,
    private val mirrors: suspend () -> List<Pair<String, File>>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Prunes each time a sync finishes.
     *
     * Launched, never awaited. The sync's own final database write runs under
     * `NonCancellable` because it must outlive cancellation; putting file
     * deletion in that path would make completion wait on unrelated work.
     */
    fun observe(running: StateFlow<Boolean>) {
        scope.launch {
            var wasRunning = running.value
            running.collect { isRunning ->
                if (wasRunning && !isRunning) pruneStale()
                wasRunning = isRunning
            }
        }
    }

    /** Deletes archives whose recorded ref fingerprint no longer matches. */
    suspend fun pruneStale() = withContext(io) {
        mirrors().forEach { (slug, gitDir) ->
            runCatching {
                val repository = MirrorRepository(gitDir)
                if (!repository.exists()) return@runCatching
                store.prune(slug, RefFingerprint.of(repository.localRefs()))
            }
        }
    }

    /** Gives back every archive, current ones included. */
    suspend fun dropEverything() = withContext(io) {
        mirrors().forEach { (slug, _) -> runCatching { store.prune(slug, null) } }
    }
}
