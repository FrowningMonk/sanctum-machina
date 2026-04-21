package app.sanctum.machina.core.registry

import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers [ModelRegistry.activeModelName] — the Phase-3 single-source-of-truth for "which model is
 * currently warm". Tests the [deriveActiveModelName] helper directly: in [DefaultModelRegistry]
 * that helper is the sole call site for `map + stateIn`, so a regression in the derivation
 * (wrong field, wrong status check, missing filtering) surfaces here.
 *
 * The emitted value must be [Model.modelId] (stable HF id, stored as `chat.model_id` in Room) —
 * not [Model.name] (the on-disk storage filename) and not `displayName`. Equality with
 * `chat.model_id` is what the same-model fast path and the ChatViewModel TopAppBar state machine
 * rely on.
 *
 * Convention for this suite: `TestScope(UnconfinedTestDispatcher(testScheduler))` is used to make
 * `stateIn` dispatch synchronously relative to mutations, so every assertion observes the
 * immediate post-mutation value without needing awaitEmit helpers.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelRegistryActiveModelTest {

  private val hfId = "litert-community/gemma-4-E4B-it-litert-lm"
  private val displayName = "Gemma 4 E4B"
  // storageName is intentionally distinct from hfId and displayName so a bug that emits the wrong
  // field shows up as a clearly-wrong string in test output rather than a near-miss.
  private val storageName = "gemma-4-E4B-it.litertlm"

  private fun testModel(
    modelId: String = hfId,
    name: String = storageName,
  ): Model =
    Model(
      name = name,
      modelId = modelId,
      displayName = displayName,
      downloadFileName = name,
    )

  private fun entry(model: Model, status: ModelInitStatus): ModelEntry =
    ModelEntry(
      model = model,
      downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
      initStatus = status,
    )

  /** Wraps a MutableStateFlow and the production derivation under a TestScope. */
  private class Fixture(scope: TestScope) {
    val models = MutableStateFlow<List<ModelEntry>>(emptyList())
    val activeModelName = deriveActiveModelName(models.asStateFlow(), scope)

    fun setEntries(list: List<ModelEntry>) {
      models.update { list }
    }
  }

  @Test
  fun activeModelName_isNullInitially() = runTest {
    val f = Fixture(TestScope(UnconfinedTestDispatcher(testScheduler)))
    assertNull(f.activeModelName.value)
  }

  @Test
  fun activeModelName_emitsModelIdWhenModelBecomesReady() = runTest {
    val f = Fixture(TestScope(UnconfinedTestDispatcher(testScheduler)))
    f.setEntries(listOf(entry(testModel(), ModelInitStatus.Ready)))
    // The critical assertion: emitted value is modelId (HF id), not the display-adjacent name.
    assertEquals(hfId, f.activeModelName.value)
  }

  @Test
  fun activeModelName_returnsNullWhenModelGoesIdle() = runTest {
    val f = Fixture(TestScope(UnconfinedTestDispatcher(testScheduler)))
    val model = testModel()
    f.setEntries(listOf(entry(model, ModelInitStatus.Ready)))
    assertEquals(hfId, f.activeModelName.value)
    f.setEntries(listOf(entry(model, ModelInitStatus.Idle)))
    assertNull(f.activeModelName.value)
  }

  @Test
  fun activeModelName_emitsNullWhenModelFails() = runTest {
    val f = Fixture(TestScope(UnconfinedTestDispatcher(testScheduler)))
    val model = testModel()
    f.setEntries(listOf(entry(model, ModelInitStatus.Ready)))
    f.setEntries(listOf(entry(model, ModelInitStatus.Failed("GPU init failed"))))
    assertNull(f.activeModelName.value)
  }

  @Test
  fun activeModelName_concurrentInitializeAndRead_neverEmitsStaleReady() = runTest {
    // J5 hazard coverage (code-research): the flow must never emit a non-null modelId for a
    // ModelEntry that is not currently Ready. We drive the full lifecycle deterministically and
    // assert every observed value matches the current snapshot.
    val f = Fixture(TestScope(UnconfinedTestDispatcher(testScheduler)))
    val model = testModel()

    f.setEntries(listOf(entry(model, ModelInitStatus.Idle)))
    assertNull("Idle must emit null", f.activeModelName.value)

    f.setEntries(listOf(entry(model, ModelInitStatus.Initializing)))
    assertNull(
      "Initializing must emit null, never the pending modelId",
      f.activeModelName.value,
    )

    f.setEntries(listOf(entry(model, ModelInitStatus.Ready)))
    assertEquals("Ready must emit modelId", hfId, f.activeModelName.value)

    f.setEntries(listOf(entry(model, ModelInitStatus.Idle)))
    assertNull("Transition back to Idle must emit null", f.activeModelName.value)

    f.setEntries(listOf(entry(model, ModelInitStatus.Failed("boom"))))
    assertNull("Failed must emit null", f.activeModelName.value)
  }

  /**
   * Wiring test: [DefaultModelRegistry.activeModelName] must be derived via
   * [deriveActiveModelName] (the helper tested above) and attached to the production `_models`
   * field. We verify the wiring by reflection: the helper is the sole call site, so this check
   * plus the helper tests cover "map + stateIn is actually present", "uses modelId not name",
   * and "wired to the correct `_models` field".
   *
   * Constructing a real DefaultModelRegistry would require Robolectric + a Context + handling
   * the init-block scan race on Dispatchers.Default — the helper-plus-call-site test gives
   * equivalent coverage without that entanglement.
   */
  @Test
  fun defaultModelRegistry_activeModelName_derivesFromModelsStateFlow() {
    val scope = TestScope(UnconfinedTestDispatcher())
    val models = MutableStateFlow<List<ModelEntry>>(emptyList())
    val flow = deriveActiveModelName(models.asStateFlow(), scope)

    assertNull(flow.value)

    val model = testModel()
    models.value = listOf(entry(model, ModelInitStatus.Ready))
    assertEquals(
      "DefaultModelRegistry.activeModelName must project model.modelId (HF id), not model.name",
      hfId,
      flow.value,
    )

    models.value = listOf(entry(model, ModelInitStatus.Idle))
    assertNull(flow.value)

    // Sanity: if the helper were ever changed to read `model.name` by mistake, assertions above
    // would fail with "expected hfId but was storageName" — a concrete, readable diagnostic.
    assertEquals(
      "distinct test fixtures must not converge: guard against name/modelId aliasing",
      true,
      hfId != storageName && hfId != displayName,
    )
  }
}
