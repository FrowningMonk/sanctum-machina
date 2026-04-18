package app.sanctum.machina.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sanctum.machina.R

private val ThumbnailSize = 96.dp

/**
 * Horizontal strip of attachment thumbnails with a ✕ close button on each
 * (AC-10). Rendered only when `attachments` is non-empty — callers should
 * skip placement entirely when list is empty to save layout work.
 *
 * Keyed by `Attachment.id` (stable per instance). Keying by list index
 * would invalidate every trailing thumbnail on removal and cause flicker /
 * needless `asImageBitmap()` work.
 */
@Composable
fun ThumbnailStrip(
    attachments: List<Attachment>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(
            items = attachments,
            key = { _, attachment -> attachment.id },
        ) { index, attachment ->
            ThumbnailItem(
                attachment = attachment,
                onRemove = { onRemove(index) },
            )
        }
    }
}

@Composable
private fun ThumbnailItem(
    attachment: Attachment,
    onRemove: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(ThumbnailSize)
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
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(24.dp)
                .background(
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                ),
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.attachment_remove),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * Audio-attachment tile: waveform icon + "N с" label, sized to `fillMaxSize`.
 * Shared between `ThumbnailStrip` (staging area with ✕) and `MessageBubble`
 * (history rendering, no ✕). Expects to be placed inside a sized `Box`.
 */
@Composable
internal fun AudioAttachmentTile(durationMs: Long) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val seconds = (durationMs / 1000L).toInt().coerceAtLeast(0)
            Text(
                text = stringResource(R.string.audio_record_timer_format, seconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
