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
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Minimal `CoroutineWorker` used by [IngestCancelReceiverTest] to populate a unique-work
 * record without dragging in IngestWorker's Hilt graph. Returns success immediately on the
 * SynchronousExecutor, or stays ENQUEUED if the test cancels before it runs.
 */
class NoopTestWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {
  override suspend fun doWork(): Result = Result.success()
}
