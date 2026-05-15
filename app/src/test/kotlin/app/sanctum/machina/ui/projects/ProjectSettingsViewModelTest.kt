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

import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.data.RagConfig
import app.sanctum.machina.data.dao.ProjectFileDao
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectSettingsViewModelTest {

  private val testDispatcher = StandardTestDispatcher()
  private lateinit var repo: SettingsFakeProjectRepository
  private lateinit var fileDao: SettingsFakeProjectFileDao

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    repo = SettingsFakeProjectRepository()
    fileDao = SettingsFakeProjectFileDao()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun init_seedsSlidersFromEffective() = runTest {
    repo.effective = RagConfig(chunkSize = 600, chunkOverlap = 50, topK = 6, embeddingDim = 768)
    val vm = newViewModel()
    advanceUntilIdle()

    assertEquals(600, vm.chunkSize.value)
    assertEquals(50, vm.chunkOverlap.value)
    assertEquals(6, vm.topK.value)
  }

  @Test
  fun topKChange_appliesImmediatelyWithoutDialog() = runTest {
    val vm = newViewModel()
    advanceUntilIdle()

    vm.onTopKChange(7)
    advanceUntilIdle()

    assertEquals(1, repo.updateCalls.size)
    val applied = repo.updateCalls.single()
      ?: error("light apply must pass a non-null RagConfig (test-reviewer round-1 nit)")
    assertEquals(7, applied.topK)
    // Spec — light apply preserves the other knobs from effective; verify no stale-baseline
    // drift bled into the persisted overrides.
    assertEquals(800, applied.chunkSize)
    assertEquals(100, applied.chunkOverlap)
    assertEquals(ReindexConfirmState.Hidden, vm.confirmDialogState.value)
  }

  @Test
  fun topKIdentityChange_doesNotPersist() = runTest {
    repo.effective = RagConfig(chunkSize = 800, chunkOverlap = 100, topK = 4, embeddingDim = 768)
    val vm = newViewModel()
    advanceUntilIdle()

    vm.onTopKChange(4)
    advanceUntilIdle()

    assertTrue(repo.updateCalls.isEmpty())
  }

  @Test
  fun chunkSizeChange_showsReindexDialogBeforeApply() = runTest {
    repo.emitFiles(
      listOf(fileRow(1L), fileRow(2L), fileRow(3L)),
    )
    val vm = newViewModel()
    backgroundScope.launch { vm.fileCount.collect {} }
    val received = mutableListOf<ProjectSettingsEvent>()
    val eventJob = launch(start = CoroutineStart.UNDISPATCHED) {
      vm.events.collect { received += it }
    }
    advanceUntilIdle()

    vm.onChunkSizeChange(1200)
    vm.applyHeavyChanges()
    advanceUntilIdle()

    val state = vm.confirmDialogState.value as ReindexConfirmState.Showing
    assertEquals(1200, state.pendingChunkSize)
    assertEquals(3, state.fileCount)
    assertTrue("applyReindexRequired must not fire before confirm", repo.reindexApplies.isEmpty())

    vm.confirmReindex()
    advanceUntilIdle()

    assertEquals(1, repo.reindexApplies.size)
    val (chunkSize, chunkOverlap) = repo.reindexApplies.single()
    assertEquals(1200, chunkSize)
    assertEquals(100, chunkOverlap)
    assertEquals(ReindexConfirmState.Hidden, vm.confirmDialogState.value)
    assertTrue(
      "confirmReindex must emit ReindexStarted",
      received.contains(ProjectSettingsEvent.ReindexStarted),
    )
    eventJob.cancel()
  }

  @Test
  fun chunkSizeIdentityApply_doesNotShowDialog() = runTest {
    repo.effective = RagConfig(chunkSize = 800, chunkOverlap = 100, topK = 4, embeddingDim = 768)
    val vm = newViewModel()
    advanceUntilIdle()

    vm.applyHeavyChanges()
    advanceUntilIdle()

    assertEquals(ReindexConfirmState.Hidden, vm.confirmDialogState.value)
    assertTrue(repo.reindexApplies.isEmpty())
  }

  @Test
  fun cancelReindex_revertsSlidersToEffective() = runTest {
    repo.effective = RagConfig(chunkSize = 800, chunkOverlap = 100, topK = 4, embeddingDim = 768)
    val vm = newViewModel()
    advanceUntilIdle()

    vm.onChunkSizeChange(1200)
    vm.applyHeavyChanges()
    advanceUntilIdle()
    vm.cancelReindex()
    advanceUntilIdle()

    assertEquals(800, vm.chunkSize.value)
    assertEquals(100, vm.chunkOverlap.value)
    assertEquals(ReindexConfirmState.Hidden, vm.confirmDialogState.value)
  }

  @Test
  fun chunkOverlapClampedBelowChunkSize() = runTest {
    repo.effective = RagConfig(chunkSize = 800, chunkOverlap = 100, topK = 4, embeddingDim = 768)
    val vm = newViewModel()
    advanceUntilIdle()

    // Slider tries to drag overlap above chunkSize — clamped to min(slider upper, chunkSize-1).
    // With chunkSize=800 and RagSliderBounds.chunkOverlapRange.last < 799, the slider upper
    // dominates. Pin the exact expected value rather than the loose `< chunkSize` invariant.
    vm.onChunkOverlapChange(2000)
    advanceUntilIdle()

    val expected = minOf(
      app.sanctum.machina.core.data.RagSliderBounds.chunkOverlapRange.last,
      vm.chunkSize.value - 1,
    )
    assertEquals(expected, vm.chunkOverlap.value)
    assertTrue(vm.chunkOverlap.value < vm.chunkSize.value)
  }

  @Test
  fun chunkSizeDragsDownRecomputesOverlapCeiling() = runTest {
    // Effective overlap=300 (init bypasses the slider clamp), then drag chunkSize down to
    // 200 — the cross-knob branch fires because 300 >= 200, clamping overlap to chunkSize-1 = 199.
    // (test-reviewer round-1: pin exact value, not the loose `< 200` invariant.)
    repo.effective = RagConfig(chunkSize = 800, chunkOverlap = 300, topK = 4, embeddingDim = 768)
    val vm = newViewModel()
    advanceUntilIdle()
    assertEquals("init bypasses the slider clamp", 300, vm.chunkOverlap.value)

    vm.onChunkSizeChange(200)
    advanceUntilIdle()

    assertEquals(200, vm.chunkSize.value)
    assertEquals(199, vm.chunkOverlap.value)
  }

  @Test
  fun resetDefaults_clearsRagOverridesJson() = runTest {
    repo.effective = RagConfig(chunkSize = 600, chunkOverlap = 50, topK = 6, embeddingDim = 768)
    val vm = newViewModel()
    advanceUntilIdle()

    vm.resetToDefaults()
    advanceUntilIdle()

    // updateRagOverrides(null) is the surface the repository exposes for "clear overrides".
    assertTrue(
      "expected one update call with null overrides",
      repo.updateCalls.any { it == null },
    )
    // After reset, sliders snap back to default 800/100/4 (Decision 12 baseline).
    assertEquals(800, vm.chunkSize.value)
    assertEquals(100, vm.chunkOverlap.value)
    assertEquals(4, vm.topK.value)
  }

  // ---- helpers ----

  private fun newViewModel(): ProjectSettingsViewModel =
    ProjectSettingsViewModel(
      projectId = PROJECT_ID,
      projectRepository = repo,
      projectFileDao = fileDao,
      ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler),
    )

  private fun fileRow(id: Long): ProjectFileEntity = ProjectFileEntity(
    id = id,
    projectId = PROJECT_ID,
    fileName = "f$id.pdf",
    relativePath = "projects/$PROJECT_ID/docs/$id.pdf",
    contentHash = "h$id",
    status = "ready",
    createdAt = id,
  )

  companion object {
    private const val PROJECT_ID: Long = 7L
  }
}

// ---------- fakes ----------

private class SettingsFakeProjectRepository : ProjectRepository {
  var effective: RagConfig = RagConfig(800, 100, 4, 768)
  val updateCalls = mutableListOf<RagConfig?>()
  val reindexApplies = mutableListOf<Pair<Int, Int>>()
  private val filesFlow = MutableStateFlow<List<ProjectFileEntity>>(emptyList())

  fun emitFiles(rows: List<ProjectFileEntity>) { filesFlow.value = rows }

  override fun observeAllProjects(): Flow<List<ProjectEntity>> = emptyFlow()
  override fun observeProjectById(projectId: Long): Flow<ProjectEntity?> = emptyFlow()
  override suspend fun getById(projectId: Long): ProjectEntity? = null
  override suspend fun create(name: String, defaultModelId: String?): Long = 0L
  override suspend fun delete(projectId: Long, filesDir: File) = Unit
  override fun observeFiles(projectId: Long): Flow<List<ProjectFileEntity>> =
    filesFlow.asStateFlow()
  override suspend fun addFile(
    projectId: Long,
    fileName: String,
    contentHash: String,
    localPath: String,
  ): Long = 0L
  override suspend fun deleteFile(fileId: Long, filesDir: File) = Unit

  override suspend fun updateRagOverrides(projectId: Long, overrides: RagConfig?) {
    updateCalls += overrides
    // Reset semantics: null overrides → effective falls back to baseline (Decision 12).
    if (overrides == null) {
      effective = RagConfig(800, 100, 4, 768)
    } else {
      effective = overrides
    }
  }

  override suspend fun getEffectiveRagSettings(projectId: Long): RagConfig = effective

  override suspend fun enqueueIngest(projectId: Long, fileId: Long, filePath: String) = Unit
  override suspend fun reindexFile(fileId: Long) = Unit
  override suspend fun applyReindexRequired(
    projectId: Long,
    chunkSize: Int,
    chunkOverlap: Int,
  ) {
    reindexApplies += chunkSize to chunkOverlap
    effective = effective.copy(chunkSize = chunkSize, chunkOverlap = chunkOverlap)
  }
  override suspend fun projectsUsingEmbedder(embedderModelId: String) =
    emptyList<app.sanctum.machina.data.model.ProjectEntity>()
}

private class SettingsFakeProjectFileDao : ProjectFileDao {
  override suspend fun insert(file: ProjectFileEntity): Long = 0L
  override suspend fun update(file: ProjectFileEntity) = Unit
  override suspend fun deleteById(id: Long) = Unit
  override suspend fun getById(id: Long): ProjectFileEntity? = null
  override fun observeByProject(projectId: Long): Flow<List<ProjectFileEntity>> =
    MutableStateFlow<List<ProjectFileEntity>>(emptyList()).asStateFlow()
  override suspend fun getByProjectAndHash(
    projectId: Long,
    contentHash: String,
  ): ProjectFileEntity? = null
  override fun observeReadyCount(projectId: Long): Flow<Int> =
    MutableStateFlow(0).asStateFlow()
  override suspend fun findByProjectAndStatus(
    projectId: Long,
    status: String,
  ): List<ProjectFileEntity> = emptyList()
  override suspend fun findAllByProject(projectId: Long): List<ProjectFileEntity> = emptyList()
}
