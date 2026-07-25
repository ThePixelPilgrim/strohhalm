package de.nereide.strohhalm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class SyncIntervalTest {

    @Test
    fun `manual has no duration`() {
        assertNull(SyncInterval.MANUAL.duration)
        assertNull(SyncInterval.MANUAL.minutes)
    }

    @Test
    fun `every scheduled value maps to the duration its name promises`() {
        assertEquals(Duration.ofMinutes(15), SyncInterval.M15.duration)
        assertEquals(Duration.ofMinutes(30), SyncInterval.M30.duration)
        assertEquals(Duration.ofHours(1), SyncInterval.H1.duration)
        assertEquals(Duration.ofHours(3), SyncInterval.H3.duration)
        assertEquals(Duration.ofHours(6), SyncInterval.H6.duration)
        assertEquals(Duration.ofHours(12), SyncInterval.H12.duration)
        assertEquals(Duration.ofDays(1), SyncInterval.D1.duration)
    }

    @Test
    fun `no scheduled value can violate WorkManagers 15 minute floor`() {
        val offenders = SyncInterval.entries
            .mapNotNull { it.minutes?.let { m -> it.name to m } }
            .filter { (_, minutes) -> minutes < 15 }

        assertTrue("intervals below the periodic floor: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the set of values is exactly the one the spec fixes`() {
        assertEquals(
            listOf("MANUAL", "M15", "M30", "H1", "H3", "H6", "H12", "D1"),
            SyncInterval.entries.map { it.name }
        )
    }
}
