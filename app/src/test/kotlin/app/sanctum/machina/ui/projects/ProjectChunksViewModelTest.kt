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

import app.sanctum.machina.data.dao.ChunkInspectorRow
import app.sanctum.machina.data.dao.ProjectEmbeddingDao
import app.sanctum.machina.data.model.ProjectEmbeddingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectChunksViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var dao: FakeChunksDao

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        dao = FakeChunksDao()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun init_loadsAndGroupsChunksByFileName() = runTest {
        // DAO returns 5 rows from 2 files (already in file_name ASC, id ASC order).
        dao.chunks = listOf(
            row(id = 1, fileId = 10, fileName = "a.pdf", page = 1, text = "a1"),
            row(id = 2, fileId = 10, fileName = "a.pdf", page = 2, text = "a2"),
            row(id = 3, fileId = 10, fileName = "a.pdf", page = 3, text = "a3"),
            row(id = 4, fileId = 11, fileName = "b.pdf", page = 1, text = "b1"),
            row(id = 5, fileId = 11, fileName = "b.pdf", page = 2, text = "b2"),
        )
        val vm = newViewModel()
        advanceUntilIdle()

        val loaded = vm.state.value as ChunksUiState.Loaded
        assertEquals(2, loaded.byFile.size)
        // groupBy preserves DAO ordering — a.pdf must come before b.pdf.
        assertEquals(listOf("a.pdf", "b.pdf"), loaded.byFile.keys.toList())
        assertEquals(3, loaded.byFile["a.pdf"]!!.size)
        assertEquals(2, loaded.byFile["b.pdf"]!!.size)
        assertEquals("a1", loaded.byFile["a.pdf"]!!.first().chunkText)
    }

    @Test
    fun init_emptyDao_setsEmptyState() = runTest {
        dao.chunks = emptyList()
        val vm = newViewModel()
        advanceUntilIdle()

        assertEquals(ChunksUiState.Empty, vm.state.value)
    }

    @Test
    fun reload_refreshesSnapshot() = runTest {
        dao.chunks = emptyList()
        val vm = newViewModel()
        advanceUntilIdle()
        assertEquals(ChunksUiState.Empty, vm.state.value)

        dao.chunks = listOf(row(id = 1, fileId = 7, fileName = "x.pdf", page = null, text = "x"))
        vm.reload()
        advanceUntilIdle()

        val loaded = vm.state.value as ChunksUiState.Loaded
        assertEquals(1, loaded.byFile.size)
        assertTrue(loaded.byFile.containsKey("x.pdf"))
    }

    // ---- helpers ----

    private fun newViewModel(): ProjectChunksViewModel =
        ProjectChunksViewModel(
            projectId = PROJECT_ID,
            embeddingDao = dao,
            ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler),
        )

    private fun row(
        id: Long,
        fileId: Long,
        fileName: String,
        page: Int?,
        text: String,
    ): ChunkInspectorRow = ChunkInspectorRow(
        id = id,
        fileId = fileId,
        fileName = fileName,
        page = page,
        chunkText = text,
    )

    companion object {
        private const val PROJECT_ID: Long = 42L
    }
}

private class FakeChunksDao : ProjectEmbeddingDao {
    var chunks: List<ChunkInspectorRow> = emptyList()

    override suspend fun chunksByProject(projectId: Long): List<ChunkInspectorRow> = chunks

    // ---- unused on this screen — sealed surface satisfied with stubs ----
    override suspend fun insertAll(rows: List<ProjectEmbeddingEntity>): List<Long> = emptyList()
    override suspend fun deleteByFileId(fileId: Long) = Unit
    override suspend fun deleteByProjectId(projectId: Long) = Unit
    override suspend fun getById(id: Long): ProjectEmbeddingEntity? = null
    override suspend fun countByFileId(fileId: Long): Int = 0
    override suspend fun allByProjectAndReadyFiles(
        projectId: Long,
    ): List<app.sanctum.machina.data.dao.EmbeddingRow> = emptyList()
}
