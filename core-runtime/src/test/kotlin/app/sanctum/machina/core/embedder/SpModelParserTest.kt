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

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 Task 18 round-1 test review follow-up: exercise the DoS-hardening branches in
 * [SpModelParser] that the fixture suite cannot reach. All inputs are hand-crafted byte
 * arrays — no asset dependency — so these tests fail loud if a future refactor weakens the
 * parser's `require()` gates.
 *
 * Encoding helpers below implement just enough of protobuf wire format to build pathological
 * messages: see [varint] and [tag] for the primitives, [pieceField], [piece] for the
 * model-specific encoders.
 */
class SpModelParserTest {

  @Test
  fun truncatedVarint_throws() {
    // Single byte with continuation bit set, no follow-up byte.
    val malformed = byteArrayOf(0x80.toByte())
    val ex = assertThrows(IllegalArgumentException::class.java) {
      SpModelParser.parse(malformed)
    }
    assertTrue(
      "expected 'truncated varint' in: ${ex.message}",
      ex.message?.contains("truncated varint") == true,
    )
  }

  @Test
  fun varintOverflow_throws() {
    // 11 bytes all with continuation bit set — varint would exceed 64 bits.
    val overflowing = ByteArray(11) { 0x80.toByte() }
    val ex = assertThrows(IllegalArgumentException::class.java) {
      SpModelParser.parse(overflowing)
    }
    assertTrue(
      "expected 'varint overflow' in: ${ex.message}",
      ex.message?.contains("varint overflow") == true,
    )
  }

  @Test
  fun subMessageLengthExceedsBuffer_throws() {
    // Top-level: field 1 (pieces), wire 2 (length-delimited), length=100 but only 0 follow.
    val truncated = byteArrayOf(
      0x0A, // tag for field 1, wire type 2
      0x64, // varint length = 100
    )
    val ex = assertThrows(IllegalArgumentException::class.java) {
      SpModelParser.parse(truncated)
    }
    assertTrue(
      "expected buffer-bounds error in: ${ex.message}",
      ex.message?.contains("exceeds parent buffer") == true,
    )
  }

  @Test
  fun noPieces_throws() {
    // Empty message — no top-level fields. Parser should reject vocab_size == 0.
    val empty = ByteArray(0)
    val ex = assertThrows(IllegalArgumentException::class.java) {
      SpModelParser.parse(empty)
    }
    assertTrue(
      "expected 'no pieces' message in: ${ex.message}",
      ex.message?.contains("no pieces") == true,
    )
  }

  @Test
  fun noByteFallbackPieces_throws() {
    // One NORMAL piece only — no BYTE-type pieces.
    val modelBytes = encodeModel(
      pieces = listOf(
        Piece(text = "a", score = -1f, type = 1), // NORMAL
      )
    )
    val ex = assertThrows(IllegalArgumentException::class.java) {
      SpModelParser.parse(modelBytes)
    }
    assertTrue(
      "expected 'no BYTE pieces' in: ${ex.message}",
      ex.message?.contains("no BYTE pieces") == true,
    )
  }

  @Test
  fun nonContiguousByteFallback_throws() {
    // BYTE pieces start at id=0 but only one BYTE piece exists; the parser requires 256
    // contiguous BYTE ids and must reject this shape.
    val pieces = mutableListOf<Piece>()
    pieces.add(Piece(text = "<0x00>", score = 0f, type = 6)) // BYTE at id=0
    // Pad to 256 with NORMAL pieces so vocab_size > 255 but the contiguous-256 invariant fails.
    for (i in 1..256) pieces.add(Piece(text = "n$i", score = -1f, type = 1))
    val modelBytes = encodeModel(pieces)
    val ex = assertThrows(IllegalArgumentException::class.java) {
      SpModelParser.parse(modelBytes)
    }
    assertTrue(
      "expected non-contiguous byte_fallback error in: ${ex.message}",
      ex.message?.contains("non-contiguous") == true,
    )
  }

  @Test
  fun emptyPieceString_throws() {
    // Round-1 code review: malformed piece with no `piece` field (deserializes as "").
    val modelBytes = encodeModel(
      pieces = listOf(Piece(text = "", score = 0f, type = 1))
    )
    val ex = assertThrows(IllegalArgumentException::class.java) {
      SpModelParser.parse(modelBytes)
    }
    assertTrue(
      "expected 'empty piece' in: ${ex.message}",
      ex.message?.contains("empty piece string") == true,
    )
  }

  // -- minimal protobuf wire-format encoders for test fixtures --------------------------

  private data class Piece(val text: String, val score: Float, val type: Int)

  private fun encodeModel(pieces: List<Piece>): ByteArray {
    val out = mutableListOf<Byte>()
    for (p in pieces) {
      val pieceBytes = encodePiece(p)
      // Top-level tag: field 1, wire type 2 (length-delimited).
      out += tag(field = 1, wire = 2)
      out += varint(pieceBytes.size.toLong())
      out += pieceBytes.toList()
    }
    return out.toByteArray()
  }

  private fun encodePiece(p: Piece): ByteArray {
    val out = mutableListOf<Byte>()
    if (p.text.isNotEmpty()) {
      val utf8 = p.text.toByteArray(Charsets.UTF_8)
      out += tag(field = 1, wire = 2)
      out += varint(utf8.size.toLong())
      out += utf8.toList()
    }
    out += tag(field = 2, wire = 5) // fixed32 float
    val bits = p.score.toRawBits()
    out += (bits and 0xFF).toByte()
    out += ((bits ushr 8) and 0xFF).toByte()
    out += ((bits ushr 16) and 0xFF).toByte()
    out += ((bits ushr 24) and 0xFF).toByte()
    out += tag(field = 3, wire = 0)
    out += varint(p.type.toLong())
    return out.toByteArray()
  }

  private fun tag(field: Int, wire: Int): Byte {
    val tag = (field shl 3) or wire
    require(tag <= 127) { "test helper does not handle multi-byte tags" }
    return tag.toByte()
  }

  private fun varint(value: Long): List<Byte> {
    require(value >= 0) { "negative varints not used in this test" }
    val out = mutableListOf<Byte>()
    var v = value
    while ((v and 0x7F.inv()) != 0L) {
      out += ((v and 0x7F) or 0x80).toByte()
      v = v ushr 7
    }
    out += v.toByte()
    return out
  }
}
