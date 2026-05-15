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

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.data.RagConfig
import app.sanctum.machina.data.dao.ProjectFileDao
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import app.sanctum.machina.engine.EmbedderGate
import app.sanctum.machina.engine.EmbedderState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectDetailViewModelTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val testDispatcher = StandardTestDispatcher()

  private lateinit var context: Context
  private lateinit var fakeFilesDir: File
  private lateinit var repo: DetailFakeProjectRepository
  private lateinit var fileDao: DetailFakeProjectFileDao
  private lateinit var embedderGate: DetailFakeEmbedderGate
  private lateinit var errorLog: ErrorLog

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    context = ApplicationProvider.getApplicationContext()
    fakeFilesDir = context.filesDir
    fileDao = DetailFakeProjectFileDao()
    repo = DetailFakeProjectRepository(fileDao)
    embedderGate = DetailFakeEmbedderGate()
    errorLog = ErrorLog(context)
    // Clean any prior run's project dirs to avoid cross-test pollution.
    File(fakeFilesDir, "projects").deleteRecursively()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
    File(fakeFilesDir, "projects").deleteRecursively()
  }

  // ---- FAB gate ----

  @Test
  fun fabEnabledState_followsEmbedderState() = runTest {
    embedderGate.setState(EmbedderState.NotDownloaded)
    val vm = newViewModel()
    backgroundScope.launch { vm.fabEnabled.collect {} }
    advanceUntilIdle()
    assertFalse(vm.fabEnabled.value)

    embedderGate.setState(EmbedderState.Ready)
    advanceUntilIdle()
    assertTrue(vm.fabEnabled.value)

    embedderGate.setState(EmbedderState.NotDownloaded)
    advanceUntilIdle()
    assertFalse(vm.fabEnabled.value)
  }

  @Test
  fun warmup_calledOnInit() = runTest {
    val vm = newViewModel(warmupOnInit = true)
    advanceUntilIdle()
    assertEquals(1, embedderGate.warmupCalls)
  }

  @Test
  fun onFabTapWhileDisabled_emitsEmbedderRequiredEvent() = runTest {
    embedderGate.setState(EmbedderState.NotDownloaded)
    val vm = newViewModel()
    val received = mutableListOf<ProjectDetailEvent>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      vm.events.collect { received += it }
    }

    vm.onFabTapWhileDisabled()
    advanceUntilIdle()

    assertTrue(received.contains(ProjectDetailEvent.EmbedderRequired))
    job.cancel()
  }

  // ---- Dedup ----

  @Test
  fun addDocuments_skipsDuplicateHashWithinSameProject() = runTest {
    // Two URIs whose first 100 KB hash to the same bytes — same file content.
    val uri1 = writeFileUri(tempFolder.newFile("a.pdf"), "duplicate-content-AAA".toByteArray())
    val uri2 = writeFileUri(tempFolder.newFile("b.pdf"), "duplicate-content-AAA".toByteArray())
    val vm = newViewModel()
    val received = mutableListOf<ProjectDetailEvent>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      vm.events.collect { received += it }
    }

    vm.addDocuments(listOf(uri1, uri2))
    advanceUntilIdle()

    assertEquals("addFile must be called exactly once", 1, repo.addFileCalls.size)
    assertEquals("enqueueIngest must be called exactly once", 1, repo.enqueueCalls.size)
    assertTrue(received.contains(ProjectDetailEvent.DuplicateDocument))
    job.cancel()
  }

  @Test
  fun addDocuments_secondSubmitAfterFirstCompletes_dedupsViaDb() = runTest {
    // Sequential submits of the same content — first inserts a row, second hits DB-level
    // dedup. (Renamed from `addDocuments_concurrentSubmitOfSameHash_singleEnqueue` after
    // test-reviewer round-1 major: the StandardTestDispatcher harness serialises both
    // launches, so this test exercises the persisted-dedup path, NOT the in-flight set.
    // The genuine in-flight-set test below uses a CompletableDeferred to park the first
    // processUri inside the race window.)
    val uri1 = writeFileUri(tempFolder.newFile("c.pdf"), "race-content-BBB".toByteArray())
    val uri2 = writeFileUri(tempFolder.newFile("d.pdf"), "race-content-BBB".toByteArray())
    val vm = newViewModel()

    vm.addDocuments(listOf(uri1))
    vm.addDocuments(listOf(uri2))
    advanceUntilIdle()

    assertEquals(1, repo.addFileCalls.size)
    assertEquals(1, repo.enqueueCalls.size)
  }

  @Test
  fun addDocuments_inFlightHashGuard_blocksSecondBeforeDbInsert() = runTest {
    // Gate `getByProjectAndHash` so the first processUri parks INSIDE its `try` block with
    // the hash already reserved in `inFlightHashes`. While parked, submit a second URI with
    // the same content — the in-flight guard must short-circuit it before the persisted dedup
    // path is ever reached. Litmus: deleting `inFlightHashes.add(hash)` in the VM would make
    // this test hang or call addFile twice.
    fileDao.gateGetByProjectAndHash = CompletableDeferred()
    val uri1 = writeFileUri(tempFolder.newFile("e.pdf"), "race-content-CCC".toByteArray())
    val uri2 = writeFileUri(tempFolder.newFile("f.pdf"), "race-content-CCC".toByteArray())
    val vm = newViewModel()
    val received = mutableListOf<ProjectDetailEvent>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      vm.events.collect { received += it }
    }

    vm.addDocuments(listOf(uri1))
    advanceUntilIdle() // First processUri parks awaiting the gate; hash is in-flight.

    vm.addDocuments(listOf(uri2))
    advanceUntilIdle() // Second short-circuits on inFlightHashes.add() returning false.

    assertTrue(
      "second submit must emit DuplicateDocument before dao.getByProjectAndHash returns",
      received.any { it is ProjectDetailEvent.DuplicateDocument },
    )
    assertEquals(
      "dao.getByProjectAndHash must be called exactly once (first processUri)",
      1,
      fileDao.getByProjectAndHashCalls,
    )

    // Release the gate; first processUri completes the addFile path.
    fileDao.gateGetByProjectAndHash!!.complete(Unit)
    advanceUntilIdle()

    assertEquals(1, repo.addFileCalls.size)
    job.cancel()
  }

  @Test
  fun addDocuments_emptyList_isNoOp() = runTest {
    val vm = newViewModel()
    val received = mutableListOf<ProjectDetailEvent>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      vm.events.collect { received += it }
    }

    vm.addDocuments(emptyList())
    advanceUntilIdle()

    assertTrue(repo.addFileCalls.isEmpty())
    assertTrue(repo.enqueueCalls.isEmpty())
    assertTrue(received.isEmpty())
    job.cancel()
  }

  @Test
  fun addDocuments_streamNull_emitsImportFailedAndSkipsInsert() = runTest {
    // Point at a path that never existed so contentResolver.openInputStream throws
    // FileNotFoundException — `readContentHash` catches IOException and returns null,
    // triggering the import-failed branch and skipping addFile.
    val missing = File(tempFolder.root, "never-existed-${System.nanoTime()}.pdf")
    val uri = Uri.fromFile(missing)

    val vm = newViewModel()
    val received = mutableListOf<ProjectDetailEvent>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      vm.events.collect { received += it }
    }

    vm.addDocuments(listOf(uri))
    advanceUntilIdle()

    assertTrue("addFile must not be called", repo.addFileCalls.isEmpty())
    assertTrue(
      "expected DocumentImportFailed in $received",
      received.any { it is ProjectDetailEvent.DocumentImportFailed },
    )
    job.cancel()
  }

  @Test
  fun requestModelManagerNav_emitsNavigationEvent() = runTest {
    val vm = newViewModel()
    val received = mutableListOf<ProjectDetailEvent>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      vm.events.collect { received += it }
    }

    vm.requestModelManagerNav()
    advanceUntilIdle()

    assertTrue(received.contains(ProjectDetailEvent.NavigateToModelManager))
    job.cancel()
  }

  @Test
  fun deleteFile_callsRepositoryWithFilesDir() = runTest {
    val vm = newViewModel()
    vm.deleteFile(42L)
    advanceUntilIdle()
    assertEquals(listOf(42L), repo.deleteFileCalls)
  }

  @Test
  fun addDocuments_emitsDuplicate_whenAlreadyPersisted() = runTest {
    val uri = writeFileUri(tempFolder.newFile("existing.pdf"), "existing-content".toByteArray())
    val vm = newViewModel()
    val received = mutableListOf<ProjectDetailEvent>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      vm.events.collect { received += it }
    }

    // First submit — succeeds.
    vm.addDocuments(listOf(uri))
    advanceUntilIdle()
    assertEquals(1, repo.addFileCalls.size)

    // Second submit of the same content — DB-level dedup fires (in-flight set already cleared).
    vm.addDocuments(listOf(uri))
    advanceUntilIdle()

    assertEquals("second submit must not call addFile", 1, repo.addFileCalls.size)
    assertTrue(received.any { it is ProjectDetailEvent.DuplicateDocument })
    job.cancel()
  }

  // ---- Failed-docs banner ----

  @Test
  fun failedDocsBanner_filtersFailedStatusInterrupted() = runTest {
    repo.emitFiles(
      listOf(
        fileRow(id = 1L, status = "ready", message = null),
        fileRow(id = 2L, status = "failed", message = "Прервано"),
        fileRow(id = 3L, status = "failed", message = "Документ зашифрован"),
      ),
    )
    val vm = newViewModel()
    backgroundScope.launch { vm.failedDocsBanner.collect {} }
    advanceUntilIdle()

    val banner = vm.failedDocsBanner.value
    assertEquals(listOf(2L), banner.map { it.id })
  }

  @Test
  fun dismissBanner_hidesUntilNewRowArrives() = runTest {
    repo.emitFiles(listOf(fileRow(id = 2L, status = "failed", message = "Прервано")))
    val vm = newViewModel()
    backgroundScope.launch { vm.failedDocsBanner.collect {} }
    advanceUntilIdle()
    assertEquals(1, vm.failedDocsBanner.value.size)

    vm.dismissFailedDocsBanner()
    advanceUntilIdle()
    assertTrue(vm.failedDocsBanner.value.isEmpty())

    // A different file failing surfaces a new banner row even after dismissal.
    repo.emitFiles(
      listOf(
        fileRow(id = 2L, status = "failed", message = "Прервано"),
        fileRow(id = 5L, status = "failed", message = "Прервано"),
      ),
    )
    advanceUntilIdle()
    assertEquals(listOf(5L), vm.failedDocsBanner.value.map { it.id })
  }

  // ---- Reindex / delete ----

  @Test
  fun reindex_callsRepoAndClearsDismissed() = runTest {
    repo.emitFiles(listOf(fileRow(id = 7L, status = "failed", message = "Прервано")))
    val vm = newViewModel()
    backgroundScope.launch { vm.failedDocsBanner.collect {} }
    advanceUntilIdle()

    vm.dismissFailedDocsBanner()
    advanceUntilIdle()
    assertTrue(vm.failedDocsBanner.value.isEmpty())

    vm.reindex(7L)
    advanceUntilIdle()
    assertEquals(listOf(7L), repo.reindexCalls)

    // After reindex, a re-failure of the same row should not stay hidden — verify by re-emitting.
    repo.emitFiles(listOf(fileRow(id = 7L, status = "failed", message = "Прервано")))
    advanceUntilIdle()
    assertEquals(listOf(7L), vm.failedDocsBanner.value.map { it.id })
  }

  @Test
  fun deleteProject_emitsProjectDeletedEvent() = runTest {
    val vm = newViewModel()
    val received = mutableListOf<ProjectDetailEvent>()
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      vm.events.collect { received += it }
    }

    vm.deleteProject()
    advanceUntilIdle()

    assertTrue(received.contains(ProjectDetailEvent.ProjectDeleted))
    assertEquals(listOf(PROJECT_ID), repo.deleteCalls)
    job.cancel()
  }

  // ---- helpers ----

  private fun newViewModel(warmupOnInit: Boolean = false): ProjectDetailViewModel {
    val ioDispatcher = UnconfinedTestDispatcher(testDispatcher.scheduler)
    return ProjectDetailViewModel(
      projectId = PROJECT_ID,
      projectRepository = repo,
      projectFileDao = fileDao,
      embedderGate = embedderGate,
      errorLog = errorLog,
      context = context,
      ioDispatcher = ioDispatcher,
      warmupOnInit = warmupOnInit,
    )
  }

  private fun fileRow(id: Long, status: String, message: String?): ProjectFileEntity =
    ProjectFileEntity(
      id = id,
      projectId = PROJECT_ID,
      fileName = "doc-$id.pdf",
      relativePath = "projects/$PROJECT_ID/docs/$id.pdf",
      contentHash = "h$id",
      status = status,
      statusMessage = message,
      chunkCount = null,
      createdAt = id,
    )

  private fun writeFileUri(file: File, bytes: ByteArray): Uri {
    file.writeBytes(bytes)
    return Uri.fromFile(file)
  }

  companion object {
    private const val PROJECT_ID: Long = 100L
  }
}

// ---------- fakes ----------

private class DetailFakeProjectRepository(
  private val fileDao: DetailFakeProjectFileDao,
) : ProjectRepository {
  val addFileCalls = mutableListOf<AddFileCall>()
  val enqueueCalls = mutableListOf<EnqueueCall>()
  val reindexCalls = mutableListOf<Long>()
  val deleteCalls = mutableListOf<Long>()
  val deleteFileCalls = mutableListOf<Long>()
  private val filesFlow = MutableStateFlow<List<ProjectFileEntity>>(emptyList())

  fun emitFiles(rows: List<ProjectFileEntity>) {
    filesFlow.value = rows
  }

  override fun observeAllProjects(): Flow<List<ProjectEntity>> = emptyFlow()
  override fun observeProjectById(projectId: Long): Flow<ProjectEntity?> =
    MutableStateFlow<ProjectEntity?>(ProjectEntity(id = projectId, name = "Test", createdAt = 0))
      .asStateFlow()

  override suspend fun getById(projectId: Long): ProjectEntity? =
    ProjectEntity(id = projectId, name = "Test", createdAt = 0)

  override suspend fun create(name: String, defaultModelId: String?): Long = 0L

  override suspend fun delete(projectId: Long, filesDir: File) {
    deleteCalls += projectId
  }

  override fun observeFiles(projectId: Long): Flow<List<ProjectFileEntity>> =
    filesFlow.asStateFlow()

  override suspend fun addFile(
    projectId: Long,
    fileName: String,
    contentHash: String,
    localPath: String,
  ): Long {
    val id = fileDao.insert(
      ProjectFileEntity(
        projectId = projectId,
        fileName = fileName,
        relativePath = localPath,
        contentHash = contentHash,
        status = "pending",
        createdAt = 0,
      ),
    )
    addFileCalls += AddFileCall(projectId, fileName, contentHash, localPath, id)
    return id
  }

  override suspend fun deleteFile(fileId: Long, filesDir: File) {
    deleteFileCalls += fileId
  }

  override suspend fun updateRagOverrides(projectId: Long, overrides: RagConfig?) = Unit
  override suspend fun getEffectiveRagSettings(projectId: Long): RagConfig =
    RagConfig(800, 100, 4, 768)

  override suspend fun enqueueIngest(projectId: Long, fileId: Long, filePath: String) {
    enqueueCalls += EnqueueCall(projectId, fileId, filePath)
  }

  override suspend fun reindexFile(fileId: Long) { reindexCalls += fileId }
  override suspend fun applyReindexRequired(
    projectId: Long,
    chunkSize: Int,
    chunkOverlap: Int,
  ) = Unit

}

private data class AddFileCall(
  val projectId: Long,
  val fileName: String,
  val contentHash: String,
  val localPath: String,
  val returnedId: Long,
)

private data class EnqueueCall(
  val projectId: Long,
  val fileId: Long,
  val filePath: String,
)

private class DetailFakeProjectFileDao : ProjectFileDao {
  private val byHash = mutableMapOf<Pair<Long, String>, ProjectFileEntity>()
  private var nextId: Long = 1L
  var gateGetByProjectAndHash: CompletableDeferred<Unit>? = null
  var getByProjectAndHashCalls: Int = 0; private set

  override suspend fun insert(file: ProjectFileEntity): Long {
    val id = nextId++
    byHash[file.projectId to file.contentHash] = file.copy(id = id)
    return id
  }
  override suspend fun update(file: ProjectFileEntity) {
    byHash[file.projectId to file.contentHash] = file
  }
  override suspend fun deleteById(id: Long) {
    byHash.entries.removeAll { it.value.id == id }
  }
  override suspend fun getById(id: Long): ProjectFileEntity? =
    byHash.values.firstOrNull { it.id == id }
  override fun observeByProject(projectId: Long): Flow<List<ProjectFileEntity>> =
    MutableStateFlow(byHash.values.filter { it.projectId == projectId }).asStateFlow()

  override suspend fun getByProjectAndHash(
    projectId: Long,
    contentHash: String,
  ): ProjectFileEntity? {
    getByProjectAndHashCalls++
    gateGetByProjectAndHash?.await()
    return byHash[projectId to contentHash]
  }

  override fun observeReadyCount(projectId: Long): Flow<Int> =
    MutableStateFlow(0).asStateFlow()

  override suspend fun findByProjectAndStatus(
    projectId: Long,
    status: String,
  ): List<ProjectFileEntity> = byHash.values.filter {
    it.projectId == projectId && it.status == status
  }

  override suspend fun findAllByProject(projectId: Long): List<ProjectFileEntity> =
    byHash.values.filter { it.projectId == projectId }
}

private class DetailFakeEmbedderGate : EmbedderGate {
  private val _state: MutableStateFlow<EmbedderState> = MutableStateFlow(EmbedderState.NotDownloaded)
  override val state: StateFlow<EmbedderState> = _state.asStateFlow()
  var warmupCalls: Int = 0

  fun setState(value: EmbedderState) { _state.value = value }

  override suspend fun warmup() { warmupCalls++ }
}
