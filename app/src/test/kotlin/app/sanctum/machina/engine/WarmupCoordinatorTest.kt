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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * Timing is driven by `runTest` + an [UnconfinedTestDispatcher]-backed coordinator scope so
 * child coroutines unwind inline — no `advanceUntilIdle` bookkeeping needed between a
 * production call and the assertion. `ErrorLog` is real and writes under Robolectric's
 * `filesDir/logs/`;
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
    // UnconfinedTestDispatcher runs child coroutines inline so `scope.launch { ... }` from
    // inside the coordinator unwinds before returning — no `advanceUntilIdle` bookkeeping
    // needed between production call and assertion.
    coordinatorScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
    return WarmupCoordinator(registry, appSettings, errorLog, coordinatorScope)
  }

  // ---- warmupDefault: model resolution (AC-F5) ----

  @Test
  fun warmupDefault_usesDefaultModelId_whenSet() = runTest {
    appSettings.defaultModelId = "model-a"
    appSettings.lastUsedModelId = "model-b"

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()

    assertEquals(listOf("model-a"), registry.initializeCalls)
  }

  @Test
  fun warmupDefault_fallsBackToLastUsed_whenDefaultEmpty() = runTest {
    appSettings.defaultModelId = ""
    appSettings.lastUsedModelId = "model-b"

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
  }

  // ---- warmupDefault: last_used_model_id update on success (AC-F6) ----

  @Test
  fun warmupDefault_updatesLastUsedModelId_onSuccess() = runTest {
    appSettings.defaultModelId = "model-a"
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
    val deadlineMs = System.currentTimeMillis() + 2_000
    while (!errorLogFile.exists() && System.currentTimeMillis() < deadlineMs) {
      Thread.sleep(20)
    }
  }

  // ---- cancelAndRestart: ordering guarantee ----

  @Test
  fun cancelAndRestart_cancelsInFlightJobBeforeNewInitialize() = runTest {
    appSettings.defaultModelId = "model-a"
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

    // One-shot: a second SUCCEEDED emission must NOT call setDefaultModelId again.
    val other = fakeModel(name = "other-local", modelId = "org/other-model")
    registry._models.value = registry._models.value + ModelEntry(
      model = other,
      downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
      initStatus = ModelInitStatus.Idle,
    )
    advanceUntilIdle()

    assertEquals(1, appSettings.setDefaultModelIdCalls.size)
  }

  // ---- isWarmupInProgress StateFlow ----

  @Test
  fun isWarmupInProgress_emitsTrue_duringWarmup() = runTest {
    appSettings.defaultModelId = "model-a"
    registry.initializeHandler = { awaitCancellation() }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()

    assertTrue(coordinator.isWarmupInProgress.value)
  }

  @Test
  fun isWarmupInProgress_emitsFalse_afterCompletion() = runTest {
    appSettings.defaultModelId = "model-a"
    registry.initializeHandler = { Result.success(Unit) }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()

    assertFalse(coordinator.isWarmupInProgress.value)
  }

  @Test
  fun isWarmupInProgress_emitsFalse_afterCancellation() = runTest {
    appSettings.defaultModelId = "model-a"
    registry.initializeHandler = { modelName ->
      if (modelName == "model-a") awaitCancellation()
      else Result.success(Unit)
    }

    val coordinator = newCoordinator()
    coordinator.warmupDefault()
    advanceUntilIdle()
    assertTrue(coordinator.isWarmupInProgress.value)

    coordinator.cancelAndRestart("model-b")
    advanceUntilIdle()

    // After the successful model-b warmup completes, the flag is false.
    assertFalse(coordinator.isWarmupInProgress.value)
  }

  // ---- helpers ----

  private fun fakeModel(name: String, modelId: String): Model =
    Model(name = name, modelId = modelId)
}

// ---------- Fakes (hand-rolled; no MockK/Mockito per patterns.md) ----------

private class FakeModelRegistry : ModelRegistry {
  val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
  override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

  private val _activeModelName = MutableStateFlow<String?>(null)
  override val activeModelName: StateFlow<String?> = _activeModelName.asStateFlow()

  val initializeCalls: MutableList<String> = mutableListOf()
  var initializeHandler: suspend (String) -> Result<Unit> = { Result.success(Unit) }

  override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
  override fun download(model: Model): Flow<ModelDownloadStatus> =
    throw NotImplementedError("unused in WarmupCoordinator tests")

  override fun cancelDownload(modelName: String) {
    throw NotImplementedError("unused in WarmupCoordinator tests")
  }

  override suspend fun delete(modelName: String) {
    throw NotImplementedError("unused in WarmupCoordinator tests")
  }

  override suspend fun initialize(modelName: String): Result<Unit> {
    initializeCalls += modelName
    return initializeHandler(modelName)
  }

  override suspend fun cleanup(modelName: String) {
    throw NotImplementedError("unused in WarmupCoordinator tests")
  }

  override suspend fun resetConversation(modelName: String, systemPrompt: String?) {
    throw NotImplementedError("unused in WarmupCoordinator tests")
  }

  override fun getModel(modelName: String): Model? = null
}

private class FakeAppSettingsRepository : AppSettingsRepository {
  var defaultModelId: String = ""
  var lastUsedModelId: String = ""
  var migrated: Boolean = false

  val setDefaultModelIdCalls: MutableList<String> = mutableListOf()

  override fun observePerModelSettings(modelId: String): Flow<PerModelSettings?> =
    throw NotImplementedError("unused in WarmupCoordinator tests")

  override suspend fun savePerModelSettings(modelId: String, settings: PerModelSettings) {
    throw NotImplementedError("unused in WarmupCoordinator tests")
  }

  override suspend fun resetPerModelSettings(modelId: String) {
    throw NotImplementedError("unused in WarmupCoordinator tests")
  }

  override suspend fun getDefaultModelId(): String = defaultModelId

  override suspend fun setDefaultModelId(id: String) {
    defaultModelId = id
    setDefaultModelIdCalls += id
  }

  override fun observeDefaultModelId(): Flow<String> =
    throw NotImplementedError("unused in WarmupCoordinator tests")

  override suspend fun getLastUsedModelId(): String = lastUsedModelId

  override suspend fun setLastUsedModelId(id: String) {
    lastUsedModelId = id
  }

  override fun observeLastUsedModelId(): Flow<String> =
    throw NotImplementedError("unused in WarmupCoordinator tests")

  override suspend fun isSettingsMigrated(): Boolean = migrated

  override suspend fun markSettingsMigrated() {
    migrated = true
  }
}
