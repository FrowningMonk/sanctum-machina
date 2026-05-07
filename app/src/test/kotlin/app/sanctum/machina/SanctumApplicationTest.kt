package app.sanctum.machina

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.DefaultDownloadRepository
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.registry.ResetReason
import app.sanctum.machina.core.settings.AppSettingsRepository
import app.sanctum.machina.core.settings.proto.AppSettings
import app.sanctum.machina.core.settings.proto.PerModelSettings
import app.sanctum.machina.crash.CrashHandler
import app.sanctum.machina.data.ChatRepository
import app.sanctum.machina.data.SettingsMigrationHelper
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.engine.StartupHousekeeper
import app.sanctum.machina.engine.WarmupCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards Decision 4 and the Phase-3 Task 6 cold-start sequence: `SanctumApplication.onCreate`
 * installs `CrashHandler` and launches warmup / migration / housekeeping only when
 * `getProcessName() == packageName`; `DefaultDownloadRepository.mainActivityFqn` stays outside
 * the guard (the download worker process needs the FQN).
 *
 * `SanctumApplication` is `final` and annotated `@HiltAndroidApp`; tech-spec explicitly forbids
 * opening it for testability ("класс остаётся как есть"). We stub the process name via
 * reflection on `ActivityThread.mBoundApplication.processName` — the same backing field that
 * [android.app.Application.getProcessName] ultimately reads on API 28+. Robolectric 4.12
 * emulates the real `ActivityThread`, so the reflection path is stable across sdk=[33] runs.
 *
 * `@Before` snapshots and `@After` restores [Thread.getDefaultUncaughtExceptionHandler] because
 * Robolectric shares JVM globals across test classes; leaking a [CrashHandler] would poison
 * later suites. Mirrors the symmetric-cleanup pattern used by `ErrorLogTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SanctumApplicationTest {

    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var previousProcessName: String? = null
    private var previousMainActivityFqn: String? = null
    private lateinit var recordingScope: CoroutineScope
    private lateinit var recording: Recordings

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        previousProcessName = readBoundProcessName()
        previousMainActivityFqn = DefaultDownloadRepository.mainActivityFqn
        DefaultDownloadRepository.mainActivityFqn = null
        // Deterministic non-CrashHandler sentinel so guard-path assertions are unambiguous
        // regardless of what an earlier Robolectric test leaked into the JVM-global handler.
        Thread.setDefaultUncaughtExceptionHandler(SentinelHandler)
        recordingScope = CoroutineScope(SupervisorJob())
        val context = ApplicationProvider.getApplicationContext<Context>()
        recording = Recordings(context, recordingScope)
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        previousProcessName?.let(::writeBoundProcessName)
        DefaultDownloadRepository.mainActivityFqn = previousMainActivityFqn
        recordingScope.cancel()
    }

    @Test
    fun installsCrashHandlerInMainProcess() {
        val app = installRecordings()
        writeBoundProcessName(app.packageName)

        app.onCreate()

        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertTrue(
            "main process must install CrashHandler, got ${installed?.javaClass?.name}",
            installed is CrashHandler,
        )
    }

    @Test
    fun skipsInstallInCrashProcess() {
        val app = installRecordings()
        writeBoundProcessName("${app.packageName}:crash")

        app.onCreate()

        val after = Thread.getDefaultUncaughtExceptionHandler()
        assertFalse(
            ":crash process must NOT install CrashHandler, got ${after?.javaClass?.name}",
            after is CrashHandler,
        )
        assertSame(
            ":crash process must leave the pre-existing handler untouched",
            SentinelHandler,
            after,
        )
    }

    @Test
    fun testHousekeepingRunsInMainProcess() {
        val app = installRecordings()
        writeBoundProcessName(app.packageName)

        app.onCreate()

        awaitCounter("startupHousekeeper.run") { recording.startupHousekeeper.runCount >= 1 }
        awaitCounter("warmupCoordinator.warmupDefault") { recording.warmupCoordinator.warmupCalls >= 1 }
        awaitCounter("settingsMigrationHelper.migrateIfNeeded") { recording.settingsMigrationHelper.migrateCalls >= 1 }
    }

    @Test
    fun testHousekeepingSkippedInCrashProcess() {
        val app = installRecordings()
        writeBoundProcessName("${app.packageName}:crash")

        app.onCreate()

        // Short settle so a racing coroutine would have time to increment the counter if any
        // were launched; the guard branch must NOT launch anything.
        Thread.sleep(100)
        assertEquals(
            ":crash process must NOT launch startup housekeeping",
            0,
            recording.startupHousekeeper.runCount,
        )
        assertEquals(
            ":crash process must NOT call warmupCoordinator.warmupDefault()",
            0,
            recording.warmupCoordinator.warmupCalls,
        )
        assertEquals(
            ":crash process must NOT call settingsMigrationHelper.migrateIfNeeded()",
            0,
            recording.settingsMigrationHelper.migrateCalls,
        )
    }

    @Test
    fun testMainActivityFqnAssignedEvenInCrashProcess() {
        val app = installRecordings()
        writeBoundProcessName("${app.packageName}:crash")

        app.onCreate()

        assertEquals(
            "mainActivityFqn assignment must run outside the packageName guard — DownloadWorker " +
                "runs in a background process and needs the FQN to construct the main-activity PendingIntent",
            "app.sanctum.machina.MainActivity",
            DefaultDownloadRepository.mainActivityFqn,
        )
    }

    private fun installRecordings(): SanctumApplication {
        val app = ApplicationProvider.getApplicationContext<SanctumApplication>()
        app.startupHousekeeper = recording.startupHousekeeper
        app.warmupCoordinator = recording.warmupCoordinator
        app.settingsMigrationHelper = recording.settingsMigrationHelper
        return app
    }

    private fun awaitCounter(label: String, predicate: () -> Boolean) {
        val deadlineMs = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadlineMs) {
            if (predicate()) return
            Thread.sleep(20)
        }
        throw AssertionError("expected $label to be invoked within 2000ms")
    }

    /**
     * Bundle of recording fakes used to stand in for Hilt-injected collaborators.
     * Instantiated once per test with a dedicated [CoroutineScope] so the `init {}` observer
     * loop inside [WarmupCoordinator] does not leak across tests.
     */
    private class Recordings(context: Context, scope: CoroutineScope) {
        val startupHousekeeper = RecordingStartupHousekeeper(context)
        val warmupCoordinator = RecordingWarmupCoordinator(context, scope)
        val settingsMigrationHelper = RecordingSettingsMigrationHelper(context)
    }

    private class RecordingStartupHousekeeper(context: Context) : StartupHousekeeper(
        context = context,
        errorLog = ErrorLog(context),
        chatRepository = NullChatRepository,
    ) {
        var runCount = 0
            private set

        override suspend fun run() {
            runCount++
        }
    }

    private class RecordingWarmupCoordinator(
        context: Context,
        scope: CoroutineScope,
    ) : WarmupCoordinator(
        registry = NullModelRegistry,
        appSettings = NullAppSettingsRepository,
        errorLog = ErrorLog(context),
        scope = scope,
    ) {
        @Volatile var warmupCalls = 0
            private set

        override fun warmupDefault() {
            warmupCalls++
        }
    }

    private class RecordingSettingsMigrationHelper(
        context: Context,
    ) : SettingsMigrationHelper(
        appSettings = NullAppSettingsRepository,
        registry = NullModelRegistry,
        dataStore = NullDataStore,
        errorLog = ErrorLog(context),
    ) {
        @Volatile var migrateCalls = 0
            private set

        override suspend fun migrateIfNeeded() {
            migrateCalls++
        }
    }

    private object NullChatRepository : ChatRepository {
        override suspend fun commitDraftChat(
            modelId: String,
            firstMessage: MessageEntity,
            stagingDir: java.io.File?,
            filesDir: java.io.File,
            stagedImageFilename: String?,
            stagedAudioFilename: String?,
        ): Long = 0L
        override suspend fun writeAttachmentStaging(
            stagingDir: java.io.File,
            filesDir: java.io.File,
            attachment: app.sanctum.machina.ui.chat.Attachment,
        ): String = ""
        override suspend fun deleteStagedAttachment(
            stagingDir: java.io.File,
            filesDir: java.io.File,
            filename: String,
        ) = Unit
        override suspend fun pruneStagingDir(
            stagingDir: java.io.File,
            filesDir: java.io.File,
            retain: Set<String>,
        ) = Unit
        override suspend fun savePersistentAttachment(
            chatId: Long,
            filesDir: java.io.File,
            attachment: app.sanctum.machina.ui.chat.Attachment,
        ): app.sanctum.machina.data.PersistedAttachment =
            app.sanctum.machina.data.PersistedAttachment()
        override suspend fun savePersistentMessage(message: MessageEntity) = Unit
        override suspend fun updateChatLastMessage(chatId: Long, timestampMs: Long) = Unit
        override suspend fun updateChatTitle(chatId: Long, title: String, isManuallyTitled: Boolean) = Unit
        override suspend fun deleteChat(chatId: Long, filesDir: java.io.File) = Unit
        override fun observeChats(): Flow<List<ChatEntity>> = emptyFlow()
        override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = emptyFlow()
        override suspend fun sweepZombieChats(filesDir: java.io.File) = Unit
    }

    private object NullModelRegistry : ModelRegistry {
        override val models: StateFlow<List<ModelEntry>> =
            MutableStateFlow(emptyList<ModelEntry>()).asStateFlow()
        override val activeModelName: StateFlow<String?> =
            MutableStateFlow<String?>(null).asStateFlow()
        override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
        override fun download(model: Model): Flow<ModelDownloadStatus> = emptyFlow()
        override fun cancelDownload(modelName: String) = Unit
        override suspend fun delete(modelName: String) = Unit
        override suspend fun initialize(modelName: String): Result<Unit> = Result.success(Unit)
        override suspend fun cleanup(modelName: String) = Unit
        override suspend fun resetConversation(
            modelName: String,
            systemPrompt: String?,
            reason: ResetReason,
            initialMessages: List<com.google.ai.edge.litertlm.Message>,
        ) = Unit
        override fun getModel(modelName: String): Model? = null
    }

    private object NullAppSettingsRepository : AppSettingsRepository {
        override fun observePerModelSettings(modelId: String): Flow<PerModelSettings?> = emptyFlow()
        override suspend fun savePerModelSettings(modelId: String, settings: PerModelSettings) = Unit
        override suspend fun resetPerModelSettings(modelId: String) = Unit
        override suspend fun getDefaultModelId(): String = ""
        override suspend fun setDefaultModelId(id: String) = Unit
        override fun observeDefaultModelId(): Flow<String> = emptyFlow()
        override suspend fun getLastUsedModelId(): String = ""
        override suspend fun setLastUsedModelId(id: String) = Unit
        override fun observeLastUsedModelId(): Flow<String> = emptyFlow()
        override suspend fun isSettingsMigrated(): Boolean = true
        override suspend fun markSettingsMigrated() = Unit
    }

    private object NullDataStore : DataStore<AppSettings> {
        override val data: Flow<AppSettings> = emptyFlow()
        override suspend fun updateData(
            transform: suspend (t: AppSettings) -> AppSettings,
        ): AppSettings = AppSettings.getDefaultInstance()
    }

    private object SentinelHandler : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) = Unit
    }

    private fun readBoundProcessName(): String? {
        val boundApp = boundAppBindData() ?: return null
        val field = boundApp.javaClass.getDeclaredField("processName")
        field.isAccessible = true
        return field.get(boundApp) as? String
    }

    private fun writeBoundProcessName(name: String) {
        val boundApp = boundAppBindData()
            ?: error("ActivityThread.mBoundApplication is null under Robolectric — test harness broken")
        val field = boundApp.javaClass.getDeclaredField("processName")
        field.isAccessible = true
        field.set(boundApp, name)
    }

    private fun boundAppBindData(): Any? {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentThread = activityThreadClass
            .getMethod("currentActivityThread")
            .invoke(null)
            ?: return null
        val field = activityThreadClass.getDeclaredField("mBoundApplication")
        field.isAccessible = true
        return field.get(currentThread)
    }
}
