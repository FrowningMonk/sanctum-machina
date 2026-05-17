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
import org.junit.BeforeClass
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Phase 4 Task 18: roundtrip tests against the bundled EmbeddingGemma SentencePiece model.
 *
 * The reference token-id sequences are taken from Python `sentencepiece` 0.2.1 on the same
 * `sentencepiece.model` file, generated 2026-05-16. Treat each expected list as an opaque
 * oracle — any divergence is a tokenizer bug (the alternative would be re-training the model
 * which is out of scope). To regenerate: run
 * `work/phase-4-projects-rag/scripts/regen_sp_fixtures.py`.
 *
 * **Source of model file:** `app/src/main/assets/embedding/sentencepiece.model`. Test resolves
 * via a path relative to the `:core-runtime` module working directory. If the file is
 * missing the suite fails loud (round-1 test review: silent skip would let CI go green
 * with zero assertions executed after an accidental asset removal).
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

  // -- BiDi / RTL --------------------------------------------------------------------

  @Test
  fun hebrewHello_matchesReference() {
    assertEncodes("שלום", intArrayOf(82110, 42737))
  }

  @Test
  fun arabicHello_matchesReference() {
    assertEncodes("مرحبا", intArrayOf(236873, 150345))
  }

  @Test
  fun mixedLatnHebrewWithSpace_matchesReference() {
    // 'Hi' (Latn) + ' ' (escapes to ▁) + 'שלום' (Hebrew). The ▁ exception keeps the
    // Cyrl-like cross-script boundary suppressed, so '▁שלום' becomes one pre-token; BPE
    // then drops the score-less ▁ off the Hebrew and emits 'Hi', '▁', 'שלום'.
    assertEncodes("Hi שלום", intArrayOf(10979, 14755, 42737))
  }

  @Test
  fun bidiControlMark_inVocabSinglePiece() {
    // U+200E LEFT-TO-RIGHT MARK is in vocab as a single piece in EmbeddingGemma's tokenizer.
    assertEncodes("a‎b", intArrayOf(236746, 239856, 236763))
  }

  // -- Surrogate pairs / lone surrogate -----------------------------------------------

  @Test
  fun outOfVocabZwjEmoji_byteFallback() {
    // 😶‍🌫️ (face in clouds, ZWJ sequence) — composite emoji unlikely to live as one piece.
    // Python sentencepiece breaks it into 4 ids of mixed normal + byte-fallback shape.
    assertEncodes("😶‍🌫️", intArrayOf(247717, 237243, 248989, 238178))
  }

  @Test
  fun loneHighSurrogate_replacesWithQuestionMarkByte() {
    // Java strings can hold orphan surrogates (e.g. unpaired high surrogate from a buggy
    // upstream slice). `String.toByteArray(UTF_8)` replaces the malformed char with a
    // single '?' byte (0x3F) — JVM's documented default replacement for UTF-8 (NOT the
    // U+FFFD three-byte sequence that other UTF-8 encoders use). With byteFallbackBase=238
    // the ids are: 'a'=236746, '?'→238+63=301, 'b'=236763.
    // Python sentencepiece cannot generate this oracle (it rejects malformed UTF-16), so
    // expected values come from the JVM contract + byte_fallback arithmetic.
    assertArrayEquals(
      intArrayOf(236746, 301, 236763),
      tokenizer.encode("a\uD83Db", maxLength = 100),
    )
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

  @Test
  fun maxLengthTruncatesMidByteFallback() {
    // 'U+FFFF' expands to 3 byte-fallback ids; maxLength=2 keeps only the first two.
    assertArrayEquals(
      intArrayOf(477, 429),
      tokenizer.encode("￿", maxLength = 2),
    )
  }

  // -- DoS scan cap ---------------------------------------------------------------------

  @Test
  fun scanCapBoundsAdversarialInput_outputMatchesTruncatedInput() {
    // Round-1 security review: pathological long input with small `maxLength` must not
    // process the full input. The cap is `maxLength * SCAN_CAP_PER_TOKEN` codepoints, so a
    // 1 MiB input with maxLength=2 sees the same symbol graph as a 16-char input — output
    // must be byte-identical. This is a structural assertion (not wall-clock-based) so it
    // can't flake on shared CI runners.
    val capScan = 2 * 8 // maxLength * SCAN_CAP_PER_TOKEN
    val short = "a".repeat(capScan)
    val long = "a".repeat(1024 * 1024)
    val shortResult = tokenizer.encode(short, maxLength = 2)
    val longResult = tokenizer.encode(long, maxLength = 2)
    assertArrayEquals(
      "scan cap not engaging: long-input output diverges from cap-equivalent short input",
      shortResult,
      longResult,
    )
  }

  // -- Roundtrip sanity ------------------------------------------------------------------

  @Test
  fun longRussianParagraph_underPerformanceBudget() {
    // ~100-word Russian-ish paragraph. JVM cold-start can include class load + JIT spin-up;
    // round-1 review: warm up with one throw-away encode, then take min-of-3 as the robust
    // estimator on shared CI runners.
    val paragraph = ("Регистр накопления — это объект конфигурации 1С, используемый для " +
      "хранения количественных или суммовых данных в разрезе нескольких измерений. " +
      "Существует два вида регистров накопления: остатки и обороты. ").repeat(3)
    tokenizer.encode(paragraph, maxLength = 2048) // warm-up; result discarded
    val durations = LongArray(3)
    for (i in 0..2) {
      val start = System.nanoTime()
      tokenizer.encode(paragraph, maxLength = 2048)
      durations[i] = System.nanoTime() - start
    }
    val minMs = durations.min() / 1_000_000
    assertTrue("encode min-of-3 took ${minMs}ms (budget: 150ms)", minMs < 150)
  }

  // -- helpers ---------------------------------------------------------------------------

  private fun assertEncodes(input: String, expected: IntArray) {
    val actual = tokenizer.encode(input, maxLength = 2048)
    assertArrayEquals("encode mismatch for ${quote(input)}", expected, actual)
  }

  private fun quote(s: String): String = "\"${s.replace("\n", "\\n")}\""

  companion object {
    @JvmStatic
    private lateinit var tokenizer: SentencePieceTokenizer

    @BeforeClass
    @JvmStatic
    fun loadTokenizer() {
      val modelFile = locateModelFile()
      check(modelFile.exists()) {
        "bundled sentencepiece.model not found at ${modelFile.absolutePath}; " +
          "Task 17 bundles the asset — its absence is a real regression worth failing on."
      }
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
