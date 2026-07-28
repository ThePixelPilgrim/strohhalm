package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.Repo
import kotlinx.coroutines.flow.Flow

/** Repository CRUD plus the bookkeeping each sync attempt writes back. */
interface RepoRepository {

    fun observeAll(): Flow<List<Repo>>

    fun observe(id: Long): Flow<Repo?>

    /** Oldest-synced first. */
    suspend fun all(): List<Repo>

    /**
     * Creates a repository with a directory name derived from [remoteUrl] and
     * made unique against those already taken. Returns the new row id.
     *
     * A null [hostKeyFingerprint] is a repository added before its server was
     * verified; syncs skip it until a key is pinned.
     */
    suspend fun add(displayName: String, remoteUrl: String, hostKeyFingerprint: String?): Long

    suspend fun markSyncing(id: Long)

    suspend fun markSuccess(id: Long, sizeBytes: Long, refCount: Int)

    suspend fun markFailure(id: Long, error: SyncError)

    /** Re-pins the host key after the user has confirmed a new one. */
    suspend fun updateHostKey(id: Long, fingerprint: String)

    /** Rewrites any row still marked SYNCING, after a process died mid-sync. */
    suspend fun resetStaleSyncing(error: SyncError)

    suspend fun delete(id: Long)
}
