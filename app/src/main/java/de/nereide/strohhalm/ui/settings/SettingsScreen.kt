package de.nereide.strohhalm.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import de.nereide.strohhalm.ui.common.rememberStorageRootPicker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val publicKey by viewModel.publicKey.collectAsStateWithLifecycle()
    val probe by viewModel.probe.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickFolder = rememberStorageRootPicker { picked -> viewModel.setStorageRoot(picked) }
    var confirmRegenerate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            Text(
                stringResource(R.string.settings_folder_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = uiState.storageRoot ?: "",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            probe?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "probe: ${it.filePath} (nonce=${it.nonce})",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = pickFolder) { Text(stringResource(R.string.settings_folder_change)) }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Text(
                stringResource(R.string.settings_key_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                stringResource(R.string.settings_key_body),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = publicKey ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(
                    onClick = { publicKey?.let { copyPublicKey(context, it) } },
                    enabled = publicKey != null
                ) {
                    Text(stringResource(R.string.settings_key_copy))
                }
                Spacer(Modifier.padding(horizontal = 4.dp))
                TextButton(onClick = { confirmRegenerate = true }) {
                    Text(stringResource(R.string.settings_key_regenerate))
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.settings_notify_title),
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = uiState.notifyOnFailure,
                    onCheckedChange = viewModel::setNotifyOnFailure
                )
            }
        }
    }

    if (confirmRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text(stringResource(R.string.settings_key_regenerate_title)) },
            text = { Text(stringResource(R.string.settings_key_regenerate_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRegenerate = false
                    viewModel.regenerateKey()
                }) { Text(stringResource(R.string.settings_key_regenerate)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRegenerate = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * Android 13+ shows its own confirmation when something is copied, so the app
 * only adds a toast below that version — otherwise the user sees two.
 */
private fun copyPublicKey(context: Context, key: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Strohhalm public key", key))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.settings_key_copied, Toast.LENGTH_SHORT).show()
    }
}
