package app.sanctum.machina.core.registry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.data.AllowedModel
import app.sanctum.machina.core.data.ConfigKeys
import app.sanctum.machina.core.data.RuntimeType
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
  fun loadFromFixture_returnsListOfThreeModels() {
    // Phase 4 Task 1: third row is EmbeddingGemma-300M (taskTypes=["llm_embedding"]).
    val result = loadFixture()
    assertEquals(3, result.getOrThrow().size)
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

      // Mapped Model sanity: URL points at huggingface.co with correct id/commit/file.
      val mapped = model.toModel()
      assertTrue("mapped URL wrong for ${model.modelId}: ${mapped.url}",
        mapped.url.startsWith("https://huggingface.co/${model.modelId}/resolve/${model.commitHash}/${model.modelFile}"))

      // LLM-chat rows carry defaultConfig (sampler knobs + accelerator order).
      // Embedder rows do not — they carry defaultRagConfig instead, validated separately.
      if (model.taskTypes.contains("llm_chat")) {
        val cfg = model.defaultConfig
        assertNotNull("defaultConfig null for ${model.modelId}", cfg)
        assertNotNull("topK null for ${model.modelId}", cfg!!.topK)
        assertNotNull("temperature null for ${model.modelId}", cfg.temperature)
        assertNotNull("accelerators null for ${model.modelId}", cfg.accelerators)

        // Decision T8 guard: GPU must be tried first.
        val firstToken = cfg.accelerators!!.split(",")[0].trim().lowercase()
        assertEquals("first accelerator must be gpu for ${model.modelId}", "gpu", firstToken)
      }
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

  // --- Phase 3.7 Task 1: maxContextLength range validation + toModel propagation ----------

  /** Compose a minimal model JSON with explicit `maxContextLength`. */
  private fun maxContextJson(maxContextLength: Int): String =
    """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm",
    "commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,
    "minDeviceMemoryInGb":4,"taskTypes":["llm_chat"],
    "defaultConfig":{"topK":64,"temperature":1.0,"accelerators":"gpu,cpu",
    "maxTokens":4000,"maxContextLength":$maxContextLength}}]}"""

  @Test
  fun parse_rejectsMaxContextLengthZero() {
    val result = parseRaw(maxContextJson(0))
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected maxContextLength in message: $msg", msg.contains("maxContextLength"))
    assertTrue("expected range marker in message: $msg", msg.contains("1024..131072"))
  }

  @Test
  fun parse_rejectsMaxContextLengthNegative() {
    val result = parseRaw(maxContextJson(-1))
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected range marker in message: $msg", msg.contains("1024..131072"))
  }

  @Test
  fun parse_rejectsMaxContextLengthOverCeiling() {
    val result = parseRaw(maxContextJson(200000))
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected range marker in message: $msg", msg.contains("1024..131072"))
  }

  @Test
  fun parse_acceptsMaxContextLengthBoundary_lo() {
    val parsed = parseRaw(maxContextJson(1024)).getOrThrow()
    assertEquals(1024, parsed.single().defaultConfig?.maxContextLength)
  }

  @Test
  fun parse_acceptsMaxContextLengthBoundary_hi() {
    val parsed = parseRaw(maxContextJson(131072)).getOrThrow()
    assertEquals(131072, parsed.single().defaultConfig?.maxContextLength)
  }

  @Test
  fun parse_acceptsMaxContextLengthMissing() {
    // No defaultConfig key for maxContextLength — must pass (range check is null-tolerant).
    val json =
      """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm",
      "commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,
      "minDeviceMemoryInGb":4,"taskTypes":["llm_chat"],
      "defaultConfig":{"topK":64,"temperature":1.0,"accelerators":"gpu,cpu","maxTokens":4000}}]}"""
    val parsed = parseRaw(json).getOrThrow()
    assertEquals(null, parsed.single().defaultConfig?.maxContextLength)
  }

  @Test
  fun loadFromStream_logsRejectionForOutOfRangeMaxContextLength() = runTest {
    val loader = AllowlistLoader(context, errorLog)

    val result = loader.loadFromStream(maxContextJson(0).byteInputStream(Charsets.UTF_8))

    assertTrue("rejected entries must not reach the registry", result.isFailure)
    assertTrue("errors.log must exist after rejection", errorLogFile.exists())
    val lines = errorLogFile.readLines()
    assertEquals("expected exactly one log entry, got: $lines", 1, lines.size)
    val entry = lines.single()
    assertTrue("missing 'download' component tag in: $entry", entry.contains("download"))
    assertTrue("missing 'model rejected' in: $entry", entry.contains("model rejected"))
    assertTrue("missing maxContextLength marker in: $entry", entry.contains("maxContextLength"))
  }

  @Test
  fun toModel_propagatesMaxContextLengthIntoConfigValues() {
    // AllowedModel.toModel() must surface defaultConfig.maxContextLength as a String value-carrier
    // on Model.configValues, keyed by MAX_CONTEXT_LENGTH.label.
    val model = parseRaw(maxContextJson(32000)).getOrThrow().single().toModel().apply { preProcess() }
    assertEquals(
      "MAX_CONTEXT_LENGTH must reach configValues as a String (LabelConfig carrier shape)",
      "32000",
      model.configValues[ConfigKeys.MAX_CONTEXT_LENGTH.label],
    )
  }

  @Test
  fun toModel_omitsMaxContextLengthWhenDefaultConfigMissing() {
    val json =
      """{"models":[{"name":"x","modelId":"litert-community/ok","modelFile":"a.lm",
      "commitHash":"7fa1d78473894f7e736a21d920c3aa80f950c0db","sizeInBytes":1,
      "minDeviceMemoryInGb":4,"taskTypes":["llm_chat"],
      "defaultConfig":{"topK":64,"temperature":1.0,"accelerators":"gpu,cpu","maxTokens":4000}}]}"""
    val model = parseRaw(json).getOrThrow().single().toModel().apply { preProcess() }
    assertFalse(
      "configValues must not contain MAX_CONTEXT_LENGTH when defaultConfig omits it",
      model.configValues.containsKey(ConfigKeys.MAX_CONTEXT_LENGTH.label),
    )
  }

  // --- Phase 4 Task 1: EmbeddingGemma allowlist row ------------------------------------

  /**
   * Compose an EmbeddingGemma-style row. Embedder rows differ structurally from chat rows:
   * `taskTypes` = ["llm_embedding"], no `defaultConfig`, presence of `defaultRagConfig` with
   * Matryoshka-enum-restricted embeddingDim. Org allowed: `litert-community` OR `google`.
   */
  private fun embeddingGemmaJson(
    modelId: String = "litert-community/embeddinggemma-300m",
    taskTypes: String = "[\"llm_embedding\"]",
    defaultRagConfig: String =
      "{\"chunkSize\":800,\"chunkOverlap\":100,\"topK\":4,\"embeddingDim\":768}",
  ): String =
    """{"models":[{"name":"EmbeddingGemma-300M","modelId":"$modelId",
    "modelFile":"embeddinggemma-300M_seq2048_mixed-precision.tflite",
    "commitHash":"e054b9751a203d96508b87532585e20730f23ef6",
    "sizeInBytes":205520896,"minDeviceMemoryInGb":4,
    "taskTypes":$taskTypes,"defaultRagConfig":$defaultRagConfig}]}"""

  @Test
  fun accepts_google_org_for_embedder_row() {
    // Decision 4: regex widened to ^(litert-community|google)/... so google/* is parseable
    // even though our final ship-row points at litert-community/embeddinggemma-300m
    // (Decision 12 deviation — recorded in decisions.md).
    val json = embeddingGemmaJson(modelId = "google/embeddinggemma-300m")
    val parsed = parseRaw(json).getOrThrow()
    val model = parsed.single().toModel()
    assertEquals(RuntimeType.LITERT_INTERPRETER, model.runtimeType)
    assertNotNull("defaultRagConfig must propagate to domain Model", model.defaultRagConfig)
    assertEquals(800, model.defaultRagConfig!!.chunkSize)
    assertEquals(100, model.defaultRagConfig!!.chunkOverlap)
    assertEquals(4, model.defaultRagConfig!!.topK)
    assertEquals(768, model.defaultRagConfig!!.embeddingDim)
  }

  @Test
  fun accepts_litert_community_org_regression() {
    // Regression guard: widening the regex must not break the existing org.
    val json = embeddingGemmaJson(modelId = "litert-community/embeddinggemma-300m")
    val parsed = parseRaw(json).getOrThrow()
    assertEquals("litert-community/embeddinggemma-300m", parsed.single().modelId)
  }

  @Test
  fun rejects_third_party_org() {
    val json = embeddingGemmaJson(modelId = "evil-org/foo")
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    assertTrue(
      result.exceptionOrNull()?.message.orEmpty().contains("modelId must match"),
    )
  }

  @Test
  fun derives_runtime_type_litert_interpreter_from_embedding_task() {
    val model = parseRaw(embeddingGemmaJson()).getOrThrow().single().toModel()
    assertEquals(RuntimeType.LITERT_INTERPRETER, model.runtimeType)
    assertFalse("embedder must not be isLlm (no chat configs)", model.isLlm)
    assertTrue("embedder must have empty configs", model.configs.isEmpty())
  }

  @Test
  fun derives_runtime_type_litert_lm_from_chat_tasks() {
    // Regression: existing Gemma 4 row with llm_chat must still derive LITERT_LM,
    // not change behaviour after the runtimeType derivation rule lands.
    val json = minimalModelJson()  // taskTypes = ["llm_chat"]
    val model = parseRaw(json).getOrThrow().single().toModel()
    assertEquals(RuntimeType.LITERT_LM, model.runtimeType)
    assertTrue("chat model must be isLlm", model.isLlm)
  }

  @Test
  fun rejects_malformed_default_rag_config_overlap_ge_chunkSize() {
    val json = embeddingGemmaJson(
      defaultRagConfig =
        "{\"chunkSize\":100,\"chunkOverlap\":100,\"topK\":4,\"embeddingDim\":768}",
    )
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected defaultRagConfig marker: $msg", msg.contains("defaultRagConfig"))
    assertTrue("expected overlap/chunkSize marker: $msg",
      msg.contains("chunkOverlap") || msg.contains("chunkSize"))
  }

  @Test
  fun rejects_unknown_embedding_dim() {
    // 384 is NOT in Matryoshka enum {128, 256, 512, 768} from EmbeddingGemma model card.
    val json = embeddingGemmaJson(
      defaultRagConfig =
        "{\"chunkSize\":800,\"chunkOverlap\":100,\"topK\":4,\"embeddingDim\":384}",
    )
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected embeddingDim marker: $msg", msg.contains("embeddingDim"))
  }

  @Test
  fun rejects_empty_task_types() {
    // taskTypes drives runtimeType derivation — must be non-empty to be unambiguous.
    val json = embeddingGemmaJson(taskTypes = "[]")
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected taskTypes marker: $msg", msg.contains("taskTypes"))
  }

  @Test
  fun rejects_mixed_task_types_embedding_and_chat() {
    // Decision 4: single-purpose enforcement — a row may not advertise both llm_embedding
    // and chat tasks. The Model would otherwise come out incoherent: LITERT_INTERPRETER
    // runtime AND isLlm=true AND populated chat configs.
    val json = embeddingGemmaJson(taskTypes = "[\"llm_embedding\",\"llm_chat\"]")
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected mix marker: $msg", msg.contains("must not mix"))
  }

  @Test
  fun rejects_zero_or_negative_chunk_size() {
    val json = embeddingGemmaJson(
      defaultRagConfig =
        "{\"chunkSize\":0,\"chunkOverlap\":0,\"topK\":4,\"embeddingDim\":768}",
    )
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected chunkSize marker: $msg", msg.contains("chunkSize"))
  }

  @Test
  fun rejects_oversized_chunk_size() {
    // Defense-in-depth: parser refuses Int.MAX_VALUE-class values so a future schema edit
    // can't OOM the chunker downstream (security-auditor round-1 low finding).
    val json = embeddingGemmaJson(
      defaultRagConfig =
        "{\"chunkSize\":100000,\"chunkOverlap\":100,\"topK\":4,\"embeddingDim\":768}",
    )
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected chunkSize range marker: $msg",
      msg.contains("chunkSize") && msg.contains("65536"))
  }

  @Test
  fun rejects_negative_chunk_overlap() {
    val json = embeddingGemmaJson(
      defaultRagConfig =
        "{\"chunkSize\":800,\"chunkOverlap\":-1,\"topK\":4,\"embeddingDim\":768}",
    )
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected chunkOverlap marker: $msg", msg.contains("chunkOverlap"))
  }

  @Test
  fun rejects_zero_or_negative_top_k() {
    val json = embeddingGemmaJson(
      defaultRagConfig =
        "{\"chunkSize\":800,\"chunkOverlap\":100,\"topK\":0,\"embeddingDim\":768}",
    )
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected topK marker: $msg", msg.contains("topK"))
  }

  @Test
  fun rejects_oversized_top_k() {
    val json = embeddingGemmaJson(
      defaultRagConfig =
        "{\"chunkSize\":800,\"chunkOverlap\":100,\"topK\":1000,\"embeddingDim\":768}",
    )
    val result = parseRaw(json)
    assertTrue(result.isFailure)
    val msg = result.exceptionOrNull()?.message.orEmpty()
    assertTrue("expected topK range marker: $msg",
      msg.contains("topK") && msg.contains("256"))
  }

  @Test
  fun accepts_embedder_row_without_default_rag_config() {
    // Contract pin: defaultRagConfig is OPTIONAL on embedder rows. Spec implies it but the
    // parser does not require it (validation only fires when the field is present). If a
    // future row ships without ragDefaults, the Model lands with defaultRagConfig=null and
    // T9 falls back to its hardcoded RagDefaults baseline. Test guards this contract.
    val jsonNoRag = """{"models":[{"name":"x","modelId":"litert-community/embeddinggemma-300m",
      "modelFile":"a.tflite","commitHash":"e054b9751a203d96508b87532585e20730f23ef6",
      "sizeInBytes":1,"minDeviceMemoryInGb":4,"taskTypes":["llm_embedding"]}]}"""
    val parsed = parseRaw(jsonNoRag).getOrThrow()
    val model = parsed.single().toModel()
    assertEquals(RuntimeType.LITERT_INTERPRETER, model.runtimeType)
    assertEquals(null, model.defaultRagConfig)
  }

  @Test
  fun best_for_task_types_parsed_when_present() {
    // bestForTaskTypes was promoted to first-class on AllowedModel in this task (was Gson-
    // silently-ignored before). Pin that the JSON field reaches the parsed object.
    val parsed = parseRaw(embeddingGemmaJson()).getOrThrow().single()
    // The default fixture omits bestForTaskTypes → must be null.
    assertEquals(null, parsed.bestForTaskTypes)

    val withBest = """{"models":[{"name":"x","modelId":"litert-community/embeddinggemma-300m",
      "modelFile":"a.tflite","commitHash":"e054b9751a203d96508b87532585e20730f23ef6",
      "sizeInBytes":1,"minDeviceMemoryInGb":4,"taskTypes":["llm_embedding"],
      "bestForTaskTypes":["llm_embedding"],
      "defaultRagConfig":{"chunkSize":800,"chunkOverlap":100,"topK":4,"embeddingDim":768}}]}"""
    val withBestParsed = parseRaw(withBest).getOrThrow().single()
    assertEquals(listOf("llm_embedding"), withBestParsed.bestForTaskTypes)
  }

  @Test
  fun bundled_flag_defaults_false_when_missing() {
    // Regression guard: every pre-Task-17 row (`Gemma-4-E2B-it`, `Gemma-4-E4B-it`) omits
    // `bundled`; the parser must default to `false`, otherwise DefaultModelRegistry would
    // surface chat rows as SUCCEEDED on first emission and skip the download flow entirely.
    val parsed = parseRaw(embeddingGemmaJson()).getOrThrow().single()
    assertFalse("bundled must default to false when key absent", parsed.bundled)
    assertFalse("bundled must propagate as false to domain Model", parsed.toModel().bundled)
  }

  @Test
  fun bundled_true_propagates_through_toModel() {
    // Task 17: EmbeddingGemma row carries `bundled: true`. AllowlistLoader must accept it
    // without complaint and propagate the flag onto the domain `Model` so EmbedderRegistry
    // can branch on it at warmup time.
    val json = """{"models":[{"name":"EmbeddingGemma-300M",
      "modelId":"litert-community/embeddinggemma-300m",
      "modelFile":"embeddinggemma-300M_seq2048_mixed-precision.tflite",
      "commitHash":"e054b9751a203d96508b87532585e20730f23ef6",
      "sizeInBytes":205520896,"minDeviceMemoryInGb":4,
      "bundled":true,
      "taskTypes":["llm_embedding"],
      "defaultRagConfig":{"chunkSize":800,"chunkOverlap":100,"topK":4,"embeddingDim":768}}]}"""
    val parsed = parseRaw(json).getOrThrow().single()
    assertTrue("AllowedModel.bundled must reflect JSON value", parsed.bundled)
    assertTrue("Model.bundled must reflect JSON value", parsed.toModel().bundled)
  }

  @Test
  fun parses_real_bundled_allowlist_with_three_rows() {
    // Asserts the bundled prod asset (after adding EmbeddingGemma row in this task)
    // contains all three rows and they all parse — aggregate doesn't fail-fast.
    val prod = File("src/main/assets/model_allowlist.json")
    assertTrue("prod asset missing: ${prod.absolutePath}", prod.exists())
    val parsed = AllowlistLoader.parse(prod.inputStream()).getOrThrow()
    assertEquals("expected 3 rows after Phase-4-T1 add", 3, parsed.size)
    val embedder = parsed.find { it.modelId.endsWith("/embeddinggemma-300m") }
    assertNotNull("EmbeddingGemma row missing from prod asset", embedder)
    assertEquals(listOf("llm_embedding"), embedder!!.taskTypes)
    assertEquals(RuntimeType.LITERT_INTERPRETER, embedder.toModel().runtimeType)
  }

  // --- Pre-existing tests below ---------------------------------------------------------

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
