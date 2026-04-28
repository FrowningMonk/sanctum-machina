package app.sanctum.machina.core.registry

/**
 * Recording [InitDiagnostics] test fake that captures every call into a list, used by
 * `DefaultModelRegistryTest` for positive call-order assertions on Task 6 plumbing.
 *
 * Single-threaded by design — `DefaultModelRegistry` writers are serialised by
 * `lifecycleMutex`, so registry tests do not need synchronisation. `DiagnosticsState`'s
 * own atomicity is exercised separately in `:app/diagnostics`.
 */
class RecordingInitDiagnostics : InitDiagnostics {
    val starts: MutableList<Triple<String, Long, Long>> = mutableListOf()
    val ends: MutableList<Boolean> = mutableListOf()
    private val order: MutableList<String> = mutableListOf()

    override fun onInitStart(modelName: String, freeRamBytes: Long, atEpochMs: Long) {
        starts += Triple(modelName, freeRamBytes, atEpochMs)
        order += "start:$modelName"
    }

    override fun onInitEnd(success: Boolean) {
        ends += success
        order += "end:$success"
    }

    /** Snapshot of call order, e.g. `["start:Gemma-4-E4B-it", "end:true"]`. */
    fun callOrder(): List<String> = order.toList()
}
