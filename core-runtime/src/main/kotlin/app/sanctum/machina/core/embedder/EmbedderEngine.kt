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
import java.io.File

/**
 * Phase 4 Task 4: thin contract that lets `EmbedderRegistry` (in `:app/engine/`) hold the
 * concrete runtime via a Hilt binding while unit tests inject a deterministic fake.
 *
 * The single production implementation is [EmbeddingGemmaEngine] (Task 1). All thread-safety
 * and lifecycle guarantees of the production engine flow up unchanged — the registry serialises
 * every transition under its own `encodeMutex` (Decision 2), so an implementation MAY rely on
 * not being called concurrently.
 */
interface EmbedderEngine {

  /**
   * Load the encoder + tokenizer onto native memory. Idempotent — repeat calls release the
   * prior instance before opening a new one. See [EmbeddingGemmaEngine.initialize] for the
   * full failure-recovery contract.
   */
  suspend fun initialize(
    context: Context,
    modelFile: File,
    tokenizerFile: File,
  ): Result<Unit>

  /**
   * Synchronous encode of a single text. Caller MUST hold the registry-side serialisation
   * mutex; the contract is identical to [EmbeddingGemmaEngine.encode].
   */
  fun encode(text: String, taskType: String): FloatArray

  /** Release native resources. Idempotent. Safe to call from any thread. */
  fun releaseEngine()
}
