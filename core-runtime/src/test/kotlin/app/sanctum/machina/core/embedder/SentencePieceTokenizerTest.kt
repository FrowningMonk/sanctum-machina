/*
 * Copyright 2026 Sanctum Machina authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package app.sanctum.machina.core.embedder

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * Phase 4 Task 18: roundtrip tests against the bundled EmbeddingGemma SentencePiece model.
 *
 * The reference token-id sequences are taken from Python `sentencepiece` 0.2.1 on the same
 * `sentencepiece.model` file, generated 2026-05-16. Treat each expected list as an opaque
 * oracle — any divergence is a tokenizer bug (the alternative would be re-training the model
 * which is out of scope).
 *
 * **Source of model file:** `app/src/main/assets/embedding/sentencepiece.model`. Test resolves
 * via a path relative to the `:core-runtime` module working directory (= module folder during
 * `:core-runtime:test`). If the file is missing — JUnit `Assume` skips the suite with a clear
 * message; this keeps `:core-runtime` decoupled from `:app` for CI environments that build
 * the runtime library in isolation.
 */
class SentencePieceTokenizerTest {

  // -- English -----------------------------------------------------------------------------

  @Test
  fun helloWorld_matchesReference() {
    assertEncodes("Hello, world.", intArrayOf(9259, 236764, 1902, 236761))
  }

  @Test
  fun helloWorldLowercase_matchesReference() {
    assertEncodes("hello world", intArrayOf(23391, 1902))
  }

  @Test
  fun singleWord_matchesReference() {
    assertEncodes("Hello", intArrayOf(9259))
  }

  @Test
  fun consecutiveSpaces_matchesReference() {
    assertEncodes("Hi  there", intArrayOf(10979, 138, 13534))
  }

  // -- Russian -----------------------------------------------------------------------------

  @Test
  fun russianHello_matchesReference() {
    assertEncodes(
      "Здравствуй, мир.",
      intArrayOf(237590, 42967, 11984, 236934, 236764, 58562, 236761),
    )
  }

  @Test
  fun russianShort_matchesReference() {
    assertEncodes("тест", intArrayOf(1305, 871))
  }

  @Test
  fun russian1cDomain_matchesReference() {
    assertEncodes("Регистр накопления", intArrayOf(33367, 3777, 73969, 80876, 162060))
  }

  // -- Edge cases --------------------------------------------------------------------------

  @Test
  fun emptyInput_returnsEmptyArray() {
    assertEncodes("", intArrayOf())
  }

  @Test
  fun singleSpace_matchesReference() {
    assertEncodes(" ", intArrayOf(236743))
  }

  @Test
  fun newlineOnly_matchesReference() {
    // '\n' is NOT escaped to '▁' — `escape_whitespaces` in SentencePiece only escapes U+0020.
    assertEncodes("\n", intArrayOf(107))
  }

  @Test
  fun singleLetter_matchesReference() {
    assertEncodes("a", intArrayOf(236746))
  }

  // -- Digit splitting -------------------------------------------------------------------

  @Test
  fun letterPlusDigits_eachDigitSplit() {
    assertEncodes("test123", intArrayOf(2181, 236770, 236778, 236800))
  }

  @Test
  fun yearLikeDigits_eachIsolated() {
    assertEncodes("2026", intArrayOf(236778, 236771, 236778, 236825))
  }

  @Test
  fun mixedAlphaNumeric_eachDigitIsolated() {
    assertEncodes("a1b2", intArrayOf(236746, 236770, 236763, 236778))
  }

  // -- Unicode / byte fallback -----------------------------------------------------------

  @Test
  fun emojiInVocab_singlePiece() {
    // 🎉 happens to be in the EmbeddingGemma vocab as a single piece — no byte fallback.
    assertEncodes("Hello 🎉", intArrayOf(9259, 204906))
  }

  @Test
  fun latinSupplementSplit_matchesReference() {
    // 'café' has 'é' as its own piece — BPE merges 'caf' but stops at é.
    assertEncodes("café", intArrayOf(123125, 236859))
  }

  @Test
  fun trademarkInVocab_singlePiece() {
    assertEncodes("A™B", intArrayOf(236776, 238590, 236799))
  }

  @Test
  fun ufffdByteFallback_threeBytes() {
    // U+FFFF — codepoint not in vocab → UTF-8 bytes [0xEF, 0xBF, 0xBF] → ids 477, 429, 429.
    assertEncodes("￿", intArrayOf(477, 429, 429))
  }

  // -- Pre-tokenization boundary behaviour ----------------------------------------------

  @Test
  fun scriptChangeNoSpace_breaksAtBoundary() {
    // Latn↔Cyrl with no ▁ between → forced split, then each script BPE'd independently.
    assertEncodes("helloпривет", intArrayOf(23391, 10628, 8362))
  }

  @Test
  fun escapedSpaceCrossesScript_singlePreToken() {
    // ' ' escapes to ▁; the ▁ suppresses the script-change boundary, so Latn+▁+Cyrl is one
    // pre-token. BPE then chooses 'привет' → ['при','вет'] + '▁hello'.
    assertEncodes("привет hello", intArrayOf(10628, 8362, 29104))
  }

  @Test
  fun punctuationBetweenLetters_isolates() {
    assertEncodes("a,b", intArrayOf(236746, 236764, 236763))
  }

  @Test
  fun symbolBetweenLetters_isolates() {
    assertEncodes("%a\$", intArrayOf(236908, 236746, 236795))
  }

  @Test
  fun newlineBetweenLetters_isolates() {
    assertEncodes("Hello\nthere", intArrayOf(9259, 107, 13534))
  }

  // -- maxLength truncation -------------------------------------------------------------

  @Test
  fun maxLengthTruncation_returnsPrefix() {
    val full = tokenizer.encode("Hello, world.", maxLength = 128)
    assertEquals(4, full.size)
    val truncated = tokenizer.encode("Hello, world.", maxLength = 2)
    assertArrayEquals(intArrayOf(9259, 236764), truncated)
  }

  @Test
  fun maxLengthZero_returnsEmpty() {
    val result = tokenizer.encode("Hello", maxLength = 0)
    assertEquals(0, result.size)
  }

  // -- Roundtrip sanity ------------------------------------------------------------------

  @Test
  fun longRussianParagraph_underPerformanceBudget() {
    // ~100-word Russian-ish paragraph. AC: tokenizes in ≤ 50 ms on JVM.
    val paragraph = ("Регистр накопления — это объект конфигурации 1С, используемый для " +
      "хранения количественных или суммовых данных в разрезе нескольких измерений. " +
      "Существует два вида регистров накопления: остатки и обороты. ").repeat(3)
    val start = System.nanoTime()
    val ids = tokenizer.encode(paragraph, maxLength = 2048)
    val elapsedMs = (System.nanoTime() - start) / 1_000_000
    assertTrue("encoded ids should be non-empty", ids.isNotEmpty())
    assertTrue("encode took ${elapsedMs}ms (budget: 50ms)", elapsedMs < 50)
  }

  // -- helpers ---------------------------------------------------------------------------

  private fun assertEncodes(input: String, expected: IntArray) {
    val actual = tokenizer.encode(input, maxLength = 2048)
    if (!actual.contentEquals(expected)) {
      fail(
        "encode mismatch for ${quote(input)}\n" +
          "  expected = ${expected.toList()}\n" +
          "  actual   = ${actual.toList()}"
      )
    }
  }

  private fun quote(s: String): String = "\"${s.replace("\n", "\\n")}\""

  companion object {
    @JvmStatic
    private lateinit var tokenizer: SentencePieceTokenizer

    @BeforeClass
    @JvmStatic
    fun loadTokenizer() {
      val modelFile = locateModelFile()
      assumeTrue(
        "bundled sentencepiece.model not found at ${modelFile.absolutePath}; " +
          "this test requires the :app module's asset to be in-tree.",
        modelFile.exists(),
      )
      tokenizer = SentencePieceTokenizer.fromFile(modelFile)
    }

    private fun locateModelFile(): File {
      // Gradle runs unit tests with workingDir = module folder. Walk up to repo root just in
      // case an IDE override sets workingDir elsewhere.
      val candidates = listOf(
        File("../app/src/main/assets/embedding/sentencepiece.model"),
        File("app/src/main/assets/embedding/sentencepiece.model"),
        File("../../app/src/main/assets/embedding/sentencepiece.model"),
      )
      return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }
  }
}
