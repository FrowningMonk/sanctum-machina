package app.sanctum.machina.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.R
import app.sanctum.machina.core.common.pcmToWav
import app.sanctum.machina.core.data.ConfigKeys
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.SAMPLE_RATE
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.runtime.LlmModelHelper
import app.sanctum.machina.core.settings.AppSettingsRepository
import app.sanctum.machina.core.settings.proto.PerModelSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Ready(val isGenerating: Boolean) : ChatUiState
    data class Failed(val rawCause: String) : ChatUiState
}

/**
 * Capability flags derived from the active [Model] after `registry.initialize`.
 * Drives conditional visibility of camera/gallery/mic in `MultimodalInputBar`
 * (AC-18) and the thinking UI gate (AC-14).
 */
data class ModelCapabilities(
    val supportImage: Boolean = false,
    val supportAudio: Boolean = false,
    val supportThinking: Boolean = false,
)

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
) : ViewModel() {

    val modelName: String =
        savedStateHandle.get<String>(NAV_ARG_MODEL_NAME)
            ?: error("modelName nav-arg is required")

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    private val _modelCaps = MutableStateFlow(ModelCapabilities())
    val modelCaps: StateFlow<ModelCapabilities> = _modelCaps.asStateFlow()

    /**
     * Toggles the modal `ReinitProgressDialog` while a heavy-setting reinit
     * runs (D12/D21). UI must block input — accelerator/enableThinking
     * teardown is uncancellable.
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

    init {
        viewModelScope.launch {
            // Apply persisted overrides before init so awaitInitialize sees the
            // user's accelerator + system prompt (D24, D21).
            applyEffectiveConfigToModel()
            registry.initialize(modelName).fold(
                onSuccess = {
                    refreshModelCaps()
                    _uiState.value = ChatUiState.Ready(isGenerating = false)
                },
                onFailure = { e ->
                    val cause =
                        e.message?.takeIf { it.isNotBlank() }
                            ?: e::class.simpleName.orEmpty()
                    _uiState.value = ChatUiState.Failed(cause)
                },
            )
        }
    }

    fun send(text: String) {
        val normalized = text.trim()
        val pending = _attachments.value
        if (normalized.isEmpty() && pending.isEmpty()) return
        val state = _uiState.value
        if (state !is ChatUiState.Ready || state.isGenerating) return
        if (_reinitInProgress.value) return
        val model = registry.getModel(modelName) ?: error("Model not initialized")

        val images = pending.filterIsInstance<Attachment.Image>().map { it.bitmap }
        // Wrap raw PCM in a RIFF/WAVE header at the litertlm boundary —
        // user-spec claim "litertlm ест PCM" was wrong (confirmed on
        // Honor 200 smoke: headerless PCM → onError → message marked
        // `interrupted`). Attachment.Audio keeps raw PCM for in-memory
        // compactness and Phase-3 Room serialisation.
        val audioClips = pending.filterIsInstance<Attachment.Audio>()
            .map { pcmToWav(it.pcm, SAMPLE_RATE) }

        // D9/AC-14 gate — only surface and accumulate reasoning when the
        // model exposes the channel AND the user has opted in via
        // enable_thinking. Evaluated once per send() so a mid-stream flip
        // of the config value can't leave the bubble half-populated.
        val enableThinking =
            model.configValues[ConfigKeys.ENABLE_THINKING.label] as? Boolean == true
        val accumulateThinking = model.llmSupportThinking && enableThinking
        val initialThinkingText: String? = if (accumulateThinking) "" else null

        _messages.update { current ->
            current +
                Message(MessageRole.USER, normalized, attachments = pending) +
                Message(
                    MessageRole.ASSISTANT,
                    text = "",
                    streaming = true,
                    thinkingText = initialThinkingText,
                )
        }
        // Clear the staging area so the ThumbnailStrip empties — the
        // attachments now live inside the USER Message for history rendering
        // (AC-26, D28) and in the `images`/`audioClips` snapshots for the
        // inference call below.
        _attachments.value = emptyList()
        _uiState.value = ChatUiState.Ready(isGenerating = true)

        val startMs = System.currentTimeMillis()
        var firstTokenMs = 0L
        val sb = StringBuilder()
        val thinkingSb = StringBuilder()

        // Gemma 4 emits reasoning as inline `<|think|>` tokens that LiteRT-LM's
        // Jinja chat template only injects when `enable_thinking=true` is passed
        // via `extraContext`. Without this, the template renders in answer-only
        // mode and `message.channels["thought"]` stays null — `accumulateThinking`
        // would still gate the UI, but no thinking tokens would ever arrive.
        // Matches Gallery reference (`LlmChatViewModel.kt:216`).
        val extraContext: Map<String, String>? =
            if (accumulateThinking) mapOf("enable_thinking" to "true") else null

        helper.runInference(
            model = model,
            input = normalized,
            resultListener = { partial, done, partialThinking ->
                // Drop emissions that arrive after stop() — LiteRT-LM may deliver a trailing
                // partial/done pair between cancelProcess() and actual thread teardown.
                if (_messages.value.lastOrNull()?.interrupted == true) return@runInference
                if (firstTokenMs == 0L && partial.isNotEmpty()) {
                    firstTokenMs = System.currentTimeMillis()
                }
                if (accumulateThinking && !partialThinking.isNullOrEmpty()) {
                    thinkingSb.append(partialThinking)
                    val snapshot = thinkingSb.toString()
                    updateLastAssistant { it.copy(thinkingText = snapshot) }
                }
                if (partial.isNotEmpty()) {
                    sb.append(partial)
                    updateLastAssistant { it.copy(text = sb.toString()) }
                }
                if (done) {
                    val totalMs = System.currentTimeMillis() - startMs
                    val ttftMs = if (firstTokenMs > 0L) (firstTokenMs - startMs) else 0L
                    val totalSec = totalMs / 1000.0
                    val footer =
                        context.getString(
                            R.string.ttft_footer_format,
                            ttftMs.toInt(),
                            totalSec,
                        )
                    updateLastAssistant { it.copy(streaming = false, footer = footer) }
                    _uiState.value = ChatUiState.Ready(isGenerating = false)
                }
            },
            cleanUpListener = { /* no-op */ },
            onError = { msg ->
                val safeMsg = msg.ifBlank { "(no message)" }
                viewModelScope.launch { errorLog.e("inference", safeMsg) }
                updateLastAssistant { it.copy(streaming = false, interrupted = true) }
                _uiState.value = ChatUiState.Ready(isGenerating = false)
            },
            images = images,
            audioClips = audioClips,
            coroutineScope = viewModelScope,
            extraContext = extraContext,
        )
    }

    fun stop() {
        val model = registry.getModel(modelName) ?: return
        helper.stopResponse(model)
        updateLastAssistant { it.copy(streaming = false, interrupted = true) }
        _uiState.value = ChatUiState.Ready(isGenerating = false)
    }

    /**
     * Reset (↻) — clear UI history and engine context but keep the engine
     * loaded (D23). The effective system prompt (defaults ∪ override) is
     * passed back into `registry.resetConversation` so the next user turn
     * starts under the same system instruction the engine had on init.
     */
    fun resetConversation() {
        viewModelScope.launch {
            val model = registry.getModel(modelName)
            val effective = model?.let { effectiveSystemPrompt(it) }
            registry.resetConversation(modelName, systemPrompt = effective)
            _messages.value = emptyList()
            _attachments.value = emptyList()
        }
    }

    /** Backwards-compat alias for the existing TopAppBar wiring. */
    fun reset() = resetConversation()

    /**
     * Persist [settings] then dispatch apply* (light / semi / heavy)
     * based on which fields actually differ from the engine's current
     * `model.configValues` (D15). Heavy applications are routed through
     * [applyHeavySetting] which surfaces the modal progress dialog; the
     * `HeavyChangeDialog` confirmation gate lives in the bottom-sheet UI
     * and must be shown by the caller before this is invoked for a
     * heavy-difference set.
     */
    fun saveAndApplySettings(settings: PerModelSettings) {
        viewModelScope.launch {
            val model = registry.getModel(modelName) ?: return@launch
            val current = model.configValues.toMap()
            val target = EffectiveConfig.merge(computeDefaults(model), settings)
            settingsRepository.savePerModelSettings(modelName, settings)
            dispatchByLevel(classifyApplyLevel(current, target))
        }
    }

    /**
     * Reset persisted overrides for the active model and apply whatever
     * level the diff between the current engine config and the allowlist
     * defaults requires (D15). Heavy reset is gated on the same
     * `HeavyChangeDialog` flow as user-edited heavy changes — the caller
     * is responsible for showing the confirm dialog when
     * [needsHeavyResetToDefaults] returns `true`.
     */
    fun resetSettingsToDefaults() {
        viewModelScope.launch {
            val model = registry.getModel(modelName) ?: return@launch
            val current = model.configValues.toMap()
            val defaults = computeDefaults(model)
            settingsRepository.resetPerModelSettings(modelName)
            dispatchByLevel(classifyApplyLevel(current, defaults))
        }
    }

    /**
     * Reads the current engine config snapshot and reports whether
     * resetting to allowlist defaults would require an engine reinit
     * (i.e. accelerator would change). The bottom sheet uses this to
     * decide whether the Default button must surface `HeavyChangeDialog`
     * first.
     */
    fun needsHeavyResetToDefaults(): Boolean {
        val model = registry.getModel(modelName) ?: return false
        val current = model.configValues
        val defaults = computeDefaults(model)
        return classifyApplyLevel(current, defaults) == ApplyLevel.HEAVY
    }

    /**
     * Returns whether applying [target] would route through the heavy
     * (cleanup + initialize) flow per current D15 classification. The
     * bottom-sheet UI calls this to decide whether to surface
     * `HeavyChangeDialog`. Single source of truth — the sheet must NOT
     * keep its own copy of the heavy-field set, so reclassification
     * (e.g. `enable_thinking` heavy → semi-light) propagates everywhere.
     */
    fun needsHeavyApply(target: PerModelSettings): Boolean {
        val model = registry.getModel(modelName) ?: return false
        val current = model.configValues
        val merged = EffectiveConfig.merge(computeDefaults(model), target)
        return classifyApplyLevel(current, merged) == ApplyLevel.HEAVY
    }

    /** Observable snapshot of the persisted overrides for the active model. */
    fun observePerModelSettings(): kotlinx.coroutines.flow.Flow<PerModelSettings?> =
        settingsRepository.observePerModelSettings(modelName)

    /**
     * Convenience accessor for the active model's current `configValues`
     * (effective = defaults ∪ overrides). Returns an empty map when the
     * registry has not published a `Ready` model yet.
     */
    fun currentEffectiveConfig(): Map<String, Any> =
        registry.getModel(modelName)?.configValues ?: emptyMap()

    /** Allowlist-default snapshot for the active model (no overrides). */
    fun allowlistDefaults(): Map<String, Any> =
        registry.getModel(modelName)?.let(::computeDefaults) ?: emptyMap()

    /**
     * Light-field apply (D15, AC-21): re-load persisted overrides and
     * reassign `model.configValues` so the next `send()` sees the new value.
     * No engine reinit, stream not interrupted, dialogs not shown.
     */
    fun applyLightOverrides() {
        viewModelScope.launch {
            val model = registry.getModel(modelName) ?: return@launch
            val overrides = settingsRepository.observePerModelSettings(modelName).first()
            val defaults = computeDefaults(model)
            model.configValues = EffectiveConfig.merge(defaults, overrides)
        }
    }

    /**
     * Semi-light apply (D15) for `systemPromptDefault` and
     * `enableThinking` (Honor 200 smoke 2026-04-18 confirmed the latter):
     * the engine stays loaded, `resetConversation(systemPrompt = …)`
     * wipes the KV cache and the next turn picks up the new flag /
     * prompt via Jinja-template rendering. UI history clears and a
     * snackbar surfaces the action.
     */
    fun applySystemPromptAndReset() {
        viewModelScope.launch {
            val model = registry.getModel(modelName) ?: return@launch
            val overrides = settingsRepository.observePerModelSettings(modelName).first()
            val defaults = computeDefaults(model)
            val merged = EffectiveConfig.merge(defaults, overrides)
            model.configValues = merged
            val effective = effectiveSystemPrompt(merged)
            registry.resetConversation(modelName, systemPrompt = effective)
            _messages.value = emptyList()
            _attachments.value = emptyList()
            _snackbar.tryEmit(R.string.settings_semilight_applied_snackbar)
        }
    }

    /**
     * Heavy-field apply (D15, D21): stop in-flight inference, reload
     * persisted overrides into `model.configValues`, then `cleanup ⇒
     * initialize` — strictly sequenced so litertlm sees a clean teardown
     * before re-allocating the new accelerator/thinking-mode engine.
     *
     * `_reinitInProgress` gates the modal `ReinitProgressDialog`. On
     * `initialize` failure the VM transitions to `Failed` and the engine
     * stays Idle (D27 / R2).
     */
    fun applyHeavySetting() {
        viewModelScope.launch {
            _reinitInProgress.value = true
            try {
                val priorModel = registry.getModel(modelName)
                val state = _uiState.value
                if (priorModel != null && state is ChatUiState.Ready && state.isGenerating) {
                    helper.stopResponse(priorModel)
                    updateLastAssistant { it.copy(streaming = false, interrupted = true) }
                    _uiState.value = ChatUiState.Ready(isGenerating = false)
                }
                val rawModel = preInitModel()
                if (rawModel == null) {
                    _reinitInProgress.value = false
                    return@launch
                }
                val overrides = settingsRepository.observePerModelSettings(modelName).first()
                val defaults = computeDefaults(rawModel)
                rawModel.configValues = EffectiveConfig.merge(defaults, overrides)

                registry.cleanup(modelName)
                registry.initialize(modelName).fold(
                    onSuccess = {
                        refreshModelCaps()
                        _uiState.value = ChatUiState.Ready(isGenerating = false)
                    },
                    onFailure = { e ->
                        val cause =
                            e.message?.takeIf { it.isNotBlank() }
                                ?: e::class.simpleName.orEmpty()
                        // Set state first so any failure inside the log path
                        // doesn't leave the UI stuck on a stale Ready state.
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

    /**
     * Decodes [uris] via [ImageDecoder], downscales to ~1024×1024, clips the
     * total image count to [MAX_IMAGES] (AC-10, R5).
     *
     * The cap is re-checked inside the final `_attachments.update { }` block
     * to close a TOCTOU race between concurrent `addImages` calls (rapid
     * double-tap of the Photo Picker). Pre-decode clipping is a courtesy
     * optimisation; the `update` block is authoritative.
     */
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

            var droppedByRace = false
            if (decoded.isNotEmpty()) {
                _attachments.update { current ->
                    val room = (MAX_IMAGES - current.count { it is Attachment.Image })
                        .coerceAtLeast(0)
                    val taken = decoded.take(room)
                    droppedByRace = decoded.size > taken.size
                    current + taken
                }
            }

            if (droppedPreDecode || droppedByRace) {
                _snackbar.tryEmit(R.string.attachment_max_images_reached)
            }
            // Fire-and-forget: errorLog.e suspends into Dispatchers.IO, which
            // detaches from the test scheduler and would otherwise leave
            // `_attachments.update` pending when `advanceUntilIdle` returns.
            // Sequencing logs in a separate launch keeps the hot path
            // deterministic for tests and for the UI state transition.
            if (failed.isNotEmpty()) {
                viewModelScope.launch {
                    for (uri in failed) errorLog.e("attachment-decode", "decode failed: $uri")
                }
            }
        }
    }

    /**
     * Adds a [bitmap] captured from CameraX directly (task 8). Large source
     * bitmaps are defensively downscaled to the same ~1024-px invariant as
     * `addImages` (R5) — camera callers should downscale upstream, but this
     * keeps the R5 contract centralised.
     */
    fun addImageBitmap(bitmap: Bitmap) {
        val currentImageCount = _attachments.value.count { it is Attachment.Image }
        if (currentImageCount >= MAX_IMAGES) {
            _snackbar.tryEmit(R.string.attachment_max_images_reached)
            return
        }
        _attachments.update { it + Attachment.Image(downscaleIfOversized(bitmap)) }
    }

    fun removeAttachment(idx: Int) {
        _attachments.update { list ->
            if (idx < 0 || idx >= list.size) list
            else list.toMutableList().also { it.removeAt(idx) }
        }
    }

    /**
     * Adds an [Attachment.Audio] produced by [AudioRecorderBottomSheet]
     * (AC-12, D5). Enforces `MAX_AUDIO_CLIPS = 1` (AC-20, D13) — if an
     * audio clip is already staged, the call is a no-op. The sheet's mic
     * button is also disabled in that state (see [MultimodalInputBar]);
     * this VM guard is a defensive second line for TOCTOU and tests.
     */
    fun addAudio(pcm: ByteArray, durationMs: Long) {
        _attachments.update { current ->
            if (current.any { it is Attachment.Audio }) current
            else current + Attachment.Audio(pcm, durationMs)
        }
    }

    /**
     * Called from [CameraBottomSheet] when CameraX bind/takePicture fails
     * (D27 — "camera" component). Logs via [ErrorLog] and surfaces a user-
     * visible snackbar. The sheet dismisses itself; no state reset needed
     * here because no attachment was added.
     */
    fun reportCameraError(description: String, cause: Throwable?) {
        viewModelScope.launch { errorLog.e("camera", description, cause) }
        _snackbar.tryEmit(R.string.camera_init_failed)
    }

    /**
     * Called from [AudioRecorderBottomSheet] when `AudioRecord` fails to
     * reach `STATE_INITIALIZED` (D27 — "audio" component, R9 for
     * MIUI/HarmonyOS). Logs and emits the `audio_record_init_failed`
     * snackbar; the sheet dismisses itself.
     */
    fun reportAudioError(description: String, cause: Throwable?) {
        viewModelScope.launch { errorLog.e("audio", description, cause) }
        _snackbar.tryEmit(R.string.audio_record_init_failed)
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope is already cancelled here; GlobalScope is lint-discouraged.
        // A fresh SupervisorJob scope lets cleanup outlive the ViewModel.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { registry.cleanup(modelName) }
    }

    // --- internals -----------------------------------------------------------

    /**
     * D15 classification of a config diff. Heavy beats semi beats light;
     * `NONE` means the diff is empty across all tracked fields.
     */
    enum class ApplyLevel { NONE, LIGHT, SYSTEM_PROMPT, HEAVY }

    private fun classifyApplyLevel(
        current: Map<String, Any>,
        target: Map<String, Any>,
    ): ApplyLevel {
        // D15 (post-2026-04-18 smoke): only `accelerator` is heavy. Smoke on
        // Honor 200 with Gemma-4-E4B-it via litertlm 0.10.0 confirmed that
        // `enable_thinking` flips correctly through `resetConversation`
        // alone — no engine teardown needed because the flag only affects
        // Jinja-template rendering of the next turn and the KV cache is
        // wiped by the reset.
        val heavyChanged = current[ConfigKeys.ACCELERATOR.label] !=
            target[ConfigKeys.ACCELERATOR.label]
        if (heavyChanged) return ApplyLevel.HEAVY
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


    /**
     * Applies persisted overrides to `model.configValues` BEFORE
     * `registry.initialize` so the engine reads the user's accelerator
     * + system prompt instead of allowlist defaults. Silent no-op when the
     * registry hasn't published the model yet — `initialize` will fail
     * later with a domain-specific error.
     */
    private suspend fun applyEffectiveConfigToModel() {
        val model = preInitModel() ?: return
        val overrides = settingsRepository.observePerModelSettings(modelName).first()
        val defaults = computeDefaults(model)
        model.configValues = EffectiveConfig.merge(defaults, overrides)
    }

    /** Look up the [Model] without the registry's `Ready`-state filter. */
    private fun preInitModel(): Model? =
        registry.models.value.find { it.model.name == modelName }?.model

    private fun computeDefaults(model: Model): Map<String, Any> =
        model.configs.associate { it.key.label to it.defaultValue }

    private fun effectiveSystemPrompt(map: Map<String, Any>): String? =
        (map[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label] as? String)?.takeIf { it.isNotBlank() }

    private fun effectiveSystemPrompt(model: Model): String? =
        effectiveSystemPrompt(model.configValues)

    private fun refreshModelCaps() {
        registry.getModel(modelName)?.let { model ->
            _modelCaps.value = ModelCapabilities(
                supportImage = model.llmSupportImage,
                supportAudio = model.llmSupportAudio,
                supportThinking = model.llmSupportThinking,
            )
        }
    }

    private inline fun updateLastAssistant(transform: (Message) -> Message) {
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

    companion object {
        const val NAV_ARG_MODEL_NAME: String = "modelName"
        private const val MAX_IMAGES: Int = 10
        private const val MAX_IMAGE_EDGE: Int = 1024

        private val LIGHT_FIELD_LABELS: Set<String> = setOf(
            ConfigKeys.TEMPERATURE.label,
            ConfigKeys.TOPK.label,
            ConfigKeys.TOPP.label,
            ConfigKeys.MAX_TOKENS.label,
        )

        // Semi-light = needs `resetConversation` (KV-cache wipe + new
        // turn picks up the change) but NOT an engine teardown.
        private val SEMI_LIGHT_FIELD_LABELS: Set<String> = setOf(
            ConfigKeys.SYSTEM_PROMPT_DEFAULT.label,
            ConfigKeys.ENABLE_THINKING.label,
        )
    }
}
