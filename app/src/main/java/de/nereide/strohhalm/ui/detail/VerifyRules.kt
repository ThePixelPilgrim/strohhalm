package de.nereide.strohhalm.ui.detail

import de.nereide.strohhalm.domain.ProbeRejectedException
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors

/** What a finished probe means for the verification flow. */
sealed interface VerifyOutcome {
    /** A fingerprint to show the user, [authFailed] when the server refused auth. */
    data class Pending(val fingerprint: String, val authFailed: Boolean) : VerifyOutcome

    data class Failed(val error: SyncError) : VerifyOutcome
}

/**
 * Maps a probe result to its verification outcome.
 *
 * The one non-obvious rule: an auth failure that carries a fingerprint is an
 * *offer to pin* — the missing-public-key case this whole flow exists for —
 * while any other failure, fingerprint or not, is just a failure. A server
 * that refused the *repository* spoke after auth succeeded; pinning then
 * would trust a server the user may simply have mistyped.
 */
object VerifyRules {

    /**
     * What declining a pending fingerprint leaves behind. Declining an
     * auth-flagged offer must not erase what the probe learned — the server
     * refused authentication, and the key-setup card is driven by exactly
     * that state. A declined ordinary offer leaves nothing.
     */
    fun onDismiss(authFailed: Boolean): SyncError? =
        if (authFailed) {
            SyncError(
                SyncErrorCode.AUTH_FAILED,
                "the server refused the key this device signs with",
            )
        } else {
            null
        }

    fun fromProbe(result: Result<String>): VerifyOutcome = result.fold(
        onSuccess = { VerifyOutcome.Pending(it, authFailed = false) },
        onFailure = { failure ->
            val error = SyncErrors.fromException(failure)
            val probe = failure as? ProbeRejectedException
            if (probe != null && error.code == SyncErrorCode.AUTH_FAILED) {
                VerifyOutcome.Pending(probe.fingerprint, authFailed = true)
            } else {
                VerifyOutcome.Failed(error)
            }
        },
    )
}
