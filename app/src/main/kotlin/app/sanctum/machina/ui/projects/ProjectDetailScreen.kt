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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.sanctum.machina.R
import app.sanctum.machina.data.model.ProjectFileEntity

/**
 * Phase 4 Task 9 — central project surface. Renders:
 *  - TopAppBar with project name + overflow (Settings / Delete project).
 *  - Failed-docs banner (US-AC «banner при возврате»).
 *  - Two sections (Chats / Documents) with per-section FABs.
 *  - Documents list with status chips + per-file overflow.
 *  - Snackbar host wired to [ProjectDetailViewModel.events].
 *
 * The Documents FAB is **always clickable** — when disabled it raises a snackbar with a CTA
 * to Model Manager rather than swallowing the tap silently. Visually it surfaces with the
 * surfaceVariant color so the disabled state reads as such.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
  onBack: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenChat: (Long) -> Unit,
  onNewChat: (defaultModelId: String?) -> Unit,
  onOpenModelManager: () -> Unit,
  viewModel: ProjectDetailViewModel = hiltViewModel(),
) {
  val project by viewModel.project.collectAsState()
  val files by viewModel.files.collectAsState()
  val fabEnabled by viewModel.fabEnabled.collectAsState()
  val failedBanner by viewModel.failedDocsBanner.collectAsState()

  val snackbarHostState = remember { SnackbarHostState() }
  var showDeleteDialog by remember { mutableStateOf(false) }
  var menuExpanded by remember { mutableStateOf(false) }

  val embedderRequiredMsg = stringResource(R.string.project_detail_embedder_not_downloaded_snackbar)
  val embedderRequiredCta = stringResource(R.string.project_detail_embedder_not_downloaded_cta)
  val duplicateMsg = stringResource(R.string.project_detail_duplicate_document_toast)
  val importFailedMsg = stringResource(R.string.project_detail_import_failed_toast)
  val tooLargeMsg = stringResource(R.string.project_detail_too_large_toast)

  // Wire VM one-shot events to snackbar / navigation actions.
  LaunchedEffect(viewModel) {
    viewModel.events.collect { event ->
      when (event) {
        ProjectDetailEvent.DuplicateDocument ->
          snackbarHostState.showSnackbar(duplicateMsg)

        ProjectDetailEvent.EmbedderRequired -> {
          val result = snackbarHostState.showSnackbar(
            message = embedderRequiredMsg,
            actionLabel = embedderRequiredCta,
          )
          if (result == SnackbarResult.ActionPerformed) onOpenModelManager()
        }

        ProjectDetailEvent.NavigateToModelManager -> onOpenModelManager()
        ProjectDetailEvent.ProjectDeleted -> onBack()
        ProjectDetailEvent.DocumentImportFailed ->
          snackbarHostState.showSnackbar(importFailedMsg)
        ProjectDetailEvent.DocumentTooLarge ->
          snackbarHostState.showSnackbar(tooLargeMsg)
      }
    }
  }

  val safLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments(),
  ) { uris -> if (uris.isNotEmpty()) viewModel.addDocuments(uris) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(project?.name ?: "") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
          }
        },
        actions = {
          IconButton(onClick = { menuExpanded = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = null)
          }
          DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
          ) {
            DropdownMenuItem(
              text = { Text(stringResource(R.string.project_detail_overflow_settings)) },
              onClick = {
                menuExpanded = false
                onOpenSettings()
              },
            )
            DropdownMenuItem(
              text = { Text(stringResource(R.string.project_detail_overflow_delete)) },
              onClick = {
                menuExpanded = false
                showDeleteDialog = true
              },
            )
          }
        },
      )
    },
    snackbarHost = { SnackbarHost(snackbarHostState) },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (failedBanner.isNotEmpty()) {
        item {
          FailedDocsBanner(
            rows = failedBanner,
            onDismiss = { viewModel.dismissFailedDocsBanner() },
            onReindexAll = { failedBanner.forEach { viewModel.reindex(it.id) } },
          )
        }
      }

      item { SectionHeader(text = stringResource(R.string.project_detail_chats_section)) }
      item {
        // Chats list is wired in Task 11 (project-scoped chats projection). For T9 we render
        // an empty-state placeholder so the section is structurally present and the "+ Новый
        // чат" FAB is reachable.
        Text(
          text = stringResource(R.string.project_detail_no_chats),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 4.dp),
        )
      }
      item {
        ExtendedFloatingActionButton(
          text = { Text(stringResource(R.string.project_detail_new_chat_fab)) },
          icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
          onClick = { onNewChat(project?.defaultModelId) },
          modifier = Modifier.padding(top = 4.dp),
        )
      }

      item { SectionHeader(text = stringResource(R.string.project_detail_documents_section)) }

      if (files.isEmpty()) {
        item {
          Text(
            text = stringResource(R.string.project_detail_no_documents),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
          )
        }
      } else {
        items(files, key = { it.id }) { file ->
          DocumentRow(
            file = file,
            onReindex = { viewModel.reindex(file.id) },
            onDelete = { viewModel.deleteFile(file.id) },
          )
        }
      }
      item {
        DocumentsFab(
          enabled = fabEnabled,
          onTap = {
            if (fabEnabled) {
              safLauncher.launch(arrayOf("application/pdf"))
            } else {
              viewModel.onFabTapWhileDisabled()
            }
          },
        )
      }
    }
  }

  if (showDeleteDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteDialog = false },
      title = { Text(stringResource(R.string.project_detail_delete_confirm_title)) },
      text = { Text(stringResource(R.string.project_detail_delete_confirm_body)) },
      confirmButton = {
        TextButton(onClick = {
          showDeleteDialog = false
          viewModel.deleteProject()
        }) {
          Text(stringResource(R.string.project_detail_delete_confirm_yes))
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteDialog = false }) {
          Text(stringResource(R.string.project_detail_delete_confirm_no))
        }
      },
    )
  }

}

@Composable
private fun SectionHeader(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(top = 8.dp),
  )
}

@Composable
private fun FailedDocsBanner(
  rows: List<ProjectFileEntity>,
  onDismiss: () -> Unit,
  onReindexAll: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = stringResource(R.string.project_detail_failed_docs_banner_title),
        style = MaterialTheme.typography.titleSmall,
      )
      for (row in rows) {
        Text(
          text = row.fileName,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
      Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onDismiss) {
          Text(stringResource(R.string.project_detail_failed_docs_dismiss_action))
        }
        TextButton(onClick = onReindexAll) {
          Text(stringResource(R.string.project_detail_failed_docs_reindex_action))
        }
      }
    }
  }
}

@Composable
private fun DocumentsFab(enabled: Boolean, onTap: () -> Unit) {
  // FloatingActionButton(enabled=false) swallows the tap entirely — we want the tap to fire
  // even when disabled so the VM can raise the embedder-required snackbar. Surface the
  // disabled state via the container color instead.
  val container =
    if (enabled) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
  FloatingActionButton(
    onClick = onTap,
    containerColor = container,
    modifier = Modifier.padding(top = 4.dp),
  ) {
    Icon(
      imageVector = Icons.Outlined.Add,
      contentDescription = stringResource(R.string.project_detail_add_document_fab),
    )
  }
}

@Composable
private fun DocumentRow(
  file: ProjectFileEntity,
  onReindex: () -> Unit,
  onDelete: () -> Unit,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Icon(
      imageVector = Icons.Outlined.Description,
      contentDescription = null,
      modifier = Modifier.size(20.dp),
    )
    Column(modifier = Modifier.weight(1f)) {
      Text(file.fileName, style = MaterialTheme.typography.bodyMedium)
      StatusChip(file = file)
    }
    Box {
      IconButton(onClick = { menuExpanded = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = null)
      }
      DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
        DropdownMenuItem(
          text = { Text(stringResource(R.string.project_detail_file_overflow_reindex)) },
          onClick = {
            menuExpanded = false
            onReindex()
          },
        )
        DropdownMenuItem(
          text = { Text(stringResource(R.string.project_detail_file_overflow_delete)) },
          onClick = {
            menuExpanded = false
            onDelete()
          },
        )
      }
    }
  }
}

@Composable
private fun StatusChip(file: ProjectFileEntity) {
  // While IngestWorker updates `chunk_count` mid-run, there is no companion `total_chunks`
  // column yet — so the chip shows the running count alone (not N/M). Code-reviewer round-1
  // major: the previous formatter rendered «N / N» which read as «complete» before completion.
  // TODO(post-T9): add `total_chunks` column + matching migration; restore the two-int format.
  val (label, color) = when (file.status) {
    PROJECT_FILE_STATUS_READY ->
      stringResource(R.string.project_file_status_ready) to MaterialTheme.colorScheme.primary
    PROJECT_FILE_STATUS_INDEXING ->
      stringResource(R.string.project_file_status_indexing_simple, file.chunkCount ?: 0) to
        MaterialTheme.colorScheme.tertiary
    PROJECT_FILE_STATUS_FAILED ->
      stringResource(R.string.project_file_status_failed) to MaterialTheme.colorScheme.error
    else ->
      stringResource(R.string.project_file_status_pending) to
        MaterialTheme.colorScheme.onSurfaceVariant
  }
  Box(
    modifier = Modifier
      .padding(top = 2.dp)
      .background(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(6.dp),
      )
      .padding(horizontal = 6.dp, vertical = 2.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = color,
    )
  }
}
