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
  fun pcmToWav_buildsCanonicalRiffWaveHeader() {
    // 4 bytes of PCM → 44-byte header + 4-byte data.
    val pcm = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    val wav = pcmToWav(pcm, sampleRate = 16000)

    assertEquals(48, wav.size)
    // RIFF / WAVE / fmt  / data magic bytes
    assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
    assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
    assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
    assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

    val headerView = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(40, headerView.getInt(4))        // RIFF chunk size = 36 + data (4)
    assertEquals(16, headerView.getInt(16))       // fmt sub-chunk size
    assertEquals(1.toShort(), headerView.getShort(20))  // PCM format tag
    assertEquals(1.toShort(), headerView.getShort(22))  // channels = mono
    assertEquals(16000, headerView.getInt(24))    // sample rate
    assertEquals(32000, headerView.getInt(28))    // byte rate = 16000 × 1 × 16 / 8
    assertEquals(2.toShort(), headerView.getShort(32))  // block align
    assertEquals(16.toShort(), headerView.getShort(34)) // bits per sample
    assertEquals(4, headerView.getInt(40))        // data sub-chunk size
    // Payload appended verbatim.
    assertEquals(0x11.toByte(), wav[44])
    assertEquals(0x44.toByte(), wav[47])
  }

  @Test
  fun pcmToWav_emptyPcm_stillProducesValidHeader() {
    val wav = pcmToWav(ByteArray(0), sampleRate = 16000)
    assertEquals(44, wav.size)
    val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(36, buf.getInt(4))   // RIFF chunk size = 36 for zero data
    assertEquals(0, buf.getInt(40))   // data sub-chunk size = 0
  }

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
