/*
 * Copyright 2026 Sanctum Machina authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package app.sanctum.machina.di

import app.sanctum.machina.core.embedder.Embedder
import app.sanctum.machina.core.embedder.EmbedderEngine
import app.sanctum.machina.core.embedder.EmbeddingGemmaEngine
import app.sanctum.machina.core.worker.IngestEnqueuer
import app.sanctum.machina.core.worker.WorkManagerIngestEnqueuer
import app.sanctum.machina.engine.EmbedderGate
import app.sanctum.machina.engine.EmbedderRegistry
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 4 Task 4 / Task 7: Hilt graph for embedding-runtime singletons.
 *
 * Why a `@Binds` instead of a `@Provides` factory: [EmbeddingGemmaEngine] is constructable with
 * no arguments (`@Inject constructor()`); native handles are only allocated inside
 * [EmbeddingGemmaEngine.initialize]. The Singleton lifetime is purely an object identity for
 * [EmbedderRegistry] to hold — instantiating the wrapper does not touch LiteRT.
 *
 * Task 7 adds the [Embedder] interface binding so `:core-runtime` consumers (today the worker
 * `EntryPoint`-style DI) can request an `Embedder` without referencing `:app/engine/`.
 *
 * `EmbedderRegistry` itself carries its own `@Inject constructor`, so no explicit `@Provides`
 * is needed for it.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class EngineModule {

  @Binds
  @Singleton
  abstract fun bindEmbedderEngine(impl: EmbeddingGemmaEngine): EmbedderEngine

  @Binds
  @Singleton
  abstract fun bindEmbedder(impl: EmbedderRegistry): Embedder

  /**
   * Task 9: narrow UI-side surface (state + warmup) consumed by `ProjectDetailViewModel`.
   * Bound to the same singleton — `Embedder` (encode) and `EmbedderGate` (state) are different
   * facets of one runtime, not two engines.
   */
  @Binds
  @Singleton
  abstract fun bindEmbedderGate(impl: EmbedderRegistry): EmbedderGate

  /**
   * Task 7: production [IngestEnqueuer] wraps `WorkManager.enqueueUniqueWork`. The
   * `@VisibleForTesting` constructor of `DefaultProjectRepository` accepts a no-op default so
   * unit tests do not have to construct WorkManager — production wiring goes through this bind.
   */
  @Binds
  @Singleton
  abstract fun bindIngestEnqueuer(impl: WorkManagerIngestEnqueuer): IngestEnqueuer
}
