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

import app.sanctum.machina.core.embedder.EmbedderEngine
import app.sanctum.machina.core.embedder.EmbeddingGemmaEngine
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Phase 4 Task 4: Hilt graph for embedding-runtime singletons.
 *
 * Why a `@Binds` instead of a `@Provides` factory: [EmbeddingGemmaEngine] is constructable with
 * no arguments (`@Inject constructor()`); native handles are only allocated inside
 * [EmbeddingGemmaEngine.initialize]. The Singleton lifetime is purely an object identity for
 * [EmbedderRegistry] to hold — instantiating the wrapper does not touch LiteRT.
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
}
