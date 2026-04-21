package app.sanctum.machina.ui.home

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.engine.AppCorruptionState
import app.sanctum.machina.logexport.ExportSource
import app.sanctum.machina.logexport.LogExportManager
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TDD harness for [HomeViewModel] (Phase-3 Task 7).
 *
 * Uses `runTest` + [StandardTestDispatcher] per project convention — same pattern as
 * `WarmupCoordinatorTest`. No MockK / no Turbine; registry is a hand-rolled fake whose
 * `models` StateFlow is mutated directly from the test body.
 *
 * `Dispatchers.setMain` is needed because `HomeViewModel` internally creates a
 * `viewModelScope` which defaults to `Dispatchers.Main.immediate` — overriding it with a
 * `StandardTestDispatcher` keeps all emissions on the test scheduler so `advanceUntilIdle()`
 * drives `stateIn` collection deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HomeViewModelTest {

  private val testDispatcher = StandardTestDispatcher()
  private lateinit var registry: FakeModelRegistry
  private lateinit var corruption: AppCorruptionState
  private lateinit var logExport: LogExportManager

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    registry = FakeModelRegistry()
    corruption = AppCorruptionState()
    // Real LogExportManager via the Context-only constructor (same entry point used by the
    // :crash process). Tests in this file never call `buildAndWrite`, so logcat / filesystem
    // reads are not exercised — construction alone is enough for the Hilt-style VM ctor.
    logExport = LogExportManager(
      ApplicationProvider.getApplicationContext<Application>(),
    )
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun hasDownloadedModels_emitsFalse_whenNoDownloadedEntry() = runTest {
    registry.emit(
      listOf(
        entry("a", ModelDownloadStatusType.NOT_DOWNLOADED),
        entry("b", ModelDownloadStatusType.IN_PROGRESS),
        entry("c", ModelDownloadStatusType.FAILED),
      ),
    )

    val viewModel = createSubscribedViewModel()
    advanceUntilIdle()

    assertFalse(viewModel.hasDownloadedModels.value)
  }

  @Test
  fun hasDownloadedModels_emitsTrue_whenAnyEntryDownloaded() = runTest {
    registry.emit(
      listOf(
        entry("a", ModelDownloadStatusType.NOT_DOWNLOADED),
        entry("b", ModelDownloadStatusType.SUCCEEDED),
      ),
    )

    val viewModel = createSubscribedViewModel()
    advanceUntilIdle()

    assertTrue(viewModel.hasDownloadedModels.value)
  }

  @Test
  fun hasDownloadedModels_emitsFalse_onEmptyList() = runTest {
    registry.emit(emptyList())

    val viewModel = createSubscribedViewModel()
    advanceUntilIdle()

    assertFalse(viewModel.hasDownloadedModels.value)
  }

  @Test
  fun hasDownloadedModels_updatesReactively() = runTest {
    registry.emit(listOf(entry("a", ModelDownloadStatusType.NOT_DOWNLOADED)))

    val viewModel = createSubscribedViewModel()
    advanceUntilIdle()
    assertFalse(viewModel.hasDownloadedModels.value)

    registry.emit(
      listOf(
        entry("a", ModelDownloadStatusType.NOT_DOWNLOADED),
        entry("b", ModelDownloadStatusType.SUCCEEDED),
      ),
    )
    advanceUntilIdle()

    assertTrue(viewModel.hasDownloadedModels.value)
  }

  @Test
  fun hasDownloadedModels_initialSeedIsFalse_beforeAnyCollection() = runTest {
    // AC-F2: no-models placeholder must render on cold start before the allowlist loads.
    // A regression flipping `initialValue` to `true` would ship a broken first-run screen;
    // asserting the pre-collection seed pins this.
    registry.emit(listOf(entry("a", ModelDownloadStatusType.SUCCEEDED)))

    val viewModel = HomeViewModel(registry, corruption, logExport)
    // Deliberately no `subscribe` and no `advanceUntilIdle` — we want the pre-collection
    // seed value the UI sees before the first frame observes the flow.
    assertFalse(viewModel.hasDownloadedModels.value)
  }

  @Test
  fun activeModelName_reflectsRegistry_reactively() = runTest {
    val viewModel = HomeViewModel(registry, corruption, logExport)
    assertEquals(null, viewModel.activeModelName.value)

    registry.setActiveModelName("model-a")
    assertEquals("model-a", viewModel.activeModelName.value)

    registry.setActiveModelName("model-b")
    assertEquals("model-b", viewModel.activeModelName.value)
  }

  // ---- Task 10 — corruption banner seed (AC-D5 / US-14) -----------------

  @Test
  fun corruptionOccurred_reflectsFalse_byDefault() = runTest {
    val vm = HomeViewModel(registry, AppCorruptionState(), logExport)
    assertFalse(vm.corruptionOccurred)
  }

  @Test
  fun corruptionOccurred_reflectsTrue_whenFlagSet() = runTest {
    val corrupted = AppCorruptionState().apply { corruptionOccurred = true }
    val vm = HomeViewModel(registry, corrupted, logExport)
    assertTrue(vm.corruptionOccurred)
  }

  // ---- Task 10 — buildAndWrite SAF export contract ----------------------

  @Test
  fun buildAndWrite_success_delegatesThroughLogExportManager() = runTest {
    val recorder = RecordingLogExport(
      ApplicationProvider.getApplicationContext<Application>(),
    )
    val vm = HomeViewModel(registry, corruption, recorder)
    val target = android.net.Uri.parse("content://unit-test/save")

    val result = vm.buildAndWrite(target)

    assertTrue("success path must yield Result.success", result.isSuccess)
    assertEquals(ExportSource.About, recorder.lastSource)
    assertEquals(target, recorder.lastUri)
    assertEquals(
      "writeTo must receive the buildExport output",
      recorder.builtContent,
      recorder.writtenContent,
    )
  }

  @Test
  fun buildAndWrite_ioException_returnsFailure() = runTest {
    val recorder = RecordingLogExport(
      ApplicationProvider.getApplicationContext<Application>(),
    ).apply { writeThrows = IOException("disk full") }
    val vm = HomeViewModel(registry, corruption, recorder)

    val result = vm.buildAndWrite(android.net.Uri.parse("content://unit-test/fail"))

    assertTrue("IOException must be captured as Result.failure", result.isFailure)
    assertTrue(
      "the original IOException must propagate through Result.failure",
      result.exceptionOrNull() is IOException,
    )
  }

  // ---- helpers ----

  // Single factory used by every `hasDownloadedModels` test so the `WhileSubscribed` collector
  // is never forgotten — `.value` on a `WhileSubscribed` StateFlow without an active subscriber
  // returns the initial seed, which would silently produce passing-but-meaningless `assertFalse`.
  private fun TestScope.createSubscribedViewModel(): HomeViewModel {
    val viewModel = HomeViewModel(registry, corruption, logExport)
    backgroundScope.launch { viewModel.hasDownloadedModels.collect {} }
    return viewModel
  }

  private fun entry(name: String, status: ModelDownloadStatusType): ModelEntry =
    ModelEntry(
      model = Model(name = name, modelId = name),
      downloadStatus = ModelDownloadStatus(status = status),
      initStatus = ModelInitStatus.Idle,
    )
}

/**
 * Test double for [LogExportManager] that records export calls and can throw on demand.
 * Extends the production class (opened in Task 10) rather than implementing an interface, so
 * the Hilt-style ctor of [HomeViewModel] accepts it without a separate seam.
 */
private class RecordingLogExport(context: android.content.Context) : LogExportManager(context) {
  var lastSource: ExportSource? = null
  var lastUri: android.net.Uri? = null
  var builtContent: String = "unit-test-log-payload"
  var writtenContent: String? = null
  var writeThrows: IOException? = null

  override suspend fun buildExport(source: ExportSource): String {
    lastSource = source
    return builtContent
  }

  override suspend fun writeTo(uri: android.net.Uri, content: String) {
    lastUri = uri
    writeThrows?.let { throw it }
    writtenContent = content
  }
}

// ---------- Fake (hand-rolled; no MockK/Mockito per patterns.md) ----------

private class FakeModelRegistry : ModelRegistry {
  private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
  override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

  private val _activeModelName = MutableStateFlow<String?>(null)
  override val activeModelName: StateFlow<String?> = _activeModelName.asStateFlow()

  fun emit(list: List<ModelEntry>) {
    _models.value = list
  }

  fun setActiveModelName(name: String?) {
    _activeModelName.value = name
  }

  override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
  override fun download(model: Model): Flow<ModelDownloadStatus> =
    MutableStateFlow(ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED))
      .asStateFlow()
  override fun cancelDownload(modelName: String) = Unit
  override suspend fun delete(modelName: String) = Unit
  override suspend fun initialize(modelName: String): Result<Unit> = Result.success(Unit)
  override suspend fun cleanup(modelName: String) = Unit
  override suspend fun resetConversation(modelName: String, systemPrompt: String?) = Unit
  override fun getModel(modelName: String): Model? = null
}
