package de.nereide.strohhalm.work

import de.nereide.strohhalm.domain.SyncErrorCode

/**
 * Notification ids are fixed per failure *category*, so a recurring failure
 * replaces its predecessor rather than stacking, and is cancelled on the next
 * success.
 */
object NotificationIds {

    const val PROGRESS: Int = 1

    private const val ERROR_BASE = 100

    fun forError(code: SyncErrorCode): Int = ERROR_BASE + code.ordinal

    /** Every id this app may post, for bulk cancellation. */
    fun allErrorIds(): List<Int> = SyncErrorCode.entries.map(::forError)
}
