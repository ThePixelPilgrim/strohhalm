package de.nereide.strohhalm.domain.archive

import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode

/**
 * How much internal storage an archive needs, and whether it is available.
 *
 * Checked before building rather than discovered during it. The unmounted-card
 * failure taught this the hard way: a storage problem reported as whatever
 * happened to throw first, two minutes in, sends the reader looking in entirely
 * the wrong place.
 */
object ArchiveSpace {

    /**
     * A flat cushion, so a small repository is not judged against a percentage
     * of almost nothing.
     */
    const val FLOOR_BYTES = 10L * 1024 * 1024

    /**
     * The archive is close to the mirror's own size — pack files, nearly all of
     * the bytes, are stored rather than re-deflated. The five percent covers
     * zip per-entry overhead, which scales with file count.
     */
    fun required(mirrorBytes: Long): Long = mirrorBytes + mirrorBytes / 20 + FLOOR_BYTES

    /**
     * @param allocatableBytes what the system could make available, from
     *   `StorageManager.getAllocatableBytes` — not what is merely free now.
     *   The difference is other apps' reclaimable caches, and using the smaller
     *   figure would refuse builds the device could comfortably do.
     */
    fun check(mirrorBytes: Long, allocatableBytes: Long): SyncError? {
        val need = required(mirrorBytes)
        if (allocatableBytes >= need) return null
        return SyncError(
            SyncErrorCode.LOW_STORAGE,
            "packing this backup needs $need bytes of internal storage; " +
                "only $allocatableBytes can be made available",
        )
    }
}

/**
 * The device's cache allowance, behind an interface so the sequencing above can
 * be tested without a device. The implementation over `StorageManager` lives in
 * `DefaultAppContainer`.
 */
interface CacheSpace {
    suspend fun allocatable(): Long

    /** Actually reclaim [bytes]. False if the system could not. */
    suspend fun reserve(bytes: Long): Boolean
}

/**
 * Ask, then take. Asking alone is not enough: `getAllocatableBytes` counts the
 * space other apps' caches are holding, and that only becomes real free space
 * when `allocateBytes` is called for it. A build approved against the reported
 * figure but never granted the bytes runs into ENOSPC part-way through, which
 * surfaces as a bare `IOException` — `UNKNOWN`, two minutes in, which is the
 * exact failure the precheck exists to prevent.
 *
 * The check still runs first, so a device that is genuinely too full is told so
 * with both figures rather than by a bare refusal from the allocator.
 */
suspend fun ArchiveSpace.reserveOrRefuse(space: CacheSpace, mirrorBytes: Long): SyncError? {
    check(mirrorBytes, space.allocatable())?.let { return it }
    val need = required(mirrorBytes)
    if (space.reserve(need)) return null
    return SyncError(
        SyncErrorCode.LOW_STORAGE,
        "packing this backup needs $need bytes of internal storage; " +
            "the system reported them as available but could not reserve them",
    )
}
