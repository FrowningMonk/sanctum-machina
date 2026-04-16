package app.sanctum.machina.core.registry

import app.sanctum.machina.core.data.AllowedModel
import app.sanctum.machina.core.data.ConfigKeys
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowlistLoaderTest {

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
      "commitHash":"abc","sizeInBytes":1,"taskTypes":["llm_chat"]}]}"""
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
      "commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,"taskTypes":["llm_chat"]}]}"""
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
      "commitHash":"HEAD","sizeInBytes":1,"taskTypes":["llm_chat"]}]}"""
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
      "taskTypes":["llm_chat"]}]}"""
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    assertTrue(
      result.exceptionOrNull()?.message.orEmpty().contains("sizeInBytes out of range"),
    )
  }

  // --- Phase 2 Task 1: llmSupport* + systemPromptDefault plumbing -----------------------

  private fun minimalModelJson(
    llmSupportFields: String = "",
    systemPromptDefaultField: String = "",
  ): String =
    """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm","commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,"taskTypes":["llm_chat"]$llmSupportFields,"defaultConfig":{"topK":64,"temperature":1.0,"accelerators":"gpu,cpu","maxTokens":4000$systemPromptDefaultField}}]}"""

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
      """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm","commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,"taskTypes":["llm_chat"]}]}"""
    val model = parseRaw(json).getOrThrow().single().toModel().apply { preProcess() }
    assertEquals("", model.configValues[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label])
  }
}
