package de.nereide.strohhalm.work

import de.nereide.strohhalm.domain.SyncErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPreconditionsTest {

    private val plenty = 10L * 1024 * 1024 * 1024

    @Test
    fun `everything in order yields no error`() {
        assertNull(
            SyncPreconditions.check(
                freeBytes = plenty,
                storageRootExists = true,
                hasStoragePermission = true,
                hasNetwork = true
            )
        )
    }

    @Test
    fun `too little free space is reported`() {
        val error = SyncPreconditions.check(
            freeBytes = 1024,
            storageRootExists = true,
            hasStoragePermission = true,
            hasNetwork = true
        )
        assertEquals(SyncErrorCode.LOW_STORAGE, error?.code)
    }

    @Test
    fun `exactly the minimum is enough`() {
        assertNull(
            SyncPreconditions.check(
                freeBytes = SyncPreconditions.MIN_FREE_BYTES,
                storageRootExists = true,
                hasStoragePermission = true,
                hasNetwork = true
            )
        )
    }

    @Test
    fun `a revoked permission is reported`() {
        val error = SyncPreconditions.check(plenty, storageRootExists = true, hasStoragePermission = false, hasNetwork = true)
        assertEquals(SyncErrorCode.PERMISSION_LOST, error?.code)
    }

    @Test
    fun `a missing storage root is reported as a permission problem`() {
        val error = SyncPreconditions.check(plenty, storageRootExists = false, hasStoragePermission = true, hasNetwork = true)
        assertEquals(SyncErrorCode.PERMISSION_LOST, error?.code)
    }

    @Test
    fun `no network is reported`() {
        val error = SyncPreconditions.check(plenty, storageRootExists = true, hasStoragePermission = true, hasNetwork = false)
        assertEquals(SyncErrorCode.NO_NETWORK, error?.code)
    }

    @Test
    fun `storage is checked before the network so the actionable problem wins`() {
        val error = SyncPreconditions.check(
            freeBytes = 0,
            storageRootExists = true,
            hasStoragePermission = true,
            hasNetwork = false
        )
        assertEquals(SyncErrorCode.LOW_STORAGE, error?.code)
    }

    @Test
    fun `the minimum is the 250 MB the spec fixes`() {
        assertEquals(250L * 1024 * 1024, SyncPreconditions.MIN_FREE_BYTES)
    }
}
