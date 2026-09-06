package de.nereide.strohhalm.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** How one sync attempt of one repository ended. */
enum class SyncEventOutcome { RECEIVED, UP_TO_DATE, FAILED, CANCELLED }

/** What started the attempt. */
enum class SyncTrigger { MANUAL, SCHEDULED }

/**
 * One sync attempt of one repository. The repository's name is copied in so
 * a rename or deletion leaves the entry legible; [repoId] is not a foreign
 * key for the same reason.
 *
 * [bytesReceived] is the size of the pack the server sent — the transfer,
 * not the growth of the mirror folder — and is zero unless [outcome] is
 * [SyncEventOutcome.RECEIVED].
 */
@Entity(
    tableName = "sync_events",
    indices = [Index(value = ["startedAt"])]
)
data class SyncEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val repoId: Long,
    val repoName: String,
    val startedAt: Long,
    val finishedAt: Long,
    val outcome: SyncEventOutcome,
    val bytesReceived: Long = 0,
    val refsChanged: Int = 0,
    val errorCode: String? = null,
    val trigger: SyncTrigger,
) {
    val durationMillis: Long get() = (finishedAt - startedAt).coerceAtLeast(0)
}

class SyncEventConverters {
    @TypeConverter
    fun toOutcome(name: String): SyncEventOutcome =
        SyncEventOutcome.entries.firstOrNull { it.name == name } ?: SyncEventOutcome.FAILED

    @TypeConverter
    fun fromOutcome(outcome: SyncEventOutcome): String = outcome.name

    @TypeConverter
    fun toTrigger(name: String): SyncTrigger =
        SyncTrigger.entries.firstOrNull { it.name == name } ?: SyncTrigger.MANUAL

    @TypeConverter
    fun fromTrigger(trigger: SyncTrigger): String = trigger.name
}
