package de.nereide.strohhalm.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.nereide.strohhalm.R
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.nereide.strohhalm.domain.SyncProgress
import kotlinx.coroutines.delay

/**
 * Shows what the mirror engine is actually doing.
 *
 * Mirroring a large repository legitimately runs for many minutes — a 500 MB
 * repo is minutes of transfer before a single object is indexed. Without a task
 * name and a count, a working sync and a deadlocked one look identical, and the
 * only rational response to either is to force-quit the app.
 *
 * JGit reports object counts rather than bytes, and some phases report no total
 * at all, so the bar falls back to indeterminate rather than inventing a number.
 */
@Composable
fun SyncProgressBar(
    progress: SyncProgress?,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    if (progress == null) return

    // Ticks once a second purely so the elapsed time advances. A label that
    // never changes is indistinguishable from a frozen app, which is exactly
    // the confusion this whole component exists to prevent.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress.startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsed = if (progress.startedAt > 0) (now - progress.startedAt) / 1000 else 0L

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = progress.repoName + "  ·  " + formatElapsed(elapsed),
                style = MaterialTheme.typography.labelMedium
            )
            // Without this the only way out of a stuck sync is to force-quit the
            // app — which is precisely what strands rows at SYNCING.
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_stop_sync))
                }
            }
        }
        Text(
            text = if (progress.total > 0) {
                stringResource(
                    R.string.progress_of,
                    progress.task,
                    progress.completed,
                    progress.total
                )
            } else {
                stringResource(R.string.progress_indeterminate, progress.task)
            },
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(4.dp))
        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun formatElapsed(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "%d:%02d".format(m, s) else "%ds".format(s)
}
