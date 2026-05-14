package app.sanctum.machina.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "project_files",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["project_id"]),
        Index(value = ["project_id", "content_hash"], unique = true),
    ],
)
data class ProjectFileEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "project_id")
    val projectId: Long,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "relative_path")
    val relativePath: String,

    @ColumnInfo(name = "content_hash")
    val contentHash: String,

    val status: String,

    @ColumnInfo(name = "status_message")
    val statusMessage: String? = null,

    @ColumnInfo(name = "chunk_count")
    val chunkCount: Int? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
