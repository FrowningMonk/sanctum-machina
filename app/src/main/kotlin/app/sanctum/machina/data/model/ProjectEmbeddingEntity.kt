package app.sanctum.machina.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "project_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ProjectFileEntity::class,
            parentColumns = ["id"],
            childColumns = ["file_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["project_id"]),
        Index(value = ["file_id"]),
    ],
)
data class ProjectEmbeddingEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "project_id")
    val projectId: Long,

    @ColumnInfo(name = "file_id")
    val fileId: Long,

    val page: Int? = null,

    @ColumnInfo(name = "chunk_text")
    val chunkText: String,

    @ColumnInfo(name = "embedding_blob")
    val embeddingBlob: ByteArray,
) {
    // Kotlin `data class` with a `ByteArray` field falls back to identity comparison
    // for that field. Override with content-equality so equality reflects the stored
    // bytes — the BLOB roundtrip test relies on this.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProjectEmbeddingEntity) return false
        return id == other.id &&
            projectId == other.projectId &&
            fileId == other.fileId &&
            page == other.page &&
            chunkText == other.chunkText &&
            embeddingBlob.contentEquals(other.embeddingBlob)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + projectId.hashCode()
        result = 31 * result + fileId.hashCode()
        result = 31 * result + (page ?: 0)
        result = 31 * result + chunkText.hashCode()
        result = 31 * result + embeddingBlob.contentHashCode()
        return result
    }
}
