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
        // Seed 7 images directly.
        repeat(7) { vm.addImageBitmap(stubBitmap()) }
        advanceUntilIdle()
        val events = collectSnackbar(vm)

        vm.addImages(List(5) { uri("u$it") })
        advanceUntilIdle()

        assertEquals(10, vm.attachments.value.size)
        assertTrue(
            "snackbar should have emitted R.string.attachment_max_images_reached",
            events.contains(R.string.attachment_max_images_reached),
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
        assertTrue(events.contains(R.string.attachment_max_images_reached))
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

        assertEquals(1, vm.attachments.value.size)
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

    override suspend fun decode(uri: Uri): Bitmap? {
        val last = uri.lastPathSegment ?: return null
        if (last in nullFor) return null
        return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    }
}

private class FakeModelRegistry : ModelRegistry {
    var model: Model = Model(name = "m")
    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    override val models: StateFlow<List<ModelEntry>> = _models
    override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
    override fun download(model: Model): Flow<ModelDownloadStatus> = emptyFlow()
    override fun cancelDownload(modelName: String) {}
    override suspend fun delete(modelName: String) {}
    override suspend fun initialize(modelName: String): Result<Unit> = Result.success(Unit)
    override suspend fun cleanup(modelName: String) {}
    override suspend fun resetConversation(modelName: String, systemPrompt: String?) {}
    override fun getModel(modelName: String): Model = model
}

private class FakeLlmHelper : LlmModelHelper {
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
    ) {
        onDone("")
    }

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
