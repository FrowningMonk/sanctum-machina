/*
 * Copyright 2026 Sanctum Machina authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package app.sanctum.machina.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow surface that Phase 4 Task 9 UI consumes from [EmbedderRegistry]: the lifecycle state
 * stream that drives the FAB-disabled gate on `ProjectDetailScreen` (US-6 / AC-4) and the
 * `warmup()` trigger fired on entry per Decision 2.
 *
 * Exists so `ProjectDetailViewModel` (and future T11 `ChatViewModel` RAG additions) can be unit-
 * tested with a hand-rolled fake instead of pulling the full `EmbedderRegistry` constructor
 * (model file, engine dispatcher, idle-teardown loop) into the test graph. [EmbedderRegistry]
 * implements this directly; production Hilt wiring binds the interface to the singleton.
 */
interface EmbedderGate {
  /** Hot stream of the embedder runtime's state. Mirrors `EmbedderRegistry.state`. */
  val state: StateFlow<EmbedderState>

  /**
   * Idempotent warmup — see `EmbedderRegistry.warmup` kdoc for serialisation semantics.
   * UI call-sites fire this on entry; concurrent calls collapse to a single initialise.
   */
  suspend fun warmup()
}
