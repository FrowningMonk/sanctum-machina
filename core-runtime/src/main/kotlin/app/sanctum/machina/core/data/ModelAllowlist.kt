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
data class AllowedModel(
  val name: String,
  val modelId: String,
  val modelFile: String,
  val commitHash: String,
  val sizeInBytes: Long,
  val taskTypes: List<String>,
  val description: String = "",
  val version: String = "",
) {
  fun toModel(): Model {
    val downloadUrl = "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"
    val learnMoreUrl = "https://huggingface.co/$modelId"

    val isLlmModel = taskTypes.contains(TASK_ID_LLM_CHAT) || taskTypes.isEmpty()

    val configs: List<Config> =
      if (isLlmModel) {
        createLlmChatConfigs(accelerators = DEFAULT_ACCELERATORS)
      } else {
        listOf()
      }

    return Model(
      name = name,
      version = if (version.isNotEmpty()) version else commitHash,
      info = description,
      url = downloadUrl,
      sizeInBytes = sizeInBytes,
      configs = configs,
      downloadFileName = modelFile,
      showBenchmarkButton = !isLlmModel,
      showRunAgainButton = !isLlmModel,
      learnMoreUrl = learnMoreUrl,
      accelerators = DEFAULT_ACCELERATORS,
      visionAccelerator = DEFAULT_VISION_ACCELERATOR,
      llmMaxToken = DEFAULT_MAX_TOKEN,
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
