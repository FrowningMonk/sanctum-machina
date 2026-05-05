package app.sanctum.machina.logexport

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogExportManagerTest {

    private lateinit var context: Context
    private lateinit var logsDir: File
    private lateinit var crashLog: File
    private lateinit var errorsLog: File
    private lateinit var errorsLog1: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        logsDir = File(context.filesDir, "logs")
        crashLog = File(logsDir, "crash.log")
        errorsLog = File(logsDir, "errors.log")
        errorsLog1 = File(logsDir, "errors.log.1")
        logsDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        logsDir.deleteRecursively()
    }

    // ---- buildExport ----------------------------------------------------------

    @Test
    fun headerContainsRequiredFields() = runTest {
        val manager = manager()

        val out = manager.buildExport(ExportSource.About)

        for (field in listOf(
            "applicationId:",
            "version:",
            "device:",
            "memory:",
            "process:",
            "last init:",
            "active model:",
            "downloaded models:",
        )) {
            assertTrue("header must contain '$field', got:\n$out", out.contains(field))
        }
    }

    @Test
    fun allSectionsPresent_orderedCorrectly() = runTest {
        seedFile(crashLog, "CRASH-MARKER-αβγ")
        seedFile(errorsLog, "ERRORS-MARKER-δεζ")
        seedFile(errorsLog1, "ERRORS1-MARKER-ηθι")
        val manager = manager(logcatStdout = "LOGCAT-MARKER-κλμ")

        val out = manager.buildExport(ExportSource.About)

        val crashIdx = out.indexOf("=== crash.log ===")
        val errorsIdx = out.indexOf("=== errors.log ===")
        val errors1Idx = out.indexOf("=== errors.log.1 ===")
        val logcatIdx = out.indexOf("=== logcat ===")
        assertTrue("crash divider missing", crashIdx >= 0)
        assertTrue("errors divider missing", errorsIdx > crashIdx)
        assertTrue("errors.1 divider missing", errors1Idx > errorsIdx)
        assertTrue("logcat divider missing", logcatIdx > errors1Idx)

        val crashSection = out.substring(crashIdx, errorsIdx)
        val errorsSection = out.substring(errorsIdx, errors1Idx)
        val errors1Section = out.substring(errors1Idx, logcatIdx)
        val logcatSection = out.substring(logcatIdx)
        assertTrue("crash marker not in crash section:\n$crashSection",
            crashSection.contains("CRASH-MARKER-αβγ"))
        assertTrue("errors marker not in errors section:\n$errorsSection",
            errorsSection.contains("ERRORS-MARKER-δεζ"))
        assertTrue("errors.1 marker not in errors.1 section:\n$errors1Section",
            errors1Section.contains("ERRORS1-MARKER-ηθι"))
        assertTrue("logcat marker not in logcat section:\n$logcatSection",
            logcatSection.contains("LOGCAT-MARKER-κλμ"))
    }

    @Test
    fun missingErrorsLog_rendersEmpty() = runTest {
        seedFile(crashLog, "stacktrace")
        val manager = manager()

        val out = manager.buildExport(ExportSource.About)

        val section = sectionBetween(out, "=== errors.log ===", "=== errors.log.1 ===")
            ?: sectionBetween(out, "=== errors.log ===", "=== logcat ===")
            ?: fail("errors.log section missing: $out").let { "" as String }
        assertTrue("errors.log section must render [empty], got:\n$section",
            section.contains("[empty]"))
    }

    @Test
    fun missingCrashLog_rendersEmpty() = runTest {
        seedFile(errorsLog, "line")
        val manager = manager()

        val out = manager.buildExport(ExportSource.About)

        val section = sectionBetween(out, "=== crash.log ===", "=== errors.log ===")
            ?: fail("crash.log section missing: $out").let { "" as String }
        assertTrue("crash.log must render [empty], got:\n$section",
            section.contains("[empty]"))
    }

    @Test
    fun crashReportSource_logcatPlaceholder() = runTest {
        val manager = manager(logcatStdout = null, logcatThrowsIfCalled = true)

        val out = manager.buildExport(ExportSource.CrashReport)

        val logcatSection = out.substringAfter("=== logcat ===")
        assertTrue(
            "CrashReport logcat must contain exact placeholder, got:\n$logcatSection",
            logcatSection.contains("[logcat available only via About export]")
        )
    }

    @Test
    fun aboutSource_logcatPopulatedFromRunner() = runTest {
        val manager = manager(logcatStdout = "sentinel-logcat-content")

        val out = manager.buildExport(ExportSource.About)

        val logcatSection = out.substringAfter("=== logcat ===")
        assertTrue(
            "About logcat must contain runner output, got:\n$logcatSection",
            logcatSection.contains("sentinel-logcat-content")
        )
    }

    @Test
    fun logcatTailTruncation_preservesTailAndMarks() = runTest {
        val tailMarker = "UNIQUE-TAIL-MARKER-2718\n"
        val huge = "X".repeat(200 * 1024) + tailMarker
        val manager = manager(logcatStdout = huge)

        val out = manager.buildExport(ExportSource.About)

        val logcatSection = out.substringAfter("=== logcat ===").trimStart('\n')
        assertTrue("tail marker must be preserved, got last 200 chars:\n${logcatSection.takeLast(200)}",
            logcatSection.contains(tailMarker))
        assertTrue("truncation marker must be at the head of the section, got first 200 chars:\n${logcatSection.take(200)}",
            logcatSection.take(200).contains("[truncated: head") && logcatSection.take(200).contains("bytes]"))
        val sectionBytes = logcatSection.toByteArray(Charsets.UTF_8).size
        assertTrue("section bytes ${sectionBytes} must be within 100KB + marker overhead",
            sectionBytes <= 100 * 1024 + 128)
    }

    @Test
    fun crashLogHeadTruncation_preservesHeadAndMarks() = runTest {
        val headMarker = "UNIQUE-HEAD-MARKER-3141\n"
        val huge = headMarker + "Y".repeat(200 * 1024)
        seedFile(crashLog, huge)
        val manager = manager()

        val out = manager.buildExport(ExportSource.About)

        val crashSection = sectionBetween(out, "=== crash.log ===", "=== errors.log ===")
            ?: fail("crash section missing: $out").let { "" as String }
        assertTrue("head marker must be preserved, got first 200 chars:\n${crashSection.take(200)}",
            crashSection.contains(headMarker))
        val crashContent = crashSection.removePrefix("=== crash.log ===").trim()
        assertTrue(
            "truncation marker must be anchored at the END of the crash section, got tail:\n${crashContent.takeLast(64)}",
            crashContent.endsWith("[truncated at 100 KB]")
        )
        val contentOnly = crashSection.removePrefix("=== crash.log ===").trim()
        val contentBytes = contentOnly.toByteArray(Charsets.UTF_8).size
        assertTrue("crash section content ${contentBytes} must be within 100KB + marker overhead",
            contentBytes <= 100 * 1024 + 128)
    }

    @Test
    fun errorsLog1Absent_sectionOmitted() = runTest {
        seedFile(crashLog, "stacktrace")
        seedFile(errorsLog, "line")
        val manager = manager()

        val out = manager.buildExport(ExportSource.About)

        assertFalse("errors.log.1 divider must NOT appear when file absent, got:\n$out",
            out.contains("=== errors.log.1 ==="))
    }

    @Test
    fun freshInstall_noLogsDir_succeeds() = runTest {
        assertFalse("precondition: logs dir must not exist", logsDir.exists())
        val manager = manager()

        val out = manager.buildExport(ExportSource.About)

        assertTrue("must contain crash divider", out.contains("=== crash.log ==="))
        assertTrue("must contain errors divider", out.contains("=== errors.log ==="))
        assertTrue("must contain logcat divider", out.contains("=== logcat ==="))
        assertTrue("crash section must be [empty]",
            sectionBetween(out, "=== crash.log ===", "=== errors.log ===")!!.contains("[empty]"))
        assertTrue("errors section must be [empty]",
            sectionBetween(out, "=== errors.log ===", "=== logcat ===")!!.contains("[empty]"))
    }

    @Test
    fun emptyDownloadedModels_rendersBlankList() = runTest {
        val manager = manager(
            deviceInfoProvider = stubProvider(
                activeModelId = null,
                downloadedModels = emptyList(),
            )
        )

        val out = manager.buildExport(ExportSource.About)

        assertTrue("header must show '(none)' for empty downloaded models, got:\n$out",
            out.contains("downloaded models:\n  (none)"))
    }

    // ---- writeTo --------------------------------------------------------------

    @Test
    fun writeTo_happyPath_writesContent() = runTest {
        val sink = ByteArrayOutputStream()
        val manager = manager(outputStreamOpener = { sink })
        val uri = Uri.parse("content://test/happy")

        manager.writeTo(uri, "hello-world")

        assertEquals("hello-world", sink.toString(Charsets.UTF_8))
    }

    @Test
    fun writeTo_ioException_surfaces() = runTest {
        val manager = manager(outputStreamOpener = { ThrowingOutputStream() })
        val uri = Uri.parse("content://test/throws")

        try {
            manager.writeTo(uri, "content")
            fail("expected IOException to surface")
        } catch (e: IOException) {
            assertEquals("forced write failure", e.message)
        }
    }

    @Test
    fun writeTo_nullOutputStream_surfacesAsIoException() = runTest {
        val manager = manager(outputStreamOpener = { null })
        val uri = Uri.parse("content://test/null")

        try {
            manager.writeTo(uri, "content")
            fail("expected IOException to surface")
        } catch (e: IOException) {
            assertEquals("openOutputStream returned null", e.message)
        }
    }

    // ---- non-Hilt construction ------------------------------------------------

    @Test
    fun nonHiltConstruction_instantiatesWithoutInjection() = runTest {
        val realContext = ApplicationProvider.getApplicationContext<Context>()
        val manager = LogExportManager(realContext)

        val out = manager.buildExport(ExportSource.CrashReport)

        assertNotNull(out)
        assertTrue("output must be non-empty", out.isNotEmpty())
        assertTrue("output must contain crash divider, got:\n$out",
            out.contains("=== crash.log ==="))
        assertTrue("output must contain the CrashReport logcat placeholder, got:\n$out",
            out.contains("[logcat available only via About export]"))
    }

    // ---- helpers --------------------------------------------------------------

    private fun seedFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun sectionBetween(text: String, from: String, to: String): String? {
        val start = text.indexOf(from)
        val end = text.indexOf(to, startIndex = start + from.length)
        if (start < 0 || end < 0) return null
        return text.substring(start, end)
    }

    private fun manager(
        deviceInfoProvider: DeviceInfoProvider = stubProvider(),
        logcatStdout: String? = null,
        logcatThrowsIfCalled: Boolean = false,
        outputStreamOpener: ((Uri) -> OutputStream?)? = null,
    ): LogExportManager {
        val runner = if (logcatThrowsIfCalled) {
            ThrowingCommandRunner()
        } else {
            StubCommandRunner(
                LogcatResult(
                    stdout = (logcatStdout ?: "").toByteArray(Charsets.UTF_8),
                    exitCode = 0,
                    timedOut = false,
                )
            )
        }
        val manager = LogExportManager(
            context = context,
            deviceInfo = DeviceInfoCollector(deviceInfoProvider),
            logcat = LogcatReader(runner),
        )
        if (outputStreamOpener != null) {
            manager.openOutputStreamForTest = outputStreamOpener
        }
        return manager
    }

    private fun stubProvider(
        activeModelId: String? = "litert-community/gemma-4-E2B-it-litert-lm",
        downloadedModels: List<Pair<String, Long>> = listOf(
            "litert-community/gemma-4-E2B-it-litert-lm" to 3_328_599_818L
        ),
    ): DeviceInfoProvider = object : DeviceInfoProvider {
        override fun applicationId() = "app.sanctum.machina"
        override fun versionName() = "0.1.0"
        override fun versionCode() = 1L
        override fun isDebug() = true
        override fun manufacturer() = "Honor"
        override fun model() = "HONOR 200"
        override fun androidRelease() = "14"
        override fun apiLevel() = 34
        override fun totalMemoryBytes() = 12_564_408_729L
        override fun availableMemoryBytes() = 6_657_199_718L
        override fun thresholdMemoryBytes() = 419_430_400L
        override fun isLowMemory() = false
        override fun processJavaHeapBytes() = 80_000_000L
        override fun processNativeHeapBytes() = 220_000_000L
        override fun processTotalPssBytes() = 380_000_000L
        override fun lastInitSnapshot() = null
        override fun activeModelId() = activeModelId
        override fun downloadedModels() = downloadedModels
        override fun nowIso() = "2026-04-18T14:25:00+05:00"
    }

    private class StubCommandRunner(private val result: LogcatResult) : CommandRunner {
        override fun run(argv: List<String>, timeoutMs: Long): LogcatResult = result
    }

    private class ThrowingCommandRunner : CommandRunner {
        override fun run(argv: List<String>, timeoutMs: Long): LogcatResult {
            throw AssertionError("CommandRunner must not be called for ExportSource.CrashReport")
        }
    }

    private class ThrowingOutputStream : OutputStream() {
        override fun write(b: Int) {
            throw IOException("forced write failure")
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            throw IOException("forced write failure")
        }
    }
}
