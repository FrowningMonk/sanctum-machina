package app.sanctum.machina.core.registry

import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Central coordinator for model discovery, download, and native inference-engine lifecycle.
 *
 * Single source of truth for [ChatViewModel]/[ModelManagerViewModel] (Phase 1 Tasks 8–9):
 * all lifecycle transitions (download, init, cleanup, reset, delete) are funnelled through this
 * interface. The [DefaultModelRegistry] implementation serialises every lifecycle operation under
 * a process-wide [kotlinx.coroutines.sync.Mutex] and replaces the Gallery `Model.initializing` +
 * `cleanUpAfterInit` flag pair (user-spec Risks R3 / Decision T9).
 */
interface ModelRegistry {
  /**
   * Hot stream of every known model with its current download + init state. Updated by
   * [refreshAllowlist], [download], [initialize], [cleanup], [delete] and background scans.
   */
  val models: StateFlow<List<ModelEntry>>

  /**
   * Stable HF [Model.modelId] (e.g. `"litert-community/gemma-4-E4B-it-litert-lm"`) of the single
   * [ModelEntry] currently in [ModelInitStatus.Ready], or `null` if no model is Ready.
   *
   * Note: the name is `activeModelName` for API compatibility with Phase-3 consumers (DrawerContent,
   * ChatViewModel, WarmupCoordinator), but the emitted value is [Model.modelId] — the stable HF
   * identifier stored in Room as `chat.model_id` — NOT [Model.name] (the display-adjacent storage
   * filename). Equality with `chat.model_id` is what drives the same-model fast path and the
   * TopAppBar "Загрузить" button decision.
   *
   * Single-engine invariant (Phase-1, Decision T9): only one entry can be [ModelInitStatus.Ready]
   * at a time; the derived flow exploits this and uses `firstOrNull { ... Ready }`.
   */
  val activeModelName: StateFlow<String?>

  /**
   * Load the bundled allowlist JSON via [AllowlistLoader] and republish [models].
   *
   * On I/O or schema error: logs to `"download"` (allowlist parsing is part of download-discovery)
   * and returns [Result.failure]; the existing `models` value is left untouched.
   */
  suspend fun refreshAllowlist(): Result<Unit>

  /**
   * Start a download for [model] and stream status updates. Bridges the callback-based
   * [app.sanctum.machina.core.data.DownloadRepository.downloadModel] into a cold [Flow]. Side-effects
   * update the corresponding [ModelEntry.downloadStatus] inside [models] as events arrive.
   */
  fun download(model: Model): Flow<ModelDownloadStatus>

  /**
   * Cancel an in-flight download. No-op if [modelName] is unknown or not downloading. Cancellation
   * is delegated to WorkManager; the emitted Flow from [download] will terminate when the worker
   * reports CANCELLED.
   */
  fun cancelDownload(modelName: String)

  /**
   * Delete on-device files for [modelName]. If the engine is currently loaded, [cleanup] is called
   * first. Runs under the registry lifecycle mutex.
   */
  suspend fun delete(modelName: String)

  /**
   * Create and warm up the native inference engine for [modelName]. Implements unconditional
   * GPU→CPU fallback (Decision T8): any non-empty first-attempt error is treated as a signal to
   * retry on CPU after cleanUp-guarded release. Runs under the registry lifecycle mutex.
   *
   * Caller must call [cleanup] for any previously active model before invoking [initialize] on a
   * new one — the registry's Phase-1 contract is single-active-engine.
   */
  suspend fun initialize(modelName: String): Result<Unit>

  /**
   * Release the native inference engine for [modelName]. Implements stale-instance guard
   * (Decision T9): if `model.instance` has been swapped since the guard captured it, the cleanup
   * aborts to avoid double-closing a foreign engine. Runs under the registry lifecycle mutex.
   */
  suspend fun cleanup(modelName: String)

  /**
   * Reset the LiteRT-LM conversation for [modelName] without rebuilding the engine. The string
   * [systemPrompt] (null = no system instruction) is converted internally to LiteRT-LM `Contents`.
   * Runs under the registry lifecycle mutex.
   */
  suspend fun resetConversation(modelName: String, systemPrompt: String? = null)

  /**
   * Read-only accessor used by Task 9 `ChatViewModel` to obtain a ready-to-run [Model] (with
   * `model.instance` populated). Returns `null` if [modelName] is unknown or the engine is not
   * in [ModelInitStatus.Ready].
   */
  fun getModel(modelName: String): Model?
}
