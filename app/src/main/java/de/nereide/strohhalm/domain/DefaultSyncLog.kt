package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventDao
import java.util.concurrent.TimeUnit

/**
 * Writes events to Room and prunes on every write. One indexed DELETE per
 * sync is cheap, and doing it here means there is no separate maintenance
 * path to forget. The cutoff is relative to the event being written, not to
 * the wall clock, so a device with a wrong clock prunes consistently with
 * what it records.
 */
class DefaultSyncLog(private val dao: SyncEventDao) : SyncLog {

    override suspend fun record(event: SyncEvent) {
        dao.recordAndPrune(event, cutoff = event.finishedAt - RETENTION_MILLIS)
    }

    companion object {
        val RETENTION_MILLIS: Long = TimeUnit.DAYS.toMillis(30)
    }
}
