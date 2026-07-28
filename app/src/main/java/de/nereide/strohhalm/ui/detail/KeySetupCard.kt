package de.nereide.strohhalm.ui.detail

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.nereide.strohhalm.R
import de.nereide.strohhalm.domain.KeySetupLinks

/**
 * Shown while authentication is failing: the fix is always "put the public
 * key on the server", so the card carries both halves of it — the key, and
 * for a known forge, the exact page it belongs on. State-driven, not a
 * one-shot hint: it stays as long as the failure does.
 */
@Composable
fun KeySetupCard(
    host: String,
    publicKey: String?,
    onCopyKey: () -> Unit,
) {
    val context = LocalContext.current
    val link = KeySetupLinks.forHost(host)
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.keysetup_body, host),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                if (publicKey != null) {
                    OutlinedButton(onClick = onCopyKey) {
                        Text(stringResource(R.string.keysetup_copy_key))
                    }
                }
                if (link != null) {
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()))
                    }) {
                        Text(stringResource(R.string.keysetup_open, host))
                    }
                }
            }
        }
    }
}
