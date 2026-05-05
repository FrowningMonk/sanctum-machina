package app.sanctum.machina.diagnostics

/**
 * Atomic snapshot of the most recent engine init attempt. Fields captured at
 * `onInitStart` (model name, free RAM, timestamp) stay frozen for the lifetime of
 * the snapshot; only [outcome] flips on `onInitEnd`. See Phase 3.5 tech-spec
 * Decision 7 and § Data Models.
 */
data class InitSnapshot(
    val modelName: String,
    val freeRamBytes: Long,
    val atEpochMs: Long,
    val outcome: Outcome,
)

enum class Outcome { InProgress, Ok, Failed }
