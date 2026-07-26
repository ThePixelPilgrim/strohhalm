package de.nereide.strohhalm.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.nereide.strohhalm.R
import de.nereide.strohhalm.domain.SyncProgress

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
fun SyncProgressBar(progress: SyncProgress?, modifier: Modifier = Modifier) {
    if (progress == null) return

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
