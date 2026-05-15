package app.sanctum.machina.ui.modelmanager

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.core.data.RuntimeType
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.settings.AppSettingsRepository
import app.sanctum.machina.crash.CrashState
import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.logexport.DeviceInfoProvider
import app.sanctum.machina.logexport.ExportSource
import app.sanctum.machina.logexport.LogExportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * Embedder delete-confirmation dialog state (Task 10).
 *
 * `null` means no dialog is showing; the two concrete shapes carry the data the screen renders.
 * [WarningWithProjects.projectNames] is ordered by `created_at ASC` per `ProjectDao` — locked
 * by `projectsUsingEmbedder_*` tests so screenshots / smoke runs see a stable list.
 *
 * `modelName` is the `Model.name` (display + registry key) carried so `onConfirmDelete` can
 * route the call without re-querying the row from the UI thread.
 */
sealed interface EmbedderDeleteDialogState {
    val modelName: String

    /** Zero affected projects — show the standard confirm without a list. */
    data class Confirm(override val modelName: String) : EmbedderDeleteDialogState

    /** ≥1 affected projects — show the warning with the project list. */
    data class WarningWithProjects(
        override val modelName: String,
        val projectNames: List<String>,
    ) : EmbedderDeleteDialogState
}

/**
 * Task 10 — runtime check for embedder rows. Single source of truth (Decision 12 derives
 * `runtimeType` from `taskTypes contains "llm_embedding"` at allowlist parse time, so the
 * runtime field is the only signal we need at UI time). Used by both the row treatment and
 * `setDefaultModel`'s defence-in-depth guard.
 */
internal fun ModelEntry.isEmbedder(): Boolean =
    model.runtimeType == RuntimeType.LITERT_INTERPRETER

@HiltViewModel
class ModelManagerViewModel
@Inject
constructor(
    private val registry: ModelRegistry,
    private val crashState: CrashState,
    private val logExportManager: LogExportManager,
    private val appSettings: AppSettingsRepository,
    private val deviceInfo: DeviceInfoProvider,
    private val projectRepository: ProjectRepository,
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

    private val _embedderDeleteDialog = MutableStateFlow<EmbedderDeleteDialogState?>(null)
    /** Screen-observed state for the embedder delete-confirmation dialog (Task 10). */
    val embedderDeleteDialog: StateFlow<EmbedderDeleteDialogState?> =
        _embedderDeleteDialog.asStateFlow()

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
     *
     * Task 10 defence-in-depth: embedder rows must never have their `modelId` written to
     * `default_model_id` (the field tracks the chat model used by quick chat). The UI already
     * hides the overflow item for embedder rows, but a programmatic call (test, deeplink) must
     * also short-circuit — set-default for an embedder would corrupt the chat-model contract.
     */
    fun setDefaultModel(modelId: String, modelName: String) {
        val isEmbedder = registry.models.value.firstOrNull {
            it.model.modelId == modelId
        }?.isEmbedder() == true
        if (isEmbedder) return
        viewModelScope.launch {
            appSettings.setDefaultModelId(modelId)
            _navEvents.emit(NavEvent.ShowSnackbar("Default model: $modelName"))
        }
    }

    /**
     * Task 10: tap on the embedder row's «Удалить эмбеддер» action. Resolves the affected
     * projects (Phase 4 MVP: every project depends on the single allowlisted embedder, so the
     * list is either empty or every project), then surfaces the matching dialog state to the UI.
     *
     * The lookup runs on the IO dispatcher via `projectRepository` — caller path is the user
     * tap on a menu item, response time is whatever a snapshot Room read costs (sub-ms in
     * practice).
     */
    fun onDeleteEmbedderClick(modelId: String, modelName: String) {
        viewModelScope.launch {
            val projects = projectRepository.projectsUsingEmbedder(modelId)
            _embedderDeleteDialog.value = if (projects.isEmpty()) {
                EmbedderDeleteDialogState.Confirm(modelName = modelName)
            } else {
                EmbedderDeleteDialogState.WarningWithProjects(
                    modelName = modelName,
                    projectNames = projects.map { it.name },
                )
            }
        }
    }

    /**
     * Confirm the embedder deletion shown in [embedderDeleteDialog]. Delegates to
     * [ModelRegistry.delete] (which also cancels in-flight downloads and releases the engine
     * if loaded). Clears the dialog after the registry call so the UI snaps back without
     * showing intermediate state — the row treatment flips via [registry.models].
     */
    fun onConfirmEmbedderDelete() {
        val state = _embedderDeleteDialog.value ?: return
        viewModelScope.launch {
            registry.delete(state.modelName)
            _embedderDeleteDialog.value = null
        }
    }

    /** Dismiss the embedder delete-confirmation dialog without deleting. */
    fun onDismissEmbedderDelete() {
        _embedderDeleteDialog.value = null
    }

    /** Re-read `crash.log` + `crash.log.dismissed` from disk (Decision 6). */
    fun refreshCrashState() = crashState.refresh()

    /** ✕ on the banner — create `.dismissed` flag, banner disappears. */
    fun dismissCrashBanner() = crashState.markDismissed()

    /**
     * Builds the About-path export (live logcat, matches [app.sanctum.machina.ui.diagnostics.DiagnosticsViewModel])
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
