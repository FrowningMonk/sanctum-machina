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
import app.sanctum.machina.logexport.DeviceInfoProvider
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TDD harness for [ModelManagerViewModel] (Phase-3 Task 11).
 *
 * Uses `UnconfinedTestDispatcher` for `Dispatchers.setMain` so `viewModelScope.launch { emit }`
 * runs inline up to its first suspension — combined with UNDISPATCHED subscription (see
 * [collectNavEvents]) the SharedFlow is observed deterministically without replay. Fakes are
 * hand-rolled per patterns.md. Covers the acceptance anchors from tasks/11.md: default model
 * selection ordering (AC-F7), quick-chat routing (AC-F4), and the reactive `default_model_id`
 * contract including the proto3-default "unset" state.
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
    private lateinit var deviceInfo: FakeDeviceInfoProvider

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        registry = FakeModelRegistry()
        crashState = CrashState(ApplicationProvider.getApplicationContext<Application>())
        logExport = LogExportManager(ApplicationProvider.getApplicationContext<Application>())
        settings = FakeAppSettingsRepository()
        // Default totalMem comfortably above the highest production minGb so the legacy
        // tests don't accidentally exercise the gate path. Per-test overrides set their
        // own value before the VM is constructed.
        deviceInfo = FakeDeviceInfoProvider(totalMemoryBytes = 12_000_000_000L)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun setDefaultModel_updatesSettingsAndEmitsSnackbar() = runTest {
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings, deviceInfo)
        val events = collectNavEvents(vm)

        vm.setDefaultModel("model-id", "Model Name")
        advanceUntilIdle()

        assertEquals(listOf("model-id"), settings.setDefaultModelIdCalls)
        assertEquals(
            listOf<NavEvent>(NavEvent.ShowSnackbar("Модель по умолчанию: Model Name")),
            events,
        )
    }

    @Test
    fun setDefaultModel_persistsBeforeEmittingSnackbar() = runTest {
        // Regression guard: the UI relies on the ⭐ having moved by the time the snackbar
        // appears. Swapping `emit` and `setDefaultModelId` order would pass the previous test
        // but fail this one.
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings, deviceInfo)
        val actionLog = mutableListOf<String>()
        settings.onSetDefaultModelId = { actionLog += "setDefault:$it" }
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.navEvents.collect { event ->
                actionLog += "event:${event::class.simpleName}"
            }
        }

        vm.setDefaultModel("abc", "A B C")
        advanceUntilIdle()

        assertEquals(listOf("setDefault:abc", "event:ShowSnackbar"), actionLog)
    }

    @Test
    fun defaultModelId_emitsEmptyStringWhenUnset() = runTest {
        // First-launch contract from tasks/11.md 'Edge cases': proto3 default_model_id is "",
        // so no row should get a ⭐. Locks the `stateIn(initialValue = "")` seed.
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings, deviceInfo)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.defaultModelId.collect { /* keep subscription alive */ }
        }
        advanceUntilIdle()

        assertEquals("", vm.defaultModelId.value)
    }

    @Test
    fun defaultModelId_reflectsSettingsValue() = runTest {
        // Seed the underlying setting BEFORE VM construction so the stateIn initial collection
        // sees it — this is the "user already has a default" cold-start scenario the UI relies on.
        settings.setDefault("first-default")
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings, deviceInfo)
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
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings, deviceInfo)
        val events = collectNavEvents(vm)

        vm.onLoad("some-model-id")
        advanceUntilIdle()

        // Exact-list assertion already enforces 'exactly one event, of type OpenQuickChat'.
        assertEquals(listOf<NavEvent>(NavEvent.OpenQuickChat("some-model-id")), events)
    }

    // ---------------- Phase 3.5 Task 5: pre-flight gate ----------------

    @Test
    fun row_below_threshold_has_gate_disallowed() = runTest {
        // Sub-threshold device (S20 FE-class, 5.3 GB) against E4B's minGb=6 — gate must block.
        deviceInfo = FakeDeviceInfoProvider(totalMemoryBytes = 5_300_000_000L)
        registry.setEntries(listOf(entryWithMinGb("e4b", minGb = 6)))
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings, deviceInfo)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.rows.collect { /* keep subscription alive */ }
        }
        advanceUntilIdle()

        val rows = vm.rows.value
        assertEquals(1, rows.size)
        assertFalse(
            "Sub-threshold device should be gated, got allowed=${rows[0].gate.allowed}",
            rows[0].gate.allowed,
        )
        assertEquals(5_300_000_000L, rows[0].gate.totalBytes)
        assertEquals(6, rows[0].gate.minGb)
    }

    @Test
    fun row_above_threshold_has_gate_allowed() = runTest {
        // Honor 200-class (12 GB) against E4B's minGb=6 — gate must pass.
        deviceInfo = FakeDeviceInfoProvider(totalMemoryBytes = 12_000_000_000L)
        registry.setEntries(listOf(entryWithMinGb("e4b", minGb = 6)))
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings, deviceInfo)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.rows.collect { /* keep subscription alive */ }
        }
        advanceUntilIdle()

        val rows = vm.rows.value
        assertEquals(1, rows.size)
        assertEquals(
            GateDecision(allowed = true, totalBytes = 12_000_000_000L, minGb = 6),
            rows[0].gate,
        )
    }

    @Test
    fun onDownload_short_circuits_when_gate_disallowed() = runTest {
        // Defence-in-depth: even if a caller bypasses the disabled UI button, the VM must not
        // hand the model to ModelRegistry.download (which would enqueue a WorkManager job).
        deviceInfo = FakeDeviceInfoProvider(totalMemoryBytes = 5_300_000_000L)
        val entry = entryWithMinGb("e4b", minGb = 6)
        registry.setEntries(listOf(entry))
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings, deviceInfo)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.rows.collect { }
        }
        advanceUntilIdle()

        vm.onDownload(entry)
        advanceUntilIdle()

        assertEquals(0, registry.downloadInvocations)
    }

    @Test
    fun onDownload_invokes_registry_when_gate_allows() = runTest {
        // Regression-protection: the short-circuit must only fire on disallowed gate, not always.
        deviceInfo = FakeDeviceInfoProvider(totalMemoryBytes = 12_000_000_000L)
        val entry = entryWithMinGb("e4b", minGb = 6)
        registry.setEntries(listOf(entry))
        val vm = ModelManagerViewModel(registry, crashState, logExport, settings, deviceInfo)
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.rows.collect { }
        }
        advanceUntilIdle()

        vm.onDownload(entry)
        advanceUntilIdle()

        assertEquals(1, registry.downloadInvocations)
    }

    private fun entryWithMinGb(name: String, minGb: Int?): ModelEntry =
        ModelEntry(
            model = Model(
                name = name,
                modelId = "owner/$name",
                minDeviceMemoryInGb = minGb,
                downloadFileName = "$name.task",
                url = "https://example/$name",
                sizeInBytes = 1_073_741_824L,
            ),
            downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED),
            initStatus = ModelInitStatus.Idle,
        )

    // UNDISPATCHED start guarantees the subscription is registered before the VM method call
    // regardless of future changes to the SharedFlow replay/buffer config — keep this even if
    // current tests pass without it.
    private fun kotlinx.coroutines.test.TestScope.collectNavEvents(
        vm: ModelManagerViewModel,
    ): MutableList<NavEvent> {
        val events = mutableListOf<NavEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.navEvents.collect { events += it }
        }
        return events
    }
}

// ---------- Fakes (hand-rolled; no MockK/Mockito per patterns.md) ----------

private class FakeModelRegistry : ModelRegistry {
    private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
    override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

    private val _activeModelName = MutableStateFlow<String?>(null)
    override val activeModelName: StateFlow<String?> = _activeModelName.asStateFlow()

    /** Counts terminal subscriptions to [download]'s flow — used by gate short-circuit tests. */
    var downloadInvocations: Int = 0
        private set

    fun setEntries(entries: List<ModelEntry>) { _models.value = entries }

    override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
    override fun download(model: Model): Flow<ModelDownloadStatus> {
        downloadInvocations += 1
        return MutableStateFlow(ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED))
            .asStateFlow()
    }
    override fun cancelDownload(modelName: String) = Unit
    override suspend fun delete(modelName: String) = Unit
    override suspend fun initialize(modelName: String): Result<Unit> = Result.success(Unit)
    override suspend fun cleanup(modelName: String) = Unit
    override suspend fun resetConversation(modelName: String, systemPrompt: String?) = Unit
    override fun getModel(modelName: String): Model? = null
}

/**
 * Hand-rolled [DeviceInfoProvider] (patterns.md "Hand-rolled fakes"). Only
 * [totalMemoryBytes] is read by [ModelManagerViewModel] in Phase 3.5 — every
 * other accessor errors loudly so accidental new dependencies fail the test
 * loudly instead of silently picking up a stale stub value.
 */
private class FakeDeviceInfoProvider(
    private val totalMemoryBytes: Long,
) : DeviceInfoProvider {
    override fun totalMemoryBytes(): Long = totalMemoryBytes

    override fun applicationId(): String = error("not used")
    override fun versionName(): String = error("not used")
    override fun versionCode(): Long = error("not used")
    override fun isDebug(): Boolean = error("not used")
    override fun manufacturer(): String = error("not used")
    override fun model(): String = error("not used")
    override fun androidRelease(): String = error("not used")
    override fun apiLevel(): Int = error("not used")
    override fun availableMemoryBytes(): Long = error("not used")
    override fun thresholdMemoryBytes(): Long = error("not used")
    override fun isLowMemory(): Boolean = error("not used")
    override fun processJavaHeapBytes(): Long = error("not used")
    override fun processNativeHeapBytes(): Long = error("not used")
    override fun processTotalPssBytes(): Long = error("not used")
    override fun lastInitSnapshot(): app.sanctum.machina.diagnostics.InitSnapshot? = error("not used")
    override fun activeModelId(): String? = error("not used")
    override fun downloadedModels(): List<Pair<String, Long>> = error("not used")
    override fun nowIso(): String = error("not used")
}

private class FakeAppSettingsRepository : AppSettingsRepository {
    private val defaultModelId = MutableStateFlow("")
    private val lastUsedModelId = MutableStateFlow("")
    val setDefaultModelIdCalls: MutableList<String> = mutableListOf()

    /** Optional ordering probe — the sequencing test uses this to record the write before the emit. */
    var onSetDefaultModelId: (String) -> Unit = {}

    fun setDefault(id: String) { defaultModelId.value = id }

    override fun observePerModelSettings(modelId: String): Flow<PerModelSettings?> =
        MutableStateFlow(null).asStateFlow()
    override suspend fun savePerModelSettings(modelId: String, settings: PerModelSettings) = Unit
    override suspend fun resetPerModelSettings(modelId: String) = Unit

    override suspend fun getDefaultModelId(): String = defaultModelId.value
    override suspend fun setDefaultModelId(id: String) {
        setDefaultModelIdCalls += id
        onSetDefaultModelId(id)
        defaultModelId.value = id
    }
    override fun observeDefaultModelId(): Flow<String> = defaultModelId

    override suspend fun getLastUsedModelId(): String = lastUsedModelId.value
    override suspend fun setLastUsedModelId(id: String) { lastUsedModelId.value = id }
    override fun observeLastUsedModelId(): Flow<String> = lastUsedModelId

    override suspend fun isSettingsMigrated(): Boolean = false
    override suspend fun markSettingsMigrated() = Unit
}
