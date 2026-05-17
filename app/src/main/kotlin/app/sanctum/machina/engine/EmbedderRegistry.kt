/*
 * Copyright 2026 Sanctum Machina authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package app.sanctum.machina.engine

import android.content.Context
import android.content.res.AssetManager
import androidx.annotation.VisibleForTesting
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.embedder.Embedder
import app.sanctum.machina.core.embedder.EmbedderEngine
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val LOG_INIT = "embed-init"
private const val TOKENIZER_FILE_NAME = "sentencepiece.model"
private const val TASK_TYPE_QUERY = "retrieval_query"

/**
 * Phase 4 Task 17: directory under `cacheDir/` where bundled embedder assets are extracted on
 * first warmup. Symmetric with the assets/-side layout (`assets/embedding/...`) so a future
 * reviewer can grep both paths under one substring.
 *
 * `cacheDir` (vs `filesDir`) is deliberate: Android may evict the directory under memory
 * pressure. The size-gated short-circuit in [EmbedderRegistry.extractAssetIfStale] re-extracts
 * automatically on the next warmup if the file is gone — at the cost of one ~1-3 s copy of
 * 196 MB on the affected start. `filesDir` would survive eviction but inflates the app-data
 * footprint by the full asset size for every install, regardless of whether the user opens
 * Projects.
 */
private const val BUNDLED_ASSET_SUBDIR = "embedding"
private const val EXTRACT_BUFFER_BYTES = 1 shl 16 // 64 KiB — large enough that 196 MB extracts in ~3 ms/MB.
/**
 * Defence-in-depth for [EmbedderRegistry.extractAssetIfStale] (round-1 security review).
 * Same shape as [AllowlistLoader.MODEL_FILE_REGEX] but enforced locally so a future caller
 * that bypasses the loader cannot pass a `..`-bearing or path-separator-bearing filename.
 */
private val SAFE_BUNDLED_FILE_NAME = Regex("^[A-Za-z0-9._-]+$")

/**
 * Phase 4 Task 4 (Decision 2): `@Singleton` owner of [EmbedderEngine] lifecycle, sibling of
 * [WarmupCoordinator]. Independent from `DefaultModelRegistry`'s single-engine invariant — the
 * embedding engine and the chat engine co-reside (different runtimes; see Decision 3).
 *
 * State machine: `NotDownloaded → Idle → Initializing → Ready → Failed`. Every transition AND
 * every encode call goes through [encodeMutex] per Decision 2 — LiteRT Interpreter is not safe
 * across concurrent native calls into the same instance. The engine runs on a dedicated
 * single-threaded [CoroutineDispatcher] so IngestWorker (WorkManager) and RagInjector
 * (ChatViewModel) are physically serialised at the dispatcher level even before the mutex
 * comes into play.
 *
 * Idle teardown: a coroutine launched in [init] wakes every [idleCheckIntervalMillis] and
 * releases the engine if [Ready] **and** the last encode was more than [idleTimeoutMillis] ago.
 * The teardown takes [encodeMutex], so an `encode*` arriving mid-cleanup waits transparently
 * and may trigger re-warmup on the next call (Decision 2 trigger-points).
 *
 * **Hook points** (wired downstream by their owning task):
 *  - ProjectDetailViewModel (Task 9): `init { viewModelScope.launch { embedderRegistry.warmup() } }`.
 *  - ChatViewModel (Task 11): `init { if (chat.project_id != null) viewModelScope.launch { embedderRegistry.warmup() } }`.
 *
 * The registry exposes only the *suspend warmup()* — call-sites decide their own dispatch
 * scope; the registry's own scope is reserved for the idle-teardown loop.
 */
@Singleton
open class EmbedderRegistry @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE) internal constructor(
  private val context: Context,
  private val modelRegistry: ModelRegistry,
  private val engine: EmbedderEngine,
  private val errorLog: ErrorLog,
  private val scope: CoroutineScope,
  private val engineDispatcher: CoroutineDispatcher,
  private val clock: () -> Long,
  private val idleTimeoutMillis: Long,
  /**
   * Period between idle-teardown checks. Pass [Long.MAX_VALUE] to suppress the loop entirely
   * (used by tests that do not assert on idle behaviour — a perpetually-rescheduling `delay()`
   * conflicts with `runTest`'s terminal drain).
   */
  private val idleCheckIntervalMillis: Long,
) : Embedder, EmbedderGate {

  @Inject
  constructor(
    @ApplicationContext context: Context,
    modelRegistry: ModelRegistry,
    engine: EmbedderEngine,
    errorLog: ErrorLog,
  ) : this(
    context = context,
    modelRegistry = modelRegistry,
    engine = engine,
    errorLog = errorLog,
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    // Single-threaded executor matches Decision 2's thread-safety contract — even if a future
    // change drops `encodeMutex`, the dispatcher alone serialises every native call.
    engineDispatcher = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "EmbedderRegistry-engine").apply { isDaemon = true }
    }.asCoroutineDispatcher(),
    clock = { System.currentTimeMillis() },
    idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MILLIS,
    idleCheckIntervalMillis = DEFAULT_IDLE_CHECK_INTERVAL_MILLIS,
  )

  private val _state: MutableStateFlow<EmbedderState> = MutableStateFlow(EmbedderState.NotDownloaded)

  /** Hot stream of the current registry state — observed by UI gates (FAB enabled, snackbar). */
  override val state: StateFlow<EmbedderState> = _state.asStateFlow()

  /**
   * Serialises every transition: `warmup` / `encode` / `encodeQuery` / `releaseEngine` /
   * idle-teardown. See Decision 2 thread-safety rationale.
   */
  private val encodeMutex = Mutex()

  @Volatile private var lastEncodeAt: Long = clock()

  init {
    if (idleCheckIntervalMillis < Long.MAX_VALUE) {
      startIdleTeardownLoop()
    }
  }

  /**
   * Idempotent warmup. Resolves the embedder model entry from [ModelRegistry], gates on
   * download completion, then initialises [EmbedderEngine]. Concurrent callers are serialised
   * through [encodeMutex] — exactly one [EmbedderEngine.initialize] runs even with N parallel
   * `launch { warmup() }` calls.
   */
  override suspend fun warmup() {
    encodeMutex.withLock { warmupLocked() }
  }

  private suspend fun warmupLocked() {
    if (_state.value is EmbedderState.Ready) return

    val entry = modelRegistry.models.value.firstOrNull { it.model.modelId == MODEL_ID_EMBEDDER }
    if (entry == null || entry.downloadStatus.status != ModelDownloadStatusType.SUCCEEDED) {
      _state.value = EmbedderState.NotDownloaded
      return
    }

    _state.value = EmbedderState.Initializing

    val initResult = try {
      withContext(engineDispatcher) {
        // Decision 2: engineDispatcher is single-threaded and decoupled from the call-site's
        // coroutine context. Running `resolveEngineFiles` inside the same hop ensures the
        // (potentially blocking) bundled-asset extraction never lands on Main, AND keeps the
        // hop count identical for both branches — relevant for `runTest`'s virtual-time
        // scheduler, which drives a substituted `engineDispatcher` but does not advance
        // `Dispatchers.IO`.
        val (modelFile, tokenizerFile) = resolveEngineFiles(entry.model)
        engine.initialize(context, modelFile, tokenizerFile)
      }
    } catch (ce: CancellationException) {
      // Singleton-lifetime fail-loud: do NOT leave `_state` stuck in Initializing if the
      // caller's scope (typically a ViewModelScope on ProjectDetailViewModel / ChatViewModel)
      // is cancelled mid-warmup. The next warmup() retries from a clean slate; encode()
      // arriving in the meantime sees NotDownloaded and throws EmbedderNotReadyException with
      // a self-explanatory state instead of "still initializing".
      _state.value = EmbedderState.NotDownloaded
      // NonCancellable shields the log write from the inflight cancellation — without it the
      // suspending `errorLog.i` (Mutex.withLock + withContext(Dispatchers.IO)) re-throws CE
      // before reaching the file, leaving the audit trail empty for the very case it exists
      // to record.
      withContext(NonCancellable) {
        runCatching { errorLog.i(LOG_INIT, "warmup cancelled") }
      }
      throw ce
    } catch (t: Throwable) {
      Result.failure(t)
    }

    initResult
      .onSuccess { _state.value = EmbedderState.Ready }
      .onFailure { cause ->
        // Include the leaf cause class so UI snackbars (Task 9 / Task 11) can surface
        // GPU-driver-broke vs OOM nuance — the engine wraps native errors in IllegalStateException,
        // so without this the user-facing reason is always the generic outer message.
        val leafClass = cause::class.simpleName.orEmpty().ifEmpty { "Throwable" }
        val outer = cause.message?.takeIf { it.isNotBlank() } ?: "init failed"
        val reason = "$leafClass: $outer"
        _state.value = EmbedderState.Failed(reason, cause)
        runCatching { errorLog.e(LOG_INIT, "warmup failed", cause) }
      }
  }

  /**
   * Batch-encode [texts] under [taskType] (`"retrieval_document"` for ingest,
   * `"retrieval_query"` for queries). The current engine API is single-text; the registry
   * walks the batch under [encodeMutex] so each native call sees the same serialised view.
   * A future batched engine API can be wired without touching call-sites.
   *
   * @throws EmbedderNotReadyException when state is not [EmbedderState.Ready] at call time.
   */
  override suspend fun encode(texts: List<String>, taskType: String): List<FloatArray> {
    val current = _state.value
    if (current !is EmbedderState.Ready) {
      throw EmbedderNotReadyException(current)
    }
    return encodeMutex.withLock {
      // Re-check inside the lock — idle-teardown could have flipped us to Idle between the
      // fast-path read and the mutex acquisition.
      val locked = _state.value
      if (locked !is EmbedderState.Ready) throw EmbedderNotReadyException(locked)
      val out = withContext(engineDispatcher) {
        texts.map { engine.encode(it, taskType) }
      }
      lastEncodeAt = clock()
      out
    }
  }

  /** Convenience wrapper used by RagInjector (Task 6) and IngestWorker (Task 7). */
  override suspend fun encodeQuery(text: String): FloatArray =
    encode(listOf(text), TASK_TYPE_QUERY).first()

  /**
   * Explicit teardown — used by Application#onTerminate fallbacks or future "free memory"
   * controls. Idle-teardown reuses [releaseEngineLocked] under the same mutex.
   */
  open suspend fun releaseEngine() {
    encodeMutex.withLock { releaseEngineLocked() }
  }

  private suspend fun releaseEngineLocked() {
    if (_state.value !is EmbedderState.Ready) return
    withContext(engineDispatcher) {
      try {
        engine.releaseEngine()
      } catch (ce: CancellationException) {
        // Preserve structured cancellation — propagate so the caller's scope cancellation
        // is not silently swallowed by a generic Throwable catch.
        throw ce
      } catch (t: Throwable) {
        // Native release is idempotent + synchronized inside `EmbeddingGemmaEngine`; a throw
        // here means the implementation broke its contract. Log and continue — flipping to
        // Idle is still correct because the registry's view of the engine is "gone".
        runCatching { errorLog.w(LOG_INIT, "release threw", t) }
      }
    }
    _state.value = EmbedderState.Idle
  }

  private fun startIdleTeardownLoop() {
    scope.launch {
      while (isActive) {
        delay(idleCheckIntervalMillis)
        maybeReleaseIdle()
      }
    }
  }

  /**
   * One iteration of the idle-teardown check. The production loop in [startIdleTeardownLoop]
   * calls this on every wake; tests drive it directly to avoid wiring an infinite `delay()`
   * loop into the test scheduler (the loop's perpetual re-scheduling fights `runTest`'s
   * terminal drain).
   */
  @VisibleForTesting
  internal suspend fun maybeReleaseIdle() {
    encodeMutex.withLock {
      if (_state.value is EmbedderState.Ready &&
        clock() - lastEncodeAt >= idleTimeoutMillis
      ) {
        releaseEngineLocked()
      }
    }
  }

  /**
   * Returns the on-disk [File] handles the [EmbedderEngine] consumes. Two branches per
   * [Model.bundled]:
   *  - **bundled = true (Task 17, current EmbeddingGemma row)** — assets ship inside the APK
   *    at `assets/$BUNDLED_ASSET_SUBDIR/`. Extract them to `cacheDir/$BUNDLED_ASSET_SUBDIR/`
   *    on first warmup; subsequent warmups are zero-copy (size-gated short-circuit).
   *  - **bundled = false (downloadable rows, future use)** — files live under
   *    `Model.getPath()` (externalFilesDir / imported overrides), same code path the engine
   *    used pre-Task-17.
   */
  internal fun resolveEngineFiles(model: Model): Pair<File, File> {
    return if (model.bundled) {
      val cacheDir = File(context.cacheDir, BUNDLED_ASSET_SUBDIR).apply { mkdirs() }
      val modelOut = File(cacheDir, model.downloadFileName)
      val tokOut = File(cacheDir, TOKENIZER_FILE_NAME)
      extractAssetIfStale(context.assets, "$BUNDLED_ASSET_SUBDIR/${model.downloadFileName}", modelOut)
      extractAssetIfStale(context.assets, "$BUNDLED_ASSET_SUBDIR/$TOKENIZER_FILE_NAME", tokOut)
      modelOut to tokOut
    } else {
      val modelFile = File(model.getPath(context, model.downloadFileName))
      val explicit = model.getExtraDataFile(TOKENIZER_FILE_NAME)
      val tokFileName = explicit?.downloadFileName ?: TOKENIZER_FILE_NAME
      val tokFile = File(model.getPath(context, tokFileName))
      modelFile to tokFile
    }
  }

  /**
   * Copy [assetPath] from the APK to [dst] iff [dst] is missing or its byte length differs
   * from the asset's declared length. Build config pins these assets to `noCompress` (see
   * `app/build.gradle.kts`), which is the precondition for `openFd().length` to be a valid
   * size oracle — on compressed assets `openFd` throws `FileNotFoundException`.
   *
   * **Path traversal is bounded twice:**
   *  - At construction by [AllowlistLoader]'s `MODEL_FILE_REGEX` gate on `Model.downloadFileName`.
   *  - Defence-in-depth here via [SAFE_BUNDLED_FILE_NAME] applied to [dst]'s basename — a future
   *    caller that bypasses the loader still cannot reach `..`-bearing names.
   *
   * **Atomicity:** writes go to `dst.tmp` and only `renameTo(dst)` on success, so a truncate-
   * then-die race cannot leave a partial file at `dst`. A leftover `.tmp` is harmless — the
   * size gate ignores it and the next call overwrites it.
   */
  private fun extractAssetIfStale(assets: AssetManager, assetPath: String, dst: File) {
    require(SAFE_BUNDLED_FILE_NAME.matches(dst.name)) {
      "bundled-asset target filename '${dst.name}' contains unsafe characters"
    }
    val expectedSize = try {
      assets.openFd(assetPath).use { it.length }
    } catch (e: FileNotFoundException) {
      // `openFd` throws on compressed assets too — re-raise with a clearer hint for the
      // bench-day-2 reviewer than the framework's stock message.
      throw IllegalStateException(
        "bundled asset '$assetPath' is missing or compressed in APK — check `noCompress` in " +
          "app/build.gradle.kts and the assets/ layout",
        e,
      )
    }
    if (dst.exists() && dst.length() == expectedSize) return
    val tmp = File(dst.parentFile, "${dst.name}.tmp")
    assets.open(assetPath).use { input ->
      tmp.outputStream().use { output ->
        input.copyTo(output, bufferSize = EXTRACT_BUFFER_BYTES)
      }
    }
    // Atomic publish: a process-kill between the copy and the rename leaves `.tmp` but not `dst`,
    // so the next warmup's size check (dst missing) re-extracts cleanly.
    if (!tmp.renameTo(dst)) {
      // Fallback for unlikely cross-filesystem cases; cacheDir lives on the app's internal
      // storage so this branch should be unreachable on the production runtime.
      tmp.copyTo(dst, overwrite = true)
      tmp.delete()
    }
  }

  companion object {
    /** Decision 2: 5-min idle window before automatic teardown in MVP. */
    @JvmField val DEFAULT_IDLE_TIMEOUT_MILLIS: Long = 5.minutes.inWholeMilliseconds
    /**
     * One-minute check interval — worst-case teardown latency is
     * `DEFAULT_IDLE_TIMEOUT_MILLIS + DEFAULT_IDLE_CHECK_INTERVAL_MILLIS` ≈ 6 min.
     */
    @JvmField val DEFAULT_IDLE_CHECK_INTERVAL_MILLIS: Long = 1.minutes.inWholeMilliseconds

    /**
     * HF `modelId` for the embedder allowlist row consumed by warmup. Exposed here so future
     * consumers (IngestWorker, RagInjector, UI gates) reference one source of truth — see
     * Decision 12 (model card row) + Task 1 deviation in decisions.md.
     */
    const val MODEL_ID_EMBEDDER: String = "litert-community/embeddinggemma-300m"
  }
}

/**
 * State of the embedder runtime. `Ready` is the only state where [EmbedderRegistry.encode] is
 * safe; every other state throws [EmbedderNotReadyException] — UI gates read [EmbedderState]
 * and either disable the action or surface a "download the embedder" CTA.
 */
sealed class EmbedderState {
  object NotDownloaded : EmbedderState()
  object Idle : EmbedderState()
  object Initializing : EmbedderState()
  object Ready : EmbedderState()
  data class Failed(val reason: String, val cause: Throwable?) : EmbedderState()
}

/**
 * Thrown by [EmbedderRegistry.encode] / [EmbedderRegistry.encodeQuery] when state is not
 * [EmbedderState.Ready]. Fail-loud per Task 6 / Task 11: callers (RagInjector, IngestWorker)
 * convert this into a typed user-facing error rather than silently degrading retrieval.
 */
class EmbedderNotReadyException(val currentState: EmbedderState) :
  IllegalStateException("EmbedderRegistry not ready: $currentState")
