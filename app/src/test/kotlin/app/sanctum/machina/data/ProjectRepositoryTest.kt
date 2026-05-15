package app.sanctum.machina.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.dao.EmbeddingRow
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.dao.ProjectDao
import app.sanctum.machina.data.dao.ProjectEmbeddingDao
import app.sanctum.machina.data.dao.ProjectFileDao
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.data.model.ProjectEmbeddingEntity
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private val CITATION_LIST_TYPE = object : TypeToken<List<Citation>>() {}.type

/** Mirror of `DefaultProjectRepository.STALE_MARK_BATCH_SIZE` for the pagination assertion. */
private const val STALE_MARK_BATCH_SIZE_TEST = 50

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectRepositoryTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var context: Context
  private lateinit var projectDao: ProjectRepoFakeProjectDao
  private lateinit var fileDao: ProjectRepoFakeProjectFileDao
  private lateinit var embeddingDao: ProjectRepoFakeProjectEmbeddingDao
  private lateinit var messageDao: ProjectRepoFakeMessageDao
  private lateinit var errorLog: ErrorLog
  private lateinit var errorLogFile: File
  private lateinit var filesDir: File
  private val gson = Gson()

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    projectDao = ProjectRepoFakeProjectDao()
    fileDao = ProjectRepoFakeProjectFileDao()
    embeddingDao = ProjectRepoFakeProjectEmbeddingDao()
    messageDao = ProjectRepoFakeMessageDao()
    errorLog = ErrorLog(context)
    errorLogFile = File(context.filesDir, "logs/errors.log")
    errorLogFile.parentFile?.deleteRecursively()
    filesDir = tempFolder.newFolder("filesDir")
  }

  @After
  fun tearDown() {
    errorLogFile.parentFile?.deleteRecursively()
  }

  // ---- deleteFile: stale-mark logic ----

  @Test
  fun deleteFile_marksMatchingCitationsStale() = runTest {
    val (projectId, fileId, _) = setupProjectWithCitedMessage(
      citations = listOf(
        Citation(fileId = 7L, fileName = "x.pdf", page = 1, chunkText = "x", stale = false),
      ),
      assignFileId = 7L,
    )
    val repo = newRepository()

    repo.deleteFile(fileId, filesDir)

    val msg = messageDao.allMessages().single()
    val decoded: List<Citation> = gson.fromJson(msg.citations, CITATION_LIST_TYPE)
    assertTrue("matching citation must be stale", decoded.single().stale)
    assertNull("project_files row must be gone", fileDao.getByIdSync(fileId))
    assertNotNull("project still exists", projectDao.getByIdSync(projectId))
  }

  @Test
  fun deleteFile_leavesOtherCitationsUntouched() = runTest {
    // Two messages: one cites the deleted file, one cites a different file.
    val deletedFileId = 100L
    val keptFileId = 200L
    val (_, _, _) = setupProjectWithCitedMessage(
      citations = listOf(
        Citation(fileId = deletedFileId, fileName = "a.pdf", page = 1, chunkText = "a"),
      ),
      assignFileId = deletedFileId,
    )
    // Add a sibling cited message referencing a DIFFERENT file inside the same project.
    val chatId = messageDao.firstChatId()
    messageDao.insertSync(
      MessageEntity(
        chatId = chatId,
        role = "assistant",
        text = "b",
        createdAt = 2L,
        citations = gson.toJson(
          listOf(
            Citation(fileId = keptFileId, fileName = "b.pdf", page = 1, chunkText = "b"),
          ),
        ),
      ),
    )
    val repo = newRepository()

    repo.deleteFile(deletedFileId, filesDir)

    val allMsgs = messageDao.allMessages()
    val matched = allMsgs.single { it.citations!!.contains("\"fileId\":$deletedFileId") }
    val unrelated = allMsgs.single { it.citations!!.contains("\"fileId\":$keptFileId") }
    val matchedDecoded: List<Citation> = gson.fromJson(matched.citations, CITATION_LIST_TYPE)
    val unrelatedDecoded: List<Citation> = gson.fromJson(unrelated.citations, CITATION_LIST_TYPE)
    assertTrue("matching citation flipped stale", matchedDecoded.single().stale)
    assertFalse("unrelated citation untouched", unrelatedDecoded.single().stale)
  }

  @Test
  fun deleteFile_skipsMalformedJsonRow_andStillDeletesFileRow() = runTest {
    val deletedFileId = 42L
    val projectId = projectDao.insertSync(ProjectEntity(name = "p", createdAt = 1L))
    val chatId = messageDao.insertChat(projectId)
    fileDao.insertSync(
      ProjectFileEntity(
        id = deletedFileId,
        projectId = projectId,
        fileName = "x.pdf",
        relativePath = "projects/$projectId/docs/x.pdf",
        contentHash = "h",
        status = "ready",
        createdAt = 1L,
      ),
    )
    // Three messages: good, malformed, good — the malformed one must not abort.
    messageDao.insertSync(
      MessageEntity(
        chatId = chatId, role = "assistant", text = "a", createdAt = 2L,
        citations = gson.toJson(
          listOf(Citation(fileId = deletedFileId, fileName = "x", page = 1, chunkText = "a")),
        ),
      ),
    )
    val malformedId = messageDao.insertSync(
      MessageEntity(
        chatId = chatId, role = "assistant", text = "b", createdAt = 3L,
        citations = "{this is not valid json",
      ),
    )
    messageDao.insertSync(
      MessageEntity(
        chatId = chatId, role = "assistant", text = "c", createdAt = 4L,
        citations = gson.toJson(
          listOf(Citation(fileId = deletedFileId, fileName = "x", page = 2, chunkText = "c")),
        ),
      ),
    )
    val repo = newRepository()

    repo.deleteFile(deletedFileId, filesDir)

    assertNull("file row deleted despite malformed sibling", fileDao.getByIdSync(deletedFileId))
    val malformed = messageDao.allMessages().single { it.id == malformedId }
    assertEquals("malformed row untouched on disk", "{this is not valid json", malformed.citations)
    val log = errorLogFile.readLines()
    assertTrue(
      "rag-retrieve log emitted for malformed row id=$malformedId, lines: $log",
      log.any { it.contains("[rag-retrieve]") && it.contains("id=$malformedId") },
    )
    // The other two messages must still be flipped to stale.
    val good = messageDao.allMessages().filter { it.id != malformedId }
    for (m in good) {
      val decoded: List<Citation> = gson.fromJson(m.citations, CITATION_LIST_TYPE)
      assertTrue("good row stale-marked, message ${m.id}", decoded.all { it.stale })
    }
  }

  @Test
  fun deleteFile_processesBatchesOfFifty() = runTest {
    // Boundary cases: 50, 51, 100 — verify the pagination math.
    for (count in listOf(50, 51, 100)) {
      // Fresh per-iteration fakes — re-running @Before / TemporaryFolder.newFolder is not
      // legal inside a single test (TemporaryFolder fails on the second `newFolder("filesDir")`).
      projectDao = ProjectRepoFakeProjectDao()
      fileDao = ProjectRepoFakeProjectFileDao()
      embeddingDao = ProjectRepoFakeProjectEmbeddingDao()
      messageDao = ProjectRepoFakeMessageDao()
      val fileId = 9L
      val projectId = projectDao.insertSync(ProjectEntity(name = "p", createdAt = 1L))
      val chatId = messageDao.insertChat(projectId)
      fileDao.insertSync(
        ProjectFileEntity(
          id = fileId, projectId = projectId, fileName = "x.pdf",
          relativePath = "projects/$projectId/docs/x.pdf",
          contentHash = "h", status = "ready", createdAt = 1L,
        ),
      )
      repeat(count) { i ->
        messageDao.insertSync(
          MessageEntity(
            chatId = chatId, role = "assistant", text = "$i", createdAt = (i + 1).toLong(),
            citations = gson.toJson(
              listOf(Citation(fileId = fileId, fileName = "x", page = 1, chunkText = "$i")),
            ),
          ),
        )
      }

      val repo = newRepository()
      repo.deleteFile(fileId, filesDir)

      val staleCount = messageDao.allMessages().count {
        gson.fromJson<List<Citation>>(it.citations, CITATION_LIST_TYPE).all { c -> c.stale }
      }
      assertEquals("all $count messages flipped to stale", count, staleCount)

      // Pagination math — assert offsets walked through every full batch (catches a
      // regression where the loop reads only the first batch; test-reviewer-1 minor).
      // For count=50  → reads at offsets [0, 50]   (second read returns empty → break)
      // For count=51  → reads at offsets [0, 50]   (second read returns 1, < 50 → break)
      // For count=100 → reads at offsets [0, 50, 100]
      val expectedOffsets = (0..count step STALE_MARK_BATCH_SIZE_TEST).toList()
      assertEquals(
        "offsets walked for count=$count",
        expectedOffsets,
        messageDao.observedOffsets,
      )
    }
  }

  @Test
  fun deleteFile_doubleDelete_isNoOp() = runTest {
    val (_, fileId, _) = setupProjectWithCitedMessage(
      citations = listOf(
        Citation(fileId = 5L, fileName = "x.pdf", page = 1, chunkText = "x"),
      ),
      assignFileId = 5L,
    )
    val repo = newRepository()

    repo.deleteFile(fileId, filesDir)
    // Second call must not throw — row is already gone.
    repo.deleteFile(fileId, filesDir)

    assertNull(fileDao.getByIdSync(fileId))
  }

  @Test
  fun deleteFile_typeConfusedCitationsTreatedAsMalformed() = runTest {
    // security-auditor-1 major: Gson reflection over `[{"fileId":42}]` produces a Citation
    // with fileName=null / chunkText=null (Kotlin non-null contract bypassed). Without
    // the post-decode null check, this row would get its stale flag flipped and
    // re-serialised back with explicit nulls, deferring a NPE to UI render.
    val fileId = 77L
    val projectId = projectDao.insertSync(ProjectEntity(name = "p", createdAt = 1L))
    val chatId = messageDao.insertChat(projectId)
    fileDao.insertSync(
      ProjectFileEntity(
        id = fileId, projectId = projectId, fileName = "x.pdf",
        relativePath = "projects/$projectId/docs/x.pdf",
        contentHash = "h", status = "ready", createdAt = 1L,
      ),
    )
    val partialJson = """[{"fileId":$fileId}]"""
    val msgId = messageDao.insertSync(
      MessageEntity(
        chatId = chatId, role = "assistant", text = "p", createdAt = 2L,
        citations = partialJson,
      ),
    )

    val repo = newRepository()
    repo.deleteFile(fileId, filesDir)

    val msg = messageDao.allMessages().single { it.id == msgId }
    assertEquals("type-confused citation left untouched", partialJson, msg.citations)
    val log = errorLogFile.readLines()
    assertTrue(
      "type-confused row logged as malformed under rag-retrieve, lines: $log",
      log.any { it.contains("[rag-retrieve]") && it.contains("id=$msgId") },
    )
  }

  @Test
  fun deleteFile_zeroFileIdCitationsTreatedAsMalformed() = runTest {
    // security-auditor-2 minor: Gson silently defaults a missing `fileId` JSON key to
    // 0L. SQLite autoincrement starts at 1, so 0L cannot legitimately reference a real
    // row — treat as poison rather than risk re-persisting a corrupt id alongside a
    // valid sibling.
    val targetFileId = 99L
    val projectId = projectDao.insertSync(ProjectEntity(name = "p", createdAt = 1L))
    val chatId = messageDao.insertChat(projectId)
    fileDao.insertSync(
      ProjectFileEntity(
        id = targetFileId, projectId = projectId, fileName = "x.pdf",
        relativePath = "projects/$projectId/docs/x.pdf",
        contentHash = "h", status = "ready", createdAt = 1L,
      ),
    )
    val zeroIdJson = """[{"fileName":"x.pdf","page":1,"chunkText":"hi"}]"""
    val msgId = messageDao.insertSync(
      MessageEntity(
        chatId = chatId, role = "assistant", text = "z", createdAt = 2L,
        citations = zeroIdJson,
      ),
    )

    val repo = newRepository()
    repo.deleteFile(targetFileId, filesDir)

    val msg = messageDao.allMessages().single { it.id == msgId }
    assertEquals("zero-id citation row left byte-identical", zeroIdJson, msg.citations)
    val log = errorLogFile.readLines()
    assertTrue(
      "zero-id row logged as malformed under rag-retrieve, lines: $log",
      log.any { it.contains("[rag-retrieve]") && it.contains("id=$msgId") },
    )
  }

  @Test
  fun deleteFile_refusesEscapingRelativePath() = runTest {
    // security-auditor-1 / code-reviewer-1 major: a poisoned `project_files.relative_path`
    // pointing outside `filesDir/projects/` must not delete that path. The production code
    // joins `relativePath` to `filesDir` (NOT `filesDir/projects/`), so `../sensitive.txt`
    // resolves to `filesDir.parent/sensitive.txt`. Write the sentinel at that resolved
    // path so the "survive" assertion is load-bearing rather than vacuously true on a
    // non-existent file (test-reviewer-2 minor).
    val fileId = 88L
    val projectId = projectDao.insertSync(ProjectEntity(name = "p", createdAt = 1L))
    messageDao.insertChat(projectId)
    val sensitive = File(filesDir.parentFile, "sensitive.txt").apply { writeText("must survive") }
    fileDao.insertSync(
      ProjectFileEntity(
        id = fileId, projectId = projectId, fileName = "evil.pdf",
        relativePath = "../sensitive.txt",
        contentHash = "h", status = "ready", createdAt = 1L,
      ),
    )

    val repo = newRepository()
    repo.deleteFile(fileId, filesDir)

    assertTrue("sentinel file outside projects root must survive", sensitive.exists())
    assertEquals("sentinel content untouched", "must survive", sensitive.readText())
    val log = errorLogFile.readLines()
    assertTrue(
      "escape attempt logged under rag-index, lines: $log",
      log.any { it.contains("[rag-index]") && it.contains("escapes projects root") },
    )
    assertNull("file row still removed despite disk-side refusal", fileDao.getByIdSync(fileId))
  }

  @Test
  fun deleteFile_deletesOnDiskPdfBestEffort() = runTest {
    val fileId = 12L
    val projectId = projectDao.insertSync(ProjectEntity(name = "p", createdAt = 1L))
    messageDao.insertChat(projectId)
    val pdf = File(filesDir, "projects/$projectId/docs/x.pdf").apply {
      parentFile?.mkdirs()
      writeBytes(byteArrayOf(0x25, 0x50, 0x44, 0x46)) // "%PDF"
    }
    fileDao.insertSync(
      ProjectFileEntity(
        id = fileId, projectId = projectId, fileName = "x.pdf",
        relativePath = "projects/$projectId/docs/x.pdf",
        contentHash = "h", status = "ready", createdAt = 1L,
      ),
    )

    val repo = newRepository()
    repo.deleteFile(fileId, filesDir)

    assertFalse("PDF removed from disk", pdf.exists())
  }

  // ---- delete(projectId) ----

  @Test
  fun delete_cleansFilesDirTree() = runTest {
    val projectId = projectDao.insertSync(ProjectEntity(name = "p", createdAt = 1L))
    val projectDir = File(filesDir, "projects/$projectId/docs").apply { mkdirs() }
    File(projectDir, "x.pdf").writeBytes(byteArrayOf(1))

    val repo = newRepository()
    repo.delete(projectId, filesDir)

    assertNull("project row gone", projectDao.getByIdSync(projectId))
    assertFalse("project dir wiped", File(filesDir, "projects/$projectId").exists())
  }

  @Test
  fun delete_missingDir_isNoOp() = runTest {
    val projectId = projectDao.insertSync(ProjectEntity(name = "p", createdAt = 1L))
    // No directory created on disk.

    val repo = newRepository()
    repo.delete(projectId, filesDir) // must not throw

    assertNull(projectDao.getByIdSync(projectId))
  }

  // ---- updateRagOverrides ----

  @Test
  fun updateRagOverrides_writesJsonToProjectRow() = runTest {
    val projectId = projectDao.insertSync(ProjectEntity(name = "p", createdAt = 1L))
    val repo = newRepository()

    val overrides = RagConfig(chunkSize = 1000, chunkOverlap = 150, topK = 6, embeddingDim = 768)
    repo.updateRagOverrides(projectId, overrides)

    val stored = projectDao.getByIdSync(projectId)!!
    assertEquals(gson.toJson(overrides), stored.ragOverridesJson)
  }

  @Test
  fun updateRagOverrides_nullClearsJson() = runTest {
    val projectId = projectDao.insertSync(
      ProjectEntity(name = "p", ragOverridesJson = "{\"chunkSize\":900}", createdAt = 1L),
    )
    val repo = newRepository()

    repo.updateRagOverrides(projectId, null)

    assertNull(projectDao.getByIdSync(projectId)!!.ragOverridesJson)
  }

  // ---- Helpers ----

  private fun newRepository(): DefaultProjectRepository =
    DefaultProjectRepository(
      projectDao = projectDao,
      projectFileDao = fileDao,
      projectEmbeddingDao = embeddingDao,
      messageDao = messageDao,
      errorLog = errorLog,
      gson = gson,
      ioDispatcher = UnconfinedTestDispatcher(),
      transactionRunner = { block -> block() }, // no real txn in unit tests
      clock = { 1L },
    )

  /**
   * Insert a project + chat + one cited message. Returns (projectId, fileId, messageId).
   * Used by the simple stale-mark cases that don't need to drive batch boundaries.
   */
  private suspend fun setupProjectWithCitedMessage(
    citations: List<Citation>,
    assignFileId: Long,
  ): Triple<Long, Long, Long> {
    val projectId = projectDao.insertSync(ProjectEntity(name = "p", createdAt = 1L))
    val chatId = messageDao.insertChat(projectId)
    val fileRow = fileDao.insertSync(
      ProjectFileEntity(
        id = assignFileId, projectId = projectId, fileName = "x.pdf",
        relativePath = "projects/$projectId/docs/x.pdf",
        contentHash = "h", status = "ready", createdAt = 1L,
      ),
    )
    val msgId = messageDao.insertSync(
      MessageEntity(
        chatId = chatId, role = "assistant", text = "a", createdAt = 2L,
        citations = gson.toJson(citations),
      ),
    )
    return Triple(projectId, fileRow, msgId)
  }
}

// ---- Fakes ----

private class ProjectRepoFakeProjectDao : ProjectDao {
  val byId: MutableMap<Long, ProjectEntity> = linkedMapOf()
  private val state = MutableStateFlow<List<ProjectEntity>>(emptyList())
  private val byIdState = MutableStateFlow<Map<Long, ProjectEntity>>(emptyMap())
  private var nextId: Long = 1L

  fun insertSync(p: ProjectEntity): Long {
    val id = if (p.id > 0) p.id else nextId++
    if (id >= nextId) nextId = id + 1
    val stored = p.copy(id = id)
    byId[id] = stored
    state.value = byId.values.sortedByDescending { it.createdAt }
    byIdState.value = byId.toMap()
    return id
  }

  fun getByIdSync(id: Long): ProjectEntity? = byId[id]

  override suspend fun insert(project: ProjectEntity): Long = insertSync(project)
  override suspend fun update(project: ProjectEntity) {
    byId[project.id] = project
    state.value = byId.values.sortedByDescending { it.createdAt }
    byIdState.value = byId.toMap()
  }
  override suspend fun deleteById(id: Long) {
    byId.remove(id)
    state.value = byId.values.sortedByDescending { it.createdAt }
    byIdState.value = byId.toMap()
  }
  override suspend fun getById(id: Long): ProjectEntity? = byId[id]
  override fun observeById(id: Long): Flow<ProjectEntity?> = byIdState.map { it[id] }
  override fun observeAll(): Flow<List<ProjectEntity>> = state
}

private class ProjectRepoFakeProjectFileDao : ProjectFileDao {
  val byId: MutableMap<Long, ProjectFileEntity> = linkedMapOf()
  private var nextId: Long = 1L
  private val state = MutableStateFlow<Map<Long, ProjectFileEntity>>(emptyMap())

  fun insertSync(f: ProjectFileEntity): Long {
    val id = if (f.id > 0) f.id else nextId++
    if (id >= nextId) nextId = id + 1
    val stored = f.copy(id = id)
    byId[id] = stored
    state.value = byId.toMap()
    return id
  }
  fun getByIdSync(id: Long): ProjectFileEntity? = byId[id]

  override suspend fun insert(file: ProjectFileEntity): Long = insertSync(file)
  override suspend fun update(file: ProjectFileEntity) {
    byId[file.id] = file; state.value = byId.toMap()
  }
  override suspend fun deleteById(id: Long) {
    byId.remove(id); state.value = byId.toMap()
  }
  override suspend fun getById(id: Long): ProjectFileEntity? = byId[id]
  override fun observeByProject(projectId: Long): Flow<List<ProjectFileEntity>> =
    state.map { snap -> snap.values.filter { it.projectId == projectId }.sortedBy { it.createdAt } }
  override suspend fun getByProjectAndHash(
    projectId: Long, contentHash: String,
  ): ProjectFileEntity? = byId.values.firstOrNull {
    it.projectId == projectId && it.contentHash == contentHash
  }
  override fun observeReadyCount(projectId: Long): Flow<Int> =
    state.map { snap -> snap.values.count { it.projectId == projectId && it.status == "ready" } }
  override suspend fun findByProjectAndStatus(
    projectId: Long, status: String,
  ): List<ProjectFileEntity> =
    byId.values.filter { it.projectId == projectId && it.status == status }
  override suspend fun findAllByProject(projectId: Long): List<ProjectFileEntity> =
    byId.values.filter { it.projectId == projectId }.sortedBy { it.createdAt }
}

private class ProjectRepoFakeProjectEmbeddingDao : ProjectEmbeddingDao {
  override suspend fun insertAll(rows: List<ProjectEmbeddingEntity>): List<Long> =
    rows.mapIndexed { i, _ -> (i + 1).toLong() }
  override suspend fun deleteByFileId(fileId: Long) {}
  override suspend fun deleteByProjectId(projectId: Long) {}
  override suspend fun getById(id: Long): ProjectEmbeddingEntity? = null
  override suspend fun countByFileId(fileId: Long): Int = 0
  override suspend fun allByProjectAndReadyFiles(projectId: Long): List<EmbeddingRow> = emptyList()
}

/**
 * File-private chat-projection row used by [ProjectRepoFakeMessageDao] to model the production JOIN
 * through `chats.project_id`. Hoisted to top-level because the K2 compiler rejects a
 * nested data class inside a `private class` — visibility resolution fails on every
 * reference to the outer fake when ChatRow is nested.
 */
private data class ProjectRepoFakeChatRow(val id: Long, val projectId: Long?)

/**
 * Insert-aware MessageDao fake. Tracks chat→project assignment via the `chats` map so
 * `observeCitedMessagesPageByProject` mimics the production JOIN through `chats.project_id`.
 */
private class ProjectRepoFakeMessageDao : MessageDao {
  private val chats: MutableList<ProjectRepoFakeChatRow> = mutableListOf()
  private val messages: MutableList<MessageEntity> = mutableListOf()
  private var nextChatId: Long = 1L
  private var nextMsgId: Long = 1L

  /**
   * Offsets passed to [observeCitedMessagesPageByProject] in arrival order. Lets the batch-
   * boundary test (count=50/51/100) assert the production code walked through the full
   * pagination sequence rather than reading only the first batch (test-reviewer-1 minor).
   */
  val observedOffsets: MutableList<Int> = mutableListOf()

  fun insertChat(projectId: Long?): Long {
    val id = nextChatId++
    chats += ProjectRepoFakeChatRow(id, projectId)
    return id
  }

  fun firstChatId(): Long = chats.first().id

  fun insertSync(m: MessageEntity): Long {
    val id = nextMsgId++
    messages += m.copy(id = id)
    return id
  }

  fun allMessages(): List<MessageEntity> = messages.toList()

  override suspend fun insert(message: MessageEntity): Long = insertSync(message)
  override suspend fun deleteById(id: Long) {
    messages.removeAll { it.id == id }
  }
  override suspend fun getByChatId(chatId: Long): List<MessageEntity> =
    messages.filter { it.chatId == chatId }.sortedBy { it.createdAt }
  override fun observeByChat(chatId: Long): Flow<List<MessageEntity>> =
    MutableStateFlow(messages.filter { it.chatId == chatId })
  override suspend fun countByChatId(chatId: Long): Int =
    messages.count { it.chatId == chatId }
  override suspend fun firstByChatIdAndRole(chatId: Long, role: String): MessageEntity? =
    messages.firstOrNull { it.chatId == chatId && it.role == role }
  override suspend fun lastByChat(chatId: Long): MessageEntity? =
    messages.filter { it.chatId == chatId }
      .maxWithOrNull(compareBy({ it.createdAt }, { it.id }))

  override suspend fun observeCitedMessagesPageByProject(
    projectId: Long, offset: Int, limit: Int,
  ): List<MessageEntity> {
    observedOffsets += offset
    val chatIdsInProject = chats.filter { it.projectId == projectId }.map { it.id }.toSet()
    return messages
      .filter { it.chatId in chatIdsInProject && it.citations != null }
      .sortedBy { it.id }
      .drop(offset)
      .take(limit)
  }

  override suspend fun updateCitations(messageId: Long, citationsJson: String?) {
    val idx = messages.indexOfFirst { it.id == messageId }
    if (idx >= 0) messages[idx] = messages[idx].copy(citations = citationsJson)
  }
}

