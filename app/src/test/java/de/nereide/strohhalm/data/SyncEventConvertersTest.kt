package de.nereide.strohhalm.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A downgrade that removes an enum constant must not crash the Activity
 * screen; the converters fall back the way SyncStatusConverter does.
 */
class SyncEventConvertersTest {

    private val converters = SyncEventConverters()

    @Test
    fun `outcome round-trips`() {
        assertEquals(
            SyncEventOutcome.RECEIVED,
            converters.toOutcome(converters.fromOutcome(SyncEventOutcome.RECEIVED)),
        )
    }

    @Test
    fun `an unknown outcome falls back to FAILED`() {
        assertEquals(SyncEventOutcome.FAILED, converters.toOutcome("EXPLODED"))
    }

    @Test
    fun `trigger round-trips`() {
        assertEquals(
            SyncTrigger.SCHEDULED,
            converters.toTrigger(converters.fromTrigger(SyncTrigger.SCHEDULED)),
        )
    }

    @Test
    fun `an unknown trigger falls back to MANUAL`() {
        assertEquals(SyncTrigger.MANUAL, converters.toTrigger("CRON"))
    }
}
