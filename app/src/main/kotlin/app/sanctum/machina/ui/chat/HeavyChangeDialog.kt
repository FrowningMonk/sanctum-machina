package app.sanctum.machina.ui.chat

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sanctum.machina.R

/**
 * Confirmation dialog for heavy-setting changes (D12, AC-21). Heavy changes —
 * accelerator, enable_thinking — require `cleanup ⇒ initialize` of the native
 * engine and wipe the in-engine conversation context (the UI-side history is
 * preserved by the caller). Confirm hands off to a non-cancellable
 * [ReinitProgressDialog] while litertlm rebuilds.
 */
@Composable
fun HeavyChangeDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.heavy_change_dialog_title)) },
        text = { Text(stringResource(R.string.heavy_change_dialog_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.btn_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.btn_cancel))
            }
        },
    )
}
