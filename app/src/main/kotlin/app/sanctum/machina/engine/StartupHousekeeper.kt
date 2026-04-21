package app.sanctum.machina.engine

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.ChatRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

private const val LOG_COMPONENT = "attachment-save"
private const val QUICK_DIR_NAME = "quick"
private const val ATTACHMENTS_DIR_NAME = "attachments"
private const val STAGING_PREFIX = ".staging-"

/**
 * Cold-start housekeeping extracted from `SanctumApplication.onCreate` (Phase-3 Task 6).
 *
 * Runs once per process, inside the `getProcessName() == packageName` guard, on a
 * `Dispatchers.IO` coroutine owned by `SanctumApplication`. Three independent best-effort
 * steps:
 *  1. Purge `filesDir/quick/` — the incognito guarantee (no quick-chat artefact survives
 *     a process restart). A missing directory is not an error.
 *  2. Remove orphan `.staging-*` directories under `filesDir/attachments/` — these appear
 *     when `ChatRepository.commitDraftChat` is killed between staging and rename (AC-A6).
 *  3. Invoke `ChatRepository.sweepZombieChats` — rows with no messages and no attachments
 *     directory (AC-P8).
 *
 * Each step's failure is funnelled through `ErrorLog.e("attachment-save", ...)` and swallowed
 * so one broken file cannot cascade into a failed startup sequence. The class is `open` and
 * [deleter] is `@VisibleForTesting` so a unit test can force `IOException` without relying
 * on POSIX permission semantics that Robolectric's sandbox does not honour.
 */
@Singleton
open class StartupHousekeeper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val errorLog: ErrorLog,
    private val chatRepository: ChatRepository,
) {

    @VisibleForTesting
    internal var deleter: (File) -> Unit = { dir ->
        if (!dir.deleteRecursively()) {
            throw IOException("delete failed: ${dir.absolutePath}")
        }
    }

    open suspend fun run() {
        val filesDir = context.filesDir
        purgeQuickDir(filesDir)
        cleanupOrphanStagingDirs(filesDir)
        try {
            chatRepository.sweepZombieChats(filesDir)
        } catch (ce: CancellationException) {
            throw ce
        } catch (cause: Throwable) {
            errorLog.e(LOG_COMPONENT, "sweepZombieChats failed", cause)
        }
    }

    private suspend fun purgeQuickDir(filesDir: File) {
        val quickDir = File(filesDir, QUICK_DIR_NAME)
        if (!quickDir.exists()) return
        try {
            deleter(quickDir)
        } catch (ce: CancellationException) {
            throw ce
        } catch (cause: Throwable) {
            errorLog.e(LOG_COMPONENT, "quick/ purge failed", cause)
        }
    }

    private suspend fun cleanupOrphanStagingDirs(filesDir: File) {
        val attachmentsDir = File(filesDir, ATTACHMENTS_DIR_NAME)
        val orphans = attachmentsDir.listFiles { _, name -> name.startsWith(STAGING_PREFIX) }
            ?: return
        for (dir in orphans) {
            try {
                deleter(dir)
            } catch (ce: CancellationException) {
                throw ce
            } catch (cause: Throwable) {
                errorLog.e(LOG_COMPONENT, "orphan staging cleanup failed: ${dir.name}", cause)
            }
        }
    }
}
