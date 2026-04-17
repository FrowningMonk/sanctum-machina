package app.sanctum.machina.ui.chat

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.R
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.runtime.CleanUpListener
import app.sanctum.machina.core.runtime.LlmModelHelper
import app.sanctum.machina.core.runtime.ResultListener
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

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext<Application>()
        fakeRegistry = FakeModelRegistry()
        fakeHelper = FakeLlmHelper()
        fakeDecoder = FakeImageDecoder()
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
        // Seed 7 with distinct bitmaps so we can pin FIFO ordering.
        val seed = List(7) { stubBitmap() }
        seed.forEach { vm.addImageBitmap(it) }
        advanceUntilIdle()
        val events = collectSnackbar(vm)

        vm.addImages(List(5) { uri("u$it") })
        advanceUntilIdle()

        assertEquals(10, vm.attachments.value.size)
        val bitmaps = vm.attachments.value.filterIsInstance<Attachment.Image>().map { it.bitmap }
        // Seeds preserved at the front (no prepend-instead-of-append regression).
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
        // Real ErrorLog writes a line to Robolectric's filesDir — harmless,
        // drained by `advanceUntilIdle` before the assertion runs.
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
        fakeRegistry.model = Model(
            name = "m",
            llmSupportImage = true,
            llmSupportAudio = true,
            llmSupportThinking = false,
        )

        val vm = buildViewModel()
        advanceUntilIdle()

        assertTrue(vm.modelCaps.value.supportImage)
        assertTrue(vm.modelCaps.value.supportAudio)
        assertFalse(vm.modelCaps.value.supportThinking)
    }

    @Test
    fun send_transfersPendingAttachmentsIntoUserMessageAndClearsStaging() = runTest(dispatcher) {
        fakeRegistry.model = Model(name = "m", llmSupportImage = true)
        val vm = buildViewModel()
        advanceUntilIdle()
        val bitmap = stubBitmap()
        vm.addImageBitmap(bitmap)
        advanceUntilIdle()

        vm.send("describe this")
        advanceUntilIdle()

        // Staging cleared so ThumbnailStrip empties.
        assertTrue(vm.attachments.value.isEmpty())
        // USER message carries the attachments for history rendering (AC-26).
        val user = vm.messages.value.first { it.role == MessageRole.USER }
        assertEquals(1, user.attachments.size)
        assertTrue(user.attachments.single() is Attachment.Image)
        assertSame(bitmap, (user.attachments.single() as Attachment.Image).bitmap)
    }

    @Test
    fun send_attachmentOnlyBlankText_stillProceedsAndClears() = runTest(dispatcher) {
        fakeRegistry.model = Model(name = "m", llmSupportImage = true)
        val vm = buildViewModel()
        advanceUntilIdle()
        vm.addImageBitmap(stubBitmap())
        advanceUntilIdle()

        vm.send("")  // blank text, attachments present — AC-9 + AC-26 contract
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
        // AC-20 / D13: MAX_AUDIO_CLIP_COUNT = 1 — the VM is the defensive
        // guard behind the disabled mic button in MultimodalInputBar.
        val vm = buildViewModel()
        advanceUntilIdle()
        vm.addAudio(byteArrayOf(9), durationMs = 1_000L)
        advanceUntilIdle()

        vm.addAudio(byteArrayOf(10, 11), durationMs = 2_000L)
        advanceUntilIdle()

        val audio = vm.attachments.value.single() as Attachment.Audio
        assertEquals(1_000L, audio.durationMs)
        assertEquals(1, audio.pcm.size)
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

        // "audio" is in the ErrorLog D27 whitelist — must not throw.
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
        fakeRegistry.model = Model(name = "m", llmSupportAudio = true)
        val vm = buildViewModel()
        advanceUntilIdle()
        val pcm = byteArrayOf(5, 6, 7)
        vm.addAudio(pcm, durationMs = 2_000L)
        advanceUntilIdle()

        vm.send("")
        advanceUntilIdle()

        // Audio attachment transferred into the USER message (AC-26), and
        // the staging area clears so MultimodalInputBar returns to idle.
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

        // Must not throw — "camera" is in the ErrorLog D27 whitelist
        // (enforcement itself is covered by :core-runtime ErrorLogTest).
        // The file-side effect runs on Dispatchers.IO and detaches from
        // the test scheduler, so this test only asserts the Main-thread
        // observable: the snackbar event.
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
        fakeRegistry.model = Model(
            name = "m",
            llmSupportImage = true,
            llmSupportAudio = true,
            llmSupportThinking = true,
        )

        val vm = buildViewModel()
        advanceUntilIdle()

        assertEquals(ModelCapabilities(), vm.modelCaps.value)
        assertTrue(vm.uiState.value is ChatUiState.Failed)
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

private class FakeModelRegistry : ModelRegistry {
    var model: Model = Model(name = "m")
    var initResult: Result<Unit> = Result.success(Unit)
    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    override val models: StateFlow<List<ModelEntry>> = _models
    override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
    override fun download(model: Model): Flow<ModelDownloadStatus> = emptyFlow()
    override fun cancelDownload(modelName: String) {}
    override suspend fun delete(modelName: String) {}
    override suspend fun initialize(modelName: String): Result<Unit> = initResult
    override suspend fun cleanup(modelName: String) {}
    override suspend fun resetConversation(modelName: String, systemPrompt: String?) {}
    override fun getModel(modelName: String): Model = model
}

private class FakeLlmHelper : LlmModelHelper {
    // Inert defaults — ChatViewModel never reaches these paths in the current
    // test suite (init is routed through ModelRegistry; send() is not exercised).
    // Task 11 can swap in a richer fake when heavy-setting / send flows are tested.
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
    ) {}

    override fun stopResponse(model: Model) {}
}
