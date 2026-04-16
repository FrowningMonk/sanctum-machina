package app.sanctum.machina.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AudioClipTest {

  @Test
  fun construction_emptyData_preservesSampleRate() {
    val clip = AudioClip(audioData = ByteArray(0), sampleRate = 44100)
    assertEquals(0, clip.audioData.size)
    assertEquals(44100, clip.sampleRate)
  }

  @Test
  fun construction_oddDataSize_noException() {
    val bytes = ByteArray(7) { it.toByte() }
    val clip = AudioClip(audioData = bytes, sampleRate = 16000)
    assertEquals(7, clip.audioData.size)
    assertEquals(16000, clip.sampleRate)
  }

  @Test
  fun construction_preservesAudioData_byReference() {
    val bytes = byteArrayOf(1, 2, 3, 4)
    val clip = AudioClip(audioData = bytes, sampleRate = 16000)
    assertSame("plain class must not defensive-copy audioData", bytes, clip.audioData)
  }
}
