/*
 * Copyright 2026 Sanctum Machina authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package app.sanctum.machina.ui.projects

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.sanctum.machina.R
import app.sanctum.machina.data.model.ProjectFileEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 Task 23 — Compose UI smoke for the dynamic ingest progress chip.
 *
 * The `StatusChip` composable used to render a static «Indexing (N chunks)» text. After
 * Task 23 the chip prefers `ProjectFileEntity.statusMessage` (written live by
 * [app.sanctum.machina.core.worker.IngestWorker]) and falls back to the legacy chunk-only
 * string when the message is null. These two tests pin both branches so the indexing-UX
 * regression that previously hid mid-run progress can't return silently.
 */
@RunWith(AndroidJUnit4::class)
class StatusChipTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun indexingWithStatusMessage_rendersDynamicText() {
    val live = ProjectFileEntity(
      id = 1L,
      projectId = 1L,
      fileName = "doc.pdf",
      relativePath = "projects/1/docs/doc.pdf",
      contentHash = "h",
      status = "indexing",
      statusMessage = "Indexing · p. 5 · 12 chunks",
      chunkCount = 12,
      createdAt = 0L,
    )

    composeRule.setContent { StatusChip(file = live) }

    composeRule.onNodeWithText("Indexing · p. 5 · 12 chunks").assertIsDisplayed()
  }

  @Test
  fun indexingWithoutStatusMessage_fallsBackToSimple() {
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    val fallback = ProjectFileEntity(
      id = 2L,
      projectId = 1L,
      fileName = "doc.pdf",
      relativePath = "projects/1/docs/doc.pdf",
      contentHash = "h2",
      status = "indexing",
      statusMessage = null,
      chunkCount = 4,
      createdAt = 0L,
    )
    val expected = ctx.getString(R.string.project_file_status_indexing_simple, 4)

    composeRule.setContent { StatusChip(file = fallback) }

    composeRule.onNodeWithText(expected).assertIsDisplayed()
  }
}
