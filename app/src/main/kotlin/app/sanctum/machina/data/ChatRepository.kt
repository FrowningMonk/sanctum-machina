package app.sanctum.machina.data

import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.ui.chat.Attachment
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Result of [ChatRepository.savePersistentAttachment]. Exactly one of
 * [imagePath] / [audioPath] is non-null, mirroring the [MessageEntity] schema
 * (a single `messages` row carries at most one image path and one audio path).
 */
data class PersistedAttachment(
  val imagePath: String? = null,
  val audioPath: String? = null,
)

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
   * (with auto-generated title) and the user's first [firstMessage] AND
   * renames [stagingDir] (if non-null) to `filesDir/attachments/{chatId}/`,
   * all inside a single Room transaction. Rename failure throws inside the
   * transaction → Room rolls back both INSERTs; the staging directory is
   * then removed by the outer catch (Decision 6, AC-A6).
   *
   * [filesDir] is the canonical app private storage root. When [stagingDir]
   * is non-null it MUST live under `filesDir/attachments/`; otherwise the
   * call throws `IOException` before any Room work.
   *
   * @return the newly inserted chat id.
   * @throws java.io.IOException when [stagingDir] is outside the attachments
   *   root, missing, not a directory, or when the rename to the final
   *   attachments folder fails.
   */
  suspend fun commitDraftChat(
    modelId: String,
    firstMessage: MessageEntity,
    stagingDir: File?,
    filesDir: File,
    stagedImageFilename: String? = null,
    stagedAudioFilename: String? = null,
  ): Long

  /**
   * Serialize [attachment] into [stagingDir], creating the directory lazily
   * if missing. Returns the filename (relative to [stagingDir]) under which
   * the payload was stored. Caller must remember [stagingDir] so it can be
   * promoted to `filesDir/attachments/{chatId}/` by [commitDraftChat].
   *
   * [stagingDir] MUST live under `filesDir/attachments/` — the same
   * containment check that [commitDraftChat] enforces (Decision 6, T4-S1).
   * Partial writes on failure are rolled back (the half-written file is
   * deleted) before the `IOException` is rethrown.
   *
   * Filenames are `img_{N}.png` / `audio_{N}.wav` where N is the next index
   * in the directory — predictable for log/diagnostic reading.
   */
  suspend fun writeAttachmentStaging(
    stagingDir: File,
    attachment: Attachment,
  ): String

  /**
   * Direct write for already-committed Persistent chats. Writes into
   * `filesDir/attachments/{chatId}/` (creating the directory if needed) on
   * `Dispatchers.IO` and returns the relative path in [PersistedAttachment].
   * Exactly one of [PersistedAttachment.imagePath] / [PersistedAttachment.audioPath]
   * is populated per call — callers place the value on [MessageEntity.imagePath]
   * or [MessageEntity.audioPath] accordingly.
   *
   * Partial-fail is not critical here: `chatId` is stable, orphan files are
   * cleaned up by [deleteChat] when the row is removed. Callers still
   * surface write failure to the user (hard-gate the USER persist on
   * `IOException` — same pattern as [savePersistentMessage]).
   */
  suspend fun savePersistentAttachment(
    chatId: Long,
    filesDir: File,
    attachment: Attachment,
  ): PersistedAttachment

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
