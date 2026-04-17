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
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ToolProvider
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun addImages_belowLimit_addsAll() = runTest(dispatcher) {
        val vm = buildViewModel()
        val uris = listOf(uri("a"), uri("b"), uri("c"))

        vm.addImages(uris)
        advanceUntilIdle()

        assertEquals(3, vm.attachments.value.size)
        assertTrue(vm.attachments.value.all { it is Attachment.Image })
    }

    @Test
    fun addImages_exceedsLimit_clipsToTen() = runTest(dispatcher) {
        val vm = buildViewModel()
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
        val vm = buildViewModel()
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
        val vm = buildViewModel()
        repeat(3) { vm.addImageBitmap(stubBitmap()) }
        advanceUntilIdle()

        vm.removeAttachment(0)

        assertEquals(2, vm.attachments.value.size)
    }

    @Test
    fun removeAttachment_invalidIdx_noCrash() = runTest(dispatcher) {
        val vm = buildViewModel()
        repeat(2) { vm.addImageBitmap(stubBitmap()) }
        advanceUntilIdle()

        vm.removeAttachment(-1)
        vm.removeAttachment(99)

        assertEquals(2, vm.attachments.value.size)
    }

    @Test
    fun addImages_decoderReturnsNull_skipsAndDoesNotCrash() = runTest(dispatcher) {
        fakeDecoder.nullFor.add("broken")
        val vm = buildViewModel()

        vm.addImages(listOf(uri("broken"), uri("ok")))
        advanceUntilIdle()

        val attachments = vm.attachments.value
        assertEquals(1, attachments.size)
        assertTrue(
            "surviving attachment must be an Image (not a placeholder)",
            attachments.single() is Attachment.Image,
        )
        assertEquals(
            "both URIs should have been attempted",
            listOf(uri("broken"), uri("ok")),
            fakeDecoder.decodedUris,
        )
    }

    @Test
    fun modelCaps_reflectInitializedModelSupport() = runTest(dispatcher) {
        fakeRegistry.setModel(
            Model(
                name = "m",
                llmSupportImage = true,
                llmSupportAudio = true,
                llmSupportThinking = false,
            )
        )

        val vm = buildViewModel()
        advanceUntilIdle()

        assertTrue(vm.modelCaps.value.supportImage)
        assertTrue(vm.modelCaps.value.supportAudio)
        assertFalse(vm.modelCaps.value.supportThinking)
    }

    @Test
    fun send_transfersPendingAttachmentsIntoUserMessageAndClearsStaging() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", llmSupportImage = true))
        val vm = buildViewModel()
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
        fakeRegistry.setModel(Model(name = "m", llmSupportImage = true))
        val vm = buildViewModel()
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
        val vm = buildViewModel()
        advanceUntilIdle()

        val pcm = byteArrayOf(1, 2, 3, 4)
        vm.addAudio(pcm, durationMs = 1234L)
        advanceUntilIdle()

        val audio = vm.attachments.value.single() as Attachment.Audio
        assertSame("pcm must be stored by reference, not copied", pcm, audio.pcm)
        assertEquals(1234L, audio.durationMs)
    }

    @Test
    fun addAudio_alreadyHasAudio_isNoOp() = runTest(dispatcher) {
        val vm = buildViewModel()
        advanceUntilIdle()
        val firstPcm = byteArrayOf(9)
        vm.addAudio(firstPcm, durationMs = 1_000L)
        advanceUntilIdle()

        vm.addAudio(byteArrayOf(10, 11), durationMs = 2_000L)
        advanceUntilIdle()

        val audio = vm.attachments.value.single() as Attachment.Audio
        assertEquals(1_000L, audio.durationMs)
        assertSame("first clip must survive by reference", firstPcm, audio.pcm)
    }

    @Test
    fun addAudio_coexistsWithImages() = runTest(dispatcher) {
        val vm = buildViewModel()
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
        val vm = buildViewModel()
        advanceUntilIdle()
        val events = collectSnackbar(vm)

        vm.reportAudioError(
            "audio record init failed",
            IllegalStateException("mic busy"),
        )
        advanceUntilIdle()

        assertEquals(
            listOf(R.string.audio_record_init_failed),
            events,
        )
    }

    @Test
    fun send_transfersAudioAttachmentAndClears() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m", llmSupportAudio = true))
        val vm = buildViewModel()
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
        val vm = buildViewModel()
        advanceUntilIdle()
        val events = collectSnackbar(vm)

        vm.reportCameraError("camera bind failed", IllegalStateException("provider unavailable"))
        advanceUntilIdle()

        assertEquals(
            listOf(R.string.camera_init_failed),
            events,
        )
    }

    @Test
    fun modelCaps_initFails_keepsDefaultCaps() = runTest(dispatcher) {
        fakeRegistry.initResult = Result.failure(IOException("boom"))
        fakeRegistry.setModel(
            Model(
                name = "m",
                llmSupportImage = true,
                llmSupportAudio = true,
                llmSupportThinking = true,
            )
        )

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(ModelCapabilities(), vm.modelCaps.value)
        assertTrue(vm.uiState.value is ChatUiState.Failed)
    }

    // --- Task 11: state-machine TDD anchors -----------------------------------

    @Test
    fun send_emptyTextEmptyAttachments_noInference() = runTest(dispatcher) {
        fakeRegistry.setModel(Model(name = "m"))
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.send("   ")  // whitespace-only, no attachments
        advanceUntilIdle()

        assertEquals(0, fakeHelper.runInferenceCalls)
        assertTrue(vm.messages.value.isEmpty())
    }

    @Test
    fun send_thinkingEnabled_accumulates() = runTest(dispatcher) {
        val model = Model(
            name = "m",
            llmSupportThinking = true,
            configs = createLlmChatConfigs(supportThinking = true),
        )
        model.preProcess()
        fakeRegistry.setModel(model)
        fakeRepo.save(
            "m",
            PerModelSettings.newBuilder().setEnableThinking(true).build(),
        )
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.send("think please")
        advanceUntilIdle()

        val listener = fakeHelper.lastResultListener
            ?: error("send must invoke runInference")
        listener.invoke("hello", false, "thought-1 ")
        listener.invoke(" world", false, "thought-2")
        listener.invoke("", true, null)

        val assistant = vm.messages.value.last { it.role == MessageRole.ASSISTANT }
        assertEquals("hello world", assistant.text)
        assertEquals("thought-1 thought-2", assistant.thinkingText)
    }

    @Test
    fun send_thinkingDisabled_skips() = runTest(dispatcher) {
        val model = Model(
            name = "m",
            llmSupportThinking = true,
            configs = createLlmChatConfigs(supportThinking = true),
        )
        model.preProcess()
        fakeRegistry.setModel(model)
        // Default for ENABLE_THINKING in createLlmChatConfigs is false; no override needed.
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()
        val listener = fakeHelper.lastResultListener
            ?: error("send must invoke runInference")
        listener.invoke("ok", false, "thinking ignored")
        listener.invoke("", true, null)

        val assistant = vm.messages.value.last { it.role == MessageRole.ASSISTANT }
        assertEquals("ok", assistant.text)
        assertEquals(
            "thinkingText must stay null when enable_thinking is false",
            null,
            assistant.thinkingText,
        )
    }

    @Test
    fun send_llmSupportThinkingFalse_skips() = runTest(dispatcher) {
        // Even with supportThinking=true configs and an enable_thinking=true
        // override, the model.llmSupportThinking flag must veto accumulation.
        val model = Model(
            name = "m",
            llmSupportThinking = false,
            configs = createLlmChatConfigs(supportThinking = true),
        )
        model.preProcess()
        fakeRegistry.setModel(model)
        fakeRepo.save(
            "m",
            PerModelSettings.newBuilder().setEnableThinking(true).build(),
        )
        val vm = buildViewModel()
        advanceUntilIdle()

        vm.send("hi")
        advanceUntilIdle()
        val listener = fakeHelper.lastResultListener
            ?: error("send must invoke runInference")
        listener.invoke("ok", false, "still ignored")
        listener.invoke("", true, null)

        val assistant = vm.messages.value.last { it.role == MessageRole.ASSISTANT }
        assertEquals("ok", assistant.text)
        assertEquals(
            "model.llmSupportThinking gates the channel even with enable_thinking=true",
            null,
            assistant.thinkingText,
        )
    }

    @Test
    fun applyLightOverrides_updatesConfigValues_noCleanup() = runTest(dispatcher) {
        val model = Model(name = "m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel()
        advanceUntilIdle()

        fakeRepo.save(
            "m",
            PerModelSettings.newBuilder().setTemperature(0.2f).setMaxTokens(512).build(),
        )

        val cleanupCountBefore = fakeRegistry.cleanupCalls
        val initCountBefore = fakeRegistry.initializeCalls

        vm.applyLightOverrides()
        advanceUntilIdle()

        assertEquals(0.2f, model.configValues[ConfigKeys.TEMPERATURE.label])
        assertEquals(512, model.configValues[ConfigKeys.MAX_TOKENS.label])
        assertEquals(
            "applyLightOverrides must not call registry.cleanup",
            cleanupCountBefore,
            fakeRegistry.cleanupCalls,
        )
        assertEquals(
            "applyLightOverrides must not call registry.initialize",
            initCountBefore,
            fakeRegistry.initializeCalls,
        )
    }

    @Test
    fun applyHeavySetting_sequencing_stopCleanupInitialize() = runTest(dispatcher) {
        val model = Model(name = "m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel()
        advanceUntilIdle()

        // Drive a streaming generation so applyHeavySetting must call stopResponse first.
        vm.send("hi")
        advanceUntilIdle()
        sharedCalls.clear()

        fakeRepo.save(
            "m",
            PerModelSettings.newBuilder().setAccelerator(Accelerator.CPU.label).build(),
        )

        vm.applyHeavySetting()
        advanceUntilIdle()

        val sequence = sharedCalls.filter {
            it == "stopResponse" || it == "cleanup" || it == "initialize"
        }
        assertEquals(listOf("stopResponse", "cleanup", "initialize"), sequence)
        assertEquals(
            Accelerator.CPU.label,
            model.configValues[ConfigKeys.ACCELERATOR.label],
        )
        assertFalse("reinit progress must clear after success", vm.reinitInProgress.value)
    }

    @Test
    fun applyHeavySetting_initCrash_failedState() = runTest(dispatcher) {
        val model = Model(name = "m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel()
        advanceUntilIdle()

        fakeRepo.save(
            "m",
            PerModelSettings.newBuilder().setAccelerator(Accelerator.CPU.label).build(),
        )
        // Init succeeds first time (constructor) then fails on heavy reinit.
        fakeRegistry.initResult = Result.failure(RuntimeException("native init failed"))

        vm.applyHeavySetting()
        advanceUntilIdle()

        assertTrue(
            "init failure must transition to Failed state",
            vm.uiState.value is ChatUiState.Failed,
        )
        assertFalse(
            "reinit progress must clear in finally even on failure",
            vm.reinitInProgress.value,
        )
    }

    @Test
    fun applySystemPromptAndReset_resetsWithPrompt() = runTest(dispatcher) {
        val model = Model(name = "m", configs = createLlmChatConfigs())
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel()
        advanceUntilIdle()

        // Seed history + staging so the reset is observable.
        vm.send("first turn")
        advanceUntilIdle()
        fakeHelper.lastResultListener?.invoke("ok", true, null)
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()

        val events = collectSnackbar(vm)
        fakeRepo.save(
            "m",
            PerModelSettings.newBuilder().setSystemPromptDefault("be terse").build(),
        )
        sharedCalls.clear()

        vm.applySystemPromptAndReset()
        advanceUntilIdle()

        assertEquals("be terse", model.configValues[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label])
        assertEquals(
            "reset must hand the new prompt to the registry",
            "be terse",
            fakeRegistry.lastResetSystemPrompt,
        )
        assertTrue("messages must clear", vm.messages.value.isEmpty())
        assertTrue("attachments must clear", vm.attachments.value.isEmpty())
        assertEquals(
            listOf(R.string.settings_systemprompt_applied_snackbar),
            events,
        )
        assertEquals(
            "registry must NOT have been cleanup/init'd for systemPrompt change",
            0,
            fakeRegistry.cleanupCalls,
        )
    }

    @Test
    fun resetConversation_clearsAll() = runTest(dispatcher) {
        // Bake the system prompt as the allowlist default so the merge step
        // in `applyEffectiveConfigToModel` doesn't wipe it back to "".
        val model = Model(
            name = "m",
            configs = createLlmChatConfigs(defaultSystemPrompt = "be helpful"),
        )
        model.preProcess()
        fakeRegistry.setModel(model)
        val vm = buildViewModel()
        advanceUntilIdle()
        vm.send("hi")
        advanceUntilIdle()
        fakeHelper.lastResultListener?.invoke("ok", true, null)
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()
        assertNotEquals(0, vm.messages.value.size)

        vm.resetConversation()
        advanceUntilIdle()

        assertTrue(vm.messages.value.isEmpty())
        assertTrue(vm.attachments.value.isEmpty())
        assertEquals(
            "reset must hand the effective system prompt to the engine (D23)",
            "be helpful",
            fakeRegistry.lastResetSystemPrompt,
        )
    }

    // ---- helpers ----

    private fun buildViewModel(): ChatViewModel {
        val savedState = SavedStateHandle(mapOf(ChatViewModel.NAV_ARG_MODEL_NAME to "m"))
        return ChatViewModel(
            savedStateHandle = savedState,
            registry = fakeRegistry,
            helper = fakeHelper,
            errorLog = ErrorLog(context),
            context = context,
            imageDecoder = fakeDecoder,
            settingsRepository = fakeRepo,
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

    fun setModel(model: Model) {
        _models.value = listOf(
            ModelEntry(
                model = model,
                downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
                initStatus = ModelInitStatus.Ready,
            )
        )
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
    }

    override fun stopResponse(model: Model) {
        sharedCalls += "stopResponse"
    }
}
