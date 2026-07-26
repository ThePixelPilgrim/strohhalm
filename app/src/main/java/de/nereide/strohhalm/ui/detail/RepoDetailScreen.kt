package de.nereide.strohhalm.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nereide.strohhalm.R
import de.nereide.strohhalm.ui.add.DiagnosticCard
import de.nereide.strohhalm.ui.common.SyncProgressBar
import de.nereide.strohhalm.ui.common.syncErrorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    id: Long,
    onBack: () -> Unit,
    viewModel: RepoDetailViewModel = viewModel(factory = RepoDetailViewModel.factory(id))
) {
    val repo by viewModel.repo.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val refs by viewModel.refs.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val pendingHostKey by viewModel.pendingHostKey.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var alsoDeleteFiles by remember { mutableStateOf(false) }

    LaunchedEffect(deleted) { if (deleted) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(repo?.displayName ?: stringResource(R.string.detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        IconButton(onClick = { viewModel.syncNow() }) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.list_sync_now))
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                    }
                }
            )
        }
    ) { padding ->
        val current = repo ?: return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SyncProgressBar(progress, onCancel = viewModel::cancelSync)

            syncErrorText(current.lastErrorCode)?.let { message ->
                DiagnosticCard(
                    message = message,
                    detail = current.lastErrorDetail,
                    diagnostic = current.lastErrorDiagnostic,
                    onCopy = {
                        val text = buildString {
                            appendLine("Strohhalm sync failure")
                            appendLine("remote=${current.remoteUrl}")
                            appendLine("local=${current.localPath}")
                            appendLine("code=${current.lastErrorCode}")
                            appendLine("detail=${current.lastErrorDetail}")
                            appendLine("chain=${current.lastErrorDiagnostic}")
                        }
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Strohhalm diagnostics", text))
                    }
                )
                if (current.lastErrorCode == "HOST_KEY_MISMATCH") {
                    Button(onClick = { viewModel.recheckHostKey() }) {
                        Text(stringResource(R.string.detail_recheck_host_key))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Field(stringResource(R.string.detail_remote), current.remoteUrl)
            Field(stringResource(R.string.detail_local), current.localPath)
            Field(
                stringResource(R.string.detail_size),
                Formatter.formatShortFileSize(context, current.sizeBytes)
            )
            Field(
                stringResource(R.string.detail_last_sync),
                current.lastSyncAt?.let { relative(it) } ?: stringResource(R.string.detail_never)
            )
            Field(
                stringResource(R.string.detail_last_attempt),
                current.lastAttemptAt?.let { relative(it) } ?: stringResource(R.string.detail_never)
            )
            Field(
                stringResource(R.string.detail_fingerprint),
                current.hostKeyFingerprint ?: stringResource(R.string.detail_never)
            )

            if (refs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.detail_refs_title, refs.size),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.detail_refs_body),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                refs.forEach { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    pendingHostKey?.let { fingerprint ->
        AlertDialog(
            onDismissRequest = viewModel::dismissNewHostKey,
            title = { Text(stringResource(R.string.detail_new_host_key_title)) },
            text = { Text(stringResource(R.string.detail_new_host_key_body, fingerprint)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmNewHostKey) {
                    Text(stringResource(R.string.detail_new_host_key_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissNewHostKey) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.detail_delete_body))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = alsoDeleteFiles,
                            onCheckedChange = { alsoDeleteFiles = it }
                        )
                        Text(stringResource(R.string.detail_delete_files))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(alsoDeleteFiles)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun relative(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
