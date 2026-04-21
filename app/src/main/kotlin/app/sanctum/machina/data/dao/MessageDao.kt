package app.sanctum.machina.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import app.sanctum.machina.data.model.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC")
    suspend fun getByChatId(chatId: Long): List<MessageEntity>

    /**
     * Earliest message in [chatId] filtered by [role] (e.g. `"user"`). Used by
     * rename-blank path to find the first USER message and regenerate the
     * auto-title without loading the full history.
     */
    @Query(
        "SELECT * FROM messages WHERE chat_id = :chatId AND role = :role " +
            "ORDER BY created_at ASC LIMIT 1",
    )
    suspend fun firstByChatIdAndRole(chatId: Long, role: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chat_id = :chatId ORDER BY created_at ASC")
    fun observeByChat(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE chat_id = :chatId")
    suspend fun countByChatId(chatId: Long): Int
}
