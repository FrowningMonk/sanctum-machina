package app.sanctum.machina.core.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_FIELD_LEN = 500
private const val MAX_LOG_BYTES: Long = 2L * 1024 * 1024
private const val LOG_DIR = "logs"
private const val LOG_FILE = "errors.log"
private const val ROTATED_FILE = "errors.log.1"

private val CONTROL_WS = Regex("[\\n\\r\\t]")

/**
 * On-device ERROR-only writer. Single channel for failure events in Phase 1.
 *
 * Components (Phase 1 whitelist, user-spec D11):
 * - `download`
 * - `inference-init`
 * - `inference`
 * - `inference-cleanup`
 *
 * Whitelist is not enforced in code (the [component] string is written as-is); deviations
 * are caught by code-reviewer in downstream tasks.
 *
 * Format (one physical line per event, no newlines, no emoji, no dividers):
 * ```
 * ERROR [component] description :: cause.message
 * ```
 * When [cause] is null the ` :: ...` suffix is omitted. `description` and `cause.message`
 * are sanitized: control whitespace (`\n`, `\r`, `\t`) becomes a space, then each field
 * is truncated to [MAX_FIELD_LEN] characters.
 *
 * Rotation: after each append, if the file exceeds [MAX_LOG_BYTES] (2 MB), any existing
 * `errors.log.1` is deleted and the current `errors.log` is renamed to `errors.log.1`.
 *
 * See `.claude/skills/project-knowledge/references/patterns.md § Error logging conventions`.
 */
@Singleton
class ErrorLog @Inject constructor(@ApplicationContext private val context: Context) {

  private val mutex = Mutex()

  suspend fun e(component: String, description: String, cause: Throwable? = null) {
    mutex.withLock {
      withContext(Dispatchers.IO) {
        runCatching {
          val line = buildLine(component, description, cause)
          val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
          val file = File(dir, LOG_FILE)
          file.appendText(line + "\n", Charsets.UTF_8)
          if (file.length() > MAX_LOG_BYTES) {
            val rotated = File(dir, ROTATED_FILE)
            if (rotated.exists()) rotated.delete()
            file.renameTo(rotated)
          }
        }
      }
    }
  }

  private fun buildLine(component: String, description: String, cause: Throwable?): String {
    val head = "ERROR [$component] ${sanitize(description)}"
    if (cause == null) return head
    val causeMsg = cause.message ?: cause::class.simpleName.orEmpty()
    return "$head :: ${sanitize(causeMsg)}"
  }

  private fun sanitize(raw: String): String =
    raw.replace(CONTROL_WS, " ").take(MAX_FIELD_LEN)
}
