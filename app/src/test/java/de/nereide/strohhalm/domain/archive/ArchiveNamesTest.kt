package de.nereide.strohhalm.domain.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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
