package app.sanctum.machina.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sanctum.machina.R
import app.sanctum.machina.data.Citation

/**
 * Bottom sheet that surfaces a single citation's full `chunk_text` (Phase 4 Task 12).
 *
 * **Security (CRITICAL):** `chunk_text` originates from a user-uploaded PDF and is
 * **untrusted content**. It is rendered through plain [androidx.compose.material3.Text]
 * — no `SafeMarkdown`, no `RichText`, no `Markdown`, no `AnnotatedString` link parsing.
 * Compose `Text` renders literals, which is exactly what protects against
 * `[click me](intent://...)`-style PDF-injection vectors. See `patterns.md` —
 * the "Markdown rendering goes through SafeMarkdown" rule covers trusted LLM
 * output, **not** citation payloads. Do not "improve" this by wrapping in a
 * markdown renderer.
 *
 * `citation == null` is a no-op (the sheet is hidden), letting [ChatScreenBody]
 * hold one `mutableStateOf<Citation?>(null)` and toggle visibility by assigning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitationModal(
    citation: Citation?,
    onDismiss: () -> Unit,
) {
    if (citation == null) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title = if (citation.page != null) {
        stringResource(R.string.citation_modal_title_with_page, citation.fileName, citation.page)
    } else {
        stringResource(R.string.citation_modal_title_no_page, citation.fileName)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            if (citation.stale) {
                Text(
                    text = stringResource(R.string.citation_modal_stale_marker),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Cap the scrollable region so a 2000-char chunk never pushes the
            // Close button off-screen on short devices.
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SelectionContainer {
                    // Plain Text — see file-level KDoc. NO markdown renderer.
                    Text(
                        text = citation.chunkText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(text = stringResource(R.string.citation_modal_close))
            }
        }
    }
}
