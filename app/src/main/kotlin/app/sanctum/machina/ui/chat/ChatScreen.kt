package app.sanctum.machina.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sanctum.machina.R
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.ui.SanctumIcons
import app.sanctum.machina.ui.theme.SanctumIncognitoTheme
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onNavigateToPersistent: (Long) -> Unit,
    viewModel: ChatViewModel = hiltViewModel(),
) {
    // One-shot nav event consumer (Task 8 emits NavigateToPersistent after the
    // Draft→Persistent commit). Host translates to a NavController transition.
    LaunchedEffect(viewModel) {
        viewModel.navigation.collect { event ->
            when (event) {
                is ChatNavigationEvent.NavigateToPersistent -> onNavigateToPersistent(event.chatId)
            }
        }
    }

    val body: @Composable () -> Unit = { ChatScreenBody(onBack = onBack, viewModel = viewModel) }
    if (viewModel.identity is ChatIdentity.Quick) {
        SanctumIncognitoTheme { body() }
    } else {
        body()
    }
}

@Composable
private fun ChatScreenBody(
    onBack: () -> Unit,
    viewModel: ChatViewModel,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val attachments by viewModel.attachments.collectAsStateWithLifecycle()
    val modelCaps by viewModel.modelCaps.collectAsStateWithLifecycle()
    val topAppBarState by viewModel.topAppBarState.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(viewModel, resources) {
        viewModel.snackbar.collect { stringRes ->
            snackbarHostState.showSnackbar(resources.getString(stringRes))
        }
    }

    val reinitInProgress by viewModel.reinitInProgress.collectAsStateWithLifecycle()
    var showSettingsSheet by rememberSaveable { mutableStateOf(false) }
    val isQuick = viewModel.identity is ChatIdentity.Quick

    when (val state = uiState) {
        ChatUiState.Loading -> LoadingContent()
        is ChatUiState.Failed -> FailedContent(rawCause = state.rawCause, onBack = onBack)
        is ChatUiState.Ready ->
            ReadyContent(
                topAppBarState = topAppBarState,
                isQuickMode = isQuick,
                reinitInProgress = reinitInProgress,
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
                onLoadModel = viewModel::loadModel,
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
    topAppBarState: TopAppBarState,
    isQuickMode: Boolean,
    reinitInProgress: Boolean,
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
    onLoadModel: (String) -> Unit,
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
    // Send is disabled whenever the TopAppBar reports the engine isn't usable
    // (Loading / Failed) — the Send→runInference path requires a Ready engine,
    // and reinitInProgress blocks any user action during heavy apply.
    val sendGated = isGenerating ||
        topAppBarState is TopAppBarState.Loading ||
        topAppBarState is TopAppBarState.Failed ||
        reinitInProgress
    val inputState = MultimodalInputState(
        text = text,
        hasAttachments = attachments.isNotEmpty(),
        isGenerating = sendGated,
        supportImage = modelCaps.supportImage,
        supportAudio = modelCaps.supportAudio,
        audioButtonEnabled = !hasAudioAttachment,
    )

    // Settings gate (Phase-3 debt 1): the heavy-apply path teardowns/initializes
    // the engine directly; it must only run when the engine is idle-Ready and
    // no reinit is already in flight. Disabling the button whenever the UI is
    // not in Ready(isGenerating=false) or a reinit is running makes the tech-
    // spec Decision 3 race unreachable — `lifecycleMutex` is free at tap time.
    val settingsEnabled = !isGenerating && !reinitInProgress

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    ChatTopAppBarTitle(
                        state = topAppBarState,
                        isQuickMode = isQuickMode,
                        onLoadClicked = onLoadModel,
                        onModelPicked = onLoadModel,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.btn_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettings, enabled = settingsEnabled) {
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // AC-U4: lift the input bar above the IME. Depends on
                // `WindowCompat.setDecorFitsSystemWindows(window, false)`
                // already set by MainActivity.
                .imePadding(),
        ) {
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
 * Renders the TopAppBar title region per [TopAppBarState] (AC-U5–U7, AC-E3/E3b).
 *
 * Stateless except for the `DropdownMenu` expansion and cross-model confirm dialog, both of
 * which are purely visual — business logic (`warmupCoordinator.cancelAndRestart`) lives in
 * [ChatViewModel]. [onModelPicked] fires only after the user confirms the cross-model dialog
 * (or immediately if they picked the currently-active model, which is a dropdown dismiss).
 */
@Composable
private fun ChatTopAppBarTitle(
    state: TopAppBarState,
    isQuickMode: Boolean,
    onLoadClicked: (String) -> Unit,
    onModelPicked: (String) -> Unit,
) {
    if (isQuickMode) {
        QuickIncognitoTitle(state = state)
        return
    }
    when (state) {
        is TopAppBarState.Draft -> DraftModelPicker(
            models = state.models,
            currentModelId = state.currentModelId,
            onModelPicked = onModelPicked,
        )
        is TopAppBarState.Loading -> LoadingTitle()
        is TopAppBarState.Failed -> FailedLoadButton(modelId = state.modelId, onLoadClicked = onLoadClicked)
        is TopAppBarState.Ready -> ReadyTitle(modelName = state.modelName)
    }
}

@Composable
private fun QuickIncognitoTitle(state: TopAppBarState) {
    val modelName = when (state) {
        is TopAppBarState.Ready -> state.modelName
        else -> null
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = SanctumIcons.IconEyeOff,
                contentDescription = stringResource(R.string.chat_topappbar_incognito_desc),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = modelName ?: stringResource(R.string.chat_topappbar_quick_subtitle),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = stringResource(R.string.chat_topappbar_quick_subtitle),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyTitle(modelName: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color = MaterialTheme.colorScheme.primary)
        Text(text = modelName)
    }
}

@Composable
private fun LoadingTitle() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        Text(
            text = stringResource(R.string.chat_topappbar_reloading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * «Загрузить» button for the Failed state (AC-U6, AC-E3). `modelId` should never be empty by
 * construction — [ChatViewModel.deriveTopAppBarState] only emits Failed for Quick/Persistent
 * chats that have a pinned `_chatModelId` or a resolvable `activeModelId`. The `enabled` guard
 * is a defensive no-op to prevent an unclickable button if a future refactor ever routes an
 * empty-id Failed through here.
 */
@Composable
private fun FailedLoadButton(modelId: String, onLoadClicked: (String) -> Unit) {
    OutlinedButton(
        onClick = { onLoadClicked(modelId) },
        enabled = modelId.isNotEmpty(),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
    ) {
        Icon(
            imageVector = SanctumIcons.IconWarn,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = stringResource(R.string.chat_topappbar_load_action),
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun DraftModelPicker(
    models: List<ModelEntry>,
    currentModelId: String,
    onModelPicked: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingModelId by remember { mutableStateOf<String?>(null) }
    val currentName = models.firstOrNull { it.model.modelId == currentModelId }?.model?.name
        ?: currentModelId.ifEmpty { stringResource(R.string.chat_topappbar_pick_model_desc) }

    TextButton(onClick = { expanded = true }, enabled = models.isNotEmpty()) {
        Text(text = currentName)
        Icon(
            imageVector = SanctumIcons.IconChevronDown,
            contentDescription = stringResource(R.string.chat_topappbar_pick_model_desc),
            modifier = Modifier.size(18.dp),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        for (entry in models) {
            DropdownMenuItem(
                text = { Text(entry.model.name) },
                onClick = {
                    expanded = false
                    val picked = entry.model.modelId
                    if (picked == currentModelId) return@DropdownMenuItem
                    pendingModelId = picked
                },
                leadingIcon = if (entry.model.modelId == currentModelId) {
                    { Icon(SanctumIcons.IconCheck, contentDescription = null, modifier = Modifier.size(16.dp)) }
                } else null,
            )
        }
    }
    pendingModelId?.let { picked ->
        AlertDialog(
            onDismissRequest = { pendingModelId = null },
            title = { Text(stringResource(R.string.chat_topappbar_cross_model_title)) },
            text = { Text(stringResource(R.string.chat_topappbar_cross_model_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingModelId = null
                    onModelPicked(picked)
                }) {
                    Text(stringResource(R.string.chat_topappbar_cross_model_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingModelId = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color),
    )
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
