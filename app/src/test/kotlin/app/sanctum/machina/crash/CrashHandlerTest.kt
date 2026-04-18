package app.sanctum.machina.crash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CrashHandlerTest {

    private lateinit var context: Context
    private lateinit var logsDir: File
    private lateinit var crashLog: File
    private lateinit var dismissedFlag: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        logsDir = File(context.filesDir, "logs")
        crashLog = File(logsDir, "crash.log")
        dismissedFlag = File(logsDir, "crash.log.dismissed")
        logsDir.deleteRecursively()
    }

    @After
    fun tearDown() {
        logsDir.deleteRecursively()
    }

    @Test
    fun writesCrashLogBeforeKiller() {
        val killer = RecordingKiller(crashLog)
        val handler = CrashHandler(context, killer)

        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals("killer must be called exactly once", 1, killer.callCount)
        assertNotNull("crash.log must exist before kill()", killer.observedBytes)
        val observed = String(killer.observedBytes!!, Charsets.UTF_8)
        assertTrue("observed bytes must contain RuntimeException, got:\n$observed",
            observed.contains("RuntimeException"))
        assertTrue("observed bytes must contain exception message, got:\n$observed",
            observed.contains("boom"))
    }

    @Test
    fun deletesDismissedFlagOnNewCrash() {
        logsDir.mkdirs()
        dismissedFlag.createNewFile()
        assertTrue("seed: dismissed flag must exist", dismissedFlag.exists())

        val killer = RecordingKiller(crashLog)
        val handler = CrashHandler(context, killer)
        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertFalse("dismissed flag must be deleted by new crash", dismissedFlag.exists())
    }

    @Test
    fun repeatedCrashes_overwriteNotAppend() {
        val killer = RecordingKiller(crashLog)
        val handler = CrashHandler(context, killer)

        handler.uncaughtException(Thread.currentThread(), RuntimeException("first-boom"))
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("second-boom"))

        val content = crashLog.readText(Charsets.UTF_8)
        assertTrue("must contain second exception type, got:\n$content",
            content.contains("IllegalStateException"))
        assertTrue("must contain second message, got:\n$content",
            content.contains("second-boom"))
        assertFalse("must NOT contain first exception type (overwrite, not append), got:\n$content",
            content.contains("first-boom"))
    }

    @Test
    fun handlerInternalFailure_killsOnce() {
        val killer = RecordingKiller(crashLog)
        val throwingWriter: (File, String) -> Unit = { _, _ -> throw IOException("test-failure") }
        val handler = CrashHandler(context, killer, crashLogWriter = throwingWriter)

        handler.uncaughtException(Thread.currentThread(), RuntimeException("boom"))

        assertEquals("killer must be invoked exactly once even when inner writer throws",
            1, killer.callCount)
    }

    @Test
    fun stacktraceOver100KB_truncationMarkerAtEnd() {
        val bigFrames = Array(20000) { i ->
            StackTraceElement(
                "very.long.package.name.ClassName$i",
                "someReasonablyLongMethodName",
                "FileName.kt",
                i
            )
        }
        val ex = RuntimeException("boom").apply { stackTrace = bigFrames }

        val killer = RecordingKiller(crashLog)
        val handler = CrashHandler(context, killer)
        handler.uncaughtException(Thread.currentThread(), ex)

        val bytes = crashLog.readBytes()
        val maxBytes = 100 * 1024
        val markerOverhead = 64
        assertTrue(
            "file size ${bytes.size} must not exceed $maxBytes + marker overhead",
            bytes.size <= maxBytes + markerOverhead
        )
        assertTrue(
            "file size ${bytes.size} must be near the cap (truncation fired)",
            bytes.size >= maxBytes - 1024
        )

        val content = crashLog.readText(Charsets.UTF_8)
        assertTrue(
            "must contain truncation marker, got tail:\n${content.takeLast(200)}",
            content.contains("[truncated at 100 KB]")
        )
        assertTrue(
            "head must be preserved (starts with record header), got head:\n${content.take(200)}",
            content.startsWith("=== Sanctum Machina crash record ===")
        )
        assertTrue(
            "head must contain exception type near the top",
            content.take(500).contains("RuntimeException")
        )
    }

    @Test
    fun handlerShape_noSuspendCallsNoCoroutineImports() {
        val methods = CrashHandler::class.java.declaredMethods
        for (m in methods) {
            for (p in m.parameterTypes) {
                assertFalse(
                    "method ${m.name} has Continuation parameter — suspend detected",
                    p.name == "kotlin.coroutines.Continuation"
                )
            }
        }
    }

    @Test
    fun nullMessageException_doesNotCrash() {
        val killer = RecordingKiller(crashLog)
        val handler = CrashHandler(context, killer)
        val ex = RuntimeException()

        handler.uncaughtException(Thread.currentThread(), ex)

        assertEquals(1, killer.callCount)
        assertTrue("crash.log must exist after null-message exception", crashLog.exists())
    }

    private class RecordingKiller(private val crashLog: File) : Killer {
        var callCount: Int = 0
        var observedBytes: ByteArray? = null

        override fun kill(pid: Int) {
            callCount++
            if (crashLog.exists()) {
                observedBytes = crashLog.readBytes()
            }
        }
    }
}
