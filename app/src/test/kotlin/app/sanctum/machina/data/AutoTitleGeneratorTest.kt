package app.sanctum.machina.data

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AC-U2 algorithm coverage. Pure JVM — no Robolectric needed.
 *
 * Title rules (Task 4 spec):
 *   1. trim → if empty → fallback "Чат от DD.MM HH:mm"
 *   2. collapse `\s+` to single space
 *   3. length ≤ 20 → return as-is, no ellipsis
 *   4. length > 20 → cut at last space at position ≤ 20 + "…"
 *   5. length > 20 with no space in 0..20 → hard cut to 20 + "…"
 */
class AutoTitleGeneratorTest {

  @Test
  fun shortText_returnedAsIs() {
    assertEquals("Hello", AutoTitleGenerator.generateTitle("Hello", 0L))
  }

  @Test
  fun longText_cutAtLastSpace() {
    // "The quick brown fox jumps" — 25 chars; last space ≤ 20 is at idx 19
    assertEquals(
      "The quick brown fox…",
      AutoTitleGenerator.generateTitle("The quick brown fox jumps", 0L),
    )
  }

  @Test
  fun longText_noSpace_hardCut() {
    val text = "abcdefghijklmnopqrstuvwxy" // 25 chars, no spaces
    assertEquals("abcdefghijklmnopqrst…", AutoTitleGenerator.generateTitle(text, 0L))
  }

  @Test
  fun leadingTrailingWhitespace_trimmed() {
    assertEquals("Hello world", AutoTitleGenerator.generateTitle("  Hello world  ", 0L))
  }

  @Test
  fun multipleSpaces_collapsed() {
    assertEquals("Hello world", AutoTitleGenerator.generateTitle("Hello    world", 0L))
  }

  @Test
  fun nullText_fallback() {
    val createdAt = 1_700_000_000_000L // arbitrary epoch ms
    val title = AutoTitleGenerator.generateTitle(null, createdAt)
    val expected = "Чат от " + SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
      .format(java.util.Date(createdAt))
    assertEquals(expected, title)
    // Format invariant
    assertTrue(title.matches(Regex("""Чат от \d{2}\.\d{2} \d{2}:\d{2}""")))
  }

  @Test
  fun emptyText_fallback() {
    val createdAt = 1_700_000_000_000L
    val title = AutoTitleGenerator.generateTitle("", createdAt)
    assertTrue(title.matches(Regex("""Чат от \d{2}\.\d{2} \d{2}:\d{2}""")))
  }

  @Test
  fun whitespaceOnlyText_fallback() {
    val createdAt = 1_700_000_000_000L
    val title = AutoTitleGenerator.generateTitle("   ", createdAt)
    assertTrue(title.matches(Regex("""Чат от \d{2}\.\d{2} \d{2}:\d{2}""")))
  }

  @Test
  fun exactly20Chars_returnedAsIs() {
    val text = "abcdefghijklmnopqrst" // 20 chars exactly, no space
    assertEquals(text, AutoTitleGenerator.generateTitle(text, 0L))
  }

  @Test
  fun chars21_noSpace_hardCut() {
    val text = "abcdefghijklmnopqrstu" // 21 chars, no space
    assertEquals("abcdefghijklmnopqrst…", AutoTitleGenerator.generateTitle(text, 0L))
  }
}
