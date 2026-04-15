package app.sanctum.machina.core.registry

/**
 * Lifecycle state of the native inference engine for a single model.
 *
 * - [Idle] — no engine loaded; [Ready]-only operations (runInference, resetConversation) illegal.
 * - [Initializing] — `createEngine` in flight; caller must wait.
 * - [Ready] — engine created, inference permitted.
 * - [Failed] — last `initialize` attempt failed; engine is NOT loaded; caller may retry.
 */
sealed class ModelInitStatus {
  object Idle : ModelInitStatus()

  object Initializing : ModelInitStatus()

  object Ready : ModelInitStatus()

  data class Failed(val message: String) : ModelInitStatus()
}
