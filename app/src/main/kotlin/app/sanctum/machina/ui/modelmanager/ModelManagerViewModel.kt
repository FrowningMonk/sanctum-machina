package app.sanctum.machina.ui.modelmanager

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.crash.CrashState
import app.sanctum.machina.logexport.ExportSource
import app.sanctum.machina.logexport.LogExportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.launch

sealed interface NavEvent {
    data class OpenChat(val modelName: String) : NavEvent
}

@HiltViewModel
class ModelManagerViewModel
@Inject
constructor(
    private val registry: ModelRegistry,
    private val crashState: CrashState,
    private val logExportManager: LogExportManager,
) : ViewModel() {

    val models: StateFlow<List<ModelEntry>> = registry.models

    /**
     * Flow-backed view of `crash.log` existence (Flow B / US-B, Decision 6).
     * Composable collects this and renders [app.sanctum.machina.crash.RestartCrashBanner]
     * iff `true`. Actions mutate it indirectly through [refreshCrashState],
     * [dismissCrashBanner], and [clearCrashState], all of which end up calling
     * `CrashState.refresh()` under the hood.
     */
    val hasUnresolvedCrash: StateFlow<Boolean> = crashState.hasUnresolvedCrash

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    fun onDownload(entry: ModelEntry) {
        // registry.download() is a cold callbackFlow — without a terminal subscriber the
        // WorkManager enqueue inside the producer never runs. Subscribe in viewModelScope.
        // Status updates reach the UI via registry.models StateFlow; we only need to keep
        // the flow alive for the duration of the download.
        registry.download(entry.model).launchIn(viewModelScope)
    }

    fun onCancel(modelName: String) {
        registry.cancelDownload(modelName)
    }

    fun onLoad(modelName: String) {
        viewModelScope.launch { _navEvents.emit(NavEvent.OpenChat(modelName)) }
    }

    /** Re-read `crash.log` + `crash.log.dismissed` from disk (Decision 6). */
    fun refreshCrashState() = crashState.refresh()

    /** ✕ on the banner — create `.dismissed` flag, banner disappears. */
    fun dismissCrashBanner() = crashState.markDismissed()

    /**
     * Builds the About-path export (live logcat, matches [app.sanctum.machina.ui.about.AboutViewModel])
     * and writes it to the SAF-picked URI. On success, clears the crash state so
     * the banner goes away. Returns [Result.failure] on [IOException] — the caller
     * shows the failure Snackbar and leaves `crash.log` on disk for a retry.
     */
    suspend fun saveLogAndClearCrash(uri: Uri): Result<Unit> = try {
        val content = logExportManager.buildExport(ExportSource.About)
        logExportManager.writeTo(uri, content)
        crashState.clear()
        Result.success(Unit)
    } catch (e: IOException) {
        Result.failure(e)
    }
}
