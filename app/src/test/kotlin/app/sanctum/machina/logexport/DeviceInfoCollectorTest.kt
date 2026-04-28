package app.sanctum.machina.logexport

import app.sanctum.machina.diagnostics.InitSnapshot
import app.sanctum.machina.diagnostics.Outcome
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DeviceInfoCollector]. Hand-rolled [DeviceInfoProvider] stub —
 * tests never hit Build/PackageInfo/ActivityManager directly, so no Robolectric
 * needed (keeps the suite fast and isolates header formatting).
 */
class DeviceInfoCollectorTest {

    @Test
    fun headerFormatting_deterministicFromStub() {
        val provider = StubDeviceInfoProvider(
            applicationId = "app.sanctum.machina",
            versionName = "0.1.0",
            versionCode = 1,
            isDebug = true,
            manufacturer = "Honor",
            model = "HONOR 200",
            androidRelease = "14",
            apiLevel = 34,
            totalMemoryBytes = 12_564_408_729L, // ~11.7 GiB
            availableMemoryBytes = 6_657_199_718L, // ~6.2 GiB
            thresholdMemoryBytes = 419_430_400L, // ~0.4 GiB
            isLowMemory = false,
            processJavaHeapBytes = 80_000_000L, // ~0.1 GiB
            processNativeHeapBytes = 220_000_000L, // ~0.2 GiB
            processTotalPssBytes = 380_000_000L, // ~0.4 GiB
            lastInitSnapshot = null, // pinned null keeps the blob zone-agnostic
            activeModelId = "litert-community/gemma-4-E2B-it-litert-lm",
            downloadedModels = listOf(
                "litert-community/gemma-4-E2B-it-litert-lm" to 3_328_599_818L // ~3.1 GiB
            ),
            nowIso = "2026-04-18T14:25:00+05:00",
        )

        val header = DeviceInfoCollector(provider).buildHeader()

        val expected = buildString {
            append("=== Sanctum Machina diagnostic log ===\n")
            append("exported: 2026-04-18T14:25:00+05:00\n")
            append("applicationId: app.sanctum.machina\n")
            append("version: 0.1.0 (1), debug=true\n")
            append("device: Honor / HONOR 200 / Android 14 (API 34)\n")
            append("memory: total=11.7 GB, available=6.2 GB, threshold=0.4 GB, lowMemory=false\n")
            append("process: javaHeap=76 MB, nativeHeap=209 MB, totalPss=362 MB\n")
            append("last init: пока не было\n")
            append("active model: litert-community/gemma-4-E2B-it-litert-lm\n")
            append("downloaded models:\n")
            append("  - litert-community/gemma-4-E2B-it-litert-lm (3.1 GB)\n")
        }
        assertEquals(expected, header)
    }

    @Test
    fun emptyDownloadedModels_rendersNonePlaceholder() {
        val provider = StubDeviceInfoProvider(
            activeModelId = null,
            downloadedModels = emptyList(),
        )
        val header = DeviceInfoCollector(provider).buildHeader()

        assertTrue(
            "header must end downloaded-models block with '  (none)', got:\n$header",
            header.contains("downloaded models:\n  (none)\n")
        )
        assertFalse(
            "header must NOT have a bare 'downloaded models:' with nothing following, got:\n$header",
            header.contains("downloaded models:\n\n")
        )
    }

    @Test
    fun nullActiveModelId_rendersNone() {
        val provider = StubDeviceInfoProvider(activeModelId = null)
        val header = DeviceInfoCollector(provider).buildHeader()

        assertTrue(
            "header must render 'active model: none' when activeModelId() is null, got:\n$header",
            header.contains("active model: none\n")
        )
    }

    @Test
    fun memoryLine_containsThresholdAndLowMemory() {
        val provider = StubDeviceInfoProvider(
            totalMemoryBytes = 12_564_408_729L,
            availableMemoryBytes = 6_657_199_718L,
            thresholdMemoryBytes = 419_430_400L,
            isLowMemory = true,
        )

        val line = memoryLine(DeviceInfoCollector(provider).buildHeader())

        assertTrue("memory line must contain threshold= field, got:\n$line",
            line.contains("threshold=0.4 GB"))
        assertTrue("memory line must contain lowMemory= field, got:\n$line",
            line.contains("lowMemory=true"))
    }

    @Test
    fun processLine_rendersThreeFields() {
        val provider = StubDeviceInfoProvider(
            processJavaHeapBytes = 80_000_000L,
            processNativeHeapBytes = 220_000_000L,
            processTotalPssBytes = 380_000_000L,
        )

        val line = processLine(DeviceInfoCollector(provider).buildHeader())

        assertEquals("process: javaHeap=76 MB, nativeHeap=209 MB, totalPss=362 MB", line)
    }

    @Test
    fun processLine_perFieldNaOnSourceError_java() {
        // Long.MIN_VALUE is the contract sentinel: provider returns it from any of the three
        // process getters when the underlying source threw; `buildHeader` renders that one
        // field as `n/a` while the surviving fields stay normal.
        val provider = StubDeviceInfoProvider(
            processJavaHeapBytes = Long.MIN_VALUE,
            processNativeHeapBytes = 220_000_000L,
            processTotalPssBytes = 380_000_000L,
        )

        val line = processLine(DeviceInfoCollector(provider).buildHeader())

        assertEquals("process: javaHeap=n/a, nativeHeap=209 MB, totalPss=362 MB", line)
    }

    @Test
    fun processLine_perFieldNaOnSourceError_native() {
        val provider = StubDeviceInfoProvider(
            processJavaHeapBytes = 80_000_000L,
            processNativeHeapBytes = Long.MIN_VALUE,
            processTotalPssBytes = 380_000_000L,
        )

        val line = processLine(DeviceInfoCollector(provider).buildHeader())

        assertEquals("process: javaHeap=76 MB, nativeHeap=n/a, totalPss=362 MB", line)
    }

    @Test
    fun processLine_perFieldNaOnSourceError_totalPss() {
        val provider = StubDeviceInfoProvider(
            processJavaHeapBytes = 80_000_000L,
            processNativeHeapBytes = 220_000_000L,
            processTotalPssBytes = Long.MIN_VALUE,
        )

        val line = processLine(DeviceInfoCollector(provider).buildHeader())

        assertEquals("process: javaHeap=76 MB, nativeHeap=209 MB, totalPss=n/a", line)
    }

    @Test
    fun lastInit_okBranch() {
        // Helper-direct with UTC keeps the assertion zone-agnostic and exact-equality strict.
        val rendered = formatLastInit(snapshot(Outcome.Ok), ZoneOffset.UTC)

        assertEquals(
            "3.2 ГБ RAM · ${HH_MM_AT_FIXTURE_UTC} · Gemma-4-E4B-it · ok",
            rendered,
        )
    }

    @Test
    fun lastInit_failedBranch() {
        val rendered = formatLastInit(snapshot(Outcome.Failed), ZoneOffset.UTC)

        assertEquals(
            "3.2 ГБ RAM · ${HH_MM_AT_FIXTURE_UTC} · Gemma-4-E4B-it · ошибка",
            rendered,
        )
    }

    @Test
    fun lastInit_nullSnapshot_rendersNeverHappened() {
        // Through buildHeader to also exercise the row prefix.
        val provider = StubDeviceInfoProvider(lastInitSnapshot = null)

        val line = lastInitLine(DeviceInfoCollector(provider).buildHeader())

        assertEquals("last init: пока не было", line)
    }

    @Test
    fun lastInit_inProgressBranch() {
        val rendered = formatLastInit(snapshot(Outcome.InProgress), ZoneOffset.UTC)

        assertEquals(
            "Идёт инициализация: 3.2 ГБ RAM · ${HH_MM_AT_FIXTURE_UTC} · Gemma-4-E4B-it",
            rendered,
        )
        // Negative-substring assertions also locked here so a future refactor can't slip
        // a stray "ok"/"ошибка" into the InProgress wording.
        assertFalse("InProgress branch must NOT contain ' · ok', got:\n$rendered",
            rendered.contains(" · ok"))
        assertFalse("InProgress branch must NOT contain 'ошибка', got:\n$rendered",
            rendered.contains("ошибка"))
    }

    @Test
    fun lastInit_modelNameWithLineBreaks_isFlattened() {
        // Defense-in-depth: a stray newline in the snapshot's modelName must not split the
        // header into a forged `last init:` line. Allowlist-guarded today, but the renderer
        // owns the flattening so the guard survives upstream regressions.
        val malicious = InitSnapshot(
            modelName = "evil\nlast init: forged · ok",
            freeRamBytes = 3_500_000_000L,
            atEpochMs = 1_745_000_000_000L,
            outcome = Outcome.Ok,
        )

        val rendered = formatLastInit(malicious, ZoneOffset.UTC)

        assertFalse("rendered line must not contain a raw newline, got:\n$rendered",
            rendered.contains('\n'))
        assertTrue("flattened modelName must still appear, got:\n$rendered",
            rendered.contains("evil last init: forged · ok · ok"))
    }

    @Test
    fun crashProcessDegradation_lastInitNullButMemoryAndProcessRender() {
        // AC-H4: in :crash process the secondary AndroidDeviceInfoProvider ctor passes
        // {null} for lastInitSnapshotProvider, but the five process/memory getters still
        // hit live system APIs. Header must show "пока не было" without losing the new
        // memory/process rows.
        val provider = StubDeviceInfoProvider(
            totalMemoryBytes = 12_564_408_729L,
            availableMemoryBytes = 6_657_199_718L,
            thresholdMemoryBytes = 419_430_400L,
            isLowMemory = false,
            processJavaHeapBytes = 80_000_000L,
            processNativeHeapBytes = 220_000_000L,
            processTotalPssBytes = 380_000_000L,
            lastInitSnapshot = null,
        )

        val header = DeviceInfoCollector(provider).buildHeader()

        assertEquals("last init: пока не было", lastInitLine(header))
        assertEquals(
            "memory: total=11.7 GB, available=6.2 GB, threshold=0.4 GB, lowMemory=false",
            memoryLine(header)
        )
        assertEquals(
            "process: javaHeap=76 MB, nativeHeap=209 MB, totalPss=362 MB",
            processLine(header)
        )
    }

    private fun memoryLine(header: String): String =
        lineStartingWith(header, "memory:")

    private fun processLine(header: String): String =
        lineStartingWith(header, "process:")

    private fun lastInitLine(header: String): String =
        lineStartingWith(header, "last init:")

    private fun lineStartingWith(header: String, prefix: String): String {
        val match = header.lineSequence().firstOrNull { it.startsWith(prefix) }
        assertNotNull("header must contain a line starting with '$prefix', got:\n$header", match)
        return match!!
    }

    private fun snapshot(outcome: Outcome): InitSnapshot = InitSnapshot(
        modelName = "Gemma-4-E4B-it",
        freeRamBytes = 3_500_000_000L, // floors to 3.2 ГБ
        atEpochMs = 1_745_000_000_000L, // April 2025; HH:mm shape is what matters
        outcome = outcome,
    )

    private class StubDeviceInfoProvider(
        private val applicationId: String = "app.sanctum.machina",
        private val versionName: String = "0.1.0",
        private val versionCode: Long = 1,
        private val isDebug: Boolean = true,
        private val manufacturer: String = "Honor",
        private val model: String = "HONOR 200",
        private val androidRelease: String = "14",
        private val apiLevel: Int = 34,
        private val totalMemoryBytes: Long = 12_000_000_000L,
        private val availableMemoryBytes: Long = 6_000_000_000L,
        private val thresholdMemoryBytes: Long = 419_430_400L,
        private val isLowMemory: Boolean = false,
        private val processJavaHeapBytes: Long = 80_000_000L,
        private val processNativeHeapBytes: Long = 220_000_000L,
        private val processTotalPssBytes: Long = 380_000_000L,
        private val lastInitSnapshot: InitSnapshot? = null,
        private val activeModelId: String? = null,
        private val downloadedModels: List<Pair<String, Long>> = emptyList(),
        private val nowIso: String = "2026-04-18T14:25:00+05:00",
    ) : DeviceInfoProvider {
        override fun applicationId() = applicationId
        override fun versionName() = versionName
        override fun versionCode() = versionCode
        override fun isDebug() = isDebug
        override fun manufacturer() = manufacturer
        override fun model() = model
        override fun androidRelease() = androidRelease
        override fun apiLevel() = apiLevel
        override fun totalMemoryBytes() = totalMemoryBytes
        override fun availableMemoryBytes() = availableMemoryBytes
        override fun thresholdMemoryBytes() = thresholdMemoryBytes
        override fun isLowMemory() = isLowMemory
        override fun processJavaHeapBytes() = processJavaHeapBytes
        override fun processNativeHeapBytes() = processNativeHeapBytes
        override fun processTotalPssBytes() = processTotalPssBytes
        override fun lastInitSnapshot() = lastInitSnapshot
        override fun activeModelId() = activeModelId
        override fun downloadedModels() = downloadedModels
        override fun nowIso() = nowIso
    }

    private companion object {
        // 1_745_000_000_000 ms = 2025-04-18T18:13:20Z → HH:mm in UTC = "18:13".
        private const val HH_MM_AT_FIXTURE_UTC = "18:13"
    }
}
