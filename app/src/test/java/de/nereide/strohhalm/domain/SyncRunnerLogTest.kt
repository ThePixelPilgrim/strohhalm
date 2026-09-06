package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEventOutcome
import de.nereide.strohhalm.data.SyncStatus
import de.nereide.strohhalm.data.SyncTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One entry per attempt, stamped with what the mirror reported. The log is
 * bookkeeping: it must reflect the row exactly, and it must never be the
 * reason a sync is marked failed.
 */
class SyncRunnerLogTest {

    private val dao = FakeRepoDao()
    private val repos = DefaultRepoRepository(
        dao = dao,
        storageRoot = { File("/storage/emulated/0/Strohhalm") },
        clock = { 1_000L },
    )
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun mirrorReturning(outcome: MirrorOutcome) = object : GitMirror {
        override suspend fun sync(
            remoteUrl: String,
            destination: File,
            pinnedFingerprint: String?,
            progress: MirrorProgress?,
        ): MirrorOutcome = outcome

        override suspend fun probeHostKey(remoteUrl: String) = Result.failure<String>(
            UnsupportedOperationException()
        )

        override fun refNames(destination: File): List<String> = emptyList()

        override fun sizeBytes(destination: File): Long = 0
    }

    private suspend fun SyncRunner.awaitIdle() =
        withTimeout(TimeUnit.SECONDS.toMillis(10)) { running.first { !it } }

    @Test
    fun `a sync that received data is logged with its numbers`() = runBlocking {
        val log = RecordingSyncLog()
        var now = 5_000L
        val runner = SyncRunner(
            repos, mirrorReturning(MirrorOutcome.Success(sizeBytes = 99, refCount = 3, bytesReceived = 4_096, refsChanged = 2)),
            scope, log = log, clock = { now.also { now += 250 } },
        )
        val id = repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncAll()
        runner.awaitIdle()

        val event = log.events.single()
        assertEquals(id, event.repoId)
        assertEquals("Alpha", event.repoName)
        assertEquals(SyncEventOutcome.RECEIVED, event.outcome)
        assertEquals(4_096L, event.bytesReceived)
        assertEquals(2, event.refsChanged)
        assertEquals(SyncTrigger.MANUAL, event.trigger)
        assertEquals(5_000L, event.startedAt)
        assertEquals(5_250L, event.finishedAt)
        assertNull(event.errorCode)
    }

    @Test
    fun `a sync with nothing to fetch is logged as up to date`() = runBlocking {
        val log = RecordingSyncLog()
        val runner = SyncRunner(repos, mirrorReturning(MirrorOutcome.Success(99, 3)), scope, log = log)
        repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncAll()
        runner.awaitIdle()

        assertEquals(SyncEventOutcome.UP_TO_DATE, log.events.single().outcome)
        assertEquals(0L, log.events.single().bytesReceived)
    }

    @Test
    fun `a failure is logged with its error code`() = runBlocking {
        val log = RecordingSyncLog()
        val runner = SyncRunner(
            repos, mirrorReturning(MirrorOutcome.Failure(SyncError(SyncErrorCode.AUTH_FAILED))),
            scope, log = log,
        )
        repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncAll()
        runner.awaitIdle()

        assertEquals(SyncEventOutcome.FAILED, log.events.single().outcome)
        assertEquals("AUTH_FAILED", log.events.single().errorCode)
    }

    @Test
    fun `the trigger is whatever the caller says`() = runBlocking {
        val log = RecordingSyncLog()
        val runner = SyncRunner(repos, mirrorReturning(MirrorOutcome.Success(99, 3)), scope, log = log)
        val id = repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncOne(id, SyncTrigger.SCHEDULED)
        runner.awaitIdle()

        assertEquals(SyncTrigger.SCHEDULED, log.events.single().trigger)
    }

    @Test
    fun `a cancelled sync is logged as cancelled`() = runBlocking {
        val log = RecordingSyncLog()
        val started = CountDownLatch(1)
        val blocking = object : GitMirror by mirrorReturning(MirrorOutcome.Success(0, 0)) {
            override suspend fun sync(
                remoteUrl: String,
                destination: File,
                pinnedFingerprint: String?,
                progress: MirrorProgress?,
            ): MirrorOutcome = runInterruptible(Dispatchers.IO) {
                started.countDown()
                Thread.sleep(TimeUnit.MINUTES.toMillis(5))
                MirrorOutcome.Success(0, 0)
            }
        }
        val runner = SyncRunner(repos, blocking, scope, log = log)
        repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncAll()
        started.await(10, TimeUnit.SECONDS)
        runner.cancel()
        runner.awaitIdle()

        assertEquals(SyncEventOutcome.CANCELLED, log.events.single().outcome)
    }

    @Test
    fun `a log that throws does not fail the sync`() = runBlocking {
        val runner = SyncRunner(
            repos, mirrorReturning(MirrorOutcome.Success(99, 3)), scope,
            log = RecordingSyncLog(failing = true),
        )
        val id = repos.add("Alpha", "ssh://git@host/srv/alpha.git", "SHA256:aaa")

        runner.launchSyncAll()
        runner.awaitIdle()

        assertEquals(SyncStatus.OK, dao.byId(id)!!.lastStatus)
    }
}
