package de.nereide.strohhalm.work

import de.nereide.strohhalm.domain.SyncError
import de.nereide.strohhalm.domain.SyncErrorCode

/**
 * The conditions a sync needs, expressed as plain values so the ordering and
 * thresholds are unit-testable without a device.
 *
 * These are checked *inside* the worker rather than declared as WorkManager
 * constraints. A constraint defers the work silently — the code never runs and
 * so can never report why — which would defeat the requirement to tell the user
 * when a sync cannot proceed.
 */
object SyncPreconditions {

    const val MIN_FREE_BYTES: Long = 250L * 1024 * 1024

    /**
     * Returns the reason a sync must not start, or null when it may proceed.
     * Storage is evaluated before the network so the user is shown the problem
     * they can actually act on.
     */
    fun check(
        freeBytes: Long,
        storageRootExists: Boolean,
        hasStoragePermission: Boolean,
        hasNetwork: Boolean,
    ): SyncError? = when {
        freeBytes < MIN_FREE_BYTES ->
            SyncError(SyncErrorCode.LOW_STORAGE, "$freeBytes bytes free")

        !hasStoragePermission || !storageRootExists ->
            SyncError(SyncErrorCode.PERMISSION_LOST)

        !hasNetwork ->
            SyncError(SyncErrorCode.NO_NETWORK)

        else -> null
    }
}
