package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.Repo
import java.io.File

/**
 * Runs mirror syncs and writes the outcome back to the repository rows.
 *
 * Extracted from the ViewModels so the list screen, the detail screen and — once
 * it exists — the background worker all drive syncing the same way. A failure in
 * one repository never aborts the others: partial backups beat none.
 */
class SyncRunner(
    private val repos: RepoRepository,
    private val mirror: GitMirror,
) {

    suspend fun syncOne(id: Long) {
        val repo = repos.all().firstOrNull { it.id == id } ?: return
        sync(repo)
    }

    /** @return how many repositories failed. */
    suspend fun syncAll(): Int = repos.all().count { !sync(it) }

    private suspend fun sync(repo: Repo): Boolean {
        repos.markSyncing(repo.id)
        val outcome = runCatching {
            mirror.sync(repo.remoteUrl, File(repo.localPath), repo.hostKeyFingerprint)
        }.getOrElse { t -> MirrorOutcome.Failure(SyncErrors.fromException(t)) }

        return when (outcome) {
            is MirrorOutcome.Success -> {
                repos.markSuccess(repo.id, outcome.sizeBytes, outcome.refCount)
                true
            }
            is MirrorOutcome.Failure -> {
                repos.markFailure(repo.id, outcome.error)
                false
            }
        }
    }
}
