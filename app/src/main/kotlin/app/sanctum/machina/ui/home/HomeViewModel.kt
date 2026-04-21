package app.sanctum.machina.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.engine.AppCorruptionState
import app.sanctum.machina.logexport.ExportSource
import app.sanctum.machina.logexport.LogExportManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Drives [HomeScreen] state (Phase-3 Task 7).
 *
 * Exposes the first-run gate [hasDownloadedModels]: `true` iff at least one [ModelEntry] is
 * in [ModelDownloadStatusType.SUCCEEDED] — the on-disk predicate (NOT the engine-init
 * predicate). When `false`, HomeScreen shows the "download a model first" placeholder
 * (AC-F2, US-8). The placeholder is the correct render for a genuinely empty registry
 * (e.g. before the allowlist loads) — the user is asked to start with Model Manager.
 *
 * [activeModelName] is a pass-through of [ModelRegistry.activeModelName], consumed by the
 * HomeScreen bottom status chip.
 *
 * Task-10 additions:
 *  - [corruptionOccurred] — one-shot seed for the AC-D5 banner. Read once from the
 *    `@Singleton AppCorruptionState`; the HomeScreen keeps the dismissal in a local
 *    `remember` so flipping is not persisted.
 *  - [buildAndWrite] — wraps [LogExportManager] for the banner's "Сохранить лог" action.
 *    Mirrors the contract used by `AboutScreen`'s export path — one `Result<Unit>` for one
 *    snackbar, no exception leaking into UI state.
 */
@HiltViewModel
class HomeViewModel
@Inject
constructor(
    registry: ModelRegistry,
    appCorruptionState: AppCorruptionState,
    private val logExportManager: LogExportManager,
) : ViewModel() {

    val hasDownloadedModels: StateFlow<Boolean> = registry.models
        .map { entries ->
            entries.any { it.downloadStatus.status == ModelDownloadStatusType.SUCCEEDED }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = false,
        )

    val activeModelName: StateFlow<String?> = registry.activeModelName

    /** Seeded from the process-scoped flag — `true` iff `AppModule.provideSanctumDatabase`
     *  had to rename a corrupt db on this process launch (AC-R6). */
    val corruptionOccurred: Boolean = appCorruptionState.corruptionOccurred

    /**
     * Builds an About-path export and writes it to the SAF-picked URI. Returns
     * [Result.failure] wrapping the [IOException] so the caller renders one snackbar
     * without leaking exceptions into UI state. Mirrors `AboutViewModel.buildAndWrite`.
     */
    suspend fun buildAndWrite(uri: Uri): Result<Unit> = try {
        val content = logExportManager.buildExport(ExportSource.About)
        logExportManager.writeTo(uri, content)
        Result.success(Unit)
    } catch (e: IOException) {
        Result.failure(e)
    }
}
