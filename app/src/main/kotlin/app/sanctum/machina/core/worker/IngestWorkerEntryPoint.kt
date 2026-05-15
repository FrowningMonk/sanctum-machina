/*
 * Copyright 2026 Sanctum Machina authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package app.sanctum.machina.core.worker

import app.sanctum.machina.core.embedder.Embedder
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.data.dao.ProjectEmbeddingDao
import app.sanctum.machina.data.dao.ProjectFileDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Phase 4 Task 7: Hilt [EntryPoint] used by [IngestWorker] to fetch its dependencies from the
 * application's singleton graph.
 *
 * `IngestWorker` is a plain `CoroutineWorker` (mirroring `DownloadWorker`'s shape — no
 * `@HiltWorker` / `@AssistedInject`) because the existing project does not configure
 * `HiltWorkerFactory`. The worker calls
 * `EntryPointAccessors.fromApplication(context, IngestWorkerEntryPoint::class.java)` once at
 * the top of `doWork()` to obtain every collaborator it needs.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface IngestWorkerEntryPoint {
  fun projectRepository(): ProjectRepository
  fun embedder(): Embedder
  fun projectFileDao(): ProjectFileDao
  fun projectEmbeddingDao(): ProjectEmbeddingDao
  fun errorLog(): ErrorLog
}
