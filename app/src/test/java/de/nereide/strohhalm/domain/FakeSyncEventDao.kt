package de.nereide.strohhalm.domain

import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventDao
import de.nereide.strohhalm.data.SyncEventOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [SyncEventDao] mirroring the SQL in the real one. */
class FakeSyncEventDao : SyncEventDao {

    val rows = MutableStateFlow<List<SyncEvent>>(emptyList())
    private var nextId = 1L

    override fun observeRecent(since: Long, receivedOnly: Boolean): Flow<List<SyncEvent>> =
        rows.map { list ->
            list.filter { it.startedAt >= since }
                .filter { !receivedOnly || it.outcome == SyncEventOutcome.RECEIVED }
                .sortedByDescending { it.startedAt }
        }

    override suspend fun insert(event: SyncEvent) {
        rows.value = rows.value + event.copy(id = nextId++)
    }

    override suspend fun deleteOlderThan(cutoff: Long) {
        rows.value = rows.value.filter { it.finishedAt >= cutoff }
    }
}
