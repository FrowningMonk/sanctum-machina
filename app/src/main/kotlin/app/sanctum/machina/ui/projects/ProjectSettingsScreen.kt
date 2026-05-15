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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.sanctum.machina.R

/**
 * Phase 4 Task 9 — three sliders (chunkSize / chunkOverlap / topK) per Decision 11. Apply
 * tier is enforced in [ProjectSettingsViewModel]; this screen only renders the dialog state.
 *
 * `embeddingDim` is intentionally **not** rendered — Decision 11 / US-AC13 #3 fixes it on
 * project creation. Slider ranges come from [ProjectSettingsViewModel] companion constants,
 * which alias [app.sanctum.machina.core.data.RagSliderBounds] (refined post-Task-1 spike).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectSettingsScreen(
  onBack: () -> Unit,
  viewModel: ProjectSettingsViewModel = hiltViewModel(),
) {
  val chunkSize by viewModel.chunkSize.collectAsState()
  val chunkOverlap by viewModel.chunkOverlap.collectAsState()
  val topK by viewModel.topK.collectAsState()
  val confirmState by viewModel.confirmDialogState.collectAsState()
  val effective by viewModel.effective.collectAsState()

  val snackbarHostState = remember { SnackbarHostState() }
  val reindexStartedMsg = stringResource(R.string.project_settings_reindex_started_snackbar)

  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        ProjectSettingsEvent.ReindexStarted -> snackbarHostState.showSnackbar(reindexStartedMsg)
      }
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.project_settings_title)) },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(innerPadding)
        .padding(horizontal = 20.dp, vertical = 16.dp)
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = stringResource(R.string.project_settings_rag_section),
        style = MaterialTheme.typography.titleMedium,
      )

      IntSliderField(
        label = stringResource(R.string.project_settings_chunk_size_label),
        value = chunkSize,
        range = ProjectSettingsViewModel.chunkSizeRange,
        step = ProjectSettingsViewModel.chunkSizeStep,
        onValueChange = viewModel::onChunkSizeChange,
      )

      IntSliderField(
        label = stringResource(R.string.project_settings_chunk_overlap_label),
        value = chunkOverlap,
        range = ProjectSettingsViewModel.chunkOverlapRange,
        step = ProjectSettingsViewModel.chunkOverlapStep,
        onValueChange = viewModel::onChunkOverlapChange,
      )

      IntSliderField(
        label = stringResource(R.string.project_settings_top_k_label),
        value = topK,
        range = ProjectSettingsViewModel.topKRange,
        step = ProjectSettingsViewModel.topKStep,
        onValueChange = viewModel::onTopKChange,
      )

      HorizontalDivider()

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        TextButton(onClick = { viewModel.resetToDefaults() }) {
          Text(stringResource(R.string.project_settings_reset_defaults_btn))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
          enabled = effective != null &&
            (chunkSize != effective!!.chunkSize || chunkOverlap != effective!!.chunkOverlap),
          onClick = { viewModel.applyHeavyChanges() },
        ) {
          Text(stringResource(R.string.project_settings_apply_btn))
        }
      }
    }
  }

  val showing = confirmState as? ReindexConfirmState.Showing
  if (showing != null) {
    AlertDialog(
      onDismissRequest = { viewModel.cancelReindex() },
      title = { Text(stringResource(R.string.project_settings_reindex_warning_title)) },
      text = {
        // Naive estimate: 1 minute per file. Refined post-Task-1 spike when real ingest
        // throughput on Honor 200 is measured (decisions.md TODO).
        val estimatedMinutes = showing.fileCount.coerceAtLeast(1)
        Text(
          stringResource(
            R.string.project_settings_reindex_warning_body,
            showing.fileCount,
            estimatedMinutes,
          ),
        )
      },
      confirmButton = {
        TextButton(onClick = { viewModel.confirmReindex() }) {
          Text(stringResource(R.string.project_settings_reindex_warning_confirm))
        }
      },
      dismissButton = {
        TextButton(onClick = { viewModel.cancelReindex() }) {
          Text(stringResource(R.string.project_settings_reindex_warning_cancel))
        }
      },
    )
  }
}

@Composable
private fun IntSliderField(
  label: String,
  value: Int,
  range: IntRange,
  step: Int,
  onValueChange: (Int) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(text = label, style = MaterialTheme.typography.bodyMedium)
      Text(
        text = stringResource(R.string.rag_value_int_format, value),
        style = MaterialTheme.typography.bodyMedium,
      )
    }
    val steps = ((range.last - range.first) / step) - 1
    Slider(
      value = value.toFloat(),
      onValueChange = { raw ->
        val snapped = (kotlin.math.round(raw / step) * step).toInt()
          .coerceIn(range.first, range.last)
        onValueChange(snapped)
      },
      valueRange = range.first.toFloat()..range.last.toFloat(),
      steps = steps.coerceAtLeast(0),
    )
  }
}
