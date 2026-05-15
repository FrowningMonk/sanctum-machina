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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.sanctum.machina.R

/**
 * Phase 4 Task 9 — minimal screen that creates a new project row and emits a one-shot
 * navigation event once the row id is known.
 *
 * Navigation contract:
 *  - [onCreated] receives the new project id; caller is expected to navigate to
 *    `project/{id}` with `popUpTo("project/0/create") { inclusive = true }`.
 *  - [onCancel] pops without inserting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectCreateScreen(
  onCreated: (Long) -> Unit,
  onCancel: () -> Unit,
  viewModel: ProjectCreateViewModel = hiltViewModel(),
) {
  val name by viewModel.name.collectAsState()
  val selectedModelId by viewModel.selectedModelId.collectAsState()
  val availableModels by viewModel.availableModels.collectAsState()
  val canCreate by viewModel.canCreate.collectAsState()

  LaunchedEffect(viewModel) {
    viewModel.created.collect { id -> onCreated(id) }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(R.string.project_create_title)) },
        navigationIcon = {
          IconButton(onClick = onCancel) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
          }
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(innerPadding)
        .padding(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      OutlinedTextField(
        value = name,
        onValueChange = viewModel::onNameChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.project_create_name_label)) },
        singleLine = true,
      )

      DefaultModelDropdown(
        models = availableModels,
        selectedModelId = selectedModelId,
        onModelSelect = viewModel::onModelSelect,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
      ) {
        TextButton(onClick = onCancel) {
          Text(stringResource(R.string.project_create_cancel_btn))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
          enabled = canCreate,
          onClick = { viewModel.create() },
        ) {
          Text(stringResource(R.string.project_create_confirm_btn))
        }
      }
    }
  }
}

@Composable
private fun DefaultModelDropdown(
  models: List<app.sanctum.machina.core.data.Model>,
  selectedModelId: String?,
  onModelSelect: (String?) -> Unit,
) {
  var expanded by remember { mutableStateOf(false) }
  val unsetLabel = stringResource(R.string.project_create_default_model_unset)
  val display = remember(selectedModelId, models) {
    if (selectedModelId == null) unsetLabel
    else models.firstOrNull { it.modelId == selectedModelId }?.name ?: selectedModelId
  }

  // Plain `DropdownMenu` anchored to an OutlinedTextField. Avoids `ExposedDropdownMenu`
  // (not available in the bundled M3 version) while keeping the read-only-textfield UX.
  Box(modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
      value = display,
      onValueChange = {},
      readOnly = true,
      modifier = Modifier.fillMaxWidth(),
      label = { Text(stringResource(R.string.project_create_default_model_label)) },
      trailingIcon = {
        IconButton(onClick = { expanded = true }) {
          Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
      },
    )
    DropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      DropdownMenuItem(
        text = { Text(unsetLabel) },
        onClick = {
          onModelSelect(null)
          expanded = false
        },
      )
      for (m in models) {
        DropdownMenuItem(
          text = { Text(m.name) },
          onClick = {
            onModelSelect(m.modelId)
            expanded = false
          },
        )
      }
    }
  }
}
