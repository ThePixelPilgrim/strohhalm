package de.nereide.strohhalm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val nonce = "a3f9c2e1b4d78056"
    private val documentId = "primary:Strohhalm"
    private val stamp = "2026-07-25T18:45:00Z"

    @Test
    fun `writes a file carrying the marker, nonce and derived path`() {
        val dir = tmp.newFolder("Strohhalm")

        val report = StorageProbe.write(dir, documentId, nonce, stamp).getOrThrow()

        val onDisk = File(dir, StorageProbe.FILE_NAME).readText()
        assertTrue(onDisk.contains(StorageProbe.MARKER))
        assertTrue(onDisk.contains("nonce=$nonce"))
        assertTrue(onDisk.contains("documentId=$documentId"))
        assertTrue(onDisk.contains("derivedPath=${dir.absolutePath}"))
        assertEquals(onDisk, report.content)
    }

    @Test
    fun `records the path it actually wrote to, not the one it was asked about`() {
        // The whole point of the probe: the file must self-describe its own
        // location, so an independent look at the filesystem can contradict it.
        val dir = tmp.newFolder("Elsewhere")

        val report = StorageProbe.write(dir, documentId, nonce, stamp).getOrThrow()

        assertEquals(dir.absolutePath, report.derivedPath)
        assertEquals(File(dir, StorageProbe.FILE_NAME).absolutePath, report.filePath)
    }

    @Test
    fun `content round-trips - what is read back equals what was written`() {
        val dir = tmp.newFolder("Strohhalm")

        val report = StorageProbe.write(dir, documentId, nonce, stamp).getOrThrow()

        assertEquals(File(dir, StorageProbe.FILE_NAME).readText(), report.content)
    }

    @Test
    fun `creates the directory when it does not exist yet`() {
        val dir = File(tmp.root, "NotYetThere")

        val report = StorageProbe.write(dir, documentId, nonce, stamp).getOrThrow()

        assertTrue(dir.isDirectory)
        assertTrue(File(report.filePath).isFile)
    }

    @Test
    fun `fails rather than throws when the location cannot be written`() {
        // A file where a directory is expected: mkdirs cannot succeed.
        val blocker = tmp.newFile("blocker")

        val result = StorageProbe.write(blocker, documentId, nonce, stamp)

        assertTrue("expected failure, got $result", result.isFailure)
    }

    @Test
    fun `the rendered content is greppable line by line`() {
        val text = StorageProbe.render(nonce, documentId, "/storage/emulated/0/Strohhalm", stamp)
        val fields = text.lineSequence()
            .filter { it.contains("=") }
            .associate { it.substringBefore("=") to it.substringAfter("=") }

        assertEquals(nonce, fields["nonce"])
        assertEquals(documentId, fields["documentId"])
        assertEquals("/storage/emulated/0/Strohhalm", fields["derivedPath"])
        assertEquals(stamp, fields["writtenAt"])
    }
}
