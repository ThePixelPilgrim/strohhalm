package de.nereide.strohhalm.domain

import java.io.File

/** The result of one repository sync. */
sealed interface MirrorOutcome {
    data class Success(val sizeBytes: Long, val refCount: Int) : MirrorOutcome
    data class Failure(val error: SyncError) : MirrorOutcome
}

/**
 * Maintains bare mirror clones. Implementations are the only place in the app
 * that import JGit, so replacing the engine touches exactly one file.
 *
 * Every method is read-only with respect to the remote: nothing here pushes,
 * commits or otherwise writes upstream.
 */
interface GitMirror {

    /**
     * Mirrors [remoteUrl] into [destination] — a `clone --mirror` when the
     * directory does not yet exist, otherwise a pruning fetch of every ref.
     *
     * [pinnedFingerprint] is the previously accepted host key. Null means nothing
     * is pinned, and the connection is refused rather than trusted blindly.
     * Failures are returned as [MirrorOutcome.Failure], not thrown.
     */
    suspend fun sync(
        remoteUrl: String,
        destination: File,
        pinnedFingerprint: String?,
    ): MirrorOutcome

    /**
     * Connects far enough to read the server's host key and returns its OpenSSH
     * `SHA256:` fingerprint, running no git operation. Used when adding a repo so
     * the user can confirm the fingerprint before anything is trusted.
     */
    suspend fun probeHostKey(remoteUrl: String): Result<String>

    /** Names of every ref in a local mirror, for showing what was captured. */
    fun refNames(destination: File): List<String>

    /** On-disk size of a mirror, or 0 when it does not exist. */
    fun sizeBytes(destination: File): Long
}
