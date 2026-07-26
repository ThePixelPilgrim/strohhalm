package de.nereide.strohhalm.ui.detail

import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine only, as a pure function of what has happened. The
 * ViewModel wires it to flows; the rules live here so they can be pinned
 * without a device.
 */
class ShareStateMachineTest {

    private val failure = SyncError(SyncErrorCode.HOST_UNREACHABLE, "no route to host")

    @Test
    fun `sharing while a sync runs waits for it`() {
        val state = ShareRules.onShareRequested(syncing = true, everSynced = true)
        assertTrue(state is ShareState.Waiting)
        assertFalse((state as ShareState.Waiting).neverSynced)
    }

    @Test
    fun `sharing a never-synced repository waits, and says so`() {
        val state = ShareRules.onShareRequested(syncing = false, everSynced = false)
        assertEquals(ShareState.Waiting(neverSynced = true), state)
    }

    @Test
    fun `sharing an idle, previously synced repository packs immediately`() {
        assertEquals(
            ShareState.Packing(0, 0),
            ShareRules.onShareRequested(syncing = false, everSynced = true),
        )
    }

    @Test
    fun `a sync that fails while waiting offers both ways forward`() {
        val state = ShareRules.onSyncFinished(failed = failure, cancelled = false, everSynced = true)
        val blocked = state as ShareState.Blocked
        assertEquals(failure, blocked.error)
        assertFalse(blocked.cancelled)
        assertTrue(blocked.canShareAnyway)
    }

    /** Nothing to share means no offer to share it. */
    @Test
    fun `a never-synced repository is never offered share anyway`() {
        val blocked = ShareRules.onSyncFinished(failed = failure, cancelled = false, everSynced = false)
            as ShareState.Blocked
        assertFalse(blocked.canShareAnyway)
    }

    @Test
    fun `a cancelled sync is not presented as a failure`() {
        val blocked = ShareRules.onSyncFinished(
            failed = SyncError(SyncErrorCode.CANCELLED), cancelled = true, everSynced = true,
        ) as ShareState.Blocked
        assertTrue(blocked.cancelled)
        assertTrue(blocked.canShareAnyway)
    }

    @Test
    fun `a sync that succeeds while waiting goes on to pack`() {
        assertEquals(
            ShareState.Packing(0, 0),
            ShareRules.onSyncFinished(failed = null, cancelled = false, everSynced = true),
        )
    }

    /** The share survives a retry; that is the whole reason the button is here. */
    @Test
    fun `retrying returns to waiting rather than abandoning the share`() {
        assertEquals(ShareState.Waiting(neverSynced = false), ShareRules.onRetry(everSynced = true))
    }
}
