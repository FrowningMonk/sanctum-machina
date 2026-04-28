package app.sanctum.machina.logexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [LogcatReader]. Exercises the placeholder-vs-stdout contract
 * through a hand-rolled [CommandRunner] fake — no Mockito/MockK per task rules.
 *
 * Robolectric-driven because [LogcatReader.read] calls `android.os.Process.myPid()`
 * when building argv (unavailable on the plain JVM test classpath).
 *
 * The argv-shape test (`argvShape_exactlySixArgs_knownPositions`) is a guardrail
 * for Decision 8 + OWASP A03: `ProcessBuilder` must receive exactly the fixed
 * six arguments with no shell wrapping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LogcatReaderTest {

    @Test
    fun emptyStdout_returnsUnavailableEmpty() {
        val runner = StubCommandRunner(
            result = LogcatResult(stdout = ByteArray(0), exitCode = 0, timedOut = false)
        )
        val reader = LogcatReader(runner)

        assertEquals("[logcat unavailable: empty]", reader.read())
    }

    @Test
    fun nonZeroExit_returnsUnavailableExitN() {
        val runner = StubCommandRunner(
            result = LogcatResult(stdout = "ignored".toByteArray(), exitCode = 13, timedOut = false)
        )
        val reader = LogcatReader(runner)

        assertEquals("[logcat unavailable: exit=13]", reader.read())
    }

    @Test
    fun nullExitNoTimeout_returnsUnavailableUnknown() {
        // Branch reached when DefaultCommandRunner catches IOException/SecurityException
        // from ProcessBuilder.start (OEM paranoid mode without a logcat binary on PATH)
        // and returns exitCode=null, timedOut=false. Locks the placeholder string.
        val runner = StubCommandRunner(
            result = LogcatResult(stdout = ByteArray(0), exitCode = null, timedOut = false)
        )
        val reader = LogcatReader(runner)

        assertEquals("[logcat unavailable: unknown]", reader.read())
    }

    @Test
    fun timeout_returnsUnavailableTimeout() {
        val runner = StubCommandRunner(
            result = LogcatResult(stdout = "partial".toByteArray(), exitCode = null, timedOut = true)
        )
        val reader = LogcatReader(runner)

        assertEquals("[logcat unavailable: timeout]", reader.read())
    }

    @Test
    fun happyPath_returnsStdoutVerbatim() {
        val payload = "line 1\nline 2\nline 3\n"
        val runner = StubCommandRunner(
            result = LogcatResult(
                stdout = payload.toByteArray(Charsets.UTF_8),
                exitCode = 0,
                timedOut = false
            )
        )
        val reader = LogcatReader(runner)

        assertEquals(payload, reader.read())
    }

    @Test
    fun argvShape_exactlySixArgs_knownPositions() {
        val runner = RecordingCommandRunner()
        val reader = LogcatReader(runner)

        reader.read()

        val argv = runner.lastArgv
            ?: error("runner was not invoked — LogcatReader.read() must call CommandRunner.run(...)")

        assertEquals("argv must have exactly 6 elements, got $argv", 6, argv.size)
        assertEquals("logcat", argv[0])
        assertEquals("-d", argv[1])
        assertEquals("-v", argv[2])
        assertEquals("threadtime", argv[3])
        assertTrue(
            "argv[4] must match ^--pid=\\d+$, got '${argv[4]}'",
            argv[4].matches(Regex("^--pid=\\d+$"))
        )
        assertEquals("*:W", argv[5])
    }

    private class StubCommandRunner(private val result: LogcatResult) : CommandRunner {
        override fun run(argv: List<String>, timeoutMs: Long): LogcatResult = result
    }

    private class RecordingCommandRunner : CommandRunner {
        var lastArgv: List<String>? = null
        override fun run(argv: List<String>, timeoutMs: Long): LogcatResult {
            lastArgv = argv
            return LogcatResult(stdout = "x".toByteArray(), exitCode = 0, timedOut = false)
        }
    }
}
