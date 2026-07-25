package de.nereide.strohhalm.work

import de.nereide.strohhalm.domain.SyncErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {

    @Test
    fun `each error category gets its own id`() {
        val ids = SyncErrorCode.entries.map { NotificationIds.forError(it) }
        assertEquals("categories must not share an id", ids.size, ids.toSet().size)
    }

    @Test
    fun `the same category always maps to the same id`() {
        assertEquals(
            NotificationIds.forError(SyncErrorCode.AUTH_FAILED),
            NotificationIds.forError(SyncErrorCode.AUTH_FAILED)
        )
    }

    @Test
    fun `no error id collides with the progress notification`() {
        val ids = SyncErrorCode.entries.map { NotificationIds.forError(it) }
        assertFalse(NotificationIds.PROGRESS in ids)
    }

    @Test
    fun `all ids are positive`() {
        val ids = SyncErrorCode.entries.map { NotificationIds.forError(it) } + NotificationIds.PROGRESS
        assertTrue(ids.all { it > 0 })
    }
}
