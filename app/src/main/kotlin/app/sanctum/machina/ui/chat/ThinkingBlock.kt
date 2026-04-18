package app.sanctum.machina.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sanctum.machina.R

/**
 * Collapsible reasoning panel (D9, AC-7, AC-14, AC-18). Rendered above the
 * assistant's answer when a model with `llmSupportThinking = true` is running
 * with `enableThinking = true` and the stream has produced at least a
 * non-null `thinkingText`.
 *
 * UI behaviour (mirrors gallery `MessageBodyThinking.kt`):
 * - Left vertical line drawn via `drawBehind` using `outlineVariant` at 2dp
 *   stroke — the cost of a dedicated `Divider`/`Box` would fight the padding
 *   we actually want inside the panel; `drawBehind` paints behind the content
 *   without taking layout space.
 * - Muted body text via `colorScheme.onSurfaceVariant` + `SafeMarkdown`
 *   (`smallFontSize = true`) — reasoning is secondary to the answer.
 * - Auto-expand while `inProgress = true`: `LaunchedEffect(inProgress)` snaps
 *   `expanded` back to `true` every time the flag toggles back in. Setting
 *   `expanded = inProgress` unconditionally each frame would strip the user's
 *   ability to collapse post-stream.
 *
 * The toggle Row stays tappable in both states; collapsing while still
 * streaming is intentionally allowed, matching the gallery reference.
 */
@Composable
fun ThinkingBlock(
    thinkingText: String,
    inProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    // Seed expanded = inProgress so the first frame during a live stream shows
    // the content; `LaunchedEffect(inProgress)` re-expands on any later
    // streaming restart (e.g. after resetConversation).
    var expanded by remember { mutableStateOf(inProgress) }
    LaunchedEffect(inProgress) {
        if (inProgress) expanded = true
    }

    val showLabel = stringResource(R.string.thinking_show)
    val hideLabel = stringResource(R.string.thinking_hide)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = if (expanded) hideLabel else showLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector =
                    if (expanded) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown,
                contentDescription = if (expanded) hideLabel else showLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            val lineColor = MaterialTheme.colorScheme.outlineVariant
            Column(
                modifier = Modifier
                    .padding(top = 4.dp, bottom = 4.dp, start = 4.dp)
                    .drawBehind {
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, 0f),
                            end = Offset(0f, size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                    .padding(start = 12.dp),
            ) {
                SafeMarkdown(
                    text = thinkingText,
                    smallFontSize = true,
                    textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
