package app.sanctum.machina.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure-JVM title derivation for new persistent chats (AC-U2).
 *
 * Algorithm:
 *   1. trim → if blank → fallback `"Chat from DD.MM HH:mm"` from [createdAtMs]
 *   2. collapse all `\s+` runs to a single space
 *   3. if cleaned length ≤ [MAX_LEN] → return as-is
 *   4. else cut at the last space at position ≤ [MAX_LEN] and append `…`
 *   5. if no such space exists → hard cut to [MAX_LEN] and append `…`
 */
object AutoTitleGenerator {

  private const val MAX_LEN = 20
  private const val ELLIPSIS = "…"
  private val WHITESPACE_RUN = Regex("""\s+""")

  fun generateTitle(firstUserText: String?, createdAtMs: Long): String {
    val cleaned = firstUserText
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?.replace(WHITESPACE_RUN, " ")
      ?: return fallbackTitle(createdAtMs)

    if (cleaned.length <= MAX_LEN) return cleaned

    val window = cleaned.substring(0, MAX_LEN + 1) // search 0..MAX_LEN inclusive
    val lastSpace = window.lastIndexOf(' ')
    val cutAt = if (lastSpace > 0) lastSpace else MAX_LEN
    return cleaned.substring(0, cutAt) + ELLIPSIS
  }

  private fun fallbackTitle(createdAtMs: Long): String {
    // Locale.getDefault — visible to the user; matches their date conventions.
    val formatter = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
    return "Chat from " + formatter.format(Date(createdAtMs))
  }
}
