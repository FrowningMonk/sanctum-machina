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
import app.sanctum.machina.data.ChatRepository
import app.sanctum.machina.data.PersistedAttachment
import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.data.RagConfig
import app.sanctum.machina.data.dao.ProjectFileDao
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import app.sanctum.machina.ui.chat.Attachment
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
  private lateinit var chatRepository: DetailFakeChatRepository
  private lateinit var embedderGate: DetailFakeEmbedderGate
  private lateinit var errorLog: ErrorLog

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    context = ApplicationProvider.getApplicationContext()
    fakeFilesDir = context.filesDir
    fileDao = DetailFakeProjectFileDao()
    repo = DetailFakeProjectRepository(fileDao)
    chatRepository = DetailFakeChatRepository()
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

  // ---- Project chats projection (Task 19 follow-up) ----

  @Test
  fun chats_filtersByProjectId_andSortsByLastMessageAtDesc() = runTest {
    // Bug fix: ProjectDetailScreen showed «Чатов пока нет» even when chats existed,
    // because the chats list was never wired (Task 9 stub). VM now filters
    // `observeChats()` by projectId and sorts by `lastMessageAt DESC`.
    val mine1 = chatEntity(id = 1L, projectId = PROJECT_ID, lastMessageAt = 100L, title = "old")
    val mine2 = chatEntity(id = 2L, projectId = PROJECT_ID, lastMessageAt = 300L, title = "newest")
    val mine3 = chatEntity(id = 3L, projectId = PROJECT_ID, lastMessageAt = 200L, title = "middle")
    val foreign = chatEntity(id = 4L, projectId = 999L, lastMessageAt = 500L, title = "other-project")
    val nonProject = chatEntity(id = 5L, projectId = null, lastMessageAt = 600L, title = "drawer-plain")
    chatRepository.emit(listOf(mine1, foreign, mine3, nonProject, mine2))

    val vm = newViewModel()
    backgroundScope.launch { vm.chats.collect {} }
    advanceUntilIdle()

    assertEquals(
      "only chats with projectId == this projectId, sorted by lastMessageAt DESC",
      listOf(2L, 3L, 1L),
      vm.chats.value.map { it.id },
    )
  }

  @Test
  fun chats_emitsUpdate_whenObserveChatsEmitsNewRow() = runTest {
    // After commitDraft, the drawer flow re-emits with the new project-chat row;
    // VM's `chats` StateFlow must propagate that update so the screen flips from
    // empty-state to the chat list without a manual refresh.
    val vm = newViewModel()
    backgroundScope.launch { vm.chats.collect {} }
    advanceUntilIdle()
    assertTrue("initially empty", vm.chats.value.isEmpty())

    chatRepository.emit(
      listOf(chatEntity(id = 7L, projectId = PROJECT_ID, lastMessageAt = 1L, title = "fresh"))
    )
    advanceUntilIdle()

    assertEquals(listOf(7L), vm.chats.value.map { it.id })
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
      chatRepository = chatRepository,
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

  private fun chatEntity(
    id: Long,
    projectId: Long?,
    lastMessageAt: Long,
    title: String,
  ): ChatEntity = ChatEntity(
    id = id,
    projectId = projectId,
    modelId = "m/x",
    title = title,
    isManuallyTitled = 0,
    createdAt = 0L,
    lastMessageAt = lastMessageAt,
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
  override suspend fun projectsUsingEmbedder(embedderModelId: String) =
    emptyList<app.sanctum.machina.data.model.ProjectEntity>()

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

private class DetailFakeChatRepository : ChatRepository {
  private val chatsFlow = MutableStateFlow<List<ChatEntity>>(emptyList())

  fun emit(rows: List<ChatEntity>) { chatsFlow.value = rows }

  override fun observeChats(): Flow<List<ChatEntity>> = chatsFlow.asStateFlow()

  // ---- unused (returns defaults) ----
  override suspend fun commitDraftChat(
    modelId: String,
    firstMessage: MessageEntity,
    stagingDir: File?,
    filesDir: File,
    stagedImageFilename: String?,
    stagedAudioFilename: String?,
    projectId: Long?,
  ): Long = 0L
  override suspend fun writeAttachmentStaging(
    stagingDir: File,
    filesDir: File,
    attachment: Attachment,
  ): String = ""
  override suspend fun deleteStagedAttachment(
    stagingDir: File,
    filesDir: File,
    filename: String,
  ) = Unit
  override suspend fun pruneStagingDir(
    stagingDir: File,
    filesDir: File,
    retain: Set<String>,
  ) = Unit
  override suspend fun savePersistentAttachment(
    chatId: Long,
    filesDir: File,
    attachment: Attachment,
  ): PersistedAttachment = PersistedAttachment()
  override suspend fun savePersistentMessage(message: MessageEntity) = Unit
  override suspend fun updateChatLastMessage(chatId: Long, timestampMs: Long) = Unit
  override suspend fun updateChatTitle(chatId: Long, title: String, isManuallyTitled: Boolean) = Unit
  override suspend fun deleteChat(chatId: Long, filesDir: File) = Unit
  override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = emptyFlow()
  override suspend fun sweepZombieChats(filesDir: File) = Unit
}

private class DetailFakeEmbedderGate : EmbedderGate {
  private val _state: MutableStateFlow<EmbedderState> = MutableStateFlow(EmbedderState.NotDownloaded)
  override val state: StateFlow<EmbedderState> = _state.asStateFlow()
  var warmupCalls: Int = 0

  fun setState(value: EmbedderState) { _state.value = value }

  override suspend fun warmup() { warmupCalls++ }
}
