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
        if (text.isBlank() && _attachments.value.isEmpty()) return
        val state = _uiState.value
        if (state !is ChatUiState.Ready || state.isGenerating) return
        val model = registry.getModel(modelName) ?: error("Model not initialized")

        _messages.update { current ->
            current + Message(MessageRole.USER, text) +
                Message(MessageRole.ASSISTANT, text = "", streaming = true)
        }
        _uiState.value = ChatUiState.Ready(isGenerating = true)

        val startMs = System.currentTimeMillis()
        var firstTokenMs = 0L
        val sb = StringBuilder()

        helper.runInference(
            model = model,
            input = text,
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
            images = emptyList(),
            audioClips = emptyList(),
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
     * total image count to [MAX_IMAGES] (AC-10, R5). Dropped entries or
     * at-limit calls emit a snackbar event. Silent on decode failure, logged
     * to `attachment-decode`.
     */
    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val currentImageCount = _attachments.value.count { it is Attachment.Image }
            val remaining = (MAX_IMAGES - currentImageCount).coerceAtLeast(0)
            val toDecode = uris.take(remaining)
            val dropped = uris.size - toDecode.size

            val newImages = mutableListOf<Attachment.Image>()
            val failed = mutableListOf<Uri>()
            for (uri in toDecode) {
                val bmp = runCatching { imageDecoder.decode(uri) }.getOrNull()
                if (bmp == null) failed += uri else newImages += Attachment.Image(bmp)
            }

            if (newImages.isNotEmpty()) {
                _attachments.update { it + newImages }
            }
            if (dropped > 0) {
                _snackbar.tryEmit(R.string.attachment_max_images_reached)
            }
            // Fire-and-forget: don't keep the caller pinned to log I/O dispatcher.
            if (failed.isNotEmpty()) {
                viewModelScope.launch {
                    for (uri in failed) errorLog.e("attachment-decode", "decode failed: $uri")
                }
            }
        }
    }

    /**
     * Adds a [bitmap] captured from CameraX directly (task 8). Silently drops
     * if already at [MAX_IMAGES] and emits a snackbar.
     */
    fun addImageBitmap(bitmap: Bitmap) {
        val currentImageCount = _attachments.value.count { it is Attachment.Image }
        if (currentImageCount >= MAX_IMAGES) {
            _snackbar.tryEmit(R.string.attachment_max_images_reached)
            return
        }
        _attachments.update { it + Attachment.Image(bitmap) }
    }

    fun removeAttachment(idx: Int) {
        _attachments.update { list ->
            if (idx < 0 || idx >= list.size) list
            else list.toMutableList().also { it.removeAt(idx) }
        }
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

    companion object {
        const val NAV_ARG_MODEL_NAME: String = "modelName"
        private const val MAX_IMAGES: Int = 10
    }
}
