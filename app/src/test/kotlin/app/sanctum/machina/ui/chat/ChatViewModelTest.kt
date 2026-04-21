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
        val model = Model(name = "m", modelId = "id-m")
        fakeRegistry.publishEntry(model, ModelInitStatus.Initializing)
        val vm = buildViewModel(ChatIdentityArg.Quick)
        advanceUntilIdle()
        assertEquals(ChatUiState.Loading, vm.uiState.value)

        fakeRegistry.publishEntry(model, ModelInitStatus.Ready)
        advanceUntilIdle()

        assertEquals(
            "Ready must surface Ready(isGenerating=false)",
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
            id = 1L,
            chatId = 7L,
            role = "user",
            text = "hi",
            createdAt = 10L,
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

        // Stream a token — display should show USER + streaming ASSISTANT.
        listener.invoke("partial", false, null)
        advanceUntilIdle()
        assertTrue(
            "during streaming, the in-memory bubble must be visible",
            emissions.any { it.any { m -> m.role == MessageRole.ASSISTANT && m.streaming } },
        )

        // Engine signals done. The VM persists ASSISTANT; simulate the Room
        // flow re-emitting with the new row. The atomic handover must ensure
        // no displayMessages emission carries both representations.
        listener.invoke("", true, null)
        advanceUntilIdle()
        val assistantRow = MessageEntity(
            id = 2L,
            chatId = 7L,
            role = "assistant",
            text = "partial",
            createdAt = 20L,
        )
        fakeMessageDao.emit(listOf(userRow, assistantRow))
        advanceUntilIdle()

        for (snapshot in emissions) {
            val persistedEndsWithAssistant = snapshot.count { it.role == MessageRole.ASSISTANT } >= 1 &&
                snapshot.last().role == MessageRole.ASSISTANT
            // A "streaming-only" bubble is a non-streaming assistant next to
            // a persisted assistant — we key the invariant on `streaming=true`
            // since that is the in-memory flag exclusive to `_streamingMessage`.
            val hasInMemoryStreaming = snapshot.any { it.role == MessageRole.ASSISTANT && it.streaming }
            val bothPresent = persistedEndsWithAssistant && hasInMemoryStreaming &&
                snapshot.count { it.role == MessageRole.ASSISTANT } > 1
            assertFalse(
                "atomic handover broken: both persisted ASSISTANT and streaming bubble present",
                bothPresent,
            )
        }
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
    )

    val commitCalls = mutableListOf<CommitCall>()
    val insertedMessages = mutableListOf<MessageEntity>()
    var nextChatId: Long = 1L

    override suspend fun commitDraftChat(
        modelId: String,
        firstMessage: MessageEntity,
        stagingDir: File?,
        filesDir: File,
    ): Long {
        commitCalls += CommitCall(modelId, firstMessage, stagingDir)
        return nextChatId
    }

    override suspend fun savePersistentMessage(message: MessageEntity) {
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
