package app.sanctum.machina.ui.chat

import app.sanctum.machina.core.data.ConfigKeys
import app.sanctum.machina.core.settings.proto.PerModelSettings

/**
 * Pure type-safe merge of allowlist defaults and per-model overrides (D16).
 *
 * Iteration over the proto's `hasXxx()` methods keeps "field unset" distinct
 * from "field set to the proto3 zero" — explicit `setEnableThinking(false)`
 * wins over a `true` default, while a default-instance override collapses
 * to defaults verbatim.
 *
 * Type mapping is explicit per field so the returned map carries Kotlin
 * `Int` / `Float` / `Boolean` / `String`, never proto-internal boxed types.
 * Float-to-Float and Int-to-Int are identity-safe — `value` is the proto's
 * own primitive.
 *
 * The returned map is a fresh `LinkedHashMap`; the input [defaults] is not
 * mutated, so callers can keep their canonical map in long-lived state.
 */
object EffectiveConfig {
    fun merge(
        defaults: Map<String, Any>,
        overrides: PerModelSettings?,
    ): Map<String, Any> {
        val result: MutableMap<String, Any> = LinkedHashMap(defaults)
        if (overrides == null) return result

        if (overrides.hasMaxTokens()) {
            result[ConfigKeys.MAX_TOKENS.label] = overrides.maxTokens
        }
        if (overrides.hasTopK()) {
            result[ConfigKeys.TOPK.label] = overrides.topK
        }
        if (overrides.hasTopP()) {
            result[ConfigKeys.TOPP.label] = overrides.topP
        }
        if (overrides.hasTemperature()) {
            result[ConfigKeys.TEMPERATURE.label] = overrides.temperature
        }
        if (overrides.hasEnableThinking()) {
            result[ConfigKeys.ENABLE_THINKING.label] = overrides.enableThinking
        }
        if (overrides.hasAccelerator()) {
            result[ConfigKeys.ACCELERATOR.label] = overrides.accelerator
        }
        if (overrides.hasSystemPromptDefault()) {
            result[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label] = overrides.systemPromptDefault
        }
        return result
    }
}
