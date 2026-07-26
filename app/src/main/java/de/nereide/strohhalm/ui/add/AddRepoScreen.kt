package de.nereide.strohhalm.ui.add

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nereide.strohhalm.R
import de.nereide.strohhalm.ui.common.syncErrorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRepoScreen(
    onDone: () -> Unit,
    viewModel: AddRepoViewModel = viewModel(factory = AddRepoViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = uiState.url,
                onValueChange = viewModel::setUrl,
                label = { Text(stringResource(R.string.add_url_label)) },
                placeholder = { Text(stringResource(R.string.add_url_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = uiState.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.add_name_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))

            if (uiState.probing) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.add_checking))
            } else {
                Button(
                    onClick = viewModel::probe,
                    enabled = uiState.url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.add_probe))
                }
            }

            syncErrorText(uiState.errorCode)?.let { message ->
                Spacer(Modifier.height(16.dp))
                DiagnosticCard(
                    message = message,
                    detail = uiState.errorDetail,
                    diagnostic = uiState.errorDiagnostic,
                    onCopy = {
                        val text = buildString {
                            appendLine("Strohhalm add-repository failure")
                            appendLine("url=${uiState.url}")
                            appendLine("code=${uiState.errorCode}")
                            appendLine("detail=${uiState.errorDetail}")
                            appendLine("chain=${uiState.errorDiagnostic}")
                        }
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Strohhalm diagnostics", text))
                    }
                )
            }
        }
    }

    uiState.fingerprint?.let { fingerprint ->
        AlertDialog(
            onDismissRequest = viewModel::dismissFingerprint,
            title = { Text(stringResource(R.string.add_fingerprint_title)) },
            text = { Text(stringResource(R.string.add_fingerprint_body, fingerprint)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmFingerprint) {
                    Text(stringResource(R.string.add_fingerprint_accept))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissFingerprint) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * Shows the friendly message *and* the raw library detail plus exception chain.
 *
 * Ordinarily this belongs in a log. Strohhalm is being developed without adb, so
 * the screen is the only channel back — the technical detail has to be visible
 * and copyable, not hidden behind a polished message.
 */
@Composable
fun DiagnosticCard(
    message: String,
    detail: String?,
    diagnostic: String?,
    onCopy: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            diagnostic?.let { full ->
                // The full diagnostic includes a stack trace and is far too long
                // to read on a phone. Show enough to recognise the failure; the
                // copy button carries all of it.
                val lines = full.lines()
                val preview = lines.take(DIAGNOSTIC_PREVIEW_LINES).joinToString("\n")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                if (lines.size > DIAGNOSTIC_PREVIEW_LINES) {
                    Text(
                        text = stringResource(
                            R.string.diagnostics_more,
                            lines.size - DIAGNOSTIC_PREVIEW_LINES
                        ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCopy) {
                Text(stringResource(R.string.copy_diagnostics))
            }
        }
    }
}

private const val DIAGNOSTIC_PREVIEW_LINES = 6
