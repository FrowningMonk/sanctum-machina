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
import androidx.annotation.VisibleForTesting
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.embedder.EmbedderEngine
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
private const val MODEL_ID_EMBEDDER = "litert-community/embeddinggemma-300m"
private const val TOKENIZER_FILE_NAME = "sentencepiece.model"
private const val TASK_TYPE_QUERY = "retrieval_query"

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
 *  - [ProjectDetailViewModel] Task 9: `init { viewModelScope.launch { embedderRegistry.warmup() } }`.
 *  - [ChatViewModel] Task 11: `init { if (chat.project_id != null) viewModelScope.launch { embedderRegistry.warmup() } }`.
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
  private val idleCheckIntervalMillis: Long,
) {

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
  open val state: StateFlow<EmbedderState> = _state.asStateFlow()

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
  open suspend fun warmup() {
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
    val modelFile = File(entry.model.getPath(context, entry.model.downloadFileName))
    val tokenizerFile = resolveTokenizerFile(entry.model)

    val initResult = try {
      withContext(engineDispatcher) {
        engine.initialize(context, modelFile, tokenizerFile)
      }
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      Result.failure(t)
    }

    initResult
      .onSuccess { _state.value = EmbedderState.Ready }
      .onFailure { cause ->
        val reason = cause.message?.takeIf { it.isNotBlank() } ?: "init failed"
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
  open suspend fun encode(texts: List<String>, taskType: String): List<FloatArray> {
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

  /** Convenience wrapper used by RagInjector (Task 6). */
  open suspend fun encodeQuery(text: String): FloatArray =
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
      runCatching { engine.releaseEngine() }
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

  private fun resolveTokenizerFile(model: Model): File {
    // Prefer the explicit extraDataFiles entry once Task 1 follow-up plumbs it through the
    // allowlist; today the entry is absent and the engine will fail with a clean
    // `tokenizer file missing` IllegalArgumentException → state = Failed (surfaced via
    // `embed-init` ErrorLog), which is the intended fail-loud behaviour for downstream tests.
    val explicit = model.getExtraDataFile(TOKENIZER_FILE_NAME)
    val fileName = explicit?.downloadFileName ?: TOKENIZER_FILE_NAME
    return File(model.getPath(context, fileName))
  }

  companion object {
    /** Decision 2: 5-min idle window before automatic teardown in MVP. */
    @JvmField val DEFAULT_IDLE_TIMEOUT_MILLIS: Long = 5.minutes.inWholeMilliseconds
    /** Quarter-window check interval — keeps the worst-case teardown delay bounded at ~6.25 min. */
    @JvmField val DEFAULT_IDLE_CHECK_INTERVAL_MILLIS: Long = 1.minutes.inWholeMilliseconds
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
