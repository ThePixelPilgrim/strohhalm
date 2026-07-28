package de.nereide.strohhalm.ui.detail

import de.nereide.strohhalm.domain.ProbeRejectedException
import de.nereide.strohhalm.domain.SyncErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.UnknownHostException

/**
 * What each probe result means for the verification flow. Pure, because the
 * decision is subtle enough to deserve tests without a ViewModel around it:
 * an auth failure carrying a fingerprint is an *offer to pin*, not an error.
 */
class VerifyRulesTest {

    @Test
    fun `a clean probe offers the fingerprint`() {
        assertEquals(
            VerifyOutcome.Pending("SHA256:abc", authFailed = false),
            VerifyRules.fromProbe(Result.success("SHA256:abc")),
        )
    }

    @Test
    fun `auth refused with a key seen still offers the fingerprint, flagged`() {
        val failure = ProbeRejectedException(
            fingerprint = "SHA256:abc",
            serverMessage = "",
            cause = Exception("Permission denied (publickey)"),
        )
        assertEquals(
            VerifyOutcome.Pending("SHA256:abc", authFailed = true),
            VerifyRules.fromProbe(Result.failure(failure)),
        )
    }

    /**
     * The server spoke: auth worked and the repository itself was refused.
     * Pinning would be premature — the URL is wrong, and the fix is to correct
     * it, not to trust a server the user may have mistyped.
     */
    @Test
    fun `a server-refused repository is a failure, not an offer`() {
        val failure = ProbeRejectedException(
            fingerprint = "SHA256:abc",
            serverMessage = "repository not found",
            cause = Exception("stream ended"),
        )
        val outcome = VerifyRules.fromProbe(Result.failure(failure))
        assertEquals(
            SyncErrorCode.REMOTE_ERROR,
            (outcome as VerifyOutcome.Failed).error.code,
        )
    }

    @Test
    fun `an unreachable host is a plain failure`() {
        val outcome = VerifyRules.fromProbe(Result.failure(UnknownHostException("no.such.host")))
        assertEquals(
            SyncErrorCode.HOST_UNREACHABLE,
            (outcome as VerifyOutcome.Failed).error.code,
        )
    }
}
