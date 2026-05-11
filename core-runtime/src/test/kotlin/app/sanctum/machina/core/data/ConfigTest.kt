package app.sanctum.machina.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct structural pins on [createLlmChatConfigs] (Phase 3.7 Task 1, Decision 1).
 *
 * The factory's MAX_TOKENS branch is load-bearing: a future refactor that revives the dormant
 * `NumberSliderConfig`-morphing branch (removed in Task 1) would silently change `MAX_TOKENS`'s
 * carrier shape from `LabelConfig` to `NumberSliderConfig`. Downstream code paths read
 * `Model.configValues[MAX_TOKENS.label]` as a String (`LlmChatModelHelper.getIntConfigValue`
 * coerces via `Int.toString`-aware logic) — flipping to `NumberSliderConfig` would put a Float
 * in `configValues` and break the read. These tests pin both halves of the contract:
 * MAX_TOKENS stays `LabelConfig`-shaped on every path; MAX_CONTEXT_LENGTH is a sibling
 * `LabelConfig` emitted only when the parameter is non-null.
 */
class ConfigTest {

  @Test
  fun createLlmChatConfigs_withMaxContextLength_keepsMaxTokensAsLabelConfig() {
    val configs = createLlmChatConfigs(
      defaultMaxToken = 4000,
      defaultMaxContextLength = 32000,
    )

    val maxTokens = configs.firstOrNull { it.key == ConfigKeys.MAX_TOKENS }
    assertNotNull("MAX_TOKENS must be present", maxTokens)
    assertTrue(
      "MAX_TOKENS must remain a LabelConfig — Decision 1 (no NumberSliderConfig morph)",
      maxTokens is LabelConfig,
    )
    assertEquals("4000", (maxTokens as LabelConfig).defaultValue)

    val maxContext = configs.firstOrNull { it.key == ConfigKeys.MAX_CONTEXT_LENGTH }
    assertNotNull("MAX_CONTEXT_LENGTH must be emitted when parameter non-null", maxContext)
    assertTrue(
      "MAX_CONTEXT_LENGTH must be a LabelConfig — Decision 6 (no proto override path)",
      maxContext is LabelConfig,
    )
    assertEquals("32000", (maxContext as LabelConfig).defaultValue)
  }

  @Test
  fun createLlmChatConfigs_withoutMaxContextLength_omitsKeyButKeepsMaxTokens() {
    val configs = createLlmChatConfigs(defaultMaxToken = 1024)

    val maxTokens = configs.firstOrNull { it.key == ConfigKeys.MAX_TOKENS }
    assertNotNull("MAX_TOKENS must remain present on the null-capacity path", maxTokens)
    assertTrue(
      "MAX_TOKENS shape must not depend on defaultMaxContextLength being non-null",
      maxTokens is LabelConfig,
    )
    assertEquals("1024", (maxTokens as LabelConfig).defaultValue)

    assertFalse(
      "MAX_CONTEXT_LENGTH must not appear when defaultMaxContextLength is null",
      configs.any { it.key == ConfigKeys.MAX_CONTEXT_LENGTH },
    )
  }
}
