package app.sanctum.machina.data

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

/**
 * Default [ChatRepository]. Hilt-bound via `AppModule` (Task 5) as `@Singleton`.
 *
 * Two-step `commitDraftChat` atomicity:
 *   1. `database.withTransaction { … }` covers cross-DAO INSERT (chat + first
 *      message) — Room rolls back automatically on any throw inside the block.
 *   2. After the transaction commits, the staging directory is renamed to its
 *      final `filesDir/attachments/{chatId}/`. If that rename returns `false`
 *      we treat it as `IOException`, delete the just-inserted Room row, and
 *      `deleteRecursively` the staging directory (Decision 6).
 *
 * Test seams (`ioDispatcher`, `rename`, `transactionRunner`) keep the class
 * unit-testable without an in-memory Room instance — production wiring uses
 * `Dispatchers.IO`, `File::renameTo`, and `database::withTransaction`.
 */
class DefaultChatRepository internal constructor(
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
  ): Long = withContext(ioDispatcher) {
    // Pre-validate staging BEFORE INSERT (AC-A4): if the caller's staging
    // step did not produce a directory, we must not create a half-committed
    // chat with no on-disk attachments.
    if (stagingDir != null && !stagingDir.isDirectory) {
      throw IOException("staging directory missing: ${stagingDir.absolutePath}")
    }

    val title = AutoTitleGenerator.generateTitle(
      firstUserText = if (firstMessage.role == "user") firstMessage.text else null,
      createdAtMs = firstMessage.createdAt,
    )
    val chat = ChatEntity(
      modelId = modelId,
      title = title,
      isManuallyTitled = 0,
      createdAt = firstMessage.createdAt,
      lastMessageAt = firstMessage.createdAt,
    )

    val chatId = transactionRunner {
      val id = chatDao.insert(chat)
      messageDao.insert(firstMessage.copy(chatId = id))
      id
    }

    if (stagingDir != null) {
      // Anchor the final dir off the staging dir's parent (which is
      // `filesDir/attachments/`) — the caller already chose this root.
      val attachmentsRoot = stagingDir.parentFile
        ?: throw IOException("staging dir has no parent: ${stagingDir.absolutePath}")
      attachmentsRoot.mkdirs()
      val finalDir = File(attachmentsRoot, chatId.toString())
      val ok = rename(stagingDir, finalDir)
      if (!ok) {
        // Roll back Room + clean staging (Decision 6).
        chatDao.deleteById(chatId)
        stagingDir.deleteRecursively()
        throw IOException(
          "rename failed: ${stagingDir.absolutePath} -> ${finalDir.absolutePath}",
        )
      }
    }

    chatId
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
      // deleteRecursively returns false on a missing root — that's fine; chats
      // without attachments are normal.
      File(filesDir, "$ATTACHMENTS_DIR/$chatId").deleteRecursively()
    }
  }

  override fun observeChats(): Flow<List<ChatEntity>> = chatDao.observeAll()

  override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> =
    messageDao.observeByChat(chatId)

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
