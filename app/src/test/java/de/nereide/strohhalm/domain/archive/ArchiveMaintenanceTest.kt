package de.nereide.strohhalm.domain.archive

import de.nereide.strohhalm.ArchiveMaintenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveMaintenanceTest {

    @get:Rule val temp = TemporaryFolder()

    private val refs = mapOf("refs/heads/main" to "a".repeat(64))

    private fun mirrorAt(parent: File): File = File(parent, "yamiro.git").apply {
        mkdirs()
        File(this, "HEAD").writeText("ref: refs/heads/main\n")
        File(this, "packed-refs").writeText("${"a".repeat(64)} refs/heads/main\n")
    }

    @Test
    fun `an archive matching the mirror survives a prune`() = runTest {
        val cache = temp.newFolder("archives")
        val gitDir = mirrorAt(temp.newFolder("mirrors"))
        val store = ArchiveStore(cache)
        val archive = store.build("yamiro", gitDir, RefFingerprint.of(refs), 1_785_060_000_000L, null)

        val maintenance = ArchiveMaintenance(
            store = store,
            mirrors = { listOf("yamiro" to gitDir) },
            scope = this,
            io = StandardTestDispatcher(testScheduler),
        )
        maintenance.pruneStale()

        assertTrue(archive.exists())
    }

    @Test
    fun `an archive whose refs have moved is pruned`() = runTest {
        val cache = temp.newFolder("archives")
        val gitDir = mirrorAt(temp.newFolder("mirrors"))
        val store = ArchiveStore(cache)
        val archive = store.build("yamiro", gitDir, RefFingerprint.of(refs), 1_785_060_000_000L, null)

        // The mirror moves on.
        File(gitDir, "packed-refs").writeText("${"b".repeat(64)} refs/heads/main\n")

        val maintenance = ArchiveMaintenance(
            store = store,
            mirrors = { listOf("yamiro" to gitDir) },
            scope = this,
            io = StandardTestDispatcher(testScheduler),
        )
        maintenance.pruneStale()

        assertFalse(archive.exists())
    }

    /** A severe memory trim gives back even a current archive. */
    @Test
    fun `dropping everything removes a current archive too`() = runTest {
        val cache = temp.newFolder("archives")
        val gitDir = mirrorAt(temp.newFolder("mirrors"))
        val store = ArchiveStore(cache)
        val archive = store.build("yamiro", gitDir, RefFingerprint.of(refs), 1_785_060_000_000L, null)

        val maintenance = ArchiveMaintenance(
            store = store,
            mirrors = { listOf("yamiro" to gitDir) },
            scope = this,
            io = StandardTestDispatcher(testScheduler),
        )
        maintenance.dropEverything()

        assertFalse(archive.exists())
    }

    @Test
    fun `an unreadable mirror does not stop the others being pruned`() = runTest {
        val cache = temp.newFolder("archives")
        val mirrors = temp.newFolder("mirrors")
        val good = mirrorAt(mirrors)
        val store = ArchiveStore(cache)
        val archive = store.build("yamiro", good, RefFingerprint.of(refs), 1_785_060_000_000L, null)
        File(good, "packed-refs").writeText("${"b".repeat(64)} refs/heads/main\n")

        val maintenance = ArchiveMaintenance(
            store = store,
            mirrors = { listOf("gone" to File(mirrors, "absent.git"), "yamiro" to good) },
            scope = this,
            io = StandardTestDispatcher(testScheduler),
        )
        maintenance.pruneStale()

        assertFalse(archive.exists())
    }
}
