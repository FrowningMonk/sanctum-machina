package app.sanctum.machina.data

import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Data-layer facade over `projects` / `project_files` / `project_embeddings` tables and the
 * on-disk PDF tree at `filesDir/projects/{projectId}/`.
 *
 * Phase 4 contract — see tech-spec § Decisions 7 / 8 / 13 and § Architecture lines 29 / 36 / 42.
 *
 * Scope of this task (Task 6 AC line 105):
 *   `observeAllProjects`, `getById`, `create`, `delete`, `observeFiles`, `addFile`,
 *   `deleteFile`, `updateRagOverrides`.
 *
 * Task 7 additions: `enqueueIngest` (`IngestWorker` + `WorkManager` wiring),
 * `getEffectiveRagSettings` (merge of per-project overrides on top of allowlist defaults — the
 * baseline read of `Model.defaultRagConfig` is deferred to Task 9 when `ProjectSettingsViewModel`
 * also needs it; today the fallback uses Decision 12's documented constants).
 *
 * The remaining downstream methods (`reindexFile`, `applyReindexRequired`,
 * `projectsUsingEmbedder`, `observeChatsByProject`) are deferred to the tasks that introduce
 * their supporting infrastructure — Task 8/9 (UI surfaces + ChatDao project projection), Task 10
 * (embedder delete-guard column). The interface is grown additively in those tasks rather
 * than declared here with stubs — keeps the surface honest about what is actually wired.
 *
 * `deleteFile` is the only non-trivial method: it runs Decision 8's eager stale-mark loop
 * inside a single Room transaction and best-effort deletes the on-disk PDF after commit.
 *
 * All I/O runs on `Dispatchers.IO`.
 */
interface ProjectRepository {

  /** Hot stream of all projects, ordered by `created_at DESC`. */
  fun observeAllProjects(): Flow<List<ProjectEntity>>

  /** Hot stream of a single project — emits `null` after the project is deleted. */
  fun observeProjectById(projectId: Long): Flow<ProjectEntity?>

  /** One-shot snapshot read. */
  suspend fun getById(projectId: Long): ProjectEntity?

  /**
   * Create a new project row. [defaultModelId] is optional (US-AC1 — default-модель не
   * обязательна; resolution to the global default lives in Decision 16, not here).
   * Returns the auto-generated row id.
   */
  suspend fun create(name: String, defaultModelId: String?): Long

  /**
   * Delete the project row. FK CASCADE removes `project_files`, `project_embeddings`,
   * `chats`, `messages` automatically (Decision 13). After the row is gone, the on-disk
   * tree `filesDir/projects/{projectId}/` is removed recursively — a leftover directory
   * after a failed `deleteRecursively` is logged under `rag-index` as a diagnostic-only
   * disk-orphan (does not throw).
   *
   * Missing project / missing directory → no-op (idempotent).
   */
  suspend fun delete(projectId: Long, filesDir: File)

  /** Hot stream of files in [projectId], ordered by `created_at ASC`. */
  fun observeFiles(projectId: Long): Flow<List<ProjectFileEntity>>

  /**
   * Insert a `project_files` row with `status = 'pending'`. [localPath] is stored verbatim
   * in `relative_path` (caller chooses the convention; tech-spec § How it works uses
   * `projects/{projectId}/docs/{uuid}.pdf` relative to `filesDir`). Enqueueing the
   * `IngestWorker` is the caller's responsibility — Task 7 owns that wrapper.
   */
  suspend fun addFile(
    projectId: Long,
    fileName: String,
    contentHash: String,
    localPath: String,
  ): Long

  /**
   * Delete a `project_files` row and eagerly mark stale citations on every cited message
   * inside the owning project (Decision 8). Single Room transaction; processes messages in
   * batches of 50 to bound JVM heap; malformed citation JSON is logged via `rag-retrieve`
   * and skipped (one bad row must not block file deletion). FK CASCADE removes
   * `project_embeddings` rows for the file.
   *
   * After the transaction commits, the on-disk PDF at the entity's `relative_path` (joined
   * to [filesDir]) is removed best-effort — a delete failure logs under `rag-index` as a
   * disk-orphan and does not throw.
   *
   * Missing file id → no-op (idempotent, supports double-tap delete from UI).
   */
  suspend fun deleteFile(fileId: Long, filesDir: File)

  /**
   * Partial update of per-project RAG knobs. `null` clears overrides entirely; non-null is
   * encoded via Gson and stored verbatim in `projects.rag_overrides_json`. Merging with
   * allowlist defaults lives at the call site (Task 7 / 9 — not here).
   */
  suspend fun updateRagOverrides(projectId: Long, overrides: RagConfig?)

  /**
   * Resolve the effective [RagConfig] for [projectId]: per-project overrides from
   * `projects.rag_overrides_json` if present, else Decision 12 baseline (chunkSize=800,
   * chunkOverlap=100, topK=4, embeddingDim=768 — matches the EmbeddingGemma allowlist row).
   *
   * Missing project / malformed overlay JSON / unknown projectId → baseline (logged via
   * `rag-retrieve` for diagnostic — corrupted overrides must not block ingest).
   *
   * Task 9 will refine the fallback path to read [app.sanctum.machina.core.data.RagDefaults]
   * from the embedder allowlist row when the UI surface that exposes the defaults is wired —
   * keeping the constants here today keeps `IngestWorker` honest about which knobs it needs.
   */
  suspend fun getEffectiveRagSettings(projectId: Long): RagConfig

  /**
   * Enqueue a `OneTimeWorkRequest<IngestWorker>` for the file `(projectId, fileId, filePath)`.
   * Unique-work name is `"ingest-project-{projectId}"`, policy is
   * `ExistingWorkPolicy.APPEND_OR_REPLACE` (Decision 5).
   *
   * [filePath] MUST be an absolute path under `context.filesDir/projects/{projectId}/docs/`;
   * `IngestWorker` re-validates this on entry and fails the work with a `rag-index` log on any
   * escape (defence-in-depth — the path leaves the safe scope through `inputData`).
   */
  suspend fun enqueueIngest(projectId: Long, fileId: Long, filePath: String)
}
