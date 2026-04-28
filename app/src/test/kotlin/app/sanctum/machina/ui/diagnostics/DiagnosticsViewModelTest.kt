package app.sanctum.machina.ui.diagnostics

import android.net.Uri
import app.sanctum.machina.diagnostics.DiagnosticsState
import app.sanctum.machina.diagnostics.InitSnapshot
import app.sanctum.machina.diagnostics.Outcome
import app.sanctum.machina.logexport.DeviceInfoProvider
import app.sanctum.machina.logexport.ExportSource
import app.sanctum.machina.logexport.LogExporter
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
 * TDD harness for [DiagnosticsViewModel] (Phase-3.5 Task 8).
 *
 * Hand-rolled fakes per `patterns.md` — no MockK / Mockito, no `Context`
 * dependency thanks to the [LogExporter] seam. Robolectric is here only to
 * unstub `android.net.Uri.parse` in [buildAndWrite_success_returnsResultSuccess]
 * / [buildAndWrite_ioException_returnsResultFailure] — Android `Uri` is a
 * platform class with no JVM implementation, and `unitTests.returnDefaultValues
 * = false` is the project default, so without Robolectric `Uri.parse` throws
 * from these two tests. Splitting into two test classes (pure-JVM + Robolectric)
 * was rejected because the Robolectric init pays for itself once and the four
 * pure-JVM-eligible tests are too few to justify the duplication.
 *
 * `Dispatchers.setMain(testDispatcher)` is required because `viewModelScope`
 * picks up `Main` immediately — overriding lets `advanceTimeBy` drive the
 * polling loop deterministically.
 *
 * The polling loop lives in `flow { ... }.stateIn(viewModelScope,
 * WhileSubscribed(0), seed)`, so it only starts when the test launches a
 * collector, and stops as soon as the collector is cancelled — `runTest` then
 * drains a finite scheduler in microseconds instead of looping forever on a
 * never-ending `while (isActive) delay(1_000)`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val zone: ZoneId = ZoneId.systemDefault()

    private lateinit var fakeState: FakeDiagnosticsState
    private lateinit var fakeDeviceInfo: FakeDeviceInfoProvider
    private lateinit var fakeLogExporter: FakeLogExporter

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeState = FakeDiagnosticsState(initial = null)
        fakeDeviceInfo = FakeDeviceInfoProvider(availableMemoryBytes = 4_000_000_000L)
        fakeLogExporter = FakeLogExporter()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): DiagnosticsViewModel =
        DiagnosticsViewModel(fakeState, fakeDeviceInfo, fakeLogExporter)

    // ---------- last init rendering (Decision 12 — four variants) ----------
    //
    // No collector is launched: `WhileSubscribed(0)` keeps the flow idle and
    // `state.value` returns the seed, which is computed synchronously in the
    // VM constructor. That gives us the rendered string without ever entering
    // the 1-second tick loop.

    @Test
    fun lastInit_whenSnapshotOk_rendersOkVariant() = runTest(testDispatcher) {
        fakeState.set(
            InitSnapshot(
                modelName = "E4B",
                freeRamBytes = 3_500_000_000L,
                atEpochMs = FIXED_AT_EPOCH_MS,
                outcome = Outcome.Ok,
            ),
        )

        val viewModel = newViewModel()

        val text = viewModel.state.value.lastInitText
        assertTrue("expected '3.2 ГБ' in: $text", text.contains("3.2 ГБ"))
        assertTrue("expected 'E4B' in: $text", text.contains("E4B"))
        assertTrue("expected 'ok' in: $text", text.contains("ok"))
        assertTrue("expected HH:mm in: $text", text.contains(expectedHhmm()))
    }

    @Test
    fun lastInit_whenSnapshotFailed_rendersErrorVariant() = runTest(testDispatcher) {
        fakeState.set(
            InitSnapshot(
                modelName = "E4B",
                freeRamBytes = 3_500_000_000L,
                atEpochMs = FIXED_AT_EPOCH_MS,
                outcome = Outcome.Failed,
            ),
        )

        val viewModel = newViewModel()

        val text = viewModel.state.value.lastInitText
        assertTrue("expected 'ошибка' in: $text", text.contains("ошибка"))
    }

    @Test
    fun lastInit_whenSnapshotNull_rendersNeverVariant() = runTest(testDispatcher) {
        fakeState.set(null)

        val viewModel = newViewModel()

        assertEquals("пока не было", viewModel.state.value.lastInitText)
    }

    @Test
    fun lastInit_whenSnapshotInProgress_rendersFourthVariant() = runTest(testDispatcher) {
        fakeState.set(
            InitSnapshot(
                modelName = "Gemma-4-E4B-it",
                freeRamBytes = 3_500_000_000L,
                atEpochMs = FIXED_AT_EPOCH_MS,
                outcome = Outcome.InProgress,
            ),
        )

        val viewModel = newViewModel()

        val text = viewModel.state.value.lastInitText
        assertTrue("expected 'инициализация' in: $text", text.contains("инициализация"))
        assertTrue("expected '3.2 ГБ' in: $text", text.contains("3.2 ГБ"))
        assertTrue("expected 'Gemma-4-E4B-it' in: $text", text.contains("Gemma-4-E4B-it"))
        assertTrue("expected HH:mm in: $text", text.contains(expectedHhmm()))
        assertFalse("must not contain 'ok' in: $text", text.contains("ok"))
        assertFalse("must not contain 'ошибка' in: $text", text.contains("ошибка"))
    }

    // ---------- free-RAM polling ----------
    //
    // These tests need the polling flow to actually run, so they launch a
    // collector and cancel it before the runTest block ends. The seed read
    // happens during construction; subsequent reads are driven by the flow's
    // `delay(1_000)` ticks under `advanceTimeBy`.

    @Test
    fun freeRam_tickEverySecond_refreshesValue() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        val collectJob = launch { viewModel.state.collect {} }
        runCurrent()
        // Constructor seed call (1) + first emission inside the flow (2).
        // `WhileSubscribed` resubscribes to the upstream flow when the first
        // collector arrives, and the flow's first `emit(snapshot())` happens
        // immediately, before the first delay.
        assertEquals(2, fakeDeviceInfo.availableMemoryBytesCallCount)

        advanceTimeBy(3_000)
        runCurrent()

        // 3 additional tick-driven reads.
        assertEquals(5, fakeDeviceInfo.availableMemoryBytesCallCount)
        collectJob.cancel()
    }

    @Test
    fun whenCollectorCancels_pollingStops() = runTest(testDispatcher) {
        val viewModel = newViewModel()
        val collectJob = launch { viewModel.state.collect {} }
        runCurrent()
        val baseline = fakeDeviceInfo.availableMemoryBytesCallCount

        collectJob.cancel()
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(
            "polling must stop after the last subscriber is cancelled",
            baseline,
            fakeDeviceInfo.availableMemoryBytesCallCount,
        )
    }

    // ---------- buildAndWrite (migrated from AboutViewModel) ----------
    //
    // No collector launched → flow idle → no polling interference.

    @Test
    fun buildAndWrite_success_returnsResultSuccess() = runTest(testDispatcher) {
        val viewModel = newViewModel()

        val result = viewModel.buildAndWrite(Uri.parse("content://fake/log.txt"))

        assertTrue("expected success, got: $result", result.isSuccess)
        assertEquals(1, fakeLogExporter.writeCallCount)
        assertEquals(ExportSource.About, fakeLogExporter.lastSource)
    }

    @Test
    fun buildAndWrite_ioException_returnsResultFailure() = runTest(testDispatcher) {
        fakeLogExporter.writeThrows = IOException("disk full")
        val viewModel = newViewModel()

        val result = viewModel.buildAndWrite(Uri.parse("content://fake/log.txt"))

        assertTrue("expected failure, got: $result", result.isFailure)
        assertTrue(
            "expected IOException, got: ${result.exceptionOrNull()}",
            result.exceptionOrNull() is IOException,
        )
    }

    private fun expectedHhmm(): String =
        OffsetDateTime
            .ofInstant(Instant.ofEpochMilli(FIXED_AT_EPOCH_MS), zone)
            .format(DateTimeFormatter.ofPattern("HH:mm"))

    private companion object {
        // April 2025 fixture — exact wall-clock varies by zone; tests check
        // substring presence using locally-rendered HH:mm.
        const val FIXED_AT_EPOCH_MS: Long = 1_745_000_000_000L
    }
}

// ---------- Hand-rolled fakes (no MockK / Mockito per patterns.md) ----------

private class FakeDiagnosticsState(initial: InitSnapshot?) : DiagnosticsState() {
    @Volatile private var snapshot: InitSnapshot? = initial
    fun set(s: InitSnapshot?) { snapshot = s }
    override fun lastInitSnapshot(): InitSnapshot? = snapshot
}

private class FakeDeviceInfoProvider(
    private val availableMemoryBytes: Long,
) : DeviceInfoProvider {
    var availableMemoryBytesCallCount: Int = 0
        private set

    override fun availableMemoryBytes(): Long {
        availableMemoryBytesCallCount += 1
        return availableMemoryBytes
    }

    // Unused by DiagnosticsViewModel; return stable defaults.
    override fun applicationId(): String = "test"
    override fun versionName(): String = "0.0.0"
    override fun versionCode(): Long = 0L
    override fun isDebug(): Boolean = true
    override fun manufacturer(): String = "test"
    override fun model(): String = "test"
    override fun androidRelease(): String = "0"
    override fun apiLevel(): Int = 33
    override fun totalMemoryBytes(): Long = 0L
    override fun thresholdMemoryBytes(): Long = 0L
    override fun isLowMemory(): Boolean = false
    override fun processJavaHeapBytes(): Long = 0L
    override fun processNativeHeapBytes(): Long = 0L
    override fun processTotalPssBytes(): Long = 0L
    override fun lastInitSnapshot(): InitSnapshot? = null
    override fun activeModelId(): String? = null
    override fun downloadedModels(): List<Pair<String, Long>> = emptyList()
    override fun nowIso(): String = "1970-01-01T00:00:00Z"
}

private class FakeLogExporter : LogExporter {
    var lastSource: ExportSource? = null
    var lastUri: Uri? = null
    var writeCallCount: Int = 0
        private set
    var writeThrows: IOException? = null

    override suspend fun buildExport(source: ExportSource): String {
        lastSource = source
        return "diagnostic-log-payload"
    }

    override suspend fun writeTo(uri: Uri, content: String) {
        lastUri = uri
        writeThrows?.let { throw it }
        writeCallCount += 1
    }
}
