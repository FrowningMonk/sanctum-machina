package app.sanctum.machina.core.registry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.AllowedModel
import app.sanctum.machina.core.data.ConfigKeys
import app.sanctum.machina.core.log.ErrorLog
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AllowlistLoaderTest {

  private lateinit var context: Context
  private lateinit var errorLog: ErrorLog
  private lateinit var errorLogFile: File

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    errorLog = ErrorLog(context)
    errorLogFile = File(context.filesDir, "logs/errors.log")
    errorLogFile.parentFile?.deleteRecursively()
  }

  @After
  fun tearDown() {
    errorLogFile.parentFile?.deleteRecursively()
  }

  private fun loadFixture(): Result<List<AllowedModel>> {
    val stream =
      requireNotNull(javaClass.getResourceAsStream("/model_allowlist_fixture.json")) {
        "Fixture not found on classpath"
      }
    return AllowlistLoader.parse(stream)
  }

  private fun parseRaw(json: String): Result<List<AllowedModel>> =
    AllowlistLoader.parse(json.byteInputStream(Charsets.UTF_8))

  @Test
  fun loadFromFixture_returnsListOfTwoModels() {
    val result = loadFixture()
    assertEquals(2, result.getOrThrow().size)
  }

  @Test
  fun loadFromFixture_allModelsHaveRequiredFields() {
    val models = loadFixture().getOrThrow()
    for (model in models) {
      assertTrue("name empty for ${model.modelId}", model.name.isNotEmpty())
      assertTrue("modelId empty", model.modelId.isNotEmpty())
      assertTrue("modelFile empty for ${model.modelId}", model.modelFile.isNotEmpty())
      assertTrue("commitHash empty for ${model.modelId}", model.commitHash.isNotEmpty())
      assertTrue("sizeInBytes not positive for ${model.modelId}", model.sizeInBytes > 0L)

      val cfg = model.defaultConfig
      assertNotNull("defaultConfig null for ${model.modelId}", cfg)
      assertNotNull("topK null for ${model.modelId}", cfg!!.topK)
      assertNotNull("temperature null for ${model.modelId}", cfg.temperature)
      assertNotNull("accelerators null for ${model.modelId}", cfg.accelerators)

      // Decision T8 guard: GPU must be tried first.
      val firstToken = cfg.accelerators!!.split(",")[0].trim().lowercase()
      assertEquals("first accelerator must be gpu for ${model.modelId}", "gpu", firstToken)

      // Mapped Model sanity: URL points at huggingface.co with correct id/commit/file.
      val mapped = model.toModel()
      assertTrue("mapped URL wrong for ${model.modelId}: ${mapped.url}",
        mapped.url.startsWith("https://huggingface.co/${model.modelId}/resolve/${model.commitHash}/${model.modelFile}"))
    }
  }

  @Test
  fun fixtureMatchesProductionAsset() {
    // Catches drift between src/main/assets/model_allowlist.json and the test fixture.
    // Test runs with core-runtime/ as the working directory (Gradle default).
    val prod = File("src/main/assets/model_allowlist.json")
    val fixture = File("src/test/resources/model_allowlist_fixture.json")
    assertTrue("prod asset missing: ${prod.absolutePath}", prod.exists())
    assertTrue("fixture missing: ${fixture.absolutePath}", fixture.exists())
    assertEquals(
      "fixture drifted from prod asset — sync them",
      prod.readBytes().toList(),
      fixture.readBytes().toList(),
    )
  }

  @Test
  fun parse_rejectsEmptyAllowlist() {
    val result = parseRaw("""{"models": []}""")
    assertTrue(result.isFailure)
    assertTrue(
      result.exceptionOrNull()?.message.orEmpty().contains("Empty allowlist"),
    )
  }

  @Test
  fun parse_rejectsDisallowedModelIdPrefix() {
    val json =
      """{"models":[{"name":"x","modelId":"attacker/evil","modelFile":"a.lm",
      "commitHash":"abc","sizeInBytes":1,"minDeviceMemoryInGb":4,"taskTypes":["llm_chat"]}]}"""
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    assertTrue(
      result.exceptionOrNull()?.message.orEmpty().contains("modelId must match"),
    )
  }

  @Test
  fun parse_rejectsModelFilePathTraversal() {
    val json =
      """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"../evil.lm",
      "commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,"minDeviceMemoryInGb":4,"taskTypes":["llm_chat"]}]}"""
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    assertTrue(
      result.exceptionOrNull()?.message.orEmpty().contains("modelFile must match"),
    )
  }

  @Test
  fun parse_rejectsMalformedCommitHash() {
    val json =
      """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm",
      "commitHash":"HEAD","sizeInBytes":1,"minDeviceMemoryInGb":4,"taskTypes":["llm_chat"]}]}"""
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    assertTrue(
      result.exceptionOrNull()?.message.orEmpty().contains("commitHash must match"),
    )
  }

  @Test
  fun parse_rejectsOversizedModel() {
    val json =
      """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm",
      "commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":10737418241,
      "minDeviceMemoryInGb":4,"taskTypes":["llm_chat"]}]}"""
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    assertTrue(
      result.exceptionOrNull()?.message.orEmpty().contains("sizeInBytes out of range"),
    )
  }

  // --- Phase 3.5 Task 1: minDeviceMemoryInGb plumbing + fail-loud parser ----------------

  @Test
  fun parse_rejectsNullMinDeviceMemory() {
    val json =
      """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm",
      "commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,
      "minDeviceMemoryInGb":null,"taskTypes":["llm_chat"]}]}"""
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected modelId in message: $msg", msg.contains("litert-community/ok"))
    assertTrue("expected minDeviceMemoryInGb in message: $msg", msg.contains("minDeviceMemoryInGb"))
  }

  @Test
  fun parse_rejectsMissingMinDeviceMemory() {
    // Gson maps a missing key to null — must be rejected exactly like an explicit null.
    val json =
      """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm",
      "commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,
      "taskTypes":["llm_chat"]}]}"""
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected modelId in message: $msg", msg.contains("litert-community/ok"))
    assertTrue("expected minDeviceMemoryInGb in message: $msg", msg.contains("minDeviceMemoryInGb"))
  }

  @Test
  fun parse_rejectsOutOfRangeMinDeviceMemory_zero() {
    val json = minimalModelJson(minDeviceMemoryInGb = 0)
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected modelId in message: $msg", msg.contains("litert-community/ok"))
    assertTrue("expected range marker in message: $msg", msg.contains("1..64"))
  }

  @Test
  fun parse_rejectsOutOfRangeMinDeviceMemory_negative() {
    // Defends against silent fail-open: without a range check, `totalBytes >= negative * GB`
    // is always true and the gate would let every device through.
    val json = minimalModelJson(minDeviceMemoryInGb = -1)
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected modelId in message: $msg", msg.contains("litert-community/ok"))
    assertTrue("expected range marker in message: $msg", msg.contains("1..64"))
  }

  @Test
  fun parse_rejectsOutOfRangeMinDeviceMemory_tooLarge() {
    val json = minimalModelJson(minDeviceMemoryInGb = 65)
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected modelId in message: $msg", msg.contains("litert-community/ok"))
    assertTrue("expected range marker in message: $msg", msg.contains("1..64"))
  }

  @Test
  fun parse_acceptsValidMinDeviceMemory() {
    val json = minimalModelJson(minDeviceMemoryInGb = 6)
    val parsed = parseRaw(json).getOrThrow()
    assertEquals(6, parsed.first().minDeviceMemoryInGb)
    // Value must propagate through toModel() into Model.minDeviceMemoryInGb.
    assertEquals(6, parsed.first().toModel().minDeviceMemoryInGb)
  }

  @Test
  fun parse_acceptsBoundaryMinDeviceMemory_one() {
    val parsed = parseRaw(minimalModelJson(minDeviceMemoryInGb = 1)).getOrThrow()
    assertEquals(1, parsed.first().minDeviceMemoryInGb)
  }

  @Test
  fun parse_acceptsBoundaryMinDeviceMemory_sixtyFour() {
    val parsed = parseRaw(minimalModelJson(minDeviceMemoryInGb = 64)).getOrThrow()
    assertEquals(64, parsed.first().minDeviceMemoryInGb)
  }

  @Test
  fun load_logsRejectionToErrorLog() = runTest {
    // Drives the full load→parse→log path. Invalid record (null minDeviceMemoryInGb) feeds the
    // testable internal entry point [AllowlistLoader.loadFromStream]; the contract check is that
    // (a) the rejected record never reaches the result, and (b) ErrorLog gets one event with
    // component "download" mentioning the rejected modelId.
    val invalidJson =
      """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm",
      "commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,
      "minDeviceMemoryInGb":null,"taskTypes":["llm_chat"]}]}"""
    val loader = AllowlistLoader(context, errorLog)

    val result = loader.loadFromStream(invalidJson.byteInputStream(Charsets.UTF_8))

    assertTrue("rejected entries must not reach the registry", result.isFailure)
    assertTrue("errors.log must exist after rejection", errorLogFile.exists())
    val lines = errorLogFile.readLines()
    assertEquals("expected exactly one log entry, got: $lines", 1, lines.size)
    val entry = lines.single()
    assertTrue("missing 'download' component tag in: $entry", entry.contains("download"))
    assertTrue("missing 'model rejected' in: $entry", entry.contains("model rejected"))
    assertTrue("missing rejected modelId in: $entry", entry.contains("litert-community/ok"))
  }

  // --- Phase 2 Task 1: llmSupport* + systemPromptDefault plumbing -----------------------

  private fun minimalModelJson(
    llmSupportFields: String = "",
    systemPromptDefaultField: String = "",
    minDeviceMemoryInGb: Int = 4,
  ): String =
    """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm","commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,"minDeviceMemoryInGb":$minDeviceMemoryInGb,"taskTypes":["llm_chat"]$llmSupportFields,"defaultConfig":{"topK":64,"temperature":1.0,"accelerators":"gpu,cpu","maxTokens":4000$systemPromptDefaultField}}]}"""

  @Test
  fun llmSupportImage_true_parsedCorrectly() {
    val json = minimalModelJson(llmSupportFields = ",\"llmSupportImage\":true")
    val model = parseRaw(json).getOrThrow().single().toModel()
    assertTrue(model.llmSupportImage)
    // Cross-wire guard: setting only Image must not flip the other two.
    assertFalse(model.llmSupportAudio)
    assertFalse(model.llmSupportThinking)
  }

  @Test
  fun llmSupportAudio_true_parsedCorrectly() {
    val json = minimalModelJson(llmSupportFields = ",\"llmSupportAudio\":true")
    val model = parseRaw(json).getOrThrow().single().toModel()
    assertTrue(model.llmSupportAudio)
    assertFalse(model.llmSupportImage)
    assertFalse(model.llmSupportThinking)
  }

  @Test
  fun llmSupportThinking_true_parsedCorrectly() {
    val json = minimalModelJson(llmSupportFields = ",\"llmSupportThinking\":true")
    val model = parseRaw(json).getOrThrow().single().toModel()
    assertTrue(model.llmSupportThinking)
    assertFalse(model.llmSupportImage)
    assertFalse(model.llmSupportAudio)
  }

  @Test
  fun llmSupport_missing_defaultsFalse() {
    val json = minimalModelJson()
    val model = parseRaw(json).getOrThrow().single().toModel()
    assertFalse(model.llmSupportImage)
    assertFalse(model.llmSupportAudio)
    assertFalse(model.llmSupportThinking)
  }

  @Test
  fun defaultConfig_systemPromptDefault_parsed() {
    val json =
      minimalModelJson(systemPromptDefaultField = ",\"systemPromptDefault\":\"You are helpful\"")
    val model = parseRaw(json).getOrThrow().single().toModel().apply { preProcess() }
    val sysConfig = model.configs.find { it.key == ConfigKeys.SYSTEM_PROMPT_DEFAULT }
    assertNotNull("SYSTEM_PROMPT_DEFAULT config missing", sysConfig)
    assertEquals("You are helpful", sysConfig!!.defaultValue)
    assertEquals("You are helpful", model.configValues[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label])
  }

  @Test
  fun defaultConfig_systemPromptDefault_missing_defaultsEmpty() {
    val json = minimalModelJson()
    val model = parseRaw(json).getOrThrow().single().toModel().apply { preProcess() }
    val sysConfig = model.configs.find { it.key == ConfigKeys.SYSTEM_PROMPT_DEFAULT }
    assertNotNull("SYSTEM_PROMPT_DEFAULT config missing", sysConfig)
    assertEquals("", sysConfig!!.defaultValue)
    assertEquals("", model.configValues[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label])
  }

  @Test
  fun defaultConfig_systemPromptDefault_explicitNull_defaultsEmpty() {
    // Gson deserialises a literal `null` into the Kotlin nullable field; `.orEmpty()` must
    // collapse it to "" so downstream consumers never see null.
    val json = minimalModelJson(systemPromptDefaultField = ",\"systemPromptDefault\":null")
    val model = parseRaw(json).getOrThrow().single().toModel().apply { preProcess() }
    assertEquals("", model.configValues[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label])
  }

  @Test
  fun defaultConfig_missingEntirely_systemPromptDefaultEmpty() {
    // When `defaultConfig` is absent, the safe-call chain `defaultConfig?.systemPromptDefault`
    // short-circuits to null, then `.orEmpty()` yields "".
    val json =
      """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm","commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,"minDeviceMemoryInGb":4,"taskTypes":["llm_chat"]}]}"""
    val model = parseRaw(json).getOrThrow().single().toModel().apply { preProcess() }
    assertEquals("", model.configValues[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label])
  }
}
