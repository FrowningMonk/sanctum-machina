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

/**
 * Phase 4 Task 7 (Decision 2 + module-boundary rule): public surface of the embedding runtime
 * that consumers in `:core-runtime` (today: `IngestWorker` plumbing) can depend on without
 * pulling `:app/engine/EmbedderRegistry` into the module graph. `:core-runtime` cannot depend
 * on `:app`, so the registry implements this interface and Hilt `@Binds` wires it in `:app/di`.
 *
 * Methods mirror [app.sanctum.machina.engine.EmbedderRegistry] one-for-one — production
 * thread-safety, idle-teardown, and state-machine guarantees flow up through the bound impl
 * (Decision 2). Callers MUST treat [encode] / [encodeQuery] as suspending and never call them
 * from a Mutex they also hold themselves; the registry owns its own serialisation.
 */
interface Embedder {

  /**
   * Batch encode under [taskType] (e.g. `"retrieval_document"` for ingest,
   * `"retrieval_query"` for queries). Returns one `FloatArray` per input text, in input order.
   *
   * @throws app.sanctum.machina.engine.EmbedderNotReadyException when the registry is not in
   *   `Ready` state at call time (UI gates / IngestWorker pre-flight surface this typed exception).
   */
  suspend fun encode(texts: List<String>, taskType: String): List<FloatArray>

  /** Convenience for single-query encoding — equivalent to `encode(listOf(text), "retrieval_query")[0]`. */
  suspend fun encodeQuery(text: String): FloatArray
}
