package app.sanctum.machina.ui.chat

import app.sanctum.machina.core.data.ConfigKeys
import app.sanctum.machina.core.settings.proto.PerModelSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD anchors for [EffectiveConfig] (D16):
 * - null / empty / partial overrides collapse to allowlist defaults
 * - explicit `false` boolean overrides win over defaults
 * - proto numeric types map to their Kotlin equivalents without widening
 * - the defaults map is never mutated in place
 */
class EffectiveConfigTest {

    private val defaults: Map<String, Any> = mapOf(
        ConfigKeys.MAX_TOKENS.label to 1024,
        ConfigKeys.TOPK.label to 40,
        ConfigKeys.TOPP.label to 0.95f,
        ConfigKeys.TEMPERATURE.label to 0.7f,
        ConfigKeys.ENABLE_THINKING.label to true,
        ConfigKeys.ACCELERATOR.label to "GPU",
        ConfigKeys.SYSTEM_PROMPT_DEFAULT.label to "be helpful",
    )

    @Test
    fun overridesNull_returnsDefaults() {
        val result = EffectiveConfig.merge(defaults, null)
        assertEquals(defaults, result)
    }

    @Test
    fun partialOverrides_mergedCorrectly() {
        val overrides = PerModelSettings.newBuilder()
            .setTemperature(0.2f)
            .build()

        val result = EffectiveConfig.merge(defaults, overrides)

        assertEquals(0.2f, result[ConfigKeys.TEMPERATURE.label])
        assertEquals(1024, result[ConfigKeys.MAX_TOKENS.label])
        assertEquals(40, result[ConfigKeys.TOPK.label])
        assertEquals(0.95f, result[ConfigKeys.TOPP.label])
        assertEquals(true, result[ConfigKeys.ENABLE_THINKING.label])
        assertEquals("GPU", result[ConfigKeys.ACCELERATOR.label])
        assertEquals("be helpful", result[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label])
    }

    @Test
    fun boolOverride_explicitFalse_overridesTrue() {
        val overrides = PerModelSettings.newBuilder()
            .setEnableThinking(false)
            .build()

        val result = EffectiveConfig.merge(defaults, overrides)

        assertEquals(false, result[ConfigKeys.ENABLE_THINKING.label])
    }

    @Test
    fun typeSafety_protoFloatToKotlinFloat() {
        val overrides = PerModelSettings.newBuilder()
            .setTemperature(0.42f)
            .setTopP(0.88f)
            .setMaxTokens(2048)
            .setTopK(64)
            .build()

        val result = EffectiveConfig.merge(defaults, overrides)

        val temp = result[ConfigKeys.TEMPERATURE.label]
        val topP = result[ConfigKeys.TOPP.label]
        val maxTokens = result[ConfigKeys.MAX_TOKENS.label]
        val topK = result[ConfigKeys.TOPK.label]
        assertTrue("temperature must be Kotlin Float, got ${temp?.let { it::class }}", temp is Float)
        assertTrue("topP must be Kotlin Float, got ${topP?.let { it::class }}", topP is Float)
        assertTrue("maxTokens must be Kotlin Int, got ${maxTokens?.let { it::class }}", maxTokens is Int)
        assertTrue("topK must be Kotlin Int, got ${topK?.let { it::class }}", topK is Int)
        assertEquals(0.42f, temp)
        assertEquals(0.88f, topP)
        assertEquals(2048, maxTokens)
        assertEquals(64, topK)
    }

    @Test
    fun pureFunction_defaultsNotMutated() {
        val snapshot = defaults.toMap()
        val overrides = PerModelSettings.newBuilder()
            .setTemperature(0.1f)
            .setEnableThinking(false)
            .setAccelerator("CPU")
            .setSystemPromptDefault("be terse")
            .build()

        val result = EffectiveConfig.merge(defaults, overrides)

        assertEquals("defaults must be structurally unchanged", snapshot, defaults)
        assertNotSame("merge must return a fresh map", defaults, result)
    }

    @Test
    fun emptyEqualsNull_defaultInstance() {
        val empty = PerModelSettings.getDefaultInstance()

        val mergedEmpty = EffectiveConfig.merge(defaults, empty)
        val mergedNull = EffectiveConfig.merge(defaults, null)

        assertEquals(mergedNull, mergedEmpty)
        assertEquals(defaults, mergedEmpty)
    }

    @Test
    fun stringOverride_systemPrompt_appliesExactly() {
        val overrides = PerModelSettings.newBuilder()
            .setSystemPromptDefault("answer briefly")
            .setAccelerator("CPU")
            .build()

        val result = EffectiveConfig.merge(defaults, overrides)

        assertEquals("answer briefly", result[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label])
        assertEquals("CPU", result[ConfigKeys.ACCELERATOR.label])
    }

    @Test
    fun emptyDefaults_overridesStillApplied() {
        val empty = emptyMap<String, Any>()
        val overrides = PerModelSettings.newBuilder()
            .setTemperature(0.3f)
            .build()

        val result = EffectiveConfig.merge(empty, overrides)

        assertEquals(1, result.size)
        assertEquals(0.3f, result[ConfigKeys.TEMPERATURE.label])
        assertNotSame(empty, result)
    }
}
