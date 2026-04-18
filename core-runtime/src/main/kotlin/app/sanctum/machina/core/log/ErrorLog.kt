package app.sanctum.machina.core.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_DESCRIPTION_LEN = 500
private const val MAX_CAUSE_MESSAGE_LEN = 200
private const val MAX_LOG_BYTES: Long = 2L * 1024 * 1024
private const val LOG_DIR = "logs"
private const val LOG_FILE = "errors.log"
private const val ROTATED_FILE = "errors.log.1"

private val CONTROL_WS = Regex("[\\n\\r\\t]")

/**
 * Closed whitelist of components allowed as the first argument to [ErrorLog.e].
 * Phase 2 extends the Phase-1 set with four new failure modes
 * (`settings-io`, `camera`, `audio`, `attachment-decode`) per tech-spec D27.
 * Any value not in this set raises [IllegalArgumentException] at call time.
 *
 * `internal` — consumers should pass the string literal; the set exists for
 * runtime enforcement, not for cross-module pre-validation.
 */
internal val ALLOWED_COMPONENTS: Set<String> = setOf(
  // Phase 1
  "download",
  "inference-init",
  "inference",
  "inference-cleanup",
  // Phase 2 (D27)
  "settings-io",
  "camera",
  "audio",
  "attachment-decode",
)

/**
 * On-device ERROR-only writer. Single channel for failure events.
 *
 * Format (one physical line per event, no newlines, no emoji, no dividers):
 * ```
 * ERROR [component] description :: CauseType: cause.message
 * ```
 * When `cause` is null the ` :: ...` suffix is omitted. `description` is
 * sanitized (control whitespace → space) and truncated to 500 chars;
 * `cause.message` is sanitized and truncated to 200 chars — bounds the
 * cause-chain footprint so a verbose native exception cannot blow out the log
 * (TAC-15). The cause class name is emitted verbatim.
 *
 * Rotation: after each append, if the file exceeds 2 MB, any existing
 * `errors.log.1` is deleted and the current `errors.log` is renamed to
 * `errors.log.1`.
 *
 * See `.claude/skills/project-knowledge/references/patterns.md § Error logging conventions`.
 */
@Singleton
class ErrorLog @Inject constructor(@ApplicationContext private val context: Context) {

  private val mutex = Mutex()

  suspend fun e(component: String, description: String, cause: Throwable? = null) {
    require(component in ALLOWED_COMPONENTS) {
      "Unknown ErrorLog component: '$component'. Allowed: $ALLOWED_COMPONENTS"
    }
    mutex.withLock {
      withContext(Dispatchers.IO) {
        try {
          val line = buildLine(component, description, cause)
          val dir = File(context.filesDir, LOG_DIR).apply { mkdirs() }
          val file = File(dir, LOG_FILE)
          file.appendText(line + "\n", Charsets.UTF_8)
          if (file.length() > MAX_LOG_BYTES) {
            val rotated = File(dir, ROTATED_FILE)
            if (rotated.exists()) rotated.delete()
            file.renameTo(rotated)
          }
        } catch (ce: CancellationException) {
          throw ce
        } catch (_: Throwable) {
          // Logger must never fail its caller — I/O errors are swallowed by design.
        }
      }
    }
  }

  private fun buildLine(component: String, description: String, cause: Throwable?): String {
    val head = "ERROR [$component] ${sanitize(description, MAX_DESCRIPTION_LEN)}"
    if (cause == null) return head
    val causeClass = cause::class.simpleName.orEmpty()
    val causeMsg = cause.message?.let { sanitize(it, MAX_CAUSE_MESSAGE_LEN) }.orEmpty()
    return "$head :: $causeClass: $causeMsg"
  }

  private fun sanitize(raw: String, maxLen: Int): String =
    raw.replace(CONTROL_WS, " ").take(maxLen)
}
