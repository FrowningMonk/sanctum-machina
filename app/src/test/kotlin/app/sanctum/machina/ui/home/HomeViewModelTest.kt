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
import app.sanctum.machina.logexport.LogExportManager
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
