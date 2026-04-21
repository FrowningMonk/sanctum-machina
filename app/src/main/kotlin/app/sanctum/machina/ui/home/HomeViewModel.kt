package app.sanctum.machina.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.registry.ModelRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
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
 */
@HiltViewModel
class HomeViewModel
@Inject
constructor(
    registry: ModelRegistry,
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
}
