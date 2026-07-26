package de.nereide.strohhalm.ui.list

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.nereide.strohhalm.R
import de.nereide.strohhalm.data.Repo
import de.nereide.strohhalm.data.SyncStatus
import de.nereide.strohhalm.ui.common.syncErrorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoListScreen(
    onOpenRepo: (Long) -> Unit,
    onAddRepo: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: RepoListViewModel = viewModel(factory = RepoListViewModel.Factory)
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.list_title)) },
                actions = {
                    if (syncing) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        IconButton(onClick = { viewModel.syncAll() }) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.list_sync_now))
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.list_settings))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddRepo) {
                Icon(Icons.Filled.Add, stringResource(R.string.list_add))
            }
        }
    ) { padding ->
        if (uiState.repos.isEmpty() && !uiState.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.list_empty))
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(uiState.repos, key = { it.id }) { repo ->
                    RepoRow(repo = repo, onClick = { onOpenRepo(repo.id) })
                }
            }
        }
    }
}

@Composable
private fun RepoRow(repo: Repo, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(repo.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = statusLine(repo),
                style = MaterialTheme.typography.bodySmall,
                color = if (repo.lastStatus == SyncStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (repo.sizeBytes > 0) {
                Text(
                    text = Formatter.formatShortFileSize(context, repo.sizeBytes),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (repo.refCount > 0) {
                Text(
                    text = stringResource(R.string.list_refs, repo.refCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun statusLine(repo: Repo): String = when (repo.lastStatus) {
    SyncStatus.NEVER -> stringResource(R.string.status_never)
    SyncStatus.SYNCING -> stringResource(R.string.status_syncing)
    SyncStatus.OK -> stringResource(
        R.string.status_ok,
        repo.lastSyncAt?.let {
            DateUtils.getRelativeTimeSpanString(
                it,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
        } ?: ""
    )
    // Show what actually went wrong rather than a bare "Failed" — the whole
    // point of the error taxonomy is that the user learns what to do next.
    SyncStatus.FAILED -> syncErrorText(repo.lastErrorCode) ?: stringResource(R.string.status_failed)
}
