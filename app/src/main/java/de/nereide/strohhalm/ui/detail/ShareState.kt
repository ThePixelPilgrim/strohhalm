package de.nereide.strohhalm.ui.detail

import de.nereide.strohhalm.domain.SyncError
import java.io.File

/** Where a pending share has got to. */
sealed interface ShareState {

    /** No share pending. */
    data object Idle : ShareState

    /**
     * A sync stands between the request and an archive.
     *
     * [neverSynced] distinguishes "wait for the running sync" from "there is
     * nothing backed up yet", which need different words and different buttons.
     */
    data class Waiting(val neverSynced: Boolean) : ShareState

    data class Packing(val completed: Int, val total: Int) : ShareState

    /**
     * The sync ended without producing anything new.
     *
     * [cancelled] is not a failure and must not be styled as one — a stopped
     * sync is recorded as stopped. [canShareAnyway] is false before the first
     * successful sync, because an archive of a never-cloned repository would
     * restore nothing.
     */
    data class Blocked(
        val error: SyncError,
        val cancelled: Boolean,
        val canShareAnyway: Boolean,
    ) : ShareState

    /** An archive is ready to hand to the share sheet. */
    data class Ready(val archive: File) : ShareState
}

/**
 * The transitions, as plain functions.
 *
 * Kept apart from the ViewModel so every branch can be pinned by a test that
 * needs no Android, no Room and no clock.
 */
object ShareRules {

    fun onShareRequested(syncing: Boolean, everSynced: Boolean): ShareState = when {
        syncing -> ShareState.Waiting(neverSynced = !everSynced)
        !everSynced -> ShareState.Waiting(neverSynced = true)
        else -> ShareState.Packing(0, 0)
    }

    fun onSyncFinished(failed: SyncError?, cancelled: Boolean, everSynced: Boolean): ShareState =
        if (failed == null) {
            ShareState.Packing(0, 0)
        } else {
            ShareState.Blocked(
                error = failed,
                cancelled = cancelled,
                canShareAnyway = everSynced,
            )
        }

    /** A retry keeps the share pending; losing it would make the button pointless. */
    fun onRetry(everSynced: Boolean): ShareState = ShareState.Waiting(neverSynced = !everSynced)
}
