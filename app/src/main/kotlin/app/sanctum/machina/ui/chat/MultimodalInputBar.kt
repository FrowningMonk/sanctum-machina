package app.sanctum.machina.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
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
 * Immutable snapshot driving `MultimodalInputBar` rendering. Grouped so the
 * Composable signature stays narrow as tasks 8/9 add more state (camera
 * sheet visibility, audio recorder state, etc.).
 */
@Immutable
data class MultimodalInputState(
    val text: String,
    val hasAttachments: Boolean,
    val isGenerating: Boolean,
    val supportImage: Boolean,
    val supportAudio: Boolean,
    val audioButtonEnabled: Boolean,
)

/**
 * Stable callback holder — `remember { }`ed once per `ChatScreen` so the
 * `MultimodalInputBar` Composable doesn't recompose every time a lambda is
 * re-allocated at the call site.
 */
@Stable
class MultimodalInputCallbacks(
    val onTextChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onStop: () -> Unit,
    val onPickImages: (List<Uri>) -> Unit,
    val onOpenCamera: () -> Unit,
    val onOpenAudioRecorder: () -> Unit,
)

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
    state: MultimodalInputState,
    callbacks: MultimodalInputCallbacks,
    modifier: Modifier = Modifier,
) {
    val pickMedia = rememberLauncherForActivityResult(
        // API 31+: PickMultipleVisualMedia falls back to system picker on older shells;
        // maxItems acts as a hint — ChatViewModel.addImages clips authoritatively (AC-10).
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_PICK),
    ) { uris -> if (uris.isNotEmpty()) callbacks.onPickImages(uris) }

    val canSend = (state.text.isNotBlank() || state.hasAttachments) && !state.isGenerating

    Column(modifier = modifier) {
        OutlinedTextField(
            value = state.text,
            onValueChange = callbacks.onTextChange,
            placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isGenerating,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.supportImage) {
                IconButton(
                    onClick = callbacks.onOpenCamera,
                    enabled = !state.isGenerating,
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
                    enabled = !state.isGenerating,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Photo,
                        contentDescription = stringResource(R.string.attachment_photo_picker),
                    )
                }
            }
            if (state.supportAudio) {
                IconButton(
                    onClick = callbacks.onOpenAudioRecorder,
                    enabled = !state.isGenerating && state.audioButtonEnabled,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Mic,
                        contentDescription = stringResource(
                            if (state.audioButtonEnabled) R.string.attachment_audio
                            else R.string.attachment_audio_disabled
                        ),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = { if (state.isGenerating) callbacks.onStop() else callbacks.onSend() },
                enabled = state.isGenerating || canSend,
            ) {
                Icon(
                    imageVector = if (state.isGenerating) Icons.Default.Stop
                    else Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(
                        if (state.isGenerating) R.string.btn_stop else R.string.btn_send
                    ),
                )
            }
        }
    }
}
