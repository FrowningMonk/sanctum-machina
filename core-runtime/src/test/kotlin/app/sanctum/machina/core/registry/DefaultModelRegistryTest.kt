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
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message as LitertlmMessage
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolProvider
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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
  private lateinit var logsDir: File
  private lateinit var logFile: File

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    logsDir = File(context.filesDir, "logs")
    logFile = File(logsDir, "errors.log")
    // Tests below assert against `errors.log` contents; reset before each test to keep them
    // independent of order and of any in-class fixture residue.
    logsDir.deleteRecursively()
  }

  @After
  fun tearDown() {
    logsDir.deleteRecursively()
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
    // Inject a known non-zero `availMem` via ShadowActivityManager so the assertion would catch
    // a regression that hardcoded `0L` (which Robolectric's default getMemoryInfo also returns).
    val expectedAvailMem = 7_654_321_000L
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val seedInfo = ActivityManager.MemoryInfo().apply { availMem = expectedAvailMem }
    shadowOf(am).setMemoryInfo(seedInfo)

    val recording = RecordingInitDiagnostics()
    val helper = QueuedLlmModelHelper(listOf(Response.Success))
    val registry = buildRegistry(helper, recording)
    registry.awaitEntry(modelName)

    val before = System.currentTimeMillis()
    val result = registry.initialize(modelName)
    val after = System.currentTimeMillis()

    assertTrue("init must succeed for this arm", result.isSuccess)
    assertEquals(1, recording.starts.size)
    val first = recording.starts.first()
    assertEquals(modelName, first.first)
    assertEquals(
      "freeRamBytes must come from ActivityManager.MemoryInfo.availMem",
      expectedAvailMem,
      first.second,
    )
    assertTrue(
      "atEpochMs must be sampled inside the initialize() call",
      first.third in before..after,
    )
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

  // --- Phase 3.6 Task 2: resetConversation logging + reason routing ----------

  /**
   * Mutate the seeded entry's [ModelInitStatus] without going through the lifecycle methods.
   * `_models` is `internal` + `@VisibleForTesting`; same-module access is intentional so the
   * tests in this file can drive states (Idle / Initializing / Failed / Ready) deterministically.
   */
  private fun DefaultModelRegistry.setStatus(name: String, status: ModelInitStatus) {
    _models.update { list ->
      list.map { if (it.model.name == name) it.copy(initStatus = status) else it }
    }
  }

  @Test
  fun resetConversation_skipsAndLogsWarning_whenEngineIdle() = runBlocking {
    val helper = RecordingLlmModelHelper()
    val registry = buildRegistry(helper, RecordingInitDiagnostics())
    registry.awaitEntry(modelName) // entry seeds with ModelInitStatus.Idle by default

    registry.resetConversation(modelName, systemPrompt = null, reason = ResetReason.CHAT_SWITCH)

    assertEquals("helper must NOT be invoked when engine is not Ready", 0, helper.resetCalls.size)
    assertTrue("warn-log file must exist", logFile.exists())
    val lines = logFile.readLines()
    assertEquals(1, lines.size)
    // Idle is the canonical skip-arm format pin: keeps the `skipped reason=… status=…` token
    // order stable, so a future re-order (e.g. `status=… reason=…`) doesn't slip through the
    // looser substring checks used in the Initializing / Failed siblings.
    assertEquals(
      "WARN [inference-reset] skipped reason=CHAT_SWITCH status=Idle",
      lines.single(),
    )
  }

  @Test
  fun resetConversation_skipsAndLogsWarning_whenEngineInitializing() = runBlocking {
    val helper = RecordingLlmModelHelper()
    val registry = buildRegistry(helper, RecordingInitDiagnostics())
    registry.awaitEntry(modelName)
    registry.setStatus(modelName, ModelInitStatus.Initializing)

    registry.resetConversation(modelName, systemPrompt = null, reason = ResetReason.HEAVY)

    assertEquals(0, helper.resetCalls.size)
    val line = logFile.readLines().single()
    assertTrue(line.startsWith("WARN [inference-reset] "))
    assertTrue("description must contain 'skipped': $line", line.contains("skipped"))
    assertTrue("description must contain reason HEAVY: $line", line.contains("HEAVY"))
    assertTrue("description must contain status Initializing: $line", line.contains("Initializing"))
  }

  @Test
  fun resetConversation_skipsAndLogsWarning_whenEngineFailed() = runBlocking {
    val helper = RecordingLlmModelHelper()
    val registry = buildRegistry(helper, RecordingInitDiagnostics())
    registry.awaitEntry(modelName)
    // Long, control-whitespace-laden cause text — pins that the description goes through the
    // shared `write(level, ...)` pipeline (sanitize + 500-char bound from Task 1) rather than
    // a parallel formatter (Decision 5).
    val rawCause = "boom\n\tline2\rrest" + "x".repeat(600)
    registry.setStatus(modelName, ModelInitStatus.Failed(rawCause))

    registry.resetConversation(modelName, systemPrompt = null, reason = ResetReason.SYSTEM_PROMPT)

    assertEquals(0, helper.resetCalls.size)
    val line = logFile.readLines().single()
    assertTrue(line.startsWith("WARN [inference-reset] "))
    assertTrue("description must contain 'skipped': $line", line.contains("skipped"))
    assertTrue("description must contain reason SYSTEM_PROMPT: $line",
      line.contains("SYSTEM_PROMPT"))
    assertTrue("description must contain status Failed: $line", line.contains("Failed"))
    // Pin that the Failed message text actually survives into the description (not just the
    // literal "Failed" status keyword). A regression dropping the message entirely
    // (e.g. status=Failed without `: <msg>`) would slip past the substring check above.
    assertTrue("description must include the Failed cause text (boom): $line",
      line.contains("boom"))
    val description = line.substringAfter("WARN [inference-reset] ")
    assertEquals("description must be capped to 500 chars by the shared write() pipeline",
      500, description.length)
    assertFalse("description must not contain raw newline (sanitize() in write() pipeline)",
      description.contains("\n"))
    assertFalse("description must not contain raw tab (sanitize() in write() pipeline)",
      description.contains("\t"))
    assertFalse("description must not contain raw CR (sanitize() in write() pipeline)",
      description.contains("\r"))
  }

  @Test
  fun resetConversation_dispatchesAndLogsInfo_whenReady() = runBlocking {
    val helper = RecordingLlmModelHelper()
    val registry = buildRegistry(helper, RecordingInitDiagnostics())
    registry.awaitEntry(modelName)
    registry.setStatus(modelName, ModelInitStatus.Ready)

    registry.resetConversation(
      modelName,
      systemPrompt = "stay concise",
      reason = ResetReason.LIGHT_OVERRIDE,
    )

    assertEquals("helper must be invoked exactly once on Ready arm", 1, helper.resetCalls.size)
    val call = helper.resetCalls.single()
    assertEquals(modelName, call.modelName)
    assertTrue("systemInstruction must be non-null when prompt provided",
      call.systemInstruction != null)
    val line = logFile.readLines().single()
    assertTrue(line.startsWith("INFO [inference-reset] "))
    assertTrue("description must contain reason LIGHT_OVERRIDE: $line",
      line.contains("LIGHT_OVERRIDE"))
    assertFalse("Ready arm must not emit a 'skipped' entry: $line", line.contains("skipped"))
  }

  @Test
  fun resetConversation_skipsSilently_whenModelMissing() = runBlocking {
    val helper = RecordingLlmModelHelper()
    val registry = buildRegistry(helper, RecordingInitDiagnostics())
    registry.awaitEntry(modelName) // seed completes; "unknown-name" deliberately has no entry

    registry.resetConversation(
      "unknown-name-${UUID.randomUUID()}",
      systemPrompt = null,
      reason = ResetReason.USER,
    )

    assertEquals("helper must NOT be invoked for unknown model", 0, helper.resetCalls.size)
    assertFalse(
      "unknown-model path must not write to errors.log (Decision 1: silent to avoid log spam)",
      logFile.exists() && logFile.readLines().isNotEmpty(),
    )
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun resetConversation_isSerializedByLifecycleMutex() =
    runTest(StandardTestDispatcher()) {
      // Peak-concurrency check — robust under any dispatcher: if `lifecycleMutex.withLock`
      // serialises calls, two concurrent resets can never both be inside the helper at once,
      // so `peak` stays at 1. Stronger than asserting an `[1, 2]` order, which `incrementAndGet`
      // would make trivially `[1, 2]` even without the mutex.
      val active = AtomicInteger(0)
      val peak = AtomicInteger(0)
      val helper = RecordingLlmModelHelper(onResetCalled = {
        val cur = active.incrementAndGet()
        peak.updateAndGet { prev -> if (cur > prev) cur else prev }
        // helper is non-suspend (LlmModelHelper.resetConversation), so use Thread.sleep.
        // 50ms is enough wall time for a second concurrent entry to be observed if the mutex is
        // broken; mutex serialisation pins peak == 1 regardless.
        Thread.sleep(50)
        active.decrementAndGet()
      })
      val registry = buildRegistry(helper, RecordingInitDiagnostics())

      // The init-block scan publishes _models from a real Dispatchers.Default scope; poll on a
      // real-thread context so virtual time stays untouched (`withTimeout` would race with
      // virtual-time advances under StandardTestDispatcher).
      withContext(Dispatchers.Default) {
        val deadline = System.currentTimeMillis() + 5_000
        while (registry.models.value.none { it.model.name == modelName } &&
          System.currentTimeMillis() < deadline) {
          Thread.sleep(10)
        }
      }
      registry.setStatus(modelName, ModelInitStatus.Ready)

      val j1 = launch { registry.resetConversation(modelName, null, ResetReason.USER) }
      val j2 = launch { registry.resetConversation(modelName, null, ResetReason.USER) }
      advanceUntilIdle()
      j1.join()
      j2.join()

      assertEquals("both resets must complete", 2, helper.resetCalls.size)
      assertEquals(
        "lifecycleMutex must serialise: peak concurrency must remain 1",
        1,
        peak.get(),
      )
    }

  @Test
  fun resetConversation_propagatesHelperException() = runBlocking {
    var throwOnNextCall = true
    val helper = RecordingLlmModelHelper(onResetCalled = {
      if (throwOnNextCall) {
        throwOnNextCall = false
        throw IllegalStateException("boom")
      }
    })
    val registry = buildRegistry(helper, RecordingInitDiagnostics())
    registry.awaitEntry(modelName)
    registry.setStatus(modelName, ModelInitStatus.Ready)

    // First call: helper throws → exception propagates out, info-log NOT written.
    var thrown: Throwable? = null
    try {
      registry.resetConversation(modelName, null, ResetReason.LIGHT_OVERRIDE)
    } catch (t: IllegalStateException) {
      thrown = t
    }
    assertTrue("helper exception must propagate to caller", thrown != null)
    assertEquals("boom", thrown!!.message)

    // Second call: helper succeeds → mutex was released after throw, so this call gets the lock.
    registry.resetConversation(modelName, null, ResetReason.HEAVY)

    assertEquals("helper must have been invoked twice", 2, helper.resetCalls.size)
    // First reset: threw before INFO log → no INFO line for the first call.
    // Second reset: succeeded → exactly one INFO line.
    val allLines = if (logFile.exists()) logFile.readLines() else emptyList()
    val infoLines = allLines.filter { it.startsWith("INFO [inference-reset] ") }
    val warnLines = allLines.filter { it.startsWith("WARN [inference-reset] ") }
    assertEquals(
      "exactly one INFO line — only the successful second reset emits one",
      1,
      infoLines.size,
    )
    assertTrue(
      "INFO line must reference the second reset's reason (HEAVY): ${infoLines.single()}",
      infoLines.single().contains("HEAVY"),
    )
    // Pin: the throw arm must NOT emit a WARN — both calls hit the Ready branch, so the skip
    // path is never taken. Catches a future drift that adds warn-on-throw.
    assertEquals(
      "throw path must not emit a WARN line: $warnLines",
      0,
      warnLines.size,
    )
  }

  // --- Phase 3.6 Task 11: initialMessages prefill + replayedMessages logging ---

  /**
   * Build a minimal `litertlm.Message` for prefill assertions. The constructor is `internal`
   * to the litertlm module, so out-of-module callers must go through Companion factories.
   */
  private fun userMessage(text: String): LitertlmMessage =
    LitertlmMessage.user(Contents.of(listOf(Content.Text(text))))

  private fun modelMessage(text: String): LitertlmMessage =
    LitertlmMessage.model(Contents.of(listOf(Content.Text(text))))

  @Test
  fun resetConversation_passesInitialMessagesToHelper_whenReady() = runBlocking {
    val helper = RecordingLlmModelHelper()
    val registry = buildRegistry(helper, RecordingInitDiagnostics())
    registry.awaitEntry(modelName)
    registry.setStatus(modelName, ModelInitStatus.Ready)

    val msgs = listOf(userMessage("hi"), modelMessage("hello"))
    registry.resetConversation(
      modelName,
      systemPrompt = null,
      reason = ResetReason.CHAT_SWITCH,
      initialMessages = msgs,
    )

    assertEquals(1, helper.resetCalls.size)
    val call = helper.resetCalls.single()
    assertEquals(
      "helper must receive the same list reference content the caller passed",
      msgs,
      call.initialMessages,
    )
    val line = logFile.readLines().single()
    assertTrue(line.startsWith("INFO [inference-reset] "))
    assertTrue("info line must contain reason CHAT_SWITCH: $line", line.contains("CHAT_SWITCH"))
    assertTrue(
      "info line must contain replayedMessages=2: $line",
      line.contains("replayedMessages=2"),
    )
  }

  @Test
  fun resetConversation_passesEmptyByDefault_whenCallerOmits() = runBlocking {
    // Default-parameter pin: `resetConversation(modelName, systemPrompt, reason)` without
    // an explicit `initialMessages` must reach the helper as `emptyList()`. A regression
    // that drops the default would force every caller (Quick chat, USER ↻ tap, …) to
    // pass an empty list explicitly.
    val helper = RecordingLlmModelHelper()
    val registry = buildRegistry(helper, RecordingInitDiagnostics())
    registry.awaitEntry(modelName)
    registry.setStatus(modelName, ModelInitStatus.Ready)

    registry.resetConversation(modelName, systemPrompt = null, reason = ResetReason.USER)

    assertEquals(1, helper.resetCalls.size)
    assertTrue(
      "helper must receive emptyList when caller omits initialMessages",
      helper.resetCalls.single().initialMessages.isEmpty(),
    )
    val line = logFile.readLines().single()
    assertTrue("info line must contain replayedMessages=0: $line", line.contains("replayedMessages=0"))
  }

  @Test
  fun resetConversation_skipsAndDoesNotForwardInitialMessages_whenNonReady() = runBlocking {
    // Non-Ready engine: helper is NOT invoked even when caller passes a non-empty list.
    // The warning still fires (skip-arm format pinned by the existing
    // `resetConversation_skipsAndLogsWarning_whenEngineIdle` test); this test guards
    // the orthogonal claim that no `initialMessages` leak through to the helper.
    val helper = RecordingLlmModelHelper()
    val registry = buildRegistry(helper, RecordingInitDiagnostics())
    registry.awaitEntry(modelName) // Idle by default

    val msgs = listOf(userMessage("hi"), modelMessage("hello"))
    registry.resetConversation(
      modelName,
      systemPrompt = null,
      reason = ResetReason.CHAT_SWITCH,
      initialMessages = msgs,
    )

    assertEquals(
      "helper must NOT be invoked when engine is not Ready, regardless of initialMessages",
      0,
      helper.resetCalls.size,
    )
    val line = logFile.readLines().single()
    assertTrue(line.startsWith("WARN [inference-reset] "))
    assertTrue("skip line must contain 'skipped': $line", line.contains("skipped"))
  }

  // --- helpers ---------------------------------------------------------------

  /**
   * Records [LlmModelHelper.resetConversation] invocations. [onResetCalled] is invoked synchronously
   * inside the override and lets individual tests inject behaviour (sleep / throw / etc).
   */
  private class RecordingLlmModelHelper(
    private val onResetCalled: () -> Unit = {},
  ) : LlmModelHelper {
    data class ResetCall(
      val modelName: String,
      val systemInstruction: Contents?,
      val initialMessages: List<LitertlmMessage>,
    )
    val resetCalls: MutableList<ResetCall> = java.util.Collections.synchronizedList(mutableListOf())

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
    ) = onDone("not used in resetConversation tests")

    override fun resetConversation(
      model: Model,
      supportImage: Boolean,
      supportAudio: Boolean,
      systemInstruction: Contents?,
      tools: List<ToolProvider>,
      enableConversationConstrainedDecoding: Boolean,
      initialMessages: List<LitertlmMessage>,
    ) {
      resetCalls.add(ResetCall(model.name, systemInstruction, initialMessages))
      onResetCalled()
    }

    override fun cleanUp(model: Model, onDone: () -> Unit) = onDone()

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

  // --- existing helpers ------------------------------------------------------

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
      initialMessages: List<LitertlmMessage>,
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
      initialMessages: List<LitertlmMessage>,
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
