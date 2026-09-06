package de.nereide.strohhalm.work

import de.nereide.strohhalm.domain.DefaultRepoRepository
import de.nereide.strohhalm.domain.FakeRepoDao
import de.nereide.strohhalm.domain.GitMirror
import de.nereide.strohhalm.domain.MirrorOutcome
import de.nereide.strohhalm.domain.MirrorProgress
import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.domain.SyncRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The scheduled run is the manual "sync all" with three things bolted on:
 * it refuses when the device cannot sync, it stands aside when a sync is
 * already in flight, and it waits for the outcome so the worker can report
 * what went wrong to a user who is not looking at the screen.
 */
class ScheduledSyncTest {

    private val dao = FakeRepoDao()
    private val repos = DefaultRepoRepository(
        dao = dao,
        storageRoot = { File("/storage/emulated/0/Strohhalm") },
        clock = { 1_000L },
    )

    private val syncCalls = AtomicInteger(0)

    /** Fails every remote whose URL contains "broken", succeeds otherwise. */
    private val mirror = object : GitMirror {
        override suspend fun sync(
            remoteUrl: String,
            destination: File,
            pinnedFingerprint: String?,
            progress: MirrorProgress?,
        ): MirrorOutcome {
            syncCalls.incrementAndGet()
            return if ("broken" in remoteUrl) {
                MirrorOutcome.Failure(SyncError(SyncErrorCode.HOST_UNREACHABLE))
            } else {
                MirrorOutcome.Success(sizeBytes = 0, refCount = 0)
            }
        }

        override suspend fun probeHostKey(remoteUrl: String) = Result.failure<String>(
            UnsupportedOperationException()
        )

        override fun refNames(destination: File): List<String> = emptyList()

        override fun sizeBytes(destination: File): Long = 0
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runner = SyncRunner(repos, mirror, scope)

    private fun scheduled(blocked: SyncError? = null) = ScheduledSync(
        runner = runner,
        repos = repos,
        preconditions = { blocked },
        clock = { 1_000L },
    )

    @Test
    fun `a blocked precondition refuses without contacting anything`() = runBlocking {
        repos.add("Ready", "ssh://git@host/srv/ready.git", "SHA256:aaa")
        val blocked = SyncError(SyncErrorCode.LOW_STORAGE)

        val outcome = scheduled(blocked).run()

        assertEquals(ScheduledSync.Outcome.Blocked(blocked), outcome)
        assertEquals(0, syncCalls.get())
    }

    @Test
    fun `nothing verified means nothing to do`() = runBlocking {
        repos.add("Pending", "ssh://git@host/srv/pending.git", null)

        assertEquals(ScheduledSync.Outcome.NothingToSync, scheduled().run())
        assertEquals(0, syncCalls.get())
    }

    @Test
    fun `a clean run reports no failures`() = runBlocking {
        repos.add("A", "ssh://git@host/srv/a.git", "SHA256:aaa")
        repos.add("B", "ssh://git@host/srv/b.git", "SHA256:bbb")

        val outcome = scheduled().run()

        assertEquals(ScheduledSync.Outcome.Completed(failures = emptyList()), outcome)
        assertEquals(2, syncCalls.get())
    }

    @Test
    fun `failures are reported by name with their error`() = runBlocking {
        repos.add("Fine", "ssh://git@host/srv/fine.git", "SHA256:aaa")
        repos.add("Broken", "ssh://git@host/srv/broken.git", "SHA256:bbb")

        val outcome = scheduled().run() as ScheduledSync.Outcome.Completed

        assertEquals(1, outcome.failures.size)
        assertEquals("Broken", outcome.failures.single().repoName)
        assertEquals(SyncErrorCode.HOST_UNREACHABLE, outcome.failures.single().error.code)
    }

    /**
     * A failure from before this run is not this run's failure. Otherwise a
     * repository that was skipped this cycle would be reported every cycle.
     */
    @Test
    fun `a stale failure on an unverified repository is not reported`() = runBlocking {
        val stale = repos.add("Old", "ssh://git@host/srv/old.git", null)
        repos.markFailure(stale, SyncError(SyncErrorCode.AUTH_FAILED))
        repos.add("Fine", "ssh://git@host/srv/fine.git", "SHA256:aaa")

        val later = ScheduledSync(
            runner = runner,
            repos = repos,
            preconditions = { null },
            clock = { 2_000L },
        )

        assertEquals(ScheduledSync.Outcome.Completed(failures = emptyList()), later.run())
    }

    @Test
    fun `a sync already in flight is left alone`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val slowMirror = object : GitMirror by mirror {
            override suspend fun sync(
                remoteUrl: String,
                destination: File,
                pinnedFingerprint: String?,
                progress: MirrorProgress?,
            ): MirrorOutcome {
                gate.await()
                return MirrorOutcome.Success(0, 0)
            }
        }
        val slowRunner = SyncRunner(repos, slowMirror, scope)
        repos.add("A", "ssh://git@host/srv/a.git", "SHA256:aaa")
        assertTrue(slowRunner.launchSyncAll())
        withTimeout(TimeUnit.SECONDS.toMillis(5)) { slowRunner.running.first { it } }

        val outcome = ScheduledSync(
            runner = slowRunner,
            repos = repos,
            preconditions = { null },
            clock = { 1_000L },
        ).run()

        assertEquals(ScheduledSync.Outcome.AlreadyRunning, outcome)
        gate.complete(Unit)
        withTimeout(TimeUnit.SECONDS.toMillis(5)) { slowRunner.running.first { !it } }
        Unit
    }

    @Test
    fun `a scheduled cycle is logged as scheduled`() = runBlocking {
        val log = de.nereide.strohhalm.domain.RecordingSyncLog()
        val logging = SyncRunner(repos, mirror, scope, log = log)
        repos.add("A", "ssh://git@host/srv/a.git", "SHA256:aaa")

        ScheduledSync(runner = logging, repos = repos, preconditions = { null }, clock = { 1_000L }).run()

        assertEquals(
            de.nereide.strohhalm.data.SyncTrigger.SCHEDULED,
            log.events.single().trigger,
        )
    }
}
