package de.nereide.strohhalm.work

import de.nereide.strohhalm.data.SyncInterval
import de.nereide.strohhalm.work.ScheduleHealth.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The verdict is what Settings shows. It answers "why is my backup not
 * running" in order of what the user can act on.
 */
class ScheduleHealthTest {

    private val now = TimeUnit.DAYS.toMillis(10)

    private fun health(
        interval: SyncInterval = SyncInterval.H1,
        registered: Boolean = true,
        nextRunAt: Long? = now + TimeUnit.MINUTES.toMillis(30),
        batteryExempt: Boolean = true,
    ) = ScheduleHealth(interval, registered, nextRunAt, batteryExempt)

    @Test
    fun `everything in order is healthy`() {
        assertEquals(Verdict.HEALTHY, health().verdict(now))
    }

    @Test
    fun `manual wins over everything else`() {
        assertEquals(
            Verdict.MANUAL,
            health(interval = SyncInterval.MANUAL, registered = false, batteryExempt = false).verdict(now),
        )
    }

    @Test
    fun `an interval with no registration is reported before battery state`() {
        assertEquals(
            Verdict.NOT_REGISTERED,
            health(registered = false, batteryExempt = false).verdict(now),
        )
    }

    @Test
    fun `no battery exemption is restricted`() {
        assertEquals(Verdict.RESTRICTED, health(batteryExempt = false).verdict(now))
    }

    @Test
    fun `a next run more than five minutes in the past is overdue`() {
        assertEquals(
            Verdict.OVERDUE,
            health(nextRunAt = now - TimeUnit.MINUTES.toMillis(6)).verdict(now),
        )
    }

    @Test
    fun `a next run four minutes in the past is still healthy`() {
        assertEquals(
            Verdict.HEALTHY,
            health(nextRunAt = now - TimeUnit.MINUTES.toMillis(4)).verdict(now),
        )
    }

    @Test
    fun `an unknown next run is not overdue`() {
        assertEquals(Verdict.HEALTHY, health(nextRunAt = null).verdict(now))
    }
}
