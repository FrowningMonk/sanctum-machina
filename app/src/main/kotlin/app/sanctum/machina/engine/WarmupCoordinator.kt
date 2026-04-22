package app.sanctum.machina.engine

import androidx.annotation.VisibleForTesting
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.settings.AppSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val LOG_WARMUP = "engine-warmup"
private const val LOG_SETTINGS = "settings-io"

/**
 * App-level owner of the background inference-engine warmup lifecycle (Phase-3 Decision 3).
 *
 * Injects both [ModelRegistry] (`:core-runtime`) and [AppSettingsRepository] (`:core-settings`)
 * — a combination only legal at the `:app` layer, since `:core-runtime` cannot depend on
 * `:core-settings`. Drives the cold-start sequence described in the tech-spec Architecture
 * section: resolves the default model on behalf of [SanctumApplication] and runs
 * `registry.initialize(modelId)` on a background coroutine so the first chat message doesn't
 * block on a 20–30 s engine warmup.
 *
 * Serialises warmup lifecycle via [restartMutex]: `warmupDefault` and `cancelAndRestart` both
 * go through [launchWarmup], which [cancelAndJoin]s the in-flight [warmupJob] before launching
 * the next one — this is what guards against the 50–60 s double-wait described in the user-spec
 * Risks section when the user taps "Load" for a second model while the default warmup still
 * holds `lifecycleMutex` inside `DefaultModelRegistry`.
 *
 * Also owns the AC-F3 observer: a one-shot background collector of [ModelRegistry.models] that
 * auto-sets `default_model_id` the first time any model transitions to
 * [ModelDownloadStatusType.SUCCEEDED] while the setting is still empty.
 */
@Singleton
// `open` so `SanctumApplicationTest` can substitute a recording fake to prove `warmupDefault()`
// is launched inside the `packageName` guard (AC / tech-spec Cold start sequence step 1).
open class WarmupCoordinator @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE) internal constructor(
  private val registry: ModelRegistry,
  private val appSettings: AppSettingsRepository,
  private val errorLog: ErrorLog,
  private val scope: CoroutineScope,
) {

  @Inject
  constructor(
    registry: ModelRegistry,
    appSettings: AppSettingsRepository,
    errorLog: ErrorLog,
  ) : this(
    registry = registry,
    appSettings = appSettings,
    errorLog = errorLog,
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  )

  private val _isWarmupInProgress = MutableStateFlow(false)

  /**
   * `true` while a warmup coroutine is active (engine init in flight); `false` when idle,
   * completed, or cancelled. Task 10 (ChatScreen TopAppBar) reads this to drive the reinit
   * spinner.
   *
   * Transition discipline: flipped to `true` synchronously inside [launchWarmup] under
   * [restartMutex] *before* the new Job starts, and reset in a `finally {}` block so both normal
   * completion and cancellation reliably clear it.
   */
  // `open` so `ChatViewModelTest` (Task 10) can substitute a stub coordinator whose flag is driven
  // from the test scheduler without hitting the real scope / mutex / registry.initialize path.
  open val isWarmupInProgress: StateFlow<Boolean> = _isWarmupInProgress.asStateFlow()

  private val restartMutex = Mutex()
  private var warmupJob: Job? = null

  init {
    startDefaultModelObserver()
  }

  /**
   * Resolve the model to warm (default → last-used → nothing, per AC-F5) and kick off a new
   * warmup Job. If a prior warmup is still in flight, it is cancelled and joined first (same
   * logic as [cancelAndRestart]).
   */
  open fun warmupDefault() {
    scope.launch {
      val modelId = resolveModelId() ?: return@launch
      launchWarmup(modelId)
    }
  }

  /**
   * Cancel any in-flight warmup and start a new one for [modelId]. Used by cross-model "Load"
   * flows where the user wants to switch engines while a default warmup is already running.
   */
  open fun cancelAndRestart(modelId: String) {
    scope.launch { launchWarmup(modelId) }
  }

  private suspend fun resolveModelId(): String? {
    val default = appSettings.getDefaultModelId()
    if (default.isNotEmpty()) return default
    val last = appSettings.getLastUsedModelId()
    if (last.isNotEmpty()) return last
    return null
  }

  private suspend fun launchWarmup(modelId: String) {
    restartMutex.withLock {
      // Disown the previous Job BEFORE cancelling, so its `finally` skips the flag-reset
      // (see check below). Together with writing `true` here, this guarantees the StateFlow
      // stays at `true` across the cancel-and-restart handover — no transient `false` gap for
      // Task-10's TopAppBar spinner to flicker on.
      val previous = warmupJob
      warmupJob = null
      _isWarmupInProgress.value = true
      previous?.cancelAndJoin()

      warmupJob = scope.launch {
        try {
          val result = registry.initialize(modelId)
          result
            .onSuccess { persistLastUsedModelId(modelId) }
            .onFailure { cause ->
              errorLog.e(LOG_WARMUP, "initialize failed for $modelId", cause)
            }
        } finally {
          // Only clear the flag if we are still the current warmup. A successor
          // `launchWarmup` will have nulled / re-assigned `warmupJob` and takes ownership of
          // the next `true → false` transition — avoiding the flicker described above.
          if (warmupJob === coroutineContext[Job]) {
            _isWarmupInProgress.value = false
          }
        }
      }
    }
  }

  private suspend fun persistLastUsedModelId(modelId: String) {
    try {
      appSettings.setLastUsedModelId(modelId)
    } catch (ce: CancellationException) {
      throw ce
    } catch (cause: Throwable) {
      // DataStore write failure is logged (project-wide convention: every failure path runs
      // through ErrorLog). The warmup itself already succeeded — a lost `last_used_model_id`
      // write only degrades the Phase-3 fallback hint on the next cold start.
      errorLog.e(LOG_SETTINGS, "last_used_model_id write failed for $modelId", cause)
    }
  }

  private fun startDefaultModelObserver() {
    scope.launch {
      // `coroutineContext[Job]` references this coroutine's own Job — used below to stop
      // collecting after the one-shot auto-default write succeeds, without needing a lateinit
      // field or a shared-state flag.
      val ownJob = requireNotNull(coroutineContext[Job]) {
        "observer must run in a cancellable Job context"
      }
      try {
        registry.models.collect { list ->
          val downloaded = list.firstOrNull {
            it.downloadStatus.status == ModelDownloadStatusType.SUCCEEDED
          } ?: return@collect
          if (appSettings.getDefaultModelId().isEmpty()) {
            val modelId = downloaded.model.modelId
            appSettings.setDefaultModelId(modelId)
            // Cold start with no default skipped warmup; downloading the first model
            // completes the setup step, so trigger warmup now — otherwise Home's
            // "Начать быстрый чат" would suspend on registry.activeModelName.first()
            // forever (ChatViewModel's bootstrap contract).
            launchWarmup(modelId)
            ownJob.cancel()
          }
        }
      } catch (ce: CancellationException) {
        throw ce
      } catch (cause: Throwable) {
        // DataStore read/write failure inside the observer: log once and let the coroutine
        // terminate. Re-attaching is not worth the complexity for a best-effort auto-default.
        errorLog.e(LOG_SETTINGS, "AC-F3 auto-default observer failed", cause)
      }
    }
  }
}
