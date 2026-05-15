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

import android.content.Context
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Phase 4 Task 1 / Decision 3: UI-free runtime helper for EmbeddingGemma-300M on the regular
 * LiteRT Interpreter (NOT litert-lm). Peer of [LlmChatModelHelper] — same module, same layer.
 *
 * Two independent components live behind [encode]:
 *  - A [CompiledModel] holding the .tflite graph + GPU/CPU delegate (this class loads it).
 *  - A SentencePiece tokenizer that turns text into int32 token IDs the graph expects
 *    (see [Tokenizer] below — deferred to Task 1 follow-up / Task 4 wiring; see spike-day1.md
 *    § "Open question: SentencePiece tokenizer JVM choice").
 *
 * The LiteRT graph itself does NOT include tokenization: per the
 * litert-community/embeddinggemma-300m model card, the bundled `sentencepiece.model` (4.68 MB)
 * must be invoked from JVM code before each `model.run`. The current spike does not commit
 * to a JVM SentencePiece dep — that decision blocks T4 EmbedderRegistry wiring and is
 * surfaced in the spike report.
 *
 * Thread safety: a single LiteRT [CompiledModel] is **not** safe across concurrent calls.
 * This class does **not** serialize encode calls itself — that responsibility lives in
 * [EmbedderRegistry] (Task 4) which holds an `encodeMutex` per Decision 2. [initialize] and
 * [releaseEngine] are guarded internally so init/teardown can't race the registry's encode.
 */
class EmbeddingGemmaEngine @javax.inject.Inject constructor() : EmbedderEngine {

  private val lifecycleMutex = Mutex()
  @Volatile private var compiledModel: CompiledModel? = null
  @Volatile private var tokenizer: Tokenizer? = null

  /**
   * Load the .tflite graph onto LiteRT with a GPU-then-CPU accelerator order. Idempotent —
   * any prior engine is released before the new one is opened. Suspending so the native
   * open runs on [Dispatchers.IO] off the caller's thread.
   *
   * Failure recovery contract: if any step after the model successfully opens fails (e.g.
   * tokenizer creation throws), the partially-loaded native [CompiledModel] is closed before
   * the result is returned as failure. Callers can retry without leaking a native handle.
   *
   * **Spec deviation [code-reviewer round 1 minor]:** task spec lists `initialize(Context, File)`.
   * This implementation widens to `(Context, File, File)` — the SentencePiece tokenizer is a
   * separate file (`sentencepiece.model`) bundled alongside the .tflite on HF, and the
   * tokenizer must be wired before the engine can encode. Logged in decisions.md.
   *
   * @param context Application or any [Context] (Decision 3: no Application reach-up).
   * @param modelFile downloaded .tflite file (resolved by the registry from [Model.getPath]).
   * @param tokenizerFile `sentencepiece.model` companion file (extraDataFiles entry).
   */
  override suspend fun initialize(
    context: Context,
    modelFile: File,
    tokenizerFile: File,
  ): Result<Unit> = withContext(Dispatchers.IO) {
    lifecycleMutex.withLock {
      runCatching {
        require(modelFile.exists()) { "model file missing: ${modelFile.absolutePath}" }
        require(tokenizerFile.exists()) {
          "tokenizer file missing: ${tokenizerFile.absolutePath}"
        }
        releaseEngineInternal()
        val opened = openWithAcceleratorOrder(modelFile, accelerators = ACCELERATOR_ORDER)
        val tok: Tokenizer = try {
          Tokenizer.create(tokenizerFile)
        } catch (t: Throwable) {
          // Tokenizer construction failed AFTER model open — close the native handle so a
          // retry doesn't leak it. Re-throw so `runCatching` records the failure.
          try { opened.close() } catch (closeError: Throwable) {
            Log.w(TAG, "CompiledModel.close failed during init-rollback", closeError)
          }
          throw t
        }
        compiledModel = opened
        tokenizer = tok
      }
    }
  }

  /**
   * Encode `text` into a fixed-length [FloatArray] using `taskType` to drive EmbeddingGemma's
   * task-specific prompt prefix (see model card). Synchronous so the caller's coroutine
   * dispatcher controls scheduling. Caller MUST hold any required serialization mutex —
   * EmbedderRegistry's `encodeMutex` is the production path.
   *
   * @param taskType one of `"retrieval_document"` (ingest) or `"retrieval_query"` (query).
   *   Other values from the model card (`classification`, `similarity`, etc.) are valid but
   *   unused by Sanctum's RAG pipeline.
   */
  override fun encode(text: String, taskType: String): FloatArray {
    val model = compiledModel
      ?: error("EmbeddingGemmaEngine not initialized — call initialize() first")
    val tok = tokenizer
      ?: error("EmbeddingGemmaEngine tokenizer missing — call initialize() first")
    val prompted = applyTaskPrompt(taskType, text)
    val tokenIds: IntArray = tok.encode(prompted, maxLength = MAX_INPUT_TOKENS)
    return runInference(model, tokenIds)
  }

  /**
   * Free native resources. Idempotent. Safe to call from any thread.
   *
   * Synchronization contract:
   *  - **Concurrent `encode()` during release** — the registry's responsibility (Decision 2:
   *    EmbedderRegistry holds `encodeMutex` across encode + release transitions).
   *  - **Concurrent `initialize()` during release** — the registry's responsibility as well.
   *    `initialize()` uses suspending [lifecycleMutex]; this method uses a plain
   *    `synchronized(this)` snapshot because it must remain non-suspending (callable from a
   *    `Closeable`-style cleanup path / finalizer). The two locks are NOT the same monitor,
   *    so a registry that calls both APIs from multiple coroutines must serialize them
   *    externally. Within a single coroutine the ordering is well-defined.
   */
  override fun releaseEngine() {
    // We snapshot under `synchronized(this)` so a racing initialize() can't observe a
    // half-cleared state.
    val (modelToClose, tokenizerToClose) = synchronized(this) {
      val pair = compiledModel to tokenizer
      compiledModel = null
      tokenizer = null
      pair
    }
    try {
      modelToClose?.close()
    } catch (e: Throwable) {
      Log.w(TAG, "CompiledModel.close threw", e)
    }
    try {
      tokenizerToClose?.close()
    } catch (e: Throwable) {
      Log.w(TAG, "Tokenizer.close threw", e)
    }
  }

  private fun releaseEngineInternal() {
    val m = compiledModel
    val t = tokenizer
    compiledModel = null
    tokenizer = null
    try { m?.close() } catch (e: Throwable) { Log.w(TAG, "release model", e) }
    try { t?.close() } catch (e: Throwable) { Log.w(TAG, "release tokenizer", e) }
  }

  /**
   * Try each accelerator in [accelerators] order; surface the LAST failure if none succeed.
   * GPU delegate creation can throw on devices with broken Adreno/Mali drivers or with the
   * model's specific ops not supported on GPU — CPU fallback is the bench-day-2 escape hatch.
   */
  private fun openWithAcceleratorOrder(
    modelFile: File,
    accelerators: List<Accelerator>,
  ): CompiledModel {
    val path = modelFile.absolutePath
    var lastError: Throwable? = null
    for (accelerator in accelerators) {
      try {
        return CompiledModel.create(path, CompiledModel.Options(accelerator))
      } catch (t: Throwable) {
        Log.w(TAG, "CompiledModel.create failed on $accelerator, trying next", t)
        lastError = t
      }
    }
    throw IllegalStateException(
      "LiteRT CompiledModel could not open with any of $accelerators",
      lastError,
    )
  }

  private fun applyTaskPrompt(taskType: String, text: String): String {
    // Per EmbeddingGemma model card § "Usage with sentence-transformers" the encoder expects
    // task-specific prompt prefixes. The exact strings are stable across the model card and
    // model.safetensors config; mirror them here.
    return when (taskType) {
      "retrieval_document" -> "title: none | text: $text"
      "retrieval_query" -> "task: search result | query: $text"
      "classification" -> "task: classification | query: $text"
      "similarity" -> "task: sentence similarity | query: $text"
      else -> text
    }
  }

  private fun runInference(model: CompiledModel, tokenIds: IntArray): FloatArray {
    // Spike note: the exact buffer-fill API on `CompiledModel.createInputBuffers()` for an
    // int32 token tensor is verified empirically during the bench-day-2 device session. The
    // structural shape — write padded int32 IDs, run, read first output buffer as floats — is
    // standard across LiteRT samples (litert-samples/.../semantic_similarity/main.cc).
    val padded = IntArray(MAX_INPUT_TOKENS).also { dst ->
      val n = minOf(tokenIds.size, MAX_INPUT_TOKENS)
      System.arraycopy(tokenIds, 0, dst, 0, n)
      // 0-pad rest (SentencePiece <pad> id is 0 for Gemma family).
    }
    val inputBuffers = model.createInputBuffers()
    val outputBuffers = model.createOutputBuffers()
    inputBuffers[0].writeInt(padded)
    model.run(inputBuffers, outputBuffers)
    return outputBuffers[0].readFloat()
  }

  /**
   * SentencePiece tokenizer placeholder. The actual JVM-side SentencePiece dependency is an
   * open question recorded in the spike report — candidates are `ai.djl:sentencepiece`
   * (Apache 2.0, pure-JVM), a thin JNI wrapper around libsentencepiece, or a pure-Kotlin
   * port. Choosing one is gated on (a) license fit, (b) cold-start cost vs runtime cost,
   * (c) APK size impact. T4 EmbedderRegistry consumes a settled Tokenizer.create.
   */
  internal interface Tokenizer : AutoCloseable {
    fun encode(text: String, maxLength: Int): IntArray
    companion object {
      fun create(modelFile: File): Tokenizer = throw NotImplementedError(
        "SentencePiece tokenizer not wired — see work/phase-4-projects-rag/logs/spike-day1.md " +
          "§ Open question: SentencePiece tokenizer JVM choice"
      )
    }
  }

  companion object {
    private const val TAG = "EmbeddingGemmaEngine"
    /** EmbeddingGemma seq2048 variant — matches the bundled .tflite suffix. */
    private const val MAX_INPUT_TOKENS = 2048
    /**
     * Accelerator fallback order. GPU first (Honor 200 Adreno 720), CPU as the safety net.
     * Decision 2 thread-safety contract still applies — encode is serialized in the registry
     * regardless of which delegate ultimately opens the model.
     */
    private val ACCELERATOR_ORDER: List<Accelerator> =
      listOf(Accelerator.GPU, Accelerator.CPU)
  }
}
