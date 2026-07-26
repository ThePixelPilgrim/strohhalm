package de.nereide.strohhalm.domain.git

import de.nereide.strohhalm.domain.MirrorOutcome
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncErrors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ProtocolMirrorErrorTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * Regression: a mirror onto a removable volume that is absent or read-only
     * reported `code=UNKNOWN` with a raw `ENOENT`, after opening an SSH session.
     *
     * Storage is checked before the network — and before the keystore — because
     * that is the failure the user can actually act on, and because unlocking a
     * key to discover the destination is gone is pure waste.
     */
    @Test
    fun `an unusable destination fails as a permission problem, before the key is touched`() =
        runBlocking {
            val blocked = temp.newFile("not-a-directory")
            val destination = File(blocked, "yamiro.git")

            val mirror = ProtocolMirror(
                keyPairProvider = {
                    throw AssertionError("the key must not be unlocked when storage is unusable")
                },
            )

            val outcome = mirror.sync(
                remoteUrl = "ssh://git@example.org/owner/repo.git",
                destination = destination,
                pinnedFingerprint = null,
                progress = null,
            )

            val failure = outcome as MirrorOutcome.Failure
            assertEquals(SyncErrorCode.PERMISSION_LOST, failure.error.code)
            assertTrue(
                "expected the unusable directory in the detail, got: ${failure.error.detail}",
                failure.error.detail!!.contains(blocked.path),
            )
        }

    /**
     * The device case exactly: the SD card was not mounted, so nothing above
     * the backup folder existed either. The message must point at the storage,
     * and name the nearest folder that does exist so "card gone" is
     * distinguishable from "folder deleted".
     */
    @Test
    fun `an unmounted volume names the nearest folder that still exists`() = runBlocking {
        val root = temp.newFolder("storage")
        val destination = File(root, "57EC-7BD6/Documents/GitBackup/yamiro.git")

        // Nothing may be creatable below the vanished volume.
        root.setWritable(false)

        val mirror = ProtocolMirror(keyPairProvider = { throw AssertionError("not reached") })
        val outcome = mirror.sync("ssh://git@example.org/o/r.git", destination, null, null)

        val failure = outcome as MirrorOutcome.Failure
        assertEquals(SyncErrorCode.PERMISSION_LOST, failure.error.code)
        assertTrue(
            "expected the nearest surviving folder, got: ${failure.error.detail}",
            failure.error.detail!!.contains(root.path),
        )
        assertTrue(
            "expected the removable-card hint, got: ${failure.error.detail}",
            failure.error.detail!!.contains("card"),
        )
    }

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
