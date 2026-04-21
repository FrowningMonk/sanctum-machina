package app.sanctum.machina.data

import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Data-layer facade over `chats` / `messages` Room tables and the on-disk
 * attachments tree at `filesDir/attachments/{chatId}/`.
 *
 * Phase 3 contract — see tech-spec § Architecture / Decisions 4 & 6.
 *
 * `commitDraftChat` is the only multi-step operation: a cross-DAO Room
 * transaction (INSERT chat + first message) followed by a directory rename
 * from staging to the final chat folder. Callers stage attachments first.
 *
 * All file I/O runs on `Dispatchers.IO` (AC-A1).
 */
interface ChatRepository {

  /**
   * Atomically promote a draft to a persistent chat. Inserts a [ChatEntity]
   * (with auto-generated title) plus the user's first [firstMessage] inside a
   * single Room transaction, then renames [stagingDir] (if non-null) to
   * `filesDir/attachments/{chatId}/`.
   *
   * On rename failure the Room row is rolled back via `deleteById` and the
   * staging directory is removed (Decision 6, AC-A6).
   *
   * @return the newly inserted chat id.
   * @throws java.io.IOException when [stagingDir] is provided but is not a
   *   directory, or when the rename to the final attachments folder fails.
   */
  suspend fun commitDraftChat(
    modelId: String,
    firstMessage: MessageEntity,
    stagingDir: File?,
  ): Long

  /** Insert a single message (USER on send, ASSISTANT on `done=true`). */
  suspend fun savePersistentMessage(message: MessageEntity)

  /** Bump the chat's `last_message_at` timestamp after a committed message. */
  suspend fun updateChatLastMessage(chatId: Long, timestampMs: Long)

  /**
   * Rename a chat. Pass `isManuallyTitled = true` when the user changed it,
   * `false` when resetting back to auto-title.
   */
  suspend fun updateChatTitle(chatId: Long, title: String, isManuallyTitled: Boolean)

  /**
   * Delete the chat row (CASCADE removes messages) and recursively delete
   * `filesDir/attachments/{chatId}/`. A missing attachments directory is not
   * an error (AC-A2 / Edge cases).
   */
  suspend fun deleteChat(chatId: Long, filesDir: File)

  /** Latest snapshot of all chats sorted by `last_message_at DESC`. */
  fun observeChats(): Flow<List<ChatEntity>>

  /** Messages in [chatId] sorted by `created_at ASC`. */
  fun observeMessages(chatId: Long): Flow<List<MessageEntity>>

  /**
   * Remove "zombie" chats: rows with zero messages AND no attachments
   * directory. These can appear if `commitDraftChat` was killed between Room
   * INSERT and the staging rename — the row exists, but there is no content
   * to surface (AC-P8). Each removal is logged via `ErrorLog.e("history-write", ...)`.
   */
  suspend fun sweepZombieChats(filesDir: File)
}
