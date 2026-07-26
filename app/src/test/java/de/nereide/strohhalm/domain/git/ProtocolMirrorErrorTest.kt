package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class ProtocolMirrorErrorTest {

    @Test
    fun `a band 3 message becomes a remote error carrying the server's words`() {
        val error = SyncErrors.fromException(SidebandException("repository not found"))
        assertEquals(SyncErrorCode.REMOTE_ERROR, error.code)
        assertEquals("the server said: repository not found", error.detail)
    }

    @Test
    fun `a pack checksum mismatch is local corruption, not an unknown fault`() {
        val error = SyncErrors.fromException(
            IOException("pack checksum mismatch: the transfer was corrupted")
        )
        assertEquals(SyncErrorCode.LOCAL_CORRUPT, error.code)
    }

    @Test
    fun `an old server is a remote error naming the protocol version`() {
        val error = SyncErrors.fromException(
            IOException("the server offered protocol version 0; Strohhalm requires version 2")
        )
        assertEquals(SyncErrorCode.REMOTE_ERROR, error.code)
    }
}
