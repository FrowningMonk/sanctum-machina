package app.sanctum.machina.ui.drawer

import app.sanctum.machina.core.data.Model
import app.sanctum.machina.core.data.ModelDownloadStatus
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.registry.ModelEntry
import app.sanctum.machina.core.registry.ModelInitStatus
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.core.registry.ResetReason
import app.sanctum.machina.data.ChatRepository
import app.sanctum.machina.data.PersistedAttachment
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.MessageEntity
import app.sanctum.machina.ui.chat.Attachment
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TDD harness for [DrawerViewModel] (Phase-3 Task 9).
 *
 * Hand-rolled fakes per `patterns.md` (no MockK / Mockito). `Dispatchers.setMain`
 * is required because `viewModelScope` defaults to `Dispatchers.Main.immediate`;
 * overriding with [StandardTestDispatcher] lets `advanceUntilIdle()` drive
 * `stateIn` collection deterministically.
 *
 * A fixed "today" of **2026-04-21** drives date-grouping tests; chat timestamps
 * are generated via [atStartOfDayEpoch] so the section mapping is independent of
 * the machine's real clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DrawerViewModelTest {

  private val testDispatcher = StandardTestDispatcher()
  private val today: LocalDate = LocalDate.of(2026, 4, 21)
  private val zone: ZoneId = ZoneId.systemDefault()

  private lateinit var chatRepo: FakeChatRepository
  private lateinit var registry: FakeModelRegistry
  private lateinit var messageDao: FakeMessageDao
  private lateinit var chatDao: FakeChatDao

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    chatRepo = FakeChatRepository()
    registry = FakeModelRegistry()
    messageDao = FakeMessageDao()
    chatDao = FakeChatDao()
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  // ---- Date grouping ----

  @Test
  fun groupsChatsIntoTodaySection() = runTest {
    seedChat(id = 1L, lastMessageDate = today)

    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    val sections = viewModel.drawerUiState.value.sections
    val todaySection = sections.firstOrNull { it.kind == DateSectionKind.TODAY }
    assertNotNull("expected TODAY section", todaySection)
    assertEquals(listOf(1L), todaySection!!.chats.map { it.id })
  }

  @Test
  fun groupsChatsIntoYesterdaySection() = runTest {
    seedChat(id = 2L, lastMessageDate = today.minusDays(1))

    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    val yesterday =
      viewModel.drawerUiState.value.sections.firstOrNull { it.kind == DateSectionKind.YESTERDAY }
    assertNotNull("expected YESTERDAY section", yesterday)
    assertEquals(listOf(2L), yesterday!!.chats.map { it.id })
  }

  @Test
  fun groupsChatsIntoThisWeekSection() = runTest {
    // 3 days ago: neither today nor yesterday, strictly inside the 7-day window.
    seedChat(id = 3L, lastMessageDate = today.minusDays(3))

    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    val week =
      viewModel.drawerUiState.value.sections.firstOrNull { it.kind == DateSectionKind.THIS_WEEK }
    assertNotNull("expected THIS_WEEK section", week)
    assertEquals(listOf(3L), week!!.chats.map { it.id })
  }

  @Test
  fun groupsChatsIntoEarlierSection() = runTest {
    // 10 days ago — outside the 7-day window.
    seedChat(id = 4L, lastMessageDate = today.minusDays(10))

    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    val earlier =
      viewModel.drawerUiState.value.sections.firstOrNull { it.kind == DateSectionKind.EARLIER }
    assertNotNull("expected EARLIER section", earlier)
    assertEquals(listOf(4L), earlier!!.chats.map { it.id })
  }

  @Test
  fun dateBucketsHonorExactDayBoundaries() = runTest {
    // Pins the `2..6`-day window (inclusive) and "day 7 is EARLIER" off-by-one:
    //   0d → TODAY, 1d → YESTERDAY, 2d/6d → THIS_WEEK, 7d/10d → EARLIER.
    listOf(
      100L to 0L,
      101L to 1L,
      102L to 2L,
      103L to 6L,
      104L to 7L,
      105L to 10L,
    ).forEach { (id, offset) ->
      seedChat(id = id, lastMessageDate = today.minusDays(offset))
    }

    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    val sections = viewModel.drawerUiState.value.sections
      .associate { it.kind to it.chats.map { row -> row.id }.toSet() }

    assertEquals(setOf(100L), sections[DateSectionKind.TODAY])
    assertEquals(setOf(101L), sections[DateSectionKind.YESTERDAY])
    assertEquals(setOf(102L, 103L), sections[DateSectionKind.THIS_WEEK])
    assertEquals(setOf(104L, 105L), sections[DateSectionKind.EARLIER])
  }

  @Test
  fun sectionBoundaryIsLocalMidnightNotRolling24Hours() = runTest {
    // A message stamped 23:59 local time "yesterday" must still land in
    // YESTERDAY, not TODAY — Decision 7 pins local-date granularity even
    // though the timestamp is only ~1 minute old in wall-clock terms.
    val lateYesterday = ZonedDateTime.of(
      today.minusDays(1),
      java.time.LocalTime.of(23, 59),
      zone,
    ).toInstant().toEpochMilli()
    chatDao.put(
      ChatEntity(
        id = 200L,
        modelId = "m",
        title = "Late yesterday",
        isManuallyTitled = 0,
        createdAt = lateYesterday,
        lastMessageAt = lateYesterday,
      )
    )
    chatRepo.emitChats(chatDao.snapshot())

    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    val yesterday = viewModel.drawerUiState.value.sections
      .firstOrNull { it.kind == DateSectionKind.YESTERDAY }
    assertNotNull("expected YESTERDAY section", yesterday)
    assertEquals(listOf(200L), yesterday!!.chats.map { it.id })
  }

  @Test
  fun chatsWithinSectionSortedByLastMessageDesc() = runTest {
    // AC-P4: within a section, newest first. Seed three TODAY rows with
    // increasing timestamps — the section list must reverse that order.
    val base = atStartOfDayEpoch(today)
    chatDao.put(
      ChatEntity(id = 300L, modelId = "m", title = "A", createdAt = base, lastMessageAt = base + 100)
    )
    chatDao.put(
      ChatEntity(id = 301L, modelId = "m", title = "B", createdAt = base, lastMessageAt = base + 300)
    )
    chatDao.put(
      ChatEntity(id = 302L, modelId = "m", title = "C", createdAt = base, lastMessageAt = base + 200)
    )
    chatRepo.emitChats(chatDao.snapshot())

    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    val today = viewModel.drawerUiState.value.sections
      .first { it.kind == DateSectionKind.TODAY }
    assertEquals(listOf(301L, 302L, 300L), today.chats.map { it.id })
  }

  @Test
  fun emptyChatListYieldsEmptySections() = runTest {
    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    val state = viewModel.drawerUiState.value
    assertTrue("sections must be empty", state.sections.isEmpty())
    assertFalse("isLoading must resolve to false after first combine", state.isLoading)
  }

  // ---- delete + PopBack ----

  @Test
  fun deleteChatEmitsPopBackWhenCurrentChatDeleted() = runTest {
    val viewModel = subscribedViewModel()
    val received = mutableListOf<DrawerEvent>()
    // UNDISPATCHED guarantees the `collect` subscription is registered on the
    // shared flow before we leave this statement — otherwise tryEmit runs on
    // the same test scheduler without an active subscriber and the event is
    // buffered/dropped by the time `received` is inspected.
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
      viewModel.events.collect { received += it }
    }

    viewModel.deleteChat(chatId = 5L, currentOpenChatId = 5L)
    advanceUntilIdle()

    assertEquals(listOf(5L), chatRepo.deletedChatIds)
    assertTrue("PopBack not emitted", received.any { it is DrawerEvent.PopBack })
  }

  @Test
  fun deleteChatDoesNotEmitPopBackWhenDifferentChat() = runTest {
    val viewModel = subscribedViewModel()
    val received = mutableListOf<DrawerEvent>()
    backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
      viewModel.events.collect { received += it }
    }

    viewModel.deleteChat(chatId = 5L, currentOpenChatId = 3L)
    advanceUntilIdle()

    assertEquals(listOf(5L), chatRepo.deletedChatIds)
    assertTrue("unexpected events: $received", received.isEmpty())
  }

  // ---- rename ----

  @Test
  fun renameChatWithBlankTitleResetsAutoTitle() = runTest {
    // Litmus: the regenerated title MUST derive from the first USER message via
    // AutoTitleGenerator. A weaker `title.isNotBlank()` assertion would pass if
    // the VM wrote back `"Old manual"` (the pre-existing manual title) — which
    // would silently break AC-U1.
    chatDao.put(
      ChatEntity(
        id = 7L,
        modelId = "m",
        title = "Old manual",
        isManuallyTitled = 1,
        createdAt = atStartOfDayEpoch(today),
        lastMessageAt = atStartOfDayEpoch(today),
      )
    )
    messageDao.put(
      MessageEntity(
        id = 70L,
        chatId = 7L,
        role = "user",
        text = "Привет",
        createdAt = atStartOfDayEpoch(today),
      )
    )
    val viewModel = subscribedViewModel()

    viewModel.renameChat(chatId = 7L, newTitle = "   ")
    advanceUntilIdle()

    val call = chatRepo.titleUpdates.single()
    assertEquals(7L, call.chatId)
    assertFalse("isManuallyTitled must be false on reset", call.isManuallyTitled)
    assertEquals("Привет", call.title)
  }

  @Test
  fun renameChatWithBlankTitleAndNoUserMessageFallsBackToTimestampTitle() = runTest {
    // Companion litmus: when there is no first-user-message, AutoTitleGenerator
    // emits the `"Chat from DD.MM HH:mm"` fallback — pins that branch.
    chatDao.put(
      ChatEntity(
        id = 71L,
        modelId = "m",
        title = "Old manual",
        isManuallyTitled = 1,
        createdAt = atStartOfDayEpoch(today),
        lastMessageAt = atStartOfDayEpoch(today),
      )
    )
    val viewModel = subscribedViewModel()

    viewModel.renameChat(chatId = 71L, newTitle = "")
    advanceUntilIdle()

    val call = chatRepo.titleUpdates.single()
    assertFalse(call.isManuallyTitled)
    assertTrue(
      "expected fallback title prefix, was '${call.title}'",
      call.title.startsWith("Chat from "),
    )
  }

  @Test
  fun renameChatTrimsAndCapsAtSixtyChars() = runTest {
    // AC-U1 60-char cap. Use a 70-char input; expected output is the first 60
    // characters after trim (input is already trimmed of outer whitespace).
    val input = "A".repeat(70)
    val viewModel = subscribedViewModel()

    viewModel.renameChat(chatId = 80L, newTitle = input)
    advanceUntilIdle()

    val call = chatRepo.titleUpdates.single()
    assertEquals(60, call.title.length)
    assertTrue(call.isManuallyTitled)
  }

  @Test
  fun renameChatWithValidTitleSetsManualTitle() = runTest {
    val viewModel = subscribedViewModel()

    viewModel.renameChat(chatId = 8L, newTitle = "  Rename me  ")
    advanceUntilIdle()

    val call = chatRepo.titleUpdates.single()
    assertEquals(8L, call.chatId)
    assertEquals("Rename me", call.title)
    assertTrue("isManuallyTitled must be true on manual rename", call.isManuallyTitled)
  }

  // ---- checkModelAvailable ----

  @Test
  fun checkModelAvailableReturnsFalseWhenStatusNotSucceeded() = runTest {
    seedChat(id = 9L, lastMessageDate = today, modelId = "model-x")
    registry.emit(
      listOf(entry(modelId = "model-x", status = ModelDownloadStatusType.NOT_DOWNLOADED))
    )

    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    assertFalse(viewModel.checkModelAvailable(chatId = 9L))
  }

  @Test
  fun checkModelAvailableReturnsTrueWhenStatusSucceeded() = runTest {
    seedChat(id = 10L, lastMessageDate = today, modelId = "model-y")
    registry.emit(
      listOf(entry(modelId = "model-y", status = ModelDownloadStatusType.SUCCEEDED))
    )

    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    assertTrue(viewModel.checkModelAvailable(chatId = 10L))
  }

  @Test
  fun checkModelAvailableReturnsFalseWhenChatUnknown() = runTest {
    // Regression pin: unknown chatId returns false, not NPE — UI taps an id
    // that is no longer in the registry (e.g. chat just deleted by another
    // trigger). Also covers the case where the chat row is gone from the DAO
    // but a stale `ChatRowUiModel` still lived in the last drawer emission.
    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    assertFalse(viewModel.checkModelAvailable(chatId = 9999L))
  }

  @Test
  fun checkModelAvailableReturnsFalseWhenModelRemovedFromRegistry() = runTest {
    // Chat exists but its model has been removed from the allowlist — registry
    // has no matching entry. Pin returns false (→ UI opens the
    // model-unavailable dialog rather than attempting navigation).
    seedChat(id = 400L, lastMessageDate = today, modelId = "removed-model")
    registry.emit(emptyList())

    val viewModel = subscribedViewModel()
    advanceUntilIdle()

    assertFalse(viewModel.checkModelAvailable(chatId = 400L))
  }

  // ---- helpers ----

  private fun TestScope.subscribedViewModel(): DrawerViewModel {
    val viewModel =
      DrawerViewModel(
        chatRepository = chatRepo,
        registry = registry,
        messageDao = messageDao,
        chatDao = chatDao,
        filesDir = File("fake-files-dir"),
        clock = { today },
      )
    backgroundScope.launch { viewModel.drawerUiState.collect {} }
    return viewModel
  }

  private fun seedChat(id: Long, lastMessageDate: LocalDate, modelId: String = "m") {
    val ms = atStartOfDayEpoch(lastMessageDate)
    val entity = ChatEntity(
      id = id,
      modelId = modelId,
      title = "Chat #$id",
      isManuallyTitled = 0,
      createdAt = ms,
      lastMessageAt = ms,
    )
    chatDao.put(entity)
    chatRepo.emitChats(chatDao.snapshot())
  }

  private fun atStartOfDayEpoch(date: LocalDate): Long =
    ZonedDateTime.of(date, java.time.LocalTime.NOON, zone).toInstant().toEpochMilli()

  private fun entry(modelId: String, status: ModelDownloadStatusType): ModelEntry =
    ModelEntry(
      model = Model(name = modelId, modelId = modelId, displayName = modelId),
      downloadStatus = ModelDownloadStatus(status = status),
      initStatus = ModelInitStatus.Idle,
    )
}

// ---------- Hand-rolled fakes (no MockK/Mockito per patterns.md) ----------

private class FakeModelRegistry : ModelRegistry {
  private val _models = MutableStateFlow<List<ModelEntry>>(emptyList())
  override val models: StateFlow<List<ModelEntry>> = _models.asStateFlow()

  private val _activeModelName = MutableStateFlow<String?>(null)
  override val activeModelName: StateFlow<String?> = _activeModelName.asStateFlow()

  fun emit(list: List<ModelEntry>) { _models.value = list }

  override suspend fun refreshAllowlist(): Result<Unit> = Result.success(Unit)
  override fun download(model: Model): Flow<ModelDownloadStatus> =
    MutableStateFlow(ModelDownloadStatus(status = ModelDownloadStatusType.NOT_DOWNLOADED))
      .asStateFlow()
  override fun cancelDownload(modelName: String) = Unit
  override suspend fun delete(modelName: String) = Unit
  override suspend fun initialize(modelName: String): Result<Unit> = Result.success(Unit)
  override suspend fun cleanup(modelName: String) = Unit
  override suspend fun resetConversation(
    modelName: String,
    systemPrompt: String?,
    reason: ResetReason,
    initialMessages: List<com.google.ai.edge.litertlm.Message>,
  ) = Unit
  override fun getModel(modelName: String): Model? = null
}

private data class TitleUpdate(
  val chatId: Long,
  val title: String,
  val isManuallyTitled: Boolean,
)

private class FakeChatRepository : ChatRepository {
  val chatsFlow = MutableStateFlow<List<ChatEntity>>(emptyList())
  val deletedChatIds = mutableListOf<Long>()
  val titleUpdates = mutableListOf<TitleUpdate>()

  fun emitChats(list: List<ChatEntity>) { chatsFlow.value = list }

  override fun observeChats(): Flow<List<ChatEntity>> = chatsFlow.asStateFlow()

  override suspend fun deleteChat(chatId: Long, filesDir: File) {
    deletedChatIds += chatId
  }

  override suspend fun updateChatTitle(
    chatId: Long,
    title: String,
    isManuallyTitled: Boolean,
  ) {
    titleUpdates += TitleUpdate(chatId, title, isManuallyTitled)
  }

  // ---- unused (returns defaults) ----
  override suspend fun commitDraftChat(
    modelId: String,
    firstMessage: MessageEntity,
    stagingDir: File?,
    filesDir: File,
    stagedImageFilename: String?,
    stagedAudioFilename: String?,
  ): Long = 0L
  override suspend fun writeAttachmentStaging(
    stagingDir: File,
    filesDir: File,
    attachment: Attachment,
  ): String = ""
  override suspend fun deleteStagedAttachment(
    stagingDir: File,
    filesDir: File,
    filename: String,
  ) = Unit
  override suspend fun pruneStagingDir(
    stagingDir: File,
    filesDir: File,
    retain: Set<String>,
  ) = Unit
  override suspend fun savePersistentAttachment(
    chatId: Long,
    filesDir: File,
    attachment: Attachment,
  ): PersistedAttachment = PersistedAttachment()
  override suspend fun savePersistentMessage(message: MessageEntity) = Unit
  override suspend fun updateChatLastMessage(chatId: Long, timestampMs: Long) = Unit
  override fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = emptyFlow()
  override suspend fun sweepZombieChats(filesDir: File) = Unit
}

private class FakeChatDao : ChatDao {
  private val rows = linkedMapOf<Long, ChatEntity>()

  fun put(entity: ChatEntity) { rows[entity.id] = entity }
  fun snapshot(): List<ChatEntity> =
    rows.values.sortedByDescending { it.lastMessageAt }

  override suspend fun insert(chat: ChatEntity): Long {
    rows[chat.id] = chat
    return chat.id
  }
  override suspend fun update(chat: ChatEntity) { rows[chat.id] = chat }
  override suspend fun deleteById(id: Long) { rows.remove(id) }
  override suspend fun getById(id: Long): ChatEntity? = rows[id]
  override fun observeAll(): Flow<List<ChatEntity>> =
    MutableStateFlow(snapshot()).asStateFlow()
}

private class FakeMessageDao : MessageDao {
  private val rows = mutableListOf<MessageEntity>()

  fun put(m: MessageEntity) { rows += m }

  override suspend fun insert(message: MessageEntity): Long {
    rows += message
    return message.id
  }
  override suspend fun deleteById(id: Long) { rows.removeAll { it.id == id } }
  override suspend fun getByChatId(chatId: Long): List<MessageEntity> =
    rows.filter { it.chatId == chatId }.sortedBy { it.createdAt }
  override fun observeByChat(chatId: Long): Flow<List<MessageEntity>> =
    MutableStateFlow(rows.filter { it.chatId == chatId }).asStateFlow()
  override suspend fun countByChatId(chatId: Long): Int =
    rows.count { it.chatId == chatId }
  override suspend fun firstByChatIdAndRole(chatId: Long, role: String): MessageEntity? =
    rows.filter { it.chatId == chatId && it.role == role }
      .minByOrNull { it.createdAt }
  override suspend fun lastByChat(chatId: Long): MessageEntity? =
    rows.filter { it.chatId == chatId }
      .maxWithOrNull(compareBy({ it.createdAt }, { it.id }))
}
