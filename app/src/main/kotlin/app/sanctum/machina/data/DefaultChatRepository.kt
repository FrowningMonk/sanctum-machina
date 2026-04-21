package app.sanctum.machina.data

import android.graphics.Bitmap
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import app.sanctum.machina.core.common.pcmToWav
import app.sanctum.machina.core.data.SAMPLE_RATE
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.ui.chat.Attachment
import java.io.File
import java.io.IOException
import java.util.UUID
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
    stagedImageFilename: String?,
    stagedAudioFilename: String?,
  ): Long = withContext(ioDispatcher) {
    val attachmentsRoot = File(filesDir, ATTACHMENTS_DIR)

    if (stagingDir != null) {
      // Containment check (security review T4-S1): caller must stage inside
      // `filesDir/attachments/`. Rejecting outside-tree paths before the
      // Room INSERT prevents an internal-API misuse from creating directories
      // outside the app's attachments root.
      requireInsideAttachmentsRoot(stagingDir, filesDir)
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
        // Rewrite relative paths from "staging" to final `{chatId}` inside the
        // transaction — the chat id is only known here, and we want Room row
        // and on-disk state agreed at commit time (Decision 6).
        val imagePath = stagedImageFilename?.let { "$ATTACHMENTS_DIR/$id/$it" }
        val audioPath = stagedAudioFilename?.let { "$ATTACHMENTS_DIR/$id/$it" }
        messageDao.insert(
          firstMessage.copy(
            chatId = id,
            imagePath = imagePath ?: firstMessage.imagePath,
            audioPath = audioPath ?: firstMessage.audioPath,
          ),
        )
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

  override suspend fun writeAttachmentStaging(
    stagingDir: File,
    filesDir: File,
    attachment: Attachment,
  ): String = withContext(ioDispatcher) {
    // Accept `filesDir` explicitly (code-reviewer-1 T17-R3 / security T17-S1):
    // deriving it from `stagingDir.parentFile?.parentFile` gave a tautological
    // containment check — the check compared stagingDir against a root built
    // from its own ancestry, so a path outside the real app storage could
    // never be rejected.
    requireInsideAttachmentsRoot(stagingDir, filesDir)

    if (!stagingDir.exists() && !stagingDir.mkdirs()) {
      throw IOException("failed to create staging dir: ${stagingDir.absolutePath}")
    }
    val filename = collisionFreeFilename(attachment)
    val target = File(stagingDir, filename)
    writeAttachmentPayload(attachment, target)
    filename
  }

  override suspend fun deleteStagedAttachment(
    stagingDir: File,
    filesDir: File,
    filename: String,
  ) = withContext(ioDispatcher) {
    requireInsideAttachmentsRoot(stagingDir, filesDir)
    val target = File(stagingDir, filename)
    if (target.exists() && !target.delete()) {
      errorLog.e(
        "attachment-save",
        "deleteStagedAttachment: delete failed for ${target.absolutePath}",
      )
    }
  }

  override suspend fun pruneStagingDir(
    stagingDir: File,
    filesDir: File,
    retain: Set<String>,
  ) = withContext(ioDispatcher) {
    requireInsideAttachmentsRoot(stagingDir, filesDir)
    stagingDir.listFiles()?.forEach { file ->
      if (file.name in retain) return@forEach
      if (!file.delete()) {
        errorLog.e(
          "attachment-save",
          "pruneStagingDir: failed to delete ${file.absolutePath}",
        )
      }
    }
    Unit
  }

  override suspend fun savePersistentAttachment(
    chatId: Long,
    filesDir: File,
    attachment: Attachment,
  ): PersistedAttachment = withContext(ioDispatcher) {
    val chatDir = File(filesDir, "$ATTACHMENTS_DIR/$chatId")
    // Defence in depth (test-reviewer T3 / security T17-S1 parity): the chat
    // dir is constructed from our own `ATTACHMENTS_DIR` constant, but a
    // misconfigured `filesDir` (e.g. a symlink an attacker has arranged to
    // point outside the app's private storage) would otherwise slip past.
    requireInsideAttachmentsRoot(chatDir, filesDir)
    if (!chatDir.exists() && !chatDir.mkdirs()) {
      throw IOException("failed to create attachments dir: ${chatDir.absolutePath}")
    }
    val filename = collisionFreeFilename(attachment)
    val target = File(chatDir, filename)
    writeAttachmentPayload(attachment, target)
    val relative = "$ATTACHMENTS_DIR/$chatId/$filename"
    when (attachment) {
      is Attachment.Image -> PersistedAttachment(imagePath = relative)
      is Attachment.Audio -> PersistedAttachment(audioPath = relative)
    }
  }

  /**
   * Common write: PNG for Image (lossless — Phase 3 prioritises fidelity over
   * size; cf. user-spec Risk #5), RIFF/WAVE for Audio (via [pcmToWav] so the
   * file is self-contained and playable). Partial files are deleted on write
   * failure — the caller sees a clean `IOException` without an orphan stub.
   *
   * A delete-fail in the catch is logged under `attachment-save` (security
   * T17-S3) — the write path's original exception is preserved as the thrown
   * cause so the caller's retry/error logic still runs.
   */
  private suspend fun writeAttachmentPayload(attachment: Attachment, target: File) {
    try {
      when (attachment) {
        is Attachment.Image -> target.outputStream().use { out ->
          if (!attachment.bitmap.compress(Bitmap.CompressFormat.PNG, /* quality = */ 100, out)) {
            throw IOException("Bitmap.compress returned false: ${target.absolutePath}")
          }
        }
        is Attachment.Audio -> target.writeBytes(pcmToWav(attachment.pcm, SAMPLE_RATE))
      }
    } catch (t: Throwable) {
      // Clean up the partial file so the next write does not see a half
      // payload. UUID-based filenames make collisions a non-issue, but a
      // zero-byte stub on disk would still waste space and confuse log
      // readers.
      if (target.exists() && !target.delete()) {
        errorLog.e(
          "attachment-save",
          "writeAttachmentPayload: delete of partial file failed: ${target.absolutePath}",
        )
      }
      throw t
    }
  }

  /**
   * Collision-free per-attachment filename. UUID suffix removes the
   * `listFiles().size` TOCTOU race that the previous index-based scheme had
   * (code-reviewer-1 T17-R1 / security T17-S2 / T17-S5). Stable prefix
   * (`img` / `audio`) and extension keep filenames searchable in logs and
   * diagnostic exports.
   */
  private fun collisionFreeFilename(attachment: Attachment): String {
    val uuid = UUID.randomUUID().toString()
    return when (attachment) {
      is Attachment.Image -> "img_$uuid.png"
      is Attachment.Audio -> "audio_$uuid.wav"
    }
  }

  private fun requireInsideAttachmentsRoot(candidate: File, filesDir: File) {
    val attachmentsRoot = File(filesDir, ATTACHMENTS_DIR).canonicalPath
    val candidateCanonical = candidate.canonicalPath
    if (!candidateCanonical.startsWith(attachmentsRoot + File.separator)) {
      throw IOException(
        "path must live under attachments root: $candidateCanonical",
      )
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
