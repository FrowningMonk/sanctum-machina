package app.sanctum.machina.core.registry

import android.content.Context
import app.sanctum.machina.core.data.Accelerator
import app.sanctum.machina.core.data.ConfigKeys
import app.sanctum.machina.core.data.DownloadRepository
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.data.TMP_FILE_EXT
import app.sanctum.machina.core.inference.LlmModelInstance
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.runtime.LlmModelHelper
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val LOG_TAG_DOWNLOAD = "download"
private const val LOG_TAG_INIT = "inference-init"
// LOG_TAG_CLEANUP ("inference-cleanup") — whitelisted in user-spec D11; not used yet because
// LlmChatModelHelper.cleanUp swallows its own exceptions internally. Phase 2 debt: surface
// close-failures via ErrorLog if/when cleanUp grows a throwing error path.

/**
 * Phase-1 implementation of [ModelRegistry]. Process-wide `@Singleton` owning:
 *
 * - A [MutableStateFlow] of every known [ModelEntry].
 * - A single [Mutex] serialising every lifecycle transition (initialize / cleanup /
 *   resetConversation / delete), replacing Gallery's `Model.initializing` + `cleanUpAfterInit`
 *   flag pair (user-spec R3, Decision T9).
 * - A private [CoroutineScope] rooted on a [SupervisorJob] used only for startup scan and
 *   the callback→[Flow] bridge for downloads.
 */
@Singleton
class DefaultModelRegistry
@Inject
constructor(
  private val downloadRepository: DownloadRepository,
  private val llmModelHelper: LlmModelHelper,
  private val allowlistLoader: AllowlistLoader,
  private val errorLog: ErrorLog,
  @ApplicationContext private val context: Context,
) : ModelRegistry {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val lifecycleMutex = Mutex()

  private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
  override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

  init {
    scope.launch {
      runCatching {
          refreshAllowlist()
          scanLocalFiles()
          resumePartialDownloads()
        }
        .onFailure { cause ->
          if (cause is CancellationException) throw cause
          errorLog.e(LOG_TAG_DOWNLOAD, "startup scan failed", cause)
        }
    }
  }

  override suspend fun refreshAllowlist(): Result<Unit> {
    val loaded =
      allowlistLoader.load().getOrElse { cause ->
        errorLog.e(LOG_TAG_DOWNLOAD, "allowlist load failed", cause)
        return Result.failure(cause)
      }
    loaded.forEach { it.preProcess() }
    _models.update { current ->
      val existing = current.associateBy { it.model.name }
      loaded.map { model ->
        existing[model.name]?.copy(model = model)
          ?: ModelEntry(
            model = model,
            downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED),
            initStatus = ModelInitStatus.Idle,
          )
      }
    }
    return Result.success(Unit)
  }

  override fun download(model: Model): Flow<ModelDownloadStatus> = callbackFlow {
    downloadRepository.downloadModel(model) { _, status ->
      updateEntry(model.name) { it.copy(downloadStatus = status) }
      trySend(status)
    }
    // Phase-2 debt: flow-collector cancellation does NOT propagate to WorkManager or detach the
    // `observeForever` LiveData observer inside DefaultDownloadRepository.observerWorkerProgress
    // (see Task 3 decisions.md: `observeForever` leak listed as inherited Gallery bug). External
    // cancellation goes through [cancelDownload]. See code-reviewer-1.json finding M2.
    awaitClose { }
  }

  override fun cancelDownload(modelName: String) {
    val entry = _models.value.find { it.model.name == modelName } ?: return
    downloadRepository.cancelDownloadModel(entry.model)
  }

  override suspend fun delete(modelName: String) {
    lifecycleMutex.withLock {
      val entry = _models.value.find { it.model.name == modelName } ?: return@withLock
      // Cancel any in-flight download first so the worker can't resurrect the file we're about
      // to delete (security-auditor-1 SM1).
      downloadRepository.cancelDownloadModel(entry.model)
      if (entry.initStatus !== ModelInitStatus.Idle) {
        releaseEngine(entry.model)
      }
      val file = File(entry.model.getPath(context))
      if (file.exists()) file.delete()
      _models.update { list ->
        list.map {
          if (it.model.name == modelName)
            it.copy(
              downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED),
              initStatus = ModelInitStatus.Idle,
            )
          else it
        }
      }
    }
  }

  // Hops off Main for the whole lifecycle section: LlmChatModelHelper.initialize is synchronous
  // and blocks for 5-30s while LiteRT warms the GPU backend; leaving it on Main freezes Compose
  // so the ChatScreen Loading state never reaches a frame.
  override suspend fun initialize(modelName: String): Result<Unit> =
    withContext(Dispatchers.Default) {
      lifecycleMutex.withLock {
        val entry =
          _models.value.find { it.model.name == modelName }
            ?: return@withLock Result.failure(
              IllegalArgumentException("unknown model: $modelName")
            )
        val model = entry.model
        // Flip to Initializing first so the StateFlow never advertises Ready over a null instance
        // during the subsequent releaseEngine window (code-reviewer-2 R2-Min-2).
        updateEntry(modelName) { it.copy(initStatus = ModelInitStatus.Initializing) }
        // Idempotent re-initialize: release any prior engine so the new init never leaks a
        // still-allocated native instance (security-auditor-1 SM2). Safe no-op when Idle.
        releaseEngine(model)

        val err1 = awaitInitialize(model)
        if (err1.isEmpty() && model.instance != null) {
          updateEntry(modelName) { it.copy(initStatus = ModelInitStatus.Ready) }
          return@withLock Result.success(Unit)
        }

        // T8: unconditional GPU→CPU fallback. No error-text parsing.
        errorLog.e(LOG_TAG_INIT, "GPU init failed: $err1")
        // Guard against partial native allocation before retry.
        llmModelHelper.cleanUp(model, onDone = { })
        model.configValues =
          model.configValues + (ConfigKeys.ACCELERATOR.label to Accelerator.CPU.label)

        val err2 = awaitInitialize(model)
        if (err2.isEmpty() && model.instance != null) {
          updateEntry(modelName) { it.copy(initStatus = ModelInitStatus.Ready) }
          return@withLock Result.success(Unit)
        }

        errorLog.e(LOG_TAG_INIT, "CPU init failed: $err2")
        updateEntry(modelName) { it.copy(initStatus = ModelInitStatus.Failed(err2)) }
        Result.failure(RuntimeException("GPU+CPU init failed: $err2"))
      }
    }

  override suspend fun cleanup(modelName: String) {
    withContext(Dispatchers.Default) {
      lifecycleMutex.withLock {
        val entry = _models.value.find { it.model.name == modelName } ?: return@withLock
        releaseEngine(entry.model)
        updateEntry(modelName) { it.copy(initStatus = ModelInitStatus.Idle) }
      }
    }
  }

  override suspend fun resetConversation(modelName: String, systemPrompt: String?) {
    withContext(Dispatchers.Default) {
      lifecycleMutex.withLock {
        val entry = _models.value.find { it.model.name == modelName } ?: return@withLock
        if (entry.initStatus !== ModelInitStatus.Ready) return@withLock
        val contents: Contents? = systemPrompt?.let { Contents.of(listOf(Content.Text(it))) }
        llmModelHelper.resetConversation(entry.model, systemInstruction = contents)
      }
    }
  }

  override fun getModel(modelName: String): Model? {
    val entry = _models.value.find { it.model.name == modelName } ?: return null
    return if (entry.initStatus === ModelInitStatus.Ready) entry.model else null
  }

  // --- internals -------------------------------------------------------------

  /**
   * T9: stale-instance guard + inference stop + cleanUp. Caller must hold [lifecycleMutex].
   *
   * Captures the current native instance, verifies it was not swapped, stops any in-flight
   * inference (safe no-op otherwise), then releases the engine. Silent no-op if the model has no
   * loaded instance.
   */
  private fun releaseEngine(model: Model) {
    val currentInstance = model.instance as? LlmModelInstance
    if (currentInstance == null) {
      // Already released; align the flag and exit.
      model.instance = null
      return
    }
    // Stale-instance guard (Decision T9, user-spec R3). Under the current design every caller
    // holds `lifecycleMutex`, so `model.instance` cannot change between capture and check —
    // the guard is anchor-only today (grep-verified by Task 6 smoke). It stays defensively so
    // any future refactor that exposes a non-mutex release path still avoids double-closing a
    // foreign engine (SIGSEGV risk, research §4/§12).
    val sameInstance = currentInstance === model.instance
    if (!sameInstance) return

    // Stop any active inference before closing native engine (research §4).
    llmModelHelper.stopResponse(model)
    llmModelHelper.cleanUp(model, onDone = { })
    model.instance = null
  }

  private suspend fun awaitInitialize(model: Model): String =
    suspendCancellableCoroutine { cont ->
      llmModelHelper.initialize(
        context = context,
        model = model,
        supportImage = model.llmSupportImage,
        supportAudio = model.llmSupportAudio,
        onDone = { err -> if (cont.isActive) cont.resume(err) },
      )
      cont.invokeOnCancellation {
        // Best-effort release if coroutine is cancelled mid-initialize.
        runCatching { llmModelHelper.cleanUp(model, onDone = { }) }
      }
    }

  private suspend fun scanLocalFiles() {
    val snapshot = _models.value
    snapshot.forEach { entry ->
      runCatching {
          val present = File(entry.model.getPath(context)).exists()
          if (present) {
            updateEntry(entry.model.name) {
              it.copy(
                downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED)
              )
            }
          }
        }
        .onFailure { cause ->
          if (cause is CancellationException) throw cause
          errorLog.e(LOG_TAG_DOWNLOAD, "scan ${entry.model.name} failed", cause)
        }
    }
  }

  private suspend fun resumePartialDownloads() {
    val snapshot = _models.value
    snapshot.forEach { entry ->
      runCatching {
          val tmp = File("${entry.model.getPath(context)}.$TMP_FILE_EXT")
          if (tmp.exists()) {
            // Route resume through the public `download()` API so late subscribers also observe
            // progress and the StateFlow/Flow contract is symmetric (code-reviewer-1 M3).
            download(entry.model).launchIn(scope)
          }
        }
        .onFailure { cause ->
          if (cause is CancellationException) throw cause
          errorLog.e(LOG_TAG_DOWNLOAD, "resume ${entry.model.name} failed", cause)
        }
    }
  }

  private fun updateEntry(modelName: String, transform: (ModelEntry) -> ModelEntry) {
    _models.update { list ->
      list.map { if (it.model.name == modelName) transform(it) else it }
    }
  }
}
