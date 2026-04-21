package app.sanctum.machina.data

import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val ATTACHMENTS_DIR = "attachments"
private const val ERROR_COMPONENT = "history-write"
private const val ROLE_USER = "user"

/**
 * Default [ChatRepository]. Hilt-bound via `AppModule` (Task 5) as `@Singleton`.
 *
 * `commitDraftChat` atomicity (Decision 6, hardened after Task-4 review):
 *   - Cross-DAO INSERT (chat + first message) AND the staging-dir rename run
 *     together inside a single `database.withTransaction { … }`.
 *   - If the rename returns `false`, we throw inside the block — Room rolls
 *     back both INSERTs automatically. The outer try/catch then removes the
 *     staging directory and re-throws.
 *   - Folding rename into the transaction closes the kill-window where the
 *     spec's "delete the row in a second statement" rollback could leave an
 *     orphan chat+message after a process kill (security review T4-S2).
 *
 * Caller-supplied [filesDir] is the canonical app private storage root.
 * `commitDraftChat` verifies that any non-null staging dir lives inside
 * `filesDir/attachments/` before touching the filesystem (security T4-S1).
 *
 * Test seams (`ioDispatcher`, `rename`, `transactionRunner`) live on a
 * `@VisibleForTesting` primary constructor; production wiring goes through
 * the `@Inject` secondary below.
 */
class DefaultChatRepository
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal constructor(
  private val chatDao: ChatDao,
  private val messageDao: MessageDao,
  private val errorLog: ErrorLog,
  private val ioDispatcher: CoroutineDispatcher,
  private val rename: (File, File) -> Boolean,
  private val transactionRunner: suspend (suspend () -> Long) -> Long,
) : ChatRepository {

  @Inject
  constructor(
    database: SanctumDatabase,
    chatDao: ChatDao,
    messageDao: MessageDao,
    errorLog: ErrorLog,
  ) : this(
    chatDao = chatDao,
    messageDao = messageDao,
    errorLog = errorLog,
    ioDispatcher = Dispatchers.IO,
    rename = { src, dst -> src.renameTo(dst) },
    transactionRunner = { block -> database.withTransaction { block() } },
  )

  override suspend fun commitDraftChat(
    modelId: String,
    firstMessage: MessageEntity,
    stagingDir: File?,
    filesDir: File,
  ): Long = withContext(ioDispatcher) {
    val attachmentsRoot = File(filesDir, ATTACHMENTS_DIR)

    if (stagingDir != null) {
      // Containment check (security review T4-S1): caller must stage inside
      // `filesDir/attachments/`. Rejecting outside-tree paths before the
      // Room INSERT prevents an internal-API misuse from creating directories
      // outside the app's attachments root.
      val rootCanonical = attachmentsRoot.canonicalPath
      val stagingCanonical = stagingDir.canonicalPath
      if (!stagingCanonical.startsWith(rootCanonical + File.separator)) {
        throw IOException(
          "staging dir must live under attachments root: $stagingCanonical",
        )
      }
      if (!stagingDir.exists()) {
        throw IOException("staging dir missing: ${stagingDir.absolutePath}")
      }
      if (!stagingDir.isDirectory) {
        throw IOException("staging path is not a directory: ${stagingDir.absolutePath}")
      }
    }

    val title = AutoTitleGenerator.generateTitle(
      firstUserText = if (firstMessage.role == ROLE_USER) firstMessage.text else null,
      createdAtMs = firstMessage.createdAt,
    )
    val chat = ChatEntity(
      modelId = modelId,
      title = title,
      isManuallyTitled = 0,
      createdAt = firstMessage.createdAt,
      lastMessageAt = firstMessage.createdAt,
    )

    try {
      transactionRunner {
        val id = chatDao.insert(chat)
        messageDao.insert(firstMessage.copy(chatId = id))
        if (stagingDir != null) {
          val finalDir = File(attachmentsRoot, id.toString())
          if (!rename(stagingDir, finalDir)) {
            throw IOException(
              "rename failed: ${stagingDir.absolutePath} -> ${finalDir.absolutePath}",
            )
          }
        }
        id
      }
    } catch (t: Throwable) {
      // Room rolled back both INSERTs. Remove the staging dir if it survived
      // (rename never fired or failed early). `deleteRecursively` on a missing
      // path is a no-op.
      stagingDir?.deleteRecursively()
      throw t
    }
  }

  override suspend fun savePersistentMessage(message: MessageEntity) {
    withContext(ioDispatcher) { messageDao.insert(message) }
  }

  override suspend fun updateChatLastMessage(chatId: Long, timestampMs: Long) {
    withContext(ioDispatcher) {
      val current = chatDao.getById(chatId) ?: return@withContext
      chatDao.update(current.copy(lastMessageAt = timestampMs))
    }
  }

  override suspend fun updateChatTitle(
    chatId: Long,
    title: String,
    isManuallyTitled: Boolean,
  ) {
    withContext(ioDispatcher) {
      val current = chatDao.getById(chatId) ?: return@withContext
      chatDao.update(
        current.copy(
          title = title,
          isManuallyTitled = if (isManuallyTitled) 1 else 0,
        ),
      )
    }
  }

  override suspend fun deleteChat(chatId: Long, filesDir: File) {
    withContext(ioDispatcher) {
      chatDao.deleteById(chatId) // CASCADE removes message rows
      val dir = File(filesDir, "$ATTACHMENTS_DIR/$chatId")
      if (dir.exists() && !dir.deleteRecursively()) {
        // Row is gone but on-disk attachments survived — flag as observable
        // disk-orphan so it can be picked up in diagnostics (T4-m1).
        errorLog.e(
          ERROR_COMPONENT,
          "failed to remove attachments dir for chatId=$chatId path=${dir.absolutePath}",
        )
      }
    }
  }

  override fun observeChats(): Flow<List<ChatEntity>> = chatDao.observeAll()

  override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> =
    messageDao.observeByChat(chatId)

  /**
   * Single-shot startup sweep. Caller invariant: only run from
   * `SanctumApplication.onCreate` BEFORE any UI flow that could trigger
   * `commitDraftChat` (T4-S3) — there is no transactional barrier between the
   * snapshot read and the per-row delete here.
   *
   * `chat.modelId` is logged for diagnostics; today it is a HuggingFace
   * identifier (no PII). Keep it that way (T4-S4).
   */
  override suspend fun sweepZombieChats(filesDir: File) {
    withContext(ioDispatcher) {
      val attachmentsRoot = File(filesDir, ATTACHMENTS_DIR)
      // No suspend "all chats" getter exists in ChatDao; pull the current
      // snapshot from observeAll(). Room-backed flows emit immediately, so
      // first() returns without waiting on a DB write.
      val chats = chatDao.observeAll().first()
      for (chat in chats) {
        if (messageDao.countByChatId(chat.id) > 0) continue
        val dir = File(attachmentsRoot, chat.id.toString())
        if (dir.exists()) continue
        chatDao.deleteById(chat.id)
        errorLog.e(
          ERROR_COMPONENT,
          "zombie chat swept: id=${chat.id} model=${chat.modelId}",
        )
      }
    }
  }
}
