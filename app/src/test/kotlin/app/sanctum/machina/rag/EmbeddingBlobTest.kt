package app.sanctum.machina.rag

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EmbeddingBlobTest {

  @Test
  fun roundtrip_preservesFloatArrayBitwise() {
    val rnd = Random(seed = 1234)
    val src = FloatArray(384) { rnd.nextFloat() * 2f - 1f }
    val blob = EmbeddingBlob.encode(src)
    assertEquals(384 * 4, blob.size)
    val back = EmbeddingBlob.decode(blob)
    assertEquals(src.size, back.size)
    for (i in src.indices) {
      assertEquals(
        "bit mismatch at index $i",
        src[i].toRawBits(),
        back[i].toRawBits(),
      )
    }
  }

  @Test
  fun roundtrip_handlesEmptyArray() {
    val blob = EmbeddingBlob.encode(FloatArray(0))
    assertEquals(0, blob.size)
    val back = EmbeddingBlob.decode(blob)
    assertEquals(0, back.size)
  }

  @Test
  fun roundtrip_preservesNaN_andInfinity() {
    val src = floatArrayOf(
      Float.NaN,
      Float.POSITIVE_INFINITY,
      Float.NEGATIVE_INFINITY,
      -0f,
      0f,
      Float.MIN_VALUE,
      Float.MAX_VALUE,
    )
    val back = EmbeddingBlob.decode(EmbeddingBlob.encode(src))
    assertEquals(src.size, back.size)
    for (i in src.indices) {
      // Float.NaN != Float.NaN by IEEE, so compare raw bits.
      assertEquals(
        "bit mismatch at index $i (src=${src[i]} back=${back[i]})",
        src[i].toRawBits(),
        back[i].toRawBits(),
      )
    }
  }

  @Test
  fun littleEndian_order_verifiedAgainstKnownBytes() {
    // 1.0f IEEE-754 = 0x3F800000 → LE bytes: 00 00 80 3F.
    val blob = EmbeddingBlob.encode(floatArrayOf(1.0f))
    assertArrayEquals(
      byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F),
      blob,
    )
  }

  @Test
  fun decode_blobNotDivisibleBy4_throws_IllegalArgumentException() {
    assertThrows(IllegalArgumentException::class.java) {
      EmbeddingBlob.decode(ByteArray(7))
    }
    assertThrows(IllegalArgumentException::class.java) {
      EmbeddingBlob.decode(ByteArray(1))
    }
  }
}
