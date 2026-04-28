package app.sanctum.machina.ui.modelmanager

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.settings.AppSettingsRepository
import app.sanctum.machina.crash.CrashState
import app.sanctum.machina.logexport.DeviceInfoProvider
import app.sanctum.machina.logexport.ExportSource
import app.sanctum.machina.logexport.LogExportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface NavEvent {
    /** Tap on "Загрузить" → open quick chat seeded with this model (AC-F4). */
    data class OpenQuickChat(val modelId: String) : NavEvent

    /** Emitted by [ModelManagerViewModel.setDefaultModel] after the setting is persisted (AC-F7). */
    data class ShowSnackbar(val message: String) : NavEvent
}

@HiltViewModel
class ModelManagerViewModel
@Inject
constructor(
    private val registry: ModelRegistry,
    private val crashState: CrashState,
    private val logExportManager: LogExportManager,
    private val appSettings: AppSettingsRepository,
    private val deviceInfo: DeviceInfoProvider,
) : ViewModel() {

    /**
     * Captured once on VM construction. `MemoryInfo.totalMem` is a kernel-static value
     * (cannot change between cold-starts), so a single read is sufficient and avoids
     * recomputation on every registry emission. Recomposition continues to pick up
     * registry changes through [rows] without re-reading device memory.
     */
    private val totalBytes: Long = deviceInfo.totalMemoryBytes()

    /**
     * Phase 3.5 Slice 1: reactive view of [registry.models] paired with a per-row
     * [GateDecision]. UI consumes this directly; the gate (`totalBytes`, `minGb`,
     * `allowed`) is materialized at every emission so a Compose recomposition picks
     * up registry changes without re-reading device memory.
     */
    val rows: StateFlow<List<ModelRowState>> =
        registry.models
            .map { entries -> entries.map(::toRowState) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = registry.models.value.map(::toRowState),
            )

    private fun toRowState(entry: ModelEntry): ModelRowState {
        val minGb = entry.model.minDeviceMemoryInGb
        return ModelRowState(
            entry = entry,
            gate = GateDecision(
                allowed = gateAllowsDownload(totalBytes, minGb),
                totalBytes = totalBytes,
                minGb = minGb ?: 0,
            ),
        )
    }

    /**
     * Flow-backed view of `crash.log` existence (Flow B / US-B, Decision 6).
     * Composable collects this and renders [app.sanctum.machina.crash.RestartCrashBanner]
     * iff `true`. Actions mutate it indirectly through [refreshCrashState],
     * [dismissCrashBanner], and [saveLogAndClearCrash] (which calls
     * `CrashState.clear()` on success), all of which end up calling
     * `CrashState.refresh()` under the hood.
     */
    val hasUnresolvedCrash: StateFlow<Boolean> = crashState.hasUnresolvedCrash

    /**
     * Stable HF [app.sanctum.machina.core.data.Model.modelId] currently marked as the user's
     * default, or `""` when unset (DataStore's proto3 default for `default_model_id`). Drives the
     * ⭐ leading indicator in the model list and the visibility of the "Сделать по умолчанию"
     * overflow item (AC-F7). `WhileSubscribed(5_000)` per patterns.md.
     */
    val defaultModelId: StateFlow<String> =
        appSettings.observeDefaultModelId()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), "")

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    fun onDownload(entry: ModelEntry) {
        // Defence-in-depth gate: UI already disables the button on a sub-threshold device,
        // but a programmatic call (test, future deeplink, accessibility action) must not
        // reach registry.download — that would enqueue a WorkManager job for a model the
        // device cannot host.
        if (!gateAllowsDownload(totalBytes, entry.model.minDeviceMemoryInGb)) return
        // registry.download() is a cold callbackFlow — without a terminal subscriber the
        // WorkManager enqueue inside the producer never runs. Subscribe in viewModelScope.
        // Status updates reach the UI via registry.models StateFlow; we only need to keep
        // the flow alive for the duration of the download.
        registry.download(entry.model).launchIn(viewModelScope)
    }

    fun onCancel(modelName: String) {
        registry.cancelDownload(modelName)
    }

    fun onLoad(modelId: String) {
        viewModelScope.launch { _navEvents.emit(NavEvent.OpenQuickChat(modelId)) }
    }

    /**
     * Persist [modelId] as the user's default and surface a Snackbar with the display [modelName].
     * Called by the overflow menu "Сделать по умолчанию" item (AC-F7, US-8 item 7).
     */
    fun setDefaultModel(modelId: String, modelName: String) {
        viewModelScope.launch {
            appSettings.setDefaultModelId(modelId)
            _navEvents.emit(NavEvent.ShowSnackbar("Модель по умолчанию: $modelName"))
        }
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
