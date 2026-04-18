package app.sanctum.machina.logexport

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LOG_DIR = "logs"
private const val CRASH_LOG = "crash.log"
private const val ERRORS_LOG = "errors.log"
private const val ERRORS_LOG_1 = "errors.log.1"

private const val MAX_CRASH_BYTES = 100 * 1024
private const val MAX_LOGCAT_BYTES = 100 * 1024

private const val CRASH_TRUNC_MARKER = "\n[truncated at 100 KB]"
private const val EMPTY_PLACEHOLDER = "[empty]"
private const val CRASH_REPORT_LOGCAT_PLACEHOLDER = "[logcat available only via About export]"

/** Target of an export run — differs only in how the `logcat` section is filled. */
enum class ExportSource { About, CrashReport }

/**
 * Assembles the exported `.txt` (header + `crash.log` + `errors.log` +
 * `errors.log.1` + `logcat`) as a single UTF-8 [String] on [Dispatchers.IO]
 * and writes it to a SAF-picked `content://` URI.
 *
 * Section caps per Decision 7: `crash.log` head-truncated to 100 KB (preserves
 * the exception type and top frames); `logcat` tail-truncated to 100 KB
 * (preserves the most recent lines); `errors.log` / `errors.log.1` already
 * bounded by `ErrorLog` rotation; header ≤ ~1 KB. Missing or empty sources
 * render as `[empty]` — except `errors.log.1`, which is omitted entirely when
 * absent (tech-spec "Data Models").
 *
 * Two construction paths:
 *
 *  * **Hilt-friendly primary** — `@Inject constructor(Context, DeviceInfoCollector,
 *    LogcatReader)`. Used in the main process, where Hilt resolves everything.
 *  * **Manual secondary** — `LogExportManager(Context)`. Used by the `:crash`
 *    process's `CrashReportActivity` (Decision 5), which runs without Hilt.
 *    Collaborators are constructed from the [Context] on the spot.
 *
 * See `work/phase-2.5-logexport/tech-spec.md` Decisions 1, 5, 7, 8, 11.
 */
@Singleton
class LogExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceInfo: DeviceInfoCollector,
    private val logcat: LogcatReader,
) {

    /**
     * Test seam for the SAF write path. Production code goes through
     * [android.content.ContentResolver.openOutputStream]; tests substitute a
     * lambda that returns `null` or a throwing stream to exercise
     * [writeTo]'s error branches without forcing Robolectric shadow internals.
     *
     * `internal` so only the logexport test classpath can read/write it; the
     * public surface of this class is unchanged.
     */
    internal var openOutputStreamForTest: (Uri) -> OutputStream? = { uri ->
        context.contentResolver.openOutputStream(uri)
    }

    constructor(context: Context) : this(
        context = context,
        deviceInfo = DeviceInfoCollector(AndroidDeviceInfoProvider(context)),
        logcat = LogcatReader(DefaultCommandRunner()),
    )

    suspend fun buildExport(source: ExportSource): String = withContext(Dispatchers.IO) {
        val logsDir = File(context.filesDir, LOG_DIR)
        buildString {
            append(deviceInfo.buildHeader())

            append("\n=== ").append(CRASH_LOG).append(" ===\n")
            append(renderCrashLog(File(logsDir, CRASH_LOG)))

            append("\n\n=== ").append(ERRORS_LOG).append(" ===\n")
            append(renderPlainFile(File(logsDir, ERRORS_LOG)))

            val errors1 = File(logsDir, ERRORS_LOG_1)
            if (errors1.exists()) {
                append("\n\n=== ").append(ERRORS_LOG_1).append(" ===\n")
                append(renderPlainFile(errors1))
            }

            append("\n\n=== logcat ===\n")
            append(renderLogcat(source))
        }
    }

    suspend fun writeTo(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        val stream = openOutputStreamForTest(uri)
            ?: throw IOException("openOutputStream returned null")
        stream.use { it.write(content.toByteArray(Charsets.UTF_8)) }
    }

    private fun renderCrashLog(file: File): String {
        if (!file.exists() || file.length() == 0L) return EMPTY_PLACEHOLDER
        val bytes = file.readBytes()
        return if (bytes.size <= MAX_CRASH_BYTES) {
            String(bytes, Charsets.UTF_8)
        } else {
            String(bytes, 0, MAX_CRASH_BYTES, Charsets.UTF_8) + CRASH_TRUNC_MARKER
        }
    }

    private fun renderPlainFile(file: File): String {
        if (!file.exists() || file.length() == 0L) return EMPTY_PLACEHOLDER
        return file.readText(Charsets.UTF_8)
    }

    private fun renderLogcat(source: ExportSource): String = when (source) {
        ExportSource.CrashReport -> CRASH_REPORT_LOGCAT_PLACEHOLDER
        ExportSource.About -> truncateLogcat(logcat.read())
    }

    private fun truncateLogcat(raw: String): String {
        val bytes = raw.toByteArray(Charsets.UTF_8)
        if (bytes.size <= MAX_LOGCAT_BYTES) return raw
        val head = bytes.size - MAX_LOGCAT_BYTES
        val tail = String(bytes, head, MAX_LOGCAT_BYTES, Charsets.UTF_8)
        return "[truncated: head $head bytes]\n$tail"
    }
}
