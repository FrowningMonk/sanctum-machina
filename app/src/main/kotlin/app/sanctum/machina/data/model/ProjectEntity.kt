package app.sanctum.machina.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    val name: String,

    @ColumnInfo(name = "default_model_id")
    val defaultModelId: String? = null,

    @ColumnInfo(name = "rag_overrides_json")
    val ragOverridesJson: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
