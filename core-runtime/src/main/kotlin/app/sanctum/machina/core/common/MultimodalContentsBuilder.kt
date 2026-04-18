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
import java.io.ByteArrayOutputStream

object MultimodalContentsBuilder {
  fun build(
    text: String,
    images: List<Bitmap>,
    audio: List<ByteArray>,
  ): List<Content> {
    val result = ArrayList<Content>(images.size + audio.size + 1)
    for (image in images) {
      result.add(Content.ImageBytes(image.toPngByteArray()))
    }
    for (clip in audio) {
      result.add(Content.AudioBytes(clip))
    }
    // Whitespace-only text is treated as empty — matches the previous
    // LlmChatModelHelper.runInference behavior (`input.trim().isNotEmpty()`).
    if (text.isNotBlank()) {
      result.add(Content.Text(text))
    }
    return result
  }

  private fun Bitmap.toPngByteArray(): ByteArray {
    val stream = ByteArrayOutputStream()
    this.compress(Bitmap.CompressFormat.PNG, 100, stream)
    return stream.toByteArray()
  }
}
