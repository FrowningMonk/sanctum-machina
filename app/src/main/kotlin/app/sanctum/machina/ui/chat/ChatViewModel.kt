package app.sanctum.machina.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.R
import app.sanctum.machina.core.common.pcmToWav
import app.sanctum.machina.core.common.wavToPcm
import app.sanctum.machina.core.data.ConfigKeys
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.data.SAMPLE_RATE
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.registry.ResetReason
import app.sanctum.machina.core.runtime.LlmModelHelper
import app.sanctum.machina.core.settings.AppSettingsRepository
import app.sanctum.machina.core.settings.proto.PerModelSettings
import app.sanctum.machina.data.ChatRepository
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.engine.WarmupCoordinator
import java.io.File
import java.util.UUID
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Ready(val isGenerating: Boolean) : ChatUiState
    data class Failed(val rawCause: String) : ChatUiState
}

/**
 * Renders the `ChatScreen` TopAppBar title region (AC-U5–U7, AC-E3/E3b).
 *
 * Driven by `combine(chatIdentity, registry.models, warmupCoordinator.isWarmupInProgress)` in
 * [ChatViewModel.topAppBarState]: [Loading] takes precedence whenever a warmup is in flight; in
 * Draft mode the user can pick any downloaded model from the dropdown; in Quick/Persistent the
 * variant is derived from the engine's init status for the pinned `modelId`.
 */
sealed class TopAppBarState {
    /** Draft mode — model picker. [models] contains only downloaded entries; [currentModelId]
     *  marks which one to check in the dropdown. */
    data class Draft(val models: List<ModelEntry>, val currentModelId: String) : TopAppBarState()

    /**
     * Warmup / reinit in flight — show spinner, Send disabled. [modelName] is
     * the human-readable `Model.name` of the target being warmed (or `null`
     * when the name cannot be resolved yet — e.g. during the very first cold
     * start before the allowlist loads); Task 18 B2-UX uses it to render
     * «Загружаю {modelName}…» so the user sees which model is being loaded
     * rather than a contextless spinner.
     */
    data class Loading(val modelId: String, val modelName: String? = null) : TopAppBarState()

    /** `ModelInitStatus.Idle` or `Failed` in a non-Draft chat — show «Загрузить» button. */
    data class Failed(val modelId: String) : TopAppBarState()

    /** Engine ready — show read-only chip with [modelName] (Model.name, human-readable). */
    data class Ready(val modelName: String) : TopAppBarState()
}

/**
 * Capability flags derived from the active [Model] when the engine reports
 * [ModelInitStatus.Ready]. Drives conditional visibility of camera/gallery/mic
 * in `MultimodalInputBar` (AC-18) and the thinking UI gate (AC-14).
 */
data class ModelCapabilities(
    val supportImage: Boolean = false,
    val supportAudio: Boolean = false,
    val supportThinking: Boolean = false,
)

private const val ROLE_USER = "user"
private const val ROLE_ASSISTANT = "assistant"

@HiltViewModel
class ChatViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: ModelRegistry,
    private val helper: LlmModelHelper,
    private val errorLog: ErrorLog,
    @ApplicationContext private val context: Context,
    private val imageDecoder: ImageDecoder,
    private val settingsRepository: AppSettingsRepository,
    private val chatRepository: ChatRepository,
    private val messageDao: MessageDao,
    private val chatDao: ChatDao,
    private val warmupCoordinator: WarmupCoordinator,
) : ViewModel() {

    // Eagerly resolved so a missing/mis-typed nav arg surfaces at construction
    // time rather than lazily on the first send() (AC-E4 requires the route to
    // be interpretable before any UI state transition).
    val identity: ChatIdentity = resolveIdentity(savedStateHandle)

    // Quick-mode override: when the Model Manager "Load" flow opens a chat,
    // the route carries `?modelId={id}` that pins the VM to that specific
    // model instead of whatever WarmupCoordinator warmed. Null for Draft and
    // for Quick without an explicit override.
    private val explicitModelId: String? = savedStateHandle[NAV_ARG_MODEL_ID]

    /**
     * Stable HF [Model.modelId] backing this chat. For Persistent this is a
     * one-shot read of `chat.model_id` from Room (immutable for the chat's
     * lifetime). For Quick/Draft it tracks [ModelRegistry.activeModelName]
     * reactively so the first time [app.sanctum.machina.engine.WarmupCoordinator]
     * publishes a Ready model the VM's engine observer fires.
     *
     * Quick may carry an explicit `modelId` nav arg (from the Model-Manager
     * "Load" flow, Task 11) — if present it pins the flow; otherwise the flow
     * is the pass-through of [ModelRegistry.activeModelName].
     */
    private val _chatModelId: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * Persistent-mode auto-resume target (Task 18 B4). Set during bootstrap
     * when the last persisted message for this chat is an unpaired USER row,
     * meaning Draft→Persistent handover committed the USER but the inference
     * dispatch died with the Draft VM. [observeFirstReadyThenResume] fires
     * once when the pinned model reaches Ready and clears [autoResumeAttempted]
     * so a subsequent Ready→Initializing→Ready flutter cannot double-dispatch.
     */
    private var autoResumeTarget: MessageEntity? = null
    private var autoResumeAttempted: Boolean = false

    /**
     * Decoded-attachment cache keyed by [MessageEntity.id] (Task 18 B1). A Room
     * flow re-emits the full message list on every mutation; without the cache
     * each emit would re-read the same `img_<uuid>.png` / `audio_<uuid>.wav`
     * payload from disk and re-decode it to a Bitmap/ByteArray. Declared BEFORE
     * [messages] so the `buildMessagesFlow` collector never observes a null
     * cache during eager subscription.
     *
     * Access is serialised by the single-threaded Room flow collector; cache
     * hits skip BitmapFactory entirely.
     */
    private val attachmentCache: MutableMap<Long, List<Attachment>> = HashMap()

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Quick / Draft history. Persistent mode exposes history through
    // [messages] backed by the combine below — `_messages` stays empty there.
    private val _messages = MutableStateFlow<List<Message>>(emptyList())

    /**
     * In-memory streaming ASSISTANT bubble for Persistent mode (D4). Cleared
     * atomically when Room first re-emits with the persisted ASSISTANT row so
     * the combine below never publishes a state where the same bubble appears
     * both as in-memory streaming and as a Room row.
     */
    private val _streamingMessage = MutableStateFlow<Message?>(null)

    /**
     * Single messages stream for the UI: Persistent combines Room-backed
     * [MessageEntity]s with [_streamingMessage]; Quick/Draft just mirror the
     * in-memory [_messages] list.
     *
     * Atomic handover at `done=true`: when the Room flow first emits a list
     * ending in an `ASSISTANT` row the combine lambda in [buildMessagesFlow]
     * suppresses [_streamingMessage] — no emission carries both
     * representations, so the UI never flickers a duplicate bubble. The
     * in-memory value may linger, but it cannot become visible until the
     * next user turn clears it via [send].
     */
    val messages: StateFlow<List<Message>> = buildMessagesFlow()

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    /**
     * Lazy Draft-mode staging directory. Created on the first addImage* /
     * addAudio call, so a Draft VM that sees no attachments leaves no
     * `.staging-*` orphans
     * behind — the startup sweep (Task 6 AC-A3) has nothing to clean up.
     *
     * Null in Quick / Persistent modes. Reset to null on successful commit in
     * [commitDraft] (the directory is renamed into `attachments/{chatId}/`
     * inside the transaction, so the old File handle is stale).
     */
    private var draftStagingDir: File? = null

    private val _modelCaps = MutableStateFlow(ModelCapabilities())
    val modelCaps: StateFlow<ModelCapabilities> = _modelCaps.asStateFlow()

    /**
     * Toggles the modal `ReinitProgressDialog` while a heavy-setting reinit
     * runs (D12/D21). UI must block input — accelerator teardown is
     * uncancellable.
     */
    private val _reinitInProgress = MutableStateFlow(false)
    val reinitInProgress: StateFlow<Boolean> = _reinitInProgress.asStateFlow()

    /**
     * Transient UI events carrying a `@StringRes` id — snackbar host collects
     * and renders. `replay = 0` + `extraBufferCapacity = 8` means tryEmit is
     * non-suspending and events survive brief UI rebinding.
     */
    private val _snackbar = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 8)
    val snackbar: SharedFlow<Int> = _snackbar.asSharedFlow()

    /**
     * One-shot navigation events. Extra-buffer=1 + `replay=0` means the
     * commit-then-navigate path never suspends; if no collector is attached
     * (e.g. the host composable has just started config change) the event is
     * coalesced by the buffer and consumed on resume.
     */
    private val _navigation = MutableSharedFlow<ChatNavigationEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val navigation: SharedFlow<ChatNavigationEvent> = _navigation.asSharedFlow()

    /**
     * Drives the `ChatScreen` TopAppBar title region (AC-U5–U7, AC-E3/E3b). Derived reactively
     * from [ChatIdentity], [ModelRegistry.models] and [WarmupCoordinator.isWarmupInProgress]:
     *
     *  - `warmupInProgress == true` → [TopAppBarState.Loading] regardless of identity — the
     *    cross-model reinit surface is the same spinner the initial warmup renders.
     *  - `ChatIdentity.Draft` → [TopAppBarState.Draft] with the downloaded-model list (filtered
     *    by [ModelDownloadStatusType.SUCCEEDED]) and `currentModelId` = pinned or active model id.
     *  - `ChatIdentity.Quick` / `ChatIdentity.Persistent` → resolve the entry for the pinned
     *    `_chatModelId` and map its `initStatus` to Ready / Loading / Failed. `Idle` and a
     *    missing entry both collapse to [TopAppBarState.Failed] so the user sees the
     *    «Загрузить» affordance (AC-U6, AC-E3).
     */
    val topAppBarState: StateFlow<TopAppBarState> = combine(
        registry.models,
        _chatModelId,
        warmupCoordinator.isWarmupInProgress,
        registry.activeModelName,
    ) { models, modelId, warmupInFlight, activeModelId ->
        deriveTopAppBarState(models, modelId, warmupInFlight, activeModelId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = TopAppBarState.Loading(modelId = "", modelName = null),
    )

    init {
        viewModelScope.launch { bootstrapChatModelId() }
        viewModelScope.launch { observeEngineState() }
    }

    fun send(text: String) {
        val normalized = text.trim()
        val pending = _attachments.value
        if (normalized.isEmpty() && pending.isEmpty()) return
        val state = _uiState.value
        if (state !is ChatUiState.Ready || state.isGenerating) return
        if (_reinitInProgress.value) return

        // Task 17 send-gate: a Draft-mode attachment may still be mid-flight to
        // the staging directory when the user taps Send. We must not commit a
        // chat referencing a file that does not exist yet — the rename inside
        // the Room transaction would either miss it (silent data loss) or the
        // caller would supply a stagedFilename that was never written.
        if (identity is ChatIdentity.Draft && pending.any { it.isStagingPending() }) {
            _snackbar.tryEmit(R.string.attachment_still_saving)
            return
        }

        when (val id = identity) {
            ChatIdentity.Draft -> commitDraft(normalized, pending)
            ChatIdentity.Quick -> runInferenceQuick(normalized, pending)
            is ChatIdentity.Persistent -> runInferencePersistent(id, normalized, pending)
        }
    }

    private fun Attachment.isStagingPending(): Boolean = when (this) {
        is Attachment.Image -> stagedFilename == null
        is Attachment.Audio -> stagedFilename == null
    }

    fun stop() {
        val model = currentReadyModel() ?: return
        helper.stopResponse(model)
        when (identity) {
            is ChatIdentity.Persistent -> {
                // Mark the streaming bubble as interrupted but keep it visible
                // until the next Room emission; there is no persisted ASSISTANT
                // yet (`done=true` did not fire) — the interrupted partial is
                // intentionally not saved, matching AC-R3.
                _streamingMessage.update { it?.copy(streaming = false, interrupted = true) }
            }
            ChatIdentity.Quick, ChatIdentity.Draft -> {
                updateLastAssistantInMemory { it.copy(streaming = false, interrupted = true) }
            }
        }
        _uiState.value = ChatUiState.Ready(isGenerating = false)
    }

    /**
     * Reset (↻) — clear UI history and engine context but keep the engine
     * loaded (D23). The effective system prompt (defaults ∪ override) is
     * passed back into `registry.resetConversation` so the next user turn
     * starts under the same system instruction the engine had on init.
     *
     * For Persistent mode this does NOT delete Room rows — it only resets
     * the engine KV cache and clears the in-memory streaming bubble. The
     * persisted history remains intact (user can still see past turns).
     */
    fun resetConversation() {
        viewModelScope.launch {
            val model = currentReadyModel() ?: return@launch
            val effective = effectiveSystemPrompt(model)
            registry.resetConversation(
                model.name,
                systemPrompt = effective,
                reason = ResetReason.USER,
            )
            _messages.value = emptyList()
            _streamingMessage.value = null
            _attachments.value = emptyList()
        }
    }

    /** Backwards-compat alias for the existing TopAppBar wiring. */
    fun reset() = resetConversation()

    /**
     * Triggers a cross-model reinit (AC-E3, AC-E3b). Delegates to
     * [WarmupCoordinator.cancelAndRestart] so the single-flight mutex inside the coordinator
     * serialises the handover — a rapid double-tap from the Draft dropdown or the «Загрузить»
     * button cannot interleave two `registry.initialize` calls against the same
     * `lifecycleMutex` (tech-spec Decision 3 race).
     *
     * Also re-pins [_chatModelId] to the new target so [observeEngineState] and
     * [deriveTopAppBarState] track model B instead of staying on model A after
     * the coordinator's single-engine release flips A → Idle. Without this
     * re-pin, `uiState` would freeze at `Loading` because the VM kept watching
     * the now-Idle prior model even after B reached Ready — the user had to
     * back out of the chat to bootstrap a new VM on B. For the Failed→retry
     * path the write is a no-op (same modelId).
     */
    fun loadModel(modelId: String) {
        _chatModelId.value = modelId
        warmupCoordinator.cancelAndRestart(modelId)
    }

    private fun deriveTopAppBarState(
        models: List<ModelEntry>,
        pinnedModelId: String?,
        warmupInFlight: Boolean,
        activeModelId: String?,
    ): TopAppBarState {
        val effectiveModelId = pinnedModelId ?: activeModelId ?: ""
        val effectiveModelName = models.firstOrNull { it.model.modelId == effectiveModelId }?.model?.name
        if (warmupInFlight) return TopAppBarState.Loading(effectiveModelId, effectiveModelName)
        return when (identity) {
            ChatIdentity.Draft -> TopAppBarState.Draft(
                models = models.filter {
                    it.downloadStatus.status == ModelDownloadStatusType.SUCCEEDED
                },
                currentModelId = effectiveModelId,
            )
            ChatIdentity.Quick, is ChatIdentity.Persistent -> {
                val entry = pinnedModelId?.let { id ->
                    models.firstOrNull { it.model.modelId == id }
                }
                when (entry?.initStatus) {
                    ModelInitStatus.Ready -> TopAppBarState.Ready(entry.model.name)
                    ModelInitStatus.Initializing ->
                        TopAppBarState.Loading(effectiveModelId, entry.model.name)
                    is ModelInitStatus.Failed -> TopAppBarState.Failed(effectiveModelId)
                    ModelInitStatus.Idle, null -> TopAppBarState.Failed(effectiveModelId)
                }
            }
        }
    }

    fun saveAndApplySettings(settings: PerModelSettings) {
        viewModelScope.launch {
            val modelId = _chatModelId.value ?: return@launch
            val model = currentReadyModel() ?: return@launch
            val current = model.configValues.toMap()
            val target = EffectiveConfig.merge(computeDefaults(model), settings)
            settingsRepository.savePerModelSettings(modelId, settings)
            dispatchByLevel(classifyApplyLevel(current, target))
        }
    }

    fun resetSettingsToDefaults() {
        viewModelScope.launch {
            val modelId = _chatModelId.value ?: return@launch
            val model = currentReadyModel() ?: return@launch
            val current = model.configValues.toMap()
            val defaults = computeDefaults(model)
            settingsRepository.resetPerModelSettings(modelId)
            dispatchByLevel(classifyApplyLevel(current, defaults))
        }
    }

    fun needsHeavyResetToDefaults(): Boolean {
        val model = currentReadyModel() ?: return false
        val current = model.configValues
        val defaults = computeDefaults(model)
        return classifyApplyLevel(current, defaults) == ApplyLevel.HEAVY
    }

    fun needsHeavyApply(target: PerModelSettings): Boolean {
        val model = currentReadyModel() ?: return false
        val current = model.configValues
        val merged = EffectiveConfig.merge(computeDefaults(model), target)
        return classifyApplyLevel(current, merged) == ApplyLevel.HEAVY
    }

    /** Observable snapshot of the persisted overrides for the active model. */
    fun observePerModelSettings(): kotlinx.coroutines.flow.Flow<PerModelSettings?> {
        val modelId = _chatModelId.value ?: return flowOf(null)
        return settingsRepository.observePerModelSettings(modelId)
    }

    fun currentEffectiveConfig(): Map<String, Any> =
        currentReadyModel()?.configValues ?: emptyMap()

    fun allowlistDefaults(): Map<String, Any> =
        currentReadyModel()?.let(::computeDefaults) ?: emptyMap()

    fun applyLightOverrides() {
        viewModelScope.launch {
            val modelId = _chatModelId.value ?: return@launch
            val model = currentReadyModel() ?: return@launch
            val overrides = settingsRepository.observePerModelSettings(modelId).first()
            val defaults = computeDefaults(model)
            val merged = EffectiveConfig.merge(defaults, overrides)
            model.configValues = merged
            // Sampler params (topK/topP/temperature) are baked into the
            // engine's `Conversation` at creation time (LiteRT-LM 0.10.0):
            // mutating `configValues` alone is a silent no-op until the
            // Conversation is recreated. registry.resetConversation re-reads
            // the merged values when constructing the new ConversationConfig.
            // UI history is NOT touched — Light tier is a sampler refresh,
            // not a context wipe.
            val effective = effectiveSystemPrompt(merged)
            registry.resetConversation(
                model.name,
                systemPrompt = effective,
                reason = ResetReason.LIGHT_OVERRIDE,
            )
        }
    }

    fun applySystemPromptAndReset() {
        viewModelScope.launch {
            val modelId = _chatModelId.value ?: return@launch
            val model = currentReadyModel() ?: return@launch
            val overrides = settingsRepository.observePerModelSettings(modelId).first()
            val defaults = computeDefaults(model)
            val merged = EffectiveConfig.merge(defaults, overrides)
            model.configValues = merged
            val effective = effectiveSystemPrompt(merged)
            registry.resetConversation(
                model.name,
                systemPrompt = effective,
                reason = ResetReason.SYSTEM_PROMPT,
            )
            _messages.value = emptyList()
            _streamingMessage.value = null
            _attachments.value = emptyList()
            _snackbar.tryEmit(R.string.settings_semilight_applied_snackbar)
        }
    }

    fun applyHeavySetting() {
        viewModelScope.launch {
            _reinitInProgress.value = true
            try {
                val modelId = _chatModelId.value ?: return@launch
                val priorModel = currentReadyModel()
                val state = _uiState.value
                if (priorModel != null && state is ChatUiState.Ready && state.isGenerating) {
                    helper.stopResponse(priorModel)
                    when (identity) {
                        is ChatIdentity.Persistent -> _streamingMessage.update {
                            it?.copy(streaming = false, interrupted = true)
                        }
                        else -> updateLastAssistantInMemory {
                            it.copy(streaming = false, interrupted = true)
                        }
                    }
                    _uiState.value = ChatUiState.Ready(isGenerating = false)
                }
                val rawModel = preInitModel() ?: return@launch
                val overrides = settingsRepository.observePerModelSettings(modelId).first()
                val defaults = computeDefaults(rawModel)
                rawModel.configValues = EffectiveConfig.merge(defaults, overrides)

                registry.cleanup(rawModel.name)
                registry.initialize(rawModel.name).fold(
                    onSuccess = {
                        // The reactive observer will push uiState back to Ready
                        // when registry.models emits Ready for this model — no
                        // direct assignment here avoids races with the observer.
                    },
                    onFailure = { e ->
                        val cause =
                            e.message?.takeIf { it.isNotBlank() }
                                ?: e::class.simpleName.orEmpty()
                        _uiState.value = ChatUiState.Failed(cause)
                        _snackbar.tryEmit(R.string.chat_load_failed_title)
                        viewModelScope.launch {
                            errorLog.e("inference-init", "heavy reinit failed", e)
                        }
                    },
                )
            } finally {
                _reinitInProgress.value = false
            }
        }
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val currentCount = _attachments.value.count { it is Attachment.Image }
            val initialRoom = (MAX_IMAGES - currentCount).coerceAtLeast(0)
            val toDecode = uris.take(initialRoom)
            val droppedPreDecode = uris.size > toDecode.size

            val decoded = mutableListOf<Attachment.Image>()
            val failed = mutableListOf<Uri>()
            for (uri in toDecode) {
                val bmp = runCatching { imageDecoder.decode(uri) }.getOrNull()
                if (bmp != null) decoded += Attachment.Image(bmp) else failed += uri
            }

            val admitted: List<Attachment.Image>
            var droppedByRace = false
            if (decoded.isNotEmpty()) {
                val accepted = mutableListOf<Attachment.Image>()
                _attachments.update { current ->
                    val room = (MAX_IMAGES - current.count { it is Attachment.Image })
                        .coerceAtLeast(0)
                    val taken = decoded.take(room)
                    droppedByRace = decoded.size > taken.size
                    accepted.clear()
                    accepted.addAll(taken)
                    current + taken
                }
                admitted = accepted.toList()
            } else {
                admitted = emptyList()
            }

            admitted.forEach { stageDraftAttachmentIfNeeded(it) }

            if (droppedPreDecode || droppedByRace) {
                _snackbar.tryEmit(R.string.attachment_max_images_reached)
            }
            if (failed.isNotEmpty()) {
                viewModelScope.launch {
                    for (uri in failed) errorLog.e("attachment-decode", "decode failed: $uri")
                }
            }
        }
    }

    fun addImageBitmap(bitmap: Bitmap) {
        val currentImageCount = _attachments.value.count { it is Attachment.Image }
        if (currentImageCount >= MAX_IMAGES) {
            _snackbar.tryEmit(R.string.attachment_max_images_reached)
            return
        }
        val image = Attachment.Image(downscaleIfOversized(bitmap))
        _attachments.update { it + image }
        stageDraftAttachmentIfNeeded(image)
    }

    fun removeAttachment(idx: Int) {
        val removed: Attachment? = run {
            val snapshot = _attachments.value
            if (idx < 0 || idx >= snapshot.size) null else snapshot[idx]
        }
        _attachments.update { list ->
            if (idx < 0 || idx >= list.size) list
            else list.toMutableList().also { it.removeAt(idx) }
        }
        if (removed != null) deleteStagedFileIfAny(removed)
    }

    fun addAudio(pcm: ByteArray, durationMs: Long) {
        val alreadyHasAudio = _attachments.value.any { it is Attachment.Audio }
        if (alreadyHasAudio) return
        val audio = Attachment.Audio(pcm, durationMs)
        _attachments.update { current ->
            if (current.any { it is Attachment.Audio }) current else current + audio
        }
        // Only stage if the new audio was admitted to the list (races above).
        if (_attachments.value.any { it.id == audio.id }) {
            stageDraftAttachmentIfNeeded(audio)
        }
    }

    /**
     * Draft-only: launch an IO coroutine to write [attachment] into
     * [draftStagingDir], then replace the in-memory instance with a copy
     * that carries the returned `stagedFilename`. In Quick / Persistent modes
     * this is a no-op — those paths never touch the staging area.
     *
     * On failure the attachment is removed from `_attachments` (so the user
     * can retry), an `attachment_save_failed` snackbar is emitted, and the
     * cause is logged under the `attachment-save` component.
     */
    private fun stageDraftAttachmentIfNeeded(attachment: Attachment) {
        if (identity !is ChatIdentity.Draft) return
        val dir = ensureDraftStagingDir()
        val filesDir = context.filesDir
        viewModelScope.launch {
            val result = runCatching {
                chatRepository.writeAttachmentStaging(dir, filesDir, attachment)
            }
            result.fold(
                onSuccess = { filename ->
                    _attachments.update { current ->
                        current.map { existing ->
                            if (existing.id != attachment.id) existing
                            else when (existing) {
                                is Attachment.Image -> Attachment.Image(
                                    bitmap = existing.bitmap,
                                    id = existing.id,
                                    stagedFilename = filename,
                                )
                                is Attachment.Audio -> Attachment.Audio(
                                    pcm = existing.pcm,
                                    durationMs = existing.durationMs,
                                    id = existing.id,
                                    stagedFilename = filename,
                                )
                            }
                        }
                    }
                },
                onFailure = { cause ->
                    _attachments.update { current -> current.filterNot { it.id == attachment.id } }
                    _snackbar.tryEmit(R.string.attachment_save_failed)
                    viewModelScope.launch {
                        errorLog.e(
                            "attachment-save",
                            "writeAttachmentStaging failed for id=${attachment.id}",
                            cause,
                        )
                    }
                },
            )
        }
    }

    private fun deleteStagedFileIfAny(attachment: Attachment) {
        if (identity !is ChatIdentity.Draft) return
        val dir = draftStagingDir ?: return
        val filename = when (attachment) {
            is Attachment.Image -> attachment.stagedFilename
            is Attachment.Audio -> attachment.stagedFilename
        } ?: return
        val filesDir = context.filesDir
        viewModelScope.launch {
            runCatching { chatRepository.deleteStagedAttachment(dir, filesDir, filename) }
                .onFailure { cause ->
                    errorLog.e(
                        "attachment-save",
                        "deleteStagedAttachment threw for filename=$filename",
                        cause,
                    )
                }
        }
    }

    private fun ensureDraftStagingDir(): File {
        val existing = draftStagingDir
        if (existing != null) return existing
        val created = File(
            context.filesDir,
            "attachments/.staging-${UUID.randomUUID()}",
        )
        draftStagingDir = created
        return created
    }

    fun reportCameraError(description: String, cause: Throwable?) {
        viewModelScope.launch { errorLog.e("camera", description, cause) }
        _snackbar.tryEmit(R.string.camera_init_failed)
    }

    fun reportAudioError(description: String, cause: Throwable?) {
        viewModelScope.launch { errorLog.e("audio", description, cause) }
        _snackbar.tryEmit(R.string.audio_record_init_failed)
    }

    override fun onCleared() {
        super.onCleared()
        // Decision 5 / AC-E6: do NOT call registry.cleanup here. The engine
        // lifecycle is owned by WarmupCoordinator; tearing it down on every
        // Back navigation would defeat background warmup.
    }

    // --- init helpers --------------------------------------------------------

    private fun resolveIdentity(savedStateHandle: SavedStateHandle): ChatIdentity {
        val chatId: Long? = savedStateHandle[NAV_ARG_CHAT_ID]
        if (chatId != null) return ChatIdentity.Persistent(chatId)
        val kind: String? = savedStateHandle[NAV_ARG_KIND]
        return if (kind == KIND_DRAFT) ChatIdentity.Draft else ChatIdentity.Quick
    }

    /**
     * Seeds `_chatModelId` and, for non-persistent modes, keeps it tracking
     * [ModelRegistry.activeModelName] reactively.
     *
     * - Persistent: one-shot Room lookup of `chat.model_id` — the chat row
     *   cannot switch models mid-session, so a StateFlow snapshot is enough.
     *   A missing row (chat deleted concurrently) leaves the flow null and
     *   the engine observer at Loading.
     * - Quick with explicit nav arg: pin to the caller-supplied modelId (the
     *   Model Manager "Load" flow, Task 11).
     * - Quick / Draft without pin: collect `registry.activeModelName` — the
     *   first time warmup publishes a Ready model the flow flips, which in
     *   turn fires the engine-state observer and unblocks Send.
     *
     * Also applies persisted overrides once a model resolves so the engine
     * sees the user's accelerator / system prompt on the next heavy apply
     * (D24, D21). Re-running on every activeModelName change is safe: the
     * repository read is idempotent.
     */
    private suspend fun bootstrapChatModelId() {
        when (val id = identity) {
            is ChatIdentity.Persistent -> {
                val entity = runCatching { chatDao.getById(id.id) }
                    .onFailure { errorLog.e("history-read", "chat row lookup failed for id=${id.id}", it) }
                    .getOrNull()
                _chatModelId.value = entity?.modelId
                applyEffectiveConfigToModel()
                // B4: Detect Draft→Persistent handover gap. Draft commits a USER
                // row and navigates; the new Persistent VM never received
                // `send(...)` for that first turn. When the last persisted
                // message is an unpaired USER row we stage an auto-resume and
                // let [observeFirstReadyThenResume] dispatch inference once the
                // pinned engine reaches Ready. Same path recovers from AC-R3
                // kill-mid-stream (USER persisted, process died before ASSISTANT).
                val lastMsg = runCatching { messageDao.lastByChat(id.id) }
                    .onFailure { errorLog.e("history-read", "lastByChat failed for id=${id.id}", it) }
                    .getOrNull()
                // Phase 3.6 Bug-1 fix: every Persistent VM bootstrap must
                // recreate the engine's Conversation so KV-cache from the
                // prior chat does not leak into this one. The reset is
                // gated on first Ready — DefaultModelRegistry would
                // warning-skip a non-Ready engine and leave the cache dirty.
                // Reuses `lastMsg` (already read for AC-R3 auto-resume) to
                // distinguish DRAFT_COMMIT (unpaired USER tail = handover
                // from Draft) from CHAT_SWITCH (any other state).
                val resetReason = if (lastMsg?.role == ROLE_USER) {
                    ResetReason.DRAFT_COMMIT
                } else {
                    ResetReason.CHAT_SWITCH
                }
                val pendingAutoResume = lastMsg?.takeIf { it.role == ROLE_USER }
                if (pendingAutoResume != null) {
                    autoResumeTarget = pendingAutoResume
                }
                // Reset and auto-resume run in a single coroutine so the
                // resume's `helper.runInference` is invoked strictly AFTER
                // `helper.resetConversation` has returned. Two sibling
                // coroutines awaiting the same Ready edge would race: the
                // reset's `withContext(Dispatchers.Default)` hop inside
                // DefaultModelRegistry releases Main, letting the resume
                // siblings fire against the still-dirty Conversation —
                // exactly the KV leak Bug-1 closes.
                viewModelScope.launch {
                    observeFirstReadyThenReset(resetReason)
                    if (pendingAutoResume != null) {
                        observeFirstReadyThenResume(id)
                    }
                }
            }
            ChatIdentity.Quick, ChatIdentity.Draft -> {
                val pinned = explicitModelId
                    ?: registry.activeModelName.value
                    // Suspend until WarmupCoordinator publishes a Ready model.
                    // Quick/Draft pin to that first non-null emission; if the
                    // engine later transitions Ready → Initializing/Failed the
                    // VM keeps observing the same modelId and surfaces the
                    // transition via the engine observer (not by switching
                    // models). Cross-model reinit goes through `applyHeavySetting`.
                    // Suspend until WarmupCoordinator publishes a Ready model.
                    // `mapNotNull` drops nulls on the fly so `first()` always
                    // returns a non-null modelId without an explicit `!!`.
                    ?: registry.activeModelName.mapNotNull { it }.first()
                _chatModelId.value = pinned
                // Explicit-modelId Quick flow from Model Manager: the route pins a
                // model the coordinator is not currently warming (or is warming a
                // different one). Without this trigger, the entry stays at
                // ModelInitStatus.Idle and the body renders a perpetual spinner.
                // Skip if the target is already Ready — cancelAndRestart would
                // needlessly recycle a healthy engine.
                if (explicitModelId != null) {
                    val alreadyReady = registry.models.value
                        .firstOrNull { it.model.modelId == explicitModelId }
                        ?.initStatus == ModelInitStatus.Ready
                    if (!alreadyReady) {
                        warmupCoordinator.cancelAndRestart(explicitModelId)
                    }
                }
                applyEffectiveConfigToModel()
            }
        }
    }

    private fun buildMessagesFlow(): StateFlow<List<Message>> = when (val id = identity) {
        is ChatIdentity.Persistent -> {
            // Decode the Room row list into domain Messages (Task 18 B1) before
            // it enters the `combine` with `_streamingMessage`. Room dispatches
            // `observeByChat` on its own background executor (not Main), so the
            // BitmapFactory / wavToPcm work inherits that context — no explicit
            // `flowOn(Dispatchers.IO)` is needed, and avoiding it keeps
            // `UnconfinedTestDispatcher`-based unit tests deterministic (a
            // real-thread IO hop would require real-time waits).
            // [attachmentCache] memoises by row id so a Room re-emission that
            // only bumped an existing row does not re-read the same file.
            val decoded = messageDao.observeByChat(id.id)
                .map { list -> list.map { toDomainMessageWithAttachments(it) } }
            combine(decoded, _streamingMessage) { persisted, streaming ->
                // Sole mechanism for the double-bubble invariant (user-spec
                // Risks, D4): the moment the Room list ends in an ASSISTANT
                // row the in-memory streaming bubble is hidden. No second
                // clear path — the `_streamingMessage` value may linger
                // internally but cannot become visible until [send] resets it.
                val persistedEndsWithAssistant = persisted.lastOrNull()?.role == MessageRole.ASSISTANT
                val visibleStreaming = if (persistedEndsWithAssistant) null else streaming
                persisted + listOfNotNull(visibleStreaming)
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )
        }
        ChatIdentity.Quick, ChatIdentity.Draft -> _messages.asStateFlow()
    }

    /**
     * Waits for the pinned Persistent-mode engine to reach [ChatUiState.Ready]
     * once, then dispatches a one-shot inference for the unpaired USER row
     * captured in [autoResumeTarget] (Task 18 B4).
     *
     * Invariant: [_uiState.first { Ready }] intentionally hangs through a
     * terminal `Failed` state — the auto-resume stays armed until the user
     * taps «Загрузить» → engine reaches Ready → dispatch. Do NOT persist
     * [autoResumeAttempted] across process restarts; a new VM on the next
     * cold start sees the unpaired USER row and re-arms correctly.
     *
     * The flag is flipped INSIDE [resumePendingAssistantPersistent], AFTER
     * the [currentReadyModel] null-check succeeds, so a late engine flap
     * (Ready→Idle) between the Ready signal and this function does not
     * consume the auto-resume attempt — a fresh VM can still recover.
     */
    private suspend fun observeFirstReadyThenResume(identity: ChatIdentity.Persistent) {
        _uiState.first { it is ChatUiState.Ready }
        val target = autoResumeTarget ?: return
        if (autoResumeAttempted) return
        resumePendingAssistantPersistent(identity, target)
    }

    /**
     * Persistent-mode Phase 3.6 Bug-1 hook (AC-1.1, AC-1.2). Suspends until
     * the pinned engine first reaches [ChatUiState.Ready], then issues a
     * single [registry.resetConversation] call so the next user turn starts
     * with a fresh KV-cache and the merged sampler/system-prompt picked up
     * by [applyEffectiveConfigToModel].
     *
     * Mirrors [observeFirstReadyThenResume]: a single `first { Ready }` call
     * latches on the very first Ready emission. Subsequent flutters
     * (Ready → Initializing → Ready, e.g. after [applyHeavySetting]) do NOT
     * re-trigger — the heavy reinit creates a new engine and a new
     * Conversation by construction, so a second reset would be redundant.
     *
     * Intentionally does NOT clear UI history — the chat's persisted Room
     * rows must remain visible across the reset; only the engine-internal
     * Conversation is recreated.
     */
    private suspend fun observeFirstReadyThenReset(reason: ResetReason) {
        _uiState.first { it is ChatUiState.Ready }
        val model = currentReadyModel() ?: return
        val effective = effectiveSystemPrompt(model)
        registry.resetConversation(
            model.name,
            systemPrompt = effective,
            reason = reason,
        )
    }

    /**
     * Runs inference for a pre-persisted USER row without re-inserting it
     * (avoids the duplicate-USER-in-Room regression in B4-AC3). Mirrors the
     * streaming-bubble + dispatchInferencePersistent tail of [runInferencePersistent]
     * without its `chatRepository.savePersistentMessage(userMsg)` step.
     *
     * Attachments on [userEntity] (`imagePath` / `audioPath`) are re-decoded
     * from disk via the B1 helpers and forwarded to `runInference` so the
     * model actually sees the image/audio the user attached to their first
     * send — without this, the Draft→Persistent handover dropped the media
     * and the assistant answered text-only on the first turn even though
     * the history bubble displayed the attachment correctly.
     */
    private fun resumePendingAssistantPersistent(
        identity: ChatIdentity.Persistent,
        userEntity: MessageEntity,
    ) {
        val model = currentReadyModel() ?: return
        // Commit the auto-resume attempt only after a model is locked in —
        // a Ready→Idle flap between the Ready signal and this point would
        // otherwise consume the attempt and strand the unpaired USER row.
        autoResumeAttempted = true
        autoResumeTarget = null
        val pending = decodeAttachmentsForEntity(userEntity)
        val accumulateThinking = shouldAccumulateThinking(model)
        val initialThinkingText: String? = if (accumulateThinking) "" else null
        _streamingMessage.value = Message(
            role = MessageRole.ASSISTANT,
            text = "",
            streaming = true,
            thinkingText = initialThinkingText,
            attachments = emptyList(),
        )
        _uiState.value = ChatUiState.Ready(isGenerating = true)
        dispatchInferencePersistent(
            identity = identity,
            text = userEntity.text,
            pending = pending,
            model = model,
            accumulateThinking = accumulateThinking,
        )
    }

    private suspend fun observeEngineState() {
        combine(registry.models, _chatModelId) { list, modelId ->
            if (modelId == null) null else list.firstOrNull { it.model.modelId == modelId }
        }.collect { entry ->
            updateUiStateFromEntry(entry)
        }
    }

    private fun updateUiStateFromEntry(entry: ModelEntry?) {
        val status = entry?.initStatus
        val next = when (status) {
            ModelInitStatus.Ready -> {
                refreshModelCaps(entry.model)
                val prev = _uiState.value
                val generating = (prev as? ChatUiState.Ready)?.isGenerating == true
                ChatUiState.Ready(isGenerating = generating)
            }
            ModelInitStatus.Initializing -> ChatUiState.Loading
            is ModelInitStatus.Failed -> ChatUiState.Failed(status.message)
            ModelInitStatus.Idle, null -> ChatUiState.Loading
        }
        // Avoid redundant emissions — a StateFlow already dedupes on equals,
        // but constructing a new Ready(isGenerating=false) over and over would
        // still trigger collectors due to instance identity in combine chains.
        if (_uiState.value != next) _uiState.value = next
    }

    // --- send paths ----------------------------------------------------------

    private fun commitDraft(text: String, pending: List<Attachment>) {
        val modelId = _chatModelId.value
        if (modelId == null) {
            viewModelScope.launch {
                errorLog.e("history-write", "commitDraft aborted: no active model")
            }
            _snackbar.tryEmit(R.string.chat_load_failed_title)
            return
        }
        // Require a Ready engine for the first send — matches the Send-gate
        // check in the outer `send()` (uiState must be Ready there).
        if (currentReadyModel() == null) return
        // Flip the Send-gate synchronously so a rapid double-tap can't slip a
        // second commit past `state.isGenerating` before the first one
        // finishes (code-reviewer-1 M1). Reverted on commit failure below.
        _uiState.value = ChatUiState.Ready(isGenerating = true)
        val now = System.currentTimeMillis()
        // MVP: one image + one audio per message row (mirrors the
        // MessageEntity schema — single `image_path` / `audio_path`). The
        // staged-file contract ensures these filenames point at already-
        // written payloads inside `draftStagingDir`.
        val stagedImage = pending.firstNotNullOfOrNull { att ->
            (att as? Attachment.Image)?.stagedFilename
        }
        val stagedAudio = pending.firstNotNullOfOrNull { att ->
            (att as? Attachment.Audio)?.stagedFilename
        }
        val stagingDir = draftStagingDir?.takeIf { stagedImage != null || stagedAudio != null }
        val imageCount = pending.count { it is Attachment.Image }
        if (imageCount > 1) {
            // code-reviewer-1 T17-R2: MAX_IMAGES=10 in the UI but only the
            // first is persisted. Warn the user so they know the rest will
            // only influence this single answer — this also matches the
            // staging-prune step below, which deletes the extras before the
            // commit rename so they do not become permanent orphans.
            _snackbar.tryEmit(R.string.attachment_only_first_persisted)
        }
        val firstMessage = MessageEntity(
            chatId = 0L,
            role = ROLE_USER,
            text = text,
            createdAt = now,
        )
        viewModelScope.launch {
            val filesDir = context.filesDir
            // Prune everything the commit is about to rename that is NOT
            // referenced by the first-message paths. Covers two races:
            //   (a) N>1 images — only `stagedImage` is referenced; the rest
            //       would otherwise become orphans inside attachments/{chatId}/
            //       that no sweep catches (security T17-S4, code-reviewer T17-R2).
            //   (b) removeAttachment fired while the staging write was in
            //       flight — the staged file landed on disk but its
            //       attachment reference was already gone from `_attachments`.
            if (stagingDir != null) {
                chatRepository.pruneStagingDir(
                    stagingDir = stagingDir,
                    filesDir = filesDir,
                    retain = setOfNotNull(stagedImage, stagedAudio),
                )
            }
            val result = runCatching {
                chatRepository.commitDraftChat(
                    modelId = modelId,
                    firstMessage = firstMessage,
                    stagingDir = stagingDir,
                    filesDir = filesDir,
                    stagedImageFilename = stagedImage,
                    stagedAudioFilename = stagedAudio,
                )
            }
            result.fold(
                onSuccess = { chatId ->
                    _attachments.value = emptyList()
                    // Staging dir is now renamed to `attachments/{chatId}/` — the
                    // old File handle is stale, so drop it to prevent any late
                    // `deleteStagedFileIfAny` from targeting the final chat dir.
                    draftStagingDir = null
                    _navigation.tryEmit(ChatNavigationEvent.NavigateToPersistent(chatId))
                },
                onFailure = { cause ->
                    errorLog.e("history-write", "commitDraftChat failed", cause)
                    _snackbar.tryEmit(R.string.chat_load_failed_title)
                    // Let the user retry — restore the Send gate to its prior
                    // Ready-idle state (without recomputing the engine status,
                    // which is still Ready by construction of this path).
                    _uiState.value = ChatUiState.Ready(isGenerating = false)
                },
            )
        }
    }

    private fun runInferenceQuick(text: String, pending: List<Attachment>) {
        val model = currentReadyModel() ?: return
        appendUserAndStreamingBubbleInMemory(text, pending, model)
        dispatchInference(text, pending, model) { done, interrupted ->
            if (done || interrupted) {
                _uiState.value = ChatUiState.Ready(isGenerating = false)
            }
        }
    }

    private fun runInferencePersistent(
        identity: ChatIdentity.Persistent,
        text: String,
        pending: List<Attachment>,
    ) {
        val model = currentReadyModel() ?: return
        val accumulateThinking = shouldAccumulateThinking(model)
        val initialThinkingText: String? = if (accumulateThinking) "" else null

        _streamingMessage.value = Message(
            role = MessageRole.ASSISTANT,
            text = "",
            streaming = true,
            thinkingText = initialThinkingText,
            attachments = emptyList(),
        )
        if (pending.count { it is Attachment.Image } > 1) {
            // Same MVP limitation as Draft — the Room row carries a single
            // image_path; extras flow to inference but are not persisted to
            // disk and will not appear in the chat history after reload.
            _snackbar.tryEmit(R.string.attachment_only_first_persisted)
        }
        _attachments.value = emptyList()
        _uiState.value = ChatUiState.Ready(isGenerating = true)

        // AC-R1: persist USER synchronously before runInference so a process
        // kill mid-stream leaves the USER row present even if ASSISTANT never
        // arrives (AC-R3). `savePersistentMessage` runs on Dispatchers.IO
        // inside the repository.
        val userTs = System.currentTimeMillis()
        viewModelScope.launch {
            val persistedImage = pending.firstOrNull { it is Attachment.Image }
            val persistedAudio = pending.firstOrNull { it is Attachment.Audio }
            // MVP: single image + single audio per USER row (mirrors the
            // Phase-2 send contract and the MessageEntity schema). Any
            // additional images are still forwarded to inference via the
            // bitmap list below — they just aren't persisted.
            val imagePath: String?
            val audioPath: String?
            try {
                imagePath = persistedImage?.let {
                    chatRepository.savePersistentAttachment(identity.id, context.filesDir, it)
                        .imagePath
                }
                audioPath = persistedAudio?.let {
                    chatRepository.savePersistentAttachment(identity.id, context.filesDir, it)
                        .audioPath
                }
            } catch (cause: Throwable) {
                // Hard-gate (mirrors the USER-persist failure guard below): if
                // the attachment write fails we must not start inference or
                // write a USER row referencing a file that does not exist.
                viewModelScope.launch {
                    errorLog.e(
                        "attachment-save",
                        "savePersistentAttachment failed for chatId=${identity.id}",
                        cause,
                    )
                }
                _streamingMessage.value = null
                _attachments.value = pending
                _uiState.value = ChatUiState.Ready(isGenerating = false)
                _snackbar.tryEmit(R.string.attachment_save_failed)
                return@launch
            }

            val userMsg = MessageEntity(
                chatId = identity.id,
                role = ROLE_USER,
                text = text,
                imagePath = imagePath,
                audioPath = audioPath,
                createdAt = userTs,
            )
            // Only bump `last_message_at` if the save actually landed. A
            // failed INSERT with an updated timestamp would weaken AC-R3's
            // invariant: the chat would appear "active" in the drawer while
            // carrying no new row (security-auditor-1 s2).
            val saveResult = runCatching { chatRepository.savePersistentMessage(userMsg) }
            if (saveResult.isFailure) {
                // AC-R1 hard-gate (security-auditor-2 s4): if USER cannot be
                // persisted we must NOT run inference — otherwise the next
                // done=true would write an ASSISTANT row with no preceding
                // USER row, inverting the process-kill invariant. Surface
                // the failure to the user and restore the Send gate. Log
                // in a detached launch so the UI unblocks immediately —
                // errorLog.e suspends on Dispatchers.IO.
                viewModelScope.launch {
                    errorLog.e(
                        "history-write",
                        "savePersistentMessage USER failed",
                        saveResult.exceptionOrNull(),
                    )
                }
                _streamingMessage.value = null
                _uiState.value = ChatUiState.Ready(isGenerating = false)
                _snackbar.tryEmit(R.string.chat_load_failed_title)
                return@launch
            }
            chatRepository.updateChatLastMessage(identity.id, userTs)

            dispatchInferencePersistent(identity, text, pending, model, accumulateThinking)
        }
    }

    private fun dispatchInferencePersistent(
        identity: ChatIdentity.Persistent,
        text: String,
        pending: List<Attachment>,
        model: Model,
        accumulateThinking: Boolean,
    ) {
        val startMs = System.currentTimeMillis()
        var firstTokenMs = 0L
        val sb = StringBuilder()
        val thinkingSb = StringBuilder()

        val images = pending.filterIsInstance<Attachment.Image>().map { it.bitmap }
        val audioClips = pending.filterIsInstance<Attachment.Audio>()
            .map { pcmToWav(it.pcm, SAMPLE_RATE) }
        val extraContext: Map<String, String>? =
            if (accumulateThinking) mapOf("enable_thinking" to "true") else null

        helper.runInference(
            model = model,
            input = text,
            resultListener = { partial, done, partialThinking ->
                val bubble = _streamingMessage.value
                if (bubble?.interrupted == true) return@runInference
                if (firstTokenMs == 0L && partial.isNotEmpty()) {
                    firstTokenMs = System.currentTimeMillis()
                }
                if (accumulateThinking && !partialThinking.isNullOrEmpty()) {
                    thinkingSb.append(partialThinking)
                    _streamingMessage.update { it?.copy(thinkingText = thinkingSb.toString()) }
                }
                if (partial.isNotEmpty()) {
                    sb.append(partial)
                    _streamingMessage.update { it?.copy(text = sb.toString()) }
                }
                if (done) {
                    val totalMs = System.currentTimeMillis() - startMs
                    val ttftMs = if (firstTokenMs > 0L) (firstTokenMs - startMs) else 0L
                    val totalSec = totalMs / 1000.0
                    val footer = context.getString(
                        R.string.ttft_footer_format,
                        ttftMs.toInt(),
                        totalSec,
                    )
                    // AC-R2: persist ASSISTANT only on done=true. The Room
                    // emission will trigger the atomic handover and clear
                    // `_streamingMessage` — no direct clear here.
                    val createdAt = System.currentTimeMillis()
                    val assistantText = sb.toString()
                    val thinkingText = if (accumulateThinking) thinkingSb.toString() else null
                    viewModelScope.launch {
                        val assistantMsg = MessageEntity(
                            chatId = identity.id,
                            role = ROLE_ASSISTANT,
                            text = assistantText,
                            thinkingText = thinkingText,
                            createdAt = createdAt,
                        )
                        val savedOk = runCatching { chatRepository.savePersistentMessage(assistantMsg) }
                            .onFailure { errorLog.e("history-write", "savePersistentMessage ASSISTANT failed", it) }
                            .isSuccess
                        if (savedOk) {
                            chatRepository.updateChatLastMessage(identity.id, createdAt)
                        }
                    }
                    // Update the in-memory bubble to carry footer/streaming=false
                    // in case the Room write is slow — the combine guarantees
                    // no double-bubble even if streaming lingers here briefly.
                    _streamingMessage.update { it?.copy(streaming = false, footer = footer) }
                    _uiState.value = ChatUiState.Ready(isGenerating = false)
                }
            },
            cleanUpListener = { /* no-op */ },
            onError = { msg ->
                val safeMsg = msg.ifBlank { "(no message)" }
                viewModelScope.launch { errorLog.e("inference", safeMsg) }
                _streamingMessage.update { it?.copy(streaming = false, interrupted = true) }
                _uiState.value = ChatUiState.Ready(isGenerating = false)
            },
            images = images,
            audioClips = audioClips,
            coroutineScope = viewModelScope,
            extraContext = extraContext,
        )
    }

    private fun appendUserAndStreamingBubbleInMemory(
        text: String,
        pending: List<Attachment>,
        model: Model,
    ) {
        val accumulateThinking = shouldAccumulateThinking(model)
        val initialThinkingText: String? = if (accumulateThinking) "" else null
        _messages.update { current ->
            current +
                Message(MessageRole.USER, text, attachments = pending) +
                Message(
                    MessageRole.ASSISTANT,
                    text = "",
                    streaming = true,
                    thinkingText = initialThinkingText,
                )
        }
        _attachments.value = emptyList()
        _uiState.value = ChatUiState.Ready(isGenerating = true)
    }

    private fun dispatchInference(
        text: String,
        pending: List<Attachment>,
        model: Model,
        onTerminal: (done: Boolean, interrupted: Boolean) -> Unit,
    ) {
        val startMs = System.currentTimeMillis()
        var firstTokenMs = 0L
        val sb = StringBuilder()
        val thinkingSb = StringBuilder()
        val accumulateThinking = shouldAccumulateThinking(model)

        val images = pending.filterIsInstance<Attachment.Image>().map { it.bitmap }
        val audioClips = pending.filterIsInstance<Attachment.Audio>()
            .map { pcmToWav(it.pcm, SAMPLE_RATE) }
        val extraContext: Map<String, String>? =
            if (accumulateThinking) mapOf("enable_thinking" to "true") else null

        helper.runInference(
            model = model,
            input = text,
            resultListener = { partial, done, partialThinking ->
                if (_messages.value.lastOrNull()?.interrupted == true) return@runInference
                if (firstTokenMs == 0L && partial.isNotEmpty()) {
                    firstTokenMs = System.currentTimeMillis()
                }
                if (accumulateThinking && !partialThinking.isNullOrEmpty()) {
                    thinkingSb.append(partialThinking)
                    val snapshot = thinkingSb.toString()
                    updateLastAssistantInMemory { it.copy(thinkingText = snapshot) }
                }
                if (partial.isNotEmpty()) {
                    sb.append(partial)
                    updateLastAssistantInMemory { it.copy(text = sb.toString()) }
                }
                if (done) {
                    val totalMs = System.currentTimeMillis() - startMs
                    val ttftMs = if (firstTokenMs > 0L) (firstTokenMs - startMs) else 0L
                    val totalSec = totalMs / 1000.0
                    val footer = context.getString(
                        R.string.ttft_footer_format,
                        ttftMs.toInt(),
                        totalSec,
                    )
                    updateLastAssistantInMemory { it.copy(streaming = false, footer = footer) }
                    onTerminal(true, false)
                }
            },
            cleanUpListener = { /* no-op */ },
            onError = { msg ->
                val safeMsg = msg.ifBlank { "(no message)" }
                viewModelScope.launch { errorLog.e("inference", safeMsg) }
                updateLastAssistantInMemory { it.copy(streaming = false, interrupted = true) }
                onTerminal(false, true)
            },
            images = images,
            audioClips = audioClips,
            coroutineScope = viewModelScope,
            extraContext = extraContext,
        )
    }

    private fun shouldAccumulateThinking(model: Model): Boolean {
        val enableThinking =
            model.configValues[ConfigKeys.ENABLE_THINKING.label] as? Boolean == true
        return model.llmSupportThinking && enableThinking
    }

    // --- D15 classification --------------------------------------------------

    enum class ApplyLevel { NONE, LIGHT, SYSTEM_PROMPT, HEAVY }

    private fun classifyApplyLevel(
        current: Map<String, Any>,
        target: Map<String, Any>,
    ): ApplyLevel {
        val acceleratorChanged = current[ConfigKeys.ACCELERATOR.label] !=
            target[ConfigKeys.ACCELERATOR.label]
        // Phase 3.6 Decision 4: `max_tokens` lives in `EngineConfig`,
        // applied only at engine creation — must take the HEAVY path
        // (cleanup+initialize), the same one accelerator uses.
        val maxTokensChanged = current[ConfigKeys.MAX_TOKENS.label] !=
            target[ConfigKeys.MAX_TOKENS.label]
        if (acceleratorChanged || maxTokensChanged) return ApplyLevel.HEAVY
        val semiChanged = SEMI_LIGHT_FIELD_LABELS.any {
            current[it] != target[it]
        }
        if (semiChanged) return ApplyLevel.SYSTEM_PROMPT
        val lightChanged = LIGHT_FIELD_LABELS.any { current[it] != target[it] }
        if (lightChanged) return ApplyLevel.LIGHT
        return ApplyLevel.NONE
    }

    private fun dispatchByLevel(level: ApplyLevel) {
        when (level) {
            ApplyLevel.HEAVY -> applyHeavySetting()
            ApplyLevel.SYSTEM_PROMPT -> applySystemPromptAndReset()
            ApplyLevel.LIGHT -> applyLightOverrides()
            ApplyLevel.NONE -> Unit
        }
    }

    // --- model lookup / config helpers --------------------------------------

    private suspend fun applyEffectiveConfigToModel() {
        val modelId = _chatModelId.value ?: return
        val model = preInitModel() ?: return
        val overrides = settingsRepository.observePerModelSettings(modelId).first()
        val defaults = computeDefaults(model)
        model.configValues = EffectiveConfig.merge(defaults, overrides)
    }

    /** Raw [Model] lookup ignoring init state — used before heavy reinit. */
    private fun preInitModel(): Model? {
        val modelId = _chatModelId.value ?: return null
        return registry.models.value.firstOrNull { it.model.modelId == modelId }?.model
    }

    private fun currentReadyModel(): Model? {
        val modelId = _chatModelId.value ?: return null
        val entry = registry.models.value.firstOrNull { it.model.modelId == modelId }
        return if (entry?.initStatus === ModelInitStatus.Ready) entry.model else null
    }

    private fun computeDefaults(model: Model): Map<String, Any> =
        model.configs.associate { it.key.label to it.defaultValue }

    private fun effectiveSystemPrompt(map: Map<String, Any>): String? =
        (map[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label] as? String)?.takeIf { it.isNotBlank() }

    private fun effectiveSystemPrompt(model: Model): String? =
        effectiveSystemPrompt(model.configValues)

    private fun refreshModelCaps(model: Model) {
        _modelCaps.value = ModelCapabilities(
            supportImage = model.llmSupportImage,
            supportAudio = model.llmSupportAudio,
            supportThinking = model.llmSupportThinking,
        )
    }

    private inline fun updateLastAssistantInMemory(transform: (Message) -> Message) {
        _messages.update { list ->
            if (list.isEmpty()) return@update list
            val last = list.last()
            if (last.role != MessageRole.ASSISTANT) return@update list
            list.toMutableList().also { it[it.lastIndex] = transform(last) }
        }
    }

    private fun downscaleIfOversized(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_IMAGE_EDGE) return bitmap
        val ratio = MAX_IMAGE_EDGE.toFloat() / longest
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, /* filter = */ true)
    }

    /**
     * Persistent-mode decode (Task 18 B1). Reads `imagePath`/`audioPath` off
     * the [MessageEntity] and materialises [Attachment.Image] / [Attachment.Audio]
     * — without this, `MessageBubble.MessageAttachmentsRow` renders nothing for
     * historical rows because `message.attachments` is empty.
     *
     * Called from the `.map { }.flowOn(Dispatchers.IO)` stage of
     * [buildMessagesFlow] so the decode does not run on Main. Results are
     * memoised by [attachmentCache] keyed on row id — a subsequent Room emit
     * (e.g. `updateChatLastMessage` bump) reuses the cached bitmap and pcm
     * instead of re-reading the file.
     *
     * Failure modes (see B1-AC2 / B1-AC3):
     *  - Path escapes `filesDir/attachments/` (containment check) → SecurityException logged, attachment omitted.
     *  - File missing / corrupt / decode returns null → attachment omitted, `attachment-read` log emitted once.
     *  - Text part of the message still renders — graceful degradation.
     */
    private fun toDomainMessageWithAttachments(entity: MessageEntity): Message {
        val role = if (entity.role == ROLE_USER) MessageRole.USER else MessageRole.ASSISTANT
        val attachments = attachmentCache[entity.id] ?: run {
            val decoded = decodeAttachmentsForEntity(entity)
            attachmentCache[entity.id] = decoded
            decoded
        }
        return Message(
            role = role,
            text = entity.text,
            streaming = false,
            interrupted = false,
            footer = null,
            thinkingText = entity.thinkingText,
            attachments = attachments,
        )
    }

    private fun decodeAttachmentsForEntity(entity: MessageEntity): List<Attachment> {
        val result = mutableListOf<Attachment>()
        val filesDir = context.filesDir
        entity.imagePath?.let { path ->
            val image = runCatching { decodeImageFromRelativePath(path, filesDir) }
                .onFailure { cause ->
                    viewModelScope.launch {
                        errorLog.e("attachment-read", "image decode failed: $path", cause)
                    }
                }
                .getOrNull()
            if (image != null) result += image
        }
        entity.audioPath?.let { path ->
            val audio = runCatching { decodeAudioFromRelativePath(path, filesDir) }
                .onFailure { cause ->
                    viewModelScope.launch {
                        errorLog.e("attachment-read", "audio decode failed: $path", cause)
                    }
                }
                .getOrNull()
            if (audio != null) result += audio
        }
        return result
    }

    private fun decodeImageFromRelativePath(relativePath: String, filesDir: File): Attachment.Image? {
        val file = resolveInsideAttachmentsRoot(relativePath, filesDir)
        if (!file.exists()) {
            viewModelScope.launch {
                errorLog.e("attachment-read", "file missing: ${file.absolutePath}")
            }
            return null
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: run {
            viewModelScope.launch {
                errorLog.e("attachment-read", "BitmapFactory returned null: ${file.absolutePath}")
            }
            return null
        }
        return Attachment.Image(bitmap)
    }

    private fun decodeAudioFromRelativePath(relativePath: String, filesDir: File): Attachment.Audio? {
        val file = resolveInsideAttachmentsRoot(relativePath, filesDir)
        if (!file.exists()) {
            viewModelScope.launch {
                errorLog.e("attachment-read", "file missing: ${file.absolutePath}")
            }
            return null
        }
        val decoded = wavToPcm(file)
        return Attachment.Audio(pcm = decoded.pcm, durationMs = decoded.durationMs)
    }

    /**
     * Containment check (parity with `DefaultChatRepository.requireInsideAttachmentsRoot`):
     * a Room-stored path that canonicalises outside `filesDir/attachments/` must
     * be rejected with [SecurityException] — otherwise a poisoned row could read
     * arbitrary files under the app's private storage.
     */
    private fun resolveInsideAttachmentsRoot(relativePath: String, filesDir: File): File {
        val attachmentsRoot = File(filesDir, "attachments").canonicalFile
        val candidate = File(filesDir, relativePath).canonicalFile
        if (!candidate.path.startsWith(attachmentsRoot.path + File.separator)) {
            throw SecurityException(
                "attachment path escapes attachments root: $relativePath",
            )
        }
        return candidate
    }

    companion object {
        /**
         * Nav-arg key for persistent-chat routes (`chat/{chatId}`). Replaces the
         * Phase-2 `modelName` arg — chat identity now lives on the Room row and
         * the model is looked up indirectly via `chat.model_id`.
         */
        const val NAV_ARG_CHAT_ID: String = "chatId"

        /**
         * Nav-arg key injecting `"quick"` or `"draft"` into the SavedStateHandle
         * for the non-persistent routes. Supplied via `navArgument("kind") { defaultValue = ... }`
         * in the route definitions (see SanctumApp.kt) so composables need not
         * pass it explicitly.
         */
        const val NAV_ARG_KIND: String = "kind"

        /**
         * Optional Quick-mode nav arg: pins the VM to a specific model that
         * the user asked to "Load" from the Model Manager (Task 11 wires the
         * `?modelId={id}` query arg).
         */
        const val NAV_ARG_MODEL_ID: String = "modelId"

        const val KIND_DRAFT: String = "draft"
        const val KIND_QUICK: String = "quick"

        private const val MAX_IMAGES: Int = 10
        private const val MAX_IMAGE_EDGE: Int = 1024

        // MAX_TOKENS is intentionally NOT here (Phase 3.6 Decision 4):
        // `maxNumTokens` is a field of LiteRT-LM `EngineConfig`, not
        // `ConversationConfig`. Light-tier Conversation recreation does not
        // change the engine's token cap — only HEAVY (cleanup+initialize)
        // does. See classifyApplyLevel HEAVY condition.
        private val LIGHT_FIELD_LABELS: Set<String> = setOf(
            ConfigKeys.TEMPERATURE.label,
            ConfigKeys.TOPK.label,
            ConfigKeys.TOPP.label,
        )

        private val SEMI_LIGHT_FIELD_LABELS: Set<String> = setOf(
            ConfigKeys.SYSTEM_PROMPT_DEFAULT.label,
            ConfigKeys.ENABLE_THINKING.label,
        )
    }
}
