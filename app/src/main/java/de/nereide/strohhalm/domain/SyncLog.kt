package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEvent

/** Where the runner writes one entry per sync attempt. */
interface SyncLog {
    suspend fun record(event: SyncEvent)
}

/** Tests, and anywhere no history is wanted. */
object NoSyncLog : SyncLog {
    override suspend fun record(event: SyncEvent) = Unit
}
