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
    assertTrue("ImageBytes must be non-empty PNG payload", imageBytes.isNotEmpty())
    assertArrayEquals(audio, (result[1] as Content.AudioBytes).bytes)
    assertEquals("hello", (result[2] as Content.Text).text)
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
