package de.nereide.strohhalm.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Where a repository stood at the end of its last sync attempt. */
enum class SyncStatus { NEVER, SYNCING, OK, FAILED }

/**
 * One mirrored repository.
 *
 * [lastSyncAt] and [lastAttemptAt] are deliberately separate: a failed sync
 * advances the attempt time but leaves the success time alone, so a repository
 * that has been failing for weeks cannot present a fresh timestamp and look
 * healthy.
 *
 * [localPath] is unique — two repositories sharing a directory would corrupt
 * each other.
 */
@Entity(
    tableName = "repos",
    indices = [Index(value = ["localPath"], unique = true)]
)
data class Repo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val remoteUrl: String,
    val localPath: String,
    val hostKeyFingerprint: String? = null,
    val lastSyncAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastStatus: SyncStatus = SyncStatus.NEVER,
    val lastErrorCode: String? = null,
    val lastErrorDetail: String? = null,
    /** Exception class chain from the last failure; the debugging channel. */
    val lastErrorDiagnostic: String? = null,
    val sizeBytes: Long = 0,
    val refCount: Int = 0,
    val createdAt: Long,
)
