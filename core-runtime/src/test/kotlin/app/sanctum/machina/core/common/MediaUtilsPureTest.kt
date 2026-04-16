package app.sanctum.machina.core.common

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM tests — no Robolectric — for the Android-free slice of `MediaUtils`.
 * Realises the D20 seam: `calculateInSampleSize` and `calculatePeakAmplitude`
 * take primitive types only and must stay testable without a Robolectric
 * runtime (fast, no Skia, no shadow-based pixel backing).
 */
class MediaUtilsPureTest {

  @Test
  fun calculateInSampleSize_exactFit_2x_4x_8x() {
    assertEquals(2, calculateInSampleSize(rawWidth = 2048, rawHeight = 2048, reqWidth = 1024, reqHeight = 1024))
    assertEquals(4, calculateInSampleSize(rawWidth = 2048, rawHeight = 2048, reqWidth = 512, reqHeight = 512))
    assertEquals(8, calculateInSampleSize(rawWidth = 2048, rawHeight = 2048, reqWidth = 256, reqHeight = 256))
  }

  @Test
  fun calculateInSampleSize_smallerThanRequested_returnsOne() {
    assertEquals(1, calculateInSampleSize(rawWidth = 512, rawHeight = 512, reqWidth = 1024, reqHeight = 1024))
  }

  @Test
  fun calculateInSampleSize_asymmetric_takesLargerRatio() {
    // 2048 wide × 256 tall; request 1024 × 1024 → width ratio = 2, height ratio = 0 → max = 2
    assertEquals(2, calculateInSampleSize(rawWidth = 2048, rawHeight = 256, reqWidth = 1024, reqHeight = 1024))
  }

  @Test
  fun calculatePeakAmplitude_emptyBuffer_returnsZero() {
    assertEquals(0f, calculatePeakAmplitude(ByteArray(0)), 0f)
  }

  @Test
  fun calculatePeakAmplitude_bytesReadZero_returnsZero() {
    assertEquals(0f, calculatePeakAmplitude(ByteArray(4), bytesRead = 0), 0f)
  }

  @Test
  fun calculatePeakAmplitude_mixedValues_returnsMax() {
    // LE 16-bit shorts: 1000, -2000, 500 → max abs = 2000
    val buffer = ByteArray(6)
    ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().apply {
      put(1000.toShort())
      put((-2000).toShort())
      put(500.toShort())
    }
    assertEquals(2000f, calculatePeakAmplitude(buffer), 0f)
  }

  @Test
  fun calculatePeakAmplitude_boundary_shortMaxValue() {
    val buffer = ByteArray(2)
    ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(Short.MAX_VALUE)
    assertEquals(Short.MAX_VALUE.toFloat(), calculatePeakAmplitude(buffer), 0f)
  }

  @Test
  fun calculatePeakAmplitude_oddSize_dropsTrailingByte() {
    // 4 bytes = 2 shorts (100, 200), plus one stray byte → trailing byte ignored
    val buffer = ByteArray(5)
    ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().apply {
      put(100.toShort())
      put(200.toShort())
    }
    buffer[4] = 0x7F
    assertEquals(200f, calculatePeakAmplitude(buffer), 0f)
  }

  @Test
  fun calculatePeakAmplitude_respectsBytesRead() {
    // Full buffer has a louder sample after bytesRead — must be ignored.
    val buffer = ByteArray(6)
    ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().apply {
      put(100.toShort())
      put(200.toShort())
      put(Short.MAX_VALUE) // past bytesRead=4
    }
    assertEquals(200f, calculatePeakAmplitude(buffer, bytesRead = 4), 0f)
  }
}
