package de.nereide.strohhalm.ui.onboarding

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
fun OnboardingScreen(
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Returning from the system all-files-access screen produces no result, so
    // the state is simply re-read when the launcher's callback fires.
    val storageAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.refresh() }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refresh() }

    val pickFolder = rememberStorageRootPicker { picked -> viewModel.setStorageRoot(picked) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.onboarding_intro))
            Spacer(Modifier.height(16.dp))

            Step(
                title = stringResource(R.string.onboarding_storage_title),
                body = stringResource(R.string.onboarding_storage_body),
                action = stringResource(R.string.onboarding_storage_action),
                satisfied = uiState.hasStorageAccess,
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        storageAccessLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
            )
            Spacer(Modifier.height(12.dp))

            Step(
                title = stringResource(R.string.onboarding_folder_title),
                body = uiState.storageRoot ?: stringResource(R.string.onboarding_folder_body),
                action = stringResource(R.string.onboarding_folder_action),
                satisfied = uiState.storageRoot != null,
                enabled = uiState.hasStorageAccess,
                onAction = pickFolder
            )

            uiState.probeError?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.probe_failed, message),
                    color = MaterialTheme.colorScheme.error
                )
            }

            uiState.probe?.let { probe ->
                Spacer(Modifier.height(12.dp))
                ProbeCard(
                    filePath = probe.filePath,
                    nonce = probe.nonce,
                    documentId = probe.documentId,
                    onCopy = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("Strohhalm path probe", probe.content)
                        )
                    }
                )
            }

            Spacer(Modifier.height(12.dp))

            Step(
                title = stringResource(R.string.onboarding_notifications_title),
                body = stringResource(R.string.onboarding_notifications_body),
                action = stringResource(R.string.onboarding_notifications_action),
                satisfied = uiState.hasNotificationPermission,
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDone,
                enabled = uiState.complete,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.onboarding_done))
            }
        }
    }
}

/**
 * Shows where the app believes it wrote the probe file, so the claim can be
 * checked against the filesystem independently.
 */
@Composable
private fun ProbeCard(
    filePath: String,
    nonce: String,
    documentId: String,
    onCopy: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.probe_title),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.probe_body),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = filePath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "nonce=$nonce",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "documentId=$documentId",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onCopy) {
                Text(stringResource(R.string.probe_copy))
            }
        }
    }
}

@Composable
private fun Step(
    title: String,
    body: String,
    action: String,
    satisfied: Boolean,
    onAction: () -> Unit,
    enabled: Boolean = true,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            if (satisfied) {
                Text(
                    stringResource(R.string.onboarding_granted),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Button(onClick = onAction, enabled = enabled) { Text(action) }
            }
        }
    }
}
