package de.nereide.strohhalm.data

import java.time.Duration

/**
 * How often mirrors are refreshed.
 *
 * An enum rather than a raw number of minutes: WorkManager rejects periodic
 * intervals below 15 minutes, and encoding the choices as named values makes
 * that floor impossible to violate through a bad write, at the cost of no
 * arbitrary intervals. [MANUAL] cancels the periodic work entirely.
 */
enum class SyncInterval(val minutes: Long?) {
    MANUAL(null),
    M15(15),
    M30(30),
    H1(60),
    H3(180),
    H6(360),
    H12(720),
    D1(1_440);

    val duration: Duration?
        get() = minutes?.let(Duration::ofMinutes)
}
