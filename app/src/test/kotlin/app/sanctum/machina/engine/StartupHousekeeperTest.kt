package app.sanctum.machina.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.ChatRepository
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TDD harness for [StartupHousekeeper] (Phase-3 Task 6).
 *
 * Housekeeping is extracted into a dedicated Hilt-injected class so each failure branch can be
 * unit-tested without reconstructing the full `SanctumApplication` + `@HiltAndroidApp` graph.
 * The quick-purge and staging-cleanup failure paths require a deliberate throw — we inject
 * `deleter` to simulate `IOException` without fighting Robolectric's sand-boxed filesystem
 * (which does not honour POSIX permission bits reliably under the JVM).
 *
 * `ErrorLog` is the real class writing under `filesDir/logs/errors.log`; the file is the
 * observable assertion surface for `"attachment-save"` entries (project convention: no mocks).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StartupHousekeeperTest {

    private lateinit var context: Context
    private lateinit var errorLog: ErrorLog
    private lateinit var errorLogFile: File
    private lateinit var chatRepository: FakeChatRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        errorLog = ErrorLog(context)
        errorLogFile = File(context.filesDir, "logs/errors.log")
        errorLogFile.parentFile?.deleteRecursively()
        chatRepository = FakeChatRepository()
        File(context.filesDir, "quick").deleteRecursively()
        File(context.filesDir, "attachments").deleteRecursively()
    }

    @After
    fun tearDown() {
        errorLogFile.parentFile?.deleteRecursively()
        File(context.filesDir, "quick").deleteRecursively()
        File(context.filesDir, "attachments").deleteRecursively()
    }

    @Test
    fun purgeQuickDirFailureLogsAttachmentSave() = runBlocking {
        val quickDir = File(context.filesDir, "quick").apply { mkdirs() }
        File(quickDir, "marker").writeText("x")

        val housekeeper = StartupHousekeeper(context, errorLog, chatRepository).apply {
            deleter = { throw IOException("synthetic delete failure") }
        }

        housekeeper.run()

        awaitAttachmentSaveEntry("quick")
        val logged = errorLogFile.readText()
        assertTrue(
            "expected attachment-save entry for quick purge; got: $logged",
            logged.contains("ERROR [attachment-save]") && logged.contains("quick"),
        )
    }

    @Test
    fun orphanStagingCleanupFailureLogsAttachmentSave() = runBlocking {
        val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val stagingA = File(attachmentsDir, ".staging-a").apply { mkdirs() }
        val stagingB = File(attachmentsDir, ".staging-b").apply { mkdirs() }

        val failingNames = setOf(stagingA.name)
        val deletedNames = mutableListOf<String>()

        val housekeeper = StartupHousekeeper(context, errorLog, chatRepository).apply {
            deleter = { dir ->
                if (dir.name in failingNames) {
                    throw IOException("synthetic delete failure for ${dir.name}")
                }
                deletedNames += dir.name
                dir.deleteRecursively()
            }
        }

        housekeeper.run()

        awaitAttachmentSaveEntry("staging")
        val logged = errorLogFile.readText()
        assertTrue(
            "failing staging dir must log attachment-save; got: $logged",
            logged.contains("ERROR [attachment-save]") && logged.contains(".staging-a"),
        )
        assertTrue(
            "healthy staging dir must still be processed after a peer throws",
            ".staging-b" in deletedNames,
        )
    }

    @Test
    fun sweepZombieChatsInvokedWithFilesDir() = runBlocking {
        val housekeeper = StartupHousekeeper(context, errorLog, chatRepository)

        housekeeper.run()

        assertEquals(
            "sweepZombieChats must receive the ApplicationContext's filesDir exactly once",
            listOf(context.filesDir.absolutePath),
            chatRepository.sweepCalls.map { it.absolutePath },
        )
    }

    @Test
    fun sweepZombieChatsFailureLogsHistoryWriteAndDoesNotThrow() = runBlocking {
        val throwingRepo = FakeChatRepository().apply {
            sweepThrowable = IOException("synthetic sweep failure")
        }
        val housekeeper = StartupHousekeeper(context, errorLog, throwingRepo)

        // Implicit assertion: run() completes normally despite the underlying throw.
        housekeeper.run()

        awaitHistoryWriteEntry()
        val logged = errorLogFile.readText()
        assertTrue(
            "expected history-write entry for sweep failure; got: $logged",
            logged.contains("ERROR [history-write]") && logged.contains("sweepZombieChats failed"),
        )
    }

    private fun awaitHistoryWriteEntry() {
        val deadlineMs = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadlineMs) {
            if (errorLogFile.exists() &&
                errorLogFile.length() > 0 &&
                errorLogFile.readText().contains("ERROR [history-write]")
            ) {
                return
            }
            Thread.sleep(20)
        }
    }

    @Test
    fun missingQuickAndAttachmentsDirsAreSilent() = runBlocking {
        val housekeeper = StartupHousekeeper(context, errorLog, chatRepository)

        housekeeper.run()

        assertFalse(
            "no ErrorLog entry should be written when nothing exists to clean",
            errorLogFile.exists() && errorLogFile.length() > 0,
        )
    }

    private fun awaitAttachmentSaveEntry(needle: String) {
        val deadlineMs = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadlineMs) {
            if (errorLogFile.exists() &&
                errorLogFile.length() > 0 &&
                errorLogFile.readText().let {
                    it.contains("ERROR [attachment-save]") && it.contains(needle)
                }
            ) {
                return
            }
            Thread.sleep(20)
        }
    }

    private class FakeChatRepository : ChatRepository {
        val sweepCalls = mutableListOf<File>()
        var sweepThrowable: Throwable? = null

        override suspend fun commitDraftChat(
            modelId: String,
            firstMessage: MessageEntity,
            stagingDir: File?,
            filesDir: File,
            stagedImageFilename: String?,
            stagedAudioFilename: String?,
            projectId: Long?,
        ): Long = 0L

        override suspend fun writeAttachmentStaging(
            stagingDir: File,
            filesDir: File,
            attachment: app.sanctum.machina.ui.chat.Attachment,
        ): String = ""

        override suspend fun deleteStagedAttachment(
            stagingDir: File,
            filesDir: File,
            filename: String,
        ) = Unit

        override suspend fun pruneStagingDir(
            stagingDir: File,
            filesDir: File,
            retain: Set<String>,
        ) = Unit

        override suspend fun savePersistentAttachment(
            chatId: Long,
            filesDir: File,
            attachment: app.sanctum.machina.ui.chat.Attachment,
        ): app.sanctum.machina.data.PersistedAttachment =
            app.sanctum.machina.data.PersistedAttachment()

        override suspend fun savePersistentMessage(message: MessageEntity) = Unit
        override suspend fun updateChatLastMessage(chatId: Long, timestampMs: Long) = Unit
        override suspend fun updateChatTitle(chatId: Long, title: String, isManuallyTitled: Boolean) = Unit
        override suspend fun deleteChat(chatId: Long, filesDir: File) = Unit
        override fun observeChats(): Flow<List<ChatEntity>> = emptyFlow()
        override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = emptyFlow()

        override suspend fun sweepZombieChats(filesDir: File) {
            sweepCalls += filesDir
            sweepThrowable?.let { throw it }
        }
    }
}
