package app.sanctum.machina.data

import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.core.worker.IngestEnqueuer
import app.sanctum.machina.engine.EmbedderRegistry
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.dao.ProjectDao
import app.sanctum.machina.data.dao.ProjectEmbeddingDao
import app.sanctum.machina.data.dao.ProjectFileDao
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

private const val PROJECTS_DIR = "projects"
private const val LOG_RETRIEVE = "rag-retrieve"
private const val LOG_INDEX = "rag-index"
private const val STALE_MARK_BATCH_SIZE = 50

/**
 * Decision 12 baseline — mirrors the EmbeddingGemma allowlist row's `defaultRagConfig` block.
 * Used by [DefaultProjectRepository.getEffectiveRagSettings] when the project has no override
 * and the allowlist read has not been wired through yet (Task 9 follow-up).
 */
private val DEFAULT_RAG_CONFIG = RagConfig(
  chunkSize = 800,
  chunkOverlap = 100,
  topK = 4,
  embeddingDim = 768,
)

/**
 * Production payload — list of citations for Gson reflection. Bound on a top-level field
 * so reflection metadata is cached once instead of rebuilt per row.
 */
private val CITATION_LIST_TYPE = object : TypeToken<List<Citation>>() {}.type

/**
 * Default [ProjectRepository]. Hilt-bound via `AppModule` as `@Singleton`. Mirrors
 * [DefaultChatRepository]'s seam pattern: a `@VisibleForTesting` primary constructor
 * exposing every collaborator (DAOs, ErrorLog, Gson, IO dispatcher, transaction runner)
 * plus a thin `@Inject` secondary that wires production defaults.
 *
 * `deleteFile` runs Decision 8 inside a single transaction via the `transactionRunner`
 * seam, so unit tests can drive the block without spinning a real Room instance.
 */
class DefaultProjectRepository
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal constructor(
  private val projectDao: ProjectDao,
  private val projectFileDao: ProjectFileDao,
  private val projectEmbeddingDao: ProjectEmbeddingDao,
  private val messageDao: MessageDao,
  private val errorLog: ErrorLog,
  private val gson: Gson,
  private val ioDispatcher: CoroutineDispatcher,
  private val transactionRunner: suspend (suspend () -> Unit) -> Unit,
  private val clock: () -> Long,
  // Task 7 seam — defaulted so existing tests that built the @VisibleForTesting constructor
  // by name continue to compile. Production wiring goes through the @Inject constructor.
  private val ingestEnqueuer: IngestEnqueuer = IngestEnqueuer { _, _, _ -> },
) : ProjectRepository {

  @Inject
  constructor(
    database: SanctumDatabase,
    projectDao: ProjectDao,
    projectFileDao: ProjectFileDao,
    projectEmbeddingDao: ProjectEmbeddingDao,
    messageDao: MessageDao,
    errorLog: ErrorLog,
    gson: Gson,
    ingestEnqueuer: IngestEnqueuer,
  ) : this(
    projectDao = projectDao,
    projectFileDao = projectFileDao,
    projectEmbeddingDao = projectEmbeddingDao,
    messageDao = messageDao,
    errorLog = errorLog,
    gson = gson,
    ioDispatcher = Dispatchers.IO,
    transactionRunner = { block -> database.withTransaction { block() } },
    clock = { System.currentTimeMillis() },
    ingestEnqueuer = ingestEnqueuer,
  )

  override fun observeAllProjects(): Flow<List<ProjectEntity>> = projectDao.observeAll()

  override fun observeProjectById(projectId: Long): Flow<ProjectEntity?> =
    projectDao.observeById(projectId)

  override suspend fun getById(projectId: Long): ProjectEntity? =
    withContext(ioDispatcher) { projectDao.getById(projectId) }

  override suspend fun create(name: String, defaultModelId: String?): Long =
    withContext(ioDispatcher) {
      projectDao.insert(
        ProjectEntity(
          name = name,
          defaultModelId = defaultModelId,
          ragOverridesJson = null,
          createdAt = clock(),
        ),
      )
    }

  override suspend fun delete(projectId: Long, filesDir: File) {
    withContext(ioDispatcher) {
      // CASCADE drops project_files / project_embeddings / chats / messages.
      projectDao.deleteById(projectId)
      val dir = File(filesDir, "$PROJECTS_DIR/$projectId")
      if (!isInsideProjectsRoot(dir, filesDir)) {
        // Should never happen — projectId is a typed Long and we joined our own
        // constant — but mirror the deleteFile guard so the two paths stay symmetric.
        errorLog.e(
          LOG_INDEX,
          "delete: refusing to remove projectId=$projectId — path escapes projects root",
        )
        return@withContext
      }
      if (dir.exists() && !dir.deleteRecursively()) {
        // Row is gone; on-disk PDFs survived. Flag as observable disk-orphan
        // (per Decision 8 / patterns.md — disk failures never block row deletion).
        // Log relative path, not absolute, to avoid leaking the app's storage
        // root into bug reports (security-auditor-1 minor).
        errorLog.e(
          LOG_INDEX,
          "delete: failed to remove project dir projectId=$projectId path=$PROJECTS_DIR/$projectId",
        )
      }
    }
  }

  override fun observeFiles(projectId: Long): Flow<List<ProjectFileEntity>> =
    projectFileDao.observeByProject(projectId)

  override suspend fun addFile(
    projectId: Long,
    fileName: String,
    contentHash: String,
    localPath: String,
  ): Long = withContext(ioDispatcher) {
    projectFileDao.insert(
      ProjectFileEntity(
        projectId = projectId,
        fileName = fileName,
        relativePath = localPath,
        contentHash = contentHash,
        status = "pending",
        createdAt = clock(),
      ),
    )
  }

  /** per Decision 8 — see interface KDoc for behavioural contract. */
  override suspend fun deleteFile(fileId: Long, filesDir: File) {
    withContext(ioDispatcher) {
      // Resolve scope before the transaction — we need projectId for the stale-mark loop
      // and relative_path for the post-commit disk cleanup. Both are derived from the same
      // row, so a single read keeps the path consistent if the row were updated mid-flight
      // (the snapshot here pins what the rest of the method operates on).
      val file = projectFileDao.getById(fileId) ?: return@withContext
      val projectId = file.projectId
      val relativePath = file.relativePath

      // Buffer malformed-row diagnostics until after the transaction commits — calling
      // ErrorLog inside the block re-enters Dispatchers.IO from within a Room transaction
      // scope, which has historically tripped the test-runner's mutex-vs-withContext
      // ordering on Robolectric.
      val malformedRowIds = mutableListOf<Long>()

      transactionRunner {
        projectFileDao.deleteById(fileId) // FK CASCADE -> project_embeddings rows gone.

        var offset = 0
        while (true) {
          val batch = messageDao.observeCitedMessagesPageByProject(
            projectId = projectId,
            offset = offset,
            limit = STALE_MARK_BATCH_SIZE,
          )
          if (batch.isEmpty()) break

          for (msg in batch) {
            val citationsJson = msg.citations ?: continue
            val updated = markStaleIfMatches(citationsJson, fileId)
            when (updated) {
              is StaleMarkResult.Changed -> messageDao.updateCitations(msg.id, updated.json)
              StaleMarkResult.Unchanged -> Unit
              StaleMarkResult.Malformed -> malformedRowIds += msg.id
            }
          }

          if (batch.size < STALE_MARK_BATCH_SIZE) break
          offset += batch.size
        }
      }

      // Post-commit diagnostics: log malformed rows so a corrupted snapshot is observable
      // in field bug reports without aborting the deletion the user explicitly asked for.
      // NonCancellable shields the audit trail when the caller's scope cancels between the
      // Room commit and the log loop (precedent: EmbedderRegistry.warmupLocked T6 review).
      withContext(NonCancellable) {
        for (id in malformedRowIds) {
          errorLog.e(
            LOG_RETRIEVE,
            "malformed citations row id=$id, skipping during deleteFile fileId=$fileId",
          )
        }
      }

      // Best-effort disk cleanup. relative_path is the value the caller stored via
      // ProjectRepository.addFile (tech-spec convention: relative to filesDir). The
      // containment check below is defence-in-depth — `relativePath` comes from a Room
      // row that internal code wrote, but a poisoned DB restore (or a future caller bug)
      // must not be able to convert this site into an arbitrary-file-delete. A delete
      // failure leaves a disk-orphan but the row is already gone — diagnostic only, do
      // not throw (Decision 8 disk-orphan policy).
      val pdf = File(filesDir, relativePath)
      if (!isInsideProjectsRoot(pdf, filesDir)) {
        errorLog.e(
          LOG_INDEX,
          "deleteFile: refusing to remove fileId=$fileId — relative_path escapes projects root: $relativePath",
        )
      } else if (pdf.exists() && !pdf.delete()) {
        errorLog.e(
          LOG_INDEX,
          "deleteFile: failed to remove PDF fileId=$fileId path=$relativePath",
        )
      }
    }
  }

  override suspend fun updateRagOverrides(projectId: Long, overrides: RagConfig?) {
    withContext(ioDispatcher) {
      val current = projectDao.getById(projectId) ?: return@withContext
      val json = overrides?.let { gson.toJson(it) }
      projectDao.update(current.copy(ragOverridesJson = json))
    }
  }

  override suspend fun getEffectiveRagSettings(projectId: Long): RagConfig =
    withContext(ioDispatcher) {
      val proj = projectDao.getById(projectId) ?: return@withContext DEFAULT_RAG_CONFIG
      val overlayJson = proj.ragOverridesJson ?: return@withContext DEFAULT_RAG_CONFIG
      try {
        gson.fromJson(overlayJson, RagConfig::class.java) ?: DEFAULT_RAG_CONFIG
      } catch (_: JsonSyntaxException) {
        errorLog.e(LOG_RETRIEVE, "malformed rag_overrides_json projectId=$projectId, using defaults")
        DEFAULT_RAG_CONFIG
      } catch (_: JsonParseException) {
        errorLog.e(LOG_RETRIEVE, "malformed rag_overrides_json projectId=$projectId, using defaults")
        DEFAULT_RAG_CONFIG
      }
    }

  override suspend fun enqueueIngest(projectId: Long, fileId: Long, filePath: String) {
    // No coroutine context switch needed — WorkManager.enqueueUniqueWork is non-blocking;
    // the suspend signature exists so future call-sites can move WorkManager wiring behind a
    // suspending API without API churn.
    ingestEnqueuer.enqueue(projectId, fileId, filePath)
  }

  override suspend fun reindexFile(fileId: Long, filesDir: File) {
    withContext(ioDispatcher) {
      val file = projectFileDao.getById(fileId) ?: return@withContext
      // FK CASCADE wipes `project_embeddings` rows that were persisted before this reindex;
      // we then re-insert via the worker pipeline so the row id stays stable but its state
      // walks the pending → indexing → ready arc fresh.
      projectEmbeddingDao.deleteByFileId(fileId)
      projectFileDao.update(
        file.copy(status = "pending", statusMessage = null, chunkCount = null),
      )
      // Task 20 fix: `IngestWorker.doWork` security-checks `expectedRoot` (always absolute)
      // against the input `filePath`. `project_files.relative_path` is stored relative to
      // [filesDir], so joining + canonicalising here keeps the worker's path-traversal guard
      // honest. Symmetric with how `ProjectDetailViewModel.addDocuments` passes
      // `pdfFile.absolutePath` on first ingest.
      ingestEnqueuer.enqueue(
        file.projectId,
        fileId,
        File(filesDir, file.relativePath).absolutePath,
      )
    }
  }

  override suspend fun applyReindexRequired(
    projectId: Long,
    chunkSize: Int,
    chunkOverlap: Int,
    filesDir: File,
  ) {
    withContext(ioDispatcher) {
      // Capture the existing effective config so the partial slider apply preserves topK +
      // embeddingDim — only the two reindex knobs are mutated by this entry point.
      val current = getEffectiveRagSettings(projectId)
      val merged = current.copy(chunkSize = chunkSize, chunkOverlap = chunkOverlap)

      // Snapshot files BEFORE the transaction so we can re-enqueue after commit. Reading
      // inside the transaction would block IngestEnqueuer's WorkManager call, which is
      // synchronous and runs on the same dispatcher.
      val filesSnapshot = projectFileDao.findAllByProject(projectId)

      transactionRunner {
        val project = projectDao.getById(projectId) ?: return@transactionRunner
        projectDao.update(project.copy(ragOverridesJson = gson.toJson(merged)))
        projectEmbeddingDao.deleteByProjectId(projectId)
        for (file in filesSnapshot) {
          projectFileDao.update(
            file.copy(status = "pending", statusMessage = null, chunkCount = null),
          )
        }
      }

      // Post-commit enqueue. Sharing one unique work name + APPEND_OR_REPLACE serialises
      // execution per Decision 5; spam-clicks on Confirm are absorbed by the queue.
      // Per-file try/catch so a partial WorkManager failure surfaces diagnostically without
      // dropping the rest of the batch (code-reviewer round-1 minor — robustness).
      for (file in filesSnapshot) {
        try {
          // Task 20 fix: see `reindexFile` for rationale — pass absolute path so the
          // `IngestWorker.doWork` path-traversal guard accepts the input.
          ingestEnqueuer.enqueue(
            projectId,
            file.id,
            File(filesDir, file.relativePath).absolutePath,
          )
        } catch (t: Throwable) {
          errorLog.e(
            LOG_INDEX,
            "applyReindexRequired: enqueue failed projectId=$projectId fileId=${file.id} :: ${t.message}",
          )
        }
      }
    }
  }

  override suspend fun projectsUsingEmbedder(embedderModelId: String): List<ProjectEntity> =
    withContext(ioDispatcher) {
      // Phase 4 MVP: a single allowlisted embedder backs every project. Defence-in-depth — a
      // mismatched id must not surface a misleading warning list (callers like Model Manager
      // already filter to the embedder row, but the test seam allows arbitrary input).
      if (embedderModelId != EmbedderRegistry.MODEL_ID_EMBEDDER) return@withContext emptyList()
      projectDao.getAllOrderedByCreatedAtAsc()
    }

  /**
   * Decode [json], flip `stale = true` on entries whose `fileId == [targetFileId]`, re-encode
   * if and only if at least one entry changed. Returns:
   *
   *  - [StaleMarkResult.Changed] with the new JSON when at least one entry was flipped.
   *  - [StaleMarkResult.Unchanged] when the row had no matching citation (write skipped to
   *    save SQLite work and to keep the on-disk JSON bit-identical for unrelated rows).
   *  - [StaleMarkResult.Malformed] when Gson rejects the input OR when the decoded payload
   *    violates the non-null contract of [Citation] (security-auditor-1 major: Gson
   *    reflection happily fills `null` into non-null Kotlin fields, so a poisoned row like
   *    `[{"fileId":42}]` would otherwise re-serialise with explicit nulls and defer a NPE
   *    to Task 11's UI). Caller logs + skips on Malformed.
   */
  private fun markStaleIfMatches(json: String, targetFileId: Long): StaleMarkResult {
    // Gson maps `null`/`""` payloads to null; treat as empty rather than malformed (a
    // legitimate caller may persist null when the assistant produced no citations).
    val decoded: List<Citation> = try {
      gson.fromJson<List<Citation>?>(json, CITATION_LIST_TYPE) ?: return StaleMarkResult.Unchanged
    } catch (_: JsonSyntaxException) {
      return StaleMarkResult.Malformed
    } catch (_: JsonParseException) {
      return StaleMarkResult.Malformed
    }
    // Defence against Kotlin-null-contract bypass via Gson reflection — any decoded
    // citation whose non-null fields ended up null means the snapshot is structurally
    // broken; we cannot safely round-trip it without writing back explicit nulls that
    // would crash downstream consumers. `fileId <= 0` catches the parallel primitive
    // case where Gson silently defaults a missing JSON key to `0L` (Citation has no
    // `fileId` default in source) — SQLite autoincrement starts at 1, so `0L` cannot
    // legitimately reference a real row; treat it as poison rather than risk re-
    // persisting a corrupt id alongside a valid sibling (security-auditor-2 minor).
    for (citation in decoded) {
      @Suppress("SENSELESS_COMPARISON")
      if (citation.fileName == null || citation.chunkText == null || citation.fileId <= 0L) {
        return StaleMarkResult.Malformed
      }
    }
    var changed = false
    val updated = decoded.map { citation ->
      if (citation.fileId == targetFileId && !citation.stale) {
        changed = true
        citation.copy(stale = true)
      } else {
        citation
      }
    }
    return if (changed) StaleMarkResult.Changed(gson.toJson(updated)) else StaleMarkResult.Unchanged
  }

  /**
   * Defence-in-depth path containment (parity with [DefaultChatRepository.requireInsideAttachmentsRoot]).
   * Both `delete` and `deleteFile` operate on disk paths derived from caller-supplied data
   * (`relative_path` on `project_files` rows) — without this check, a poisoned DB row could
   * convert these sites into arbitrary-file-delete primitives within the app-private sandbox.
   *
   * Returns `true` when [candidate]'s canonical path is strictly inside `filesDir/projects/`.
   * Returns `false` (rather than throwing) so the caller can log + skip — matches Decision 8's
   * disk-orphan policy (the row is already gone; a disk-side failure must never propagate).
   */
  private fun isInsideProjectsRoot(candidate: File, filesDir: File): Boolean {
    val projectsRoot = File(filesDir, PROJECTS_DIR).canonicalPath
    val candidateCanonical = try {
      candidate.canonicalPath
    } catch (_: java.io.IOException) {
      return false
    }
    // Reject equality with the root itself — every legitimate caller passes a *child*
    // path (`projects/{projectId}` or `projects/{projectId}/docs/{uuid}.pdf`). Allowing
    // equality would mean a future caller could erase the entire projects root through
    // a poisoned row; tightened for parity with sister repo (code-reviewer-2 minor).
    return candidateCanonical.startsWith(projectsRoot + File.separator)
  }

  private sealed class StaleMarkResult {
    data class Changed(val json: String) : StaleMarkResult()
    object Unchanged : StaleMarkResult()
    object Malformed : StaleMarkResult()
  }
}
