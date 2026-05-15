package app.sanctum.machina.rag

import kotlin.math.min

/**
 * One contiguous slice of a page's text.
 *
 * Offsets are measured in **Unicode codepoints** from the start of the input
 * (not UTF-16 chars, not bytes), so a slice can always be re-materialised with
 * `String(codepoints, offsetStart, offsetEnd - offsetStart)` and never splits
 * a surrogate pair.
 */
data class TextChunk(val text: String, val offsetStart: Int, val offsetEnd: Int)

/**
 * Splits page text into overlapping fixed-size chunks at codepoint boundaries.
 *
 * Pure logic — no IO, no Android, no coroutines. `chunkSize` and `overlap` are
 * counted in codepoints, not `Char`s; with `chunkSize=4` the string "привет世界"
 * yields two chunks ("прив", "ет世界"), and an emoji that straddles a chunk
 * boundary moves to the next chunk in one piece.
 */
object Chunker {

  fun chunkPage(text: String, chunkSize: Int, overlap: Int): List<TextChunk> {
    require(chunkSize > 0) { "chunkSize must be > 0, was $chunkSize" }
    require(chunkSize > overlap) {
      "chunkSize must be > overlap, was chunkSize=$chunkSize overlap=$overlap"
    }
    if (text.isEmpty()) return emptyList()

    val codepoints = text.codePoints().toArray()
    if (codepoints.isEmpty()) return emptyList()

    val step = chunkSize - overlap
    val out = ArrayList<TextChunk>()
    var start = 0
    while (start < codepoints.size) {
      val end = min(start + chunkSize, codepoints.size)
      val slice = String(codepoints, start, end - start)
      out.add(TextChunk(slice, start, end))
      if (end == codepoints.size) break
      start += step
    }
    return out
  }
}
