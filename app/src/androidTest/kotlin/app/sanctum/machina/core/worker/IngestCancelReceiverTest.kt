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
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

/**
 * Phase 4 Task 7 — verifies the [IngestCancelReceiver] cancel forwarding and its defence-in-
 * depth `WORK_NAME_PATTERN` validation.
 *
 * Uses [WorkManagerTestInitHelper] to install a synchronous test WorkManager; the receiver
 * itself is instantiated directly (not delivered through the system broadcast bus) because
 * the validator only consumes the intent extras — no need to drive the framework.
 */
@RunWith(AndroidJUnit4::class)
class IngestCancelReceiverTest {

  private lateinit var context: Context
  private lateinit var workManager: WorkManager

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    val config = Configuration.Builder()
      .setExecutor(SynchronousExecutor())
      .build()
    WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
    workManager = WorkManager.getInstance(context)
  }

  @Test
  fun onReceive_validWorkName_cancelsUniqueWork() = runBlocking {
    val workName = WorkManagerIngestEnqueuer.uniqueWorkNameFor(projectId = 42L)
    val request = OneTimeWorkRequest.Builder(NoopTestWorker::class.java).build()
    workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request).await()

    val intent = Intent().putExtra(IngestCancelReceiver.EXTRA_WORK_NAME, workName)
    IngestCancelReceiver().onReceive(context, intent)

    val info = workManager.getWorkInfosForUniqueWorkFlow(workName).first().firstOrNull()
    assertNotNull("work record must exist after enqueue", info)
    assertEquals(WorkInfo.State.CANCELLED, info!!.state)
  }

  @Test
  fun onReceive_missingWorkName_isNoOp() {
    // Should not crash, should not affect any unique-work record.
    val intent = Intent() // no extra
    IngestCancelReceiver().onReceive(context, intent)
    // No assertion beyond "did not throw" — the receiver returns early.
  }

  @Test
  fun onReceive_workNameOutsideIngestNamespace_isRejected() = runBlocking {
    val workName = WorkManagerIngestEnqueuer.uniqueWorkNameFor(projectId = 7L)
    val request = OneTimeWorkRequest.Builder(NoopTestWorker::class.java).build()
    workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request).await()

    // Try to cancel via the receiver with an unrelated work name that does not match
    // the ingest-project pattern. The receiver MUST refuse (defence-in-depth) so a future
    // in-app caller bug cannot use this receiver to cancel arbitrary unique-work chains
    // such as the model-download pipeline.
    val intent = Intent().putExtra(IngestCancelReceiver.EXTRA_WORK_NAME, "download-model-abc")
    IngestCancelReceiver().onReceive(context, intent)

    val info = workManager.getWorkInfosForUniqueWorkFlow(workName).first().firstOrNull()
    // SynchronousExecutor may have already run the NoopTestWorker to SUCCEEDED by the time
    // we sample state — any non-CANCELLED state confirms the rejection held; the receiver
    // did NOT flip the worker to CANCELLED.
    assertNotNull(info)
    assert(info!!.state != WorkInfo.State.CANCELLED) {
      "ingest-project-7 must not be CANCELLED by an out-of-namespace cancel attempt, was ${info.state}"
    }
  }

  @Test
  fun workNamePattern_acceptsExpectedShapesAndRejectsNeighbours() {
    // Positive cases.
    listOf("ingest-project-1", "ingest-project-42", "ingest-project-9999999999").forEach {
      assert(IngestCancelReceiver.WORK_NAME_PATTERN.matches(it)) { "expected match: $it" }
    }
    // Negative cases — neighbour strings that should never be accepted.
    listOf(
      "",
      "ingest-project-",
      "ingest-project-abc",
      "ingest-project-1.0",
      "ingest-project-1 ",
      " ingest-project-1",
      "INGEST-PROJECT-1",
      "download-model-1",
    ).forEach {
      assert(!IngestCancelReceiver.WORK_NAME_PATTERN.matches(it)) { "expected reject: $it" }
    }
  }
}
