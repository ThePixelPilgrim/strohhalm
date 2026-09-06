package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventOutcome
import de.nereide.strohhalm.data.SyncTrigger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class DefaultSyncLogTest {

    private val dao = FakeSyncEventDao()
    private val log = DefaultSyncLog(dao)

    private fun event(finishedAt: Long) = SyncEvent(
        repoId = 1, repoName = "Alpha",
        startedAt = finishedAt - 100, finishedAt = finishedAt,
        outcome = SyncEventOutcome.UP_TO_DATE, trigger = SyncTrigger.MANUAL,
    )

    @Test
    fun `recording keeps the entry`() = runTest {
        log.record(event(finishedAt = 1_000_000))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun `recording prunes entries older than thirty days before the new one`() = runTest {
        val now = TimeUnit.DAYS.toMillis(100)
        val day = TimeUnit.DAYS.toMillis(1)
        log.record(event(finishedAt = now - 31 * day))
        log.record(event(finishedAt = now - 29 * day))

        log.record(event(finishedAt = now))

        assertEquals(
            listOf(now - 29 * day, now),
            dao.rows.value.map { it.finishedAt },
        )
    }

    @Test
    fun `the retention is thirty days`() {
        assertEquals(TimeUnit.DAYS.toMillis(30), DefaultSyncLog.RETENTION_MILLIS)
    }
}
