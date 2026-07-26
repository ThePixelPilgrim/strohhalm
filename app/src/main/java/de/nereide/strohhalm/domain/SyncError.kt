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
    /** The process died mid-sync; the row was left claiming to be running. */
    INTERRUPTED,
    UNKNOWN,
}

/**
 * [detail] is the library's own message, shown under the friendly text.
 *
 * [diagnostic] is the exception class chain — e.g.
 * `TransportException <- SshException <- SocketTimeoutException`. It exists
 * because the app is being debugged from the device without adb, where the UI is
 * the only channel back. A message alone frequently cannot distinguish an SSH
 * negotiation failure from a plain network timeout; the type chain can.
 */
data class SyncError(
    val code: SyncErrorCode,
    val detail: String? = null,
    val diagnostic: String? = null,
)

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
 * The SSH handshake succeeded but the server refused the repository, explaining
 * itself on stderr — where JGit's pack transport cannot see it.
 */
class ProbeRejectedException(
    val fingerprint: String,
    val serverMessage: String,
    cause: Throwable,
) : Exception("the server said: $serverMessage", cause)

/**
 * Translates library exceptions into [SyncError]. This is the single boundary
 * where JGit and MINA SSHD exception types are allowed to be inspected; nothing
 * above the domain layer ever sees a `TransportException`.
 */
object SyncErrors {

    fun fromException(t: Throwable): SyncError {
        val diagnostic = describeChain(t)
        for (cause in causeChain(t)) {
            classify(cause)?.let { return it.copy(diagnostic = diagnostic) }
        }
        return SyncError(SyncErrorCode.UNKNOWN, t.message, diagnostic)
    }

    /**
     * The full cause chain — class, message, and the root cause's stack frames.
     *
     * The class names alone proved insufficient: a chain ending in
     * `NoClassDefFoundError <- ExceptionInInitializerError <- IllegalArgumentException`
     * says a static initialiser threw, but not *which class* failed to load —
     * and that name lives in the `NoClassDefFoundError`'s message, which an
     * earlier version discarded.
     *
     * Verbose by design. The device is the only place this app can be observed,
     * so the copy button has to carry everything a logcat line would.
     */
    private fun describeChain(t: Throwable): String = buildString {
        causeChain(t).forEachIndexed { index, cause ->
            appendLine("${index + 1}. ${cause::class.java.name}")
            cause.message?.takeIf { it.isNotBlank() }?.let { appendLine("   $it") }
        }
        val root = causeChain(t).last()
        appendLine()
        appendLine("root cause stack:")
        root.stackTrace.take(MAX_FRAMES).forEach { frame -> appendLine("   at $frame") }
        if (root.stackTrace.size > MAX_FRAMES) {
            appendLine("   … ${root.stackTrace.size - MAX_FRAMES} more frames")
        }
    }.trim()

    /** Enough to identify the failing initialiser without producing a wall of text. */
    private const val MAX_FRAMES = 25

    private fun classify(t: Throwable): SyncError? = when {
        t is ProbeRejectedException ->
            SyncError(SyncErrorCode.REMOTE_ERROR, "the server said: ${t.serverMessage}")

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
