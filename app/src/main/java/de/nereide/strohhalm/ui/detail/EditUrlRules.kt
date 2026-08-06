package de.nereide.strohhalm.ui.detail

/**
 * When a typed remote URL is worth writing. Pure, in the shape of
 * [VerifyRules] and [ShareRules], because the answer is a decision rather than
 * a side effect: persisting the unchanged URL would clear the pinned host key
 * and demand a fresh confirmation for a server the user never left.
 */
object EditUrlRules {

    /**
     * The URL to store, or null to leave the repository alone. Trimmed —
     * a pasted URL commonly arrives with a trailing newline, and the
     * comparison against [current] has to see past that too.
     */
    fun urlToPersist(current: String?, typed: String): String? {
        val trimmed = typed.trim()
        return when {
            trimmed.isEmpty() -> null
            trimmed == current?.trim() -> null
            else -> trimmed
        }
    }
}
