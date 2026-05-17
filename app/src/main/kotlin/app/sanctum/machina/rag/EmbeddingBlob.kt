package app.sanctum.machina.rag

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format for embedding vectors stored as `project_embeddings.embedding_blob`
 * (Decision 10).
 *
 * Layout: tightly packed IEEE-754 little-endian `Float`s, 4 bytes each, no
 * header. Encoding is bit-exact (NaN payload, signed zero, ±Infinity all
 * survive a roundtrip). Endianness is pinned to LE explicitly so the on-disk
 * format stays platform-independent — every current Android device is LE, but
 * we never want this to depend on `ByteOrder.nativeOrder()`.
 */
object EmbeddingBlob {

  /** Encodes [floats] as a little-endian `4 * floats.size`-byte array. */
  fun encode(floats: FloatArray): ByteArray {
    val buf = ByteBuffer.allocate(floats.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
    buf.asFloatBuffer().put(floats)
    return buf.array()
  }

  /**
   * Decodes a little-endian float blob produced by [encode].
   *
   * @throws IllegalArgumentException if [blob]'s length is not a multiple of
   *   `Float.SIZE_BYTES` (4) — the blob would otherwise yield a truncated
   *   float, which on the retrieval side surfaces as a silent dim mismatch.
   */
  fun decode(blob: ByteArray): FloatArray {
    require(blob.size % Float.SIZE_BYTES == 0) {
      "blob length not divisible by 4, was ${blob.size}"
    }
    val fb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    val out = FloatArray(fb.remaining())
    fb.get(out)
    return out
  }
}
