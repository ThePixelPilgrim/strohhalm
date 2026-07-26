package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A sync that cannot be stopped is a sync the user must force-quit the app to
 * escape — and force-quitting is exactly what leaves rows stranded at SYNCING.
 *
 * The mirror here blocks the way the real one does: inside `runInterruptible`,
 * in a blocking call that no amount of coroutine bookkeeping can unwind on its
 * own. Cancelling has to actually interrupt the thread, or nothing moves.
 */
class SyncRunnerCancelTest {

    private val dao = FakeRepoDao()
    private val repos = DefaultRepoRepository(
        dao = dao,
        storageRoot = { File("/storage/emulated/0/Strohhalm") },
        clock = { 1_000L },
    )

    private val started = CountDownLatch(1)

    /** Blocks until interrupted, as a real transfer against a silent server does. */
    private val blockingMirror = object : GitMirror {
        override suspend fun sync(
            remoteUrl: String,
            destination: File,
            pinnedFingerprint: String?,
            progress: MirrorProgress?,
        ): MirrorOutcome = runInterruptible(Dispatchers.IO) {
            started.countDown()
            Thread.sleep(TimeUnit.MINUTES.toMillis(5))
            MirrorOutcome.Success(sizeBytes = 0, refCount = 0)
        }

        override suspend fun probeHostKey(remoteUrl: String) = Result.failure<String>(
            UnsupportedOperationException()
        )

        override fun refNames(destination: File): List<String> = emptyList()

        override fun sizeBytes(destination: File): Long = 0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runner = SyncRunner(repos, blockingMirror, scope)

    @Test
    fun `cancel unblocks a stuck sync and records it as cancelled`() = runBlocking {
        val id = repos.add("Notes", "ssh://git@host/srv/notes.git", "SHA256:aaa")

        runner.launchSyncAll()
        assertTrue("sync never started", started.await(5, TimeUnit.SECONDS))

        runner.cancel()

        // Generous, but nowhere near the five minutes the mirror would otherwise
        // block for: this fails loudly if cancellation is merely bookkeeping.
        withTimeout(TimeUnit.SECONDS.toMillis(10)) { runner.running.first { !it } }

        val repo = dao.byId(id)!!
        assertEquals(SyncStatus.FAILED, repo.lastStatus)
        assertEquals(SyncErrorCode.CANCELLED.name, repo.lastErrorCode)
        assertNull("a cancelled sync is not a completed one", repo.lastSyncAt)
        assertNull("no progress should survive the cancel", runner.progress.first())
    }
}
