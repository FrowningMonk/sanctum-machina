package app.sanctum.machina.rag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {

  @Test
  fun emptyText_returnsEmptyList() {
    assertEquals(emptyList<TextChunk>(), Chunker.chunkPage("", chunkSize = 512, overlap = 64))
  }

  @Test
  fun singleChunk_whenTextShorterThanChunkSize() {
    val text = "abcdefghij" // 10 codepoints
    val chunks = Chunker.chunkPage(text, chunkSize = 512, overlap = 64)
    assertEquals(1, chunks.size)
    assertEquals(text, chunks[0].text)
    assertEquals(0, chunks[0].offsetStart)
    assertEquals(10, chunks[0].offsetEnd)
  }

  @Test
  fun multipleChunks_withOverlap() {
    val text = "x".repeat(1000)
    val chunks = Chunker.chunkPage(text, chunkSize = 400, overlap = 80)
    assertEquals(3, chunks.size)
    assertEquals(0, chunks[0].offsetStart)
    assertEquals(400, chunks[0].offsetEnd)
    assertEquals(320, chunks[1].offsetStart)
    assertEquals(720, chunks[1].offsetEnd)
    assertEquals(640, chunks[2].offsetStart)
    assertEquals(1000, chunks[2].offsetEnd)
  }

  @Test
  fun utf8Boundary_doesNotSplitSurrogatePair() {
    // 🦀 (U+1F980) is a non-BMP codepoint encoded as a UTF-16 surrogate pair.
    // Place it across what would be the char-index midpoint of chunk 1.
    // 3 leading codepoints + 🦀 + 3 trailing codepoints = 7 codepoints total.
    val text = "abc🦀def" // "abc🦀def"
    val chunks = Chunker.chunkPage(text, chunkSize = 4, overlap = 0)
    // codepoints: a(0) b(1) c(2) 🦀(3) d(4) e(5) f(6)
    // chunk 1: cp[0..4) -> "abc🦀"; chunk 2: cp[4..7) -> "def"
    assertEquals(2, chunks.size)
    assertEquals("abc🦀", chunks[0].text)
    assertEquals(0, chunks[0].offsetStart)
    assertEquals(4, chunks[0].offsetEnd)
    assertEquals("def", chunks[1].text)
    assertEquals(4, chunks[1].offsetStart)
    assertEquals(7, chunks[1].offsetEnd)
    // Each chunk's text must round-trip through codepoint count cleanly — no
    // unpaired surrogates (a lone high surrogate would render as U+FFFD).
    for (c in chunks) {
      assertTrue(
        "chunk '${c.text}' contains an unpaired surrogate",
        c.text.codePointCount(0, c.text.length) == c.offsetEnd - c.offsetStart,
      )
    }
  }

  @Test
  fun cyrillicAndChinese_codepointCounted_notUtf16Char() {
    val text = "привет世界" // 8 codepoints, but Chinese chars are BMP so also 8 chars here
    val chunks = Chunker.chunkPage(text, chunkSize = 4, overlap = 0)
    assertEquals(2, chunks.size)
    assertEquals("прив", chunks[0].text)
    assertEquals("ет世界", chunks[1].text)
    assertEquals(0, chunks[0].offsetStart)
    assertEquals(4, chunks[0].offsetEnd)
    assertEquals(4, chunks[1].offsetStart)
    assertEquals(8, chunks[1].offsetEnd)
  }

  @Test
  fun chunkSize_LE_overlap_throws_IllegalArgumentException() {
    assertThrows(IllegalArgumentException::class.java) {
      Chunker.chunkPage("hello world", chunkSize = 4, overlap = 4)
    }
    assertThrows(IllegalArgumentException::class.java) {
      Chunker.chunkPage("hello world", chunkSize = 4, overlap = 5)
    }
  }

  @Test
  fun chunkSize_zeroOrNegative_throws() {
    assertThrows(IllegalArgumentException::class.java) {
      Chunker.chunkPage("hello", chunkSize = 0, overlap = 0)
    }
    assertThrows(IllegalArgumentException::class.java) {
      Chunker.chunkPage("hello", chunkSize = -1, overlap = 0)
    }
  }
}
