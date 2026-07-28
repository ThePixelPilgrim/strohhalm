package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A repository whose host key was never confirmed must not be contacted: the
 * engine would refuse the unpinned key and the row would collect a failure
 * every 15 minutes for a state the UI already explains. Skipping is silent —
 * no SYNCING, no error, no attempt timestamp.
 */
class SyncRunnerSkipTest {

    private val dao = FakeRepoDao()
    private val repos = DefaultRepoRepository(
        dao = dao,
        storageRoot = { File("/storage/emulated/0/Strohhalm") },
        clock = { 1_000L },
    )

    private val syncCalls = AtomicInteger(0)

    private val countingMirror = object : GitMirror {
        override suspend fun sync(
            remoteUrl: String,
            destination: File,
            pinnedFingerprint: String?,
            progress: MirrorProgress?,
        ): MirrorOutcome {
            syncCalls.incrementAndGet()
            return MirrorOutcome.Success(sizeBytes = 0, refCount = 0)
        }

        override suspend fun probeHostKey(remoteUrl: String) = Result.failure<String>(
            UnsupportedOperationException()
        )

        override fun refNames(destination: File): List<String> = emptyList()

        override fun sizeBytes(destination: File): Long = 0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runner = SyncRunner(repos, countingMirror, scope)

    private suspend fun awaitIdle() =
        withTimeout(TimeUnit.SECONDS.toMillis(10)) { runner.running.first { !it } }

    @Test
    fun `sync-all only touches verified repositories`() = runBlocking {
        val unverified = repos.add("Pending", "ssh://git@host/srv/pending.git", null)
        repos.add("Ready", "ssh://git@host/srv/ready.git", "SHA256:aaa")

        runner.launchSyncAll()
        awaitIdle()

        assertEquals("only the verified repo syncs", 1, syncCalls.get())
        val row = dao.byId(unverified)!!
        assertEquals(SyncStatus.NEVER, row.lastStatus)
        assertNull("no error for a skipped repo", row.lastErrorCode)
        assertNull("no attempt recorded", row.lastAttemptAt)
    }

    @Test
    fun `sync-one refuses an unverified repository`() = runBlocking {
        val id = repos.add("Pending", "ssh://git@host/srv/pending.git", null)

        runner.launchSyncOne(id)
        awaitIdle()

        assertEquals(0, syncCalls.get())
        assertEquals(SyncStatus.NEVER, dao.byId(id)!!.lastStatus)
    }

    /**
     * Skipping is not starting. The contract on the return value —
     * "`false` means nothing is coming" — is what `retrySync` builds its
     * share-card state on, and a `true` for a skipped repository would leave
     * that card waiting for a completion that never arrives.
     */
    @Test
    fun `skipping is not starting - the caller is told nothing is coming`() = runBlocking {
        val id = repos.add("Pending", "ssh://git@host/srv/pending.git", null)

        assertFalse("sync-one of an unverified repo starts nothing", runner.launchSyncOne(id))
        assertFalse("sync-all with nothing verified starts nothing", runner.launchSyncAll())
        assertEquals(0, syncCalls.get())
    }

    /**
     * A skipped sync must not touch shared state either: no running flip that
     * flashes the spinner, and no mirror-lock acquisition that a concurrent
     * share would lose against.
     */
    @Test
    fun `skipping takes neither the running flag nor the mirror lock`() = runBlocking {
        val access = MirrorAccess()
        val guarded = SyncRunner(repos, countingMirror, scope, NoForegroundHold, access)
        repos.add("Pending", "ssh://git@host/srv/pending.git", null)

        guarded.launchSyncAll()

        assertFalse("running never rose for a skip", guarded.running.value)
        assertTrue(
            "the mirror lock was never taken",
            access.tryAcquire(MirrorAccess.Owner.ARCHIVE),
        )
        access.release(MirrorAccess.Owner.ARCHIVE)
    }
}
