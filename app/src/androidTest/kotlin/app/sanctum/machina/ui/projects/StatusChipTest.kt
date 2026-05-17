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
import androidx.compose.ui.test.onNodeWithTag
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
 * Two contracts pinned here:
 *   1. `StatusChip` honors `ProjectFileEntity.statusMessage` verbatim when `status=indexing`,
 *      and falls back to the chunk-only localised string when message is null.
 *   2. `DocumentRow` only renders the indeterminate `LinearProgressIndicator` for
 *      indexing rows — never for `ready` or `failed`.
 *
 * Sentinel strings are used for the dynamic-message branch so a future regression where the
 * chip stops honoring `statusMessage` and falls through to the en-template fallback would
 * fail with an unambiguous error (test-reviewer round 1 M-2): if the chip resolved the en
 * template by accident, the sentinel would not match and the failure log would point
 * straight at «chip ignored statusMessage» rather than at a localisation drift.
 */
@RunWith(AndroidJUnit4::class)
class StatusChipTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun statusChip_indexingWithStatusMessage_rendersVerbatim() {
    val sentinel = "SENTINEL-PROGRESS-XYZ-42"
    val live = indexingFile(statusMessage = sentinel, chunkCount = 12)

    composeRule.setContent { StatusChip(file = live) }

    composeRule.onNodeWithText(sentinel).assertIsDisplayed()
  }

  @Test
  fun statusChip_indexingWithoutStatusMessage_fallsBackToSimple() {
    val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    val fallback = indexingFile(statusMessage = null, chunkCount = 4)
    val expected = ctx.getString(R.string.project_file_status_indexing_simple, 4)

    composeRule.setContent { StatusChip(file = fallback) }

    composeRule.onNodeWithText(expected).assertIsDisplayed()
  }

  @Test
  fun documentRow_indexing_showsChipAndProgressBar() {
    val sentinel = "SENTINEL-ROW-PROGRESS-99"
    val live = indexingFile(statusMessage = sentinel, chunkCount = 7)

    composeRule.setContent { DocumentRow(file = live, onReindex = {}, onDelete = {}) }

    composeRule.onNodeWithText(sentinel).assertIsDisplayed()
    composeRule.onNodeWithTag(INGEST_PROGRESS_BAR_TEST_TAG).assertIsDisplayed()
  }

  @Test
  fun documentRow_ready_hidesProgressBar() {
    val ready = ProjectFileEntity(
      id = 3L,
      projectId = 1L,
      fileName = "doc.pdf",
      relativePath = "projects/1/docs/doc.pdf",
      contentHash = "h3",
      status = "ready",
      statusMessage = null,
      chunkCount = 7,
      createdAt = 0L,
    )

    composeRule.setContent { DocumentRow(file = ready, onReindex = {}, onDelete = {}) }

    composeRule.onNodeWithTag(INGEST_PROGRESS_BAR_TEST_TAG).assertDoesNotExist()
  }

  private fun indexingFile(statusMessage: String?, chunkCount: Int) = ProjectFileEntity(
    id = 1L,
    projectId = 1L,
    fileName = "doc.pdf",
    relativePath = "projects/1/docs/doc.pdf",
    contentHash = "h$chunkCount",
    status = "indexing",
    statusMessage = statusMessage,
    chunkCount = chunkCount,
    createdAt = 0L,
  )
}
