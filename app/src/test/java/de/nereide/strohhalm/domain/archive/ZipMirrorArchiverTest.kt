package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class ZipMirrorArchiverTest {

    @get:Rule val temp = TemporaryFolder()

    private val archiver = ZipMirrorArchiver()

    /** A directory shaped like a bare mirror, including a fake pack. */
    private fun mirror(): File = temp.newFolder("yamiro.git").apply {
        File(this, "HEAD").writeText("ref: refs/heads/main\n")
        File(this, "config").writeText("[core]\n\tbare = true\n")
        File(this, "packed-refs").writeText("a".repeat(64) + " refs/heads/main\n")
        File(this, "objects/pack").mkdirs()
        File(this, "objects/pack/pack-abc.pack").writeBytes(ByteArray(4096) { (it % 251).toByte() })
        File(this, "objects/pack/pack-abc.idx").writeBytes(ByteArray(512) { (it % 127).toByte() })
    }

    @Test
    fun `every file is present under a single top-level directory`() {
        val target = File(temp.root, "out.zip")
        val result = archiver.archive(mirror(), target, null)

        ZipFile(target).use { zip ->
            val names = zip.entries().toList().map { it.name }.sorted()
            assertEquals(
                listOf(
                    "yamiro.git/HEAD",
                    "yamiro.git/config",
                    "yamiro.git/objects/pack/pack-abc.idx",
                    "yamiro.git/objects/pack/pack-abc.pack",
                    "yamiro.git/packed-refs",
                ),
                names,
            )
            assertEquals(5, result.entries)
        }
    }

    @Test
    fun `contents round-trip byte for byte`() {
        val source = mirror()
        val target = File(temp.root, "out.zip")
        archiver.archive(source, target, null)

        ZipFile(target).use { zip ->
            val pack = zip.getEntry("yamiro.git/objects/pack/pack-abc.pack")
            assertNotNull(pack)
            assertArrayEquals(
                File(source, "objects/pack/pack-abc.pack").readBytes(),
                zip.getInputStream(pack).readBytes(),
            )
            assertEquals(
                "ref: refs/heads/main\n",
                zip.getInputStream(zip.getEntry("yamiro.git/HEAD")).readBytes().decodeToString(),
            )
        }
    }

    /**
     * The checksum must identify the content, not the moment of building —
     * otherwise a cached archive could never be matched against its sidecar.
     */
    @Test
    fun `the same input produces the same bytes and the same checksum`() {
        val source = mirror()
        val first = File(temp.root, "a.zip")
        val second = File(temp.root, "b.zip")

        val one = archiver.archive(source, first, null)
        val two = archiver.archive(source, second, null)

        assertEquals(one.sha256, two.sha256)
        assertArrayEquals(first.readBytes(), second.readBytes())
    }

    @Test
    fun `the reported checksum is the checksum of the file on disk`() {
        val target = File(temp.root, "out.zip")
        val result = archiver.archive(mirror(), target, null)

        val actual = MessageDigest.getInstance("SHA-256").digest(target.readBytes()).toHex()
        assertEquals(actual, result.sha256)
        assertEquals(target.length(), result.bytes)
    }

    @Test
    fun `progress counts every entry`() {
        val seen = mutableListOf<Pair<Int, Int>>()
        archiver.archive(mirror(), File(temp.root, "out.zip")) { _, completed, total ->
            seen += completed to total
        }
        assertEquals(5, seen.size)
        assertEquals(5 to 5, seen.last())
    }

    /** Cancellation is a thread interrupt, exactly as in the mirror engine. */
    @Test
    fun `an interrupted archive throws and leaves no complete file behind`() {
        val target = File(temp.root, "out.zip")
        var thrown: Throwable? = null

        val worker = Thread {
            try {
                archiver.archive(mirror(), target) { _, _, _ ->
                    Thread.currentThread().interrupt()
                }
            } catch (t: Throwable) {
                thrown = t
            }
        }
        worker.start()
        worker.join()

        assertTrue("expected InterruptedException, got $thrown", thrown is InterruptedException)
        assertFalse("a cancelled build must not leave its output", target.exists())
    }

    /** Packs are already deflated; re-deflating them is pure cost. */
    @Test
    fun `pack files are stored rather than compressed`() {
        val target = File(temp.root, "out.zip")
        archiver.archive(mirror(), target, null)

        ZipFile(target).use { zip ->
            val pack = zip.getEntry("yamiro.git/objects/pack/pack-abc.pack")
            assertEquals(pack.size, pack.compressedSize)
        }
    }
}
