package app.sanctum.machina.diagnostics

import app.sanctum.machina.core.registry.InitDiagnostics
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton holder for the most recent [InitSnapshot]. Writers live in `:core-runtime`
 * (`DefaultModelRegistry`, Task 6); readers live in `:app` (`DeviceInfoCollector`,
 * `DiagnosticsViewModel`, Tasks 7–8). The seam between writer and `:app` is the
 * [InitDiagnostics] interface (Decision 6).
 *
 * `AtomicReference` rather than `@Volatile` because `onInitEnd` is read-modify-write
 * (`copy(outcome = ...)`) — without CAS a future second writer would risk a lost
 * update. Visibility between writer-thread (`Dispatchers.Default` under
 * `lifecycleMutex`) and reader-thread (Main) is guaranteed by the atomic.
 * See Phase 3.5 tech-spec Decision 7.
 */
@Singleton
open class DiagnosticsState @Inject constructor() : InitDiagnostics {

    private val ref = AtomicReference<InitSnapshot?>(null)

    override fun onInitStart(modelName: String, freeRamBytes: Long, atEpochMs: Long) {
        ref.set(InitSnapshot(modelName, freeRamBytes, atEpochMs, Outcome.InProgress))
    }

    override fun onInitEnd(success: Boolean) {
        ref.updateAndGet { current ->
            current?.copy(outcome = if (success) Outcome.Ok else Outcome.Failed)
        }
    }

    open fun lastInitSnapshot(): InitSnapshot? = ref.get()
}
