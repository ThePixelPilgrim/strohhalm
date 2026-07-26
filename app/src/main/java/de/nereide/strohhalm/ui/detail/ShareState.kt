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

    /** Which retry a blocked share is actually asking for. */
    enum class RetryAction { SYNC, ARCHIVE }

    /**
     * The sync ended without producing anything new.
     *
     * [cancelled] is not a failure and must not be styled as one — a stopped
     * sync is recorded as stopped. [canShareAnyway] is false before the first
     * successful sync, because an archive of a never-cloned repository would
     * restore nothing.
     *
     * [retry] names the step that failed, because the two failures need
     * opposite buttons: a fetch that could not reach the host is retried by
     * fetching again, while an archive refused for want of storage is retried
     * by packing the mirror it already has. Offering a fetch after a storage
     * refusal opens an SSH connection to fix a problem on the phone.
     */
    data class Blocked(
        val error: SyncError,
        val cancelled: Boolean,
        val canShareAnyway: Boolean,
        val retry: RetryAction = RetryAction.SYNC,
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

    /**
     * A running sync wins: `neverSynced` selects the "Sync now" button, and
     * that button calls `launchSyncOne`, which returns immediately while the
     * runner is busy. Offering it mid-sync offers a control that does nothing,
     * under a sentence contradicted by the progress bar directly above it. The
     * honest state is "waiting for the sync to finish", with no button —
     * `neverSynced` is then only reached where it can actually help.
     */
    fun onShareRequested(syncing: Boolean, everSynced: Boolean): ShareState = when {
        syncing -> ShareState.Waiting(neverSynced = false)
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
    fun onRetry(): ShareState = ShareState.Waiting(neverSynced = false)

    /**
     * Packing itself failed. Nothing about the remote is implicated, so the
     * way forward is to pack again — after freeing space, or restoring access
     * to the mirror folder. `canShareAnyway` stays false because "share
     * anyway" *is* that same pack, and offering it twice under two labels
     * would only be confusing.
     */
    fun onArchiveFailed(error: SyncError): ShareState.Blocked = ShareState.Blocked(
        error = error,
        cancelled = false,
        canShareAnyway = false,
        retry = ShareState.RetryAction.ARCHIVE,
    )
}
