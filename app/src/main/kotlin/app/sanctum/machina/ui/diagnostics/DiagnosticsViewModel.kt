package app.sanctum.machina.ui.diagnostics

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.diagnostics.DiagnosticsState
import app.sanctum.machina.logexport.DeviceInfoProvider
import app.sanctum.machina.logexport.ExportSource
import app.sanctum.machina.logexport.LogExporter
import app.sanctum.machina.logexport.formatLastInit
import app.sanctum.machina.ui.modelmanager.formatGbFloor
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * Backing state for [DiagnosticsScreen]. Both fields are pre-rendered Russian
 * strings so the screen has no formatting logic — keeps the screen a thin
 * Composable and the formatting fully unit-testable here.
 */
data class DiagnosticsUiState(
    val lastInitText: String,
    val freeRamText: String,
)

/**
 * ViewModel for the Diagnostics screen (Phase-3.5 Task 8). Reads the latest
 * [app.sanctum.machina.diagnostics.InitSnapshot] via [DiagnosticsState] and the
 * device's free RAM via [DeviceInfoProvider]; both are recomposed on every
 * 1-second tick while the screen is subscribed to the [state] flow. Also owns
 * the SAF-export entry point migrated from `AboutViewModel` (Decision 11).
 *
 * The polling loop lives inside a `flow { ... }` started lazily via
 * [stateIn] with [SharingStarted.WhileSubscribed]: the tick only runs while
 * Compose is collecting (`collectAsStateWithLifecycle`), and stops 0 ms after
 * the last unsubscribe — so the loop never leaks past the screen's lifecycle.
 * `viewModelScope` cancellation on Hilt teardown stops the flow as a backstop.
 *
 * The seed value is computed synchronously so the UI never shows a placeholder
 * frame after composition.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val diagnosticsState: DiagnosticsState,
    private val deviceInfo: DeviceInfoProvider,
    private val logExporter: LogExporter,
) : ViewModel() {

    val state: StateFlow<DiagnosticsUiState> = flow {
        while (true) {
            emit(snapshot())
            delay(POLL_INTERVAL_MS)
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0L),
        initialValue = snapshot(),
    )

    /**
     * Builds the export and writes it to the SAF-picked URI. Mirrors the
     * deleted `AboutViewModel.buildAndWrite` (Decision 11) — same `Result`
     * shape so the snackbar-rendering pattern stays unchanged.
     */
    suspend fun buildAndWrite(uri: Uri): Result<Unit> = try {
        val content = logExporter.buildExport(ExportSource.About)
        logExporter.writeTo(uri, content)
        Result.success(Unit)
    } catch (e: IOException) {
        Result.failure(e)
    }

    private fun snapshot(): DiagnosticsUiState = DiagnosticsUiState(
        lastInitText = formatLastInit(diagnosticsState.lastInitSnapshot()),
        freeRamText = "Свободно: ${formatGbFloor(deviceInfo.availableMemoryBytes())} ГБ",
    )

    private companion object {
        const val POLL_INTERVAL_MS: Long = 1_000L
    }
}
