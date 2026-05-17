package app.sanctum.machina.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.sanctum.machina.data.model.ProjectEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observeById(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    /**
     * One-shot snapshot ordered by `created_at ASC` — the order in which projects
     * were created. Used by Task 10's embedder-delete warning dialog so the user
     * sees affected projects in the order they were added (matches the order
     * shown elsewhere in the UI, e.g. the Drawer Projects section).
     */
    @Query("SELECT * FROM projects ORDER BY created_at ASC")
    suspend fun getAllOrderedByCreatedAtAsc(): List<ProjectEntity>
}
