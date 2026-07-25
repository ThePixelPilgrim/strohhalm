package de.nereide.strohhalm.ui.common

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import de.nereide.strohhalm.domain.StorageRootResolver
import java.io.File

/**
 * A folder the user chose, together with the SAF document id it came from.
 *
 * The document id is carried through rather than discarded because the probe
 * records it: when a derived path turns out to be wrong, the id is the evidence
 * needed to work out why.
 */
data class PickedFolder(val dir: File, val documentId: String)

/**
 * Launches the system folder picker and hands back a real filesystem path.
 *
 * The returned tree URI is discarded: access comes from MANAGE_EXTERNAL_STORAGE
 * and ordinary `java.io.File`, because JGit needs a real path and SAF only hands
 * out opaque document URIs. The picker is used purely as a chooser.
 *
 * [onPicked] receives null when the user cancelled or the path could not be
 * derived at all. Note that a non-null result is *not* yet proof the path is
 * correct — only that it parsed. Confirming it is the probe's job.
 */
@Composable
fun rememberStorageRootPicker(onPicked: (PickedFolder?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            onPicked(null)
            return@rememberLauncherForActivityResult
        }
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val resolved = documentId?.let {
            StorageRootResolver.resolve(
                documentId = it,
                primaryRoot = Environment.getExternalStorageDirectory(),
                volumeLookup = volumeLookup(context)
            )
        }
        onPicked(
            if (resolved != null && documentId != null) {
                PickedFolder(resolved, documentId)
            } else {
                null
            }
        )
    }

    // Android 11+ refuses to return the root of primary storage, so the user must
    // pick or create a subfolder.
    return { launcher.launch(null) }
}

private fun volumeLookup(context: Context): (String) -> File? = { volumeId ->
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.getSystemService(StorageManager::class.java)
            ?.storageVolumes
            ?.firstOrNull { it.uuid == volumeId }
            ?.directory
    } else {
        null
    }
}
