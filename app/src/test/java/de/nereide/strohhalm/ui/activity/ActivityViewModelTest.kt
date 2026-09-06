package de.nereide.strohhalm.ui.activity

import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventOutcome
import de.nereide.strohhalm.data.SyncTrigger
import de.nereide.strohhalm.domain.FakeSyncEventDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val dao = FakeSyncEventDao()
    private val now = TimeUnit.DAYS.toMillis(100)

    private fun event(name: String, outcome: SyncEventOutcome, startedAt: Long) = SyncEvent(
        repoId = 1, repoName = name, startedAt = startedAt, finishedAt = startedAt + 10,
        outcome = outcome, trigger = SyncTrigger.SCHEDULED,
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opens showing only entries that received data, newest first`() = runTest(dispatcher) {
        dao.insert(event("older", SyncEventOutcome.RECEIVED, now - 2_000))
        dao.insert(event("clean", SyncEventOutcome.UP_TO_DATE, now - 1_500))
        dao.insert(event("newer", SyncEventOutcome.RECEIVED, now - 1_000))
        val viewModel = ActivityViewModel(dao, clock = { now })

        val shown = viewModel.events.first { it.isNotEmpty() }

        assertEquals(listOf("newer", "older"), shown.map { it.repoName })
    }

    @Test
    fun `turning the filter off shows everything`() = runTest(dispatcher) {
        dao.insert(event("clean", SyncEventOutcome.UP_TO_DATE, now - 1_500))
        dao.insert(event("failed", SyncEventOutcome.FAILED, now - 1_000))
        val viewModel = ActivityViewModel(dao, clock = { now })

        viewModel.setReceivedOnly(false)
        val shown = viewModel.events.first { it.isNotEmpty() }

        assertEquals(listOf("failed", "clean"), shown.map { it.repoName })
    }

    @Test
    fun `entries older than thirty days are not shown even if not yet pruned`() = runTest(dispatcher) {
        dao.insert(event("ancient", SyncEventOutcome.RECEIVED, now - TimeUnit.DAYS.toMillis(31)))
        dao.insert(event("recent", SyncEventOutcome.RECEIVED, now - TimeUnit.DAYS.toMillis(1)))
        val viewModel = ActivityViewModel(dao, clock = { now })

        val shown = viewModel.events.first { it.isNotEmpty() }

        assertEquals(listOf("recent"), shown.map { it.repoName })
    }
}
