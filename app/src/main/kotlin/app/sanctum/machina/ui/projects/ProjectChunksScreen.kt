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

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import app.sanctum.machina.R
import app.sanctum.machina.data.dao.ChunkInspectorRow
import kotlinx.coroutines.launch

/**
 * Phase 4 Task 22 — chunks inspector. Diagnostic-only surface that shows every chunk
 * (id, file_name, page, **full** `chunk_text`) in the project, grouped by file.
 *
 * Design notes:
 *  - Full `chunk_text` is rendered without truncation by spec: a first-N-char preview hides
 *    the overlap region that makes this screen useful for debugging the Chunker.
 *  - Tap-to-copy on a card pushes the full text into the system clipboard. No long-press
 *    menu; the chunk is the unit of the screen, so a single primary gesture per card.
 *  - Monospace font helps read PDF-extracted text whose line breaks rarely align with
 *    visual word wrap.
 *  - No `animateContentSize` / item animations — `LazyColumn` with 500+ items needs to
 *    scroll cleanly on Honor 200 and recompositions cost more than the visual win.
 *  - `chunk_text` is untrusted PDF-derived content; we route it through a plain `Text`
 *    composable, never markdown / HTML renderers (consistent with the citation modal,
 *    see strings.xml:305-308).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProjectChunksScreen(
    onBack: () -> Unit,
    viewModel: ProjectChunksViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val copiedMsg = stringResource(R.string.project_chunks_copied)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.project_chunks_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when (val s = state) {
            ChunksUiState.Loading -> LoadingState(modifier = Modifier.padding(innerPadding))
            ChunksUiState.Empty -> EmptyState(modifier = Modifier.padding(innerPadding))
            is ChunksUiState.Loaded -> ChunksList(
                byFile = s.byFile,
                contentPadding = innerPadding,
                onChunkTap = { chunk ->
                    clipboard.setText(AnnotatedString(chunk.chunkText))
                    scope.launch { snackbarHostState.showSnackbar(copiedMsg) }
                },
            )
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.project_chunks_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChunksList(
    byFile: Map<String, List<ChunkInspectorRow>>,
    contentPadding: PaddingValues,
    onChunkTap: (ChunkInspectorRow) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
            start = 16.dp,
            end = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        byFile.forEach { (fileName, chunks) ->
            stickyHeader(key = "header-$fileName") {
                FileHeader(fileName = fileName, chunkCount = chunks.size)
            }
            itemsIndexed(items = chunks, key = { _, item -> item.id }) { index, chunk ->
                ChunkCard(
                    indexInFile = index + 1,
                    chunk = chunk,
                    onTap = { onChunkTap(chunk) },
                )
            }
        }
    }
}

@Composable
private fun FileHeader(fileName: String, chunkCount: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.project_chunks_header_format, fileName, chunkCount),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ChunkCard(
    indexInFile: Int,
    chunk: ChunkInspectorRow,
    onTap: () -> Unit,
) {
    val page = chunk.page
    val label = if (page != null) {
        stringResource(R.string.project_chunks_item_label, indexInFile, page)
    } else {
        stringResource(R.string.project_chunks_item_label_no_page, indexInFile)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = chunk.chunkText,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
