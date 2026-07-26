package de.nereide.strohhalm.domain

import de.nereide.strohhalm.domain.MirrorAccess.Owner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock that keeps a fetch and an archive off the same mirror directory.
 *
 * Every assertion here is about a failure mode that is otherwise silent: an
 * archive built across a fetch checksums clean and restores nothing.
 */
class MirrorAccessTest {

    private val access = MirrorAccess()

    @Test
    fun `the lock is free to start with`() {
        assertTrue(access.tryAcquire(Owner.SYNC))
    }

    @Test
    fun `a second acquire fails while the lock is held`() {
        assertTrue(access.tryAcquire(Owner.SYNC))
        assertFalse("a held lock must not be handed out twice", access.tryAcquire(Owner.SYNC))
    }

    @Test
    fun `releasing frees the lock for the next taker`() {
        assertTrue(access.tryAcquire(Owner.ARCHIVE))
        access.release(Owner.ARCHIVE)
        assertTrue("release must actually free it", access.tryAcquire(Owner.ARCHIVE))
    }

    @Test
    fun `releasing without holding does not free somebody else's hold`() {
        assertTrue(access.tryAcquire(Owner.ARCHIVE))

        // A `finally` on a path that never acquired: it must be a no-op, or a
        // sync that lost the race would unlock the archive that beat it.
        access.release(Owner.SYNC)

        assertFalse("the archive still holds it", access.tryAcquire(Owner.SYNC))
    }

    @Test
    fun `a sync and an archive exclude each other in both directions`() {
        assertTrue(access.tryAcquire(Owner.SYNC))
        assertFalse("an archive must not start mid-fetch", access.tryAcquire(Owner.ARCHIVE))
        access.release(Owner.SYNC)

        assertTrue(access.tryAcquire(Owner.ARCHIVE))
        assertFalse("a fetch must not start mid-archive", access.tryAcquire(Owner.SYNC))
    }
}
