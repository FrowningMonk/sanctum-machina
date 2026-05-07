package app.sanctum.machina.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.registry.ResetReason
import app.sanctum.machina.core.settings.AppSettingsRepository
import app.sanctum.machina.core.settings.proto.PerModelSettings
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TDD harness for [WarmupCoordinator] (Phase-3 Task 5).
 *
 * Timing is driven by `runTest` + a [StandardTestDispatcher]-backed coordinator scope (the
 * task's Implementation Hint): the scheduler is advanced explicitly via `advanceUntilIdle`,
 * so ordering bugs that only surface under real-dispatcher scheduling (e.g. intermediate
 * StateFlow emissions during cancel-and-restart) are observable. `ErrorLog` is real and
 * writes under Robolectric's `filesDir/logs/`;
 * the file is consulted as the observable capture surface for "engine-warmup" entries
 * (no mocking library per `patterns.md`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WarmupCoordinatorTest {

  private lateinit var context: Context
  private lateinit var registry: FakeModelRegistry
  private lateinit var appSettings: FakeAppSettingsRepository
  private lateinit var errorLog: ErrorLog
  private lateinit var errorLogFile: File
  private lateinit var coordinatorScope: CoroutineScope

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    registry = FakeModelRegistry()
    appSettings = FakeAppSettingsRepository()
    errorLog = ErrorLog(context)
    errorLogFile = File(context.filesDir, "logs/errors.log")
    errorLogFile.parentFile?.deleteRecursively()
  }

  @After
  fun tearDown() {
    if (::coordinatorScope.isInitialized) coordinatorScope.cancel()
    errorLogFile.parentFile?.deleteRecursively()
  }

  private fun TestScope.newCoordinator(): WarmupCoordinator {
    coordinatorScope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
    return WarmupCoordinator(registry, appSettings, errorLog, coordinatorScope)
  }

  // ---- warmupDefault: model resolution (AC-F5) ----

  @Test
  fun warmupDefault_usesDefaultModelId_whenSet() = runTest {
    appSettings.defaultModelId = "model-a"
    appSettings.lastUsedModelId = "model-b"
    seedAllowlist("model-a", "model-b")

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()

    assertEquals(listOf("model-a"), registry.initializeCalls)
  }

  @Test
  fun warmupDefault_fallsBackToLastUsed_whenDefaultEmpty() = runTest {
    appSettings.defaultModelId = ""
    appSettings.lastUsedModelId = "model-b"
    seedAllowlist("model-b")

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()

    assertEquals(listOf("model-b"), registry.initializeCalls)
  }

  @Test
  fun warmupDefault_doesNothing_whenBothEmpty() = runTest {
    appSettings.defaultModelId = ""
    appSettings.lastUsedModelId = ""

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()

    assertTrue(registry.initializeCalls.isEmpty())
    assertEquals("", appSettings.lastUsedModelId)
    assertFalse(
      "no-op warmup must leave isWarmupInProgress at false",
      coordinator.isWarmupInProgress.value,
    )
  }

  // ---- warmupDefault: last_used_model_id update on success (AC-F6) ----

  @Test
  fun warmupDefault_updatesLastUsedModelId_onSuccess() = runTest {
    appSettings.defaultModelId = "model-a"
    seedAllowlist("model-a")
    registry.initializeHandler = { Result.success(Unit) }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()

    assertEquals("model-a", appSettings.lastUsedModelId)
  }

  // ---- warmupDefault: failure logs engine-warmup (AC-D4) ----

  @Test
  fun warmupDefault_logsEngineWarmupError_onFailure() = runTest {
    appSettings.defaultModelId = "model-a"
    seedAllowlist("model-a")
    registry.initializeHandler = { Result.failure(RuntimeException("GPU+CPU init failed")) }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()
    // `ErrorLog.e` hops onto the real `Dispatchers.IO`, which is outside the test scheduler's
    // virtual time. Poll briefly on real time for the log file to materialise (TAC-style
    // bounded wait — never sleeps when the file is already there).
    awaitLogFile()

    val logged = errorLogFile.readText()
    assertTrue(
      "expected engine-warmup entry in log; got: $logged",
      logged.contains("ERROR [engine-warmup]"),
    )
    // last_used_model_id must NOT be updated on failure.
    assertEquals("", appSettings.lastUsedModelId)
  }

  private fun awaitLogFile() {
    // Poll on `length > 0` and the expected component string — not just existence — so a slow
    // CI runner cannot read a half-flushed file and produce a misleading assertion failure.
    val deadlineMs = System.currentTimeMillis() + 2_000
    while (System.currentTimeMillis() < deadlineMs) {
      if (errorLogFile.exists() &&
        errorLogFile.length() > 0 &&
        errorLogFile.readText().contains("ERROR [engine-warmup]")
      ) {
        return
      }
      Thread.sleep(20)
    }
  }

  // ---- cancelAndRestart: ordering guarantee ----

  @Test
  fun cancelAndRestart_cancelsInFlightJobBeforeNewInitialize() = runTest {
    appSettings.defaultModelId = "model-a"
    seedAllowlist("model-a", "model-b")
    val firstStarted = CompletableDeferred<Unit>()
    val firstCancelled = CompletableDeferred<Unit>()
    val secondStarted = CompletableDeferred<Unit>()

    registry.initializeHandler = { modelName ->
      when (modelName) {
        "model-a" -> {
          firstStarted.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            firstCancelled.complete(Unit)
          }
        }
        "model-b" -> {
          // The ordering assertion: the first Job must have fully cancelled
          // (its finally block ran) before the new initialize() is entered.
          if (!firstCancelled.isCompleted) {
            fail("second initialize started before first was cancelled")
          }
          secondStarted.complete(Unit)
          Result.success(Unit)
        }
        else -> Result.success(Unit)
      }
    }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()

    assertTrue("first warmup must reach initialize()", firstStarted.isCompleted)
    assertFalse("first warmup must still be in flight", firstCancelled.isCompleted)

    coordinator.cancelAndRestart("model-b")
    advanceUntilIdle()

    assertTrue("first must be cancelled", firstCancelled.isCompleted)
    assertTrue("second must have started", secondStarted.isCompleted)
    assertEquals(listOf("model-a", "model-b"), registry.initializeCalls)
  }

  // ---- AC-F3 observer ----

  @Test
  fun ac_f3Observer_setsDefaultModelId_onFirstDownloadedModel_whenEmpty() = runTest {
    appSettings.defaultModelId = ""
    val coordinator = newCoordinator()
    advanceUntilIdle() // let observer attach to the empty initial list

    // Emit a model entry with SUCCEEDED download status.
    val model = fakeModel(name = "gemma-local", modelId = "litert-community/gemma-4-E4B-it-litert-lm")
    registry._models.value = listOf(
      ModelEntry(
        model = model,
        downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
        initStatus = ModelInitStatus.Idle,
      ),
    )
    advanceUntilIdle()

    assertEquals(
      listOf("litert-community/gemma-4-E4B-it-litert-lm"),
      appSettings.setDefaultModelIdCalls,
    )

    // One-shot: a second SUCCEEDED emission must NOT call setDefaultModelId again. Verify
    // both that (a) no additional write happens AND (b) the collector itself terminated via
    // `ownJob.cancel()` — otherwise a future refactor could drop the self-cancel and rely on
    // the `isEmpty()` guard alone without this test noticing.
    val other = fakeModel(name = "other-local", modelId = "org/other-model")
    registry._models.value = registry._models.value + ModelEntry(
      model = other,
      downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
      initStatus = ModelInitStatus.Idle,
    )
    advanceUntilIdle()

    assertEquals(1, appSettings.setDefaultModelIdCalls.size)
    assertEquals(
      "observer must have cancelled its collector after the one-shot write",
      0,
      registry._models.subscriptionCount.value,
    )
  }

  @Test
  fun ac_f3Observer_triggersWarmup_afterAutoSettingDefault() = runTest {
    // Regression: first-launch scenario where no default is set at cold start, so
    // warmupDefault() skipped. Downloading the first model auto-writes the default,
    // and without this trigger Home's "Начать быстрый чат" suspends forever on
    // registry.activeModelName.first().
    appSettings.defaultModelId = ""
    appSettings.lastUsedModelId = ""
    val coordinator = newCoordinator()
    coordinator.warmupDefault() // first pass: no default, no-op
    advanceUntilIdle()
    assertTrue(registry.initializeCalls.isEmpty())

    val model = fakeModel(name = "gemma-local", modelId = "org/first-downloaded")
    registry._models.value = listOf(
      ModelEntry(
        model = model,
        downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
        initStatus = ModelInitStatus.Idle,
      ),
    )
    advanceUntilIdle()

    assertEquals(listOf("org/first-downloaded"), appSettings.setDefaultModelIdCalls)
    // Coordinator translates modelId → Model.name before calling registry.initialize.
    assertEquals(listOf("gemma-local"), registry.initializeCalls)
  }

  @Test
  fun ac_f3Observer_doesNotSetDefault_onEmptyModelList() = runTest {
    // Edge case from tasks/5.md: empty list on startup MUST NOT trigger setDefaultModelId.
    appSettings.defaultModelId = ""
    newCoordinator()
    advanceUntilIdle()

    assertTrue(appSettings.setDefaultModelIdCalls.isEmpty())
  }

  @Test
  fun ac_f3Observer_picksFirstEntry_whenMultipleDownloadedInSameEmission() = runTest {
    // Edge case: "if multiple models have SUCCEEDED in the same emission, pick the first one
    // (iteration order of the list). Do not call setDefaultModelId more than once."
    appSettings.defaultModelId = ""
    val first = fakeModel(name = "first-local", modelId = "org/first")
    val second = fakeModel(name = "second-local", modelId = "org/second")
    registry._models.value = listOf(
      ModelEntry(
        first,
        ModelDownloadStatus(ModelDownloadStatusType.SUCCEEDED),
        ModelInitStatus.Idle,
      ),
      ModelEntry(
        second,
        ModelDownloadStatus(ModelDownloadStatusType.SUCCEEDED),
        ModelInitStatus.Idle,
      ),
    )

    newCoordinator()
    advanceUntilIdle()

    assertEquals(listOf("org/first"), appSettings.setDefaultModelIdCalls)
  }

  @Test
  fun warmupDefault_calledTwice_cancelsFirstBeforeSecondStarts() = runTest {
    // Edge case from tasks/5.md: "warmupDefault() called multiple times before first warmup
    // completes: each call should cancel the previous Job and start a new one." The default
    // is resolved each call — we change it between invocations to distinguish the two Jobs.
    appSettings.defaultModelId = "model-a"
    seedAllowlist("model-a", "model-b")
    val firstStarted = CompletableDeferred<Unit>()
    val firstCancelled = CompletableDeferred<Unit>()
    val secondStarted = CompletableDeferred<Unit>()

    registry.initializeHandler = { modelName ->
      when (modelName) {
        "model-a" -> {
          firstStarted.complete(Unit)
          try {
            awaitCancellation()
          } finally {
            firstCancelled.complete(Unit)
          }
        }
        "model-b" -> {
          if (!firstCancelled.isCompleted) {
            fail("second warmupDefault started before first was cancelled")
          }
          secondStarted.complete(Unit)
          Result.success(Unit)
        }
        else -> Result.success(Unit)
      }
    }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()
    assertTrue(firstStarted.isCompleted)

    appSettings.defaultModelId = "model-b"
    coordinator.warmupDefault()
    advanceUntilIdle()

    assertTrue(firstCancelled.isCompleted)
    assertTrue(secondStarted.isCompleted)
    assertEquals(listOf("model-a", "model-b"), registry.initializeCalls)
  }

  // ---- isWarmupInProgress StateFlow ----

  @Test
  fun isWarmupInProgress_emitsTrue_duringWarmup() = runTest {
    appSettings.defaultModelId = "model-a"
    seedAllowlist("model-a")
    registry.initializeHandler = { awaitCancellation() }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()

    assertTrue(coordinator.isWarmupInProgress.value)
  }

  @Test
  fun isWarmupInProgress_emitsFalse_afterCompletion() = runTest {
    appSettings.defaultModelId = "model-a"
    seedAllowlist("model-a")
    // Gate inside the handler so the test can OBSERVE the true emission before completion —
    // otherwise `assertFalse(value)` would hold even for an implementation that never flipped
    // the flag to true (the initial StateFlow value is false).
    val release = CompletableDeferred<Unit>()
    registry.initializeHandler = { release.await(); Result.success(Unit) }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()
    assertTrue(
      "flag must be true while warmup is in flight",
      coordinator.isWarmupInProgress.value,
    )

    release.complete(Unit)
    advanceUntilIdle()

    assertFalse(coordinator.isWarmupInProgress.value)
  }

  @Test
  fun isWarmupInProgress_staysTrue_acrossCancelAndRestart_andClearsOnCompletion() = runTest {
    // Guarantees two things the coordinator contract promises: (1) no transient `false` gap
    // during cancel-and-restart handover (Task-10 spinner must not flicker); (2) cancellation
    // of the first Job alone does not reset the flag — the new Job's completion does. Both
    // would regress if the `finally` block unconditionally wrote false.
    appSettings.defaultModelId = "model-a"
    seedAllowlist("model-a", "model-b")
    val secondRelease = CompletableDeferred<Unit>()
    registry.initializeHandler = { modelName ->
      when (modelName) {
        "model-a" -> awaitCancellation()
        "model-b" -> {
          secondRelease.await()
          Result.success(Unit)
        }
        else -> Result.success(Unit)
      }
    }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()
    assertTrue(
      "flag must be true while first warmup is in flight",
      coordinator.isWarmupInProgress.value,
    )

    coordinator.cancelAndRestart("model-b")
    advanceUntilIdle()
    assertTrue(
      "flag must REMAIN true across cancel-and-restart — no `false` gap observable",
      coordinator.isWarmupInProgress.value,
    )

    secondRelease.complete(Unit)
    advanceUntilIdle()
    assertFalse(
      "flag must clear after the new warmup completes",
      coordinator.isWarmupInProgress.value,
    )
  }

  // ---- Task 18 B2 — cross-model reinit timeout / failure contract ------

  @Test
  fun crossModelSwitch_whenPriorReady_eventuallyReachesReady_forNewTarget() = runTest {
    // A→Ready, then cross-model switch to B. The coordinator must fully release A's
    // warmup lifecycle and drive B through initialize() to a successful completion —
    // no deadlock / stale Initializing that would leave the UI on a forever spinner.
    //
    // The handler doubles as a fake `initialize` that publishes Ready for the target
    // and Idle for the prior Ready — otherwise `registry.models` stays frozen and
    // scenario-3 (stale Initializing on A) would go undetected.
    appSettings.defaultModelId = "model-a"
    seedAllowlist("model-a", "model-b")
    registry.initializeHandler = { modelName ->
      registry._models.value = registry._models.value.map { entry ->
        when (entry.model.name) {
          modelName -> entry.copy(initStatus = ModelInitStatus.Ready)
          else -> entry.copy(initStatus = ModelInitStatus.Idle)
        }
      }
      Result.success(Unit)
    }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()
    assertEquals(listOf("model-a"), registry.initializeCalls)
    assertFalse(coordinator.isWarmupInProgress.value)
    val afterA = registry._models.value
    assertEquals(
      "model-a should be Ready after its warmup",
      ModelInitStatus.Ready,
      afterA.single { it.model.name == "model-a" }.initStatus,
    )

    coordinator.cancelAndRestart("model-b")
    advanceUntilIdle()

    assertEquals(listOf("model-a", "model-b"), registry.initializeCalls)
    assertFalse(
      "B initialize returned success → flag must settle at false",
      coordinator.isWarmupInProgress.value,
    )
    val afterB = registry._models.value
    assertEquals(
      "model-b must be Ready after cross-model switch",
      ModelInitStatus.Ready,
      afterB.single { it.model.name == "model-b" }.initStatus,
    )
    assertEquals(
      "model-a must not linger Ready — single-engine invariant",
      ModelInitStatus.Idle,
      afterB.single { it.model.name == "model-a" }.initStatus,
    )
  }

  @Test
  fun crossModelSwitch_isWarmupInProgress_finalizesFalse_evenOnFailure() = runTest {
    // Sanity: a failing initialize must not leave the flag stuck at true.
    // `errorLog.e` in the coordinator's `.onFailure` hops to real `Dispatchers.IO`,
    // which the test scheduler cannot advance — poll in real time for the flag to
    // settle, the same bounded-wait pattern used by `awaitLogFile`.
    appSettings.defaultModelId = "model-a"
    seedAllowlist("model-a")
    registry.initializeHandler = { Result.failure(RuntimeException("GPU+CPU init failed")) }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()

    val deadlineMs = System.currentTimeMillis() + 2_000
    while (coordinator.isWarmupInProgress.value && System.currentTimeMillis() < deadlineMs) {
      Thread.sleep(20)
      advanceUntilIdle()
    }
    assertFalse(
      "failed warmup must still reset isWarmupInProgress to false",
      coordinator.isWarmupInProgress.value,
    )
    // AC-B2-AC2: the finally block in launchWarmup is the sole writer of the false
    // transition — without it, callers could assume the engine is still initialising.
  }

  @Test
  fun isWarmupInProgress_emitsFalse_afterBareCancellation() = runTest {
    // Complements the "stays true across cancel-and-restart" case: when the coordinator scope
    // is cancelled outright (no restart), the flag must settle on `false` via the finally
    // block so no stuck spinner survives.
    appSettings.defaultModelId = "model-a"
    seedAllowlist("model-a")
    registry.initializeHandler = { awaitCancellation() }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()
    assertTrue(coordinator.isWarmupInProgress.value)

    coordinatorScope.cancel()
    advanceUntilIdle()

    assertFalse(coordinator.isWarmupInProgress.value)
  }

  // ---- helpers ----

  private fun fakeModel(name: String, modelId: String): Model =
    Model(name = name, modelId = modelId)

  /**
   * Seed [registry]._models with entries keyed by [modelIds]. Each entry uses the same string
   * for `name` and `modelId` so tests can assert `registry.initializeCalls == listOf(modelId)`
   * (the coordinator translates modelId → Model.name via the registry snapshot).
   */
  private fun seedAllowlist(vararg modelIds: String) {
    registry._models.value = modelIds.map { id ->
      ModelEntry(
        model = fakeModel(name = id, modelId = id),
        downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
        initStatus = ModelInitStatus.Idle,
      )
    }
  }
}

// ---------- Fakes (hand-rolled; no MockK/Mockito per patterns.md) ----------

private class FakeModelRegistry : ModelRegistry {
  val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
  override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

  private val _activeModelName = MutableStateFlow<String?>(null)
  override val activeModelName: StateFlow<String?> = _activeModelName.asStateFlow()

  val initializeCalls: MutableList<String> = mutableListOf()
  var initializeHandler: suspend (String) -> Result<Unit> = { Result.success(Unit) }

  // Non-destructive defaults for methods the coordinator does not exercise: returning
  // success/no-op keeps the fake forward-compatible if a future refactor widens
  // WarmupCoordinator's surface. `download` is the one Flow-returning method; using the
  // empty flow is safer than throwing.
  override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
  override fun download(model: Model): Flow<ModelDownloadStatus> =
    MutableStateFlow(ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED)).asStateFlow()
  override fun cancelDownload(modelName: String) = Unit
  override suspend fun delete(modelName: String) = Unit

  override suspend fun initialize(modelName: String): Result<Unit> {
    initializeCalls += modelName
    return initializeHandler(modelName)
  }

  override suspend fun cleanup(modelName: String) = Unit
  override suspend fun resetConversation(
    modelName: String,
    systemPrompt: String?,
    reason: ResetReason,
    initialMessages: List<com.google.ai.edge.litertlm.Message>,
  ) = Unit
  override fun getModel(modelName: String): Model? = null
}

private class FakeAppSettingsRepository : AppSettingsRepository {
  var defaultModelId: String = ""
  var lastUsedModelId: String = ""
  var migrated: Boolean = false

  val setDefaultModelIdCalls: MutableList<String> = mutableListOf()

  override fun observePerModelSettings(modelId: String): Flow<PerModelSettings?> =
    MutableStateFlow<PerModelSettings?>(null).asStateFlow()
  override suspend fun savePerModelSettings(modelId: String, settings: PerModelSettings) = Unit
  override suspend fun resetPerModelSettings(modelId: String) = Unit

  override suspend fun getDefaultModelId(): String = defaultModelId

  override suspend fun setDefaultModelId(id: String) {
    defaultModelId = id
    setDefaultModelIdCalls += id
  }

  override fun observeDefaultModelId(): Flow<String> =
    MutableStateFlow(defaultModelId).asStateFlow()

  override suspend fun getLastUsedModelId(): String = lastUsedModelId

  override suspend fun setLastUsedModelId(id: String) {
    lastUsedModelId = id
  }

  override fun observeLastUsedModelId(): Flow<String> =
    MutableStateFlow(lastUsedModelId).asStateFlow()

  override suspend fun isSettingsMigrated(): Boolean = migrated

  override suspend fun markSettingsMigrated() {
    migrated = true
  }
}
