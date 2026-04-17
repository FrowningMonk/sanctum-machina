package app.sanctum.machina.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.R
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.runtime.LlmModelHelper
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
     * Transient UI events carrying a `@StringRes` id — snackbar host collects
     * and renders. `replay = 0` + `extraBufferCapacity = 8` means tryEmit is
     * non-suspending and events survive brief UI rebinding.
     */
    private val _snackbar = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 8)
    val snackbar: SharedFlow<Int> = _snackbar.asSharedFlow()

    init {
        viewModelScope.launch {
            registry.initialize(modelName).fold(
                onSuccess = {
                    registry.getModel(modelName)?.let { model ->
                        _modelCaps.value = ModelCapabilities(
                            supportImage = model.llmSupportImage,
                            supportAudio = model.llmSupportAudio,
                            supportThinking = model.llmSupportThinking,
                        )
                    }
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
        val model = registry.getModel(modelName) ?: error("Model not initialized")

        val images = pending.filterIsInstance<Attachment.Image>().map { it.bitmap }
        val audioClips = pending.filterIsInstance<Attachment.Audio>().map { it.pcm }

        _messages.update { current ->
            current +
                Message(MessageRole.USER, normalized, attachments = pending) +
                Message(MessageRole.ASSISTANT, text = "", streaming = true)
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

        helper.runInference(
            model = model,
            input = normalized,
            resultListener = { partial, done, _ ->
                // Drop emissions that arrive after stop() — LiteRT-LM may deliver a trailing
                // partial/done pair between cancelProcess() and actual thread teardown.
                if (_messages.value.lastOrNull()?.interrupted == true) return@runInference
                if (firstTokenMs == 0L && partial.isNotEmpty()) {
                    firstTokenMs = System.currentTimeMillis()
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
            extraContext = null,
        )
    }

    fun stop() {
        val model = registry.getModel(modelName) ?: return
        helper.stopResponse(model)
        updateLastAssistant { it.copy(streaming = false, interrupted = true) }
        _uiState.value = ChatUiState.Ready(isGenerating = false)
    }

    fun reset() {
        viewModelScope.launch {
            registry.resetConversation(modelName)
            _messages.value = emptyList()
            _attachments.value = emptyList()
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
    }
}
