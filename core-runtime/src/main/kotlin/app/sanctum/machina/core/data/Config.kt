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

import androidx.annotation.StringRes
import kotlin.math.abs

enum class ConfigEditorType {
  LABEL,
  NUMBER_SLIDER,
  BOOLEAN_SWITCH,
  SEGMENTED_BUTTON,
  BOTTOMSHEET_SELECTOR,
}

enum class ValueType {
  INT,
  FLOAT,
  DOUBLE,
  STRING,
  BOOLEAN,
}

data class ConfigKey(val id: String, val label: String)

object ConfigKeys {
  val MAX_TOKENS = ConfigKey("max_tokens", "Max tokens")
  val MAX_CONTEXT_LENGTH = ConfigKey("max_context_length", "Max context length")
  val TOPK = ConfigKey("topk", "TopK")
  val TOPP = ConfigKey("topp", "TopP")
  val TEMPERATURE = ConfigKey("temperature", "Temperature")
  val DEFAULT_MAX_TOKENS = ConfigKey("default_max_tokens", "Default max tokens")
  val DEFAULT_TOPK = ConfigKey("default_topk", "Default TopK")
  val DEFAULT_TOPP = ConfigKey("default_topp", "Default TopP")
  val DEFAULT_TEMPERATURE = ConfigKey("default_temperature", "Default temperature")
  val SUPPORT_IMAGE = ConfigKey("support_image", "Support image")
  val SUPPORT_AUDIO = ConfigKey("support_audio", "Support audio")
  val SUPPORT_TINY_GARDEN = ConfigKey("support_tiny_garden", "Support tiny garden")
  val SUPPORT_MOBILE_ACTIONS = ConfigKey("support_mobile_actions", "Support mobile actions")
  val SUPPORT_THINKING = ConfigKey("support_thinking", "Support thinking")
  val ENABLE_THINKING = ConfigKey("enable_thinking", "Enable thinking")
  val SYSTEM_PROMPT_DEFAULT = ConfigKey("system_prompt_default", "Default system prompt")
  val MAX_RESULT_COUNT = ConfigKey("max_result_count", "Max result count")
  val USE_GPU = ConfigKey("use_gpu", "Use GPU")
  val ACCELERATOR = ConfigKey("accelerator", "Accelerator")
  val VISION_ACCELERATOR = ConfigKey("vision_accelerator", "Vision accelerator")
  val COMPATIBLE_ACCELERATORS = ConfigKey("compatible_accelerators", "Compatible accelerators")
  val WARM_UP_ITERATIONS = ConfigKey("warm_up_iterations", "Warm up iterations")
  val BENCHMARK_ITERATIONS = ConfigKey("benchmark_iterations", "Benchmark iterations")
  val ITERATIONS = ConfigKey("iterations", "Iterations")
  val THEME = ConfigKey("theme", "Theme")
  val NAME = ConfigKey("name", "Name")
  val MODEL_TYPE = ConfigKey("model_type", "Model type")
  val MODEL = ConfigKey("model", "Model")
  val RESET_CONVERSATION_TURN_COUNT =
    ConfigKey("reset_conversation_turn_count", "Number of turns before the conversation resets")
  val PREFILL_TOKENS = ConfigKey("prefill_tokens", "Prefill tokens")
  val DECODE_TOKENS = ConfigKey("decode_tokens", "Decode tokens")
  val NUMBER_OF_RUNS = ConfigKey("number_of_runs", "Number of runs")
}

open class Config(
  val type: ConfigEditorType,
  open val key: ConfigKey,
  open val defaultValue: Any,
  open val valueType: ValueType,
  open val needReinitialization: Boolean = true,
)

class LabelConfig(override val key: ConfigKey, override val defaultValue: String = "") :
  Config(
    type = ConfigEditorType.LABEL,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.STRING,
  )

class NumberSliderConfig(
  override val key: ConfigKey,
  val sliderMin: Float,
  val sliderMax: Float,
  override val defaultValue: Float,
  override val valueType: ValueType,
  override val needReinitialization: Boolean = true,
) :
  Config(
    type = ConfigEditorType.NUMBER_SLIDER,
    key = key,
    defaultValue = defaultValue,
    valueType = valueType,
  )

class BooleanSwitchConfig(
  override val key: ConfigKey,
  override val defaultValue: Boolean,
  override val needReinitialization: Boolean = true,
) :
  Config(
    type = ConfigEditorType.BOOLEAN_SWITCH,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.BOOLEAN,
  )

class SegmentedButtonConfig(
  override val key: ConfigKey,
  override val defaultValue: String,
  val options: List<String>,
  val allowMultiple: Boolean = false,
) :
  Config(
    type = ConfigEditorType.SEGMENTED_BUTTON,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.STRING,
  )

class BottomSheetSelectorConfig(
  override val key: ConfigKey,
  override val defaultValue: String,
  val options: List<BottomSheetSelectorItem>,
  @StringRes val bottomSheetTitleResId: Int? = null,
) :
  Config(
    type = ConfigEditorType.BOTTOMSHEET_SELECTOR,
    key = key,
    defaultValue = defaultValue,
    valueType = ValueType.STRING,
  )

data class BottomSheetSelectorItem(val label: String)

fun convertValueToTargetType(value: Any, valueType: ValueType): Any {
  return when (valueType) {
    ValueType.INT ->
      when (value) {
        is Int -> value
        is Float -> value.toInt()
        is Double -> value.toInt()
        is String -> value.toIntOrNull() ?: ""
        is Boolean -> if (value) 1 else 0
        else -> ""
      }

    ValueType.FLOAT ->
      when (value) {
        is Int -> value.toFloat()
        is Float -> value
        is Double -> value.toFloat()
        is String -> value.toFloatOrNull() ?: ""
        is Boolean -> if (value) 1f else 0f
        else -> ""
      }

    ValueType.DOUBLE ->
      when (value) {
        is Int -> value.toDouble()
        is Float -> value.toDouble()
        is Double -> value
        is String -> value.toDoubleOrNull() ?: ""
        is Boolean -> if (value) 1.0 else 0.0
        else -> ""
      }

    ValueType.BOOLEAN ->
      when (value) {
        is Int -> value == 0
        is Boolean -> value
        is Float -> abs(value) > 1e-6
        is Double -> abs(value) > 1e-6
        is String -> value.isNotEmpty()
        else -> false
      }

    ValueType.STRING -> value.toString()
  }
}

fun createLlmChatConfigs(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  defaultMaxContextLength: Int? = null,
  defaultTopK: Int = DEFAULT_TOPK,
  defaultTopP: Float = DEFAULT_TOPP,
  defaultTemperature: Float = DEFAULT_TEMPERATURE,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
  supportThinking: Boolean = false,
  defaultSystemPrompt: String = "",
): List<Config> {
  // MAX_TOKENS stays as a LabelConfig value-carrier on every path. The dormant
  // NumberSliderConfig-morphing branch was removed in Phase 3.7 Task 1 — MAX_CONTEXT_LENGTH
  // is emitted as a sibling LabelConfig below so the slider UI can read it as the upper bound
  // without mutating the MAX_TOKENS shape.
  val maxTokensConfig: Config =
    LabelConfig(key = ConfigKeys.MAX_TOKENS, defaultValue = "$defaultMaxToken")
  val configs =
    listOf(
        maxTokensConfig,
        NumberSliderConfig(
          key = ConfigKeys.TOPK,
          sliderMin = 5f,
          sliderMax = 100f,
          defaultValue = defaultTopK.toFloat(),
          valueType = ValueType.INT,
        ),
        NumberSliderConfig(
          key = ConfigKeys.TOPP,
          sliderMin = 0.0f,
          sliderMax = 1.0f,
          defaultValue = defaultTopP,
          valueType = ValueType.FLOAT,
        ),
        NumberSliderConfig(
          key = ConfigKeys.TEMPERATURE,
          sliderMin = 0.0f,
          sliderMax = 2.0f,
          defaultValue = defaultTemperature,
          valueType = ValueType.FLOAT,
        ),
        SegmentedButtonConfig(
          key = ConfigKeys.ACCELERATOR,
          defaultValue = accelerators[0].label,
          options = accelerators.map { it.label },
        ),
      )
      .toMutableList()

  if (supportThinking) {
    configs.add(BooleanSwitchConfig(key = ConfigKeys.ENABLE_THINKING, defaultValue = false))
  }
  configs.add(
    LabelConfig(key = ConfigKeys.SYSTEM_PROMPT_DEFAULT, defaultValue = defaultSystemPrompt)
  )
  if (defaultMaxContextLength != null) {
    configs.add(
      LabelConfig(key = ConfigKeys.MAX_CONTEXT_LENGTH, defaultValue = defaultMaxContextLength.toString())
    )
  }
  return configs
}

fun getConfigValueString(value: Any, config: Config): String {
  var strNewValue = "$value"
  if (config.valueType == ValueType.FLOAT) {
    strNewValue = "%.2f".format(value)
  }
  return strNewValue
}
