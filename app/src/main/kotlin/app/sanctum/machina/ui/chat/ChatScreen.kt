package app.sanctum.machina.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sanctum.machina.R
import kotlinx.coroutines.launch

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
    val resources = LocalResources.current
    LaunchedEffect(viewModel, resources) {
        viewModel.snackbar.collect { stringRes ->
            snackbarHostState.showSnackbar(resources.getString(stringRes))
        }
    }

    val reinitInProgress by viewModel.reinitInProgress.collectAsStateWithLifecycle()
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }

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
                onReset = viewModel::resetConversation,
                onSettings = { showSettingsSheet = true },
                onBack = onBack,
                onImageCaptured = viewModel::addImageBitmap,
                onCameraError = viewModel::reportCameraError,
                onAudioCaptured = viewModel::addAudio,
                onAudioError = viewModel::reportAudioError,
            )
    }

    if (showSettingsSheet && uiState is ChatUiState.Ready) {
        InferenceSettingsBottomSheet(
            viewModel = viewModel,
            supportThinking = modelCaps.supportThinking,
            onDismiss = { showSettingsSheet = false },
        )
    }

    if (reinitInProgress) {
        ReinitProgressDialog()
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
    onPickImages: (List<Uri>) -> Unit,
    onRemoveAttachment: (Int) -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    onImageCaptured: (Bitmap) -> Unit,
    onCameraError: (String, Throwable?) -> Unit,
    onAudioCaptured: (ByteArray, Long) -> Unit,
    onAudioError: (String, Throwable?) -> Unit,
) {
    // rememberSaveable survives rotation / process death restore so a half-typed
    // prompt isn't lost on configuration change.
    var text by rememberSaveable { mutableStateOf("") }
    // rememberSaveable so a rotation mid-capture doesn't silently dismiss the
    // camera sheet — composition rebuilds with the sheet still open and
    // CameraX rebinds to the new lifecycle (R8). Same rationale applies to
    // the audio sheet, though AC-19 ON_PAUSE closes it on backgrounding.
    var showCameraSheet by rememberSaveable { mutableStateOf(false) }
    var showAudioSheet by rememberSaveable { mutableStateOf(false) }
    val hasAudioAttachment = attachments.any { it is Attachment.Audio }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraDeniedMsg = stringResource(R.string.permission_camera_denied)
    val audioDeniedMsg = stringResource(R.string.permission_audio_denied)
    val openSettingsLabel = stringResource(R.string.permission_open_settings)

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
            onOpenCamera = { showCameraSheet = true },
            onOpenAudioRecorder = { showAudioSheet = true },
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
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings, enabled = !isGenerating) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = stringResource(R.string.chat_action_settings),
                        )
                    }
                    IconButton(onClick = onReset, enabled = !isGenerating) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = stringResource(R.string.chat_action_reset),
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
                supportThinking = modelCaps.supportThinking,
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

    if (showCameraSheet) {
        CameraBottomSheet(
            onDismiss = { showCameraSheet = false },
            onImageCaptured = onImageCaptured,
            onCameraError = onCameraError,
            onPermissionDenied = { permanent ->
                scope.launch {
                    if (permanent) {
                        val result = snackbarHostState.showSnackbar(
                            message = cameraDeniedMsg,
                            actionLabel = openSettingsLabel,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            context.openAppSettings()
                        }
                    } else {
                        snackbarHostState.showSnackbar(cameraDeniedMsg)
                    }
                }
            },
        )
    }

    if (showAudioSheet) {
        AudioRecorderBottomSheet(
            onDismiss = { showAudioSheet = false },
            onSaveAudio = onAudioCaptured,
            onAudioError = onAudioError,
            onPermissionDenied = { permanent ->
                scope.launch {
                    if (permanent) {
                        val result = snackbarHostState.showSnackbar(
                            message = audioDeniedMsg,
                            actionLabel = openSettingsLabel,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            context.openAppSettings()
                        }
                    } else {
                        snackbarHostState.showSnackbar(audioDeniedMsg)
                    }
                }
            },
        )
    }
}

/**
 * Launches the system "App info" screen so the user can re-enable a
 * permission they permanently denied. `FLAG_ACTIVITY_NEW_TASK` is required
 * because `startActivity` may be called from a non-Activity context.
 *
 * Wrapped in `runCatching` because a handful of locked-down OEM builds
 * (enterprise MDM, kiosk ROMs) don't resolve `ACTION_APPLICATION_DETAILS_SETTINGS`
 * and would otherwise crash with `ActivityNotFoundException`. Silent
 * swallow is acceptable here — the snackbar message already communicates
 * the permission state; the user retains manual access to system settings.
 */
private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
}

@Composable
private fun MessageList(
    messages: List<Message>,
    supportThinking: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // AC-8 autoscroll — single combined effect keyed on list size and the
    // growing length of BOTH the last message's body and its thinking
    // block so streaming reasoning also re-scrolls. `scrollOffset =
    // Int.MAX_VALUE / 2` lands the viewport at the bottom of the last
    // item, which keeps newly-emitted tokens visible even when the
    // assistant bubble grows past the viewport height — plain
    // `animateScrollToItem(lastIndex)` would anchor the top of the item
    // and clip the bottom during long streams.
    val lastTextLen = messages.lastOrNull()?.text?.length ?: 0
    val lastThinkingLen = messages.lastOrNull()?.thinkingText?.length ?: 0
    LaunchedEffect(messages.size, lastTextLen, lastThinkingLen) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(
                index = messages.lastIndex,
                scrollOffset = Int.MAX_VALUE / 2,
            )
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages) { message ->
            MessageBubble(message = message, supportThinking = supportThinking)
        }
    }
}
