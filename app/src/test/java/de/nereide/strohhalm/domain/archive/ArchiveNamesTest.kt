package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

class ArchiveNamesTest {

    private val utc = ZoneId.of("UTC")
    private val fingerprint = "4f2a91c07b3e59d0a8c1e77b0f3d2a6c9e5b18f4c07a3e2d1b6f8095c4a7d3e21"

    /** 2026-07-26T10:00:00Z */
    private val synced = 1_785_060_000_000L

    @Test
    fun `an archive is named slug, date and the fingerprint prefix`() {
        assertEquals(
            "yamiro-2026-07-26-4f2a91c07b3e.zip",
            ArchiveNames.archive("yamiro", synced, fingerprint, utc),
        )
    }

    @Test
    fun `the sidecar and part files hang off the archive name`() {
        val name = ArchiveNames.archive("yamiro", synced, fingerprint, utc)
        assertEquals("yamiro-2026-07-26-4f2a91c07b3e.zip.sha256", ArchiveNames.sidecar(name))
        assertEquals("yamiro-2026-07-26-4f2a91c07b3e.zip.part", ArchiveNames.part(name))
    }

    /**
     * Lookup matches on slug and fingerprint only. The date must be ignored:
     * lastSyncAt advances on every successful sync, including the common one
     * where nothing moved, so a date in the key would rebuild daily.
     */
    @Test
    fun `matching ignores the date`() {
        assertTrue(ArchiveNames.matches("yamiro-2026-07-26-4f2a91c07b3e.zip", "yamiro", fingerprint))
        assertTrue(ArchiveNames.matches("yamiro-2019-01-01-4f2a91c07b3e.zip", "yamiro", fingerprint))
    }

    @Test
    fun `matching rejects another repository or another fingerprint`() {
        assertFalse(ArchiveNames.matches("other-2026-07-26-4f2a91c07b3e.zip", "yamiro", fingerprint))
        assertFalse(ArchiveNames.matches("yamiro-2026-07-26-000000000000.zip", "yamiro", fingerprint))
        assertFalse(ArchiveNames.matches("yamiro-2026-07-26-4f2a91c07b3e.zip.part", "yamiro", fingerprint))
        assertFalse(ArchiveNames.matches("yamiro-2026-07-26-4f2a91c07b3e.zip.sha256", "yamiro", fingerprint))
    }

    /** A slug containing digits and dashes must not confuse the parser. */
    @Test
    fun `a dashed slug survives round-tripping`() {
        val name = ArchiveNames.archive("my-notes-2", synced, fingerprint, utc)
        assertEquals("my-notes-2-2026-07-26-4f2a91c07b3e.zip", name)
        assertTrue(ArchiveNames.matches(name, "my-notes-2", fingerprint))
        assertFalse(ArchiveNames.matches(name, "my-notes", fingerprint))
    }

    /** Ownership covers all three files a build can leave behind. */
    @Test
    fun `an archive, its sidecar and its part file all belong to the slug`() {
        val name = ArchiveNames.archive("notes", synced, fingerprint, utc)
        assertTrue(ArchiveNames.belongsTo(name, "notes"))
        assertTrue(ArchiveNames.belongsTo(ArchiveNames.sidecar(name), "notes"))
        assertTrue(ArchiveNames.belongsTo(ArchiveNames.part(name), "notes"))
    }

    /**
     * The case pruning got wrong: "notes-2" is a different repository, and none
     * of its files may be claimed by "notes".
     */
    @Test
    fun `a slug that extends this one keeps its own files`() {
        val sibling = ArchiveNames.archive("notes-2", synced, fingerprint, utc)
        assertFalse(ArchiveNames.belongsTo(sibling, "notes"))
        assertFalse(ArchiveNames.belongsTo(ArchiveNames.sidecar(sibling), "notes"))
        assertFalse(ArchiveNames.belongsTo(ArchiveNames.part(sibling), "notes"))
        assertTrue(ArchiveNames.belongsTo(sibling, "notes-2"))
    }

    @Test
    fun `a name that is not ours belongs to nothing`() {
        assertFalse(ArchiveNames.belongsTo("other-2026-07-26-4f2a91c07b3e.zip", "notes"))
        assertFalse(ArchiveNames.belongsTo("notes.zip", "notes"))
        assertFalse(ArchiveNames.belongsTo("notes-2026-07-26.zip", "notes"))
        assertFalse(ArchiveNames.belongsTo("notes-2026-07-26-zzzzzzzzzzzz.zip", "notes"))
        assertFalse(ArchiveNames.belongsTo("notes-2026-07-26-4f2a91c07b3e.tar", "notes"))
        assertFalse(ArchiveNames.belongsTo("README.txt", "notes"))
    }

    /**
     * The mirror directory carries the collision-resolved slug, which is the
     * only name that is unique across repositories.
     */
    @Test
    fun `the slug comes from the mirror directory`() {
        assertEquals("notes", ArchiveNames.slugForMirror(File("/storage/mirrors/notes.git")))
        assertEquals("notes-2", ArchiveNames.slugForMirror(File("/storage/mirrors/notes-2.git")))
    }

    /** The recipient sees no hash: that is the point of the provider override. */
    @Test
    fun `the display name drops the fingerprint`() {
        assertEquals(
            "yamiro-2026-07-26.zip",
            ArchiveNames.displayName("yamiro-2026-07-26-4f2a91c07b3e.zip"),
        )
    }

    @Test
    fun `an unrecognised name is its own display name`() {
        assertEquals("something.zip", ArchiveNames.displayName("something.zip"))
    }

    @Test
    fun `a repository never synced is dated from the epoch rather than crashing`() {
        assertEquals(
            "yamiro-1970-01-01-4f2a91c07b3e.zip",
            ArchiveNames.archive("yamiro", 0L, fingerprint, utc),
        )
    }
}
