package app.sanctum.machina.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.derivedStateOf
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
import app.sanctum.machina.data.Citation
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
    val engineReady by viewModel.engineReady.collectAsStateWithLifecycle()

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

    // Loading is rendered through ReadyContent with the Send gate disabled rather
    // than via a separate full-screen overlay: the cross-model-reinit UX (Task 18
    // B2) needs the TopAppBar Loading chip to stay visible on top of the chat
    // history / Draft model picker, not be hidden behind a centred spinner. The
    // sendGated check inside ReadyContent already blocks sends whenever
    // topAppBarState is Loading/Failed.
    val failed = uiState as? ChatUiState.Failed
    if (failed != null) {
        FailedContent(rawCause = failed.rawCause, onBack = onBack)
    } else {
        val isGenerating = (uiState as? ChatUiState.Ready)?.isGenerating == true
        ReadyContent(
            topAppBarState = topAppBarState,
            engineReady = engineReady,
            isQuickMode = isQuick,
            reinitInProgress = reinitInProgress,
            messages = messages,
            attachments = attachments,
            modelCaps = modelCaps,
            isGenerating = isGenerating,
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
    engineReady: Boolean,
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
    // Phase 4 Task 12 — single shared modal state for citation chips across
    // every assistant bubble (T12 § Modal state hoisting). Not
    // rememberSaveable: `Citation` is not Parcelable and the user can re-tap
    // a chip after restore; cheaper to drop selection than to plumb a Saver.
    var selectedCitation by remember { mutableStateOf<Citation?>(null) }
    // Sticky-to-bottom state — hoisted in ReadyContent (D10) so the
    // onSend callback below can reset it before forwarding to the VM,
    // ensuring the autoscroll effect re-fires unconditionally after a
    // user send (Free-scroll AC item 5). Intentionally NOT
    // rememberSaveable: rotation / process recreate interrupts the
    // active stream, so a fresh pinned-to-bottom posture is the
    // correct reset (task `Edge cases`).
    var userScrolledAway by remember { mutableStateOf(false) }
    val hasAudioAttachment = attachments.any { it is Attachment.Audio }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cameraDeniedMsg = stringResource(R.string.permission_camera_denied)
    val audioDeniedMsg = stringResource(R.string.permission_audio_denied)
    val openSettingsLabel = stringResource(R.string.permission_open_settings)

    val sendRef = rememberUpdatedState(onSend)
    val stopRef = rememberUpdatedState(onStop)
    val pickImagesRef = rememberUpdatedState(onPickImages)
    // `callbacks` below is built with `remember { ... }` once per composition,
    // so any captured value goes stale across recompositions. The send-reset
    // setter must reach `userScrolledAway` reliably, so route it through
    // `rememberUpdatedState` exactly like sendRef/stopRef above.
    val onUserScrolledAwayChange: (Boolean) -> Unit = { userScrolledAway = it }
    val resetScrolledAwayRef = rememberUpdatedState(onUserScrolledAwayChange)
    val callbacks = remember {
        MultimodalInputCallbacks(
            onTextChange = { text = it },
            onSend = {
                resetScrolledAwayRef.value(false)
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

    // Settings/Reset gate (Phase-3 debt 1 + Phase-3.6 Bug 2 fix): the heavy-
    // apply path teardowns/initializes the engine directly; it must only run
    // when the engine is Ready and no reinit is in flight, so `lifecycleMutex`
    // is free at tap time (tech-spec Decision 3 race unreachable). The
    // readiness signal is `viewModel.engineReady` rather than
    // `topAppBarState is TopAppBarState.Ready` because the Draft branch of
    // `deriveTopAppBarState` keeps returning `TopAppBarState.Draft` even after
    // warmup completes — its model picker dropdown lives on that state.
    // `engineReady` is the orthogonal boolean that flips true for Draft and
    // Persistent alike once the entry hits Ready and warmup is no longer in
    // flight (tech-spec Decision 6).
    val settingsEnabled = engineReady && !isGenerating && !reinitInProgress

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
                    IconButton(onClick = onReset, enabled = engineReady && !isGenerating) {
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
                // Tell imePadding() that Scaffold's innerPadding already accounts for the
                // navigation-bar inset — without this the bottom gap equals IME + nav-bar
                // instead of max(IME, nav-bar), leaving a visible empty strip under the
                // input panel when the keyboard opens.
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            MessageList(
                messages = messages,
                supportThinking = modelCaps.supportThinking,
                userScrolledAway = userScrolledAway,
                onUserScrolledAwayChange = onUserScrolledAwayChange,
                onCitationClick = { selectedCitation = it },
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

    // Phase 4 Task 12 — citation detail sheet, shared by every bubble's chip-strip.
    CitationModal(
        citation = selectedCitation,
        onDismiss = { selectedCitation = null },
    )

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
        QuickIncognitoTitle(state = state, onLoadClicked = onLoadClicked)
        return
    }
    when (state) {
        is TopAppBarState.Draft -> DraftModelPicker(
            models = state.models,
            currentModelId = state.currentModelId,
            projectName = state.projectName,
            onModelPicked = onModelPicked,
        )
        is TopAppBarState.Loading -> LoadingTitle(modelName = state.modelName)
        is TopAppBarState.Failed -> FailedLoadButton(modelId = state.modelId, onLoadClicked = onLoadClicked)
        is TopAppBarState.Ready -> ReadyTitle(
            modelName = state.modelName,
            projectName = state.projectName,
            chatTitle = state.chatTitle,
        )
    }
}

@Composable
private fun QuickIncognitoTitle(
    state: TopAppBarState,
    onLoadClicked: (String) -> Unit,
) {
    // Phase 3.6 Task 13: branch by state so the user gets the same warmup
    // signal Persistent has (`LoadingTitle`). Earlier this composable
    // collapsed every non-Ready state to «Быстрый чат», hiding the engine
    // is-loading state behind a static label.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            is TopAppBarState.Loading -> IncognitoLoadingRow(modelName = state.modelName)
            is TopAppBarState.Failed -> IncognitoFailedRow(
                modelId = state.modelId,
                onLoadClicked = onLoadClicked,
            )
            is TopAppBarState.Ready -> IncognitoReadyRow(modelName = state.modelName)
            // Draft is unreachable for Quick (deriveTopAppBarState routes
            // ChatIdentity.Quick through the Ready/Loading/Failed branch),
            // but keep a defensive fallback so a future refactor that
            // accidentally lands Draft here doesn't crash.
            is TopAppBarState.Draft -> IncognitoReadyRow(modelName = null)
        }
        Text(
            text = stringResource(R.string.chat_topappbar_quick_subtitle),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IncognitoReadyRow(modelName: String?) {
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
}

@Composable
private fun IncognitoLoadingRow(modelName: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        val text = if (modelName != null) {
            stringResource(R.string.chat_topappbar_loading_model, modelName)
        } else {
            stringResource(R.string.chat_topappbar_reloading)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IncognitoFailedRow(modelId: String, onLoadClicked: (String) -> Unit) {
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
        FailedLoadButton(modelId = modelId, onLoadClicked = onLoadClicked)
    }
}

@Composable
private fun ReadyTitle(modelName: String, projectName: String?, chatTitle: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color = MaterialTheme.colorScheme.primary)
        // Phase 4 Task 11 cosmetic: project chats surface «{project} / {chat}» so the user
        // sees they are in a RAG-augmented conversation. Non-project chats fall back to the
        // Phase-3 model-name title verbatim.
        val title = if (projectName != null) {
            stringResource(
                R.string.chat_project_title_format,
                projectName,
                chatTitle.orEmpty(),
            )
        } else {
            modelName
        }
        Text(text = title)
    }
}

@Composable
private fun LoadingTitle(modelName: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        // Task 18 B2-UX: when the target model is resolvable, render
        // «Загружаю {name}…» so the user can tell A-warming from B-warming
        // during cross-model switches. Falls back to the Phase-3 generic
        // "reloading" copy only when the allowlist has not populated yet
        // (cold start, empty `registry.models`).
        val text = if (modelName != null) {
            stringResource(R.string.chat_topappbar_loading_model, modelName)
        } else {
            stringResource(R.string.chat_topappbar_reloading)
        }
        Text(
            text = text,
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
    projectName: String?,
    onModelPicked: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingModelId by remember { mutableStateOf<String?>(null) }
    val currentName = models.firstOrNull { it.model.modelId == currentModelId }?.model?.name
        ?: currentModelId.ifEmpty { stringResource(R.string.chat_topappbar_pick_model_desc) }

    // Phase 4 Task 19: surface «{project} / Новый чат» above the model-picker
    // button when the draft was opened from a project surface. The picker
    // itself stays unchanged — the user can still pick any downloaded model
    // and the project linkage is preserved via the `projectId` nav-arg.
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (projectName != null) {
            Text(
                text = stringResource(
                    R.string.chat_project_title_format,
                    projectName,
                    stringResource(R.string.drawer_new_chat),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
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
    userScrolledAway: Boolean,
    onUserScrolledAwayChange: (Boolean) -> Unit,
    onCitationClick: (Citation) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    // `isAtBottom` reflects whether the last item is fully visible: its
    // index matches the list's last index AND its `offset + size` fits
    // inside `viewportEndOffset`. Reads only from `listState.layoutInfo`,
    // a snapshot state — so the `derivedStateOf` wrapper only invalidates
    // when layoutInfo actually shifts, not on every scroll frame, and the
    // closure has nothing stale to capture (note: `totalItemsCount - 1` is
    // equivalent to `messages.lastIndex` since the LazyColumn renders one
    // item per message; the symmetric alternative `remember(listState,
    // messages) { derivedStateOf { ... } }` would re-allocate the derived
    // state on every list mutation, which streaming makes per-token —
    // worse than substituting the equivalent layoutInfo read. If this
    // LazyColumn ever grows non-message rows (separators, date headers,
    // typing indicators), revisit: the 1:1 assumption would break.).
    // Empty/short lists naturally report `true` (last visible item ==
    // last index, content shorter than viewport).
    val isAtBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null ||
                (last.index == info.totalItemsCount - 1 &&
                    last.offset + last.size <= info.viewportEndOffset)
        }
    }
    // Detect "user took control": a touch-driven DragInteraction.Start
    // while the list is not pinned to the bottom flips the sticky flag
    // off (D7). Programmatic `animateScrollToItem` does not emit
    // DragInteraction events, so the autoscroll effect below and the
    // FAB onClick cannot self-trigger this flag.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start && !isAtBottom) {
                onUserScrolledAwayChange(true)
            }
        }
    }
    // AC-8 autoscroll — single combined effect keyed on list size and the
    // growing length of BOTH the last message's body and its thinking
    // block so streaming reasoning also re-scrolls. `scrollOffset =
    // Int.MAX_VALUE / 2` lands the viewport at the bottom of the last
    // item, which keeps newly-emitted tokens visible even when the
    // assistant bubble grows past the viewport height — plain
    // `animateScrollToItem(lastIndex)` would anchor the top of the item
    // and clip the bottom during long streams. The `!userScrolledAway ||
    // isAtBottom` guard makes the autoscroll sticky-to-bottom: if the
    // user is reading mid-list we leave the viewport alone; if they're
    // already pinned to the bottom we keep following.
    val lastTextLen = messages.lastOrNull()?.text?.length ?: 0
    val lastThinkingLen = messages.lastOrNull()?.thinkingText?.length ?: 0
    LaunchedEffect(messages.size, lastTextLen, lastThinkingLen) {
        if (messages.isNotEmpty() && (!userScrolledAway || isAtBottom)) {
            listState.animateScrollToItem(
                index = messages.lastIndex,
                scrollOffset = Int.MAX_VALUE / 2,
            )
        }
    }
    // `fillMaxSize` (not `fillMaxWidth`) is mandatory here: the outer
    // weight slot supplies the height, and FAB alignment to BottomEnd
    // requires the Box to actually occupy that height — otherwise it
    // would collapse to the LazyColumn's content height and the FAB
    // would sit above the input bar instead of over the message list.
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { message ->
                MessageBubble(
                    message = message,
                    supportThinking = supportThinking,
                    onCitationClick = onCitationClick,
                )
            }
        }
        AnimatedVisibility(
            visible = userScrolledAway && !isAtBottom,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    coroutineScope.launch {
                        listState.animateScrollToItem(
                            index = messages.lastIndex,
                            scrollOffset = Int.MAX_VALUE / 2,
                        )
                        onUserScrolledAwayChange(false)
                    }
                },
            ) {
                Icon(
                    imageVector = SanctumIcons.IconChevronDown,
                    contentDescription = stringResource(R.string.chat_scroll_to_bottom),
                )
            }
        }
    }
}
