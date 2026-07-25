package de.nereide.strohhalm.data

import de.nereide.strohhalm.domain.FakeDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsRepositoryTest {

    private val settings = SettingsRepository(FakeDataStore())

    @Test
    fun `defaults to hourly syncing`() = runTest {
        assertEquals(SyncInterval.H1, settings.syncInterval.first())
    }

    @Test
    fun `the interval round-trips`() = runTest {
        settings.setSyncInterval(SyncInterval.D1)
        assertEquals(SyncInterval.D1, settings.syncInterval.first())
    }

    @Test
    fun `an unrecognised stored interval falls back to the default`() = runTest {
        settings.setSyncIntervalRaw("H99")
        assertEquals(SyncInterval.H1, settings.syncInterval.first())
    }

    @Test
    fun `the storage root is unset until chosen`() = runTest {
        assertNull(settings.storageRoot.first())
    }

    @Test
    fun `the storage root round-trips`() = runTest {
        settings.setStorageRoot(File("/storage/emulated/0/Strohhalm"))
        assertEquals("/storage/emulated/0/Strohhalm", settings.storageRoot.first())
    }

    @Test
    fun `requireStorageRoot fails loudly when none is configured`() = runTest {
        val thrown = runCatching { settings.requireStorageRoot() }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
    }

    @Test
    fun `failure notifications are on by default and can be turned off`() = runTest {
        assertTrue(settings.notifyOnFailure.first())

        settings.setNotifyOnFailure(false)
        assertEquals(false, settings.notifyOnFailure.first())
    }
}
