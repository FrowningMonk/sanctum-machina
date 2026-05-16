/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.sanctum.machina.core.data

/**
 * Phase 1: GPU/CPU only. AICore/NPU branches and per-SOC model files were stripped per user-spec
 * D1. The allowlist carries the minimum fields consumed by AllowlistLoader (Task 4) and the
 * downstream runtime.
 */
data class AllowedModelConfig(
  val topK: Int? = null,
  val topP: Float? = null,
  val temperature: Float? = null,
  val maxContextLength: Int? = null,
  val maxTokens: Int? = null,
  val accelerators: String? = null,
  val visionAccelerator: String? = null,
  val systemPromptDefault: String? = null,
)

/**
 * Phase 4 Task 1 (Decision 11): per-row RAG parameter defaults for embedder rows.
 * Parsed only when the row carries `taskTypes` containing `llm_embedding`. Validation
 * (overlap < chunkSize, embeddingDim in Matryoshka enum) lives in [AllowlistLoader.parse].
 */
data class AllowedRagConfig(
  val chunkSize: Int,
  val chunkOverlap: Int,
  val topK: Int,
  val embeddingDim: Int,
)

data class AllowedModel(
  val name: String,
  val modelId: String,
  val modelFile: String,
  val commitHash: String,
  val sizeInBytes: Long,
  val taskTypes: List<String>,
  val description: String? = null,
  val version: String? = null,
  val defaultConfig: AllowedModelConfig? = null,
  val llmSupportImage: Boolean = false,
  val llmSupportAudio: Boolean = false,
  val llmSupportThinking: Boolean = false,
  val minDeviceMemoryInGb: Int? = null,
  // Phase 4 Task 1: parsed but informational only — used by ModelManager UX to label
  // a row's "best for" surface. Already present in JSON for Gemma 4 rows; previously
  // ignored by Gson, now first-class.
  val bestForTaskTypes: List<String>? = null,
  // Phase 4 Task 1 (Decision 11): present only on embedder rows.
  val defaultRagConfig: AllowedRagConfig? = null,
  // Phase 4 Task 17 (user-spec AC-9 option «в»): row ships inside the APK rather than
  // downloading from Hugging Face. Today only set on the EmbeddingGemma row (HF blob is
  // gated; embedding the auth token would violate the «no API keys» manifest constraint).
  val bundled: Boolean = false,
) {
  fun toModel(): Model {
    val downloadUrl = "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"
    val learnMoreUrl = "https://huggingface.co/$modelId"

    // Decision 4: derive runtime from taskTypes — embedders run on LiteRT Interpreter,
    // everything else (chat tasks) on litert-lm. AllowlistLoader.parse already enforces
    // non-empty taskTypes, so the fallback below stays predictable for chat rows.
    val isEmbedder = taskTypes.contains(TASK_ID_LLM_EMBEDDING)
    val isLlmModel = taskTypes.contains(TASK_ID_LLM_CHAT)
    val derivedRuntimeType: RuntimeType =
      if (isEmbedder) RuntimeType.LITERT_INTERPRETER else RuntimeType.LITERT_LM

    val accelerators: List<Accelerator> =
      defaultConfig?.accelerators?.let { parseAccelerators(it) } ?: DEFAULT_ACCELERATORS
    val visionAccelerator: Accelerator =
      defaultConfig?.visionAccelerator?.let { parseAccelerator(it) } ?: DEFAULT_VISION_ACCELERATOR
    val maxToken: Int = defaultConfig?.maxTokens ?: DEFAULT_MAX_TOKEN
    val systemPromptDefault: String = defaultConfig?.systemPromptDefault.orEmpty()

    val configs: List<Config> =
      if (isLlmModel) {
        createLlmChatConfigs(
          defaultTopK = defaultConfig?.topK ?: DEFAULT_TOPK,
          defaultTopP = defaultConfig?.topP ?: DEFAULT_TOPP,
          defaultTemperature = defaultConfig?.temperature ?: DEFAULT_TEMPERATURE,
          defaultMaxToken = maxToken,
          defaultMaxContextLength = defaultConfig?.maxContextLength,
          accelerators = accelerators,
          defaultSystemPrompt = systemPromptDefault,
        )
      } else {
        listOf()
      }

    val ragDefaults: RagDefaults? =
      defaultRagConfig?.let {
        RagDefaults(
          chunkSize = it.chunkSize,
          chunkOverlap = it.chunkOverlap,
          topK = it.topK,
          embeddingDim = it.embeddingDim,
        )
      }

    return Model(
      name = name,
      modelId = modelId,
      version = version.takeUnless { it.isNullOrEmpty() } ?: commitHash,
      info = description.orEmpty(),
      url = downloadUrl,
      sizeInBytes = sizeInBytes,
      configs = configs,
      downloadFileName = modelFile,
      showBenchmarkButton = !isLlmModel,
      showRunAgainButton = !isLlmModel,
      learnMoreUrl = learnMoreUrl,
      accelerators = accelerators,
      visionAccelerator = visionAccelerator,
      llmMaxToken = maxToken,
      llmSupportImage = llmSupportImage,
      llmSupportAudio = llmSupportAudio,
      llmSupportThinking = llmSupportThinking,
      minDeviceMemoryInGb = minDeviceMemoryInGb,
      isLlm = isLlmModel,
      runtimeType = derivedRuntimeType,
      defaultRagConfig = ragDefaults,
      bundled = bundled,
    )
  }

  override fun toString(): String {
    return "$modelId/$modelFile"
  }
}

/** The model allowlist. */
data class ModelAllowlist(val models: List<AllowedModel>)

private fun parseAccelerators(raw: String): List<Accelerator> =
  raw.split(",").mapNotNull { token ->
    parseAcceleratorOrNull(token)
  }.ifEmpty { DEFAULT_ACCELERATORS }

private fun parseAccelerator(raw: String): Accelerator =
  parseAcceleratorOrNull(raw) ?: DEFAULT_VISION_ACCELERATOR

private fun parseAcceleratorOrNull(raw: String): Accelerator? =
  when (raw.trim().lowercase()) {
    "gpu" -> Accelerator.GPU
    "cpu" -> Accelerator.CPU
    else -> null
  }
