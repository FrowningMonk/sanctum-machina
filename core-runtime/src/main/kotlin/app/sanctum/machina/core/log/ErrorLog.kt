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
 * Closed whitelist of components allowed as the first argument to
 * [ErrorLog.e] / [ErrorLog.i] / [ErrorLog.w]. Phase 3.6 adds
 * `"inference-reset"` for `DefaultModelRegistry.resetConversation`
 * diagnostics (Decision 5). Any value not in this set raises
 * [IllegalArgumentException] at call time, before any I/O.
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
  // Phase 3
  "model",
  "engine-warmup",
  "history-read",
  "history-write",
  "attachment-save",
  "attachment-read",
  // Phase 3.6
  "inference-reset",
  // Phase 4 Task 4 forward-port — the full Task-6 batch (`embed`, `pdf-parse`,
  // `rag-index`, `rag-retrieve`) lands with `RagInjector`/`IngestWorker` wiring.
  // `EmbedderRegistry` needs `embed-init` immediately to log warmup failures
  // without tripping the whitelist guard.
  "embed-init",
)

/**
 * On-device three-level writer (`e` / `i` / `w`). Single channel for
 * operational events: errors, info, warnings. All three route through one
 * private [write] helper that owns whitelist enforcement, sanitization,
 * length-bounding, mutex, append, and rotation — so adding a level cannot
 * drift from the existing input pipeline.
 *
 * Format (one physical line per event, no newlines, no emoji, no dividers):
 * ```
 * <LEVEL> [component] description :: CauseType: cause.message
 * ```
 * `<LEVEL>` is one of `ERROR`, `INFO`, `WARN`. When `cause` is null the
 * ` :: ...` suffix is omitted. `description` is sanitized (control whitespace
 * → space) and truncated to 500 chars; `cause.message` is sanitized and
 * truncated to 200 chars — bounds the cause-chain footprint so a verbose
 * native exception cannot blow out the log (TAC-15). The cause class name
 * is emitted verbatim.
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
    write(Level.ERROR, component, description, cause)
  }

  suspend fun i(component: String, description: String, cause: Throwable? = null) {
    write(Level.INFO, component, description, cause)
  }

  suspend fun w(component: String, description: String, cause: Throwable? = null) {
    write(Level.WARN, component, description, cause)
  }

  private suspend fun write(
    level: Level,
    component: String,
    description: String,
    cause: Throwable?,
  ) {
    require(component in ALLOWED_COMPONENTS) {
      "Unknown ErrorLog component: '$component'. Allowed: $ALLOWED_COMPONENTS"
    }
    mutex.withLock {
      withContext(Dispatchers.IO) {
        try {
          val line = buildLine(level, component, description, cause)
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

  private fun buildLine(
    level: Level,
    component: String,
    description: String,
    cause: Throwable?,
  ): String {
    val head = "${level.name} [$component] ${sanitize(description, MAX_DESCRIPTION_LEN)}"
    if (cause == null) return head
    val causeClass = cause::class.simpleName.orEmpty()
    val causeMsg = cause.message?.let { sanitize(it, MAX_CAUSE_MESSAGE_LEN) }.orEmpty()
    return "$head :: $causeClass: $causeMsg"
  }

  private fun sanitize(raw: String, maxLen: Int): String =
    raw.replace(CONTROL_WS, " ").take(maxLen)

  private enum class Level { ERROR, INFO, WARN }
}
