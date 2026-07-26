package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

    private val syncCalls = AtomicInteger(0)

    /** Returns at once, but records that it was reached at all. */
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

    /**
     * The other half of the same directory: a fetch must not start while an
     * archive is reading the mirror. Nothing in the runner's own bookkeeping
     * knows about the archive — `running` is false, the job is null — so only
     * the shared [MirrorAccess] can stop it.
     */
    @Test
    fun `a sync does not start while an archive holds the mirror`() = runBlocking {
        val access = MirrorAccess()
        val id = repos.add("Notes", "ssh://git@host/srv/notes.git", "SHA256:aaa")
        val guarded = SyncRunner(repos, countingMirror, scope, NoForegroundHold, access)

        assertTrue("the archive should get the lock", access.tryAcquire(MirrorAccess.Owner.ARCHIVE))
        guarded.launchSyncOne(id)

        // Generous enough that a sync which *did* start would have reached the
        // mirror and marked the row by now.
        Thread.sleep(500)

        assertEquals("no fetch may run mid-archive", 0, syncCalls.get())
        assertFalse("the runner must not claim to be running", guarded.running.first())
        assertEquals(SyncStatus.NEVER, dao.byId(id)!!.lastStatus)

        // And once the archive is done, the same runner works normally.
        access.release(MirrorAccess.Owner.ARCHIVE)
        guarded.launchSyncOne(id)
        withTimeout(TimeUnit.SECONDS.toMillis(10)) { guarded.running.first { !it } }
        assertEquals("the lock must be released, not leaked", 1, syncCalls.get())
    }

    /**
     * `running` going false is the signal every caller waits on, so the lock has
     * to be free *before* that is visible — not merely soon afterwards.
     *
     * The collector runs `Unconfined` deliberately: it resumes inside the
     * emission, on the emitting thread, which is exactly where a caller reacting
     * to `running` lands. Release the lock after clearing the flag and this
     * observes the window; release it before and there is no window to observe.
     */
    @Test
    fun `the mirror lock is free by the time the runner reports it is idle`() = runBlocking {
        val access = MirrorAccess()
        val id = repos.add("Notes", "ssh://git@host/srv/notes.git", "SHA256:aaa")
        val guarded = SyncRunner(repos, countingMirror, scope, NoForegroundHold, access)

        val lockFreeWhenIdle = AtomicReference<Boolean?>(null)
        var wasRunning = false
        val watcher = launch(Dispatchers.Unconfined) {
            guarded.running.collect { isRunning ->
                if (isRunning) {
                    wasRunning = true
                } else if (wasRunning && lockFreeWhenIdle.get() == null) {
                    val free = access.tryAcquire(MirrorAccess.Owner.ARCHIVE)
                    lockFreeWhenIdle.set(free)
                    if (free) access.release(MirrorAccess.Owner.ARCHIVE)
                }
            }
        }

        guarded.launchSyncOne(id)
        withTimeout(TimeUnit.SECONDS.toMillis(10)) { guarded.running.first { !it } }
        watcher.cancel()

        assertEquals(
            "an archive starting the moment the sync went idle would have been refused",
            true,
            lockFreeWhenIdle.get(),
        )
    }

    /**
     * A dropped launch has to be *reported* dropped. The caller decides what it
     * means — `RepoDetailViewModel.retrySync` moves the share to Waiting only if
     * a sync is genuinely in flight, and would otherwise wait for one that is
     * never coming.
     */
    @Test
    fun `launching says whether a sync actually started`() = runBlocking {
        val access = MirrorAccess()
        val id = repos.add("Notes", "ssh://git@host/srv/notes.git", "SHA256:aaa")
        val guarded = SyncRunner(repos, countingMirror, scope, NoForegroundHold, access)

        assertTrue(access.tryAcquire(MirrorAccess.Owner.ARCHIVE))
        assertFalse("an archive holds the mirror", guarded.launchSyncOne(id))
        assertFalse("and so does a sync all", guarded.launchSyncAll())

        access.release(MirrorAccess.Owner.ARCHIVE)
        assertTrue("nothing is in the way now", guarded.launchSyncOne(id))
        withTimeout(TimeUnit.SECONDS.toMillis(10)) { guarded.running.first { !it } }
        assertEquals(1, syncCalls.get())
    }
}
