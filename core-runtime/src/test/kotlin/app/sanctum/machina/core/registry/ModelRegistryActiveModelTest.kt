package app.sanctum.machina.core.registry

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.DownloadRepository
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.runtime.CleanUpListener
import app.sanctum.machina.core.runtime.LlmModelHelper
import app.sanctum.machina.core.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [ModelRegistry.activeModelName] — the Phase-3 single-source-of-truth for "which model is
 * currently warm".
 *
 * - Tests 1–5 drive the [deriveActiveModelName] helper directly to verify the derivation logic
 *   (initial null, modelId projection, lifecycle transitions, mixed-list filtering, J5 atomicity).
 * - Test 6 constructs a real [DefaultModelRegistry] under Robolectric and verifies that the
 *   public `activeModelName` property is actually wired to the internal `_models` field — which
 *   closes the gap that a helper-only suite would miss (e.g. `override val activeModelName =
 *   MutableStateFlow(null).asStateFlow()` compiles and would pass helper tests but breaks
 *   production).
 *
 * The emitted value must be [Model.modelId] (stable HF id, stored as `chat.model_id` in Room) —
 * not [Model.name] (the on-disk storage filename). Equality with `chat.model_id` is what the
 * same-model fast path and the ChatViewModel TopAppBar state machine rely on.
 *
 * Robolectric runs all tests; tests 1–5 don't need Android framework and pay only the
 * per-class setup cost, which is negligible relative to the single Context-requiring test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelRegistryActiveModelTest {

  private val hfId = "litert-community/gemma-4-E4B-it-litert-lm"
  private val displayName = "Gemma 4 E4B"
  // storageName is intentionally distinct from hfId and displayName so a regression that emits
  // the wrong field shows up as a clearly-wrong string in test output.
  // KEEP DISTINCT: the divergence is an invariant of this suite.
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

  /** Wraps a [MutableStateFlow] plus the production derivation under a [TestScope]. */
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
  fun activeModelName_withMixedList_picksOnlyReadyEntry() = runTest {
    // Pins the `firstOrNull { it.initStatus === Ready }` predicate: a refactor that dropped the
    // filter or widened it to `Ready || Initializing` would surface here. Uses three entries
    // with distinct modelIds so the emitted value identifies which one was selected.
    val f = Fixture(TestScope(UnconfinedTestDispatcher(testScheduler)))
    val a = testModel(modelId = "litert-community/model-a", name = "a.litertlm")
    val b = testModel(modelId = "litert-community/model-b", name = "b.litertlm")
    val c = testModel(modelId = "litert-community/model-c", name = "c.litertlm")

    f.setEntries(
      listOf(
        entry(a, ModelInitStatus.Idle),
        entry(b, ModelInitStatus.Ready),
        entry(c, ModelInitStatus.Initializing),
      )
    )
    assertEquals("only the Ready entry's modelId must be emitted", b.modelId, f.activeModelName.value)

    f.setEntries(
      listOf(
        entry(a, ModelInitStatus.Ready),
        entry(b, ModelInitStatus.Idle),
      )
    )
    assertEquals(
      "shifting Ready to a different entry must update the emitted modelId",
      a.modelId,
      f.activeModelName.value,
    )

    f.setEntries(
      listOf(
        entry(a, ModelInitStatus.Idle),
        entry(b, ModelInitStatus.Failed("boom")),
        entry(c, ModelInitStatus.Initializing),
      )
    )
    assertNull("no Ready entry → null", f.activeModelName.value)
  }

  @Test
  fun activeModelName_everyStateMatchesSnapshot_noStaleReady() = runTest {
    // J5 hazard coverage (code-research): every emitted value must reflect the current _models
    // snapshot — the flow must never advertise a modelId for an entry that is not Ready. Kotlin
    // StateFlow.map is atomic per emission, so sequential mutations under
    // UnconfinedTestDispatcher suffice; a real-thread race would add no observable behaviour
    // under the atomicity guarantee.
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

  @Test
  fun defaultModelRegistry_activeModelName_wiredToMutationsOfUnderlyingModelsFlow() =
    runBlocking {
      // Wiring test: constructs a real DefaultModelRegistry, mutates the internal `_models` via
      // the @VisibleForTesting accessor, and asserts that `activeModelName` reflects the change.
      // Catches a regression where the override is replaced with a constant or hardcoded flow —
      // something helper-only tests cannot detect.
      val context = ApplicationProvider.getApplicationContext<Context>()
      val registry =
        DefaultModelRegistry(
          downloadRepository = NoOpDownloadRepository,
          llmModelHelper = NoOpLlmModelHelper,
          allowlistLoader = AllowlistLoader(context),
          errorLog = ErrorLog(context),
          context = context,
        )

      // Use a probe modelId that cannot coincidentally match a real-allowlist entry loaded by
      // the init-block scan: the scan is racing with our writes, so a coincidental match would
      // let a stale-Ready regression slip through.
      val probeHfId = "litert-community/wiring-probe-${UUID.randomUUID()}"
      val probeStorageName = "wiring-probe.litertlm"
      val probeModel =
        Model(name = probeStorageName, modelId = probeHfId, downloadFileName = probeStorageName)
      val readyEntry =
        ModelEntry(
          model = probeModel,
          downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
          initStatus = ModelInitStatus.Ready,
        )

      // Writer loop defeats the init-block scan race: even if `refreshAllowlist` overwrites the
      // list, we re-install it every few ms until `first { ... }` observes our probe.
      val writer =
        launch(Dispatchers.Default) {
          while (isActive) {
            registry._models.value = listOf(readyEntry)
            delay(5)
          }
        }
      try {
        val observedReady =
          withTimeout(5_000) { registry.activeModelName.first { it == probeHfId } }
        assertEquals(
          "DefaultModelRegistry.activeModelName must project model.modelId of the Ready entry",
          probeHfId,
          observedReady,
        )
      } finally {
        writer.cancel()
      }

      // Stop writing; install an Idle snapshot and verify activeModelName drops to null. The
      // scan may also overwrite to Idle-or-empty-list — both produce null, so no race loop needed.
      registry._models.value = listOf(readyEntry.copy(initStatus = ModelInitStatus.Idle))
      val observedNull =
        withTimeout(5_000) { registry.activeModelName.first { it == null } }
      assertNull(observedNull)

      // `models` and `activeModelName` must be distinct StateFlow instances — a regression that
      // aliased them would be wildly wrong but is cheap to pin here.
      assertNotSame(registry.models as Any, registry.activeModelName as Any)
    }
}

/**
 * Hand-rolled no-op stub. Project convention: no Mockito/MockK for interface test doubles.
 * DefaultModelRegistry's init-block path does not invoke any LlmModelHelper method; stubs exist
 * only to satisfy the interface contract during construction.
 */
private object NoOpLlmModelHelper : LlmModelHelper {
  override fun initialize(
    context: Context,
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
    coroutineScope: CoroutineScope?,
  ) {}

  override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
  ) {}

  override fun cleanUp(model: Model, onDone: () -> Unit) {}

  override fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit,
    images: List<Bitmap>,
    audioClips: List<ByteArray>,
    coroutineScope: CoroutineScope?,
    extraContext: Map<String, String>?,
  ) {}

  override fun stopResponse(model: Model) {}
}

/**
 * Hand-rolled no-op stub for [DownloadRepository]; see [NoOpLlmModelHelper] rationale.
 */
private object NoOpDownloadRepository : DownloadRepository {
  override fun downloadModel(
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {}

  override fun cancelDownloadModel(model: Model) {}

  override fun cancelAll(onComplete: () -> Unit) {
    onComplete()
  }

  override fun observerWorkerProgress(
    workerId: UUID,
    model: Model,
    onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
  ) {}
}
