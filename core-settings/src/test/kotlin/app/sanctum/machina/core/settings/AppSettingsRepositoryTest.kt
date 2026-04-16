package app.sanctum.machina.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.settings.proto.AppSettings
import app.sanctum.machina.core.settings.proto.PerModelSettings
import java.io.File
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
class AppSettingsRepositoryTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var testScope: TestScope
  private lateinit var repository: DefaultAppSettingsRepository
  private lateinit var dataStore: DataStore<AppSettings>
  private lateinit var protoFile: File

  @Before
  fun setUp() {
    val dispatcher = UnconfinedTestDispatcher()
    testScope = TestScope(dispatcher)
    protoFile = File(tempFolder.root, "test-${UUID.randomUUID()}.pb")
    val context: Context = ApplicationProvider.getApplicationContext()
    val errorLog = ErrorLog(context)
    dataStore = DataStoreFactory.create(
      serializer = AppSettingsSerializer,
      scope = testScope.backgroundScope,
      produceFile = { protoFile },
    )
    repository = DefaultAppSettingsRepository(dataStore, errorLog)
  }

  @After
  fun tearDown() {
    if (protoFile.exists()) protoFile.delete()
  }

  @Test
  fun save_observe_roundTrip() = testScope.runTest {
    val saved = PerModelSettings.newBuilder()
      .setTemperature(0.7f)
      .setMaxTokens(256)
      .setTopK(40)
      .setTopP(0.9f)
      .setEnableThinking(true)
      .setAccelerator("GPU")
      .setSystemPromptDefault("be terse")
      .build()

    repository.savePerModelSettings("model-1", saved)
    val observed = repository.observePerModelSettings("model-1").first()

    assertNotNull(observed)
    assertEquals(0.7f, observed!!.temperature, 0f)
    assertEquals(256, observed.maxTokens)
    assertEquals(40, observed.topK)
    assertEquals(0.9f, observed.topP, 0f)
    assertTrue(observed.enableThinking)
    assertEquals("GPU", observed.accelerator)
    assertEquals("be terse", observed.systemPromptDefault)
  }

  @Test
  fun save_multipleModels_observeIndependently() = testScope.runTest {
    val forA = PerModelSettings.newBuilder().setTemperature(0.2f).build()
    val forB = PerModelSettings.newBuilder().setTemperature(0.8f).setMaxTokens(512).build()

    repository.savePerModelSettings("A", forA)
    repository.savePerModelSettings("B", forB)

    val observedA = repository.observePerModelSettings("A").first()
    val observedB = repository.observePerModelSettings("B").first()

    assertNotNull(observedA)
    assertEquals(0.2f, observedA!!.temperature, 0f)
    assertFalse("A must not carry B's maxTokens", observedA.hasMaxTokens())

    assertNotNull(observedB)
    assertEquals(0.8f, observedB!!.temperature, 0f)
    assertEquals(512, observedB.maxTokens)
  }

  @Test
  fun reset_observeNull_mapNoKey() = testScope.runTest {
    val saved = PerModelSettings.newBuilder().setTemperature(0.3f).build()
    repository.savePerModelSettings("to-reset", saved)
    val beforeReset = repository.observePerModelSettings("to-reset").first()
    assertNotNull(beforeReset)

    repository.resetPerModelSettings("to-reset")

    val afterReset = repository.observePerModelSettings("to-reset").first()
    assertNull(afterReset)

    val raw: AppSettings = dataStore.data.first()
    assertFalse(
      "map must not contain the reset key",
      raw.perModelOverridesMap.containsKey("to-reset"),
    )
  }

  @Test
  fun mergeOptional_saveOnlyTemperature_readPartial() = testScope.runTest {
    val partial = PerModelSettings.newBuilder().setTemperature(0.5f).build()
    repository.savePerModelSettings("partial", partial)

    val observed = repository.observePerModelSettings("partial").first()

    assertNotNull(observed)
    assertTrue("hasTemperature must be true", observed!!.hasTemperature())
    assertFalse("hasMaxTokens must be false", observed.hasMaxTokens())
    assertFalse("hasTopK must be false", observed.hasTopK())
    assertFalse("hasTopP must be false", observed.hasTopP())
    assertFalse("hasEnableThinking must be false", observed.hasEnableThinking())
    assertFalse("hasAccelerator must be false", observed.hasAccelerator())
    assertFalse("hasSystemPromptDefault must be false", observed.hasSystemPromptDefault())
  }

  @Test
  fun concurrent_twoSavesSequential_lastWins() = testScope.runTest {
    val first = PerModelSettings.newBuilder().setTemperature(0.1f).build()
    val second = PerModelSettings.newBuilder().setTemperature(0.9f).build()

    repository.savePerModelSettings("race", first)
    repository.savePerModelSettings("race", second)

    val observed = repository.observePerModelSettings("race").first()
    assertNotNull(observed)
    assertEquals(0.9f, observed!!.temperature, 0f)
  }

  @Test
  fun ioException_observeDoesNotCrash() = testScope.runTest {
    // Corrupt file BEFORE any DataStore interaction on a fresh instance so the
    // first read hits InvalidProtocolBufferException → CorruptionException path.
    val corruptFile = File(tempFolder.root, "corrupt-${UUID.randomUUID()}.pb")
    corruptFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00, 0x42, 0x13))

    val corruptStore: DataStore<AppSettings> = DataStoreFactory.create(
      serializer = AppSettingsSerializer,
      scope = testScope.backgroundScope,
      produceFile = { corruptFile },
    )
    val context: Context = ApplicationProvider.getApplicationContext()
    val corruptRepo = DefaultAppSettingsRepository(corruptStore, ErrorLog(context))

    val observed = corruptRepo.observePerModelSettings("any-model").first()
    assertNull(observed)
  }
}
