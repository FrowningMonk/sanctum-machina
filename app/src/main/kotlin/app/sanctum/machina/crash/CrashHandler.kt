package app.sanctum.machina.crash

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private const val LOG_DIR = "logs"
private const val CRASH_LOG = "crash.log"
private const val DISMISSED_FLAG = "crash.log.dismissed"
private const val MAX_CRASH_BYTES = 100 * 1024
private const val TRUNC_MARKER = "\n[truncated at 100 KB]\n"
private const val TAG = "CrashHandler"
private const val CRASH_REPORT_ACTIVITY_FQN = "app.sanctum.machina.crash.CrashReportActivity"

private val DEFAULT_CRASH_LOG_WRITER: (File, String) -> Unit = { file, content ->
    file.writeText(content, Charsets.UTF_8)
}

/**
 * Global `Thread.UncaughtExceptionHandler` for the main process.
 *
 * On an uncaught exception: writes [CRASH_LOG] (overwrite, head-truncated to
 * 100 KB), deletes [DISMISSED_FLAG] if present, launches `CrashReportActivity`
 * in the `:crash` OS process, then kills the current process via the injected
 * [Killer] seam. An outer `try { ... } catch (Throwable)` short-circuits on any
 * internal failure — a single `android.util.Log.e` breadcrumb plus one
 * [Killer.kill] call — to prevent a bootstrap loop.
 *
 * Installed from `SanctumApplication.onCreate` (Task 5) behind a
 * `getProcessName() == packageName` guard so it never runs in the `:crash`
 * process. The class intentionally has no `suspend` functions and no coroutine
 * imports — enforced by `CrashHandlerTest`.
 *
 * See `work/phase-2.5-logexport/tech-spec.md` Decisions 4, 5, 6, 7, 10.
 */
class CrashHandler(
    private val context: Context,
    private val killer: Killer = Killer.Default,
    internal val crashLogWriter: (File, String) -> Unit = DEFAULT_CRASH_LOG_WRITER,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val logsDir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
            val dismissedFlag = File(logsDir, DISMISSED_FLAG)
            if (dismissedFlag.exists()) {
                dismissedFlag.delete()
            }

            val record = buildCrashRecord(thread, throwable)
            val truncated = headTruncate(record, MAX_CRASH_BYTES)

            val crashLog = File(logsDir, CRASH_LOG)
            crashLogWriter(crashLog, truncated)

            // TODO(Task 4): switch to CrashReportActivity::class.java once the class lands.
            val intent = Intent().apply {
                setClassName(context.packageName, CRASH_REPORT_ACTIVITY_FQN)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            context.startActivity(intent)

            killer.kill(Process.myPid())
        } catch (outer: Throwable) {
            Log.e(TAG, "handler failed", outer)
            killer.kill(Process.myPid())
        }
    }

    private fun buildCrashRecord(thread: Thread, throwable: Throwable): String {
        val timestamp = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val typeName = throwable.javaClass.name
        val message = throwable.message.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
        }.toString()
        return buildString {
            append("=== Sanctum Machina crash record ===\n")
            append("timestamp: ").append(timestamp).append('\n')
            append("thread: ").append(thread.name).append('\n')
            append("ExceptionType: ").append(typeName).append('\n')
            append("message: ").append(message).append('\n')
            append('\n')
            append(stack)
        }
    }

    private fun headTruncate(text: String, limitBytes: Int): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limitBytes) return text
        val head = String(bytes, 0, limitBytes, Charsets.UTF_8)
        return head + TRUNC_MARKER
    }
}
