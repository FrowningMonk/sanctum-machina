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
import com.google.ai.edge.litertlm.Contents
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
        fakeHelper.lastResultListener?.invoke("ok", true, null)
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
        listener.invoke("partial", false, null)
        advanceUntilIdle()
        assertTrue(
            "during streaming, the in-memory bubble must be visible",
            emissions.any { snap -> snap.any { m -> m.role == MessageRole.ASSISTANT && m.streaming } },
        )

        // Engine signals done — VM persists ASSISTANT; simulate Room re-emitting.
        listener.invoke("", true, null)
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

        listener.invoke("hel", false, null)
        listener.invoke("lo", false, null)
        advanceUntilIdle()
        assertEquals(
            "AC-R2: streaming tokens must not trigger intermediate ASSISTANT writes",
            0,
            fakeChatRepository.insertedMessages.count { it.role == "assistant" },
        )

        listener.invoke("", true, null)
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
        listener.invoke("partial", false, null)
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
        listener.invoke("hello", false, "thought-1 ")
        listener.invoke(" world", false, "thought-2")
        listener.invoke("", true, null)

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
    fun applyLightOverrides_updatesConfigValues_noCleanup() = runTest(dispatcher) {
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
        assertEquals(cleanupCountBefore, fakeRegistry.cleanupCalls)
        assertEquals(initCountBefore, fakeRegistry.initializeCalls)
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
        fakeHelper.lastResultListener?.invoke("ok", true, null)
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
        assertTrue(vm.messages.value.isEmpty())
        assertTrue(vm.attachments.value.isEmpty())
        assertEquals(listOf(R.string.settings_semilight_applied_snackbar), events)
        assertEquals(cleanupBefore, fakeRegistry.cleanupCalls)
        assertEquals(initBefore, fakeRegistry.initializeCalls)
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
        fakeHelper.lastResultListener?.invoke("ok", true, null)
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
        assertEquals(cleanupBefore, fakeRegistry.cleanupCalls)
        assertEquals(initBefore, fakeRegistry.initializeCalls)
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
    override suspend fun resetConversation(modelName: String, systemPrompt: String?) {
        lastResetSystemPrompt = systemPrompt
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

private object ViewModelReflection {
    fun onCleared(vm: ChatViewModel): java.lang.reflect.Method {
        val cls = androidx.lifecycle.ViewModel::class.java
        val m = cls.getDeclaredMethod("onCleared")
        m.isAccessible = true
        return m
    }
}
