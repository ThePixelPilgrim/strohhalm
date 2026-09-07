package de.nereide.strohhalm.work

import de.nereide.strohhalm.data.SyncInterval
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit

/**
 * What Settings shows about the scheduled sync, as plain values so the
 * verdict is testable without WorkManager or a device.
 *
 * The verdict answers "why is my backup not running" in order of what the
 * user can act on: an interval they set to manual, a registration that is
 * missing, a battery exemption they can grant, then a run that is late.
 */
data class ScheduleHealth(
    val interval: SyncInterval,
    val registered: Boolean,
    /** WorkManager's estimate of the next run; null when unknown. */
    val nextRunAt: Long?,
    val batteryExempt: Boolean,
) {
    enum class Verdict { MANUAL, NOT_REGISTERED, RESTRICTED, OVERDUE, HEALTHY }

    fun verdict(now: Long): Verdict = when {
        interval == SyncInterval.MANUAL -> Verdict.MANUAL
        !registered -> Verdict.NOT_REGISTERED
        !batteryExempt -> Verdict.RESTRICTED
        nextRunAt != null && nextRunAt < now - OVERDUE_GRACE_MILLIS -> Verdict.OVERDUE
        else -> Verdict.HEALTHY
    }

    private companion object {
        /** WorkManager's own scheduling slack; less than this is not late. */
        val OVERDUE_GRACE_MILLIS: Long = TimeUnit.MINUTES.toMillis(5)
    }
}

/** Where the health comes from; the Android implementation reads WorkManager and PowerManager. */
interface ScheduleHealthSource {
    fun observe(): Flow<ScheduleHealth>

    /** Re-reads state that is granted in system screens and cannot be observed. */
    fun refresh()
}
