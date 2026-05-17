package app.sanctum.machina.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chat_id"]),
        Index(value = ["created_at"]),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "chat_id")
    val chatId: Long,

    val role: String,

    @ColumnInfo(defaultValue = "")
    val text: String = "",

    @ColumnInfo(name = "thinking_text")
    val thinkingText: String? = null,

    @ColumnInfo(name = "image_path")
    val imagePath: String? = null,

    @ColumnInfo(name = "audio_path")
    val audioPath: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "token_count")
    val tokenCount: Int? = null,

    @ColumnInfo(name = "citations")
    val citations: String? = null,
)
