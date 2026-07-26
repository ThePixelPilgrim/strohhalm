package de.nereide.strohhalm.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.nereide.strohhalm.R
import de.nereide.strohhalm.domain.SyncErrorCode

/**
 * Renders a stored error code as a message. The code is persisted rather than a
 * rendered string so messages stay localisable and the domain layer never holds
 * an Android resource id.
 */
@Composable
fun syncErrorText(code: String?): String? {
    val parsed = code?.let { name -> SyncErrorCode.entries.firstOrNull { it.name == name } }
        ?: return null
    return stringResource(
        when (parsed) {
            SyncErrorCode.NO_NETWORK -> R.string.error_no_network
            SyncErrorCode.LOW_STORAGE -> R.string.error_low_storage
            SyncErrorCode.PERMISSION_LOST -> R.string.error_permission_lost
            SyncErrorCode.AUTH_FAILED -> R.string.error_auth_failed
            SyncErrorCode.HOST_KEY_MISMATCH -> R.string.error_host_key_mismatch
            SyncErrorCode.HOST_UNREACHABLE -> R.string.error_host_unreachable
            SyncErrorCode.REMOTE_ERROR -> R.string.error_remote_error
            SyncErrorCode.LOCAL_CORRUPT -> R.string.error_local_corrupt
            SyncErrorCode.INTERRUPTED -> R.string.error_interrupted
            SyncErrorCode.CANCELLED -> R.string.error_cancelled
            SyncErrorCode.UNKNOWN -> R.string.error_unknown
        }
    )
}
