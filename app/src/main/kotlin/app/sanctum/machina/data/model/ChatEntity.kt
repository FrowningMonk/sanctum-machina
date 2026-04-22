package app.sanctum.machina.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chats",
    indices = [Index(value = ["last_message_at"])]
)
data class ChatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "project_id")
    val projectId: Long? = null,

    @ColumnInfo(name = "model_id")
    val modelId: String,

    val title: String? = null,

    @ColumnInfo(name = "is_manually_titled", defaultValue = "0")
    val isManuallyTitled: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "last_message_at")
    val lastMessageAt: Long,
)
