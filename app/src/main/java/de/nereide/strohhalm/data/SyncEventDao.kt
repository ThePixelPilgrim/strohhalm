package de.nereide.strohhalm.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncEventDao {

    /**
     * Newest first. [receivedOnly] narrows to attempts that fetched a pack —
     * the Activity screen's default view.
     */
    @Query(
        """
        SELECT * FROM sync_events
        WHERE startedAt >= :since
          AND (:receivedOnly = 0 OR outcome = 'RECEIVED')
        ORDER BY startedAt DESC
        """
    )
    fun observeRecent(since: Long, receivedOnly: Boolean): Flow<List<SyncEvent>>

    @Insert
    suspend fun insert(event: SyncEvent)

    @Query("DELETE FROM sync_events WHERE finishedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    /** Insert and prune atomically, so the log never needs the database itself. */
    @Transaction
    suspend fun recordAndPrune(event: SyncEvent, cutoff: Long) {
        insert(event)
        deleteOlderThan(cutoff)
    }
}
