/*
 * Copyright 2026 Google LLC
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

package app.sanctum.machina.core.runtime

import android.content.Context
import android.graphics.Bitmap
import app.sanctum.machina.core.data.Model
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CoroutineScope

// `benchmarkInfo` is only populated on the terminal callback (`done = true`)
// and only when the underlying engine exposes per-conversation stats — null
// in tests / fakes and on intermediate streaming chunks.
typealias ResultListener =
  (
    partialResult: String,
    done: Boolean,
    partialThinkingResult: String?,
    benchmarkInfo: BenchmarkInfo?,
  ) -> Unit

typealias CleanUpListener = () -> Unit

interface LlmModelHelper {
  fun initialize(
    context: Context,
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    onDone: (String) -> Unit,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    enableConversationConstrainedDecoding: Boolean = false,
    coroutineScope: CoroutineScope? = null,
  )

  fun resetConversation(
    model: Model,
    supportImage: Boolean = false,
    supportAudio: Boolean = false,
    systemInstruction: Contents? = null,
    tools: List<ToolProvider> = listOf(),
    enableConversationConstrainedDecoding: Boolean = false,
    initialMessages: List<Message> = emptyList(),
  )

  fun cleanUp(model: Model, onDone: () -> Unit)

  fun runInference(
    model: Model,
    input: String,
    resultListener: ResultListener,
    cleanUpListener: CleanUpListener,
    onError: (message: String) -> Unit = {},
    images: List<Bitmap> = listOf(),
    audioClips: List<ByteArray> = listOf(),
    coroutineScope: CoroutineScope? = null,
    extraContext: Map<String, String>? = null,
  )

  fun stopResponse(model: Model)
}
