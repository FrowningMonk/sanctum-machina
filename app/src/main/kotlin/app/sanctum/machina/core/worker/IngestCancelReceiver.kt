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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/**
 * Phase 4 Task 7: receives the "Cancel" notification action posted by [IngestWorker] and asks
 * [WorkManager] to cancel the unique-work chain.
 *
 * Manifest: `android:exported="false"` — the broadcast is only ever sent from this app's own
 * [android.app.PendingIntent], and exposing it would let any installed app trigger an
 * arbitrary-unique-work cancel by guessing the project id.
 *
 * The intent payload [EXTRA_WORK_NAME] is computed by [WorkManagerIngestEnqueuer.uniqueWorkNameFor]
 * — single source of truth so a future rename of the unique-work scheme cannot leave the cancel
 * path silently broken. Defence-in-depth: the value is also pattern-matched against
 * [WORK_NAME_PATTERN] before being forwarded to `WorkManager`, so a future in-app caller bug
 * cannot leverage this receiver into cancelling unrelated unique-work names (e.g. the
 * DownloadWorker chain) — security-auditor-1 medium.
 */
class IngestCancelReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val workName = intent.getStringExtra(EXTRA_WORK_NAME) ?: return
    if (!WORK_NAME_PATTERN.matches(workName)) return
    WorkManager.getInstance(context).cancelUniqueWork(workName)
  }

  companion object {
    const val EXTRA_WORK_NAME: String = "workName"

    /** Accepts only the `ingest-project-{positiveLong}` shape that [WorkManagerIngestEnqueuer] emits. */
    val WORK_NAME_PATTERN: Regex = Regex("^ingest-project-\\d+$")
  }
}
