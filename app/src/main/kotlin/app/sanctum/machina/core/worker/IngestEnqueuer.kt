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

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 Task 7: thin seam over `WorkManager.enqueueUniqueWork` for the ingest pipeline.
 *
 * Exists so [app.sanctum.machina.data.DefaultProjectRepository] can unit-test
 * `enqueueIngest` without pulling `WorkManager` into the test graph — the repository depends
 * on this `fun interface`, production binds [WorkManagerIngestEnqueuer] which wraps the real
 * `WorkManager.getInstance(context)` call.
 *
 * Decision 5: unique-work name is `"ingest-project-{projectId}"`, policy is
 * [ExistingWorkPolicy.APPEND_OR_REPLACE] — queues the new request behind any in-flight worker
 * for the same project (serialises ingest per US-AC6), with the `_OR_REPLACE` half kicking in
 * only when the queued worker entered a terminal failed state.
 */
fun interface IngestEnqueuer {
  fun enqueue(projectId: Long, fileId: Long, filePath: String)
}

/** Production binding — see [IngestEnqueuer] kdoc. */
@Singleton
class WorkManagerIngestEnqueuer @Inject constructor(
  @ApplicationContext private val context: Context,
) : IngestEnqueuer {

  override fun enqueue(projectId: Long, fileId: Long, filePath: String) {
    val input = workDataOf(
      KEY_PROJECT_ID to projectId,
      KEY_FILE_ID to fileId,
      KEY_FILE_PATH to filePath,
    )
    val request = OneTimeWorkRequest.Builder(IngestWorker::class.java)
      .setInputData(input)
      .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
      uniqueWorkNameFor(projectId),
      ExistingWorkPolicy.APPEND_OR_REPLACE,
      request,
    )
  }

  companion object {
    /** Public for [IngestCancelReceiver] which receives the same name in the cancel intent. */
    fun uniqueWorkNameFor(projectId: Long): String = "ingest-project-$projectId"
  }
}

/** WorkData keys shared between [WorkManagerIngestEnqueuer] and [IngestWorker]. */
internal const val KEY_PROJECT_ID = "projectId"
internal const val KEY_FILE_ID = "fileId"
internal const val KEY_FILE_PATH = "filePath"
