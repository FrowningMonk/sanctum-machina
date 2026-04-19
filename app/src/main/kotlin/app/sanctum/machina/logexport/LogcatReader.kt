package app.sanctum.machina.logexport

import android.os.Process
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

private const val LOGCAT_TIMEOUT_MS = 2_000L

/**
 * Reads the current process's own logcat at ERROR level and returns it as a
 * UTF-8 string, or a `[logcat unavailable: <reason>]` placeholder when the
 * underlying `logcat` command fails or is unavailable.
 *
 * argv is a fixed six-element list — the sole variable is the own-pid
 * integer — so `ProcessBuilder` can never be confused by shell metacharacters.
 * Tail-truncation of large outputs is the caller's responsibility
 * ([LogExportManager]); this class returns the full captured bytes.
 *
 * The [runner] seam makes the placeholder logic unit-testable without spawning
 * a real process.
 */
class LogcatReader @Inject constructor(
    private val runner: CommandRunner,
) {

    constructor() : this(DefaultCommandRunner())

    fun read(): String {
        val argv = listOf(
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "--pid=${Process.myPid()}",
            "*:E",
        )
        val result = runner.run(argv, LOGCAT_TIMEOUT_MS)
        return when {
            result.timedOut -> "[logcat unavailable: timeout]"
            result.exitCode == null -> "[logcat unavailable: unknown]"
            result.exitCode != 0 -> "[logcat unavailable: exit=${result.exitCode}]"
            result.stdout.isEmpty() -> "[logcat unavailable: empty]"
            else -> String(result.stdout, Charsets.UTF_8)
        }
    }
}

/**
 * Outcome of running a subprocess — exposed so fakes can drive edge cases
 * ([LogcatReaderTest]). [exitCode] is `null` exactly when the process was
 * destroyed due to timeout or failed to start.
 */
data class LogcatResult(
    val stdout: ByteArray,
    val exitCode: Int?,
    val timedOut: Boolean,
)

/**
 * Narrow seam over `ProcessBuilder` so tests can inject deterministic results
 * without spawning real processes. Implementations must be side-effect-free
 * apart from the subprocess call itself.
 */
interface CommandRunner {
    fun run(argv: List<String>, timeoutMs: Long): LogcatResult
}

/**
 * Production [CommandRunner]. Spawns the child process with
 * `redirectErrorStream(true)` and drains stdout on a worker thread concurrently
 * with [Process.waitFor] to avoid pipe-buffer deadlock when the child produces
 * more than the OS pipe buffer (~64 KB) before the timeout fires.
 *
 * Any [IOException] from `ProcessBuilder.start()` (e.g., OEM paranoid mode
 * without `logcat` in the PATH) is translated into an empty-stdout, non-error
 * result so the caller renders `[logcat unavailable: empty]` — never throws
 * into the export pipeline.
 */
class DefaultCommandRunner @Inject constructor() : CommandRunner {

    override fun run(argv: List<String>, timeoutMs: Long): LogcatResult {
        val process = try {
            ProcessBuilder(argv).redirectErrorStream(true).start()
        } catch (_: IOException) {
            return LogcatResult(stdout = ByteArray(0), exitCode = null, timedOut = false)
        } catch (_: SecurityException) {
            return LogcatResult(stdout = ByteArray(0), exitCode = null, timedOut = false)
        }

        val buffer = ByteArrayOutputStream()
        val drainer = Thread({
            try {
                process.inputStream.use { input ->
                    val chunk = ByteArray(4096)
                    while (true) {
                        val n = input.read(chunk)
                        if (n == -1) break
                        buffer.write(chunk, 0, n)
                    }
                }
            } catch (_: IOException) {
                // stream closed when process is destroyed on timeout — expected.
            }
        }, "logcat-drainer")
        drainer.isDaemon = true
        drainer.start()

        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroy()
            drainer.join(500)
            return LogcatResult(
                stdout = buffer.toByteArray(),
                exitCode = null,
                timedOut = true,
            )
        }
        drainer.join(500)
        return LogcatResult(
            stdout = buffer.toByteArray(),
            exitCode = process.exitValue(),
            timedOut = false,
        )
    }
}
