package app.sanctum.machina.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sanctum.machina.R

@Composable
fun ChatScreen(
    modelName: String,
    onBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val modelCaps by viewModel.modelCaps.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.snackbar.collect { stringRes ->
            snackbarHostState.showSnackbar(context.getString(stringRes))
        }
    }

    when (val state = uiState) {
        ChatUiState.Loading -> LoadingContent()
        is ChatUiState.Failed -> FailedContent(rawCause = state.rawCause, onBack = onBack)
        is ChatUiState.Ready ->
            ReadyContent(
                modelName = modelName,
                messages = messages,
                attachments = attachments,
                modelCaps = modelCaps,
                isGenerating = state.isGenerating,
                snackbarHostState = snackbarHostState,
                onSend = viewModel::send,
                onPickImages = viewModel::addImages,
                onRemoveAttachment = viewModel::removeAttachment,
                onStop = viewModel::stop,
                onReset = viewModel::reset,
            )
    }
}

@Composable
private fun LoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = stringResource(R.string.chat_loading_model),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun FailedContent(rawCause: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(R.string.chat_load_failed_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = rawCause,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.btn_back))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadyContent(
    modelName: String,
    messages: List<Message>,
    attachments: List<Attachment>,
    modelCaps: ModelCapabilities,
    isGenerating: Boolean,
    snackbarHostState: SnackbarHostState,
    onSend: (String) -> Unit,
    onPickImages: (List<android.net.Uri>) -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
) {
    // rememberSaveable survives rotation / process death restore so a half-typed
    // prompt isn't lost on configuration change.
    var text by rememberSaveable { mutableStateOf("") }
    val hasAudioAttachment = attachments.any { it is Attachment.Audio }

    // Capture callers via rememberUpdatedState so the remembered callbacks
    // holder sees the latest lambdas without being re-allocated each recomposition.
    // ChatViewModel.send() validates `normalized.isEmpty() && pending.isEmpty()`
    // and returns early, so no pre-check is needed here — the MultimodalInputBar
    // canSend gate is purely cosmetic for the button enabled-state.
    val sendRef = rememberUpdatedState(onSend)
    val stopRef = rememberUpdatedState(onStop)
    val pickImagesRef = rememberUpdatedState(onPickImages)
    val callbacks = remember {
        MultimodalInputCallbacks(
            onTextChange = { text = it },
            onSend = {
                sendRef.value(text)
                text = ""
            },
            onStop = { stopRef.value() },
            onPickImages = { uris -> pickImagesRef.value(uris) },
            onOpenCamera = { /* task 8 */ },
            onOpenAudioRecorder = { /* task 9 */ },
        )
    }
    val inputState = MultimodalInputState(
        text = text,
        hasAttachments = attachments.isNotEmpty(),
        isGenerating = isGenerating,
        supportImage = modelCaps.supportImage,
        supportAudio = modelCaps.supportAudio,
        audioButtonEnabled = !hasAudioAttachment,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(modelName) },
                actions = {
                    IconButton(onClick = onReset, enabled = !isGenerating) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.btn_reset),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            MessageList(
                messages = messages,
                modifier = Modifier.weight(1f),
            )
            if (attachments.isNotEmpty()) {
                ThumbnailStrip(
                    attachments = attachments,
                    onRemove = onRemoveAttachment,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            MultimodalInputBar(
                state = inputState,
                callbacks = callbacks,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MessageList(messages: List<Message>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0))
        }
    }
    LaunchedEffect(messages.lastOrNull()?.text?.length) {
        // Non-animated follow during streaming avoids main-thread jitter on every token.
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.lastIndex.coerceAtLeast(0))
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages) { message -> MessageBubble(message) }
    }
}

@Composable
private fun MessageBubble(message: Message) {
    val isUser = message.role == MessageRole.USER
    val bubbleColor =
        if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Column(
            modifier =
                Modifier.widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (isUser && message.attachments.isNotEmpty()) {
                MessageAttachmentsRow(attachments = message.attachments)
            }
            if (message.text.isNotEmpty() || message.interrupted) {
                val suffix =
                    if (message.interrupted) stringResource(R.string.chat_message_interrupted_suffix)
                    else ""
                Text(
                    text = message.text + suffix,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            message.footer?.let { footer ->
                Text(
                    text = footer,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val MessageAttachmentSize = 56.dp
private const val MESSAGE_ATTACHMENTS_PER_ROW = 5

/**
 * FlowRow wraps to new rows — 10 × 56dp tiles with 4dp spacing = 2 rows of
 * 5 inside the 296dp bubble content width (320dp − 24dp horizontal padding).
 * All attachments visible without internal scroll; LazyRow previously clipped
 * to the first 4 tiles which felt like data loss to the user.
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
