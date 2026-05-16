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

import android.content.Context
import com.google.gson.annotations.SerializedName
import java.io.File

data class ModelDataFile(
  val name: String,
  val url: String,
  val downloadFileName: String,
  val sizeInBytes: Long,
)

const val IMPORTS_DIR = "__imports"
private val NORMALIZE_NAME_REGEX = Regex("[^a-zA-Z0-9]")

data class PromptTemplate(val title: String, val description: String, val prompt: String)

enum class RuntimeType {
  @SerializedName("unknown") UNKNOWN,
  @SerializedName("litert_lm") LITERT_LM,
  @SerializedName("aicore") AICORE,
  // Phase 4 Task 1 (Decision 4): EmbeddingGemma runs on the regular LiteRT Interpreter
  // runtime (NOT litert-lm). Derived by AllowlistLoader from `taskTypes` containing
  // "llm_embedding"; never serialized from the allowlist JSON directly.
  @SerializedName("litert_interpreter") LITERT_INTERPRETER,
}

/**
 * Phase 4 Task 1 (Decision 11): defaults for RAG parameters, carried per-model from the
 * allowlist row's `defaultRagConfig` block. Present only on embedder rows (taskTypes contains
 * `llm_embedding`); null for chat models. Used as the floor for per-project overrides
 * (`projects.rag_overrides_json`) — see ProjectSettingsViewModel (Task 9).
 */
data class RagDefaults(
  val chunkSize: Int,
  val chunkOverlap: Int,
  val topK: Int,
  val embeddingDim: Int,
)

enum class AICoreModelReleaseStage {
  @SerializedName("stable") STABLE,
  @SerializedName("preview") PREVIEW,
}

enum class AICoreModelPreference {
  @SerializedName("fast") FAST,
  @SerializedName("full") FULL,
}

data class Model(
  val name: String,
  val modelId: String = "",
  val displayName: String = "",
  val info: String = "",
  var configs: List<Config> = listOf(),
  val learnMoreUrl: String = "",
  val bestForTaskIds: List<String> = listOf(),
  /**
   * Phase 3.5 gate input: total RAM threshold below which the model is hidden from
   * Model Manager. Null is rejected by the parser — see Phase 3.5 tech-spec.
   */
  val minDeviceMemoryInGb: Int? = null,
  val url: String = "",
  val sizeInBytes: Long = 0L,
  val downloadFileName: String = "_",
  val version: String = "_",
  val extraDataFiles: List<ModelDataFile> = listOf(),
  val isLlm: Boolean = false,
  val aicoreReleaseStage: AICoreModelReleaseStage? = null,
  val aicorePreference: AICoreModelPreference? = null,
  val runtimeType: RuntimeType = RuntimeType.UNKNOWN,
  val localFileRelativeDirPathOverride: String = "",
  val localModelFilePathOverride: String = "",
  val showRunAgainButton: Boolean = true,
  val showBenchmarkButton: Boolean = true,
  val isZip: Boolean = false,
  val unzipDir: String = "",
  val llmPromptTemplates: List<PromptTemplate> = listOf(),
  val llmSupportImage: Boolean = false,
  val llmSupportAudio: Boolean = false,
  val llmSupportTinyGarden: Boolean = false,
  val llmSupportMobileActions: Boolean = false,
  val llmSupportThinking: Boolean = false,
  val llmMaxToken: Int = 0,
  val accelerators: List<Accelerator> = listOf(),
  val visionAccelerator: Accelerator = Accelerator.GPU,
  val imported: Boolean = false,
  /**
   * Phase 4 Task 17: row ships inside the APK's assets/ rather than downloading from
   * Hugging Face. Bundled rows skip the [DownloadRepository] path, are surfaced as
   * `SUCCEEDED` immediately by [DefaultModelRegistry.refreshAllowlist], and are loaded
   * by their owning runtime through asset-extraction (currently consumed only by
   * [app.sanctum.machina.engine.EmbedderRegistry] for EmbeddingGemma). Semantically
   * distinct from [imported] — `imported` means «user picked a .gguf via SAF», `bundled`
   * means «we shipped this file inside the APK». Both opt out of the download flow but
   * for different reasons.
   */
  val bundled: Boolean = false,
  var normalizedName: String = "",
  var instance: Any? = null,
  var initializing: Boolean = false,
  var cleanUpAfterInit: Boolean = false,
  var configValues: Map<String, Any> = mapOf(),
  var prevConfigValues: Map<String, Any> = mapOf(),
  var totalBytes: Long = 0L,
  /**
   * Phase 4 Task 1 (Decision 11): RAG parameter defaults from the allowlist row.
   * Non-null only for embedder rows; consumed by ProjectSettingsViewModel as the
   * baseline before per-project overrides are merged.
   */
  val defaultRagConfig: RagDefaults? = null,
) {
  init {
    normalizedName = NORMALIZE_NAME_REGEX.replace(name, "_")
  }

  fun preProcess() {
    val configValues: MutableMap<String, Any> = mutableMapOf()
    for (config in this.configs) {
      configValues[config.key.label] = config.defaultValue
    }
    this.configValues = configValues
    this.totalBytes = this.sizeInBytes + this.extraDataFiles.sumOf { it.sizeInBytes }
  }

  fun getPath(context: Context, fileName: String = downloadFileName): String {
    if (imported) {
      return listOf(context.getExternalFilesDir(null)?.absolutePath ?: "", fileName)
        .joinToString(File.separator)
    }

    if (localModelFilePathOverride.isNotEmpty()) {
      return localModelFilePathOverride
    }

    if (localFileRelativeDirPathOverride.isNotEmpty()) {
      return listOf(
          context.getExternalFilesDir(null)?.absolutePath ?: "",
          localFileRelativeDirPathOverride,
          fileName,
        )
        .joinToString(File.separator)
    }

    val baseDir =
      listOf(context.getExternalFilesDir(null)?.absolutePath ?: "", normalizedName, version)
        .joinToString(File.separator)
    return if (this.isZip && this.unzipDir.isNotEmpty()) {
      listOf(baseDir, this.unzipDir).joinToString(File.separator)
    } else {
      listOf(baseDir, fileName).joinToString(File.separator)
    }
  }

  fun getIntConfigValue(key: ConfigKey, defaultValue: Int = 0): Int {
    return getTypedConfigValue(key = key, valueType = ValueType.INT, defaultValue = defaultValue)
      as Int
  }

  fun getFloatConfigValue(key: ConfigKey, defaultValue: Float = 0.0f): Float {
    return getTypedConfigValue(key = key, valueType = ValueType.FLOAT, defaultValue = defaultValue)
      as Float
  }

  fun getBooleanConfigValue(key: ConfigKey, defaultValue: Boolean = false): Boolean {
    return getTypedConfigValue(
      key = key,
      valueType = ValueType.BOOLEAN,
      defaultValue = defaultValue,
    )
      as Boolean
  }

  fun getStringConfigValue(key: ConfigKey, defaultValue: String = ""): String {
    return getTypedConfigValue(key = key, valueType = ValueType.STRING, defaultValue = defaultValue)
      as String
  }

  fun getExtraDataFile(name: String): ModelDataFile? {
    return extraDataFiles.find { it.name == name }
  }

  private fun getTypedConfigValue(key: ConfigKey, valueType: ValueType, defaultValue: Any): Any {
    return convertValueToTargetType(
      value = configValues.getOrDefault(key.label, defaultValue),
      valueType = valueType,
    )
  }
}

enum class ModelDownloadStatusType {
  NOT_DOWNLOADED,
  PARTIALLY_DOWNLOADED,
  IN_PROGRESS,
  UNZIPPING,
  SUCCEEDED,
  FAILED,
}

data class ModelDownloadStatus(
  val status: ModelDownloadStatusType,
  val totalBytes: Long = 0,
  val receivedBytes: Long = 0,
  val errorMessage: String = "",
  val bytesPerSecond: Long = 0,
  val remainingMs: Long = 0,
)

val EMPTY_MODEL: Model =
  Model(name = "empty", downloadFileName = "empty.tflite", url = "", sizeInBytes = 0L)
