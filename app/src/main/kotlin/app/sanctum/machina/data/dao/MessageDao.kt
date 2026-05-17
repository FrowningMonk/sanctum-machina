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

    /**
     * Latest message in [chatId] by `created_at DESC, id DESC`. Used by
     * Persistent-mode VM bootstrap (Task 18 B4) to detect an unpaired USER
     * row left behind when Draft→Persistent handover committed the first send
     * but the inference dispatch never reached the new VM instance.
     *
     * Secondary sort by `id DESC` is the tie-breaker for two messages
     * committed inside the same millisecond (drafts typically ship a USER
     * row and the ASSISTANT response close together on fast engines).
     */
    @Query(
        "SELECT * FROM messages WHERE chat_id = :chatId " +
            "ORDER BY created_at DESC, id DESC LIMIT 1",
    )
    suspend fun lastByChat(chatId: Long): MessageEntity?

    /**
     * Paged snapshot read of messages with non-null `citations` belonging to chats
     * inside [projectId] (JOIN through `chats.project_id`). Consumed by
     * `DefaultProjectRepository.deleteFile` (Decision 8) which iterates batches of
     * 50, decodes JSON, marks matching entries as stale, and writes back via
     * [updateCitations] — keeps JVM heap bounded regardless of corpus size.
     */
    @Query(
        "SELECT m.* FROM messages m " +
            "INNER JOIN chats c ON c.id = m.chat_id " +
            "WHERE c.project_id = :projectId AND m.citations IS NOT NULL " +
            "ORDER BY m.id ASC LIMIT :limit OFFSET :offset",
    )
    suspend fun observeCitedMessagesPageByProject(
        projectId: Long,
        offset: Int,
        limit: Int,
    ): List<MessageEntity>

    @Query("UPDATE messages SET citations = :citationsJson WHERE id = :messageId")
    suspend fun updateCitations(messageId: Long, citationsJson: String?)
}
