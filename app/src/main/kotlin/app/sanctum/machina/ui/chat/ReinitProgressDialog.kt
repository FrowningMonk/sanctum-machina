package app.sanctum.machina.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.sanctum.machina.R

/**
 * Modal progress indicator shown while a heavy-setting reinit runs (D12, D21).
 * Both back-button and outside-tap dismissal are blocked — the engine
 * teardown/initialize sequence is uncancellable, and a stray dismiss would
 * let the user fire `send()` against a half-built engine.
 */
@Composable
fun ReinitProgressDialog() {
    AlertDialog(
        onDismissRequest = { /* uncancellable */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        confirmButton = { /* no buttons during progress */ },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 4.dp))
                Text(text = stringResource(R.string.reinit_progress_message))
            }
        },
    )
}
