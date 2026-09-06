package de.nereide.strohhalm.ui.settings

import de.nereide.strohhalm.data.SettingsRepository
import de.nereide.strohhalm.data.SyncInterval
import de.nereide.strohhalm.domain.FakeDataStore
import de.nereide.strohhalm.domain.SshKeyStore
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
import java.security.KeyPair

/**
 * The interval control was withheld until something acted on the value. Now
 * that the worker reads it, the settings screen exposes it, and the ViewModel
 * is the seam between the picker and the stored preference.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelIntervalTest {

    private val dispatcher = StandardTestDispatcher()
    private val settings = SettingsRepository(FakeDataStore())

    private val keyStore = object : SshKeyStore {
        override fun hasKey(): Boolean = true
        override suspend fun keyPair(): KeyPair = error("not needed")
        override suspend fun publicKeyLine(): String = "ssh-ed25519 AAAA test"
        override suspend fun regenerate(): KeyPair = error("not needed")
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the ui state carries the stored interval`() = runTest(dispatcher) {
        settings.setSyncInterval(SyncInterval.H6)
        val viewModel = SettingsViewModel(settings, keyStore)

        val state = viewModel.uiState.first { it.syncInterval == SyncInterval.H6 }

        assertEquals(SyncInterval.H6, state.syncInterval)
    }

    @Test
    fun `choosing an interval persists it`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(settings, keyStore)

        viewModel.setSyncInterval(SyncInterval.MANUAL)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(SyncInterval.MANUAL, settings.syncInterval.first())
    }
}
