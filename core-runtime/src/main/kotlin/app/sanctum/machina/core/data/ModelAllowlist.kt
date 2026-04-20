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
) {
  fun toModel(): Model {
    val downloadUrl = "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"
    val learnMoreUrl = "https://huggingface.co/$modelId"

    val isLlmModel = taskTypes.contains(TASK_ID_LLM_CHAT) || taskTypes.isEmpty()

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
          accelerators = accelerators,
          defaultSystemPrompt = systemPromptDefault,
        )
      } else {
        listOf()
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
      isLlm = isLlmModel,
      runtimeType = RuntimeType.LITERT_LM,
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
