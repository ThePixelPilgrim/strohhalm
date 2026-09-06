package de.nereide.strohhalm.ui.activity

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import de.nereide.strohhalm.data.SyncEvent
import de.nereide.strohhalm.data.SyncEventOutcome
import de.nereide.strohhalm.data.SyncTrigger
import de.nereide.strohhalm.ui.common.relative
import de.nereide.strohhalm.ui.common.syncErrorText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    onBack: () -> Unit,
    viewModel: ActivityViewModel = viewModel(factory = ActivityViewModel.Factory),
) {
    val events by viewModel.events.collectAsStateWithLifecycle()
    val receivedOnly by viewModel.receivedOnly.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.activity_title)) },
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
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FilterChip(
                selected = receivedOnly,
                onClick = { viewModel.setReceivedOnly(!receivedOnly) },
                label = { Text(stringResource(R.string.activity_filter_received)) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (events.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(
                            if (receivedOnly) R.string.activity_empty_received
                            else R.string.activity_empty_all
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(events, key = { it.id }) { event ->
                        EventRow(event)
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: SyncEvent) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(event.repoName, style = MaterialTheme.typography.titleMedium)
            Text(relative(event.finishedAt), style = MaterialTheme.typography.bodySmall)
        }
        val detail = when (event.outcome) {
            SyncEventOutcome.RECEIVED -> stringResource(
                R.string.activity_received,
                Formatter.formatShortFileSize(context, event.bytesReceived),
                event.refsChanged,
            )
            SyncEventOutcome.UP_TO_DATE -> stringResource(R.string.activity_up_to_date)
            SyncEventOutcome.FAILED -> syncErrorText(event.errorCode)
                ?: stringResource(R.string.error_unknown)
            SyncEventOutcome.CANCELLED -> stringResource(R.string.activity_cancelled)
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(duration(event.durationMillis), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    when (event.trigger) {
                        SyncTrigger.SCHEDULED -> R.string.activity_trigger_scheduled
                        SyncTrigger.MANUAL -> R.string.activity_trigger_manual
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun duration(millis: Long): String {
    val totalSeconds = millis / 1_000
    return if (totalSeconds < 60) {
        stringResource(R.string.activity_duration_seconds, totalSeconds)
    } else {
        stringResource(R.string.activity_duration_minutes, totalSeconds / 60, totalSeconds % 60)
    }
}
