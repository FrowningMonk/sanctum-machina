package app.sanctum.machina.ui.modelmanager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.settings.AppSettingsRepository
import app.sanctum.machina.core.settings.proto.PerModelSettings
import app.sanctum.machina.crash.CrashState
import app.sanctum.machina.logexport.LogExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
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

/**
 * TDD harness for [ModelManagerViewModel] (Phase-3 Task 11).
 *
 * Follows the same pattern as [app.sanctum.machina.ui.home.HomeViewModelTest]: hand-rolled
 * fakes, `StandardTestDispatcher` + `Dispatchers.setMain`, `advanceUntilIdle` to drain
 * `viewModelScope` coroutines. Covers the three acceptance anchors from tasks/11.md: default
 * model selection (AC-F7), quick-chat routing (AC-F4), and reactive exposure of
 * `default_model_id` from [AppSettingsRepository].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ModelManagerViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var registry: FakeModelRegistry
    private lateinit var crashState: CrashState
    private lateinit var logExport: LogExportManager
    private lateinit var settings: FakeAppSettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        registry = FakeModelRegistry()
        crashState = CrashState(ApplicationProvider.getApplicationContext<Application>())
        logExport = LogExportManager(ApplicationProvider.getApplicationContext<Application>())
        settings = FakeAppSettingsRepository()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun setDefaultModel_updatesSettingsAndEmitsSnackbar() = runTest {
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings)
        val events = mutableListOf<NavEvent>()
        // UNDISPATCHED start runs the coroutine inline until its first suspension — the SharedFlow
        // subscription happens before the test body calls the VM, so the first emit is observed.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.navEvents.collect { events += it }
        }

        vm.setDefaultModel("model-id", "Model Name")
        advanceUntilIdle()

        assertEquals(listOf("model-id"), settings.setDefaultModelIdCalls)
        assertEquals(
            listOf<NavEvent>(NavEvent.ShowSnackbar("Модель по умолчанию: Model Name")),
            events,
        )
    }

    @Test
    fun defaultModelId_reflectsSettingsValue() = runTest {
        // Seed the underlying setting BEFORE VM construction so the stateIn initial collection
        // sees it — this is the "user already has a default" cold-start scenario the UI relies on.
        settings.setDefault("first-default")
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings)
        // WhileSubscribed(5_000L) needs an active subscriber to collect upstream. UNDISPATCHED
        // makes that subscription take effect before the test mutates the setting.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.defaultModelId.collect { /* no-op: keep the subscription alive */ }
        }
        advanceUntilIdle()
        assertEquals("first-default", vm.defaultModelId.value)

        settings.setDefault("new-default")
        advanceUntilIdle()

        assertEquals("new-default", vm.defaultModelId.value)
    }

    @Test
    fun onLoad_emitsOpenQuickChatEvent() = runTest {
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings)
        val events = mutableListOf<NavEvent>()
        // UNDISPATCHED start runs the coroutine inline until its first suspension — the SharedFlow
        // subscription happens before the test body calls the VM, so the first emit is observed.
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.navEvents.collect { events += it }
        }

        vm.onLoad("some-model-id")
        advanceUntilIdle()

        assertEquals(listOf<NavEvent>(NavEvent.OpenQuickChat("some-model-id")), events)
        assertFalse(
            "onLoad must not emit the retired OpenChat event",
            events.any { it::class.simpleName == "OpenChat" },
        )
    }
}

// ---------- Fakes (hand-rolled; no MockK/Mockito per patterns.md) ----------

private class FakeModelRegistry : ModelRegistry {
    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

    private val _activeModelName = MutableStateFlow<String?>(null)
    override val activeModelName: StateFlow<String?> = _activeModelName.asStateFlow()

    override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
    override fun download(model: Model): Flow<ModelDownloadStatus> =
        MutableStateFlow(ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED))
            .asStateFlow()
    override fun cancelDownload(modelName: String) = Unit
    override suspend fun delete(modelName: String) = Unit
    override suspend fun initialize(modelName: String): Result<Unit> = Result.success(Unit)
    override suspend fun cleanup(modelName: String) = Unit
    override suspend fun resetConversation(modelName: String, systemPrompt: String?) = Unit
    override fun getModel(modelName: String): Model? = null
}

private class FakeAppSettingsRepository : AppSettingsRepository {
    private val defaultModelId = MutableStateFlow("")
    private val lastUsedModelId = MutableStateFlow("")
    val setDefaultModelIdCalls: MutableList<String> = mutableListOf()

    fun setDefault(id: String) { defaultModelId.value = id }

    override fun observePerModelSettings(modelId: String): Flow<PerModelSettings?> =
        MutableStateFlow(null).asStateFlow()
    override suspend fun savePerModelSettings(modelId: String, settings: PerModelSettings) = Unit
    override suspend fun resetPerModelSettings(modelId: String) = Unit

    override suspend fun getDefaultModelId(): String = defaultModelId.value
    override suspend fun setDefaultModelId(id: String) {
        setDefaultModelIdCalls += id
        defaultModelId.value = id
    }
    override fun observeDefaultModelId(): Flow<String> = defaultModelId

    override suspend fun getLastUsedModelId(): String = lastUsedModelId.value
    override suspend fun setLastUsedModelId(id: String) { lastUsedModelId.value = id }
    override fun observeLastUsedModelId(): Flow<String> = lastUsedModelId

    override suspend fun isSettingsMigrated(): Boolean = false
    override suspend fun markSettingsMigrated() = Unit
}
