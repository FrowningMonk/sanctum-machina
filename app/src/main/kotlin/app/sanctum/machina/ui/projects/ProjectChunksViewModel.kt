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
import app.sanctum.machina.data.dao.ChunkInspectorRow
import app.sanctum.machina.data.dao.ProjectEmbeddingDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 4 Task 22 — drives [ProjectChunksScreen]. Snapshot-style load (not Flow-driven):
 * the chunk list can be large (Honor 200, typical project ~500 rows), and re-running the
 * `groupBy { fileName }` reducer on every DB-tick during ingest would be expensive without
 * adding diagnostic value. The screen is entered after ingest settles; [reload] is exposed
 * for a pull-to-refresh affordance if needed in the future.
 */
@HiltViewModel
open class ProjectChunksViewModel
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal constructor(
    private val projectId: Long,
    private val embeddingDao: ProjectEmbeddingDao,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        embeddingDao: ProjectEmbeddingDao,
    ) : this(
        projectId = requireNotNull(savedStateHandle.get<Long>(NAV_ARG_PROJECT_ID)) {
            "ProjectChunksViewModel requires '$NAV_ARG_PROJECT_ID' nav arg"
        },
        embeddingDao = embeddingDao,
        ioDispatcher = Dispatchers.IO,
    )

    private val _state: MutableStateFlow<ChunksUiState> = MutableStateFlow(ChunksUiState.Loading)
    val state: StateFlow<ChunksUiState> = _state.asStateFlow()

    init { reload() }

    fun reload() {
        // Flip to Loading synchronously so the spinner is observable the moment the user
        // triggers a refresh — without this, the state stays on the previous Loaded snapshot
        // until the coroutine actually resumes on Dispatchers.Main.
        _state.value = ChunksUiState.Loading
        viewModelScope.launch {
            val rows = withContext(ioDispatcher) { embeddingDao.chunksByProject(projectId) }
            _state.value = if (rows.isEmpty()) {
                ChunksUiState.Empty
            } else {
                // DAO already orders by file_name ASC then id ASC, so `groupBy` preserves a
                // deterministic file ordering in the resulting LinkedHashMap.
                ChunksUiState.Loaded(rows.groupBy { it.fileName })
            }
        }
    }
}

sealed class ChunksUiState {
    data object Loading : ChunksUiState()
    data object Empty : ChunksUiState()
    data class Loaded(val byFile: Map<String, List<ChunkInspectorRow>>) : ChunksUiState()
}
