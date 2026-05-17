package app.sanctum.machina.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.sanctum.machina.data.model.ProjectFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectFileDao {

    @Insert
    suspend fun insert(file: ProjectFileEntity): Long

    @Update
    suspend fun update(file: ProjectFileEntity)

    @Query("DELETE FROM project_files WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM project_files WHERE id = :id")
    suspend fun getById(id: Long): ProjectFileEntity?

    @Query("SELECT * FROM project_files WHERE project_id = :projectId ORDER BY created_at ASC")
    fun observeByProject(projectId: Long): Flow<List<ProjectFileEntity>>

    /**
     * Task 9 / Decision 11 reindex-required apply: snapshot read of every file in the project,
     * used by `DefaultProjectRepository.applyReindexRequired` to flip statuses + re-enqueue
     * ingest in one pass. Suspending (not Flow) because the reindex caller needs a single
     * stable snapshot to drive the WorkManager enqueue loop.
     */
    @Query("SELECT * FROM project_files WHERE project_id = :projectId ORDER BY created_at ASC")
    suspend fun findAllByProject(projectId: Long): List<ProjectFileEntity>

    /**
     * Dedup lookup for IngestWorker: returns the row matching the SHA-256 content_hash
     * within the given project, or null if the file has not been ingested there yet.
     * The unique index on `(project_id, content_hash)` makes this an indexed point read.
     */
    @Query(
        "SELECT * FROM project_files " +
            "WHERE project_id = :projectId AND content_hash = :contentHash LIMIT 1",
    )
    suspend fun getByProjectAndHash(projectId: Long, contentHash: String): ProjectFileEntity?

    /**
     * Pre-send guard count (Task 11): how many files in this project are `status = 'ready'`,
     * exposed as a Flow so the UI updates live as ingest completes.
     */
    @Query(
        "SELECT COUNT(*) FROM project_files " +
            "WHERE project_id = :projectId AND status = 'ready'",
    )
    fun observeReadyCount(projectId: Long): Flow<Int>

    /**
     * Failed-banner population (Task 9): snapshot read of files in the given status
     * (typically `'failed'`).
     */
    @Query(
        "SELECT * FROM project_files " +
            "WHERE project_id = :projectId AND status = :status " +
            "ORDER BY created_at ASC",
    )
    suspend fun findByProjectAndStatus(projectId: Long, status: String): List<ProjectFileEntity>
}
