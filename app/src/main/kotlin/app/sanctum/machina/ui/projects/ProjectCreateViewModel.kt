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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.data.RuntimeType
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.data.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase 4 Task 9 — drives [ProjectCreateScreen]. Validates the project name (US-AC1: non-empty
 * after trim), exposes the downloaded chat-model list for the optional default-model dropdown
 * (Decision 16 fallback chain), and emits a one-shot [created] event carrying the new
 * project id so the screen can navigate to `project/{id}` and pop the create route.
 *
 * Embedder rows (`runtimeType = LITERT_INTERPRETER`) are filtered out of [availableModels] —
 * the default-model for project chats is a Gemma 4 variant, not the EmbeddingGemma encoder.
 */
@HiltViewModel
class ProjectCreateViewModel
@Inject
constructor(
  private val projectRepository: ProjectRepository,
  registry: ModelRegistry,
) : ViewModel() {

  private val _name: MutableStateFlow<String> = MutableStateFlow("")
  val name: StateFlow<String> = _name.asStateFlow()

  private val _selectedModelId: MutableStateFlow<String?> = MutableStateFlow(null)
  val selectedModelId: StateFlow<String?> = _selectedModelId.asStateFlow()

  val availableModels: StateFlow<List<Model>> = registry.models
    .map { entries ->
      entries
        .filter { it.downloadStatus.status == ModelDownloadStatusType.SUCCEEDED }
        .map { it.model }
        // Embedder rows can be downloaded but should never appear as a chat default —
        // they have no chat-model surface and would crash the inference engine on send.
        .filter { it.runtimeType != RuntimeType.LITERT_INTERPRETER }
    }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = emptyList(),
    )

  private val _created: MutableSharedFlow<Long> = MutableSharedFlow(extraBufferCapacity = 1)

  /** One-shot navigation signal — emits the new project id when [create] succeeds. */
  val created: SharedFlow<Long> = _created.asSharedFlow()

  fun onNameChange(value: String) {
    _name.value = value
  }

  fun onModelSelect(modelId: String?) {
    _selectedModelId.value = modelId
  }

  /** Disabled-Create gate. Trim before checking — whitespace-only input is not a valid name. */
  val canCreate: StateFlow<Boolean> = _name
    .map { it.trim().isNotEmpty() }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = false,
    )

  /**
   * Insert the project and emit [created]. Trims the name; blank input is silently no-op (the
   * `canCreate` gate already disables the button — this guard is defence-in-depth against a
   * double-tap landing between the gate flip and the click handler).
   */
  fun create() {
    val trimmed = _name.value.trim()
    if (trimmed.isEmpty()) return
    val modelId = _selectedModelId.value
    viewModelScope.launch {
      val id = projectRepository.create(trimmed, modelId)
      _created.emit(id)
    }
  }
}
