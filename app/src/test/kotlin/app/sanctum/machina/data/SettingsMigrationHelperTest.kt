package app.sanctum.machina.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.AllowedModel
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.registry.ResetReason
import app.sanctum.machina.core.settings.AppSettingsSerializer
import app.sanctum.machina.core.settings.DefaultAppSettingsRepository
import app.sanctum.machina.core.settings.proto.AppSettings
import app.sanctum.machina.core.settings.proto.PerModelSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsMigrationHelperTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var testScope: TestScope
  private lateinit var rawDataStore: DataStore<AppSettings>
  private lateinit var dataStore: CountingDataStore
  private lateinit var repository: DefaultAppSettingsRepository
  private lateinit var registry: FakeModelRegistry
  private lateinit var errorLog: ErrorLog
  private lateinit var errorLogFile: File
  private lateinit var context: Context

  @Before
  fun setUp() {
    val dispatcher = UnconfinedTestDispatcher()
    testScope = TestScope(dispatcher)
    context = ApplicationProvider.getApplicationContext()
    val protoFile = File(tempFolder.root, "settings-${UUID.randomUUID()}.pb")
    rawDataStore = DataStoreFactory.create(
      serializer = AppSettingsSerializer,
      scope = testScope.backgroundScope,
      produceFile = { protoFile },
    )
    dataStore = CountingDataStore(rawDataStore)
    errorLog = ErrorLog(context)
    errorLogFile = File(context.filesDir, "logs/errors.log")
    errorLogFile.parentFile?.deleteRecursively()
    repository = DefaultAppSettingsRepository(rawDataStore, errorLog)
    registry = FakeModelRegistry()
  }

  @After
  fun tearDown() {
    errorLogFile.parentFile?.deleteRecursively()
  }

  @Test
  fun happyPath_rekeysNameToModelId() = testScope.runTest {
    registry.set(
      allowed("Gemma-4-E4B-it-litert-lm", "litert-community/gemma-4-E4B-it-litert-lm"),
    )
    val existing = PerModelSettings.newBuilder().setTemperature(0.7f).build()
    rawDataStore.updateData {
      it.toBuilder()
        .putPerModelOverrides("Gemma-4-E4B-it-litert-lm", existing)
        .build()
    }
    dataStore.resetCount()

    val helper = SettingsMigrationHelper(repository, registry, dataStore, errorLog)
    helper.migrateIfNeeded()

    val after = rawDataStore.data.first()
    assertFalse(
      "old name-keyed entry must be gone",
      after.perModelOverridesMap.containsKey("Gemma-4-E4B-it-litert-lm"),
    )
    val remapped = after.perModelOverridesMap["litert-community/gemma-4-E4B-it-litert-lm"]
    assertTrue("entry must be present under modelId key", remapped != null)
    assertEquals(0.7f, remapped!!.temperature, 0f)
    assertTrue("sentinel must be set", repository.isSettingsMigrated())
  }

  @Test
  fun orphanKey_droppedAndLogged() = testScope.runTest {
    registry.set(allowed("KnownName", "known/model-id"))
    rawDataStore.updateData {
      it.toBuilder()
        .putPerModelOverrides("KnownName", PerModelSettings.newBuilder().setMaxTokens(128).build())
        .putPerModelOverrides(
          "OrphanName",
          PerModelSettings.newBuilder().setTemperature(0.1f).build(),
        )
        .build()
    }
    dataStore.resetCount()

    val helper = SettingsMigrationHelper(repository, registry, dataStore, errorLog)
    helper.migrateIfNeeded()

    val after = rawDataStore.data.first()
    assertFalse(
      "orphan key must be dropped",
      after.perModelOverridesMap.containsKey("OrphanName"),
    )
    assertFalse(
      "orphan key (under new name) must not appear",
      after.perModelOverridesMap.containsKey("orphan/missing"),
    )
    assertTrue(
      "known key must be remapped",
      after.perModelOverridesMap.containsKey("known/model-id"),
    )

    // ErrorLog writes one line per event — assert exactly one orphan line logged.
    val lines = errorLogFile.readLines().filter { it.contains("OrphanName") }
    assertEquals("orphan key logged exactly once", 1, lines.size)
    assertTrue("orphan log uses settings-io component", lines[0].contains("[settings-io]"))
  }

  @Test
  fun sentinel_preventsRerun() = testScope.runTest {
    registry.set(allowed("N", "id-n"))
    rawDataStore.updateData {
      it.toBuilder()
        .putPerModelOverrides("N", PerModelSettings.newBuilder().setTopK(10).build())
        .build()
    }

    val helper = SettingsMigrationHelper(repository, registry, dataStore, errorLog)
    helper.migrateIfNeeded()
    val countAfterFirst = dataStore.updateCount
    helper.migrateIfNeeded()

    assertEquals(
      "second migrateIfNeeded() must not touch DataStore",
      countAfterFirst,
      dataStore.updateCount,
    )
  }

  @Test
  fun singleUpdateDataCall_perRun() = testScope.runTest {
    registry.set(
      allowed("A", "id/a"),
      allowed("B", "id/b"),
      allowed("C", "id/c"),
    )
    rawDataStore.updateData {
      it.toBuilder()
        .putPerModelOverrides("A", PerModelSettings.newBuilder().setMaxTokens(1).build())
        .putPerModelOverrides("B", PerModelSettings.newBuilder().setMaxTokens(2).build())
        .putPerModelOverrides("C", PerModelSettings.newBuilder().setMaxTokens(3).build())
        .build()
    }
    dataStore.resetCount()

    val helper = SettingsMigrationHelper(repository, registry, dataStore, errorLog)
    helper.migrateIfNeeded()

    assertEquals(
      "updateData must be called exactly once (atomic rekey)",
      1,
      dataStore.updateCount,
    )
  }

  @Test
  fun migration_writesRekeyAndSentinel_inSingleAtomicWrite() = testScope.runTest {
    // Regression guard (Decision 8): the rekey and sentinel flip MUST land in
    // the same DataStore transition — otherwise a process kill between two
    // writes leaves rekeyed data without the sentinel, and the next boot runs
    // migration against modelId-keyed data, sees every key as orphan, and
    // drops every per-model override.
    registry.set(allowed("N", "id/n"))
    rawDataStore.updateData {
      it.toBuilder()
        .putPerModelOverrides("N", PerModelSettings.newBuilder().setTopK(5).build())
        .build()
    }
    dataStore.resetCount()

    val helper = SettingsMigrationHelper(repository, registry, dataStore, errorLog)
    helper.migrateIfNeeded()

    assertEquals("exactly one atomic write", 1, dataStore.updateCount)
    val snapshot = rawDataStore.data.first()
    assertTrue("sentinel must be set", snapshot.settingsKeysMigrated)
    assertTrue(
      "rekeyed entry must be present in same snapshot",
      snapshot.perModelOverridesMap.containsKey("id/n"),
    )
  }

  @Test
  fun emptyOverrides_marksSentinel_withoutLogging() = testScope.runTest {
    registry.set(allowed("A", "id/a"))

    val helper = SettingsMigrationHelper(repository, registry, dataStore, errorLog)
    helper.migrateIfNeeded()

    assertTrue(repository.isSettingsMigrated())
    assertFalse("no log file expected when nothing to migrate", errorLogFile.exists())
  }

  private fun allowed(name: String, modelId: String): AllowedModel =
    AllowedModel(
      name = name,
      modelId = modelId,
      modelFile = "model.task",
      commitHash = "abc",
      sizeInBytes = 0L,
      taskTypes = listOf(),
    )
}

/** Counts `updateData` invocations while delegating behavior to a real [DataStore]. */
private class CountingDataStore(
  private val delegate: DataStore<AppSettings>,
) : DataStore<AppSettings> {
  var updateCount: Int = 0
    private set

  override val data: Flow<AppSettings> get() = delegate.data

  override suspend fun updateData(transform: suspend (t: AppSettings) -> AppSettings): AppSettings {
    updateCount++
    return delegate.updateData(transform)
  }

  fun resetCount() {
    updateCount = 0
  }
}

/** Hand-rolled [ModelRegistry] fake — only `models` is exercised. */
private class FakeModelRegistry : ModelRegistry {
  private val state = MutableStateFlow<List<ModelEntry>>(emptyList())
  override val models: StateFlow<List<ModelEntry>> = state
  override val activeModelName: StateFlow<String?> = MutableStateFlow(null)

  fun set(vararg allowed: AllowedModel) {
    state.value = allowed.map {
      ModelEntry(
        model = it.toModel(),
        downloadStatus = ModelDownloadStatus(ModelDownloadStatusType.NOT_DOWNLOADED),
        initStatus = ModelInitStatus.Idle,
      )
    }
  }

  override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
  override fun download(model: Model) = error("unused in migration test")
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
