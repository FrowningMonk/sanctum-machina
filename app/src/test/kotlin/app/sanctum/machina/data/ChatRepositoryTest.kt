package app.sanctum.machina.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
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
  fun commitDraftChat_ioExceptionOnStagingWrite() = runTest {
    // AC-A4: IOException raised before Room INSERT must leave Room untouched.
    // We model "staging write failed" as "staging dir does not exist when commit is called".
    val staging = File(attachmentsRoot, ".staging-missing")
    val repo = newRepository()

    val thrown = runCatching {
      repo.commitDraftChat(
        modelId = "model/x",
        firstMessage = userMessage("hi", 1L),
        stagingDir = staging,
      )
    }.exceptionOrNull()
    assertTrue("IOException expected, got $thrown", thrown is IOException)

    assertEquals("no chat inserted", 0, chatDao.insertCalls)
    assertEquals("no message inserted", 0, messageDao.insertCalls)
  }

  @Test
  fun commitDraftChat_renameFailsAfterRoomInsert() = runTest {
    val staging = File(attachmentsRoot, ".staging-failrename").apply { mkdirs() }
    File(staging, "doc.txt").writeText("payload")
    val repo = newRepository(rename = { _, _ -> false })

    val thrown = runCatching {
      repo.commitDraftChat(
        modelId = "model/x",
        firstMessage = userMessage("hi", 1L),
        stagingDir = staging,
      )
    }.exceptionOrNull()
    assertTrue("IOException expected, got $thrown", thrown is IOException)

    assertEquals("Room insert was attempted", 1, chatDao.insertCalls)
    assertTrue("Room row was rolled back via deleteById", chatDao.deleteByIdCalls.isNotEmpty())
    assertTrue("Room row is gone from fake state", chatDao.byId.isEmpty())
    assertFalse("staging dir cleaned up", staging.exists())
    val finalDir = File(attachmentsRoot, chatDao.deleteByIdCalls.first().toString())
    assertFalse("final dir must not exist", finalDir.exists())
  }

  // ---- deleteChat ----

  @Test
  fun deleteChat_callsRoomAndDeletesFiles() = runTest {
    // Insert a chat directly into fake state and create its attachments dir.
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
      "zombie sweep logs ErrorLog.e under history-write",
      log.any { it.contains("[history-write]") && it.contains("zombie") },
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

  // ---- auto-title trigger on commit ----

  @Test
  fun autoTitle_triggeredOnCommit() = runTest {
    val staging = File(attachmentsRoot, ".staging-title").apply { mkdirs() }
    val repo = newRepository()

    val chatId = repo.commitDraftChat(
      modelId = "m/x",
      firstMessage = userMessage("Hello title", 1_700_000_000_000L),
      stagingDir = staging,
    )

    val chat = chatDao.byId[chatId]
    assertNotNull(chat)
    assertEquals("title derived from first user message", "Hello title", chat!!.title)
    assertEquals("auto-title is not user-marked", 0, chat.isManuallyTitled)
  }

  // ---- Helpers ----

  private fun newRepository(
    rename: (File, File) -> Boolean = { src, dst -> src.renameTo(dst) },
  ): DefaultChatRepository =
    DefaultChatRepository(
      chatDao = chatDao,
      messageDao = messageDao,
      errorLog = errorLog,
      ioDispatcher = UnconfinedTestDispatcher(),
      rename = rename,
      transactionRunner = { block -> block() },
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
