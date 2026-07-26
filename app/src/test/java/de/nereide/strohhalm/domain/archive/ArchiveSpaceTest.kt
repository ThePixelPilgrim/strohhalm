package de.nereide.strohhalm.domain.archive

import de.nereide.strohhalm.domain.SyncErrorCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveSpaceTest {

    private val tenMiB = 10L * 1024 * 1024

    /**
     * Records what was asked for, so a test can prove the reservation happened
     * and was for the right figure — the whole point of the defect this pins.
     */
    private class FakeCacheSpace(
        private val allocatable: Long,
        private val granted: Boolean,
    ) : CacheSpace {
        var reserved: Long? = null
        override suspend fun allocatable(): Long = allocatable
        override suspend fun reserve(bytes: Long): Boolean {
            reserved = bytes
            return granted
        }
    }

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

    @Test
    fun `a short allocatable figure is refused before anything is reserved`() = runTest {
        val space = FakeCacheSpace(allocatable = 1_000, granted = true)
        val error = ArchiveSpace.reserveOrRefuse(space, mirrorBytes = 50L * 1024 * 1024)
        assertNotNull(error)
        assertEquals(SyncErrorCode.LOW_STORAGE, error!!.code)
        assertNull("nothing should be reserved once the check refuses", space.reserved)
    }

    /**
     * The defect: `getAllocatableBytes` counts other apps' clearable caches, and
     * that space is only genuinely freed by `allocateBytes`. A refused
     * reservation must therefore refuse the build, not let it hit ENOSPC.
     */
    @Test
    fun `a refused reservation is low storage, not a failure to write`() = runTest {
        val space = FakeCacheSpace(allocatable = Long.MAX_VALUE / 2, granted = false)
        val error = ArchiveSpace.reserveOrRefuse(space, mirrorBytes = 1_000)
        assertNotNull(error)
        assertEquals(SyncErrorCode.LOW_STORAGE, error!!.code)
    }

    @Test
    fun `a granted reservation allows the build`() = runTest {
        val space = FakeCacheSpace(allocatable = Long.MAX_VALUE / 2, granted = true)
        assertNull(ArchiveSpace.reserveOrRefuse(space, mirrorBytes = 1_000))
    }

    @Test
    fun `the reservation asks for exactly what the archive requires`() = runTest {
        val space = FakeCacheSpace(allocatable = Long.MAX_VALUE / 2, granted = true)
        ArchiveSpace.reserveOrRefuse(space, mirrorBytes = 2_000)
        assertEquals(ArchiveSpace.required(2_000), space.reserved)
    }
}
