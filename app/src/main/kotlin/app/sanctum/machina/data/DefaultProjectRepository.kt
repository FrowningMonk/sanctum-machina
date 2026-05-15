package app.sanctum.machina.data

import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import app.sanctum.machina.core.log.ErrorLog
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

private const val PROJECTS_DIR = "projects"
private const val LOG_RETRIEVE = "rag-retrieve"
private const val LOG_INDEX = "rag-index"
private const val STALE_MARK_BATCH_SIZE = 50

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
  @Suppress("UnusedPrivateProperty")
  private val projectEmbeddingDao: ProjectEmbeddingDao,
  private val messageDao: MessageDao,
  private val errorLog: ErrorLog,
  private val gson: Gson,
  private val ioDispatcher: CoroutineDispatcher,
  private val transactionRunner: suspend (suspend () -> Unit) -> Unit,
  private val clock: () -> Long,
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
      if (dir.exists() && !dir.deleteRecursively()) {
        // Row is gone; on-disk PDFs survived. Flag as observable disk-orphan
        // (per Decision 8 / patterns.md — disk failures never block row deletion).
        errorLog.e(
          LOG_INDEX,
          "delete: failed to remove project dir projectId=$projectId path=${dir.absolutePath}",
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
      for (id in malformedRowIds) {
        errorLog.e(LOG_RETRIEVE, "malformed citations row id=$id, skipping during deleteFile fileId=$fileId")
      }

      // Best-effort disk cleanup. relative_path is the value the caller stored in
      // ProjectRepository.addFile (tech-spec convention: relative to filesDir). A
      // delete failure leaves a disk-orphan but the row is already gone — diagnostic
      // only, do not throw.
      val pdf = File(filesDir, relativePath)
      if (pdf.exists() && !pdf.delete()) {
        errorLog.e(
          LOG_INDEX,
          "deleteFile: failed to remove PDF fileId=$fileId path=${pdf.absolutePath}",
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

  /**
   * Decode [json], flip `stale = true` on entries whose `fileId == [targetFileId]`, re-encode
   * if and only if at least one entry changed. Returns:
   *
   *  - [StaleMarkResult.Changed] with the new JSON when at least one entry was flipped.
   *  - [StaleMarkResult.Unchanged] when the row had no matching citation (write skipped to
   *    save SQLite work and to keep the on-disk JSON bit-identical for unrelated rows).
   *  - [StaleMarkResult.Malformed] when Gson rejects the input — caller logs + skips.
   */
  private fun markStaleIfMatches(json: String, targetFileId: Long): StaleMarkResult {
    val decoded: List<Citation> = try {
      gson.fromJson<List<Citation>?>(json, CITATION_LIST_TYPE) ?: return StaleMarkResult.Unchanged
    } catch (_: JsonSyntaxException) {
      return StaleMarkResult.Malformed
    } catch (_: JsonParseException) {
      return StaleMarkResult.Malformed
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

  private sealed class StaleMarkResult {
    data class Changed(val json: String) : StaleMarkResult()
    object Unchanged : StaleMarkResult()
    object Malformed : StaleMarkResult()
  }
}
