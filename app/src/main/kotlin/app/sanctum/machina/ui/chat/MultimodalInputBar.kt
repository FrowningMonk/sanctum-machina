package app.sanctum.machina.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sanctum.machina.R

/**
 * Max images per Photo Picker batch. Matches `ChatViewModel.MAX_IMAGES` —
 * client-side hint; `ChatViewModel.addImages` defensively clips again.
 */
private const val MAX_IMAGES_PER_PICK = 10

/**
 * Chat input row with optional camera, gallery, and mic buttons.
 *
 * Button visibility follows model capabilities (AC-18): camera/gallery hide
 * when `supportImage = false`; mic hides when `supportAudio = false`.
 * Send is disabled until at least one of text or attachments is non-empty
 * (AC-9). While `isGenerating` is true, Send is replaced by a Stop button.
 */
@Composable
fun MultimodalInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    hasAttachments: Boolean,
    isGenerating: Boolean,
    supportImage: Boolean,
    supportAudio: Boolean,
    audioButtonEnabled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickImages: (List<android.net.Uri>) -> Unit,
    onOpenCamera: () -> Unit,
    onOpenAudioRecorder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pickMedia = rememberLauncherForActivityResult(
        // API 31+: PickMultipleVisualMedia falls back to system picker on older shells;
        // maxItems acts as a hint — ChatViewModel.addImages clips authoritatively (AC-10).
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_PICK),
    ) { uris -> if (uris.isNotEmpty()) onPickImages(uris) }

    val canSend = (text.isNotBlank() || hasAttachments) && !isGenerating

    Column(modifier = modifier) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isGenerating,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (supportImage) {
                IconButton(
                    onClick = onOpenCamera,
                    enabled = !isGenerating,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PhotoCamera,
                        contentDescription = stringResource(R.string.attachment_camera),
                    )
                }
                IconButton(
                    onClick = {
                        pickMedia.launch(
                            PickVisualMediaRequest(PickVisualMedia.ImageOnly),
                        )
                    },
                    enabled = !isGenerating,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Photo,
                        contentDescription = stringResource(R.string.attachment_photo_picker),
                    )
                }
            }
            if (supportAudio) {
                IconButton(
                    onClick = onOpenAudioRecorder,
                    enabled = !isGenerating && audioButtonEnabled,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = stringResource(
                            if (audioButtonEnabled) R.string.attachment_audio
                            else R.string.attachment_audio_disabled
                        ),
                    )
                }
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { if (isGenerating) onStop() else onSend() },
                enabled = isGenerating || canSend,
            ) {
                Icon(
                    imageVector = if (isGenerating) Icons.Default.Stop
                    else Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(
                        if (isGenerating) R.string.btn_stop else R.string.btn_send
                    ),
                )
            }
        }
    }
}

/** Kept internal — ThumbnailStrip also uses it. */
internal val InputBarHorizontalPadding = 12.dp

/**
 * Stateful wrapper for `ChatScreen` — holds the text state locally and wires
 * ChatViewModel callbacks. Separated from the stateless `MultimodalInputBar`
 * to keep the latter testable (AC-9 relies on pure input props).
 */
@Composable
fun rememberMultimodalInputText(): androidx.compose.runtime.MutableState<String> =
    remember { mutableStateOf("") }
