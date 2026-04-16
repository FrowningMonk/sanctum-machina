/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.sanctum.machina.core.common

import android.graphics.Bitmap
import com.google.ai.edge.litertlm.Content
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MultimodalContentsBuilderTest {

  private fun makeBitmap(): Bitmap =
    Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

  private fun isPng(bytes: ByteArray): Boolean =
    bytes.size >= 4 &&
      bytes[0] == 0x89.toByte() &&
      bytes[1] == 0x50.toByte() && // 'P'
      bytes[2] == 0x4E.toByte() && // 'N'
      bytes[3] == 0x47.toByte()    // 'G'

  @Test
  fun textAndImagesAndAudio_correctOrder() {
    val bitmap = makeBitmap()
    val audio = byteArrayOf(1, 2, 3)

    val result = MultimodalContentsBuilder.build(
      text = "hello",
      images = listOf(bitmap),
      audio = listOf(audio),
    )

    assertEquals(3, result.size)
    assertTrue("index 0 must be ImageBytes", result[0] is Content.ImageBytes)
    assertTrue("index 1 must be AudioBytes", result[1] is Content.AudioBytes)
    assertTrue("index 2 must be Text", result[2] is Content.Text)

    val imageBytes = (result[0] as Content.ImageBytes).bytes
    assertTrue("ImageBytes must carry a PNG payload", isPng(imageBytes))
    assertArrayEquals(audio, (result[1] as Content.AudioBytes).bytes)
    assertEquals("hello", (result[2] as Content.Text).text)
  }

  @Test
  fun multipleImagesAndAudio_preservesInputOrderWithinGroup() {
    val img1 = makeBitmap()
    val img2 = makeBitmap()
    val audio1 = byteArrayOf(10, 11, 12)
    val audio2 = byteArrayOf(20, 21, 22)

    val result = MultimodalContentsBuilder.build(
      text = "t",
      images = listOf(img1, img2),
      audio = listOf(audio1, audio2),
    )

    assertEquals(5, result.size)
    assertTrue(result[0] is Content.ImageBytes)
    assertTrue(result[1] is Content.ImageBytes)
    assertTrue(result[2] is Content.AudioBytes)
    assertTrue(result[3] is Content.AudioBytes)
    assertTrue(result[4] is Content.Text)

    assertArrayEquals(audio1, (result[2] as Content.AudioBytes).bytes)
    assertArrayEquals(audio2, (result[3] as Content.AudioBytes).bytes)
  }

  @Test
  fun textAndImages_noAudio() {
    val result = MultimodalContentsBuilder.build(
      text = "caption",
      images = listOf(makeBitmap()),
      audio = emptyList(),
    )

    assertEquals(2, result.size)
    assertTrue(result[0] is Content.ImageBytes)
    assertTrue(result[1] is Content.Text)
    assertEquals("caption", (result[1] as Content.Text).text)
  }

  @Test
  fun textAndAudio_noImages() {
    val audio = byteArrayOf(7, 8, 9)
    val result = MultimodalContentsBuilder.build(
      text = "narration",
      images = emptyList(),
      audio = listOf(audio),
    )

    assertEquals(2, result.size)
    assertTrue(result[0] is Content.AudioBytes)
    assertTrue(result[1] is Content.Text)
    assertArrayEquals(audio, (result[0] as Content.AudioBytes).bytes)
  }

  @Test
  fun imagesAndAudio_noText() {
    val audio = byteArrayOf(4, 5, 6)
    val result = MultimodalContentsBuilder.build(
      text = "",
      images = listOf(makeBitmap()),
      audio = listOf(audio),
    )

    assertEquals(2, result.size)
    assertTrue(result[0] is Content.ImageBytes)
    assertTrue(result[1] is Content.AudioBytes)
    assertTrue("no Text content for empty input", result.none { it is Content.Text })
  }

  @Test
  fun emptyText_noTextContent() {
    val result = MultimodalContentsBuilder.build(
      text = "",
      images = emptyList(),
      audio = emptyList(),
    )

    assertTrue("no Text content for empty input", result.none { it is Content.Text })
    assertEquals(0, result.size)
  }

  // Whitespace-only input is treated as empty — preserves the pre-refactor
  // behavior of LlmChatModelHelper.runInference (`input.trim().isNotEmpty()`).
  @Test
  fun whitespaceText_noTextContent() {
    val result = MultimodalContentsBuilder.build(
      text = "   \n\t  ",
      images = emptyList(),
      audio = emptyList(),
    )

    assertTrue("whitespace-only text must not produce Text content", result.none { it is Content.Text })
  }

  @Test
  fun textOnly_singleTextContent() {
    val result = MultimodalContentsBuilder.build(
      text = "only text",
      images = emptyList(),
      audio = emptyList(),
    )

    assertEquals(1, result.size)
    assertTrue(result[0] is Content.Text)
    assertEquals("only text", (result[0] as Content.Text).text)
  }
}
