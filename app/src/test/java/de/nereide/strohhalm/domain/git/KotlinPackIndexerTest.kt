package de.nereide.strohhalm.domain.git

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class KotlinPackIndexerTest {

    @get:Rule val temp = TemporaryFolder()

    /**
     * Builds a real pack with JGit and returns its bytes.
     *
     * JGit is allowed in tests as a fixture builder; it is only unusable as the
     * engine because it cannot read SHA-256.
     */
    private fun sha1PackBytes(fileCount: Int): ByteArray {
        val work = temp.newFolder("work")
        Git.init().setDirectory(work).call().use { git ->
            repeat(fileCount) { i ->
                File(work, "file$i.txt").writeText("content $i\n".repeat(i + 1))
                git.add().addFilepattern("file$i.txt").call()
                git.commit().setMessage("commit $i").setSign(false).call()
            }
            val packDir = temp.newFolder("packout")
            git.repository.newObjectReader().use { reader ->
                org.eclipse.jgit.internal.storage.pack.PackWriter(git.repository).use { writer ->
                    // preparePack takes Sets, not Lists — hence the toSet().
                    val refs = git.repository.refDatabase.refs.map { it.objectId }.toSet()
                    writer.preparePack(org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE, refs, emptySet())
                    val out = File(packDir, "out.pack")
                    out.outputStream().use { writer.writePack(
                        org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE,
                        org.eclipse.jgit.lib.NullProgressMonitor.INSTANCE,
                        it,
                    ) }
                    return out.readBytes()
                }
            }
        }
    }

    @Test
    fun `a pack produces a pack file and an index named after its checksum`() {
        val objects = temp.newFolder("objects")
        val bytes = sha1PackBytes(fileCount = 3)

        val result = KotlinPackIndexer()
            .consume(ByteArrayInputStream(bytes), ObjectHash.SHA1, objects, null)

        val packFile = File(objects, "pack/${result.packName}.pack")
        val idxFile = File(objects, "pack/${result.packName}.idx")
        assertTrue("pack written", packFile.isFile)
        assertTrue("index written", idxFile.isFile)
        assertEquals(bytes.size.toLong(), packFile.length())
        assertTrue("some objects indexed", result.objectCount > 0)
    }

    @Test
    fun `deltas are resolved, so every object in the pack is indexed`() {
        val objects = temp.newFolder("objects")
        // Repeated similar content is what makes JGit emit deltas at all.
        val bytes = sha1PackBytes(fileCount = 8)

        val result = KotlinPackIndexer()
            .consume(ByteArrayInputStream(bytes), ObjectHash.SHA1, objects, null)

        // Header object count and indexed count must agree, which is only true
        // if every delta resolved.
        val declared = ((bytes[8].toInt() and 0xff) shl 24) or
            ((bytes[9].toInt() and 0xff) shl 16) or
            ((bytes[10].toInt() and 0xff) shl 8) or
            (bytes[11].toInt() and 0xff)
        assertEquals(declared, result.objectCount)
    }

    @Test
    fun `a corrupted trailer is refused`() {
        val objects = temp.newFolder("objects")
        val bytes = sha1PackBytes(fileCount = 2)
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()

        assertThrows(IOException::class.java) {
            KotlinPackIndexer().consume(ByteArrayInputStream(bytes), ObjectHash.SHA1, objects, null)
        }
    }

    @Test
    fun `a stream that is not a pack is refused`() {
        val objects = temp.newFolder("objects")
        assertThrows(IOException::class.java) {
            KotlinPackIndexer().consume(
                ByteArrayInputStream("NOTAPACK".toByteArray()),
                ObjectHash.SHA1,
                objects,
                null,
            )
        }
    }

    /**
     * Cancelling a sync interrupts the mirror thread ([runInterruptible] in
     * `ProtocolMirror`), but indexing is CPU and file work that no interrupt
     * can unwind on its own — the indexer must poll the flag, or Stop would do
     * nothing until the whole 72k-object resolve completed.
     */
    @Test
    fun `an interrupted thread stops the indexer instead of finishing the pack`() {
        val objects = temp.newFolder("objects")
        val bytes = sha1PackBytes(fileCount = 2)

        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedException::class.java) {
                KotlinPackIndexer().consume(ByteArrayInputStream(bytes), ObjectHash.SHA1, objects, null)
            }
        } finally {
            Thread.interrupted() // clear the flag so later tests are unaffected
        }
    }
}
