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
import java.util.PriorityQueue

/**
 * Phase 4 Task 18: pure-Kotlin SentencePiece **BPE** tokenizer for EmbeddingGemma-300M.
 *
 * Why hand-rolled (vs `ai.djl:sentencepiece` or `io.gitlab.shubham0204:sentence-embeddings`):
 * see `work/phase-4-projects-rag/logs/spike-day1.md` § Tokenizer landscape. Short version —
 * the JVM/Android SP options are server-ABIs-only, drop the text module, or pull a 30 MB JNI
 * artifact for an HF tokenizer.json we don't have. Zero deps wins on APK and licence.
 *
 * **Model type:** despite spike-day1's note about a "unigram port", the bundled
 * `sentencepiece.model` is BPE (`trainer_spec.model_type = 2`). The two encoders are
 * structurally different — this implementation uses BPE merge with a priority queue,
 * not Viterbi over piece-scores. The decision is recorded in `decisions.md` Task 18.
 *
 * **Normalisation:** model declares `normalizer_spec.name = "identity"` and
 * `precompiled_charsmap` is empty — no NFKC, no charmap. The only normalisation applied is
 * `escape_whitespaces = true` (U+0020 → U+2581 ▁); other whitespace (\n, \t, …) is left raw.
 *
 * **Pre-tokenization** (from `trainer_spec`):
 *  - `split_by_unicode_script = true` with the ▁ exception: script transitions are boundaries
 *    EXCEPT when either side is ▁ (so ▁+letters stays in one pre-token across script).
 *  - `split_digits = true`: every digit is its own pre-token (Unicode `Nd`).
 *  - `byte_fallback = true`: any codepoint absent from vocab is decomposed to UTF-8 bytes,
 *    each byte mapped to `<0xXX>` token (id base + b).
 *
 * The result matches `sentencepiece.SentencePieceProcessor.EncodeAsIds` byte-for-byte on the
 * EmbeddingGemma model — verified by [SentencePieceTokenizerTest] against Python `sentencepiece`
 * 0.2.1 on EN + RU + edge-case fixtures.
 */
internal class SentencePieceTokenizer private constructor(
  private val model: SpModel,
) : EmbeddingGemmaEngine.Tokenizer {

  override fun encode(text: String, maxLength: Int): IntArray {
    if (maxLength <= 0 || text.isEmpty()) return EMPTY_INT_ARRAY

    val symbols = buildInitialSymbols(text)
    if (symbols.isEmpty()) return EMPTY_INT_ARRAY

    bpeMerge(symbols)

    return collectIds(symbols, maxLength)
  }

  override fun close() {
    // Nothing to release — all state is JVM-managed.
  }

  /**
   * Step 1+2: escape U+0020 → ▁, decompose into codepoints, build initial Symbol list. Each
   * symbol carries a `boundaryToPrev` flag derived from SP's pre-tokenization rules. Codepoints
   * not in vocab decompose into one Symbol per UTF-8 byte (byte fallback).
   */
  private fun buildInitialSymbols(text: String): MutableList<Symbol> {
    // Escape spaces -> ▁ (U+2581). Other whitespace passes through unchanged.
    val escaped = StringBuilder(text.length).also { sb ->
      for (ch in text) sb.append(if (ch == ' ') SPACE_SYMBOL_CHAR else ch)
    }

    // Decompose to codepoints (handles surrogate pairs for emoji etc.).
    val codepoints = ArrayList<Int>(escaped.length)
    var i = 0
    while (i < escaped.length) {
      val cp = escaped.codePointAt(i)
      codepoints.add(cp)
      i += Character.charCount(cp)
    }
    if (codepoints.isEmpty()) return mutableListOf()

    // Compute boundary-before flags per codepoint.
    val boundaryBefore = BooleanArray(codepoints.size)
    for (k in 1 until codepoints.size) {
      boundaryBefore[k] = isBoundary(codepoints[k - 1], codepoints[k])
    }

    // Build Symbol list, byte-falling-back unknown codepoints.
    val symbols = ArrayList<Symbol>(codepoints.size)
    for (k in codepoints.indices) {
      val cp = codepoints[k]
      val str = String(Character.toChars(cp))
      val id = model.pieceToId[str]
      val firstByteBoundary = boundaryBefore[k]
      if (id != null) {
        symbols.add(Symbol(str, id, firstByteBoundary))
      } else {
        val bytes = str.toByteArray(Charsets.UTF_8)
        for ((bi, b) in bytes.withIndex()) {
          val byteId = model.byteFallbackBase + (b.toInt() and 0xFF)
          val piece = byteFallbackPiece(b)
          // Only the FIRST byte of a multi-byte codepoint inherits the codepoint's
          // boundary-to-prev. Subsequent bytes are internal and freely mergeable.
          symbols.add(Symbol(piece, byteId, if (bi == 0) firstByteBoundary else false))
        }
      }
    }
    return symbols
  }

  /**
   * SentencePiece BPE pre-tokenization rule used by EmbeddingGemma's `trainer_spec`. Returns
   * `true` if there must be a boundary between [prev] and [curr]. Mirrors `bpe_model.cc`.
   *
   * Rules applied in order — first match wins:
   *  1. `split_digits`: every codepoint that is a Unicode digit is its own pre-token.
   *  2. `split_by_unicode_script` with the ▁ exception: script transitions split UNLESS
   *     either side is the space symbol ▁.
   */
  private fun isBoundary(prev: Int, curr: Int): Boolean {
    if (isDigit(prev) || isDigit(curr)) return true

    val prevIsSpace = prev == SPACE_SYMBOL_CP
    val currIsSpace = curr == SPACE_SYMBOL_CP
    if (prevIsSpace || currIsSpace) return false

    val prevScript = Character.UnicodeScript.of(prev)
    val currScript = Character.UnicodeScript.of(curr)
    if (prevScript == currScript) return false
    // Treat COMMON / INHERITED as wildcards — a script transition between e.g. Latin and
    // Common (which holds punctuation) IS a boundary in SentencePiece, but transitions among
    // Common itself never trigger one (already handled by the equality check above).
    return true
  }

  private fun isDigit(cp: Int): Boolean = Character.isDigit(cp)

  /**
   * Iteratively merge adjacent symbol pairs by descending piece-score, respecting per-symbol
   * `boundaryToPrev`. Uses a `PriorityQueue` of merge candidates with a `seq` tie-breaker so
   * higher-score merges happen first, and earlier-discovered candidates win on ties.
   *
   * Stale candidates (where one of the endpoints has since been merged or unlinked) are
   * detected by re-checking that the symbol piece-ids and `next` link still match the
   * candidate. Stale entries are silently discarded.
   */
  private fun bpeMerge(symbols: MutableList<Symbol>) {
    if (symbols.size < 2) return

    val pq = PriorityQueue<MergeCandidate>(symbols.size)
    var seq = 0

    fun tryEnqueue(leftIdx: Int) {
      if (leftIdx < 0) return
      val left = symbols[leftIdx]
      if (left.pieceId == INVALID_ID) return
      val rightIdx = left.next
      if (rightIdx < 0) return
      val right = symbols[rightIdx]
      if (right.pieceId == INVALID_ID) return
      if (right.boundaryToPrev) return
      val merged = left.piece + right.piece
      val mergedId = model.pieceToId[merged] ?: return
      pq.add(
        MergeCandidate(
          leftIdx = leftIdx,
          rightIdx = rightIdx,
          leftPieceId = left.pieceId,
          rightPieceId = right.pieceId,
          mergedPieceId = mergedId,
          mergedPiece = merged,
          score = model.scores[mergedId],
          seq = seq++,
        )
      )
    }

    // Seed: link symbols + enqueue every initial mergeable pair.
    for (k in symbols.indices) {
      symbols[k].prev = if (k > 0) k - 1 else -1
      symbols[k].next = if (k < symbols.size - 1) k + 1 else -1
    }
    for (k in 0 until symbols.size - 1) tryEnqueue(k)

    while (pq.isNotEmpty()) {
      val cand = pq.poll() ?: break
      val left = symbols[cand.leftIdx]
      if (left.pieceId != cand.leftPieceId) continue
      if (left.next != cand.rightIdx) continue
      val right = symbols[cand.rightIdx]
      if (right.pieceId != cand.rightPieceId) continue
      // Boundary between left and right must still be permissive — `right.boundaryToPrev`
      // is immutable once set, so this is implicit from `tryEnqueue` gating.

      // Merge: `left` absorbs `right`. Mark `right` as removed.
      left.pieceId = cand.mergedPieceId
      left.piece = cand.mergedPiece
      val newNext = right.next
      left.next = newNext
      if (newNext >= 0) symbols[newNext].prev = cand.leftIdx
      right.pieceId = INVALID_ID

      // Re-enqueue neighbours.
      tryEnqueue(left.prev)
      tryEnqueue(cand.leftIdx)
    }
  }

  private fun collectIds(symbols: List<Symbol>, maxLength: Int): IntArray {
    // Find head of the linked list (the first symbol whose `prev == -1` AND `pieceId` is valid).
    var head = -1
    for (k in symbols.indices) {
      val s = symbols[k]
      if (s.pieceId != INVALID_ID && s.prev == -1) {
        head = k
        break
      }
    }
    if (head < 0) return EMPTY_INT_ARRAY

    val out = IntArray(maxLength)
    var count = 0
    var idx = head
    while (idx >= 0 && count < maxLength) {
      out[count++] = symbols[idx].pieceId
      idx = symbols[idx].next
    }
    return if (count == maxLength) out else out.copyOf(count)
  }

  /** Doubly-linked-list node used by the BPE merge loop. */
  private class Symbol(
    var piece: String,
    var pieceId: Int,
    /** True iff there's a pre-tokenization boundary immediately before this symbol. */
    val boundaryToPrev: Boolean,
  ) {
    var prev: Int = -1
    var next: Int = -1
  }

  /**
   * Stored merge candidate. Carries enough state to detect staleness on poll without
   * decrement-key support in [java.util.PriorityQueue].
   */
  private class MergeCandidate(
    val leftIdx: Int,
    val rightIdx: Int,
    val leftPieceId: Int,
    val rightPieceId: Int,
    val mergedPieceId: Int,
    val mergedPiece: String,
    val score: Float,
    val seq: Int,
  ) : Comparable<MergeCandidate> {
    override fun compareTo(other: MergeCandidate): Int {
      // Higher score first; ties broken by earlier insertion (lower seq first). Higher score
      // means less-negative — SP unigram scores are log-probs; for BPE they encode merge
      // priority (highest scoring concatenation wins, matching `bpe_model.cc::Symbol::score`).
      val byScore = other.score.compareTo(this.score)
      return if (byScore != 0) byScore else this.seq.compareTo(other.seq)
    }
  }

  companion object {
    private val EMPTY_INT_ARRAY = IntArray(0)

    /** SentencePiece "▁" (LOWER ONE EIGHTH BLOCK, U+2581) — the canonical space marker. */
    private const val SPACE_SYMBOL_CP = 0x2581
    private val SPACE_SYMBOL_CHAR = SPACE_SYMBOL_CP.toChar()

    private const val INVALID_ID = -1

    private fun byteFallbackPiece(b: Byte): String =
      "<0x${"%02X".format(b.toInt() and 0xFF)}>"

    /**
     * Parse the bundled SentencePiece ModelProto and return a ready tokenizer. The model file
     * is a standard protobuf message; this loader only extracts the `pieces` field (repeated
     * SentencePiece). Trainer/normalizer specs are ignored — their settings are hard-coded
     * to match EmbeddingGemma's actual values (see class kdoc).
     */
    fun fromBytes(bytes: ByteArray): SentencePieceTokenizer =
      SentencePieceTokenizer(SpModelParser.parse(bytes))

    fun fromFile(file: File): SentencePieceTokenizer = fromBytes(file.readBytes())
  }
}

/**
 * Lookup tables produced by [SpModelParser]. Immutable after construction.
 *
 * @property pieceToId vocab map from piece string (including ▁-prefixed forms and `<0xXX>`
 *   byte fallback tokens) to vocab id.
 * @property scores per-id score; for BPE this drives the merge priority queue (higher is
 *   better). Length equals vocab size.
 * @property byteFallbackBase id of the `<0x00>` piece; byte `b` ⇒ id `byteFallbackBase + b`.
 */
internal class SpModel(
  val pieceToId: HashMap<String, Int>,
  val scores: FloatArray,
  val byteFallbackBase: Int,
)

/**
 * Minimal protobuf wire-format reader specialised for SentencePiece `ModelProto`. We only
 * read top-level field 1 (`repeated SentencePiece pieces`). Each piece has:
 *  - field 1: `piece` (string, wire type 2)
 *  - field 2: `score` (float, wire type 5)
 *  - field 3: `type` (enum/varint, wire type 0; default NORMAL=1, BYTE=6)
 *
 * Unknown fields are skipped via standard wire-type dispatch.
 *
 * **DoS hardening:** every length-delimited read is bounded by the parent buffer. A malformed
 * proto with a length exceeding the remaining bytes throws [IllegalStateException] instead of
 * allocating an arbitrary-sized buffer.
 */
internal object SpModelParser {

  private const val WIRE_VARINT = 0
  private const val WIRE_FIXED64 = 1
  private const val WIRE_LENGTH_DELIMITED = 2
  private const val WIRE_FIXED32 = 5

  private const val PIECE_TYPE_BYTE = 6

  fun parse(bytes: ByteArray): SpModel {
    val pieces = ArrayList<String>()
    val pieceScores = ArrayList<Float>()
    val pieceTypes = ArrayList<Int>()
    val cursor = Cursor(bytes, end = bytes.size)
    while (!cursor.atEnd()) {
      val (field, wire) = cursor.readTag()
      if (field == 1 && wire == WIRE_LENGTH_DELIMITED) {
        val len = cursor.readVarintAsInt()
        val sub = cursor.subCursor(len)
        parsePiece(sub, pieces, pieceScores, pieceTypes)
        cursor.skip(len)
      } else {
        cursor.skipField(wire)
      }
    }

    val vocabSize = pieces.size
    require(vocabSize > 0) { "SentencePiece model contained no pieces" }
    val pieceToId = HashMap<String, Int>((vocabSize * 1.4f).toInt())
    val scores = FloatArray(vocabSize)
    var byteFallbackBase = -1
    for (id in 0 until vocabSize) {
      pieceToId[pieces[id]] = id
      scores[id] = pieceScores[id]
      if (pieceTypes[id] == PIECE_TYPE_BYTE && byteFallbackBase == -1) {
        byteFallbackBase = id
      }
    }
    require(byteFallbackBase >= 0) {
      "SentencePiece model has no BYTE pieces — byte_fallback expected for EmbeddingGemma"
    }

    return SpModel(pieceToId = pieceToId, scores = scores, byteFallbackBase = byteFallbackBase)
  }

  private fun parsePiece(
    sub: Cursor,
    pieces: MutableList<String>,
    scores: MutableList<Float>,
    types: MutableList<Int>,
  ) {
    var piece = ""
    var score = 0f
    var type = 1 // NORMAL
    while (!sub.atEnd()) {
      val (field, wire) = sub.readTag()
      when {
        field == 1 && wire == WIRE_LENGTH_DELIMITED -> {
          val len = sub.readVarintAsInt()
          piece = sub.readUtf8(len)
        }
        field == 2 && wire == WIRE_FIXED32 -> score = sub.readFloat32()
        field == 3 && wire == WIRE_VARINT -> type = sub.readVarintAsInt()
        else -> sub.skipField(wire)
      }
    }
    pieces.add(piece)
    scores.add(score)
    types.add(type)
  }

  /**
   * Position-tracking cursor over a byte slice. NOT thread-safe — protobuf parsing is single-
   * threaded by design.
   */
  private class Cursor(private val buf: ByteArray, var pos: Int = 0, var end: Int) {
    fun atEnd(): Boolean = pos >= end

    /** Returns (fieldNumber, wireType) for the next tag. */
    fun readTag(): Pair<Int, Int> {
      val tag = readVarintAsLong()
      return ((tag ushr 3).toInt()) to (tag.toInt() and 0x07)
    }

    fun readVarintAsLong(): Long {
      var result = 0L
      var shift = 0
      while (true) {
        require(pos < end) { "truncated varint" }
        val b = buf[pos++].toInt() and 0xFF
        result = result or ((b and 0x7F).toLong() shl shift)
        if ((b and 0x80) == 0) return result
        shift += 7
        require(shift <= 63) { "varint overflow" }
      }
    }

    fun readVarintAsInt(): Int {
      val v = readVarintAsLong()
      require(v in 0..Int.MAX_VALUE) { "varint out of int range: $v" }
      return v.toInt()
    }

    fun readUtf8(len: Int): String {
      require(len >= 0 && pos + len <= end) {
        "string length $len exceeds parent buffer at pos=$pos end=$end"
      }
      val s = String(buf, pos, len, Charsets.UTF_8)
      pos += len
      return s
    }

    fun readFloat32(): Float {
      require(pos + 4 <= end) { "truncated fixed32 at pos=$pos" }
      val bits = ((buf[pos].toInt() and 0xFF)) or
        ((buf[pos + 1].toInt() and 0xFF) shl 8) or
        ((buf[pos + 2].toInt() and 0xFF) shl 16) or
        ((buf[pos + 3].toInt() and 0xFF) shl 24)
      pos += 4
      return Float.fromBits(bits)
    }

    fun subCursor(len: Int): Cursor {
      require(len >= 0 && pos + len <= end) {
        "sub-message length $len exceeds parent buffer at pos=$pos end=$end"
      }
      return Cursor(buf, pos = pos, end = pos + len)
    }

    fun skip(bytes: Int) {
      require(bytes >= 0 && pos + bytes <= end) {
        "skip $bytes exceeds parent buffer at pos=$pos end=$end"
      }
      pos += bytes
    }

    fun skipField(wireType: Int) {
      when (wireType) {
        WIRE_VARINT -> readVarintAsLong()
        WIRE_FIXED64 -> skip(8)
        WIRE_LENGTH_DELIMITED -> {
          val len = readVarintAsInt()
          skip(len)
        }
        WIRE_FIXED32 -> skip(4)
        else -> error("unknown protobuf wire type: $wireType")
      }
    }
  }
}
