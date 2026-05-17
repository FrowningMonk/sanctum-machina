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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.annotation.VisibleForTesting
import androidx.work.workDataOf
import app.sanctum.machina.R
import app.sanctum.machina.core.embedder.Embedder
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.data.RagConfig
import app.sanctum.machina.data.dao.ProjectEmbeddingDao
import app.sanctum.machina.data.dao.ProjectFileDao
import app.sanctum.machina.data.model.ProjectEmbeddingEntity
import app.sanctum.machina.rag.Chunker
import app.sanctum.machina.rag.EmbeddingBlob
import app.sanctum.machina.rag.PageText
import app.sanctum.machina.rag.PdfTextExtractor
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val LOG_INDEX = "rag-index"
private const val NOTIFICATION_CHANNEL_ID = "ingest"
private const val TASK_TYPE_DOCUMENT = "retrieval_document"
private const val ENCODE_BATCH_SIZE = 8
private const val PROJECTS_DIR = "projects"
private const val DOCS_DIR = "docs"
private const val STATUS_INDEXING = "indexing"
private const val STATUS_READY = "ready"
private const val STATUS_FAILED = "failed"

/**
 * Phase 4 Task 7: foreground-service WorkManager job that ingests one PDF into the project's
 * vector store.
 *
 * **Module-boundary deviation.** The original tech-spec sited this file under
 * `:core-runtime/.../worker/` to mirror [DownloadWorker]. In practice every collaborator
 * ([ProjectRepository], [ProjectFileDao], [ProjectEmbeddingDao], [PdfTextExtractor],
 * [Chunker], [EmbeddingBlob]) lives in `:app`, and `:core-runtime` cannot depend on `:app`.
 * Inverting six interfaces just for module purity was rejected; the worker lives here in
 * `:app/core/worker/` instead. The [Embedder] interface still lives in `:core-runtime`
 * (Task 7 spec) so the cross-module surface a future worker-in-`:core-runtime` would need
 * stays minimal — only `Embedder` would have to be referenced.
 *
 * **Shape** (mirroring [DownloadWorker]):
 *  - plain [CoroutineWorker] — no `@HiltWorker` / `@AssistedInject`. Dependencies are
 *    resolved through [IngestWorkerEntryPoint] at the top of [doWork].
 *  - foreground service via [setForeground], type [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC]
 *    (so Android 14+ permits the long-running native PDF + encode loop).
 *  - persistent notification with Cancel action that broadcasts to [IngestCancelReceiver].
 *
 * **Security guards (Decision 5 + tech-spec § Risks R-8):**
 *  - canonical-prefix path validation on `filePath` — rejects any path outside
 *    `filesDir/projects/{projectId}/docs/`. Defends against a poisoned `inputData` payload
 *    that points at `/sdcard/foo` or uses `..` traversal.
 *  - [PendingIntent.FLAG_IMMUTABLE] + explicit [ComponentName] on the Cancel intent — required
 *    on API 31+ and prevents an arbitrary broadcast intent from being mutated by a malicious
 *    intercept.
 *  - [onStopped] and the `doWork` catch path both route through [cleanupPartialIngest] so any
 *    partial embeddings are deleted and the file is marked `failed` with the user-visible
 *    "Прервано" message — no half-ingested corpus left behind on cancel / process death.
 *
 * **Memory bound:** chunks are flushed to the DAO in batches of [ENCODE_BATCH_SIZE]; the
 * working set is page-text + one batch of chunks + one batch of FloatArrays at any moment.
 * A 500-page PDF therefore stays well under the per-process budget.
 */
class IngestWorker(context: Context, params: WorkerParameters) :
  CoroutineWorker(context, params) {

  private val notificationManager: NotificationManager =
    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

  override suspend fun doWork(): Result {
    val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
    val fileId = inputData.getLong(KEY_FILE_ID, -1L)
    val filePath = inputData.getString(KEY_FILE_PATH)

    val deps = resolveEntryPoint(applicationContext)

    if (projectId <= 0L || fileId <= 0L || filePath.isNullOrBlank()) {
      deps.errorLog().e(
        LOG_INDEX,
        "missing input data: projectId=$projectId fileId=$fileId filePathBlank=${filePath.isNullOrBlank()}",
      )
      return Result.failure()
    }

    // Foreground promotion FIRST — without this the platform can kill us before the
    // notification surfaces, especially under doze / battery saver. Matches DownloadWorker.
    setForeground(createForegroundInfo(projectId, fileId, fileName = "", page = 0, total = 0))

    // Decision 5 path validation — defence in depth against poisoned inputData.
    val expectedRoot = File(
      applicationContext.filesDir,
      "$PROJECTS_DIR/$projectId/$DOCS_DIR",
    ).canonicalPath
    val pdfFile = File(filePath)
    val resolved = try {
      pdfFile.canonicalPath
    } catch (_: Exception) {
      deps.errorLog().e(LOG_INDEX, "canonical-path resolve failed projectId=$projectId fileId=$fileId")
      cleanupPartialIngest(deps, fileId, statusMessage = applicationContext.getString(R.string.ingest_status_failed_generic))
      return Result.failure()
    }
    if (!resolved.startsWith(expectedRoot + File.separator)) {
      deps.errorLog().e(
        LOG_INDEX,
        "path traversal blocked projectId=$projectId fileId=$fileId",
      )
      cleanupPartialIngest(deps, fileId, statusMessage = applicationContext.getString(R.string.ingest_status_failed_path))
      return Result.failure()
    }

    if (!pdfFile.exists() || !pdfFile.canRead()) {
      deps.errorLog().e(LOG_INDEX, "pdf missing or unreadable fileId=$fileId")
      cleanupPartialIngest(deps, fileId, statusMessage = applicationContext.getString(R.string.ingest_status_failed_generic))
      return Result.failure()
    }

    // Mark the row as `indexing` so the UI can render the progress chip even before the first
    // notification update lands. `relativePath` etc. are left untouched. Capture the row
    // snapshot once and thread it through to `ingestPages` so the final READY update flows
    // from the same value (round-2 review: duplicate getById removed).
    val fileRow = deps.projectFileDao().getById(fileId)
    if (fileRow == null) {
      deps.errorLog().e(LOG_INDEX, "project_files row missing fileId=$fileId")
      return Result.failure()
    }
    deps.projectFileDao().update(fileRow.copy(status = STATUS_INDEXING, statusMessage = null))

    val settings = try {
      deps.projectRepository().getEffectiveRagSettings(projectId)
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      deps.errorLog().e(LOG_INDEX, "getEffectiveRagSettings failed projectId=$projectId", t)
      cleanupPartialIngest(deps, fileId, statusMessage = applicationContext.getString(R.string.ingest_status_failed_generic))
      return Result.failure()
    }

    return try {
      ingestPages(deps, projectId, fileId, pdfFile, fileRow, settings)
    } catch (ce: CancellationException) {
      // `CoroutineWorker.onStopped` is final in WorkManager 2.10, so the cancel-cleanup path
      // lives here: under NonCancellable so the DELETE + UPDATE survive the inflight CE that
      // arrived via `cancelUniqueWork` or process shutdown. Re-throw afterwards so structured
      // concurrency reports the work as STOPPED, not FAILED.
      withContext(NonCancellable) {
        cleanupPartialIngest(
          deps,
          fileId,
          statusMessage = applicationContext.getString(R.string.ingest_status_failed_cancelled),
        )
      }
      throw ce
    } catch (t: Throwable) {
      deps.errorLog().e(LOG_INDEX, "ingest failed projectId=$projectId fileId=$fileId", t)
      cleanupPartialIngest(deps, fileId, statusMessage = applicationContext.getString(R.string.ingest_status_failed_generic))
      Result.failure()
    }
  }

  private suspend fun ingestPages(
    deps: IngestWorkerEntryPoint,
    projectId: Long,
    fileId: Long,
    pdfFile: File,
    rowSnapshot: app.sanctum.machina.data.model.ProjectFileEntity,
    settings: RagConfig,
  ): Result {
    val extractor = PdfTextExtractor(
      applicationContext,
      logger = { msg, cause ->
        // Route per-page failures through ErrorLog; pdfbox-android can throw at any stage,
        // we treat each page as best-effort. Whole-document failures still terminate ingest
        // via the empty-flow path below.
        deps.errorLog().e("pdf-parse", "$msg projectId=$projectId fileId=$fileId", cause)
      },
    )

    val displayName = rowSnapshot.fileName
    var totalPages = 0
    var totalChunks = 0
    // Buffer chunks across pages so a batch can include the tail of one page + the head of
    // the next. Decision 5 / Architecture: encode in batches of N=8.
    val chunkBuffer = ArrayList<Pair<Int, String>>(ENCODE_BATCH_SIZE) // page -> chunkText

    // Task 23: write a live "стр. N · M чанков" string into `project_files.status_message`
    // after each page and each flushBuffer so the UI chip ticks in real time. We use the
    // resource string so locale honors the user's app language.
    suspend fun persistProgress() {
      deps.projectFileDao().update(
        rowSnapshot.copy(
          status = STATUS_INDEXING,
          statusMessage = applicationContext.getString(
            R.string.project_file_status_indexing_progress,
            totalPages,
            totalChunks,
          ),
          chunkCount = totalChunks,
        ),
      )
    }

    suspend fun flushBuffer() {
      if (chunkBuffer.isEmpty()) return
      // Re-check isStopped right before paying for an encode call — pdfbox extraction loop
      // already checks between pages, but a long page can fill the buffer to multiple
      // batches and we want to surrender as early as possible on a Cancel-tap (round-2
      // review: avoid wasted encode work).
      if (isStopped) throw CancellationException("ingest stopped (pre-flush)")
      val texts = chunkBuffer.map { it.second }
      val embeddings = deps.embedder().encode(texts, TASK_TYPE_DOCUMENT)
      check(embeddings.size == chunkBuffer.size) {
        "embedder returned ${embeddings.size} vectors for ${chunkBuffer.size} chunks"
      }
      val rows = chunkBuffer.mapIndexed { i, (page, text) ->
        ProjectEmbeddingEntity(
          projectId = projectId,
          fileId = fileId,
          page = page,
          chunkText = text,
          embeddingBlob = EmbeddingBlob.encode(embeddings[i]),
        )
      }
      deps.projectEmbeddingDao().insertAll(rows)
      totalChunks += rows.size
      chunkBuffer.clear()
      persistProgress()
    }

    extractor.extract(pdfFile).collect { page: PageText ->
      if (isStopped) throw CancellationException("ingest stopped (mid-extract)")
      totalPages++
      val chunks = Chunker.chunkPage(
        text = page.text,
        chunkSize = settings.chunkSize,
        overlap = settings.chunkOverlap,
      )
      for (c in chunks) {
        chunkBuffer.add(page.page to c.text)
        if (chunkBuffer.size >= ENCODE_BATCH_SIZE) flushBuffer()
      }
      // Update progress notification once per page so the visible page count is monotonic
      // even on PDFs with many short chunks.
      setForegroundInfoForProgress(
        projectId = projectId,
        fileId = fileId,
        fileName = displayName,
        page = totalPages,
        total = 0, // pdfbox extract is a Flow without a known total — left at 0 to render «индексация… N стр.».
      )
      setProgress(
        workDataOf(
          KEY_PROGRESS_PAGE to totalPages,
          KEY_PROGRESS_CHUNKS to totalChunks,
        ),
      )
      // Persist progress per-page too: an empty page (or a page with chunks that didn't fill
      // the encode batch) still ticks the page counter in the DB-driven UI chip.
      persistProgress()
    }
    flushBuffer()

    if (totalPages == 0) {
      // No pages extracted — either encrypted, malformed, scanned-image, or every page tripped
      // the pdfbox timeout/throw branch. Per-page failures are already logged via PdfParseLogger.
      cleanupPartialIngest(deps, fileId, statusMessage = applicationContext.getString(R.string.ingest_status_failed_generic))
      return Result.failure()
    }

    deps.projectFileDao().update(
      rowSnapshot.copy(
        status = STATUS_READY,
        statusMessage = null,
        chunkCount = totalChunks,
      ),
    )
    return Result.success()
  }

  override suspend fun getForegroundInfo(): ForegroundInfo {
    val projectId = inputData.getLong(KEY_PROJECT_ID, -1L)
    val fileId = inputData.getLong(KEY_FILE_ID, -1L)
    return createForegroundInfo(projectId, fileId, fileName = "", page = 0, total = 0)
  }

  private fun resolveEntryPoint(context: Context): IngestWorkerEntryPoint =
    testEntryPoint
      ?: EntryPointAccessors.fromApplication(context, IngestWorkerEntryPoint::class.java)

  /**
   * DELETE-by-file_id partial embeddings and flip `project_files.status='failed'` with the
   * caller-supplied [statusMessage]. Idempotent: missing row → no-op.
   *
   * Shared between [onStopped] and the [doWork] catch branch so cancel and runtime failure
   * leave the same observable on-disk shape.
   */
  private suspend fun cleanupPartialIngest(
    deps: IngestWorkerEntryPoint,
    fileId: Long,
    statusMessage: String,
  ) {
    try {
      deps.projectEmbeddingDao().deleteByFileId(fileId)
      val row = deps.projectFileDao().getById(fileId) ?: return
      deps.projectFileDao().update(
        row.copy(status = STATUS_FAILED, statusMessage = statusMessage),
      )
    } catch (ce: CancellationException) {
      throw ce
    } catch (t: Throwable) {
      // Cleanup must never throw — the row will be picked up by the next failed-banner pass.
      deps.errorLog().e(LOG_INDEX, "cleanupPartialIngest failed fileId=$fileId", t)
    }
  }

  private suspend fun setForegroundInfoForProgress(
    projectId: Long,
    fileId: Long,
    fileName: String,
    page: Int,
    total: Int,
  ) {
    try {
      setForeground(createForegroundInfo(projectId, fileId, fileName, page, total))
    } catch (ce: CancellationException) {
      throw ce
    } catch (_: Throwable) {
      // Foreground updates are best-effort cosmetics — failure here must not abort ingest.
    }
  }

  private fun createForegroundInfo(
    projectId: Long,
    fileId: Long,
    fileName: String,
    page: Int,
    total: Int,
  ): ForegroundInfo {
    ensureChannel()
    val title = applicationContext.getString(R.string.ingest_notification_title)
    val body = applicationContext.getString(
      R.string.ingest_notification_body,
      fileName.ifEmpty { applicationContext.getString(R.string.ingest_notification_filename_placeholder) },
      page,
      total,
    )
    val cancelIntent = Intent(applicationContext, IngestCancelReceiver::class.java).apply {
      // Explicit ComponentName — without it Android rejects implicit broadcasts to
      // receivers declared with `exported="false"` on some OEM ROMs.
      component = ComponentName(applicationContext, IngestCancelReceiver::class.java)
      putExtra(IngestCancelReceiver.EXTRA_WORK_NAME, WorkManagerIngestEnqueuer.uniqueWorkNameFor(projectId))
    }
    val cancelPi = PendingIntent.getBroadcast(
      applicationContext,
      // Stable 32-bit fold of fileId — `fileId.toInt()` would truncate, and combined with
      // `FLAG_UPDATE_CURRENT` two ingests whose ids share the low 32 bits would share the
      // same PendingIntent, letting a Cancel-tap on one card cancel a different worker
      // (security-auditor-1 major). The fold also avoids the `(-1L).toInt() == -1`
      // collision against the "missing input" sentinel.
      foldLongTo32(fileId),
      cancelIntent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val builder = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
      .setContentTitle(title)
      .setContentText(body)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .addAction(
        android.R.drawable.ic_menu_close_clear_cancel,
        applicationContext.getString(R.string.ingest_notification_cancel),
        cancelPi,
      )
    if (total > 0) builder.setProgress(total, page, false) else builder.setProgress(0, 0, true)
    return ForegroundInfo(
      // Notification id from the stable 32-bit fold (same rationale as the cancel request
      // code above). Concurrent (queued) ingests with distinct fileIds end up on distinct
      // notification cards as long as their Long ids don't collide under the fold — which
      // for autoincrement primary keys is effectively never within a project's lifetime.
      foldLongTo32(fileId),
      builder.build(),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }

  /** XOR-fold of [value]'s high and low 32 bits — stable, collision-resistant for autoinc ids. */
  private fun foldLongTo32(value: Long): Int = (value xor (value ushr 32)).toInt()

  private fun ensureChannel() {
    // NotificationManager.createNotificationChannel is idempotent — re-creating with the same
    // id is a no-op. Keeps the channel survival decoupled from Application#onCreate ordering.
    val channel = NotificationChannel(
      NOTIFICATION_CHANNEL_ID,
      applicationContext.getString(R.string.ingest_notification_channel_name),
      NotificationManager.IMPORTANCE_LOW,
    )
    notificationManager.createNotificationChannel(channel)
  }

  companion object {
    /** WorkData progress keys observed by [androidx.work.WorkInfo.getProgress]. */
    const val KEY_PROGRESS_PAGE: String = "progressPage"
    const val KEY_PROGRESS_CHUNKS: String = "progressChunks"

    /**
     * Test-only injection seam: when non-null, [resolveEntryPoint] returns this instead of
     * calling [EntryPointAccessors.fromApplication]. Lets `IngestWorkerTest` build the worker
     * via [androidx.work.testing.TestListenableWorkerBuilder] without standing up a full Hilt
     * test graph. Production code MUST leave this null.
     */
    @JvmField
    @VisibleForTesting
    @Volatile
    var testEntryPoint: IngestWorkerEntryPoint? = null
  }
}
