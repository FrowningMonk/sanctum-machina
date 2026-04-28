package app.sanctum.machina.core.registry

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.DownloadRepository
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.runtime.CleanUpListener
import app.sanctum.machina.core.runtime.LlmModelHelper
import app.sanctum.machina.core.runtime.ResultListener
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3.5 Task 6: pin the [InitDiagnostics] call-order contract on
 * [DefaultModelRegistry.initialize] — exactly one [InitDiagnostics.onInitStart] per init attempt
 * (before the first `awaitInitialize`, after `releaseEngine`); exactly one
 * [InitDiagnostics.onInitEnd] on the success arm (GPU or CPU fallback) and on the full-failure
 * arm; **none** on the cancellation arm.
 *
 * The fixture builds a real registry under Robolectric (so `ActivityManager`/`MemoryInfo` is a
 * real Android API surface, matching production), seeds it with one of the allowlist's existing
 * model names so the entry is present in `_models` after the init-block scan, and drives the
 * outcome arm via a per-call response queue in [QueuedLlmModelHelper]. `Model.instance` is set
 * to a non-null sentinel on the success arms (the registry's success predicate is
 * `err.isEmpty() && model.instance != null`) and left null on failure arms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DefaultModelRegistryTest {

  // Use a name that exists in `core-runtime/src/main/assets/model_allowlist.json` so the entry
  // is materialised by the init-block scan; we then await its appearance via `models.first` to
  // de-flake against the scan / writer race.
  private val modelName = "Gemma-4-E2B-it"

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
  }

  /** Wait for the init-block allowlist scan to surface our entry into `_models`. */
  private suspend fun DefaultModelRegistry.awaitEntry(name: String) {
    withTimeout(5_000) { models.first { list -> list.any { it.model.name == name } } }
  }

  private fun buildRegistry(
    helper: LlmModelHelper,
    initDiagnostics: InitDiagnostics,
  ): DefaultModelRegistry =
    DefaultModelRegistry(
      downloadRepository = NoOpDownloadRepository,
      llmModelHelper = helper,
      allowlistLoader = AllowlistLoader(context, ErrorLog(context)),
      errorLog = ErrorLog(context),
      context = context,
      initDiagnostics = initDiagnostics,
    )

  @Test
  fun initialize_recordsExactlyOneOnInitStartBeforeFirstAwaitInitialize() = runBlocking {
    val recording = RecordingInitDiagnostics()
    val helper = QueuedLlmModelHelper(listOf(Response.Success))
    val registry = buildRegistry(helper, recording)
    registry.awaitEntry(modelName)

    val result = registry.initialize(modelName)

    assertTrue("init must succeed for this arm", result.isSuccess)
    assertEquals(1, recording.starts.size)
    val first = recording.starts.first()
    assertEquals(modelName, first.first)
    // The recorded `freeRamBytes` must equal what `ActivityManager.MemoryInfo.availMem` reports
    // at the moment the test runs. Robolectric's default `availMem` is 0; on a real device it is
    // non-zero. We assert equality with a fresh read against the same API so this test pins
    // "registry reads availMem via ActivityManager", independent of host-OS / shadow defaults.
    val expectedAvailMem = run {
      val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
      val info = ActivityManager.MemoryInfo()
      am.getMemoryInfo(info)
      info.availMem
    }
    assertEquals(
      "freeRamBytes must come from ActivityManager.MemoryInfo.availMem",
      expectedAvailMem,
      first.second,
    )
    // `atEpochMs` must be a sane epoch-ms value taken near now (not 0L, not the future).
    val now = System.currentTimeMillis()
    assertTrue("atEpochMs must be near `now`", first.third in (now - 60_000)..now)
    // The registry must call `onInitStart` before any `onInitEnd`. With a queued helper the
    // helper invocation has already happened by this point, so `callOrder()` is fully populated.
    val order = recording.callOrder()
    assertEquals("first call must be the start event", "start:$modelName", order.first())
    assertTrue(
      "no end event may precede the start event",
      order.indexOfFirst { it.startsWith("end:") } > order.indexOfFirst { it.startsWith("start:") },
    )
  }

  @Test
  fun initialize_recordsOnInitEndTrue_onGpuSuccessArm() = runBlocking {
    val recording = RecordingInitDiagnostics()
    val helper = QueuedLlmModelHelper(listOf(Response.Success))
    val registry = buildRegistry(helper, recording)
    registry.awaitEntry(modelName)

    val result = registry.initialize(modelName)

    assertTrue(result.isSuccess)
    assertEquals(listOf(true), recording.ends)
  }

  @Test
  fun initialize_recordsOnInitEndTrue_onCpuFallbackArm_notTwo() = runBlocking {
    // GPU arm fails (non-empty err), CPU arm succeeds. Decision 8: this is ONE attempt — one
    // start, one end(true), never two starts or two ends.
    val recording = RecordingInitDiagnostics()
    val helper = QueuedLlmModelHelper(listOf(Response.Failure("GPU oom"), Response.Success))
    val registry = buildRegistry(helper, recording)
    registry.awaitEntry(modelName)

    val result = registry.initialize(modelName)

    assertTrue(result.isSuccess)
    assertEquals(1, recording.starts.size)
    assertEquals(listOf(true), recording.ends)
  }

  @Test
  fun initialize_recordsOnInitEndFalse_onFullFailArm() = runBlocking {
    // Both GPU and CPU arms fail → full failure → exactly one end(false).
    val recording = RecordingInitDiagnostics()
    val helper =
      QueuedLlmModelHelper(listOf(Response.Failure("GPU oom"), Response.Failure("CPU oom")))
    val registry = buildRegistry(helper, recording)
    registry.awaitEntry(modelName)

    val result = registry.initialize(modelName)

    assertTrue("full-fail arm must surface failure", result.isFailure)
    assertEquals(1, recording.starts.size)
    assertEquals(listOf(false), recording.ends)
  }

  @Test
  fun initialize_doesNotCallOnInitEnd_onCancellation() = runBlocking {
    // The fake helper signals it was invoked but never calls `onDone`, so `awaitInitialize`
    // suspends indefinitely. We then cancel the launched job; per Decision 8 the cancellation
    // arm must not finalise the snapshot — ends remains empty.
    val recording = RecordingInitDiagnostics()
    val invoked = CountDownLatch(1)
    val helper = SuspendingLlmModelHelper(invoked)
    val registry = buildRegistry(helper, recording)
    registry.awaitEntry(modelName)

    val job = launch(Dispatchers.Default) { registry.initialize(modelName) }
    // Wait for the helper to be hit, ensuring `onInitStart` has already run.
    assertTrue(
      "fake helper must be invoked within timeout",
      invoked.await(5, TimeUnit.SECONDS),
    )
    job.cancel()
    job.join()

    assertEquals(1, recording.starts.size)
    assertTrue("ends must remain empty on cancellation", recording.ends.isEmpty())
  }

  // --- helpers ---------------------------------------------------------------

  private sealed interface Response {
    object Success : Response
    data class Failure(val err: String) : Response
  }

  /** Drives [LlmModelHelper.initialize] from a per-call queued response. */
  private class QueuedLlmModelHelper(private val responses: List<Response>) : LlmModelHelper {
    private var index = 0

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
    ) {
      val response = responses.getOrElse(index) { Response.Failure("no response queued") }
      index += 1
      when (response) {
        is Response.Success -> {
          // Registry success predicate is `err.isEmpty() && model.instance != null`. A sentinel
          // string is sufficient — `releaseEngine` casts via `as?` and treats foreign types as
          // already-released, so cleanup is a safe no-op.
          model.instance = "fake-engine-${UUID.randomUUID()}"
          onDone("")
        }
        is Response.Failure -> onDone(response.err)
      }
    }

    override fun resetConversation(
      model: Model,
      supportImage: Boolean,
      supportAudio: Boolean,
      systemInstruction: Contents?,
      tools: List<ToolProvider>,
      enableConversationConstrainedDecoding: Boolean,
    ) = Unit

    override fun cleanUp(model: Model, onDone: () -> Unit) {
      model.instance = null
      onDone()
    }

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
    ) = Unit

    override fun stopResponse(model: Model) = Unit
  }

  /** Helper that signals it was invoked but never calls `onDone` — used to drive cancellation. */
  private class SuspendingLlmModelHelper(private val invoked: CountDownLatch) : LlmModelHelper {
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
    ) {
      invoked.countDown()
      // Intentionally never invokes `onDone`; the registry's `suspendCancellableCoroutine`
      // remains suspended until the surrounding job is cancelled.
    }

    override fun resetConversation(
      model: Model,
      supportImage: Boolean,
      supportAudio: Boolean,
      systemInstruction: Contents?,
      tools: List<ToolProvider>,
      enableConversationConstrainedDecoding: Boolean,
    ) = Unit

    override fun cleanUp(model: Model, onDone: () -> Unit) = Unit

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
    ) = Unit

    override fun stopResponse(model: Model) = Unit
  }

  /** Hand-rolled no-op stub for [DownloadRepository]. */
  private object NoOpDownloadRepository : DownloadRepository {
    override fun downloadModel(
      model: Model,
      onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
    ) = Unit

    override fun cancelDownloadModel(model: Model) = Unit

    override fun cancelAll(onComplete: () -> Unit) = onComplete()

    override fun observerWorkerProgress(
      workerId: UUID,
      model: Model,
      onStatusUpdated: (model: Model, status: ModelDownloadStatus) -> Unit,
    ) = Unit
  }

}
