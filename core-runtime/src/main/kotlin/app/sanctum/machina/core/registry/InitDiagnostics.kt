package app.sanctum.machina.core.registry

/**
 * Write-only seam over the live engine init lifecycle.
 *
 * `:core-runtime` calls [onInitStart] right before native engine initialisation begins
 * and [onInitEnd] on every terminal arm (success or failure). The implementation lives
 * in `:app` (`DiagnosticsState`) and is bound through Hilt — `:core-runtime` must not
 * depend on `:app` directly. See Phase 3.5 tech-spec Decision 6.
 */
interface InitDiagnostics {
    fun onInitStart(modelName: String, freeRamBytes: Long, atEpochMs: Long)
    fun onInitEnd(success: Boolean)
}
