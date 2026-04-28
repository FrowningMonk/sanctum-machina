package app.sanctum.machina.core.registry

/**
 * Write-only seam over the live engine init lifecycle.
 *
 * Two-event protocol: exactly one [onInitStart] per init attempt, exactly one
 * [onInitEnd] (either success or failure arm). No mid-progress events — by design,
 * so the snapshot captures one decisive RAM measurement at the start, plus one
 * outcome flip at the end. CPU fallback after a GPU failure is still one attempt
 * from the user's perspective and shares the original `onInitStart` snapshot.
 *
 * `:core-runtime` calls these methods; the implementation lives in `:app`
 * (`DiagnosticsState`) and is bound through Hilt — `:core-runtime` must not depend
 * on `:app` directly. See Phase 3.5 tech-spec Decision 6.
 */
interface InitDiagnostics {
    fun onInitStart(modelName: String, freeRamBytes: Long, atEpochMs: Long)
    fun onInitEnd(success: Boolean)
}
