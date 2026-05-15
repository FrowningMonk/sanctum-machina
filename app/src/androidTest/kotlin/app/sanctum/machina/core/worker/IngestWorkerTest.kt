/*
 * Copyright 2026 Sanctum Machina authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package app.sanctum.machina.core.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import app.sanctum.machina.core.embedder.Embedder
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.DefaultProjectRepository
import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.data.SanctumDatabase
import app.sanctum.machina.data.dao.ProjectEmbeddingDao
import app.sanctum.machina.data.dao.ProjectFileDao
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import com.google.gson.Gson
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4 Task 7 acceptance tests for [IngestWorker].
 *
 * Tests build the worker through [TestListenableWorkerBuilder] (no WorkManager required) and
 * inject collaborators via [IngestWorker.testEntryPoint] — the production seam at
 * [IngestWorker.resolveEntryPoint] flips through that field when present so we never have to
 * stand up Hilt instrumentation.
 *
 * Each test sets up:
 *   - in-memory Room database for project/file/embedding rows
 *   - a deterministic [FakeEmbedder] returning a fixed-dim vector per chunk
 *   - a single-page PDF fixture built at runtime via pdfbox itself (same approach
 *     `PdfTextExtractorTest` uses to avoid committed binary fixtures)
 *
 * The PDF lives under `filesDir/projects/{projectId}/docs/{uuid}.pdf` so path validation
 * passes; the path-traversal test deliberately points the worker outside that root.
 */
@RunWith(AndroidJUnit4::class)
class IngestWorkerTest {

  private lateinit var context: Context
  private lateinit var db: SanctumDatabase
  private lateinit var fileDao: ProjectFileDao
  private lateinit var embeddingDao: ProjectEmbeddingDao
  private lateinit var repo: ProjectRepository
  private lateinit var errorLog: ErrorLog
  private lateinit var fakeEmbedder: FakeEmbedder

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    PDFBoxResourceLoader.init(context)
    db = Room.inMemoryDatabaseBuilder(context, SanctumDatabase::class.java)
      .addCallback(SanctumDatabase.ForeignKeysOnOpenCallback)
      .allowMainThreadQueries()
      .build()
    fileDao = db.projectFileDao()
    embeddingDao = db.projectEmbeddingDao()
    errorLog = ErrorLog(context)
    repo = DefaultProjectRepository(
      database = db,
      projectDao = db.projectDao(),
      projectFileDao = fileDao,
      projectEmbeddingDao = embeddingDao,
      messageDao = db.messageDao(),
      errorLog = errorLog,
      gson = Gson(),
      // Worker never enqueues again — keep enqueuer no-op for unit ingest path.
      ingestEnqueuer = IngestEnqueuer { _, _, _ -> },
    )
    fakeEmbedder = FakeEmbedder()
    IngestWorker.testEntryPoint = TestEntryPoint(
      repo = repo,
      fileDao = fileDao,
      embeddingDao = embeddingDao,
      embedder = fakeEmbedder,
      errorLog = errorLog,
    )
  }

  @After
  fun tearDown() {
    IngestWorker.testEntryPoint = null
    db.close()
    // Best-effort wipe of any PDFs created during the test.
    File(context.filesDir, "projects").deleteRecursively()
  }

  @Test
  fun happyPath_threePagePdf_completesWithReadyStatus() = runBlocking {
    val (projectId, fileId, pdfPath) = seedProjectAndFile(pages = 3)

    val result = buildWorker(projectId, fileId, pdfPath).doWork()

    assertEquals(ListenableWorker.Result.success(), result)
    val row = fileDao.getById(fileId)!!
    assertEquals("ready", row.status)
    assertNull(row.statusMessage)
    assertTrue("chunkCount must be > 0 after happy path", (row.chunkCount ?: 0) > 0)
    val embeddings = embeddingDao.allByProjectAndReadyFiles(projectId)
    assertTrue("embeddings must be persisted", embeddings.isNotEmpty())
  }

  @Test
  fun pathTraversal_outsideProjectDocsRoot_failsAndLogs() = runBlocking {
    val (projectId, fileId, _) = seedProjectAndFile(pages = 1)
    // Point filePath at a sibling directory the worker should reject.
    val escapePath = File(context.filesDir, "evil.pdf").absolutePath

    val result = buildWorker(projectId, fileId, escapePath).doWork()

    assertEquals(ListenableWorker.Result.failure(), result)
    val row = fileDao.getById(fileId)!!
    assertEquals("failed", row.status)
    // Status message localised — assert against the resource the worker uses.
    assertEquals(
      context.getString(app.sanctum.machina.R.string.ingest_status_failed_path),
      row.statusMessage,
    )
    assertTrue(
      "no partial embeddings on path-traversal reject",
      embeddingDao.allByProjectAndReadyFiles(projectId).isEmpty(),
    )
  }

  @Test
  fun pathTraversal_dotDotInPath_failsAndLogs() = runBlocking {
    val (projectId, fileId, _) = seedProjectAndFile(pages = 1)
    // Construct a path that *appears* to live under the docs root but escapes through `..`.
    val tricky = File(
      context.filesDir,
      "projects/$projectId/docs/../../../secret.pdf",
    ).absolutePath

    val result = buildWorker(projectId, fileId, tricky).doWork()

    assertEquals(ListenableWorker.Result.failure(), result)
    assertEquals("failed", fileDao.getById(fileId)!!.status)
  }

  @Test
  fun malformedPdf_failsAndKeepsConsistentState() = runBlocking {
    val (projectId, fileId, pdfPath) = seedProjectAndFile(pages = 1)
    // Overwrite the fixture with garbage so pdfbox load throws.
    File(pdfPath).writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04))

    val result = buildWorker(projectId, fileId, pdfPath).doWork()

    assertEquals(ListenableWorker.Result.failure(), result)
    val row = fileDao.getById(fileId)!!
    assertEquals("failed", row.status)
    assertTrue(
      "no embeddings on malformed PDF",
      embeddingDao.allByProjectAndReadyFiles(projectId).isEmpty(),
    )
  }

  @Test
  fun embedderFailure_marksFailedAndCleansEmbeddings() = runBlocking {
    val (projectId, fileId, pdfPath) = seedProjectAndFile(pages = 2)
    fakeEmbedder.throwOnEncode = true

    val result = buildWorker(projectId, fileId, pdfPath).doWork()

    assertEquals(ListenableWorker.Result.failure(), result)
    val row = fileDao.getById(fileId)!!
    assertEquals("failed", row.status)
    assertEquals(
      context.getString(app.sanctum.machina.R.string.ingest_status_failed_generic),
      row.statusMessage,
    )
    assertTrue(
      "no partial embeddings after embedder failure",
      embeddingDao.allByProjectAndReadyFiles(projectId).isEmpty(),
    )
  }

  @Test
  fun missingInputData_failsImmediately() = runBlocking {
    // Build with all-zero inputs — projectId<=0 short-circuits before any DB read.
    val worker = TestListenableWorkerBuilder<IngestWorker>(context, Data.EMPTY).build()
    val result = worker.doWork()
    assertEquals(ListenableWorker.Result.failure(), result)
  }

  @Test
  fun missingFileRow_failsAndDoesNotPersistEmbeddings() = runBlocking {
    val (projectId, _, pdfPath) = seedProjectAndFile(pages = 1)
    val orphanFileId = 9_999_999L // not in DB

    val result = buildWorker(projectId, orphanFileId, pdfPath).doWork()

    assertEquals(ListenableWorker.Result.failure(), result)
    assertTrue(
      "no embeddings created for orphan fileId",
      embeddingDao.allByProjectAndReadyFiles(projectId).isEmpty(),
    )
  }

  // ---- helpers ----

  private fun buildWorker(projectId: Long, fileId: Long, filePath: String): IngestWorker {
    val data = Data.Builder()
      .putLong(KEY_PROJECT_ID, projectId)
      .putLong(KEY_FILE_ID, fileId)
      .putString(KEY_FILE_PATH, filePath)
      .build()
    return TestListenableWorkerBuilder<IngestWorker>(context, data).build()
  }

  private suspend fun seedProjectAndFile(pages: Int): Triple<Long, Long, String> {
    val projectId = db.projectDao().insert(ProjectEntity(name = "p", createdAt = 1L))
    val docsDir = File(context.filesDir, "projects/$projectId/docs").apply { mkdirs() }
    val pdfFile = File(docsDir, "fixture.pdf")
    buildPdf(pdfFile, pages)
    val fileId = fileDao.insert(
      ProjectFileEntity(
        projectId = projectId,
        fileName = pdfFile.name,
        relativePath = "projects/$projectId/docs/${pdfFile.name}",
        contentHash = "h$projectId",
        status = "pending",
        createdAt = 1L,
      ),
    )
    return Triple(projectId, fileId, pdfFile.absolutePath)
  }

  private fun buildPdf(target: File, pageCount: Int) {
    PDDocument().use { doc ->
      for (i in 1..pageCount) {
        val page = PDPage()
        doc.addPage(page)
        PDPageContentStream(doc, page).use { stream ->
          stream.beginText()
          stream.setFont(PDType1Font.HELVETICA, 12f)
          stream.newLineAtOffset(72f, 720f)
          stream.showText(
            "Sample page $i. " + (
              "The quick brown fox jumps over the lazy dog. ".repeat(20)
              ),
          )
          stream.endText()
        }
      }
      doc.save(target)
    }
  }

  /** Minimal hand-written EntryPoint impl wired to the test fixtures. */
  private class TestEntryPoint(
    private val repo: ProjectRepository,
    private val fileDao: ProjectFileDao,
    private val embeddingDao: ProjectEmbeddingDao,
    private val embedder: Embedder,
    private val errorLog: ErrorLog,
  ) : IngestWorkerEntryPoint {
    override fun projectRepository(): ProjectRepository = repo
    override fun embedder(): Embedder = embedder
    override fun projectFileDao(): ProjectFileDao = fileDao
    override fun projectEmbeddingDao(): ProjectEmbeddingDao = embeddingDao
    override fun errorLog(): ErrorLog = errorLog
  }

  /** Deterministic embedder: returns one fixed-dim vector per input text. */
  private class FakeEmbedder(val dim: Int = 8) : Embedder {
    var throwOnEncode: Boolean = false
    override suspend fun encode(texts: List<String>, taskType: String): List<FloatArray> {
      if (throwOnEncode) error("FakeEmbedder configured to throw")
      return texts.map { text ->
        FloatArray(dim) { i -> ((text.hashCode() xor i) % 7).toFloat() }
      }
    }
    override suspend fun encodeQuery(text: String): FloatArray = encode(listOf(text), "query").first()
  }
}
