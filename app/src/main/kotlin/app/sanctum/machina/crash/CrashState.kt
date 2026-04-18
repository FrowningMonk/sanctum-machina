package app.sanctum.machina.crash

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val LOG_DIR = "logs"
private const val CRASH_LOG = "crash.log"
private const val DISMISSED_FLAG = "crash.log.dismissed"

/**
 * Filesystem-backed view of "the previous run crashed and the user hasn't
 * hidden the banner yet" for the main process (Decision 6).
 *
 * Truth is read from disk — never cached — because the `:crash` process writes
 * to the same directory and we cannot share live objects across processes.
 * [hasUnresolvedCrash] emits `true` iff `filesDir/logs/crash.log` exists and
 * `filesDir/logs/crash.log.dismissed` does not.
 *
 * Not a coroutine-aware store: reads and writes are synchronous. A simple
 * JVM lock on [refresh] would be enough if we ever observed contention; today
 * the only writer path is the UI thread via `LaunchedEffect`.
 */
@Singleton
class CrashState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val logsDir: File = File(context.filesDir, LOG_DIR)
    private val crashLog: File = File(logsDir, CRASH_LOG)
    private val dismissedFlag: File = File(logsDir, DISMISSED_FLAG)

    private val _state = MutableStateFlow(false)
    val hasUnresolvedCrash: StateFlow<Boolean> = _state.asStateFlow()

    fun refresh() {
        _state.value = crashLog.exists() && !dismissedFlag.exists()
    }

    fun markDismissed() {
        logsDir.mkdirs()
        if (!dismissedFlag.exists()) {
            dismissedFlag.createNewFile()
        }
        refresh()
    }

    fun clear() {
        crashLog.delete()
        dismissedFlag.delete()
        refresh()
    }
}
