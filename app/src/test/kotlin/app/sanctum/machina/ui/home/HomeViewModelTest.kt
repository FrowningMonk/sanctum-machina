package app.sanctum.machina.ui.home

import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
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
class HomeViewModelTest {

  private val testDispatcher = StandardTestDispatcher()
  private lateinit var registry: FakeModelRegistry

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    registry = FakeModelRegistry()
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

    val viewModel = HomeViewModel(registry)
    subscribe(viewModel)
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

    val viewModel = HomeViewModel(registry)
    subscribe(viewModel)
    advanceUntilIdle()

    assertTrue(viewModel.hasDownloadedModels.value)
  }

  @Test
  fun hasDownloadedModels_emitsFalse_onEmptyList() = runTest {
    registry.emit(emptyList())

    val viewModel = HomeViewModel(registry)
    subscribe(viewModel)
    advanceUntilIdle()

    assertFalse(viewModel.hasDownloadedModels.value)
  }

  @Test
  fun hasDownloadedModels_updatesReactively() = runTest {
    registry.emit(listOf(entry("a", ModelDownloadStatusType.NOT_DOWNLOADED)))

    val viewModel = HomeViewModel(registry)
    subscribe(viewModel)
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
  fun activeModelName_isPassedThrough_fromRegistry() = runTest {
    registry.setActiveModelName("model-b")

    val viewModel = HomeViewModel(registry)
    advanceUntilIdle()

    assertEquals("model-b", viewModel.activeModelName.value)
  }

  // Keep [hasDownloadedModels] hot inside `runTest`. The production flow uses
  // `WhileSubscribed(5_000)` — without an active collector, `.value` stays at the
  // initial seed and the mapping transform never runs. `backgroundScope` is cancelled
  // automatically when `runTest` ends.
  private fun TestScope.subscribe(viewModel: HomeViewModel) {
    backgroundScope.launch { viewModel.hasDownloadedModels.collect {} }
  }

  // ---- helpers ----

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
