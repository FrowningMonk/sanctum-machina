package app.sanctum.machina.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.sanctum.machina.R
import app.sanctum.machina.data.Citation

/**
 * Chat history row for a single [Message]. Extracted from `ChatScreen.kt`
 * (task 10) so the thinking/markdown composition can be unit-previewed in
 * isolation and so `ChatScreen` stays focused on layout orchestration.
 *
 * Rendering order inside the bubble:
 * 1. USER attachments grid (AC-26, D28) — image thumbnails + audio chips,
 *    so the history shows exactly what was dispatched to the model.
 * 2. [ThinkingBlock] for assistant messages when `thinkingText != null`
 *    and the active model has `llmSupportThinking = true` (AC-7, AC-14,
 *    AC-18). The `supportThinking` gate is enforced here, not in the VM,
 *    so the VM can still accumulate thinking if the flag flips mid-chat
 *    (e.g. model switch) — the bubble just stops rendering.
 * 3. Markdown answer via [SafeMarkdown] — rich text for assistant, plain
 *    text for the user bubble (users can't inject markdown into their
 *    own prompts in a way that should be parsed client-side).
 * 4. Footer (TTFT/latency on finished assistant messages).
 */
@Composable
fun MessageBubble(
    message: Message,
    supportThinking: Boolean,
    modifier: Modifier = Modifier,
    onCitationClick: (Citation) -> Unit = {},
) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor =
        if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val interruptedSuffix =
        if (message.interrupted) stringResource(R.string.chat_message_interrupted_suffix) else ""

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isUser && message.attachments.isNotEmpty()) {
                MessageAttachmentsRow(attachments = message.attachments)
            }

            if (!isUser && supportThinking && message.thinkingText != null) {
                ThinkingBlock(
                    thinkingText = message.thinkingText,
                    inProgress = message.streaming,
                )
            }

            if (message.text.isNotEmpty() || message.interrupted) {
                val display = message.text + interruptedSuffix
                if (isUser) {
                    // User text never streams, so wrap unconditionally.
                    SelectionContainer {
                        Text(
                            text = display,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (!message.streaming) {
                    // Skip SelectionContainer while the assistant bubble is
                    // mutating mid-stream — Compose loses selection cursor
                    // each recomposition and renders broken anchor handles.
                    SelectionContainer {
                        SafeMarkdown(text = display)
                    }
                } else {
                    SafeMarkdown(text = display)
                }
            }

            message.footer?.let { footer ->
                Text(
                    text = footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Phase 4 Task 12 — citation chips for assistant messages in
            // project chats. Mid-stream and persisted both flow through
            // `message.citations`: `ChatViewModel.buildMessagesFlow` already
            // merges `_streamingCitations` into the in-flight bubble's
            // `citations` field (T11 wiring), so the bubble does not need to
            // collect that StateFlow directly — uniform rendering by source
            // of truth.
            if (!isUser && message.citations.isNotEmpty()) {
                CitationChipStrip(
                    citations = message.citations,
                    onChipClick = onCitationClick,
                )
            }
        }
    }
}

// Stale-chip color tokens. The base chip uses surfaceVariant /
// onSurfaceVariant; we drop both alphas so a stale chip recedes visually
// without losing legibility. Tuned against design_handoff_phase3_ui pill
// tokens — moving to a design-system token table is tracked in tech-spec
// AC-21 follow-ups.
private const val STALE_CHIP_CONTAINER_ALPHA = 0.5f
private const val STALE_CHIP_LABEL_ALPHA = 0.6f

/**
 * Horizontal chip-strip rendered beneath an assistant bubble (Phase 4 Task 12).
 *
 * `FlowRow` so a long citation list wraps inside the 320 dp `widthIn` parent
 * column rather than overflowing horizontally.
 */
@Composable
private fun CitationChipStrip(
    citations: List<Citation>,
    onChipClick: (Citation) -> Unit,
) {
    val mutedContainer = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = STALE_CHIP_CONTAINER_ALPHA)
    val mutedLabel = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = STALE_CHIP_LABEL_ALPHA)

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (citation in citations) {
            // `formatChipLabel` resolves the strings.xml template once so the
            // label format stays unit-testable (CitationFormatTest) without
            // standing up a Compose tree. Same wiring would apply to title /
            // content-description if their templates ever diverge from the
            // chip label format; today they share the with-page / no-page
            // structure but live as separate keys for locale flexibility.
            val withPageTemplate = stringResource(R.string.citation_chip_label_with_page)
            val noPageTemplate = stringResource(R.string.citation_chip_label_no_page)
            val label = formatChipLabel(
                filename = citation.fileName,
                page = citation.page,
                withPageTemplate = withPageTemplate,
                noPageTemplate = noPageTemplate,
            )
            val cd = if (citation.page != null) {
                stringResource(
                    R.string.citation_chip_content_description_with_page,
                    citation.fileName,
                    citation.page,
                )
            } else {
                stringResource(
                    R.string.citation_chip_content_description_no_page,
                    citation.fileName,
                )
            }
            val chipColors = if (citation.stale) {
                AssistChipDefaults.assistChipColors(
                    containerColor = mutedContainer,
                    labelColor = mutedLabel,
                )
            } else {
                AssistChipDefaults.assistChipColors()
            }
            AssistChip(
                onClick = { onChipClick(citation) },
                label = {
                    // Plain Text — chip label includes a filename pulled from
                    // user-supplied PDF metadata. No markdown / link parser.
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = chipColors,
                modifier = Modifier.semantics { contentDescription = cd },
            )
        }
    }
}

private val MessageAttachmentSize = 56.dp
private const val MESSAGE_ATTACHMENTS_PER_ROW = 5

/**
 * Attachment grid inside the user bubble (D28, AC-26). `FlowRow` wraps to new
 * rows — 10 × 56dp tiles with 4dp spacing = 2 rows of 5 inside the 296dp
 * bubble content width (320dp − 24dp horizontal padding). All attachments
 * visible without internal scroll; LazyRow previously clipped to the first
 * 4 tiles which felt like data loss to the user.
 */
@Composable
private fun MessageAttachmentsRow(attachments: List<Attachment>) {
    FlowRow(
        maxItemsInEachRow = MESSAGE_ATTACHMENTS_PER_ROW,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (attachment in attachments) {
            Box(
                modifier = Modifier
                    .size(MessageAttachmentSize)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(8.dp),
                    ),
            ) {
                when (attachment) {
                    is Attachment.Image -> Image(
                        bitmap = attachment.bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.attachment_image_label),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    is Attachment.Audio -> AudioAttachmentTile(durationMs = attachment.durationMs)
                }
            }
        }
    }
}
