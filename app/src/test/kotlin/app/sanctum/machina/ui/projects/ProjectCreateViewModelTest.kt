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

import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.data.RuntimeType
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.registry.ResetReason
import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.data.RagConfig
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectCreateViewModelTest {

  private val testDispatcher = StandardTestDispatcher()

  private lateinit var repo: CreateFakeProjectRepository
  private lateinit var registry: CreateFakeModelRegistry

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    repo = CreateFakeProjectRepository()
    registry = CreateFakeModelRegistry()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  @Test
  fun createWithBlankName_doesNotInsert() = runTest {
    val vm = ProjectCreateViewModel(repo, registry)

    vm.onNameChange("   ")
    vm.create()
    advanceUntilIdle()

    assertTrue("repo.create must not be called for blank name", repo.creates.isEmpty())
  }

  @Test
  fun createWithEmptyName_doesNotInsert() = runTest {
    val vm = ProjectCreateViewModel(repo, registry)
    vm.create()
    advanceUntilIdle()
    assertTrue(repo.creates.isEmpty())
  }

  @Test
  fun createWithValidName_trimsAndInsertsAndEmitsCreatedEvent() = runTest {
    val vm = ProjectCreateViewModel(repo, registry)
    val received = mutableListOf<Long>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      vm.created.collect { received += it }
    }

    repo.nextCreatedId = 42L
    vm.onNameChange("  My Project  ")
    vm.onModelSelect("litert-community/gemma-4")
    vm.create()
    advanceUntilIdle()

    val (name, modelId) = repo.creates.single()
    assertEquals("My Project", name)
    assertEquals("litert-community/gemma-4", modelId)
    assertEquals(listOf(42L), received)
    job.cancel()
  }

  @Test
  fun createWithoutModel_passesNullModelId() = runTest {
    val vm = ProjectCreateViewModel(repo, registry)
    vm.onNameChange("Plain")
    vm.create()
    advanceUntilIdle()

    val (_, modelId) = repo.creates.single()
    assertNull(modelId)
  }

  @Test
  fun availableModels_filtersEmbedderRows() = runTest {
    val chat = entry(
      modelId = "litert-community/gemma-4-E2B",
      runtime = RuntimeType.LITERT_LM,
      status = ModelDownloadStatusType.SUCCEEDED,
    )
    val embedder = entry(
      modelId = "litert-community/embeddinggemma-300m",
      runtime = RuntimeType.LITERT_INTERPRETER,
      status = ModelDownloadStatusType.SUCCEEDED,
    )
    val notDownloaded = entry(
      modelId = "litert-community/gemma-4-E4B",
      runtime = RuntimeType.LITERT_LM,
      status = ModelDownloadStatusType.NOT_DOWNLOADED,
    )
    registry.emit(listOf(chat, embedder, notDownloaded))

    val vm = ProjectCreateViewModel(repo, registry)
    backgroundScope.launch { vm.availableModels.collect {} }
    advanceUntilIdle()

    val ids = vm.availableModels.value.map { it.modelId }
    assertEquals(listOf("litert-community/gemma-4-E2B"), ids)
  }

  @Test
  fun canCreate_followsTrimmedName() = runTest {
    val vm = ProjectCreateViewModel(repo, registry)
    backgroundScope.launch { vm.canCreate.collect {} }
    advanceUntilIdle()
    assertFalse(vm.canCreate.value)

    vm.onNameChange("hello")
    advanceUntilIdle()
    assertTrue(vm.canCreate.value)

    vm.onNameChange("   ")
    advanceUntilIdle()
    assertFalse(vm.canCreate.value)
  }

  // ---- helpers ----

  private fun entry(
    modelId: String,
    runtime: RuntimeType,
    status: ModelDownloadStatusType,
  ): ModelEntry = ModelEntry(
    model = Model(name = modelId, modelId = modelId, displayName = modelId, runtimeType = runtime),
    downloadStatus = ModelDownloadStatus(status = status),
    initStatus = ModelInitStatus.Idle,
  )
}

// ---------- fakes (hand-rolled per patterns.md) ----------

private class CreateFakeProjectRepository : ProjectRepository {
  val creates = mutableListOf<Pair<String, String?>>()
  var nextCreatedId: Long = 1L

  override fun observeAllProjects(): Flow<List<ProjectEntity>> = emptyFlow()
  override fun observeProjectById(projectId: Long): Flow<ProjectEntity?> = emptyFlow()
  override suspend fun getById(projectId: Long): ProjectEntity? = null
  override suspend fun create(name: String, defaultModelId: String?): Long {
    creates += name to defaultModelId
    return nextCreatedId
  }
  override suspend fun delete(projectId: Long, filesDir: File) = Unit
  override fun observeFiles(projectId: Long): Flow<List<ProjectFileEntity>> = emptyFlow()
  override suspend fun addFile(
    projectId: Long,
    fileName: String,
    contentHash: String,
    localPath: String,
  ): Long = 0L
  override suspend fun deleteFile(fileId: Long, filesDir: File) = Unit
  override suspend fun updateRagOverrides(projectId: Long, overrides: RagConfig?) = Unit
  override suspend fun getEffectiveRagSettings(projectId: Long): RagConfig =
    RagConfig(800, 100, 4, 768)
  override suspend fun enqueueIngest(projectId: Long, fileId: Long, filePath: String) = Unit
  override suspend fun reindexFile(fileId: Long) = Unit
  override suspend fun applyReindexRequired(
    projectId: Long,
    chunkSize: Int,
    chunkOverlap: Int,
  ) = Unit
}

private class CreateFakeModelRegistry : ModelRegistry {
  private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
  override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

  private val _activeModelName = MutableStateFlow<String?>(null)
  override val activeModelName: StateFlow<String?> = _activeModelName.asStateFlow()

  fun emit(list: List<ModelEntry>) { _models.value = list }

  override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
  override fun download(model: Model): Flow<ModelDownloadStatus> =
    MutableStateFlow(ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED))
      .asStateFlow()
  override fun cancelDownload(modelName: String) = Unit
  override suspend fun delete(modelName: String) = Unit
  override suspend fun initialize(modelName: String): Result<Unit> = Result.success(Unit)
  override suspend fun cleanup(modelName: String) = Unit
  override suspend fun resetConversation(
    modelName: String,
    systemPrompt: String?,
    reason: ResetReason,
    initialMessages: List<com.google.ai.edge.litertlm.Message>,
  ) = Unit
  override fun getModel(modelName: String): Model? = null
}

