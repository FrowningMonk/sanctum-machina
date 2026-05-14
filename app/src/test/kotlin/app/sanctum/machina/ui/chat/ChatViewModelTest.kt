package app.sanctum.machina.ui.chat

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.R
import app.sanctum.machina.core.data.Accelerator
import app.sanctum.machina.core.data.ConfigKeys
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.data.createLlmChatConfigs
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.registry.ResetReason
import app.sanctum.machina.core.runtime.CleanUpListener
import app.sanctum.machina.core.runtime.LlmModelHelper
import app.sanctum.machina.core.runtime.ResultListener
import app.sanctum.machina.core.settings.AppSettingsRepository
import app.sanctum.machina.core.settings.proto.PerModelSettings
import app.sanctum.machina.data.ChatRepository
import app.sanctum.machina.data.PersistedAttachment
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.engine.WarmupCoordinator
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message as LitertlmMessage
import com.google.ai.edge.litertlm.Role
import com.google.ai.edge.litertlm.ToolProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context
    private lateinit var fakeRegistry: FakeModelRegistry
    private lateinit var fakeHelper: FakeLlmHelper
    private lateinit var fakeDecoder: FakeImageDecoder
    private lateinit var fakeRepo: FakeAppSettingsRepository
    private lateinit var fakeMessageDao: FakeMessageDao
    private lateinit var fakeChatDao: FakeChatDao
    private lateinit var fakeChatRepository: FakeChatRepository
    private lateinit var fakeWarmupCoordinator: FakeWarmupCoordinator

    private lateinit var sharedCalls: MutableList<String>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext<Application>()
        sharedCalls = mutableListOf()
        fakeRegistry = FakeModelRegistry(sharedCalls)
        fakeHelper = FakeLlmHelper(sharedCalls)
        fakeDecoder = FakeImageDecoder()
        fakeRepo = FakeAppSettingsRepository()
        fakeMessageDao = FakeMessageDao()
        fakeChatDao = FakeChatDao()
        fakeChatRepository = FakeChatRepository()
        fakeWarmupCoordinator = FakeWarmupCoordinator(kotlinx.coroutines.SupervisorJob())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        fakeWarmupCoordinator.shutdown()
    }

    // ------------------------------------------------------------------
    // Task 8 — TDD anchors (new behaviours under test)
    // ------------------------------------------------------------------

    @Test
    fun quickMode_neverCallsMessageDao() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        vm.send("hello")
        advanceUntilIdle()
        fakeHelper.lastResultListener?.invoke("ok", true, null, null)
        advanceUntilIdle()

        assertEquals(
            "Quick mode must never observe Room messages",
            0,
            fakeMessageDao.observeCalls,
        )
        assertEquals(
            "Quick mode must never insert into Room",
            emptyList<MessageEntity>(),
            fakeMessageDao.inserted,
        )
    }

    @Test
    fun draftMode_firstSend_callsCommitDraftChat() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()

        vm.send("first turn")
        advanceUntilIdle()

        assertEquals(
            "Draft mode's first send must commit a persistent row",
            1,
            fakeChatRepository.commitCalls.size,
        )
        val commit = fakeChatRepository.commitCalls.single()
        assertEquals("id-m", commit.modelId)
        assertEquals("first turn", commit.firstMessage.text)
        assertEquals("user", commit.firstMessage.role)
    }

    @Test
    fun draftMode_afterCommit_navigatesToPersistentRoute() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        fakeChatRepository.nextChatId = 42L
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()
        val navEvents = mutableListOf<ChatNavigationEvent>()
        backgroundScope.launch { vm.navigation.collect { navEvents += it } }

        vm.send("first turn")
        advanceUntilIdle()

        assertEquals(
            "commit success must emit NavigateToPersistent with the new chatId",
            listOf(ChatNavigationEvent.NavigateToPersistent(42L)),
            navEvents,
        )
    }

    @Test
    fun onCleared_doesNotCallRegistryCleanup() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        val method = ViewModelReflection.onCleared(vm)
        method.invoke(vm)
        advanceUntilIdle()

        assertEquals(
            "Decision 5 / AC-E6: onCleared must not tear down the warm engine",
            0,
            fakeRegistry.cleanupCalls,
        )
    }

    @Test
    fun engineStateTransition_initializingDrivesLoading() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        assertTrue(
            "initial Ready must propagate before the transition assertion",
            vm.uiState.value is ChatUiState.Ready,
        )

        fakeRegistry.publishEntry(model, ModelInitStatus.Initializing)
        advanceUntilIdle()

        assertEquals(ChatUiState.Loading, vm.uiState.value)
    }

    @Test
    fun engineStateTransition_readyDrivesReady() = runTest(dispatcher) {
        // Seed Ready first so the VM pins `_chatModelId` (Quick/Draft pin to
        // the first non-null `activeModelName`); then transition through
        // Initializing and back to Ready so we exercise the Initializing→Ready
        // branch of the observer — not the trivial initial Loading state.
        val model = Model(name = "m", modelId = "id-m")
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        assertEquals(
            "initial seed must surface Ready so activeModelName pins",
            ChatUiState.Ready(isGenerating = false),
            vm.uiState.value,
        )

        fakeRegistry.publishEntry(model, ModelInitStatus.Initializing)
        advanceUntilIdle()
        assertEquals(
            "Initializing must drive Loading",
            ChatUiState.Loading,
            vm.uiState.value,
        )

        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        advanceUntilIdle()
        assertEquals(
            "transition back to Ready must reset isGenerating to false",
            ChatUiState.Ready(isGenerating = false),
            vm.uiState.value,
        )
    }

    @Test
    fun engineStateTransition_failedDrivesFailed() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        fakeRegistry.publishEntry(model, ModelInitStatus.Failed("boom"))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is ChatUiState.Failed)
        assertEquals("boom", (state as ChatUiState.Failed).rawCause)
    }

    @Test
    fun doubleBubble_noSimultaneousStreamingAndPersisted() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        // Seed Room with the USER row that corresponds to the streaming turn.
        val userRow = MessageEntity(
            id = 1L, chatId = 7L, role = "user", text = "hi", createdAt = 10L,
        )
        fakeMessageDao.emit(listOf(userRow))
        advanceUntilIdle()

        // Capture every displayMessages emission while the stream runs.
        val emissions = mutableListOf<List<Message>>()
        backgroundScope.launch { vm.messages.collect { emissions += it } }

        vm.send("hi")
        advanceUntilIdle()
        val listener = fakeHelper.lastResultListener
            ?: error("Persistent send must call runInference")

        // Stream a token — the in-memory bubble must be observable.
        listener.invoke("partial", false, null, null)
        advanceUntilIdle()
        assertTrue(
            "during streaming, the in-memory bubble must be visible",
            emissions.any { snap -> snap.any { m -> m.role == MessageRole.ASSISTANT && m.streaming } },
        )

        // Engine signals done — VM persists ASSISTANT; simulate Room re-emitting.
        listener.invoke("", true, null, null)
        advanceUntilIdle()
        val assistantRow = MessageEntity(
            id = 2L, chatId = 7L, role = "assistant", text = "partial", createdAt = 20L,
        )
        fakeMessageDao.emit(listOf(userRow, assistantRow))
        advanceUntilIdle()

        // Load-bearing invariant: across every emission, total ASSISTANT count
        // must not exceed 1. This catches the bug regardless of the streaming
        // flag — a failure here proves either the combine suppression or the
        // persistence ordering is broken (test-reviewer-1 T1).
        for ((i, snap) in emissions.withIndex()) {
            val assistantCount = snap.count { it.role == MessageRole.ASSISTANT }
            assertTrue(
                "emission #$i has $assistantCount ASSISTANT entries: $snap",
                assistantCount <= 1,
            )
        }
        // Final state must contain exactly one ASSISTANT (the persisted row).
        val last = emissions.last()
        assertEquals(1, last.count { it.role == MessageRole.ASSISTANT })
        assertEquals("partial", last.single { it.role == MessageRole.ASSISTANT }.text)
        // And that final ASSISTANT must NOT be the streaming bubble — it is the
        // persisted row projected by `toDomainMessage` (streaming=false).
        assertFalse(
            "the final ASSISTANT must be the Room-backed row, not the in-memory bubble",
            last.single { it.role == MessageRole.ASSISTANT }.streaming,
        )
    }

    @Test
    fun persistentMode_userRowWrittenBeforeRunInference_AC_R1() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        fakeChatRepository.eventLog = sharedCalls
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        sharedCalls.clear()
        vm.send("hello")
        advanceUntilIdle()

        val savePos = sharedCalls.indexOf("savePersistentMessage")
        val inferPos = sharedCalls.indexOf("runInference")
        assertTrue("savePersistentMessage must have been invoked", savePos >= 0)
        assertTrue("runInference must have been invoked", inferPos >= 0)
        assertTrue(
            "AC-R1: USER must persist BEFORE runInference fires (save=$savePos, run=$inferPos)",
            savePos < inferPos,
        )
        val firstSaved = fakeChatRepository.insertedMessages.first()
        assertEquals("user", firstSaved.role)
        assertEquals("hello", firstSaved.text)
    }

    @Test
    fun persistentMode_assistantWrittenOnlyOnDone_AC_R2() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()
        val listener = fakeHelper.lastResultListener ?: error("send must invoke runInference")
        val beforeDone = fakeChatRepository.insertedMessages.count { it.role == "assistant" }
        assertEquals("no ASSISTANT row must exist before done=true", 0, beforeDone)

        listener.invoke("hel", false, null, null)
        listener.invoke("lo", false, null, null)
        advanceUntilIdle()
        assertEquals(
            "AC-R2: streaming tokens must not trigger intermediate ASSISTANT writes",
            0,
            fakeChatRepository.insertedMessages.count { it.role == "assistant" },
        )

        listener.invoke("", true, null, null)
        advanceUntilIdle()
        assertEquals(
            "AC-R2: exactly one ASSISTANT write on done=true",
            1,
            fakeChatRepository.insertedMessages.count { it.role == "assistant" },
        )
        val saved = fakeChatRepository.insertedMessages.single { it.role == "assistant" }
        assertEquals("hello", saved.text)
    }

    @Test
    fun persistentMode_userSaveFailure_abortsInference() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        fakeChatRepository.savePersistentMessageError = java.io.IOException("sqlite full")
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()

        assertEquals(
            "AC-R1 hard-gate: USER persist failure must NOT trigger runInference",
            0,
            fakeHelper.runInferenceCalls,
        )
        assertTrue(
            "Send gate must re-open (Ready(isGenerating=false)) on USER persist failure",
            vm.uiState.value is ChatUiState.Ready &&
                !(vm.uiState.value as ChatUiState.Ready).isGenerating,
        )
    }

    @Test
    fun persistentMode_stopBeforeDone_doesNotPersistAssistant_AC_R3() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()
        val listener = fakeHelper.lastResultListener ?: error("send must invoke runInference")
        listener.invoke("partial", false, null, null)
        advanceUntilIdle()

        vm.stop()
        advanceUntilIdle()

        assertEquals(
            "AC-R3: stop() must not synthesise a persisted ASSISTANT row",
            0,
            fakeChatRepository.insertedMessages.count { it.role == "assistant" },
        )
        // USER row must still be present (the user sent the message — only the
        // answer was interrupted).
        assertEquals(
            1,
            fakeChatRepository.insertedMessages.count { it.role == "user" },
        )
    }

    // ------------------------------------------------------------------
    // Task 17 — attachment staging (Draft atomicity + Persistent write)
    // ------------------------------------------------------------------

    @Test
    fun draftMode_addImage_writesToStagingDir() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportImage = true))
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()

        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()

        assertEquals(
            "Draft add must write the image to staging",
            1,
            fakeChatRepository.stagingWrites.count { it is Attachment.Image },
        )
        val attached = vm.attachments.value.single() as Attachment.Image
        assertEquals(
            "staged filename populated on attachment after write",
            "img_0.png",
            attached.stagedFilename,
        )
    }

    @Test
    fun draftMode_addAudio_writesToStagingDir() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportAudio = true))
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()

        vm.addAudio(byteArrayOf(1, 2, 3), durationMs = 500L)
        advanceUntilIdle()

        assertEquals(
            "Draft audio add must write to staging",
            1,
            fakeChatRepository.stagingWrites.count { it is Attachment.Audio },
        )
        val audio = vm.attachments.value.single() as Attachment.Audio
        assertEquals("audio_0.wav", audio.stagedFilename)
    }

    @Test
    fun quickMode_addImage_doesNotStage() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportImage = true))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()

        assertEquals(
            "Quick must not touch the staging API",
            0,
            fakeChatRepository.stagingWrites.size,
        )
    }

    @Test
    fun draftMode_commitWithAttachment_passesStagingDirAndFilenames() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportImage = true))
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()

        vm.send("look at this")
        advanceUntilIdle()

        val commit = fakeChatRepository.commitCalls.single()
        assertEquals("staged image forwarded", "img_0.png", commit.stagedImageFilename)
        assertNull("no audio staged", commit.stagedAudioFilename)
        assertNotNull("staging dir non-null when image staged", commit.stagingDir)
    }

    @Test
    fun draftMode_stagingWriteFails_removesAttachmentAndShowsSnackbar() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportImage = true))
        fakeChatRepository.writeStagingError = java.io.IOException("disk full")
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()
        val events = collectSnackbar(vm)

        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()

        assertTrue(
            "failed staging write removes attachment from list",
            vm.attachments.value.isEmpty(),
        )
        assertTrue(
            "user sees attachment_save_failed snackbar",
            events.contains(R.string.attachment_save_failed),
        )
    }

    @Test
    fun draftMode_sendWhileStagingInFlight_blocksWithSnackbar() = runTest(dispatcher) {
        // The send-gate guards against a commit that would reference a file
        // not yet on disk. Simulate an in-flight staging write with a
        // CompletableDeferred the test controls, then call send() while the
        // attachment still has `stagedFilename = null`.
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportImage = true))
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()

        val block = kotlinx.coroutines.CompletableDeferred<Unit>()
        val suspending = object : ChatRepository by fakeChatRepository {
            override suspend fun writeAttachmentStaging(
                stagingDir: File,
                filesDir: File,
                attachment: Attachment,
            ): String {
                block.await()
                return "img_0.png"
            }
        }
        val savedState = SavedStateHandle(
            mapOf(ChatViewModel.NAV_ARG_KIND to ChatViewModel.KIND_DRAFT),
        )
        val vm2 = ChatViewModel(
            savedStateHandle = savedState,
            registry = fakeRegistry,
            helper = fakeHelper,
            errorLog = ErrorLog(context),
            context = context,
            imageDecoder = fakeDecoder,
            settingsRepository = fakeRepo,
            chatRepository = suspending,
            messageDao = fakeMessageDao,
            chatDao = fakeChatDao,
            warmupCoordinator = fakeWarmupCoordinator,
        )
        advanceUntilIdle()
        val events = collectSnackbar(vm2)

        vm2.addImageBitmap(stubBitmap())
        advanceUntilIdle()
        // Staging is still pending: stagedFilename must be null right now.
        val pending = vm2.attachments.value.single() as Attachment.Image
        assertNull("staging still in flight", pending.stagedFilename)

        vm2.send("go")
        advanceUntilIdle()

        assertEquals("send must NOT commit while staging pending", 0, fakeChatRepository.commitCalls.size)
        assertTrue(
            "send emits attachment_still_saving snackbar",
            events.contains(R.string.attachment_still_saving),
        )
        block.complete(Unit)
    }

    @Test
    fun draftMode_removeAttachment_deletesStagedFile() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportImage = true))
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()
        val stagedName = (vm.attachments.value.single() as Attachment.Image).stagedFilename
        assertEquals("img_0.png", stagedName)

        vm.removeAttachment(0)
        advanceUntilIdle()

        assertTrue("attachment removed from list", vm.attachments.value.isEmpty())
        // test-reviewer-1 T17-T1: litmus test the delete call itself, not just
        // list state. The VM must route the delete through ChatRepository so
        // this assertion fails if `deleteStagedFileIfAny` is ever stripped.
        assertEquals(
            "staged filename routed through repository delete path",
            listOf("img_0.png"),
            fakeChatRepository.deletedStagedFiles,
        )
    }

    @Test
    fun draftMode_removeBeforeStagingSettles_skipsDeleteUntilReady() = runTest(dispatcher) {
        // If `stagedFilename` is still null when remove is called, we cannot
        // know what to delete. This exercises the `filename ?: return` guard
        // in deleteStagedFileIfAny — the list clears, no delete call fires.
        val block = kotlinx.coroutines.CompletableDeferred<Unit>()
        val suspending = object : ChatRepository by fakeChatRepository {
            override suspend fun writeAttachmentStaging(
                stagingDir: File,
                filesDir: File,
                attachment: Attachment,
            ): String {
                block.await()
                return "img_0.png"
            }
        }
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportImage = true))
        val savedState = SavedStateHandle(mapOf(ChatViewModel.NAV_ARG_KIND to ChatViewModel.KIND_DRAFT))
        val vm = ChatViewModel(
            savedStateHandle = savedState,
            registry = fakeRegistry,
            helper = fakeHelper,
            errorLog = ErrorLog(context),
            context = context,
            imageDecoder = fakeDecoder,
            settingsRepository = fakeRepo,
            chatRepository = suspending,
            messageDao = fakeMessageDao,
            chatDao = fakeChatDao,
            warmupCoordinator = fakeWarmupCoordinator,
        )
        advanceUntilIdle()

        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()
        vm.removeAttachment(0)
        advanceUntilIdle()

        assertTrue(vm.attachments.value.isEmpty())
        assertEquals(
            "no delete fires — staging never produced a filename to target",
            emptyList<String>(),
            fakeChatRepository.deletedStagedFiles,
        )
        block.complete(Unit)
    }

    @Test
    fun persistentMode_sendWithAudio_callsSavePersistentAttachment() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m", llmSupportAudio = true)
        fakeChatDao.put(ChatEntity(id = 9L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val vm = buildViewModel(ChatIdentityArg.Persistent(9L))
        advanceUntilIdle()
        vm.addAudio(byteArrayOf(1, 2, 3), durationMs = 1_000L)
        advanceUntilIdle()

        vm.send("listen")
        advanceUntilIdle()

        assertEquals(
            "savePersistentAttachment called for audio in persistent mode",
            1,
            fakeChatRepository.persistentAttachmentCalls.count { it.attachment is Attachment.Audio },
        )
        val saved = fakeChatRepository.insertedMessages.single { it.role == "user" }
        assertEquals("USER row carries persisted audio path", "attachments/9/audio_0.wav", saved.audioPath)
    }

    @Test
    fun draftMode_multipleImages_warnAndPruneExtrasBeforeCommit() = runTest(dispatcher) {
        // code-reviewer-1 T17-R2 / security T17-S4: MAX_IMAGES=10 but only the
        // first is persisted. Ensure the user is warned, the commit references
        // only the first filename, AND `pruneStagingDir` was invoked with
        // exactly that filename in the retain set — without the prune call
        // the extras would survive the rename into `attachments/{chatId}/`
        // as permanent orphans.
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportImage = true))
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()
        val events = collectSnackbar(vm)
        vm.addImageBitmap(stubBitmap())
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()

        vm.send("both")
        advanceUntilIdle()

        assertTrue(
            "extras warning snackbar emitted",
            events.contains(R.string.attachment_only_first_persisted),
        )
        val commit = fakeChatRepository.commitCalls.single()
        assertEquals(
            "only the first image filename is referenced in the commit",
            "img_0.png",
            commit.stagedImageFilename,
        )
        assertEquals(
            "pruneStagingDir called once with exactly the retain set",
            listOf(setOf("img_0.png")),
            fakeChatRepository.pruneRetainLog,
        )
    }

    @Test
    fun draftMode_successfulCommit_allocatesFreshDraftStagingDir() = runTest(dispatcher) {
        // test-reviewer-2: the previous version of this test was vacuous.
        // Real invariant: after a successful commit the VM must drop its
        // reference to the old staging dir, so the NEXT addImageBitmap on
        // the same VM creates a fresh `.staging-{uuid}/` File with a
        // different path. In production the VM is usually destroyed after
        // navigation — this test keeps it alive and uses `stop()` to reset
        // the Send gate between commits so the contract is observable.
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportImage = true))
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()
        vm.send("first")
        advanceUntilIdle()
        val firstStaging = fakeChatRepository.commitCalls.single().stagingDir
        assertNotNull("first send carried staging dir", firstStaging)

        // Post-commit: reset the Send gate (isGenerating=false) so a second
        // send can proceed, then attach + commit again. `stop()` intentionally
        // resets uiState without teardown of the model registry.
        vm.stop()
        advanceUntilIdle()
        fakeChatRepository.commitCalls.clear()
        fakeChatRepository.pruneRetainLog.clear()

        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()
        vm.send("second")
        advanceUntilIdle()
        val secondStaging = fakeChatRepository.commitCalls.single().stagingDir
        assertNotNull("second send carried staging dir", secondStaging)
        assertNotEquals(
            "draftStagingDir reset: second send must use a fresh .staging-{uuid}/ path",
            firstStaging,
            secondStaging,
        )
    }

    @Test
    fun persistentMode_sendWithImage_callsSavePersistentAttachment() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m", llmSupportImage = true)
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()

        vm.send("describe")
        advanceUntilIdle()

        assertEquals(
            "savePersistentAttachment called for image in persistent mode",
            1,
            fakeChatRepository.persistentAttachmentCalls.count { it.attachment is Attachment.Image },
        )
        val saved = fakeChatRepository.insertedMessages.single { it.role == "user" }
        assertEquals("USER row carries persisted image path", "attachments/7/img_0.png", saved.imagePath)
    }

    @Test
    fun persistentMode_savePersistentAttachmentFails_abortsInference() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m", llmSupportImage = true)
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        fakeChatRepository.savePersistentAttachmentError = java.io.IOException("ENOSPC")
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()
        val events = collectSnackbar(vm)

        vm.send("describe")
        advanceUntilIdle()

        assertEquals(
            "attachment save failure must NOT trigger runInference (hard-gate)",
            0,
            fakeHelper.runInferenceCalls,
        )
        assertEquals(
            "USER row must not be written if its attachment never landed on disk",
            0,
            fakeChatRepository.insertedMessages.count { it.role == "user" },
        )
        assertTrue(
            "Send gate reopened",
            vm.uiState.value is ChatUiState.Ready &&
                !(vm.uiState.value as ChatUiState.Ready).isGenerating,
        )
        assertTrue(
            "user sees attachment_save_failed snackbar",
            events.contains(R.string.attachment_save_failed),
        )
    }

    // ------------------------------------------------------------------
    // Phase-2 behaviour kept (adapted to ChatIdentity.Quick)
    // ------------------------------------------------------------------

    @Test
    fun addImages_belowLimit_addsAll() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        val uris = listOf(uri("a"), uri("b"), uri("c"))

        vm.addImages(uris)
        advanceUntilIdle()

        assertEquals(3, vm.attachments.value.size)
        assertTrue(vm.attachments.value.all { it is Attachment.Image })
    }

    @Test
    fun addImages_exceedsLimit_clipsToTen() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        val seed = List(7) { stubBitmap() }
        seed.forEach { vm.addImageBitmap(it) }
        advanceUntilIdle()
        val events = collectSnackbar(vm)

        vm.addImages(List(5) { uri("u$it") })
        advanceUntilIdle()

        assertEquals(10, vm.attachments.value.size)
        val bitmaps = vm.attachments.value.filterIsInstance<Attachment.Image>().map { it.bitmap }
        for (i in 0 until 7) assertSame(seed[i], bitmaps[i])
        assertEquals(
            "snackbar should emit exactly once for the overflow",
            listOf(R.string.attachment_max_images_reached),
            events,
        )
    }

    @Test
    fun addImages_alreadyAtLimit_noneAdded() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        repeat(10) { vm.addImageBitmap(stubBitmap()) }
        advanceUntilIdle()
        val events = collectSnackbar(vm)

        vm.addImages(listOf(uri("extra")))
        advanceUntilIdle()

        assertEquals(10, vm.attachments.value.size)
        assertEquals(
            listOf(R.string.attachment_max_images_reached),
            events,
        )
    }

    @Test
    fun removeAttachment_validIdx_removes() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        repeat(3) { vm.addImageBitmap(stubBitmap()) }
        advanceUntilIdle()

        vm.removeAttachment(0)

        assertEquals(2, vm.attachments.value.size)
    }

    @Test
    fun removeAttachment_invalidIdx_noCrash() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        repeat(2) { vm.addImageBitmap(stubBitmap()) }
        advanceUntilIdle()

        vm.removeAttachment(-1)
        vm.removeAttachment(99)

        assertEquals(2, vm.attachments.value.size)
    }

    @Test
    fun addImages_decoderReturnsNull_skipsAndDoesNotCrash() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        fakeDecoder.nullFor.add("broken")
        val vm = buildViewModel(ChatIdentityArg.Quick)

        vm.addImages(listOf(uri("broken"), uri("ok")))
        advanceUntilIdle()

        val attachments = vm.attachments.value
        assertEquals(1, attachments.size)
        assertTrue(attachments.single() is Attachment.Image)
        assertEquals(listOf(uri("broken"), uri("ok")), fakeDecoder.decodedUris)
    }

    @Test
    fun modelCaps_reflectInitializedModelSupport() = runTest(dispatcher) {
        fakeRegistry.setModel(
            Model(
                name = "m",
                modelId = "id-m",
                llmSupportImage = true,
                llmSupportAudio = true,
                llmSupportThinking = false,
            )
        )

        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        assertTrue(vm.modelCaps.value.supportImage)
        assertTrue(vm.modelCaps.value.supportAudio)
        assertFalse(vm.modelCaps.value.supportThinking)
    }

    @Test
    fun send_transfersPendingAttachmentsIntoUserMessageAndClearsStaging() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportImage = true))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        val bitmap = stubBitmap()
        vm.addImageBitmap(bitmap)
        advanceUntilIdle()

        vm.send("describe this")
        advanceUntilIdle()

        assertTrue(vm.attachments.value.isEmpty())
        val user = vm.messages.value.first { it.role == MessageRole.USER }
        assertEquals(1, user.attachments.size)
        assertTrue(user.attachments.single() is Attachment.Image)
        assertSame(bitmap, (user.attachments.single() as Attachment.Image).bitmap)
    }

    @Test
    fun send_attachmentOnlyBlankText_stillProceedsAndClears() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportImage = true))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()

        vm.send("")
        advanceUntilIdle()

        assertTrue(vm.attachments.value.isEmpty())
        val user = vm.messages.value.first { it.role == MessageRole.USER }
        assertEquals("", user.text)
        assertEquals(1, user.attachments.size)
    }

    @Test
    fun addAudio_createsAudioAttachment() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        val pcm = byteArrayOf(1, 2, 3, 4)
        vm.addAudio(pcm, durationMs = 1234L)
        advanceUntilIdle()

        val audio = vm.attachments.value.single() as Attachment.Audio
        assertSame(pcm, audio.pcm)
        assertEquals(1234L, audio.durationMs)
    }

    @Test
    fun addAudio_alreadyHasAudio_isNoOp() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        val firstPcm = byteArrayOf(9)
        vm.addAudio(firstPcm, durationMs = 1_000L)
        advanceUntilIdle()

        vm.addAudio(byteArrayOf(10, 11), durationMs = 2_000L)
        advanceUntilIdle()

        val audio = vm.attachments.value.single() as Attachment.Audio
        assertEquals(1_000L, audio.durationMs)
        assertSame(firstPcm, audio.pcm)
    }

    @Test
    fun addAudio_coexistsWithImages() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()

        vm.addAudio(byteArrayOf(1, 2), durationMs = 500L)
        advanceUntilIdle()

        assertEquals(2, vm.attachments.value.size)
        assertTrue(vm.attachments.value[0] is Attachment.Image)
        assertTrue(vm.attachments.value[1] is Attachment.Audio)
    }

    @Test
    fun reportAudioError_emitsSnackbarAndAcceptsValidComponent() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        val events = collectSnackbar(vm)

        vm.reportAudioError("audio record init failed", IllegalStateException("mic busy"))
        advanceUntilIdle()

        assertEquals(listOf(R.string.audio_record_init_failed), events)
    }

    @Test
    fun send_transfersAudioAttachmentAndClears() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m", llmSupportAudio = true))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        val pcm = byteArrayOf(5, 6, 7)
        vm.addAudio(pcm, durationMs = 2_000L)
        advanceUntilIdle()

        vm.send("")
        advanceUntilIdle()

        assertTrue(vm.attachments.value.isEmpty())
        val user = vm.messages.value.first { it.role == MessageRole.USER }
        val audio = user.attachments.single() as Attachment.Audio
        assertSame(pcm, audio.pcm)
        assertEquals(2_000L, audio.durationMs)
    }

    @Test
    fun reportCameraError_emitsSnackbarAndAcceptsValidComponent() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        val events = collectSnackbar(vm)

        vm.reportCameraError("camera bind failed", IllegalStateException("provider unavailable"))
        advanceUntilIdle()

        assertEquals(listOf(R.string.camera_init_failed), events)
    }

    @Test
    fun send_emptyTextEmptyAttachments_noInference() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        vm.send("   ")
        advanceUntilIdle()

        assertEquals(0, fakeHelper.runInferenceCalls)
        assertTrue(vm.messages.value.isEmpty())
    }

    @Test
    fun send_thinkingEnabled_accumulates() = runTest(dispatcher) {
        val model = Model(
            name = "m",
            modelId = "id-m",
            llmSupportThinking = true,
            configs = createLlmChatConfigs(supportThinking = true),
        )
        model.preProcess()
        fakeRegistry.setModel(model)
        fakeRepo.save("id-m", PerModelSettings.newBuilder().setEnableThinking(true).build())
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        vm.send("think please")
        advanceUntilIdle()

        val listener = fakeHelper.lastResultListener ?: error("send must invoke runInference")
        listener.invoke("hello", false, "thought-1 ", null)
        listener.invoke(" world", false, "thought-2", null)
        listener.invoke("", true, null, null)

        val assistant = vm.messages.value.last { it.role == MessageRole.ASSISTANT }
        assertEquals("hello world", assistant.text)
        assertEquals("thought-1 thought-2", assistant.thinkingText)
        assertEquals(
            mapOf("enable_thinking" to "true"),
            fakeHelper.lastExtraContext,
        )
    }

    @Test
    fun send_thinkingDisabled_extraContextNull() = runTest(dispatcher) {
        val model = Model(
            name = "m",
            modelId = "id-m",
            llmSupportThinking = true,
            configs = createLlmChatConfigs(supportThinking = true),
        )
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()

        assertNull(fakeHelper.lastExtraContext)
    }

    @Test
    fun applyLightOverrides_callsResetConversation_withLightOverrideReason() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        fakeRepo.save(
            "id-m",
            PerModelSettings.newBuilder().setTemperature(0.2f).setMaxTokens(512).build(),
        )

        val cleanupCountBefore = fakeRegistry.cleanupCalls
        val initCountBefore = fakeRegistry.initializeCalls

        vm.applyLightOverrides()
        advanceUntilIdle()

        assertEquals(0.2f, model.configValues[ConfigKeys.TEMPERATURE.label])
        assertEquals(512, model.configValues[ConfigKeys.MAX_TOKENS.label])
        // Light tier is a sampler refresh, not a context wipe — must not
        // invoke the heavy cleanup+initialize path.
        assertEquals(cleanupCountBefore, fakeRegistry.cleanupCalls)
        assertEquals(initCountBefore, fakeRegistry.initializeCalls)
        // After Phase 3.6 Bug-1 fix, Light tier MUST recreate Conversation
        // through registry.resetConversation tagged LIGHT_OVERRIDE so the
        // engine actually re-reads topK/topP/temperature from configValues.
        assertEquals(ResetReason.LIGHT_OVERRIDE, fakeRegistry.lastResetReason)
        // Default system prompt is blank — effectiveSystemPrompt collapses
        // it to null, so the reset propagates null (no system instruction
        // change).
        assertEquals(null, fakeRegistry.lastResetSystemPrompt)
        // Phase 3.6 Task 11: Quick identity has no persistent history, so the
        // initialMessages list reaching the registry must be empty (the
        // recreated Conversation matches the empty in-memory message list).
        assertTrue(
            "Quick chat must not replay any history",
            fakeRegistry.lastResetInitialMessages.isEmpty(),
        )
    }

    @Test
    fun bootstrapPersistent_chatSwitchReset_waitsForReady() = runTest(dispatcher) {
        // Cold-start race regression guard: if the reset fires before the
        // engine reaches Ready, DefaultModelRegistry's non-Ready skip path
        // logs a warning and leaves the KV cache dirty (Bug 1).
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        // ASSISTANT tail → CHAT_SWITCH (not DRAFT_COMMIT).
        fakeMessageDao.emit(
            listOf(
                MessageEntity(id = 1L, chatId = 7L, role = "user", text = "q", createdAt = 10L),
                MessageEntity(id = 2L, chatId = 7L, role = "assistant", text = "a", createdAt = 20L),
            ),
        )
        fakeRegistry.publishEntry(model, ModelInitStatus.Initializing)

        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        assertEquals(
            "reset must be deferred while engine is Initializing",
            emptyList<ResetReason>(),
            fakeRegistry.resetReasons,
        )

        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        advanceUntilIdle()

        assertEquals(
            "exactly one CHAT_SWITCH reset emitted on first Ready",
            listOf(ResetReason.CHAT_SWITCH),
            fakeRegistry.resetReasons,
        )
    }

    @Test
    fun bootstrapPersistent_draftCommit_resetsBeforeAutoResumeRunInference() = runTest(dispatcher) {
        // Ordering regression guard for the race surfaced by
        // security-auditor-1: if the reset coroutine and the auto-resume
        // coroutine were launched as siblings, the reset's
        // `withContext(Dispatchers.Default)` hop inside DefaultModelRegistry
        // would release Main and the resume's `helper.runInference` could
        // fire against a still-dirty Conversation. Pinning the order in
        // `sharedCalls` (which records both `resetConversation` and
        // `runInference` from the test fakes) prevents a future refactor
        // from re-introducing the race by un-chaining the two coroutines.
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeMessageDao.emit(
            listOf(MessageEntity(id = 1L, chatId = 7L, role = "user", text = "q", createdAt = 10L)),
        )
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)

        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        val resetIdx = sharedCalls.indexOf("resetConversation")
        val runIdx = sharedCalls.indexOf("runInference")
        assertTrue("bootstrap must call resetConversation", resetIdx >= 0)
        assertTrue("bootstrap must auto-resume runInference", runIdx >= 0)
        assertTrue(
            "reset must precede runInference (reset=$resetIdx, run=$runIdx) — " +
                "otherwise the auto-resumed first answer inherits KV from the prior chat",
            resetIdx < runIdx,
        )
    }

    @Test
    fun bootstrapPersistent_emitsDraftCommitReset_whenLastIsUnpairedUser() = runTest(dispatcher) {
        // Draft→Persistent handover: USER row persisted, no ASSISTANT yet.
        // Heuristic must classify as DRAFT_COMMIT to surface the difference
        // in inference-reset diagnostics.
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeMessageDao.emit(
            listOf(
                MessageEntity(id = 1L, chatId = 7L, role = "user", text = "q", createdAt = 10L),
            ),
        )
        fakeRegistry.publishEntry(model, ModelInitStatus.Initializing)

        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()
        assertEquals(emptyList<ResetReason>(), fakeRegistry.resetReasons)

        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        advanceUntilIdle()

        assertEquals(
            "USER tail → DRAFT_COMMIT exactly once",
            listOf(ResetReason.DRAFT_COMMIT),
            fakeRegistry.resetReasons,
        )
    }

    // ------------------------------------------------------------------
    // Phase 3.6 Task 11 — `initialMessages` propagation (KV-cache replay)
    // ------------------------------------------------------------------

    @Test
    fun bootstrapPersistent_replaysHistoryAsInitialMessages_whenLastIsAssistant() =
        runTest(dispatcher) {
            // Paired tail (USER → ASSISTANT × 2) → CHAT_SWITCH path. Every
            // row must reach `initialMessages`; nothing is dropped because
            // the tail is ASSISTANT, not unpaired USER.
            val model = Model(name = "m", modelId = "id-m")
            fakeChatDao.put(
                ChatEntity(id = 42L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L),
            )
            fakeMessageDao.emit(
                listOf(
                    MessageEntity(id = 1L, chatId = 42L, role = "user", text = "hi", createdAt = 1L),
                    MessageEntity(id = 2L, chatId = 42L, role = "assistant", text = "hello", createdAt = 2L),
                    MessageEntity(id = 3L, chatId = 42L, role = "user", text = "more", createdAt = 3L),
                    MessageEntity(id = 4L, chatId = 42L, role = "assistant", text = "ok", createdAt = 4L),
                ),
            )
            fakeRegistry.publishEntry(model, ModelInitStatus.Ready)

            val vm = buildViewModel(ChatIdentityArg.Persistent(42L))
            advanceUntilIdle()

            assertEquals(ResetReason.CHAT_SWITCH, fakeRegistry.lastResetReason)
            val replayed = fakeRegistry.lastResetInitialMessages
            assertEquals(
                "all 4 paired rows must be replayed when tail is ASSISTANT",
                4,
                replayed.size,
            )
            assertEquals(
                "role mapping ROLE_USER → USER, ROLE_ASSISTANT → MODEL preserved in order",
                listOf(Role.USER, Role.MODEL, Role.USER, Role.MODEL),
                replayed.map { it.role },
            )
            // Per-row text round-trip catches a regression where the lambda
            // closes over a single entity (e.g., misplaced `first()`) and
            // produces N copies of the same text — sizes/roles still match
            // but per-message content collapses.
            assertEquals(
                listOf("hi", "hello", "more", "ok"),
                replayed.map { msg ->
                    msg.contents.contents.filterIsInstance<Content.Text>().firstOrNull()?.text
                },
            )
        }

    @Test
    fun bootstrapPersistent_excludesUnpairedUserFromInitialMessages_whenDraftCommit() =
        runTest(dispatcher) {
            // Draft→Persistent handover (or AC-R3 mid-stream kill): tail is
            // unpaired USER. That row must NOT enter `initialMessages` —
            // `observeFirstReadyThenResume` will dispatch it as the first
            // USER turn of the new Conversation. Replaying it here too
            // would double-count the message in KV.
            val model = Model(name = "m", modelId = "id-m")
            fakeChatDao.put(
                ChatEntity(id = 99L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L),
            )
            fakeMessageDao.emit(
                listOf(
                    MessageEntity(id = 1L, chatId = 99L, role = "user", text = "u1", createdAt = 1L),
                    MessageEntity(id = 2L, chatId = 99L, role = "assistant", text = "a1", createdAt = 2L),
                    MessageEntity(id = 3L, chatId = 99L, role = "user", text = "u2", createdAt = 3L),
                ),
            )
            fakeRegistry.publishEntry(model, ModelInitStatus.Ready)

            val vm = buildViewModel(ChatIdentityArg.Persistent(99L))
            advanceUntilIdle()

            assertEquals(ResetReason.DRAFT_COMMIT, fakeRegistry.lastResetReason)
            val replayed = fakeRegistry.lastResetInitialMessages
            assertEquals(
                "unpaired USER tail must be dropped from initialMessages",
                2,
                replayed.size,
            )
            assertEquals(
                listOf(Role.USER, Role.MODEL),
                replayed.map { it.role },
            )
            assertEquals(
                "kept rows' text must be u1/a1, NOT every entry collapsed to row 0",
                listOf("u1", "a1"),
                replayed.map { msg ->
                    msg.contents.contents.filterIsInstance<Content.Text>().firstOrNull()?.text
                },
            )
            // The dropped USER row must reach the auto-resume path (engine is
            // Ready in this fixture, so observeFirstReadyThenResume fires) —
            // proves the contract is "drop from prefill AND hand to resume",
            // not "drop and silently lose".
            assertEquals(
                "unpaired USER must be auto-resumed as the first turn of the new Conversation",
                1,
                fakeHelper.runInferenceCalls,
            )
        }

    @Test
    fun bootstrapPersistent_emptyInitialMessages_whenChatEmpty() = runTest(dispatcher) {
        // Freshly-created Persistent (chat row exists, no messages yet).
        // The reset must still fire (CHAT_SWITCH semantics — no USER tail
        // to convert into DRAFT_COMMIT) and `initialMessages` is empty.
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeMessageDao.emit(emptyList())
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)

        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        assertEquals(ResetReason.CHAT_SWITCH, fakeRegistry.lastResetReason)
        assertTrue(
            "empty chat must produce empty initialMessages",
            fakeRegistry.lastResetInitialMessages.isEmpty(),
        )
    }

    @Test
    fun applyLightOverrides_passesPairedHistory_inPersistentChat() = runTest(dispatcher) {
        // Persistent slider-Apply must replay paired history: erasing the
        // engine context the user can still see in the message list would
        // be a worse experience than the pre-fix "silent no-op" Light tier.
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeChatDao.put(
            ChatEntity(id = 42L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L),
        )
        fakeMessageDao.emit(
            listOf(
                MessageEntity(id = 1L, chatId = 42L, role = "user", text = "hi", createdAt = 1L),
                MessageEntity(id = 2L, chatId = 42L, role = "assistant", text = "hello", createdAt = 2L),
            ),
        )
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)

        val vm = buildViewModel(ChatIdentityArg.Persistent(42L))
        advanceUntilIdle()

        // Mutate Room AFTER bootstrap has settled. If applyLightOverrides
        // re-uses bootstrap-time cached state instead of re-reading the
        // DAO at apply-time, the assertions below would observe size=2
        // and only the bootstrap text — distinguishable from the post-
        // mutation state.
        fakeMessageDao.emit(
            listOf(
                MessageEntity(id = 1L, chatId = 42L, role = "user", text = "hi", createdAt = 1L),
                MessageEntity(id = 2L, chatId = 42L, role = "assistant", text = "hello", createdAt = 2L),
                MessageEntity(id = 3L, chatId = 42L, role = "user", text = "u2", createdAt = 3L),
                MessageEntity(id = 4L, chatId = 42L, role = "assistant", text = "a2", createdAt = 4L),
            ),
        )
        fakeRepo.save(
            "id-m",
            PerModelSettings.newBuilder().setTemperature(0.4f).build(),
        )
        vm.applyLightOverrides()
        advanceUntilIdle()

        assertEquals(ResetReason.LIGHT_OVERRIDE, fakeRegistry.resetReasons.last())
        val replayed = fakeRegistry.lastResetInitialMessages
        assertEquals(
            "Persistent LIGHT_OVERRIDE must re-read DAO at apply-time, not stale bootstrap state",
            4,
            replayed.size,
        )
        assertEquals(
            listOf(Role.USER, Role.MODEL, Role.USER, Role.MODEL),
            replayed.map { it.role },
        )
        assertEquals(
            "post-bootstrap rows must reach the prefill, proving DAO re-read",
            listOf("hi", "hello", "u2", "a2"),
            replayed.map { msg ->
                msg.contents.contents.filterIsInstance<Content.Text>().firstOrNull()?.text
            },
        )
    }

    @Test
    fun applyLightOverrides_passesEmptyHistory_inQuickChat() = runTest(dispatcher) {
        // Quick has no persistent history — recreating the Conversation
        // with an empty list matches the empty in-memory message list and
        // avoids reading a chatId that doesn't exist for Quick.
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        fakeRepo.save(
            "id-m",
            PerModelSettings.newBuilder().setTemperature(0.4f).build(),
        )
        vm.applyLightOverrides()
        advanceUntilIdle()

        assertEquals(ResetReason.LIGHT_OVERRIDE, fakeRegistry.lastResetReason)
        assertTrue(
            "Quick LIGHT_OVERRIDE must not replay any history",
            fakeRegistry.lastResetInitialMessages.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Post-3.6 Task 14 — Quick / Draft bootstrap reset (DataStore overrides)
    // ------------------------------------------------------------------

    @Test
    fun bootstrapQuick_emitsQuickBootstrapReset_onFirstReady() = runTest(dispatcher) {
        // Symmetric to the Persistent CHAT_SWITCH bootstrap path: Quick chat
        // entry must recreate the Conversation so DataStore overrides — applied
        // to `model.configValues` by `applyEffectiveConfigToModel` — actually
        // reach the engine. Without this the warm Conversation (created in
        // WarmupCoordinator with allowlist defaults) is what answers the first
        // user turn, ignoring any setting the user had persisted.
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)

        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        assertEquals(
            "Quick bootstrap must emit exactly one QUICK_BOOTSTRAP reset on first Ready",
            listOf(ResetReason.QUICK_BOOTSTRAP),
            fakeRegistry.resetReasons,
        )
        assertTrue(
            "Quick has no persistent history — initialMessages must be empty",
            fakeRegistry.lastResetInitialMessages.isEmpty(),
        )
    }

    @Test
    fun bootstrapDraft_emitsQuickBootstrapReset_onFirstReady() = runTest(dispatcher) {
        // Draft is the staging variant of Quick (no Room row yet) — same
        // bootstrap-reset semantics apply: pre-first-send DataStore overrides
        // must reach the engine.
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)

        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()

        assertEquals(
            "Draft bootstrap must emit exactly one QUICK_BOOTSTRAP reset on first Ready",
            listOf(ResetReason.QUICK_BOOTSTRAP),
            fakeRegistry.resetReasons,
        )
        assertTrue(
            "Draft has no committed history yet — initialMessages must be empty",
            fakeRegistry.lastResetInitialMessages.isEmpty(),
        )
    }

    @Test
    fun bootstrapQuick_quickBootstrapReset_waitsForReady() = runTest(dispatcher) {
        // Cold-start race regression guard, mirror of Persistent
        // `bootstrapPersistent_chatSwitchReset_waitsForReady`. Firing the reset
        // before Ready would land on `DefaultModelRegistry`'s non-Ready skip
        // arm and warning-log without recreating Conversation — leaving the
        // first turn under stale sampler.
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.publishEntry(model, ModelInitStatus.Initializing)

        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        assertEquals(
            "reset must defer while engine is Initializing",
            emptyList<ResetReason>(),
            fakeRegistry.resetReasons,
        )

        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        advanceUntilIdle()

        assertEquals(
            listOf(ResetReason.QUICK_BOOTSTRAP),
            fakeRegistry.resetReasons,
        )
    }

    @Test
    fun bootstrapQuick_resetUsesMergedConfigValues() = runTest(dispatcher) {
        // Load-bearing assertion for the actual bug: bootstrap must call
        // `applyEffectiveConfigToModel` BEFORE `observeFirstReadyThenReset`
        // so the reset's `effectiveSystemPrompt(model)` reflects DataStore
        // overrides. A regression that flips the order would publish the
        // allowlist-default system prompt verbatim — caught here.
        val model = Model(
            name = "m", modelId = "id-m",
            configs = createLlmChatConfigs(defaultSystemPrompt = "default-prompt"),
        )
        model.preProcess()
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        fakeRepo.save(
            "id-m",
            PerModelSettings.newBuilder().setSystemPromptDefault("override-prompt").build(),
        )

        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        // Multiplicity guard: a regression that fires the reset twice (e.g.
        // Ready→Initializing→Ready flutter retriggering) would still pass a
        // `lastResetReason` check but break here.
        assertEquals(
            listOf(ResetReason.QUICK_BOOTSTRAP),
            fakeRegistry.resetReasons,
        )
        assertEquals(
            "DataStore override must reach the registry as the reset's systemPrompt",
            "override-prompt",
            fakeRegistry.lastResetSystemPrompt,
        )
    }

    @Test
    fun classifyApplyLevel_returnsHeavy_forMaxTokens() = runTest(dispatcher) {
        // After Decision 4, max_tokens lives in EngineConfig (not
        // ConversationConfig) and must trigger the HEAVY reinit dialog —
        // not the Light no-op path.
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        val target = PerModelSettings.newBuilder().setMaxTokens(2048).build()

        assertTrue(
            "max_tokens delta must be classified HEAVY",
            vm.needsHeavyApply(target),
        )
    }

    @Test
    fun classifyApplyLevel_returnsHeavy_forAccelerator() = runTest(dispatcher) {
        // Baseline Heavy guard — accelerator must remain HEAVY after the
        // Phase 3.6 reclassification of max_tokens (no regression on the
        // pre-existing condition).
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        val target = PerModelSettings.newBuilder()
            .setAccelerator(Accelerator.CPU.label)
            .build()

        assertTrue(
            "accelerator delta must remain HEAVY",
            vm.needsHeavyApply(target),
        )
    }

    @Test
    fun classifyApplyLevel_returnsLight_forTemperature() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        val target = PerModelSettings.newBuilder().setTemperature(0.5f).build()

        assertFalse(
            "temperature delta must NOT be HEAVY",
            vm.needsHeavyApply(target),
        )

        // Drive the LIGHT path through saveAndApplySettings to confirm the
        // dispatch is Light, not Heavy: no cleanup/init, reset tagged
        // LIGHT_OVERRIDE.
        val cleanupBefore = fakeRegistry.cleanupCalls
        val initBefore = fakeRegistry.initializeCalls
        vm.saveAndApplySettings(target)
        advanceUntilIdle()
        assertEquals(cleanupBefore, fakeRegistry.cleanupCalls)
        assertEquals(initBefore, fakeRegistry.initializeCalls)
        assertEquals(ResetReason.LIGHT_OVERRIDE, fakeRegistry.lastResetReason)
    }

    @Test
    fun applyMaxTokens_followsHeavyDialogSequence() = runTest(dispatcher) {
        // Mirrors `applyHeavySetting_sequencing_stopCleanupInitialize`:
        // proves max_tokens now flows through the same stop→cleanup→
        // initialize sequence as accelerator (i.e. via applyHeavySetting),
        // not through a Light-tier silent no-op.
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()
        sharedCalls.clear()

        fakeRepo.save(
            "id-m",
            PerModelSettings.newBuilder().setMaxTokens(2048).build(),
        )

        vm.applyHeavySetting()
        advanceUntilIdle()

        val sequence = sharedCalls.filter {
            it == "stopResponse" || it == "cleanup" || it == "initialize"
        }
        assertEquals(listOf("stopResponse", "cleanup", "initialize"), sequence)
        assertEquals(2048, model.configValues[ConfigKeys.MAX_TOKENS.label])
        assertFalse(vm.reinitInProgress.value)
    }

    @Test
    fun applyHeavySetting_sequencing_stopCleanupInitialize() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()
        sharedCalls.clear()

        fakeRepo.save(
            "id-m",
            PerModelSettings.newBuilder().setAccelerator(Accelerator.CPU.label).build(),
        )

        vm.applyHeavySetting()
        advanceUntilIdle()

        val sequence = sharedCalls.filter {
            it == "stopResponse" || it == "cleanup" || it == "initialize"
        }
        assertEquals(listOf("stopResponse", "cleanup", "initialize"), sequence)
        assertEquals(Accelerator.CPU.label, model.configValues[ConfigKeys.ACCELERATOR.label])
        assertFalse(vm.reinitInProgress.value)
    }

    @Test
    fun applyHeavySetting_initCrash_failedState() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        fakeRepo.save(
            "id-m",
            PerModelSettings.newBuilder().setAccelerator(Accelerator.CPU.label).build(),
        )
        fakeRegistry.initResult = Result.failure(RuntimeException("native init failed"))
        val events = collectSnackbar(vm)

        vm.applyHeavySetting()
        advanceUntilIdle()

        assertTrue(vm.uiState.value is ChatUiState.Failed)
        assertFalse(vm.reinitInProgress.value)
        assertTrue(events.contains(R.string.chat_load_failed_title))
    }

    @Test
    fun applySystemPromptAndReset_resetsWithPrompt() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        vm.send("first turn")
        advanceUntilIdle()
        fakeHelper.lastResultListener?.invoke("ok", true, null, null)
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()

        val events = collectSnackbar(vm)
        fakeRepo.save(
            "id-m",
            PerModelSettings.newBuilder().setSystemPromptDefault("be terse").build(),
        )
        val cleanupBefore = fakeRegistry.cleanupCalls
        val initBefore = fakeRegistry.initializeCalls

        vm.applySystemPromptAndReset()
        advanceUntilIdle()

        assertEquals("be terse", model.configValues[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label])
        assertEquals("be terse", fakeRegistry.lastResetSystemPrompt)
        assertEquals(ResetReason.SYSTEM_PROMPT, fakeRegistry.lastResetReason)
        assertTrue(vm.messages.value.isEmpty())
        assertTrue(vm.attachments.value.isEmpty())
        assertEquals(listOf(R.string.settings_semilight_applied_snackbar), events)
        assertEquals(cleanupBefore, fakeRegistry.cleanupCalls)
        assertEquals(initBefore, fakeRegistry.initializeCalls)
        // Phase 3.6 Task 11: SYSTEM_PROMPT is a fresh-start reset — UI history
        // is cleared above, so prefilling the engine with the prior history
        // would re-introduce the very state the user just elected to wipe.
        assertTrue(
            "SYSTEM_PROMPT must not replay history (semi-light = fresh start)",
            fakeRegistry.lastResetInitialMessages.isEmpty(),
        )
    }

    @Test
    fun resetConversation_clearsAll() = runTest(dispatcher) {
        val model = Model(
            name = "m",
            modelId = "id-m",
            configs = createLlmChatConfigs(defaultSystemPrompt = "be helpful"),
        )
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        vm.send("hi")
        advanceUntilIdle()
        fakeHelper.lastResultListener?.invoke("ok", true, null, null)
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()
        assertNotEquals(0, vm.messages.value.size)
        val cleanupBefore = fakeRegistry.cleanupCalls
        val initBefore = fakeRegistry.initializeCalls

        vm.resetConversation()
        advanceUntilIdle()

        assertTrue(vm.messages.value.isEmpty())
        assertTrue(vm.attachments.value.isEmpty())
        assertEquals("be helpful", fakeRegistry.lastResetSystemPrompt)
        assertEquals(ResetReason.USER, fakeRegistry.lastResetReason)
        assertEquals(cleanupBefore, fakeRegistry.cleanupCalls)
        assertEquals(initBefore, fakeRegistry.initializeCalls)
        // Phase 3.6 Task 11: explicit ↻ tap is a wipe — never replay history.
        assertTrue(
            "USER reset must not replay history (explicit wipe)",
            fakeRegistry.lastResetInitialMessages.isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // Task 10 — TopAppBar state machine (AC-U5/U6/U7, AC-E3/E3b)
    // ------------------------------------------------------------------

    @Test
    fun topAppBarState_draft_returnsDownloadedModels() = runTest(dispatcher) {
        val a = Model(name = "Model A", modelId = "id-a")
        val b = Model(name = "Model B", modelId = "id-b")
        fakeRegistry.publishEntries(
            FakeModelRegistry.Entry(a, ModelInitStatus.Ready, ModelDownloadStatusType.SUCCEEDED),
            FakeModelRegistry.Entry(b, ModelInitStatus.Idle, ModelDownloadStatusType.SUCCEEDED),
        )
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()

        val state = vm.topAppBarState.value
        assertTrue("Draft identity must surface Draft state: $state", state is TopAppBarState.Draft)
        val draft = state as TopAppBarState.Draft
        assertEquals(
            "dropdown must list both downloaded models",
            listOf("id-a", "id-b"),
            draft.models.map { it.model.modelId },
        )
        // Test-reviewer-1 minor: pin currentModelId so a regression that hard-coded it to "" in
        // the Draft branch would flip the dropdown check-mark off the active model (AC-U7).
        assertEquals(
            "currentModelId must track the Ready entry's id for the dropdown check-mark",
            "id-a",
            draft.currentModelId,
        )
    }

    @Test
    fun topAppBarState_draft_emptyList_whenNoDownloaded() = runTest(dispatcher) {
        val a = Model(name = "Model A", modelId = "id-a")
        fakeRegistry.publishEntries(
            FakeModelRegistry.Entry(a, ModelInitStatus.Idle, ModelDownloadStatusType.NOT_DOWNLOADED),
        )
        val vm = buildViewModel(ChatIdentityArg.Draft)
        advanceUntilIdle()

        val state = vm.topAppBarState.value
        assertTrue(state is TopAppBarState.Draft)
        assertEquals(
            "not-downloaded entries must not appear in the picker",
            emptyList<String>(),
            (state as TopAppBarState.Draft).models.map { it.model.modelId },
        )
    }

    @Test
    fun topAppBarState_persistentIdle_returnsFailed() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Idle)
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        assertEquals(
            TopAppBarState.Failed(modelId = "id-m"),
            vm.topAppBarState.value,
        )
    }

    @Test
    fun topAppBarState_persistentInitializing_returnsLoading() = runTest(dispatcher) {
        // Exercises the entry-driven Initializing branch (distinct from the warmupInFlight
        // short-circuit). test-reviewer-1 minor: without this, removing the
        // ModelInitStatus.Initializing arm would only be caught indirectly.
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Initializing)
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        assertEquals(
            TopAppBarState.Loading(modelId = "id-m", modelName = "m"),
            vm.topAppBarState.value,
        )
    }

    @Test
    fun topAppBarState_persistentFailed_returnsFailed() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Failed("boom"))
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        assertEquals(
            TopAppBarState.Failed(modelId = "id-m"),
            vm.topAppBarState.value,
        )
    }

    @Test
    fun topAppBarState_persistentReady_returnsReady() = runTest(dispatcher) {
        val model = Model(name = "Model M", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        assertEquals(
            TopAppBarState.Ready(modelName = "Model M"),
            vm.topAppBarState.value,
        )
    }

    @Test
    fun topAppBarState_warmupInFlight_returnsLoading() = runTest(dispatcher) {
        val model = Model(name = "Model M", modelId = "id-m")
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        assertTrue(
            "seed must surface Ready before the Loading transition",
            vm.topAppBarState.value is TopAppBarState.Ready,
        )

        fakeWarmupCoordinator.isWarmupInProgressState.value = true
        advanceUntilIdle()

        val state = vm.topAppBarState.value
        assertTrue("warmup in flight must collapse to Loading: $state", state is TopAppBarState.Loading)
    }

    @Test
    fun topAppBarState_quick_returnsReady_withActiveName() = runTest(dispatcher) {
        val model = Model(name = "Model M", modelId = "id-m")
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        assertEquals(
            TopAppBarState.Ready(modelName = "Model M"),
            vm.topAppBarState.value,
        )
    }

    // ------------------------------------------------------------------
    // Phase 3.6 / Task 6 — engineReady StateFlow (Bug 2: Settings gating)
    // ------------------------------------------------------------------

    @Test
    fun engineReady_combinatorics() = runTest(dispatcher) {
        // Drives the same VM through the 5 readiness states the Settings
        // IconButton must observe (AC-2.1 / AC-2.3 in the user-spec):
        //   Idle → false; Initializing → false; Failed → false;
        //   Ready + warmup-in-flight → false; Ready + no-warmup → true.
        // Persistent identity is used so `_chatModelId` is pinned by the
        // ChatDao seed (Quick mode would never resolve it because the fake
        // registry leaves `activeModelName = null` for non-Ready entries,
        // and Quick bootstrap suspends until that flow emits).
        val model = Model(name = "Model M", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Idle)
        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        assertEquals("Idle → engineReady=false", false, vm.engineReady.value)

        fakeRegistry.publishEntry(model, ModelInitStatus.Initializing)
        advanceUntilIdle()
        assertEquals("Initializing → engineReady=false", false, vm.engineReady.value)

        fakeRegistry.publishEntry(model, ModelInitStatus.Failed("boom"))
        advanceUntilIdle()
        assertEquals("Failed → engineReady=false", false, vm.engineReady.value)

        // Flip warmup before publishing Ready so the (Ready ∧ warmup) cell is
        // exercised in isolation — without this, the Ready emission would
        // race the warmup flag and briefly observe the (Ready ∧ !warmup) cell.
        fakeWarmupCoordinator.isWarmupInProgressState.value = true
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        advanceUntilIdle()
        assertEquals("Ready+warmup → engineReady=false", false, vm.engineReady.value)

        fakeWarmupCoordinator.isWarmupInProgressState.value = false
        advanceUntilIdle()
        assertEquals("Ready+no-warmup → engineReady=true", true, vm.engineReady.value)

        // 6th cell — registry drops the pinned entry while the chat is open
        // (e.g. transient miss during a rapid model-delete + re-add window).
        // The combine must observe `entry == null` and short-circuit to false.
        // Without this assertion a regression that defaults missing-entry to
        // true (e.g. `entry?.initStatus is Ready ?: true`) would slip through.
        fakeRegistry.publishEntries()
        advanceUntilIdle()
        assertEquals("missing entry → engineReady=false", false, vm.engineReady.value)
    }

    @Test
    fun loadModel_delegatesToWarmupCoordinator() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", modelId = "id-m"))
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()

        vm.loadModel("id-b")
        vm.loadModel("id-c")

        // Each call must record one entry with the caller-supplied id — idempotence collapsing
        // (which a coalescing bug could introduce) would leave only "id-b" or "id-c".
        assertEquals(listOf("id-b", "id-c"), fakeWarmupCoordinator.cancelAndRestartCalls)
    }

    // ---- Task 18 B1 — attachment decode on Room read -----------------------

    @Test
    fun persistentMode_messageWithImagePath_decodesToAttachmentImage() = runTest(dispatcher) {
        // Paired USER+ASSISTANT seed so the B4 auto-resume path (unpaired-last-USER)
        // does not fire — the bubble it emits would otherwise change the
        // messages count and muddle the assertion target (we want to exercise
        // attachment decode only).
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val relativePath = "attachments/7/img_test.png"
        val imageFile = File(context.filesDir, relativePath).apply {
            parentFile?.mkdirs()
            val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fakeMessageDao.emit(
            listOf(
                MessageEntity(
                    id = 1L, chatId = 7L, role = "user", text = "hi",
                    imagePath = relativePath, createdAt = 10L,
                ),
                MessageEntity(
                    id = 2L, chatId = 7L, role = "assistant", text = "ok",
                    createdAt = 20L,
                ),
            ),
        )

        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        val userMsg = vm.messages.value.first { it.role == MessageRole.USER }
        assertEquals("decode must produce one Attachment.Image", 1, userMsg.attachments.size)
        assertTrue(userMsg.attachments.single() is Attachment.Image)
        imageFile.delete()
    }

    @Test
    fun persistentMode_messageWithAudioPath_decodesToAttachmentAudio() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val relativePath = "attachments/7/audio_test.wav"
        val audioFile = File(context.filesDir, relativePath).apply {
            parentFile?.mkdirs()
            // 16 kHz × 1 s × 2 bytes = 32 000 PCM bytes → durationMs = 1000.
            val pcm = ByteArray(32_000)
            writeBytes(
                app.sanctum.machina.core.common.pcmToWav(pcm, 16_000),
            )
        }
        fakeMessageDao.emit(
            listOf(
                MessageEntity(
                    id = 1L, chatId = 7L, role = "user", text = "hi",
                    audioPath = relativePath, createdAt = 10L,
                ),
                MessageEntity(
                    id = 2L, chatId = 7L, role = "assistant", text = "ok",
                    createdAt = 20L,
                ),
            ),
        )

        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        val userMsg = vm.messages.value.first { it.role == MessageRole.USER }
        assertEquals(1, userMsg.attachments.size)
        val audio = userMsg.attachments.single() as Attachment.Audio
        assertEquals("1 s of 16-bit mono @ 16 kHz must report durationMs=1000", 1000L, audio.durationMs)
        assertEquals(32_000, audio.pcm.size)
        audioFile.delete()
    }

    @Test
    fun persistentMode_messageWithMissingFile_attachmentOmittedAndLogged() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        val errorLogFile = File(context.filesDir, "logs/errors.log")
        errorLogFile.parentFile?.deleteRecursively()
        fakeMessageDao.emit(
            listOf(
                MessageEntity(
                    id = 1L, chatId = 7L, role = "user", text = "ghost",
                    imagePath = "attachments/7/img_ghost.png", createdAt = 10L,
                ),
                MessageEntity(
                    id = 2L, chatId = 7L, role = "assistant", text = "ok",
                    createdAt = 20L,
                ),
            ),
        )

        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        val userMsg = vm.messages.value.first { it.role == MessageRole.USER }
        assertEquals("ghost", userMsg.text)
        assertEquals(
            "missing attachment file → attachment omitted, not crashed",
            0,
            userMsg.attachments.size,
        )
        // B1-AC3 requires a log entry under the `attachment-read` component —
        // bare-absent attachment would pass the size assertion even if the
        // decode branch were removed entirely.
        val deadlineMs = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadlineMs) {
            if (errorLogFile.exists() && errorLogFile.readText().contains("attachment-read")) break
            Thread.sleep(20)
        }
        assertTrue(
            "expected errors.log entry under 'attachment-read' component; got: " +
                (if (errorLogFile.exists()) errorLogFile.readText() else "<no log file>"),
            errorLogFile.exists() && errorLogFile.readText().contains("attachment-read"),
        )
    }

    @Test
    fun persistentMode_imagePathOutsideAttachmentsRoot_throwsAndOmits() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        // Plant a decoy PNG outside `filesDir/attachments/` — if the containment
        // check is removed or canonicalised incorrectly, BitmapFactory would
        // successfully decode this decoy and the assertion below would fail.
        // Keeps the test from passing trivially due to "file does not exist"
        // (test-reviewer-1 T18-TEST-1).
        val decoy = File(context.filesDir, "secret.png").apply {
            parentFile?.mkdirs()
            val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Traversal escape — the resolved path canonicalises outside filesDir/attachments/.
        fakeMessageDao.emit(
            listOf(
                MessageEntity(
                    id = 1L, chatId = 7L, role = "user", text = "traversal",
                    imagePath = "../secret.png", createdAt = 10L,
                ),
                MessageEntity(
                    id = 2L, chatId = 7L, role = "assistant", text = "ok",
                    createdAt = 20L,
                ),
            ),
        )

        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        val userMsg = vm.messages.value.first { it.role == MessageRole.USER }
        assertEquals(
            "path outside attachments root must not yield an attachment even when a decoy exists",
            0,
            userMsg.attachments.size,
        )
        assertEquals("traversal", userMsg.text)
        decoy.delete()
    }

    // ---- Task 18 B4 — persistent auto-resume --------------------------------

    @Test
    fun persistentMode_lastMessageIsUnpairedUser_autoResumesInference_onFirstReady() =
        runTest(dispatcher) {
            val model = Model(name = "m", modelId = "id-m")
            fakeChatDao.put(
                ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L),
            )
            val userRow = MessageEntity(
                id = 1L, chatId = 7L, role = "user", text = "привет",
                createdAt = 10L,
            )
            fakeMessageDao.emit(listOf(userRow))
            fakeRegistry.publishEntry(model, ModelInitStatus.Ready)

            val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
            advanceUntilIdle()

            assertEquals(
                "B4: unpaired USER row must auto-dispatch runInference once engine is Ready",
                1,
                fakeHelper.runInferenceCalls,
            )
            // USER row must NOT be re-persisted — it already exists in Room.
            val userInserts = fakeChatRepository.insertedMessages.count { it.role == "user" }
            assertEquals(
                "B4-AC3: USER must not be duplicated on auto-resume",
                0,
                userInserts,
            )
        }

    @Test
    fun persistentMode_autoResume_decodesAttachmentsAndPassesToInference() = runTest(dispatcher) {
        // Draft→Persistent first-send with an image must forward the bitmap to
        // the model on auto-resume — without this, the first USER turn in a
        // brand-new persistent chat answered text-only even when an image was
        // attached (user report post-Task-18 round 1).
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        val relativePath = "attachments/7/img_test.png"
        val imageFile = File(context.filesDir, relativePath).apply {
            parentFile?.mkdirs()
            val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
            outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fakeMessageDao.emit(
            listOf(
                MessageEntity(
                    id = 1L, chatId = 7L, role = "user", text = "what's this?",
                    imagePath = relativePath, createdAt = 10L,
                ),
            ),
        )
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)

        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        assertEquals("auto-resume must dispatch inference", 1, fakeHelper.runInferenceCalls)
        assertEquals(
            "decoded image bitmap must be forwarded to runInference",
            1,
            fakeHelper.lastImages.size,
        )
        imageFile.delete()
    }

    @Test
    fun persistentMode_lastMessageIsAssistant_doesNotAutoResume() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeMessageDao.emit(
            listOf(
                MessageEntity(id = 1L, chatId = 7L, role = "user", text = "q", createdAt = 10L),
                MessageEntity(id = 2L, chatId = 7L, role = "assistant", text = "a", createdAt = 20L),
            ),
        )
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)

        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()

        assertEquals(
            "Paired USER+ASSISTANT must not trigger auto-resume",
            0,
            fakeHelper.runInferenceCalls,
        )
    }

    @Test
    fun persistentMode_autoResumeTwiceOnRepeatedReady_onlyOneDispatch() = runTest(dispatcher) {
        val model = Model(name = "m", modelId = "id-m")
        fakeChatDao.put(ChatEntity(id = 7L, modelId = "id-m", createdAt = 0L, lastMessageAt = 0L))
        fakeMessageDao.emit(
            listOf(
                MessageEntity(id = 1L, chatId = 7L, role = "user", text = "q", createdAt = 10L),
            ),
        )
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)

        val vm = buildViewModel(ChatIdentityArg.Persistent(7L))
        advanceUntilIdle()
        assertEquals(1, fakeHelper.runInferenceCalls)

        // Flutter Ready → Initializing → Ready again (heavy-setting reinit pattern).
        fakeRegistry.publishEntry(model, ModelInitStatus.Initializing)
        advanceUntilIdle()
        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        advanceUntilIdle()

        assertEquals(
            "autoResumeAttempted must gate against double-dispatch across Ready flutters",
            1,
            fakeHelper.runInferenceCalls,
        )
    }

    // ---- helpers -------------------------------------------------------

    private sealed class ChatIdentityArg {
        object Quick : ChatIdentityArg()
        object Draft : ChatIdentityArg()
        data class Persistent(val id: Long) : ChatIdentityArg()
    }

    private fun buildViewModel(identity: ChatIdentityArg): ChatViewModel {
        val savedState = SavedStateHandle(
            when (identity) {
                ChatIdentityArg.Quick -> mapOf(ChatViewModel.NAV_ARG_KIND to ChatViewModel.KIND_QUICK)
                ChatIdentityArg.Draft -> mapOf(ChatViewModel.NAV_ARG_KIND to ChatViewModel.KIND_DRAFT)
                is ChatIdentityArg.Persistent -> mapOf(ChatViewModel.NAV_ARG_CHAT_ID to identity.id)
            }
        )
        return ChatViewModel(
            savedStateHandle = savedState,
            registry = fakeRegistry,
            helper = fakeHelper,
            errorLog = ErrorLog(context),
            context = context,
            imageDecoder = fakeDecoder,
            settingsRepository = fakeRepo,
            chatRepository = fakeChatRepository,
            messageDao = fakeMessageDao,
            chatDao = fakeChatDao,
            warmupCoordinator = fakeWarmupCoordinator,
        )
    }

    private fun uri(s: String): Uri = Uri.parse("content://test/$s")

    private fun stubBitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    private fun TestScope.collectSnackbar(vm: ChatViewModel): MutableList<Int> {
        val events = mutableListOf<Int>()
        backgroundScope.launch { vm.snackbar.collect { events.add(it) } }
        return events
    }
}

private class FakeImageDecoder : ImageDecoder {
    val nullFor: MutableSet<String> = mutableSetOf()
    val decodedUris: MutableList<Uri> = mutableListOf()

    override suspend fun decode(uri: Uri): Bitmap? {
        decodedUris += uri
        val last = uri.lastPathSegment ?: return null
        if (last in nullFor) return null
        return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    }
}

private class FakeAppSettingsRepository : AppSettingsRepository {
    private val store = MutableStateFlow<PerModelSettings?>(null)
    private val defaultModelId = MutableStateFlow("")
    private val lastUsedModelId = MutableStateFlow("")
    private var migrated = false

    fun save(modelId: String, settings: PerModelSettings) {
        store.value = settings
    }

    override fun observePerModelSettings(modelId: String): Flow<PerModelSettings?> = store

    override suspend fun savePerModelSettings(modelId: String, settings: PerModelSettings) {
        store.value = settings
    }

    override suspend fun resetPerModelSettings(modelId: String) {
        store.value = null
    }

    override suspend fun getDefaultModelId(): String = defaultModelId.value
    override suspend fun setDefaultModelId(id: String) { defaultModelId.value = id }
    override fun observeDefaultModelId(): Flow<String> = defaultModelId
    override suspend fun getLastUsedModelId(): String = lastUsedModelId.value
    override suspend fun setLastUsedModelId(id: String) { lastUsedModelId.value = id }
    override fun observeLastUsedModelId(): Flow<String> = lastUsedModelId
    override suspend fun isSettingsMigrated(): Boolean = migrated
    override suspend fun markSettingsMigrated() { migrated = true }
}

private class FakeModelRegistry(
    private val sharedCalls: MutableList<String>,
) : ModelRegistry {
    var initResult: Result<Unit> = Result.success(Unit)
    var lastResetSystemPrompt: String? = null
    var lastResetReason: ResetReason? = null
    var lastResetInitialMessages: List<LitertlmMessage> = emptyList()
    val resetReasons: MutableList<ResetReason> = mutableListOf()
    var cleanupCalls = 0
    var initializeCalls = 0

    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    override val models: StateFlow<List<ModelEntry>> = _models

    private val _activeModelName = MutableStateFlow<String?>(null)
    override val activeModelName: StateFlow<String?> = _activeModelName.asStateFlow()

    fun setModel(model: Model) {
        publishEntry(model, ModelInitStatus.Ready)
    }

    fun publishEntry(model: Model, status: ModelInitStatus) {
        _models.value = listOf(
            ModelEntry(
                model = model,
                downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
                initStatus = status,
            )
        )
        _activeModelName.value = if (status === ModelInitStatus.Ready) model.modelId else null
    }

    data class Entry(
        val model: Model,
        val initStatus: ModelInitStatus,
        val downloadStatus: ModelDownloadStatusType,
    )

    /** Publish multiple model entries at once — used by Draft-picker tests (Task 10). */
    fun publishEntries(vararg entries: Entry) {
        _models.value = entries.map { e ->
            ModelEntry(
                model = e.model,
                downloadStatus = ModelDownloadStatus(status = e.downloadStatus),
                initStatus = e.initStatus,
            )
        }
        _activeModelName.value = entries
            .firstOrNull { it.initStatus === ModelInitStatus.Ready }
            ?.model
            ?.modelId
    }

    override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
    override fun download(model: Model): Flow<ModelDownloadStatus> = emptyFlow()
    override fun cancelDownload(modelName: String) {}
    override suspend fun delete(modelName: String) {}
    override suspend fun initialize(modelName: String): Result<Unit> {
        initializeCalls += 1
        sharedCalls += "initialize"
        return initResult
    }
    override suspend fun cleanup(modelName: String) {
        cleanupCalls += 1
        sharedCalls += "cleanup"
    }
    override suspend fun resetConversation(
        modelName: String,
        systemPrompt: String?,
        reason: ResetReason,
        initialMessages: List<LitertlmMessage>,
    ) {
        lastResetSystemPrompt = systemPrompt
        lastResetReason = reason
        lastResetInitialMessages = initialMessages
        resetReasons += reason
        sharedCalls += "resetConversation"
    }
    override fun getModel(modelName: String): Model? =
        _models.value.firstOrNull { it.model.name == modelName }?.model
}

private class FakeLlmHelper(
    private val sharedCalls: MutableList<String>,
) : LlmModelHelper {
    var lastResultListener: ResultListener? = null
    var lastExtraContext: Map<String, String>? = null
    var lastImages: List<Bitmap> = emptyList()
    var lastAudioClips: List<ByteArray> = emptyList()
    var runInferenceCalls = 0

    override fun initialize(
        context: Context,
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        onDone: (String) -> Unit,
        systemInstruction: Contents?,
        tools: List<ToolProvider>,
        enableConversationConstrainedDecoding: Boolean,
        coroutineScope: CoroutineScope?,
    ) {}

    override fun resetConversation(
        model: Model,
        supportImage: Boolean,
        supportAudio: Boolean,
        systemInstruction: Contents?,
        tools: List<ToolProvider>,
        enableConversationConstrainedDecoding: Boolean,
        initialMessages: List<LitertlmMessage>,
    ) {}

    override fun cleanUp(model: Model, onDone: () -> Unit) { onDone() }

    override fun runInference(
        model: Model,
        input: String,
        resultListener: ResultListener,
        cleanUpListener: CleanUpListener,
        onError: (message: String) -> Unit,
        images: List<Bitmap>,
        audioClips: List<ByteArray>,
        coroutineScope: CoroutineScope?,
        extraContext: Map<String, String>?,
    ) {
        runInferenceCalls += 1
        sharedCalls += "runInference"
        lastResultListener = resultListener
        lastExtraContext = extraContext
        lastImages = images
        lastAudioClips = audioClips
    }

    override fun stopResponse(model: Model) {
        sharedCalls += "stopResponse"
    }
}

private class FakeMessageDao : MessageDao {
    private val observed = MutableStateFlow<List<MessageEntity>>(emptyList())
    val inserted = mutableListOf<MessageEntity>()
    var observeCalls = 0
        private set

    fun emit(list: List<MessageEntity>) { observed.value = list }

    override suspend fun insert(message: MessageEntity): Long {
        inserted += message
        return (inserted.size).toLong()
    }

    override suspend fun deleteById(id: Long) {}

    override suspend fun getByChatId(chatId: Long): List<MessageEntity> =
        observed.value.filter { it.chatId == chatId }

    override fun observeByChat(chatId: Long): Flow<List<MessageEntity>> {
        observeCalls += 1
        return observed.map { list -> list.filter { it.chatId == chatId } }
    }

    override suspend fun countByChatId(chatId: Long): Int =
        observed.value.count { it.chatId == chatId }

    override suspend fun firstByChatIdAndRole(chatId: Long, role: String): MessageEntity? =
        observed.value.firstOrNull { it.chatId == chatId && it.role == role }

    override suspend fun lastByChat(chatId: Long): MessageEntity? =
        observed.value.filter { it.chatId == chatId }
            .maxWithOrNull(compareBy({ it.createdAt }, { it.id }))

    override suspend fun observeCitedMessagesPageByProject(
        projectId: Long,
        offset: Int,
        limit: Int,
    ): List<MessageEntity> = emptyList()

    override suspend fun updateCitations(messageId: Long, citationsJson: String?) {
        // no-op fake — citation maintenance covered by integration tests
    }
}

private class FakeChatDao : ChatDao {
    private val store = mutableMapOf<Long, ChatEntity>()

    fun put(entity: ChatEntity) { store[entity.id] = entity }

    override suspend fun insert(chat: ChatEntity): Long {
        val id = if (chat.id == 0L) (store.keys.maxOrNull() ?: 0L) + 1 else chat.id
        store[id] = chat.copy(id = id)
        return id
    }

    override suspend fun update(chat: ChatEntity) { store[chat.id] = chat }

    override suspend fun deleteById(id: Long) { store.remove(id) }

    override suspend fun getById(id: Long): ChatEntity? = store[id]

    override fun observeAll(): Flow<List<ChatEntity>> = MutableStateFlow(store.values.toList())
}

private class FakeChatRepository : ChatRepository {
    data class CommitCall(
        val modelId: String,
        val firstMessage: MessageEntity,
        val stagingDir: File?,
        val stagedImageFilename: String?,
        val stagedAudioFilename: String?,
    )

    data class PersistentAttachmentCall(
        val chatId: Long,
        val attachment: Attachment,
    )

    val commitCalls = mutableListOf<CommitCall>()
    val insertedMessages = mutableListOf<MessageEntity>()
    val stagingWrites = mutableListOf<Attachment>()
    val persistentAttachmentCalls = mutableListOf<PersistentAttachmentCall>()
    var nextChatId: Long = 1L
    var writeStagingError: Throwable? = null
    var savePersistentAttachmentError: Throwable? = null

    /** Counter used to generate unique staged filenames for image/audio. */
    private var stagedImageIndex = 0
    private var stagedAudioIndex = 0

    /**
     * Optional cross-fake ordering log. Wiring this to the same list used by
     * `FakeLlmHelper.runInference` lets tests assert AC-R1 — the USER row is
     * persisted BEFORE the engine starts streaming.
     */
    var eventLog: MutableList<String>? = null

    override suspend fun commitDraftChat(
        modelId: String,
        firstMessage: MessageEntity,
        stagingDir: File?,
        filesDir: File,
        stagedImageFilename: String?,
        stagedAudioFilename: String?,
    ): Long {
        eventLog?.add("commitDraftChat")
        commitCalls += CommitCall(
            modelId = modelId,
            firstMessage = firstMessage,
            stagingDir = stagingDir,
            stagedImageFilename = stagedImageFilename,
            stagedAudioFilename = stagedAudioFilename,
        )
        return nextChatId
    }

    override suspend fun writeAttachmentStaging(
        stagingDir: File,
        filesDir: File,
        attachment: Attachment,
    ): String {
        writeStagingError?.let { throw it }
        stagingWrites += attachment
        return when (attachment) {
            is Attachment.Image -> "img_${stagedImageIndex++}.png"
            is Attachment.Audio -> "audio_${stagedAudioIndex++}.wav"
        }
    }

    /**
     * Records a staged filename for later assertion by tests exercising the
     * remove path. Keeps order so a test can pin which attachment was
     * targeted when multiple are staged.
     */
    val deletedStagedFiles = mutableListOf<String>()

    override suspend fun deleteStagedAttachment(
        stagingDir: File,
        filesDir: File,
        filename: String,
    ) {
        deletedStagedFiles += filename
    }

    val pruneRetainLog = mutableListOf<Set<String>>()

    override suspend fun pruneStagingDir(
        stagingDir: File,
        filesDir: File,
        retain: Set<String>,
    ) {
        pruneRetainLog += retain
    }

    private var persistentImageIndex = 0
    private var persistentAudioIndex = 0

    override suspend fun savePersistentAttachment(
        chatId: Long,
        filesDir: File,
        attachment: Attachment,
    ): PersistedAttachment {
        savePersistentAttachmentError?.let { throw it }
        persistentAttachmentCalls += PersistentAttachmentCall(chatId, attachment)
        return when (attachment) {
            is Attachment.Image -> PersistedAttachment(
                imagePath = "attachments/$chatId/img_${persistentImageIndex++}.png",
            )
            is Attachment.Audio -> PersistedAttachment(
                audioPath = "attachments/$chatId/audio_${persistentAudioIndex++}.wav",
            )
        }
    }

    /**
     * If non-null, the next `savePersistentMessage` call throws this — used to
     * exercise the AC-R1 hard-gate path where USER persistence fails and
     * inference must NOT run.
     */
    var savePersistentMessageError: Throwable? = null

    override suspend fun savePersistentMessage(message: MessageEntity) {
        eventLog?.add("savePersistentMessage")
        savePersistentMessageError?.let { throw it }
        insertedMessages += message
    }

    override suspend fun updateChatLastMessage(chatId: Long, timestampMs: Long) {}

    override suspend fun updateChatTitle(chatId: Long, title: String, isManuallyTitled: Boolean) {}

    override suspend fun deleteChat(chatId: Long, filesDir: File) {}

    override fun observeChats(): Flow<List<ChatEntity>> = emptyFlow()

    override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = emptyFlow()

    override suspend fun sweepZombieChats(filesDir: File) {}
}

/**
 * Stub extending the production [WarmupCoordinator] (class is `open` for this purpose). The real
 * scope / mutex / observer is pre-started by the parent init block, but its inputs (a fake
 * registry with no models and a fake AppSettingsRepository with empty ids) idle immediately, so
 * construction has no side effect relevant to the tests. Tests drive state through
 * [isWarmupInProgressState] and record [cancelAndRestartCalls].
 */
private class FakeWarmupCoordinator(
    private val ownJob: kotlinx.coroutines.CompletableJob,
) : WarmupCoordinator(
    registry = FakeModelRegistry(mutableListOf()),
    appSettings = FakeAppSettingsRepository(),
    errorLog = ErrorLog(ApplicationProvider.getApplicationContext<Application>()),
    // Parent init launches `startDefaultModelObserver`; give it a dedicated cancellable scope
    // so each test tears the collector down in @After (test-reviewer-1 minor on resource leak).
    scope = CoroutineScope(ownJob + Dispatchers.Unconfined),
) {
    val isWarmupInProgressState = MutableStateFlow(false)
    val cancelAndRestartCalls = mutableListOf<String>()

    override val isWarmupInProgress: StateFlow<Boolean> = isWarmupInProgressState.asStateFlow()

    override fun cancelAndRestart(modelId: String) {
        cancelAndRestartCalls += modelId
    }

    fun shutdown() { ownJob.cancel() }
}

private object ViewModelReflection {
    fun onCleared(vm: ChatViewModel): java.lang.reflect.Method {
        val cls = androidx.lifecycle.ViewModel::class.java
        val m = cls.getDeclaredMethod("onCleared")
        m.isAccessible = true
        return m
    }
}
