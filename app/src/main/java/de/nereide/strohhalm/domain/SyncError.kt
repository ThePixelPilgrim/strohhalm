package de.nereide.strohhalm.domain

import org.eclipse.jgit.errors.NoRemoteRepositoryException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Why a sync could not complete. Stored on the repository row and rendered by
 * the UI, which maps each code to a string resource — the domain layer stays
 * free of Android resource ids.
 */
enum class SyncErrorCode {
    NO_NETWORK,
    LOW_STORAGE,
    PERMISSION_LOST,
    AUTH_FAILED,
    HOST_KEY_MISMATCH,
    HOST_UNREACHABLE,
    REMOTE_ERROR,
    LOCAL_CORRUPT,
    UNKNOWN,
}

data class SyncError(val code: SyncErrorCode, val detail: String? = null)

/**
 * Raised by the server key database when a host presents a key other than the
 * pinned one. Distinct from every other failure because it may indicate an
 * interception rather than an outage.
 */
class HostKeyMismatchException(
    val stored: String,
    val presented: String,
) : Exception("host key mismatch: expected $stored, got $presented")

/**
 * Translates library exceptions into [SyncError]. This is the single boundary
 * where JGit and MINA SSHD exception types are allowed to be inspected; nothing
 * above the domain layer ever sees a `TransportException`.
 */
object SyncErrors {

    fun fromException(t: Throwable): SyncError {
        for (cause in causeChain(t)) {
            classify(cause)?.let { return it }
        }
        return SyncError(SyncErrorCode.UNKNOWN, t.message)
    }

    private fun classify(t: Throwable): SyncError? = when {
        t is HostKeyMismatchException ->
            SyncError(
                SyncErrorCode.HOST_KEY_MISMATCH,
                "expected ${t.stored}, got ${t.presented}"
            )

        t is UnknownHostException ->
            SyncError(SyncErrorCode.HOST_UNREACHABLE, t.message)

        t is SocketTimeoutException ->
            SyncError(SyncErrorCode.HOST_UNREACHABLE, t.message)

        t is NoRemoteRepositoryException ->
            SyncError(SyncErrorCode.REMOTE_ERROR, t.message)

        else -> classifyByMessage(t)
    }

    private fun classifyByMessage(t: Throwable): SyncError? {
        val message = t.message ?: return null
        val lower = message.lowercase()
        return when {
            "auth fail" in lower ||
                "permission denied" in lower ||
                "publickey" in lower ->
                SyncError(SyncErrorCode.AUTH_FAILED, message)

            "connection refused" in lower ||
                "unreachable" in lower ||
                "connection reset" in lower ->
                SyncError(SyncErrorCode.HOST_UNREACHABLE, message)

            "not found" in lower ||
                "does not appear to be a git repository" in lower ->
                SyncError(SyncErrorCode.REMOTE_ERROR, message)

            "corrupt" in lower || "invalid object" in lower ->
                SyncError(SyncErrorCode.LOCAL_CORRUPT, message)

            else -> null
        }
    }

    private fun causeChain(t: Throwable): Sequence<Throwable> =
        generateSequence(t) { current -> current.cause.takeIf { it !== current } }
}
