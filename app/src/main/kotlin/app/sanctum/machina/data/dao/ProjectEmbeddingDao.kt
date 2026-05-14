package app.sanctum.machina.data.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.sanctum.machina.data.model.ProjectEmbeddingEntity

/**
 * Projection returned by [ProjectEmbeddingDao.allByProjectAndReadyFiles]. The JOIN with
 * `project_files` is filtered to `status = 'ready'` so chunks from in-flight or failed
 * ingests never reach retrieval.
 */
data class EmbeddingRow(
    @ColumnInfo(name = "file_id") val fileId: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "page") val page: Int?,
    @ColumnInfo(name = "chunk_text") val chunkText: String,
    @ColumnInfo(name = "embedding_blob") val embeddingBlob: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingRow) return false
        return fileId == other.fileId &&
            fileName == other.fileName &&
            page == other.page &&
            chunkText == other.chunkText &&
            embeddingBlob.contentEquals(other.embeddingBlob)
    }

    override fun hashCode(): Int {
        var result = fileId.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + (page ?: 0)
        result = 31 * result + chunkText.hashCode()
        result = 31 * result + embeddingBlob.contentHashCode()
        return result
    }
}

@Dao
interface ProjectEmbeddingDao {

    @Insert
    suspend fun insertAll(rows: List<ProjectEmbeddingEntity>): List<Long>

    @Query("DELETE FROM project_embeddings WHERE file_id = :fileId")
    suspend fun deleteByFileId(fileId: Long)

    @Query("SELECT * FROM project_embeddings WHERE id = :id")
    suspend fun getById(id: Long): ProjectEmbeddingEntity?

    @Query(
        "SELECT pe.file_id AS file_id, pf.file_name AS file_name, pe.page AS page, " +
            "pe.chunk_text AS chunk_text, pe.embedding_blob AS embedding_blob " +
            "FROM project_embeddings pe " +
            "INNER JOIN project_files pf ON pf.id = pe.file_id " +
            "WHERE pe.project_id = :projectId AND pf.status = 'ready' " +
            "ORDER BY pe.id ASC",
    )
    suspend fun allByProjectAndReadyFiles(projectId: Long): List<EmbeddingRow>
}
