package app.sanctum.machina.ui.modelmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

sealed interface NavEvent {
    data class OpenChat(val modelName: String) : NavEvent
}

@HiltViewModel
class ModelManagerViewModel
@Inject
constructor(private val registry: ModelRegistry) : ViewModel() {

    val models: StateFlow<List<ModelEntry>> = registry.models

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    fun onDownload(entry: ModelEntry) {
        registry.download(entry.model)
    }

    fun onCancel(modelName: String) {
        registry.cancelDownload(modelName)
    }

    fun onLoad(modelName: String) {
        viewModelScope.launch { _navEvents.emit(NavEvent.OpenChat(modelName)) }
    }
}
