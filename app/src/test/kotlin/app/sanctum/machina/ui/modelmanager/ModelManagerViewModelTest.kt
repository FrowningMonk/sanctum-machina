package app.sanctum.machina.ui.modelmanager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.data.RuntimeType
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.registry.ResetReason
import app.sanctum.machina.core.settings.AppSettingsRepository
import app.sanctum.machina.core.settings.proto.PerModelSettings
import app.sanctum.machina.crash.CrashState
import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.data.RagConfig
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import app.sanctum.machina.diagnostics.InitSnapshot
import app.sanctum.machina.engine.EmbedderRegistry
import app.sanctum.machina.logexport.DeviceInfoProvider
import app.sanctum.machina.logexport.LogExportManager
import java.io.File
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    private lateinit var projectRepo: FakeProjectRepository

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
        projectRepo = FakeProjectRepository()
    }

    private fun buildVm() = ModelManagerViewModel(
        registry, crashState, logExport, settings, deviceInfo, projectRepo,
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun setDefaultModel_updatesSettingsAndEmitsSnackbar() = runTest {
        val vm = buildVm()
        val events = collectNavEvents(vm)

        vm.setDefaultModel("model-id", "Model Name")
        advanceUntilIdle()

        assertEquals(listOf("model-id"), settings.setDefaultModelIdCalls)
        assertEquals(
            listOf<NavEvent>(NavEvent.ShowSnackbar("Default model: Model Name")),
            events,
        )
    }

    @Test
    fun setDefaultModel_persistsBeforeEmittingSnackbar() = runTest {
        // Regression guard: the UI relies on the ⭐ having moved by the time the snackbar
        // appears. Swapping `emit` and `setDefaultModelId` order would pass the previous test
        // but fail this one.
        val vm = buildVm()
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
        val vm = buildVm()
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
        val vm = buildVm()
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
        val vm = buildVm()
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
        val vm = buildVm()
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
        val vm = buildVm()
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
        val vm = buildVm()
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
        val vm = buildVm()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            vm.rows.collect { }
        }
        advanceUntilIdle()

        vm.onDownload(entry)
        advanceUntilIdle()

        assertEquals(1, registry.downloadInvocations)
    }

    // ---------------- Phase 4 Task 10: embedder row treatment + delete flow ----------------

    @Test
    fun isEmbedderRow_detects_litert_interpreter_runtime() {
        // Detector is the single source of truth for the entire row treatment branch
        // (subtitle, chip, overflow visibility, delete action). Pin both directions.
        val embedder = entryWithRuntime("embedder", RuntimeType.LITERT_INTERPRETER)
        val chat = entryWithRuntime("e4b", RuntimeType.LITERT_LM)
        assertTrue(embedder.isEmbedder())
        assertFalse(chat.isEmbedder())
    }

    @Test
    fun deleteEmbedder_with_zero_projects_emits_empty_confirm_state() = runTest {
        projectRepo.projects = emptyList()
        val vm = buildVm()

        vm.onDeleteEmbedderClick(EmbedderRegistry.MODEL_ID_EMBEDDER, "EmbeddingGemma-300M")
        advanceUntilIdle()

        assertEquals(
            EmbedderDeleteDialogState.Confirm(modelName = "EmbeddingGemma-300M"),
            vm.embedderDeleteDialog.value,
        )
    }

    @Test
    fun deleteEmbedder_with_two_projects_emits_warning_with_names() = runTest {
        // Order from ProjectDao.getAllOrderedByCreatedAtAsc (created_at ASC) — the dialog
        // must surface project names in repository order. Pin both presence AND order.
        projectRepo.projects = listOf(
            projectEntity(id = 1, name = "1С-инструкции", createdAt = 100L),
            projectEntity(id = 2, name = "Нормативка", createdAt = 200L),
        )
        val vm = buildVm()

        vm.onDeleteEmbedderClick(EmbedderRegistry.MODEL_ID_EMBEDDER, "EmbeddingGemma-300M")
        advanceUntilIdle()

        assertEquals(
            EmbedderDeleteDialogState.WarningWithProjects(
                modelName = "EmbeddingGemma-300M",
                projectNames = listOf("1С-инструкции", "Нормативка"),
            ),
            vm.embedderDeleteDialog.value,
        )
    }

    @Test
    fun deleteEmbedder_confirm_invokes_registry_delete_and_clears_dialog() = runTest {
        projectRepo.projects = emptyList()
        val vm = buildVm()

        vm.onDeleteEmbedderClick(EmbedderRegistry.MODEL_ID_EMBEDDER, "EmbeddingGemma-300M")
        advanceUntilIdle()
        vm.onConfirmEmbedderDelete()
        advanceUntilIdle()

        assertEquals(listOf("EmbeddingGemma-300M"), registry.deleteCalls)
        assertNull(vm.embedderDeleteDialog.value)
    }

    @Test
    fun dismissEmbedderDelete_clearsDialog_doesNotInvokeRegistryDelete() = runTest {
        // Cancel-button path: WarningWithProjects state must clear without touching the
        // registry. Litmus — removing `_embedderDeleteDialog.value = null` from the dismiss
        // handler makes this test fail on the first assertion.
        projectRepo.projects = listOf(
            projectEntity(id = 1, name = "p1", createdAt = 100L),
        )
        val vm = buildVm()

        vm.onDeleteEmbedderClick(EmbedderRegistry.MODEL_ID_EMBEDDER, "EmbeddingGemma-300M")
        advanceUntilIdle()
        vm.onDismissEmbedderDelete()
        advanceUntilIdle()

        assertNull(vm.embedderDeleteDialog.value)
        assertTrue(
            "registry.delete must not be invoked on dismiss, got ${registry.deleteCalls}",
            registry.deleteCalls.isEmpty(),
        )
    }

    @Test
    fun confirmEmbedderDelete_withoutDialogState_isNoOp() = runTest {
        // Idempotency guard: a stray Confirm tap with no live dialog must NOT touch the
        // registry. Pins the `_embedderDeleteDialog.value ?: return` short-circuit so a
        // rapid double-Confirm or a programmatic poke can't double-delete or surface a
        // confused snackbar after the dialog is already gone.
        val vm = buildVm()

        vm.onConfirmEmbedderDelete()
        advanceUntilIdle()

        assertTrue(
            "registry.delete must not run without a live dialog state, got ${registry.deleteCalls}",
            registry.deleteCalls.isEmpty(),
        )
        assertNull(vm.embedderDeleteDialog.value)
    }

    @Test
    fun deleteEmbedder_withMismatchedModelId_emitsEmptyConfirm() = runTest {
        // Defence-in-depth on the data-layer contract: `projectsUsingEmbedder` returns
        // emptyList() for non-embedder ids regardless of how many projects exist. The
        // dialog must then show the no-list Confirm shape — a hostile/buggy caller cannot
        // surface a project list under a misleading "this chat model is the embedder" frame.
        projectRepo.projects = listOf(
            projectEntity(id = 1, name = "p1", createdAt = 100L),
            projectEntity(id = 2, name = "p2", createdAt = 200L),
        )
        val vm = buildVm()

        vm.onDeleteEmbedderClick("owner/some-chat-model", "Gemma 4 E4B")
        advanceUntilIdle()

        assertEquals(
            EmbedderDeleteDialogState.Confirm(modelName = "Gemma 4 E4B"),
            vm.embedderDeleteDialog.value,
        )
    }

    @Test
    fun setDefaultModel_chatRow_persistsAndEmits() = runTest {
        // Litmus baseline against [setDefaultModel_not_offered_for_embedder]: if an
        // unconditional early-return regression slipped into setDefaultModel, *this* test
        // would fail. Chat rows must still write through.
        registry.setEntries(
            listOf(entryWithRuntime("e4b", RuntimeType.LITERT_LM)),
        )
        val vm = buildVm()
        val events = collectNavEvents(vm)

        vm.setDefaultModel("owner/e4b", "Gemma 4 E4B")
        advanceUntilIdle()

        assertEquals(listOf("owner/e4b"), settings.setDefaultModelIdCalls)
        assertEquals(
            listOf<NavEvent>(NavEvent.ShowSnackbar("Default model: Gemma 4 E4B")),
            events,
        )
    }

    @Test
    fun setDefaultModel_not_offered_for_embedder() = runTest {
        // Defence-in-depth: the UI hides «Сделать по умолчанию» on embedder rows, but a
        // programmatic call (test, deeplink, future a11y action) must also short-circuit.
        // Writing the embedder modelId into `default_model_id` would corrupt the chat-model
        // contract (the field tracks the model used by quick chat).
        registry.setEntries(
            listOf(entryWithRuntime("embedder", RuntimeType.LITERT_INTERPRETER)),
        )
        val vm = buildVm()
        val events = collectNavEvents(vm)

        vm.setDefaultModel("owner/embedder", "EmbeddingGemma-300M")
        advanceUntilIdle()

        assertTrue(
            "setDefaultModelId must not be invoked for embedder row, got ${settings.setDefaultModelIdCalls}",
            settings.setDefaultModelIdCalls.isEmpty(),
        )
        assertTrue("no snackbar event expected, got $events", events.isEmpty())
    }

    private fun entryWithRuntime(name: String, runtimeType: RuntimeType): ModelEntry =
        ModelEntry(
            model = Model(
                name = name,
                modelId = "owner/$name",
                minDeviceMemoryInGb = 4,
                downloadFileName = "$name.task",
                url = "https://example/$name",
                sizeInBytes = 300_000_000L,
                runtimeType = runtimeType,
            ),
            downloadStatus = ModelDownloadStatus(status = ModelDownloadStatusType.SUCCEEDED),
            initStatus = ModelInitStatus.Idle,
        )

    private fun projectEntity(id: Long, name: String, createdAt: Long): ProjectEntity =
        ProjectEntity(id = id, name = name, createdAt = createdAt)

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

    /** Records [delete] invocations — Task 10 embedder delete-flow assertions read this. */
    val deleteCalls: MutableList<String> = mutableListOf()

    override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
    override fun download(model: Model): Flow<ModelDownloadStatus> {
        downloadInvocations += 1
        return MutableStateFlow(ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED))
            .asStateFlow()
    }
    override fun cancelDownload(modelName: String) = Unit
    override suspend fun delete(modelName: String) {
        deleteCalls += modelName
    }
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
    override fun lastInitSnapshot(): InitSnapshot? = error("not used")
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

/**
 * Hand-rolled [ProjectRepository] (patterns.md "Hand-rolled fakes"). Task 10 only reads
 * `projectsUsingEmbedder`; every other accessor errors loudly so accidental new dependencies
 * fail loudly instead of silently picking up a stale stub.
 */
private class FakeProjectRepository : ProjectRepository {
    /** Repository-ordered snapshot returned by [projectsUsingEmbedder] when the id matches. */
    var projects: List<ProjectEntity> = emptyList()

    override suspend fun projectsUsingEmbedder(embedderModelId: String): List<ProjectEntity> =
        if (embedderModelId == EmbedderRegistry.MODEL_ID_EMBEDDER) projects else emptyList()

    override fun observeAllProjects(): Flow<List<ProjectEntity>> = error("not used")
    override fun observeProjectById(projectId: Long): Flow<ProjectEntity?> = error("not used")
    override suspend fun getById(projectId: Long): ProjectEntity? = error("not used")
    override suspend fun create(name: String, defaultModelId: String?): Long = error("not used")
    override suspend fun delete(projectId: Long, filesDir: File) = error("not used")
    override fun observeFiles(projectId: Long): Flow<List<ProjectFileEntity>> = error("not used")
    override suspend fun addFile(
        projectId: Long, fileName: String, contentHash: String, localPath: String,
    ): Long = error("not used")
    override suspend fun deleteFile(fileId: Long, filesDir: File) = error("not used")
    override suspend fun updateRagOverrides(projectId: Long, overrides: RagConfig?) =
        error("not used")
    override suspend fun getEffectiveRagSettings(projectId: Long): RagConfig = error("not used")
    override suspend fun enqueueIngest(projectId: Long, fileId: Long, filePath: String) =
        error("not used")
    override suspend fun reindexFile(fileId: Long, filesDir: File) = error("not used")
    override suspend fun applyReindexRequired(
        projectId: Long, chunkSize: Int, chunkOverlap: Int, filesDir: File,
    ) = error("not used")
}
