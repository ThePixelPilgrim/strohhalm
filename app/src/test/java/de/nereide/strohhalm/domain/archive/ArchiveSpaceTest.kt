package de.nereide.strohhalm.domain.archive

import de.nereide.strohhalm.domain.SyncErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSpaceTest {

    private val tenMiB = 10L * 1024 * 1024

    @Test
    fun `the requirement is the mirror plus five percent plus a floor`() {
        assertEquals(100L + 5 + tenMiB, ArchiveSpace.required(100L))
        assertEquals(2000L + 100 + tenMiB, ArchiveSpace.required(2000L))
    }

    /** A tiny repository must not be judged against a percentage of nearly nothing. */
    @Test
    fun `an empty mirror still demands the floor`() {
        assertEquals(tenMiB, ArchiveSpace.required(0L))
    }

    @Test
    fun `enough space is no error`() {
        assertNull(ArchiveSpace.check(mirrorBytes = 1_000, allocatableBytes = 1_000_000_000))
    }

    @Test
    fun `too little space is reported as low storage, not as a failure to write`() {
        val error = ArchiveSpace.check(mirrorBytes = 50L * 1024 * 1024, allocatableBytes = 1_000)
        assertNotNull(error)
        assertEquals(SyncErrorCode.LOW_STORAGE, error!!.code)
    }

    @Test
    fun `the message names both figures so the gap is visible`() {
        val error = ArchiveSpace.check(mirrorBytes = 50L * 1024 * 1024, allocatableBytes = 1_024)!!
        assertTrue(error.detail!!.contains("1024"))
        assertTrue(error.detail!!.contains(ArchiveSpace.required(50L * 1024 * 1024).toString()))
    }

    @Test
    fun `exactly enough is enough`() {
        val need = ArchiveSpace.required(1_000)
        assertNull(ArchiveSpace.check(mirrorBytes = 1_000, allocatableBytes = need))
    }
}
