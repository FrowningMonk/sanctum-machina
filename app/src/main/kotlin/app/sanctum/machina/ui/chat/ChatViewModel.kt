package app.sanctum.machina.ui.chat

import android.content.Context
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Ready(val isGenerating: Boolean) : ChatUiState
    data class Failed(val rawCause: String) : ChatUiState
}

@HiltViewModel
class ChatViewModel
@Inject
constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: ModelRegistry,
    private val helper: LlmModelHelper,
    private val errorLog: ErrorLog,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val modelName: String =
        savedStateHandle.get<String>(NAV_ARG_MODEL_NAME)
            ?: error("modelName nav-arg is required")

    private val _uiState = MutableStateFlow<ChatUiState>(ChatUiState.Loading)
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    init {
        viewModelScope.launch {
            registry.initialize(modelName).fold(
                onSuccess = { _uiState.value = ChatUiState.Ready(isGenerating = false) },
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
        if (text.isBlank()) return
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
    }
}
