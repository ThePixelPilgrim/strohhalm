package de.nereide.strohhalm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageRootResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val primary = File("/storage/emulated/0")
    private val sdCard = File("/storage/1A2B-3C4D")
    private val lookup: (String) -> File? = { id -> sdCard.takeIf { id == "1A2B-3C4D" } }

    @Test
    fun `a primary volume document id resolves under external storage`() {
        assertEquals(
            File("/storage/emulated/0/Strohhalm"),
            StorageRootResolver.resolve("primary:Strohhalm", primary, lookup)
        )
    }

    @Test
    fun `nested paths are preserved`() {
        assertEquals(
            File("/storage/emulated/0/Backups/git"),
            StorageRootResolver.resolve("primary:Backups/git", primary, lookup)
        )
    }

    @Test
    fun `an empty relative path resolves to the volume root`() {
        assertEquals(primary, StorageRootResolver.resolve("primary:", primary, lookup))
    }

    @Test
    fun `a removable volume is resolved through the lookup`() {
        assertEquals(
            File("/storage/1A2B-3C4D/Strohhalm"),
            StorageRootResolver.resolve("1A2B-3C4D:Strohhalm", primary, lookup)
        )
    }

    @Test
    fun `an unknown volume yields null so the caller can fall back`() {
        assertNull(StorageRootResolver.resolve("XXXX-YYYY:Strohhalm", primary, lookup))
    }

    @Test
    fun `a document id without a volume separator yields null`() {
        assertNull(StorageRootResolver.resolve("Strohhalm", primary, lookup))
    }

    @Test
    fun `isWritable is true for a real directory`() {
        assertTrue(StorageRootResolver.isWritable(tmp.newFolder("writable")))
    }

    @Test
    fun `isWritable is false for a path that does not exist`() {
        assertFalse(StorageRootResolver.isWritable(File(tmp.root, "absent")))
    }

    @Test
    fun `isWritable leaves nothing behind`() {
        val dir = tmp.newFolder("clean")
        StorageRootResolver.isWritable(dir)
        assertEquals(0, dir.listFiles()!!.size)
    }
}
