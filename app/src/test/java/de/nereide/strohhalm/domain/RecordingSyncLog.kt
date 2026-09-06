package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEvent
import java.util.concurrent.CopyOnWriteArrayList

/** Collects events in memory; [failing] makes every record throw. */
class RecordingSyncLog(private val failing: Boolean = false) : SyncLog {
    val events = CopyOnWriteArrayList<SyncEvent>()

    override suspend fun record(event: SyncEvent) {
        if (failing) throw IllegalStateException("disk full")
        events += event
    }
}
