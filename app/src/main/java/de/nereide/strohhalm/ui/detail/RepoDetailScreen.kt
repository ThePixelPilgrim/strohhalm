package de.nereide.strohhalm.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nereide.strohhalm.R
import de.nereide.strohhalm.domain.SyncErrorCode
import de.nereide.strohhalm.ui.add.DiagnosticCard
import de.nereide.strohhalm.ui.common.CalmIndeterminateBar
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
    val shareState by viewModel.shareState.collectAsStateWithLifecycle()
    val verifying by viewModel.verifying.collectAsStateWithLifecycle()
    val probeError by viewModel.probeError.collectAsStateWithLifecycle()
    val publicKey by viewModel.publicKey.collectAsStateWithLifecycle()
    // Computed here, not after `val current = repo ?: return@Scaffold`: the
    // top-bar `actions` block renders before that point and needs it.
    val unverified = repo != null && repo?.hostKeyFingerprint == null
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
                        // Greyed out while an archive is being packed: a fetch
                        // would write into the very directory being zipped, and
                        // the mirror lock would silently drop the tap. Better
                        // that the button looks unavailable than dead.
                        IconButton(
                            onClick = { viewModel.syncNow() },
                            enabled = shareState !is ShareState.Packing && !unverified,
                        ) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.list_sync_now))
                        }
                    }
                    IconButton(onClick = { viewModel.share() }, enabled = !unverified) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.share_backup),
                        )
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                    }
                }
            )
        }
    ) { padding ->
        val current = repo ?: return@Scaffold
        val remoteHost = remember(current.remoteUrl) {
            runCatching {
                de.nereide.strohhalm.domain.git.GitRemote.parse(current.remoteUrl).host
            }.getOrNull()
        }
        val copyPublicKey = {
            publicKey?.let { line ->
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Strohhalm public key", line))
            }
            Unit
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SyncProgressBar(progress, onCancel = viewModel::cancelSync)

            if (unverified) {
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (verifying) {
                            Text(
                                stringResource(R.string.detail_verifying),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            CalmIndeterminateBar(modifier = Modifier.fillMaxWidth())
                        } else {
                            Text(
                                stringResource(R.string.detail_unverified_body),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = viewModel::verify) {
                                Text(stringResource(R.string.detail_verify_now))
                            }
                        }
                    }
                }
            }

            probeError?.let { error ->
                syncErrorText(error.code.name)?.let { message ->
                    DiagnosticCard(
                        message = message,
                        detail = error.detail,
                        diagnostic = error.diagnostic,
                        onCopy = {
                            val text = buildString {
                                appendLine("Strohhalm verification failure")
                                appendLine("remote=${current.remoteUrl}")
                                appendLine("code=${error.code.name}")
                                appendLine("detail=${error.detail}")
                                appendLine("chain=${error.diagnostic}")
                            }
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("Strohhalm diagnostics", text))
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                }
            }

            if (probeError?.code == SyncErrorCode.AUTH_FAILED && remoteHost != null) {
                KeySetupCard(host = remoteHost, publicKey = publicKey, onCopyKey = copyPublicKey)
            }

            when (val state = shareState) {
                is ShareState.Idle -> Unit

                is ShareState.Waiting -> ShareNotice(
                    text = if (state.neverSynced) {
                        stringResource(R.string.share_never_synced)
                    } else {
                        stringResource(R.string.share_waiting_sync)
                    },
                    primary = if (state.neverSynced) {
                        stringResource(R.string.share_sync_now) to viewModel::retrySync
                    } else {
                        null
                    },
                    onDismiss = viewModel::cancelShare,
                )

                // Packing a large mirror is minutes of work. Without a Stop
                // button the only way out is the system Back gesture, which
                // nothing on screen suggests.
                is ShareState.Packing -> ShareNotice(
                    text = stringResource(R.string.share_packing, state.completed, state.total),
                    primary = stringResource(R.string.share_stop) to viewModel::cancelShare,
                    progress = if (state.total > 0) {
                        state.completed.toFloat() / state.total
                    } else {
                        null
                    },
                    onDismiss = viewModel::cancelShare,
                )

                is ShareState.Blocked -> ShareBlocked(
                    state = state,
                    lastSyncAt = repo?.lastSyncAt,
                    onRetry = viewModel::retrySync,
                    onShareAnyway = viewModel::shareAnyway,
                    onDismiss = viewModel::cancelShare,
                )

                is ShareState.Ready -> LaunchedEffect(state.archive) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "de.nereide.strohhalm.fileprovider",
                        state.archive,
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(
                        Intent.createChooser(send, context.getString(R.string.share_chooser_title))
                    )
                    viewModel.shareConsumed()
                }
            }

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
                    Button(onClick = { viewModel.verify() }) {
                        Text(stringResource(R.string.detail_recheck_host_key))
                    }
                }
                if (current.lastErrorCode == SyncErrorCode.AUTH_FAILED.name && remoteHost != null) {
                    KeySetupCard(host = remoteHost, publicKey = publicKey, onCopyKey = copyPublicKey)
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

    pendingHostKey?.let { pending ->
        val body = buildString {
            append(
                if (pending.firstTrust) {
                    stringResource(R.string.add_fingerprint_body, pending.fingerprint)
                } else {
                    stringResource(R.string.detail_new_host_key_body, pending.fingerprint)
                }
            )
            if (pending.authFailed) {
                append("\n\n")
                append(stringResource(R.string.detail_verify_auth_note))
            }
        }
        AlertDialog(
            onDismissRequest = viewModel::dismissHostKey,
            title = {
                Text(
                    stringResource(
                        if (pending.firstTrust) R.string.add_fingerprint_title
                        else R.string.detail_new_host_key_title
                    )
                )
            },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmHostKey) {
                    Text(
                        stringResource(
                            if (pending.firstTrust) R.string.add_fingerprint_accept
                            else R.string.detail_new_host_key_accept
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissHostKey) {
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

@Composable
private fun ShareNotice(
    text: String,
    primary: Pair<String, () -> Unit>?,
    onDismiss: () -> Unit,
    progress: Float? = null,
) {
    // Back dismisses the share without touching the sync, which keeps running.
    BackHandler(onBack = onDismiss)
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text)
            // Determinate only, and only from the same two numbers the line
            // above states: a bar that disagreed with the count would be worse
            // than none.
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            Text(
                text = stringResource(R.string.share_unencrypted_warning),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                primary?.let { (label, action) ->
                    Button(onClick = action) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun ShareBlocked(
    state: ShareState.Blocked,
    lastSyncAt: Long?,
    onRetry: () -> Unit,
    onShareAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (state.cancelled) {
                    stringResource(R.string.share_sync_stopped)
                } else {
                    // syncErrorText takes the *code name*, not a SyncError.
                    syncErrorText(state.error.code.name)
                        ?: state.error.detail.orEmpty()
                },
            )
            Row(modifier = Modifier.padding(top = 8.dp)) {
                // The button has to match the step that failed. After a
                // storage refusal, "Retry sync" would open an SSH connection
                // to the remote to fix a problem on the phone — and leave the
                // archive, which is what the user asked for, unreachable
                // except by starting over from the top bar.
                when (state.retry) {
                    ShareState.RetryAction.SYNC ->
                        Button(onClick = onRetry) {
                            Text(stringResource(R.string.share_retry_sync))
                        }

                    ShareState.RetryAction.ARCHIVE ->
                        Button(onClick = onShareAnyway) {
                            Text(stringResource(R.string.share_try_again))
                        }
                }
                if (state.canShareAnyway) {
                    TextButton(
                        onClick = onShareAnyway,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            lastSyncAt?.let {
                                stringResource(R.string.share_anyway_dated, relative(it))
                            } ?: stringResource(R.string.share_anyway)
                        )
                    }
                }
            }
        }
    }
}

private fun relative(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS
    ).toString()
