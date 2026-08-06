package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.data.RepoDao
import de.nereide.strohhalm.data.RepoSlug
import de.nereide.strohhalm.data.SyncStatus
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * [storageRoot] is a function rather than a value because the user can change
 * the mirror directory at any time; resolving it per call avoids a stale root.
 * [clock] is injected so tests can advance time deterministically.
 */
class DefaultRepoRepository(
    private val dao: RepoDao,
    private val storageRoot: suspend () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
) : RepoRepository {

    override fun observeAll(): Flow<List<Repo>> = dao.observeAll()

    override fun observe(id: Long): Flow<Repo?> = dao.observe(id)

    override suspend fun all(): List<Repo> = dao.all()

    override suspend fun add(
        displayName: String,
        remoteUrl: String,
        hostKeyFingerprint: String?,
    ): Long {
        val root = storageRoot()
        val taken = dao.localPaths()
            .map { File(it).name.removeSuffix(GIT_SUFFIX) }
            .toSet()
        val slug = RepoSlug.unique(RepoSlug.fromRemoteUrl(remoteUrl), taken)
        return dao.insert(
            Repo(
                displayName = displayName.ifBlank { slug },
                remoteUrl = remoteUrl,
                localPath = File(root, slug + GIT_SUFFIX).path,
                hostKeyFingerprint = hostKeyFingerprint,
                createdAt = clock(),
            )
        )
    }

    override suspend fun markSyncing(id: Long) {
        val repo = dao.byId(id) ?: return
        dao.update(repo.copy(lastStatus = SyncStatus.SYNCING))
    }

    override suspend fun markSuccess(id: Long, sizeBytes: Long, refCount: Int) {
        val repo = dao.byId(id) ?: return
        val now = clock()
        dao.update(
            repo.copy(
                lastStatus = SyncStatus.OK,
                lastSyncAt = now,
                lastAttemptAt = now,
                sizeBytes = sizeBytes,
                refCount = refCount,
                lastErrorCode = null,
                lastErrorDetail = null,
                lastErrorDiagnostic = null,
            )
        )
    }

    override suspend fun markFailure(id: Long, error: SyncError) {
        val repo = dao.byId(id) ?: return
        dao.update(
            repo.copy(
                lastStatus = SyncStatus.FAILED,
                lastAttemptAt = clock(),
                lastErrorCode = error.code.name,
                lastErrorDetail = error.detail,
                lastErrorDiagnostic = error.diagnostic,
            )
        )
    }

    override suspend fun updateHostKey(id: Long, fingerprint: String) {
        val repo = dao.byId(id) ?: return
        dao.update(repo.copy(hostKeyFingerprint = fingerprint))
    }

    override suspend fun updateRemoteUrl(id: Long, newUrl: String) {
        val repo = dao.byId(id) ?: return
        val trimmed = newUrl.trim()
        if (trimmed.isEmpty()) return
        dao.update(repo.copy(remoteUrl = trimmed, hostKeyFingerprint = null))
    }

    override suspend fun resetStaleSyncing(error: SyncError) {
        dao.all()
            .filter { it.lastStatus == SyncStatus.SYNCING }
            .forEach { markFailure(it.id, error) }
    }

    override suspend fun delete(id: Long) {
        val repo = dao.byId(id) ?: return
        dao.delete(repo)
    }

    private companion object {
        const val GIT_SUFFIX = ".git"
    }
}
