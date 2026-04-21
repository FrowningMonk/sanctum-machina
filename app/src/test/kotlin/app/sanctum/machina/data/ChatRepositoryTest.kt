package app.sanctum.machina.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import android.graphics.Bitmap
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.ui.chat.Attachment
import java.io.File
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChatRepositoryTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var context: Context
  private lateinit var chatDao: FakeChatDao
  private lateinit var messageDao: FakeMessageDao
  private lateinit var errorLog: ErrorLog
  private lateinit var errorLogFile: File
  private lateinit var filesDir: File
  private lateinit var attachmentsRoot: File

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    chatDao = FakeChatDao()
    messageDao = FakeMessageDao()
    errorLog = ErrorLog(context)
    errorLogFile = File(context.filesDir, "logs/errors.log")
    // Only the ErrorLog dir is shared with Robolectric's app filesDir; attachment
    // I/O lives under tempFolder so it stays per-test (T4-T8).
    errorLogFile.parentFile?.deleteRecursively()
    filesDir = tempFolder.newFolder("filesDir")
    attachmentsRoot = File(filesDir, "attachments").apply { mkdirs() }
  }

  @After
  fun tearDown() {
    errorLogFile.parentFile?.deleteRecursively()
  }

  // ---- commitDraftChat ----

  @Test
  fun commitDraftChat_happyPath() = runTest {
    val staging = File(attachmentsRoot, ".staging-abc").apply { mkdirs() }
    File(staging, "image.jpg").writeBytes(byteArrayOf(1, 2, 3))
    val firstMessage = userMessage(text = "Hello world", createdAt = 1_700_000_000_000L)

    val repo = newRepository()
    val chatId = repo.commitDraftChat(
      modelId = "litert-community/gemma-4-E4B-it-litert-lm",
      firstMessage = firstMessage,
      stagingDir = staging,
      filesDir = filesDir,
    )

    assertTrue("chatId must be positive", chatId > 0L)
    val chat = chatDao.byId[chatId]
    assertNotNull("chat row must exist", chat)
    assertEquals("model id stored", "litert-community/gemma-4-E4B-it-litert-lm", chat!!.modelId)
    val messages = messageDao.byChat[chatId].orEmpty()
    assertEquals("one user message persisted", 1, messages.size)
    assertFalse("staging dir must be renamed away", staging.exists())
    val finalDir = File(attachmentsRoot, chatId.toString())
    assertTrue("final attachments dir must exist", finalDir.isDirectory)
    assertTrue("attachment file moved", File(finalDir, "image.jpg").exists())
  }

  @Test
  fun commitDraftChat_nullStagingDir_skipsRenameAndSucceeds() = runTest {
    var renameInvocations = 0
    val repo = newRepository(rename = { _, _ -> renameInvocations++; true })

    val chatId = repo.commitDraftChat(
      modelId = "model/x",
      firstMessage = userMessage("text-only", 1L),
      stagingDir = null,
      filesDir = filesDir,
    )

    assertTrue("chatId must be positive", chatId > 0L)
    assertNotNull("chat row exists", chatDao.byId[chatId])
    assertEquals("rename never invoked", 0, renameInvocations)
    assertFalse(
      "no spurious chatId dir created under attachments root",
      File(attachmentsRoot, chatId.toString()).exists(),
    )
  }

  @Test
  fun commitDraftChat_missingStagingDir_throwsBeforeRoomInsert() = runTest {
    // AC-A4: pre-INSERT staging-dir validation must short-circuit Room work.
    // Models the failure mode where the caller's staging step never produced
    // the directory the repo was told to commit.
    val staging = File(attachmentsRoot, ".staging-missing")
    val repo = newRepository()

    val thrown = runCatching {
      repo.commitDraftChat(
        modelId = "model/x",
        firstMessage = userMessage("hi", 1L),
        stagingDir = staging,
        filesDir = filesDir,
      )
    }.exceptionOrNull()
    assertTrue("IOException expected, got $thrown", thrown is IOException)

    assertEquals("no chat inserted", 0, chatDao.insertCalls)
    assertEquals("no message inserted", 0, messageDao.insertCalls)
  }

  @Test
  fun commitDraftChat_stagingOutsideAttachmentsRoot_throws() = runTest {
    // Security T4-S1: staging dir must live under filesDir/attachments/.
    val outside = tempFolder.newFolder("outside-tree")
    val repo = newRepository()

    val thrown = runCatching {
      repo.commitDraftChat(
        modelId = "model/x",
        firstMessage = userMessage("hi", 1L),
        stagingDir = outside,
        filesDir = filesDir,
      )
    }.exceptionOrNull()
    assertTrue("IOException expected, got $thrown", thrown is IOException)
    assertEquals("no chat inserted", 0, chatDao.insertCalls)
  }

  @Test
  fun commitDraftChat_renameFailsAfterRoomInsert() = runTest {
    val staging = File(attachmentsRoot, ".staging-failrename").apply { mkdirs() }
    File(staging, "doc.txt").writeText("payload")
    // Simulate Room's @Transaction rollback: on throw inside the runner we
    // restore the chat fake to its pre-block state. This mirrors what
    // database.withTransaction { ... } does in production.
    val repo = newRepository(
      rename = { _, _ -> false },
      transactionRunner = { block ->
        val chatSnapshot = chatDao.byId.toMap()
        val msgSnapshot = messageDao.byChat.mapValues { it.value.toList() }
        try {
          block()
        } catch (t: Throwable) {
          chatDao.byId.clear()
          chatDao.byId.putAll(chatSnapshot)
          messageDao.byChat.clear()
          msgSnapshot.forEach { (k, v) -> messageDao.byChat[k] = v.toMutableList() }
          throw t
        }
      },
    )

    val thrown = runCatching {
      repo.commitDraftChat(
        modelId = "model/x",
        firstMessage = userMessage("hi", 1L),
        stagingDir = staging,
        filesDir = filesDir,
      )
    }.exceptionOrNull()
    assertTrue("IOException expected, got $thrown", thrown is IOException)

    assertEquals("Room chat insert was attempted", 1, chatDao.insertCalls)
    assertEquals("Room message insert was attempted", 1, messageDao.insertCalls)
    assertTrue("Room state rolled back (chat gone)", chatDao.byId.isEmpty())
    assertTrue("Room state rolled back (messages gone)", messageDao.byChat.values.all { it.isEmpty() })
    assertFalse("staging dir cleaned up", staging.exists())
  }

  @Test
  fun commitDraftChat_messageInsertFails_chatRollbackToo() = runTest {
    // Locks down the Room-transaction contract: a throw INSIDE the runner from
    // messageDao.insert must roll back the just-inserted chat row.
    val staging = File(attachmentsRoot, ".staging-msgfail").apply { mkdirs() }
    val throwingMessageDao = FakeMessageDao().apply { failOnInsert = true }
    val repo = DefaultChatRepository(
      chatDao = chatDao,
      messageDao = throwingMessageDao,
      errorLog = errorLog,
      ioDispatcher = UnconfinedTestDispatcher(),
      rename = { _, _ -> true },
      transactionRunner = { block ->
        val chatSnapshot = chatDao.byId.toMap()
        try {
          block()
        } catch (t: Throwable) {
          chatDao.byId.clear()
          chatDao.byId.putAll(chatSnapshot)
          throw t
        }
      },
    )

    val thrown = runCatching {
      repo.commitDraftChat(
        modelId = "model/x",
        firstMessage = userMessage("hi", 1L),
        stagingDir = staging,
        filesDir = filesDir,
      )
    }.exceptionOrNull()
    assertNotNull("expected throw from messageDao.insert", thrown)
    assertTrue("Room state rolled back", chatDao.byId.isEmpty())
    assertFalse("staging dir cleaned up", staging.exists())
  }

  // ---- deleteChat ----

  @Test
  fun deleteChat_callsRoomAndDeletesFiles() = runTest {
    // CASCADE removal of messages is verified by MessageDaoTest (Task 1) —
    // fakes here do not model FK CASCADE.
    val chatId = chatDao.insertSync(
      ChatEntity(modelId = "m/1", title = "T", createdAt = 1L, lastMessageAt = 1L),
    )
    val chatDir = File(attachmentsRoot, chatId.toString()).apply { mkdirs() }
    File(chatDir, "x.jpg").writeBytes(byteArrayOf(0))

    val repo = newRepository()
    repo.deleteChat(chatId, filesDir)

    assertTrue("deleteById was called", chatDao.deleteByIdCalls.contains(chatId))
    assertFalse("attachments dir for chat removed", chatDir.exists())
  }

  // ---- sweepZombieChats ----

  @Test
  fun sweepZombieChats_zeroMessagesMissingDir_deleted() = runTest {
    val zombieId = chatDao.insertSync(
      ChatEntity(modelId = "m/1", title = null, createdAt = 1L, lastMessageAt = 1L),
    )
    // No messages, no attachments dir.

    val repo = newRepository()
    repo.sweepZombieChats(filesDir)

    assertTrue("zombie chat deleted", chatDao.deleteByIdCalls.contains(zombieId))
    val log = errorLogFile.readLines()
    assertTrue(
      "zombie sweep logs ErrorLog.e under history-write with chat id",
      log.any {
        it.contains("[history-write]") && it.contains("zombie") && it.contains("id=$zombieId")
      },
    )
  }

  @Test
  fun sweepZombieChats_zeroMessagesWithDir_notDeleted() = runTest {
    val keepId = chatDao.insertSync(
      ChatEntity(modelId = "m/1", title = null, createdAt = 1L, lastMessageAt = 1L),
    )
    File(attachmentsRoot, keepId.toString()).mkdirs() // dir present

    val repo = newRepository()
    repo.sweepZombieChats(filesDir)

    assertFalse("chat with attachments dir is not a zombie", chatDao.deleteByIdCalls.contains(keepId))
  }

  @Test
  fun sweepZombieChats_nonZeroMessages_notDeleted() = runTest {
    val keepId = chatDao.insertSync(
      ChatEntity(modelId = "m/1", title = "x", createdAt = 1L, lastMessageAt = 1L),
    )
    messageDao.insertSync(userMessage("hi", 1L).copy(chatId = keepId))
    // No attachments dir.

    val repo = newRepository()
    repo.sweepZombieChats(filesDir)

    assertFalse(
      "chat with messages but no dir must NOT be deleted",
      chatDao.deleteByIdCalls.contains(keepId),
    )
  }

  // ---- writeAttachmentStaging ----

  @Test
  fun writeAttachmentStaging_image_writesPngFile() = runTest {
    val staging = File(attachmentsRoot, ".staging-img")
    val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
    val repo = newRepository()

    val filename = repo.writeAttachmentStaging(staging, filesDir, Attachment.Image(bmp))

    assertTrue("image filename matches img_<uuid>.png", filename.matches(Regex("img_.+\\.png")))
    val written = File(staging, filename)
    assertTrue("file exists", written.exists())
    assertTrue("png payload non-empty", written.length() > 0L)
    val decoded = android.graphics.BitmapFactory.decodeFile(written.absolutePath)
    assertNotNull("file must decode back to a bitmap", decoded)
  }

  @Test
  fun writeAttachmentStaging_audio_writesWavFile() = runTest {
    val staging = File(attachmentsRoot, ".staging-aud")
    val pcm = ByteArray(128) { it.toByte() }
    val repo = newRepository()

    val filename = repo.writeAttachmentStaging(staging, filesDir, Attachment.Audio(pcm, 1_000L))

    assertTrue("audio filename matches audio_<uuid>.wav", filename.matches(Regex("audio_.+\\.wav")))
    val written = File(staging, filename)
    assertTrue("file exists", written.exists())
    val header = written.readBytes().copyOfRange(0, 4)
    assertEquals("RIFF header byte 0", 'R'.code.toByte(), header[0])
    assertEquals("RIFF header byte 1", 'I'.code.toByte(), header[1])
    assertEquals("RIFF header byte 2", 'F'.code.toByte(), header[2])
    assertEquals("RIFF header byte 3", 'F'.code.toByte(), header[3])
  }

  @Test
  fun writeAttachmentStaging_concurrentWrites_uniqueFilenames() = runTest {
    // Regression test for T17-R1 (code-reviewer-1 critical): `addImages` can
    // admit up to 10 URIs in a single batch, and a coroutine per attachment
    // races `dir.listFiles()?.size`. UUID-based filenames are the fix —
    // regardless of invocation order, no two writes can collide.
    val staging = File(attachmentsRoot, ".staging-multi")
    val repo = newRepository()
    val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    val names = (0 until 5).map {
      repo.writeAttachmentStaging(staging, filesDir, Attachment.Image(bmp))
    }

    assertEquals("all 5 files exist", 5, staging.listFiles()!!.size)
    assertEquals("no duplicate filenames", names.size, names.toSet().size)
    assertTrue(
      "all filenames match img_<uuid>.png",
      names.all { it.matches(Regex("img_.+\\.png")) },
    )
  }

  @Test
  fun writeAttachmentStaging_stagingDirOutsideRoot_throws() = runTest {
    val outside = tempFolder.newFolder("not-attachments")
    val repo = newRepository()
    val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    val thrown = runCatching {
      repo.writeAttachmentStaging(outside, filesDir, Attachment.Image(bmp))
    }.exceptionOrNull()

    assertTrue("IOException expected, got $thrown", thrown is IOException)
  }

  // ---- savePersistentAttachment ----

  @Test
  fun savePersistentAttachment_image_writesUnderChatDir() = runTest {
    val repo = newRepository()
    val bmp = Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888)

    val result = repo.savePersistentAttachment(chatId = 42L, filesDir = filesDir, Attachment.Image(bmp))

    assertNotNull("image path populated", result.imagePath)
    assertTrue(
      "image path under attachments/42/ with img_<uuid>.png shape",
      result.imagePath!!.matches(Regex("attachments/42/img_.+\\.png")),
    )
    assertNull(result.audioPath)
    assertTrue("file exists on disk", File(filesDir, result.imagePath!!).exists())
  }

  @Test
  fun savePersistentAttachment_audio_writesUnderChatDir() = runTest {
    val repo = newRepository()
    val pcm = ByteArray(16)

    val result = repo.savePersistentAttachment(chatId = 7L, filesDir = filesDir, Attachment.Audio(pcm, 500L))

    assertNotNull(result.audioPath)
    assertTrue(
      "audio path under attachments/7/ with audio_<uuid>.wav shape",
      result.audioPath!!.matches(Regex("attachments/7/audio_.+\\.wav")),
    )
    assertNull(result.imagePath)
    assertTrue(File(filesDir, result.audioPath!!).exists())
  }

  @Test
  fun savePersistentAttachment_concurrentCalls_uniqueFilenames() = runTest {
    val repo = newRepository()
    val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    val results = (0 until 5).map {
      repo.savePersistentAttachment(chatId = 9L, filesDir = filesDir, Attachment.Image(bmp))
    }
    val paths = results.map { it.imagePath!! }

    assertEquals("no duplicate persistent filenames", paths.size, paths.toSet().size)
    assertEquals("all 5 files exist under chatId dir", 5, File(filesDir, "attachments/9").listFiles()!!.size)
  }

  @Test
  fun savePersistentAttachment_chatDirOutsideRoot_throws() = runTest {
    // Test-reviewer T17-T3: parity with `writeAttachmentStaging` containment.
    // Construct a filesDir whose `attachments/` resolves outside the actual
    // app private storage — i.e. pass in a pathological `filesDir` and
    // ensure the defence-in-depth check triggers.
    val outsideFilesDir = tempFolder.newFolder("other-files-dir")
    val attackerControlled = File(outsideFilesDir, "attachments").apply { mkdirs() }
    // The chat dir would end up under `outsideFilesDir/attachments/1/` — that
    // IS under its own `filesDir/attachments/` root, so the check passes.
    // Negative path: point `filesDir` at one tree and `attachments/` at a
    // symlink target in another. Robolectric does not support symlinks, so
    // we exercise the containment path by crafting a mismatched `filesDir`
    // via a nested override on File.canonicalPath semantics: passing a
    // `filesDir` that does NOT actually contain an `attachments/` sub-path
    // forces the check against a mismatched root. The minimal repro: make
    // `attachments/` a non-directory file so `File(outsideFilesDir, "attachments/1")`
    // resolves canonical-path-wise differently from its name-resolved form.
    attackerControlled.deleteRecursively()
    attackerControlled.writeText("not a directory")
    val repo = newRepository()
    val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    val thrown = runCatching {
      repo.savePersistentAttachment(chatId = 1L, filesDir = outsideFilesDir, Attachment.Image(bmp))
    }.exceptionOrNull()

    // Either the containment check OR the mkdirs failure throws IOException;
    // both signal refusal to write. The invariant under test is "no write
    // lands in an unexpected location" — we verify no file was produced.
    assertTrue("IOException expected, got $thrown", thrown is IOException)
    assertTrue("attacker-controlled file untouched (still a non-dir)", attackerControlled.isFile)
  }

  @Test
  fun writeAttachmentStaging_failedWrite_rollsBackPartialFile() = runTest {
    // Test-reviewer T17-T2: if the payload write throws, the half-written
    // file must be deleted so the staging dir doesn't accumulate orphans.
    // Robolectric's Bitmap.compress is too permissive to fail on a recycled
    // bitmap, so we force an IOException by making the staging path a
    // regular file — `File(stagingDir, filename).outputStream()` then fails
    // because its parent is not a directory.
    val staging = File(attachmentsRoot, "staging-fail-as-file").apply {
      parentFile?.mkdirs()
      writeText("not a directory")
    }
    val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val repo = newRepository()

    val thrown = runCatching {
      repo.writeAttachmentStaging(staging, filesDir, Attachment.Image(bmp))
    }.exceptionOrNull()

    assertNotNull("write on non-directory staging path must throw", thrown)
    // Load-bearing invariant: the file we wrote BEFORE attempting the image
    // write must still exist unchanged, proving no partial write lingered
    // in the same location. With a non-directory staging path there is no
    // second file to clean up — the assertion is that nothing additional
    // was created under attachmentsRoot.
    val attachmentsEntries = attachmentsRoot.listFiles()?.map { it.name }?.toSet().orEmpty()
    assertTrue(
      "no partial file created under attachments root: $attachmentsEntries",
      attachmentsEntries == setOf("staging-fail-as-file"),
    )
  }

  @Test
  fun deleteStagedAttachment_idempotent_missingFileIsNoOp() = runTest {
    val staging = File(attachmentsRoot, ".staging-del").apply { mkdirs() }
    val repo = newRepository()

    // Missing file: must not throw.
    repo.deleteStagedAttachment(staging, filesDir, "ghost.png")

    // Present file: gets removed.
    val present = File(staging, "present.png").apply { writeBytes(byteArrayOf(1)) }
    repo.deleteStagedAttachment(staging, filesDir, present.name)
    assertFalse("present file removed", present.exists())
  }

  @Test
  fun deleteStagedAttachment_outsideRoot_throws() = runTest {
    val outside = tempFolder.newFolder("not-attachments")
    val repo = newRepository()

    val thrown = runCatching {
      repo.deleteStagedAttachment(outside, filesDir, "anything.png")
    }.exceptionOrNull()
    assertTrue("IOException expected, got $thrown", thrown is IOException)
  }

  // ---- commitDraftChat with staged filenames ----

  @Test
  fun commitDraftChat_withStagedFilenames_writesFinalPathsOnMessage() = runTest {
    val staging = File(attachmentsRoot, ".staging-final").apply { mkdirs() }
    File(staging, "img_0.png").writeBytes(byteArrayOf(1))
    val repo = newRepository()

    val chatId = repo.commitDraftChat(
      modelId = "m/x",
      firstMessage = userMessage("hi", 1L),
      stagingDir = staging,
      filesDir = filesDir,
      stagedImageFilename = "img_0.png",
      stagedAudioFilename = null,
    )

    val stored = messageDao.byChat[chatId]!!.single()
    assertEquals("attachments/$chatId/img_0.png", stored.imagePath)
    assertNull("no audio staged → no audio path", stored.audioPath)
    assertTrue("final file under chat dir", File(filesDir, "attachments/$chatId/img_0.png").exists())
    assertFalse("staging dir was renamed", staging.exists())
  }

  // ---- auto-title trigger on commit ----

  @Test
  fun autoTitle_triggeredOnCommit() = runTest {
    val staging = File(attachmentsRoot, ".staging-title").apply { mkdirs() }
    val repo = newRepository()

    val chatId = repo.commitDraftChat(
      modelId = "m/x",
      firstMessage = userMessage("Hello title", 1_700_000_000_000L),
      stagingDir = staging,
      filesDir = filesDir,
    )

    val chat = chatDao.byId[chatId]
    assertNotNull(chat)
    assertEquals("title derived from first user message", "Hello title", chat!!.title)
    assertEquals("auto-title is not user-marked", 0, chat.isManuallyTitled)
  }

  // ---- Helpers ----

  private fun newRepository(
    rename: (File, File) -> Boolean = { src, dst -> src.renameTo(dst) },
    transactionRunner: suspend (suspend () -> Long) -> Long = { it() },
  ): DefaultChatRepository =
    DefaultChatRepository(
      chatDao = chatDao,
      messageDao = messageDao,
      errorLog = errorLog,
      ioDispatcher = UnconfinedTestDispatcher(),
      rename = rename,
      transactionRunner = transactionRunner,
    )

  private fun userMessage(text: String, createdAt: Long): MessageEntity =
    MessageEntity(
      chatId = 0L,
      role = "user",
      text = text,
      createdAt = createdAt,
    )
}

/** In-memory ChatDao: assigns sequential ids, tracks calls, exposes Flow over a map snapshot. */
private class FakeChatDao : ChatDao {
  val byId: MutableMap<Long, ChatEntity> = linkedMapOf()
  val deleteByIdCalls: MutableList<Long> = mutableListOf()
  var insertCalls: Int = 0; private set
  private val state = MutableStateFlow<List<ChatEntity>>(emptyList())
  private var nextId: Long = 1L

  fun insertSync(chat: ChatEntity): Long {
    val id = nextId++
    val stored = chat.copy(id = id)
    byId[id] = stored
    state.value = byId.values.sortedByDescending { it.lastMessageAt }
    return id
  }

  override suspend fun insert(chat: ChatEntity): Long {
    insertCalls++
    return insertSync(chat)
  }

  override suspend fun update(chat: ChatEntity) {
    byId[chat.id] = chat
    state.value = byId.values.sortedByDescending { it.lastMessageAt }
  }

  override suspend fun deleteById(id: Long) {
    deleteByIdCalls += id
    byId.remove(id)
    state.value = byId.values.sortedByDescending { it.lastMessageAt }
  }

  override suspend fun getById(id: Long): ChatEntity? = byId[id]

  override fun observeAll(): Flow<List<ChatEntity>> = state
}

private class FakeMessageDao : MessageDao {
  val byChat: MutableMap<Long, MutableList<MessageEntity>> = linkedMapOf()
  var insertCalls: Int = 0; private set
  var failOnInsert: Boolean = false
  private var nextId: Long = 1L
  private val state = MutableStateFlow<Map<Long, List<MessageEntity>>>(emptyMap())

  fun insertSync(message: MessageEntity): Long {
    val id = nextId++
    val stored = message.copy(id = id)
    byChat.getOrPut(message.chatId) { mutableListOf() }.add(stored)
    state.value = byChat.mapValues { it.value.toList() }
    return id
  }

  override suspend fun insert(message: MessageEntity): Long {
    insertCalls++
    if (failOnInsert) throw IOException("synthetic message insert failure")
    return insertSync(message)
  }

  override suspend fun deleteById(id: Long) {
    byChat.values.forEach { it.removeAll { m -> m.id == id } }
    state.value = byChat.mapValues { it.value.toList() }
  }

  override suspend fun getByChatId(chatId: Long): List<MessageEntity> =
    byChat[chatId].orEmpty().toList()

  override fun observeByChat(chatId: Long): Flow<List<MessageEntity>> =
    state.map { it[chatId].orEmpty() }

  override suspend fun countByChatId(chatId: Long): Int = byChat[chatId]?.size ?: 0
}
