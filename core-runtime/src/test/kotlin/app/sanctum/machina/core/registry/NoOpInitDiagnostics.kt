package app.sanctum.machina.core.registry

/**
 * No-op [InitDiagnostics] for tests inside `:core-runtime/registry` that need an
 * instance but do not assert on call order. Positive-assertion tests for
 * `DefaultModelRegistry` use a separate `RecordingInitDiagnostics` introduced in
 * Task 6.
 */
class NoOpInitDiagnostics : InitDiagnostics {
    override fun onInitStart(modelName: String, freeRamBytes: Long, atEpochMs: Long) = Unit
    override fun onInitEnd(success: Boolean) = Unit
}
