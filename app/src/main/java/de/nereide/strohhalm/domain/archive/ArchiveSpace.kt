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
