package app.sanctum.machina.core.registry

import app.sanctum.machina.core.data.ConfigKeys
import com.google.ai.edge.litertlm.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for D24 / Task-10 TDD anchor: `DefaultModelRegistry.awaitInitialize`
 * must pass a non-null `Contents` to `LlmModelHelper.initialize` when the
 * model carries a non-blank `SYSTEM_PROMPT_DEFAULT`, and `null` otherwise.
 *
 * Exercises [buildSystemInstruction] — the extracted internal helper that
 * `awaitInitialize` calls with `model.configValues`. Testing the helper
 * directly avoids standing up the full registry graph (Context, Hilt,
 * DownloadRepository) just to verify a pure Map → Contents? mapping.
 */
class SystemInstructionTest {

  @Test
  fun nonBlankPrompt_returnsContentsWrappingPrompt() {
    val prompt = "You are a concise assistant."
    val configValues = mapOf<String, Any>(
      ConfigKeys.SYSTEM_PROMPT_DEFAULT.label to prompt,
    )

    val result = buildSystemInstruction(configValues)

    assertNotNull("Expected non-null Contents for non-blank prompt", result)
    val texts = result!!.contents.filterIsInstance<Content.Text>().map { it.text }
    assertEquals(listOf(prompt), texts)
  }

  @Test
  fun emptyPrompt_returnsNull() {
    val configValues = mapOf<String, Any>(
      ConfigKeys.SYSTEM_PROMPT_DEFAULT.label to "",
    )
    assertNull(buildSystemInstruction(configValues))
  }

  @Test
  fun blankPrompt_returnsNull() {
    val configValues = mapOf<String, Any>(
      ConfigKeys.SYSTEM_PROMPT_DEFAULT.label to "   \t\n  ",
    )
    assertNull(buildSystemInstruction(configValues))
  }

  @Test
  fun missingKey_returnsNull() {
    assertNull(buildSystemInstruction(emptyMap()))
  }

  @Test
  fun nonStringValue_returnsNull() {
    // Defensive against a future config-schema slip where the same label is
    // re-used for a different type. `as? String` must yield null, not throw.
    val configValues = mapOf<String, Any>(
      ConfigKeys.SYSTEM_PROMPT_DEFAULT.label to 42,
    )
    assertNull(buildSystemInstruction(configValues))
  }
}
