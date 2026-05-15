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

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.core.data.RagSliderBounds
import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.data.RagConfig
import app.sanctum.machina.data.dao.ProjectFileDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 4 Task 9 — drives [ProjectSettingsScreen]. Three sliders editable; `embeddingDim`
 * is **not** exposed per Decision 11 (immutable after project creation).
 *
 * Two apply tiers (US-spec § Тиры / Decision 11):
 *  - **Light** — `topK`: applies immediately on slider release via [onTopKChange]; no dialog.
 *  - **Reindex-required** — `chunkSize` / `chunkOverlap`: slider drags only update the local
 *    state; tapping «Применить» raises [confirmDialogState] = [ReindexConfirmState.Showing]
 *    with the count of files in the project, the user confirms, and
 *    [confirmReindex] flips every file back to `pending` + cascade-deletes embeddings + enqueues
 *    a fresh ingest per file.
 *
 * Cross-knob constraint `chunkOverlap < chunkSize` is enforced inline ([onChunkOverlapChange]
 * clamps to `chunkSize - 1`); the slider bounds also constrain the upper edge per
 * [RagSliderBounds.chunkOverlapRange].
 *
 * Slider ranges are imported from [RagSliderBounds] — calibrated post-Task-1 spike (see
 * `RagSliderBounds` KDoc for per-knob justification). This VM does not duplicate the constants
 * and does not invent ranges.
 */
@HiltViewModel
open class ProjectSettingsViewModel
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal constructor(
  private val projectId: Long,
  private val projectRepository: ProjectRepository,
  private val projectFileDao: ProjectFileDao,
  private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

  @Inject
  constructor(
    savedStateHandle: SavedStateHandle,
    projectRepository: ProjectRepository,
    projectFileDao: ProjectFileDao,
  ) : this(
    projectId = requireNotNull(savedStateHandle.get<Long>(NAV_ARG_PROJECT_ID)) {
      "ProjectSettingsViewModel requires '$NAV_ARG_PROJECT_ID' nav arg"
    },
    projectRepository = projectRepository,
    projectFileDao = projectFileDao,
    ioDispatcher = Dispatchers.IO,
  )

  // Effective settings at screen entry — used as the slider baseline AND as the "is dirty"
  // reference for the Apply button gate. Replaced after every successful apply or reset.
  private val _effective: MutableStateFlow<RagConfig?> = MutableStateFlow(null)
  val effective: StateFlow<RagConfig?> = _effective.asStateFlow()

  private val _chunkSize: MutableStateFlow<Int> = MutableStateFlow(0)
  val chunkSize: StateFlow<Int> = _chunkSize.asStateFlow()

  private val _chunkOverlap: MutableStateFlow<Int> = MutableStateFlow(0)
  val chunkOverlap: StateFlow<Int> = _chunkOverlap.asStateFlow()

  private val _topK: MutableStateFlow<Int> = MutableStateFlow(0)
  val topK: StateFlow<Int> = _topK.asStateFlow()

  private val _confirmDialogState: MutableStateFlow<ReindexConfirmState> =
    MutableStateFlow(ReindexConfirmState.Hidden)
  val confirmDialogState: StateFlow<ReindexConfirmState> = _confirmDialogState.asStateFlow()

  /**
   * Apply-button gate: true iff the heavy-tier sliders diverge from the last applied effective
   * config. Light tier (`topK`) is applied immediately, so dirty-ness is tracked only for the
   * reindex-required pair (code-reviewer round-1 major: drop `!!` from Compose lambda).
   */
  val isDirty: StateFlow<Boolean> =
    combine(_effective, _chunkSize, _chunkOverlap) { effective, size, overlap ->
      effective != null && (size != effective.chunkSize || overlap != effective.chunkOverlap)
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = false,
    )

  /** Number of files currently in the project — drives the warning dialog body «{N} файлов». */
  val fileCount: StateFlow<Int> = projectRepository.observeFiles(projectId)
    .map { it.size }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = 0,
    )

  private val _events: MutableSharedFlow<ProjectSettingsEvent> =
    MutableSharedFlow(extraBufferCapacity = 4)
  val events: SharedFlow<ProjectSettingsEvent> = _events.asSharedFlow()

  init {
    viewModelScope.launch {
      val current = withContext(ioDispatcher) {
        projectRepository.getEffectiveRagSettings(projectId)
      }
      _effective.value = current
      _chunkSize.value = current.chunkSize
      _chunkOverlap.value = current.chunkOverlap
      _topK.value = current.topK
    }
  }

  /** Slider drag — local-only; clamp against slider bounds (defence-in-depth). */
  fun onChunkSizeChange(value: Int) {
    val clamped = value.coerceIn(
      RagSliderBounds.chunkSizeRange.first,
      RagSliderBounds.chunkSizeRange.last,
    )
    _chunkSize.value = clamped
    // Re-clamp overlap against the new ceiling so the cross-knob invariant holds when the
    // user drags chunkSize down past the current overlap.
    if (_chunkOverlap.value >= clamped) {
      _chunkOverlap.value = (clamped - 1).coerceAtLeast(0)
    }
  }

  /** Slider drag — clamps against `chunkSize - 1` per Decision 11 / US-AC13. */
  fun onChunkOverlapChange(value: Int) {
    val sliderClamped = value.coerceIn(
      RagSliderBounds.chunkOverlapRange.first,
      RagSliderBounds.chunkOverlapRange.last,
    )
    val crossKnobMax = (_chunkSize.value - 1).coerceAtLeast(0)
    _chunkOverlap.value = sliderClamped.coerceAtMost(crossKnobMax)
  }

  /**
   * Light apply — fires immediately without a confirmation dialog (Decision 11 light tier).
   * Persists the full RagConfig (Repository surface is whole-config replace; we read effective
   * for the other two fields to avoid a stale-overlay risk).
   */
  fun onTopKChange(value: Int) {
    val clamped = value.coerceIn(RagSliderBounds.topKRange.first, RagSliderBounds.topKRange.last)
    _topK.value = clamped
    val baseline = _effective.value ?: return
    if (clamped == baseline.topK) return
    val next = baseline.copy(topK = clamped)
    viewModelScope.launch {
      withContext(ioDispatcher) { projectRepository.updateRagOverrides(projectId, next) }
      _effective.value = next
    }
  }

  /**
   * Apply for the reindex-required tier. Opens the confirm dialog only if one of the heavy
   * knobs actually changed against [effective]; otherwise no-op (avoids a "0 files / 0 minutes"
   * dialog firing on identity-apply).
   */
  fun applyHeavyChanges() {
    val baseline = _effective.value ?: return
    if (_chunkSize.value == baseline.chunkSize && _chunkOverlap.value == baseline.chunkOverlap) {
      return
    }
    _confirmDialogState.value = ReindexConfirmState.Showing(
      pendingChunkSize = _chunkSize.value,
      pendingChunkOverlap = _chunkOverlap.value,
      fileCount = fileCount.value,
    )
  }

  fun confirmReindex() {
    val state = _confirmDialogState.value as? ReindexConfirmState.Showing ?: return
    _confirmDialogState.value = ReindexConfirmState.Hidden
    val baseline = _effective.value ?: return
    val merged = baseline.copy(
      chunkSize = state.pendingChunkSize,
      chunkOverlap = state.pendingChunkOverlap,
    )
    viewModelScope.launch {
      withContext(ioDispatcher) {
        projectRepository.applyReindexRequired(
          projectId = projectId,
          chunkSize = state.pendingChunkSize,
          chunkOverlap = state.pendingChunkOverlap,
        )
      }
      _effective.value = merged
      _events.emit(ProjectSettingsEvent.ReindexStarted)
    }
  }

  /** Cancel — revert sliders to the last applied effective values. */
  fun cancelReindex() {
    _confirmDialogState.value = ReindexConfirmState.Hidden
    val baseline = _effective.value ?: return
    _chunkSize.value = baseline.chunkSize
    _chunkOverlap.value = baseline.chunkOverlap
  }

  /**
   * «Сбросить к умолчаниям» — clear `rag_overrides_json`. The next read from
   * [ProjectRepository.getEffectiveRagSettings] returns the allowlist defaults (Decision 12
   * baseline: 800 / 100 / 4 / 768 — see DEFAULT_RAG_CONFIG in DefaultProjectRepository).
   *
   * Since the existing files were embedded with the prior settings, this is a *light reset* —
   * the per-knob nuance is: dropping the override flips topK back to default for the next send
   * (no chat-history impact), but chunkSize / chunkOverlap remain effectively-old until a
   * subsequent reindex. Surfacing this to the user is an open UX question (TODO: T11/post-T9
   * polish); for MVP we keep the reset light to match the User-Spec acceptance line.
   */
  fun resetToDefaults() {
    viewModelScope.launch {
      withContext(ioDispatcher) { projectRepository.updateRagOverrides(projectId, null) }
      val refreshed = withContext(ioDispatcher) {
        projectRepository.getEffectiveRagSettings(projectId)
      }
      _effective.value = refreshed
      _chunkSize.value = refreshed.chunkSize
      _chunkOverlap.value = refreshed.chunkOverlap
      _topK.value = refreshed.topK
    }
  }

  companion object {
    val chunkSizeRange: IntRange get() = RagSliderBounds.chunkSizeRange
    val chunkOverlapRange: IntRange get() = RagSliderBounds.chunkOverlapRange
    val topKRange: IntRange get() = RagSliderBounds.topKRange
    const val chunkSizeStep: Int = RagSliderBounds.chunkSizeStep
    const val chunkOverlapStep: Int = RagSliderBounds.chunkOverlapStep
    const val topKStep: Int = RagSliderBounds.topKStep
  }
}

/**
 * Reindex confirm dialog state machine. [Showing] carries the pending slider values + file
 * count so the dialog body renders without reading the latest StateFlow values inline.
 */
sealed class ReindexConfirmState {
  data object Hidden : ReindexConfirmState()
  data class Showing(
    val pendingChunkSize: Int,
    val pendingChunkOverlap: Int,
    val fileCount: Int,
  ) : ReindexConfirmState()
}

sealed class ProjectSettingsEvent {
  data object ReindexStarted : ProjectSettingsEvent()
}
