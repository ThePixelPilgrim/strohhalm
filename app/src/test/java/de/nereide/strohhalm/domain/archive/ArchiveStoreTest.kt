package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveStoreTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var store: ArchiveStore
    private lateinit var cache: File

    private val refs = mapOf("refs/heads/main" to "a".repeat(64))
    private val fingerprint get() = RefFingerprint.of(refs)
    private val synced = 1_785_060_000_000L

    private fun mirror(): File = temp.newFolder("yamiro.git").apply {
        File(this, "HEAD").writeText("ref: refs/heads/main\n")
        File(this, "packed-refs").writeText("${"a".repeat(64)} refs/heads/main\n")
    }

    private fun store(): ArchiveStore {
        cache = temp.newFolder("archives")
        return ArchiveStore(cache)
    }

    @Test
    fun `nothing is cached before the first build`() {
        store = store()
        assertNull(store.existing("yamiro", fingerprint))
    }

    @Test
    fun `a build produces an archive and a sidecar carrying both checksums`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)

        assertTrue(archive.isFile)
        assertEquals("yamiro-2026-07-26-${fingerprint.take(12)}.zip", archive.name)

        val sidecar = File(cache, ArchiveNames.sidecar(archive.name))
        val parsed = Sidecar.parse(sidecar.readText())
        assertNotNull(parsed)
        assertEquals(fingerprint, parsed!!.refFingerprint)
        assertEquals(archive.name, parsed.archiveName)

        val actual = java.security.MessageDigest.getInstance("SHA-256")
            .digest(archive.readBytes()).toHex()
        assertEquals(actual, parsed.archiveSha256)
    }

    /** The sidecar must stay readable by ordinary tools. */
    @Test
    fun `the sidecar is sha256sum's format with the fingerprint as a comment`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)
        val lines = File(cache, ArchiveNames.sidecar(archive.name)).readLines()

        assertEquals("# refs $fingerprint", lines[0])
        assertTrue(lines[1].endsWith("  ${archive.name}"))
        assertEquals(64, lines[1].substringBefore(' ').length)
    }

    @Test
    fun `a finished archive is found again and not rebuilt`() {
        store = store()
        val first = store.build("yamiro", mirror(), fingerprint, synced, null)
        val stamp = first.lastModified()

        val found = store.existing("yamiro", fingerprint)
        assertEquals(first, found)
        assertEquals(stamp, found!!.lastModified())
    }

    /** The date must not participate: a no-op sync moves it and nothing else. */
    @Test
    fun `a later sync date still finds the same archive`() {
        store = store()
        val first = store.build("yamiro", mirror(), fingerprint, synced, null)
        assertEquals(first, store.existing("yamiro", fingerprint))
    }

    @Test
    fun `a tampered archive is not offered`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)
        archive.appendBytes(byteArrayOf(0))

        assertNull(store.existing("yamiro", fingerprint))
    }

    @Test
    fun `an archive with no sidecar is not trusted`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)
        File(cache, ArchiveNames.sidecar(archive.name)).delete()

        assertNull(store.existing("yamiro", fingerprint))
    }

    /** The filename is an index; the sidecar decides. */
    @Test
    fun `a sidecar naming a different ref state is not trusted`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)
        val sidecar = File(cache, ArchiveNames.sidecar(archive.name))
        sidecar.writeText(sidecar.readText().replace("# refs $fingerprint", "# refs ${"f".repeat(64)}"))

        assertNull(store.existing("yamiro", fingerprint))
    }

    @Test
    fun `moved refs mean a different archive, and the old one is pruned`() {
        store = store()
        val old = store.build("yamiro", mirror(), fingerprint, synced, null)

        val moved = RefFingerprint.of(mapOf("refs/heads/main" to "b".repeat(64)))
        assertNull(store.existing("yamiro", moved))

        assertEquals(1, store.prune("yamiro", moved))
        assertFalse(old.exists())
        assertFalse(File(cache, ArchiveNames.sidecar(old.name)).exists())
    }

    @Test
    fun `pruning with an unchanged fingerprint removes nothing`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)

        assertEquals(0, store.prune("yamiro", fingerprint))
        assertTrue(archive.exists())
    }

    /** What a severe memory trim does: everything goes, current included. */
    @Test
    fun `pruning with no current fingerprint removes everything for the slug`() {
        store = store()
        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)

        assertEquals(1, store.prune("yamiro", null))
        assertFalse(archive.exists())
    }

    @Test
    fun `pruning leaves other repositories alone`() {
        store = store()
        val mine = store.build("yamiro", mirror(), fingerprint, synced, null)

        assertEquals(0, store.prune("other", null))
        assertTrue(mine.exists())
    }

    /**
     * "notes-2" is a second repository, not a stale archive of "notes". A raw
     * prefix test claims its files; anchoring is what keeps them apart.
     */
    @Test
    fun `pruning leaves alone a repository whose slug extends this one`() {
        store = store()
        val mine = store.build("notes", mirror(), fingerprint, synced, null)

        val sibling = File(cache, "notes-2-2026-07-26-${"b".repeat(12)}.zip")
        sibling.writeBytes(ByteArray(8))
        val siblingSidecar = File(cache, ArchiveNames.sidecar(sibling.name))
        siblingSidecar.writeText("# refs ${"b".repeat(64)}\n${"c".repeat(64)}  ${sibling.name}\n")

        val moved = RefFingerprint.of(mapOf("refs/heads/main" to "b".repeat(64)))
        assertEquals(1, store.prune("notes", moved))
        assertFalse(mine.exists())
        assertTrue(sibling.exists())
        assertTrue(siblingSidecar.exists())
    }

    /**
     * The hazard, reproduced synchronously: maintenance prunes while a build is
     * packing. On POSIX the unlink does not stop the writer — the bytes keep
     * flowing to an anonymous inode, and the finalising rename then finds no
     * source at all. The archiver here holds its output stream open across the
     * prune, exactly as the real packer's `FileOutputStream` is.
     */
    @Test
    fun `pruning does not delete the part file a build is still writing`() {
        cache = temp.newFolder("archives")
        lateinit var underTest: ArchiveStore
        val reentrant = object : MirrorArchiver {
            override fun archive(gitDir: File, target: File, progress: ArchiveProgress?): ArchiveResult {
                val bytes = ByteArray(32) { 7 }
                java.io.FileOutputStream(target).use { out ->
                    underTest.prune("yamiro", null)
                    out.write(bytes)
                }
                return ArchiveResult(
                    sha256 = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
                    bytes = bytes.size.toLong(),
                    entries = 1,
                )
            }
        }
        underTest = ArchiveStore(cache, reentrant)
        store = underTest

        val archive = store.build("yamiro", mirror(), fingerprint, synced, null)

        assertTrue(archive.isFile)
        assertEquals(archive, store.existing("yamiro", fingerprint))
    }

    /** A part file no build is writing is dead weight; prune must still clear it. */
    @Test
    fun `pruning still deletes a stale part file from an earlier run`() {
        store = store()
        val stale = File(cache, ArchiveNames.part("yamiro-2026-07-25-${"a".repeat(12)}.zip"))
        stale.writeBytes(ByteArray(8))

        store.prune("yamiro", null)

        assertFalse(stale.exists())
    }

    /** A cancelled build must leave nothing that could later be mistaken for done. */
    @Test
    fun `a failed build leaves no part file behind`() {
        cache = temp.newFolder("archives")
        val exploding = object : MirrorArchiver {
            override fun archive(gitDir: File, target: File, progress: ArchiveProgress?): ArchiveResult {
                target.writeBytes(ByteArray(16))
                throw InterruptedException("cancelled")
            }
        }
        store = ArchiveStore(cache, exploding)

        try {
            store.build("yamiro", mirror(), fingerprint, synced, null)
        } catch (expected: InterruptedException) {
            // the point of the test
        }

        assertEquals(emptyList<String>(), cache.list()!!.toList())
    }
}
