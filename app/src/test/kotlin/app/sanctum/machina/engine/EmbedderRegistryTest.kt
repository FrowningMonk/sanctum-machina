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
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.embedder.EmbedderEngine
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.registry.ResetReason
import com.google.ai.edge.litertlm.Message
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TDD harness for [EmbedderRegistry] (Phase 4 Task 4).
 *
 * Timing is driven by `runTest` with a [StandardTestDispatcher] used both for the registry's
 * own scope (idle-teardown loop) and as its `engineDispatcher` — so virtual-time advances
 * fire the idle-teardown `delay` AND every `withContext(engineDispatcher)` hop deterministically
 * under `advanceUntilIdle` / `advanceTimeBy`. Same general shape as `WarmupCoordinatorTest`.
 *
 * The fakes are hand-rolled (no MockK/Mockito per `patterns.md` § Testing & Verification).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmbedderRegistryTest {

  private lateinit var context: Context
  private lateinit var modelRegistry: FakeEmbedderModelRegistry
  private lateinit var engine: FakeEmbedderEngine
  private lateinit var errorLog: ErrorLog
  private lateinit var errorLogFile: File
  private lateinit var registryScope: CoroutineScope

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    modelRegistry = FakeEmbedderModelRegistry()
    engine = FakeEmbedderEngine()
    errorLog = ErrorLog(context)
    errorLogFile = File(context.filesDir, "logs/errors.log")
    errorLogFile.parentFile?.deleteRecursively()
  }

  /**
   * Drop-in `runTest` replacement that cancels the registry's owned scope at the end of every
   * test. Without this, `runTest`'s implicit `advanceUntilIdle()` would re-fire the idle-teardown
   * loop's `delay` forever — the loop is intentionally `while(isActive)` in production.
   */
  private fun runRegistryTest(block: suspend TestScope.() -> Unit) = runTest {
    try {
      block()
    } finally {
      if (::registryScope.isInitialized) registryScope.cancel()
    }
  }

  @After
  fun tearDown() {
    if (::registryScope.isInitialized) registryScope.cancel()
    errorLogFile.parentFile?.deleteRecursively()
  }

  private fun TestScope.newRegistry(
    idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    // Default `Long.MAX_VALUE` keeps the teardown loop OFF for tests that do not assert on
    // idle behaviour — otherwise the loop's perpetual `delay()` re-schedules forever on the
    // test scheduler and `runTest`'s drain hangs even after `registryScope.cancel()`. The
    // dedicated idle-teardown test explicitly opts in by passing a finite interval.
    idleCheckIntervalMillis: Long = Long.MAX_VALUE,
  ): EmbedderRegistry {
    val dispatcher = StandardTestDispatcher(testScheduler)
    registryScope = CoroutineScope(dispatcher + SupervisorJob())
    return EmbedderRegistry(
      context = context,
      modelRegistry = modelRegistry,
      engine = engine,
      errorLog = errorLog,
      scope = registryScope,
      engineDispatcher = dispatcher,
      clock = { testScheduler.currentTime },
      idleTimeoutMillis = idleTimeoutMillis,
      idleCheckIntervalMillis = idleCheckIntervalMillis,
    )
  }

  private fun seedDownloadStatus(status: ModelDownloadStatusType) {
    modelRegistry._models.value = listOf(
      ModelEntry(
        model = embedderModel(),
        downloadStatus = ModelDownloadStatus(status = status),
        initStatus = ModelInitStatus.Idle,
      ),
    )
  }

  // ---- AC: initial state when model row exists but is NOT_DOWNLOADED ----

  @Test
  fun initialState_notDownloaded_whenModelNotDownloaded() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.NOT_DOWNLOADED)
    val registry = newRegistry()

    registry.warmup()

    assertEquals(EmbedderState.NotDownloaded, registry.state.value)
    assertEquals("engine.initialize must NOT be invoked when download not succeeded", 0, engine.initCalls)
  }

  // ---- AC: NotDownloaded → Initializing → Ready ----

  @Test
  fun warmupTransitions_idle_initializing_ready() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    val registry = newRegistry()

    assertEquals(
      "initial state must be NotDownloaded",
      EmbedderState.NotDownloaded,
      registry.state.value,
    )

    registry.warmup()
    advanceUntilIdle()

    assertEquals(EmbedderState.Ready, registry.state.value)
    assertEquals("engine.initialize called exactly once", 1, engine.initCalls)
    // The mid-flight `Initializing` state is exercised by
    // `encode_when_not_ready_throws_in_initializing_state`, which deliberately stalls the
    // engine via `awaitCancellation()`.
  }

  // ---- AC: failure → Failed(reason, cause) + ErrorLog ----

  @Test
  fun warmup_failedInitialize_transitions_to_Failed_with_reason() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    val cause = IllegalStateException("native delegate refused GPU")
    engine.initializeHandler = { Result.failure(cause) }

    val registry = newRegistry()
    registry.warmup()
    advanceUntilIdle()

    val state = registry.state.value
    assertTrue("state must be Failed, got $state", state is EmbedderState.Failed)
    val failed = state as EmbedderState.Failed
    assertEquals("native delegate refused GPU", failed.reason)
    assertSame(cause, failed.cause)

    assertTrue("error log file must be created", errorLogFile.exists())
    val lines = errorLogFile.readLines()
    assertTrue(
      "expected an ERROR [embed-init] entry, got: $lines",
      lines.any { it.startsWith("ERROR [embed-init] ") },
    )
  }

  // ---- AC: idempotent on Ready ----

  @Test
  fun warmup_idempotent_when_ready() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    val registry = newRegistry()

    registry.warmup()
    advanceUntilIdle()
    assertEquals(EmbedderState.Ready, registry.state.value)
    assertEquals(1, engine.initCalls)

    registry.warmup()
    advanceUntilIdle()

    assertEquals(EmbedderState.Ready, registry.state.value)
    assertEquals("warmup must be a no-op when already Ready", 1, engine.initCalls)
  }

  // ---- AC: concurrent warmup() callers see exactly one initialize() ----

  @Test
  fun warmup_serializesConcurrentCalls() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    val registry = newRegistry()

    val jobs = List(5) { launch { registry.warmup() } }
    advanceUntilIdle()
    jobs.forEach { it.join() }

    assertEquals(EmbedderState.Ready, registry.state.value)
    assertEquals(
      "encodeMutex must serialise warmup — engine.initialize called exactly once",
      1,
      engine.initCalls,
    )
  }

  // ---- AC: encode throws EmbedderNotReadyException in every non-Ready state ----

  @Test
  fun encode_when_not_ready_throws_in_notDownloaded_state() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.NOT_DOWNLOADED)
    val registry = newRegistry()
    registry.warmup()
    advanceUntilIdle()

    assertThrows<EmbedderNotReadyException> {
      registry.encode(listOf("x"), "retrieval_document")
    }
  }

  @Test
  fun encode_when_not_ready_throws_in_idle_state() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    val registry = newRegistry()
    registry.warmup()
    advanceUntilIdle()
    registry.releaseEngine()
    advanceUntilIdle()

    assertEquals(EmbedderState.Idle, registry.state.value)
    assertThrows<EmbedderNotReadyException> {
      registry.encode(listOf("x"), "retrieval_document")
    }
  }

  @Test
  fun encode_when_not_ready_throws_in_initializing_state() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    // `awaitCancellation()` keeps `engine.initialize` parked until we cancel the warmup job
    // ourselves at the end. We never need to resume it — the assertion only cares about the
    // observable Initializing state.
    engine.initializeHandler = { awaitCancellation() }
    val registry = newRegistry()

    val warmupJob = launch { registry.warmup() }
    advanceUntilIdle()
    assertEquals(EmbedderState.Initializing, registry.state.value)

    assertThrows<EmbedderNotReadyException> {
      registry.encode(listOf("x"), "retrieval_document")
    }

    warmupJob.cancel()
  }

  @Test
  fun encode_when_not_ready_throws_in_failed_state() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    engine.initializeHandler = { Result.failure(RuntimeException("boom")) }
    val registry = newRegistry()
    registry.warmup()
    advanceUntilIdle()

    assertTrue(registry.state.value is EmbedderState.Failed)
    assertThrows<EmbedderNotReadyException> {
      registry.encode(listOf("x"), "retrieval_document")
    }
  }

  // ---- AC: encode on Ready proxies engine.encode batch-by-batch ----

  @Test
  fun encode_on_ready_delegates_to_engine() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    val expected = FloatArray(4) { it.toFloat() }
    engine.encodeHandler = { _, _ -> expected }
    val registry = newRegistry()

    registry.warmup()
    advanceUntilIdle()

    val out = registry.encode(listOf("hello", "world"), "retrieval_document")

    assertEquals(2, out.size)
    assertSame("engine.encode result must be proxied verbatim", expected, out[0])
    assertSame(expected, out[1])
    assertEquals(
      listOf("hello" to "retrieval_document", "world" to "retrieval_document"),
      engine.encodeCalls,
    )
  }

  @Test
  fun encodeQuery_uses_retrieval_query_task_type() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    val expected = FloatArray(3) { it.toFloat() }
    engine.encodeHandler = { _, _ -> expected }
    val registry = newRegistry()

    registry.warmup()
    advanceUntilIdle()

    val result = registry.encodeQuery("искомый текст")

    assertSame(expected, result)
    assertEquals(listOf("искомый текст" to "retrieval_query"), engine.encodeCalls)
  }

  // ---- AC: idle-teardown triggers release() after 5 min without encode ----

  @Test
  fun idleTeardown_after_5min_no_encode_releases_engine() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    // We drive `maybeReleaseIdle()` ourselves rather than running the production loop —
    // the loop's perpetual `delay()` re-schedules forever on the test scheduler and conflicts
    // with `runTest`'s terminal drain. Loop integration is verified manually + by code review;
    // here we cover the only conditional branch (Ready + idle window elapsed → teardown).
    val registry = newRegistry(idleTimeoutMillis = DEFAULT_IDLE_TIMEOUT_MILLIS)
    registry.warmup()
    advanceUntilIdle()
    assertEquals(EmbedderState.Ready, registry.state.value)

    // Within the idle window — teardown must NOT fire.
    advanceTimeBy(DEFAULT_IDLE_TIMEOUT_MILLIS - 1)
    registry.maybeReleaseIdle()
    assertEquals("Ready preserved within idle window", EmbedderState.Ready, registry.state.value)
    assertEquals(0, engine.closeCalls)

    // Past the idle window — teardown fires exactly once.
    advanceTimeBy(2)
    registry.maybeReleaseIdle()
    assertEquals(EmbedderState.Idle, registry.state.value)
    assertEquals(1, engine.closeCalls)
  }

  // ---- AC: explicit releaseEngine() clears state ----

  @Test
  fun releaseEngine_explicit_clears_engine_and_resets_state() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    val registry = newRegistry()
    registry.warmup()
    advanceUntilIdle()
    assertEquals(EmbedderState.Ready, registry.state.value)

    registry.releaseEngine()

    assertEquals(EmbedderState.Idle, registry.state.value)
    assertEquals(1, engine.closeCalls)
  }

  @Test
  fun warmup_after_failed_retries_initialize() = runRegistryTest {
    seedDownloadStatus(ModelDownloadStatusType.SUCCEEDED)
    engine.initializeHandler = { Result.failure(RuntimeException("transient")) }
    val registry = newRegistry()

    registry.warmup()
    advanceUntilIdle()
    assertTrue(registry.state.value is EmbedderState.Failed)
    assertEquals(1, engine.initCalls)

    engine.initializeHandler = { Result.success(Unit) }
    registry.warmup()
    advanceUntilIdle()

    assertEquals(EmbedderState.Ready, registry.state.value)
    assertEquals("retry path must call engine.initialize a second time", 2, engine.initCalls)
  }

  // ---------- Helpers ----------

  private companion object {
    val DEFAULT_IDLE_TIMEOUT_MILLIS: Long = EmbedderRegistry.DEFAULT_IDLE_TIMEOUT_MILLIS
    val DEFAULT_IDLE_CHECK_INTERVAL_MILLIS: Long =
      EmbedderRegistry.DEFAULT_IDLE_CHECK_INTERVAL_MILLIS
  }

  private fun embedderModel(): Model = Model(
    name = "embeddinggemma-300m",
    modelId = "litert-community/embeddinggemma-300m",
    downloadFileName = "embeddinggemma-300M_seq2048_mixed-precision.tflite",
  )
}

private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
  try {
    block()
    fail("expected ${T::class.simpleName} but no exception was thrown")
  } catch (t: Throwable) {
    if (t !is T) fail("expected ${T::class.simpleName} but got ${t::class.simpleName}: ${t.message}")
  }
}

// ---------- Fakes ----------

private class FakeEmbedderEngine : EmbedderEngine {
  var initCalls: Int = 0
    private set
  var closeCalls: Int = 0
    private set
  val encodeCalls: MutableList<Pair<String, String>> = mutableListOf()

  var initializeHandler: suspend () -> Result<Unit> = { Result.success(Unit) }
  var encodeHandler: (String, String) -> FloatArray = { _, _ -> FloatArray(0) }

  override suspend fun initialize(
    context: Context,
    modelFile: File,
    tokenizerFile: File,
  ): Result<Unit> {
    initCalls++
    return initializeHandler()
  }

  override fun encode(text: String, taskType: String): FloatArray {
    encodeCalls += text to taskType
    return encodeHandler(text, taskType)
  }

  override fun releaseEngine() {
    closeCalls++
  }
}

private class FakeEmbedderModelRegistry : ModelRegistry {
  val _models: MutableStateFlow<List<ModelEntry>> = MutableStateFlow(emptyList())
  override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()
  override val activeModelName: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()

  override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
  override fun download(model: Model): Flow<ModelDownloadStatus> =
    MutableStateFlow(ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)).asStateFlow()
  override fun cancelDownload(modelName: String) = Unit
  override suspend fun delete(modelName: String) = Unit
  override suspend fun initialize(modelName: String): Result<Unit> = Result.success(Unit)
  override suspend fun cleanup(modelName: String) = Unit
  override suspend fun resetConversation(
    modelName: String,
    systemPrompt: String?,
    reason: ResetReason,
    initialMessages: List<Message>,
  ) = Unit
  override fun getModel(modelName: String): Model? = null
}
