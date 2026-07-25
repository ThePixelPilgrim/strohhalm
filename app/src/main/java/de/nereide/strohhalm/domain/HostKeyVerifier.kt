package de.nereide.strohhalm.domain

/** The outcome of comparing a server's key against what was pinned for it. */
sealed interface HostKeyDecision {

    /** Nothing pinned yet — the user must be shown the fingerprint and asked. */
    data object FirstUse : HostKeyDecision

    /** The presented key matches the pinned one. */
    data object Trusted : HostKeyDecision

    /**
     * The server presented a different key than the one pinned. This may mean the
     * server was rebuilt, or that something is intercepting the connection, so it
     * is surfaced distinctly rather than as a generic failure.
     */
    data class Mismatch(val stored: String, val presented: String) : HostKeyDecision
}

/**
 * Trust-on-first-use host key policy, reduced to a pure comparison so that every
 * branch is unit-testable without a network or a server.
 *
 * Fingerprints are OpenSSH-style `SHA256:<base64>` strings. Comparison is exact
 * apart from surrounding whitespace: base64 is case-sensitive, so lowercasing
 * would make distinct keys compare equal.
 */
object HostKeyVerifier {

    fun verify(stored: String?, presented: String): HostKeyDecision {
        val pinned = stored?.trim()
        if (pinned.isNullOrEmpty()) return HostKeyDecision.FirstUse
        return if (pinned == presented.trim()) {
            HostKeyDecision.Trusted
        } else {
            HostKeyDecision.Mismatch(stored = pinned, presented = presented.trim())
        }
    }
}
