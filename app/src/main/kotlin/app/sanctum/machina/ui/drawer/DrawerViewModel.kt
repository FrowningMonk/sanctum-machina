package app.sanctum.machina.ui.drawer

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.core.data.ModelDownloadStatusType
import app.sanctum.machina.core.registry.ModelRegistry
import app.sanctum.machina.data.AutoTitleGenerator
import app.sanctum.machina.data.ChatRepository
import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.data.dao.ChatDao
import app.sanctum.machina.data.dao.MessageDao
import app.sanctum.machina.data.model.ChatEntity
import app.sanctum.machina.data.model.ProjectEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val ROLE_USER = "user"
private const val MAX_MANUAL_TITLE_LEN = 60

/** Discrete day-bucket a chat's `last_message_at` falls into (local time zone). */
enum class DateSectionKind { TODAY, YESTERDAY, THIS_WEEK, EARLIER }

/** One header + its chats, already sorted inside the bucket by `last_message_at DESC`. */
data class DateSection(
  val kind: DateSectionKind,
  val chats: List<ChatRowUiModel>,
)

/**
 * Display projection of one [ChatEntity] with the resolved model state needed
 * by [DrawerContent]:
 *
 * - [isModelAvailable] is `entry.downloadStatus.status == SUCCEEDED` for the
 *   chat's `model_id` in [ModelRegistry.models] (AC-M1, Decision 7 — file-on-
 *   disk predicate, NOT the [ModelInitStatus] runtime predicate).
 * - [modelDisplayName] is `Model.displayName` when the model is still in the
 *   allowlist, or `model_id` as a fallback if it has been removed (edge case).
 */
data class ChatRowUiModel(
  val id: Long,
  val title: String,
  val modelId: String,
  val modelDisplayName: String,
  val relativeDate: String,
  val isModelAvailable: Boolean,
)

/**
 * One Phase-4 project rendered in the drawer's «Проекты» section. Chats inside
 * the group are already sorted by `last_message_at DESC` to match the date-group
 * convention; [isExpanded] is purely UI state driven by [DrawerViewModel.toggleProject].
 */
data class ProjectGroupUiModel(
  val id: Long,
  val name: String,
  val chats: List<ChatRowUiModel>,
  val isExpanded: Boolean,
)

data class DrawerUiState(
  val projects: List<ProjectGroupUiModel>,
  val sections: List<DateSection>,
  val isLoading: Boolean,
) {
  companion object {
    val Initial = DrawerUiState(
      projects = emptyList(),
      sections = emptyList(),
      isLoading = true,
    )
  }
}

/**
 * One-shot side-effect surface consumed by [DrawerContent] via `LaunchedEffect`.
 * Kept intentionally narrow — the VM does not hold a `NavController` reference
 * (anti-pattern). Currently: pop back when the open chat is deleted.
 */
sealed class DrawerEvent {
  object PopBack : DrawerEvent()
}

/**
 * Drives [DrawerContent] (Phase-3 Task 9).
 *
 * Combines `chatRepository.observeChats()` with `registry.models` to form a
 * section-bucketed list of chats, each row tagged with its model-availability
 * flag per Decision 7 (on-disk file predicate — tap on a chat whose file was
 * deleted shows the "Модель недоступна" dialog before navigating, AC-M1).
 *
 * Date grouping uses `LocalDate` in the device's local time zone: the section
 * boundary is at local midnight, not a rolling 24-hour window — a message from
 * 23:59 yesterday still lands in "Вчера" at 00:01 today.
 */
@HiltViewModel
class DrawerViewModel
@VisibleForTesting
internal constructor(
  private val chatRepository: ChatRepository,
  private val registry: ModelRegistry,
  private val messageDao: MessageDao,
  private val chatDao: ChatDao,
  private val projectRepository: ProjectRepository,
  private val filesDir: File,
  private val clock: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

  @Inject
  constructor(
    chatRepository: ChatRepository,
    registry: ModelRegistry,
    messageDao: MessageDao,
    chatDao: ChatDao,
    projectRepository: ProjectRepository,
    @ApplicationContext context: Context,
  ) : this(
    chatRepository = chatRepository,
    registry = registry,
    messageDao = messageDao,
    chatDao = chatDao,
    projectRepository = projectRepository,
    filesDir = context.filesDir,
    clock = { LocalDate.now() },
  )

  /**
   * Projects that are visually collapsed in the drawer. Tracking the *collapsed*
   * subset instead of the *expanded* one means newly-arriving projects default
   * to expanded without an extra registration step (Task 8 MVP: «все раскрыты»).
   * State is in-VM only — survives configuration changes via `viewModelScope`
   * but resets on process death (acceptable per task spec — no persist for MVP).
   */
  private val _collapsedProjectIds = MutableStateFlow<Set<Long>>(emptySet())

  val drawerUiState: StateFlow<DrawerUiState> =
    combine(
      chatRepository.observeChats(),
      registry.models,
      projectRepository.observeAllProjects(),
      _collapsedProjectIds,
    ) { chats, entries, projects, collapsed ->
      val (withProject, withoutProject) = chats.partition { it.projectId != null }
      DrawerUiState(
        projects = buildProjectGroups(projects, withProject, entries, collapsed),
        sections = buildSections(withoutProject, entries),
        isLoading = false,
      )
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = DrawerUiState.Initial,
    )

  /** Toggle drawer expansion state for [projectId]. UI-only — never touches Room. */
  fun toggleProject(projectId: Long) {
    _collapsedProjectIds.update { current ->
      if (projectId in current) current - projectId else current + projectId
    }
  }

  private val _events = MutableSharedFlow<DrawerEvent>(
    replay = 0,
    extraBufferCapacity = 1,
  )
  val events: SharedFlow<DrawerEvent> = _events.asSharedFlow()

  /**
   * Delete [chatId] via [ChatRepository.deleteChat] (CASCADE removes messages,
   * recursively deletes `attachments/{chatId}/`). When the deleted chat was the
   * one currently open in the NavHost, emit [DrawerEvent.PopBack] so the host
   * composable can pop the back stack — VM stays free of `NavController`.
   */
  fun deleteChat(chatId: Long, currentOpenChatId: Long?) {
    viewModelScope.launch {
      chatRepository.deleteChat(chatId, filesDir)
      if (chatId == currentOpenChatId) {
        _events.tryEmit(DrawerEvent.PopBack)
      }
    }
  }

  /**
   * Rename [chatId] to [newTitle]. A blank input resets the chat back to the
   * auto-title (AC-U1, `is_manually_titled = false`), regenerated from the
   * first user message via [AutoTitleGenerator]. A non-blank input is trimmed
   * and stored as a manual title (`is_manually_titled = true`).
   */
  fun renameChat(chatId: Long, newTitle: String) {
    viewModelScope.launch {
      if (newTitle.isBlank()) {
        val chat = chatDao.getById(chatId) ?: return@launch
        val firstUserText = messageDao.firstByChatIdAndRole(chatId, ROLE_USER)?.text
        val auto = AutoTitleGenerator.generateTitle(firstUserText, chat.createdAt)
        chatRepository.updateChatTitle(chatId, auto, isManuallyTitled = false)
      } else {
        val trimmed = newTitle.trim().take(MAX_MANUAL_TITLE_LEN)
        chatRepository.updateChatTitle(chatId, trimmed, isManuallyTitled = true)
      }
    }
  }

  /**
   * Count messages for [chatId] — surfaced by the delete-confirmation dialog
   * so the user sees how much history they're about to drop. Called on demand
   * when the dialog opens rather than eagerly per drawer render. Returns
   * `null` on any I/O failure so the dialog can fall back to the no-count
   * body; the caller never sees a misleading `0` right before CASCADE DELETE.
   */
  suspend fun getMessageCount(chatId: Long): Int? =
    runCatching { messageDao.countByChatId(chatId) }.getOrNull()

  /**
   * Pre-navigation check for the tap handler (Decision 7). Resolves the chat
   * via [ChatDao] (a `SELECT … WHERE id = :id LIMIT 1`) and checks the
   * corresponding [ModelEntry] in [ModelRegistry.models] — deliberately does
   * NOT read [drawerUiState], which is a `WhileSubscribed(5_000L)` flow that
   * resets to `Initial` once no subscriber is attached (off-screen tap would
   * spuriously return `false`).
   *
   * Unknown chatId returns `false` so the caller shows the "Модель
   * недоступна" dialog rather than navigating to a chat that no longer
   * exists.
   */
  suspend fun checkModelAvailable(chatId: Long): Boolean {
    val chat = chatDao.getById(chatId) ?: return false
    val entry = registry.models.value.firstOrNull { it.model.modelId == chat.modelId }
    return entry?.downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
  }

  /**
   * Group `chats` (already filtered to `project_id != null`) under their owning
   * [ProjectEntity]. Project order is preserved from the repo stream (Decision —
   * `created_at DESC` per `ProjectRepository` contract). Chats whose `project_id`
   * does not resolve to any current project are dropped silently: per task
   * edge-cases, the CASCADE-DELETE race window is narrow and surfacing the
   * orphan in a date group would mislead the user about ownership.
   */
  private fun buildProjectGroups(
    projects: List<ProjectEntity>,
    chats: List<ChatEntity>,
    entries: List<app.sanctum.machina.core.registry.ModelEntry>,
    collapsed: Set<Long>,
  ): List<ProjectGroupUiModel> {
    if (projects.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val byProjectId = chats.groupBy { it.projectId }
    return projects.map { project ->
      val rows = byProjectId[project.id]
        ?.sortedByDescending { it.lastMessageAt }
        ?.map { toRow(it, entries, zone) }
        ?: emptyList()
      ProjectGroupUiModel(
        id = project.id,
        name = project.name,
        chats = rows,
        isExpanded = project.id !in collapsed,
      )
    }
  }

  private fun buildSections(
    chats: List<ChatEntity>,
    entries: List<app.sanctum.machina.core.registry.ModelEntry>,
  ): List<DateSection> {
    if (chats.isEmpty()) return emptyList()

    val zone = ZoneId.systemDefault()
    val today = clock()

    val grouped = LinkedHashMap<DateSectionKind, MutableList<ChatRowUiModel>>().apply {
      // Preserve header order: Today → Yesterday → This week → Earlier.
      put(DateSectionKind.TODAY, mutableListOf())
      put(DateSectionKind.YESTERDAY, mutableListOf())
      put(DateSectionKind.THIS_WEEK, mutableListOf())
      put(DateSectionKind.EARLIER, mutableListOf())
    }

    for (chat in chats.sortedByDescending { it.lastMessageAt }) {
      val row = toRow(chat, entries, zone)
      val date = Instant.ofEpochMilli(chat.lastMessageAt).atZone(zone).toLocalDate()
      val kind = classifyDate(date, today)
      grouped.getValue(kind).add(row)
    }

    return grouped.entries
      .filter { it.value.isNotEmpty() }
      .map { (kind, chatList) -> DateSection(kind, chatList) }
  }

  private fun classifyDate(date: LocalDate, today: LocalDate): DateSectionKind {
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
      days == 0L -> DateSectionKind.TODAY
      days == 1L -> DateSectionKind.YESTERDAY
      days in 2L..6L -> DateSectionKind.THIS_WEEK
      else -> DateSectionKind.EARLIER
    }
  }

  private fun toRow(
    chat: ChatEntity,
    entries: List<app.sanctum.machina.core.registry.ModelEntry>,
    zone: ZoneId,
  ): ChatRowUiModel {
    val entry = entries.firstOrNull { it.model.modelId == chat.modelId }
    val display = entry?.model?.displayName?.takeIf { it.isNotBlank() } ?: chat.modelId
    val available = entry?.downloadStatus?.status == ModelDownloadStatusType.SUCCEEDED
    return ChatRowUiModel(
      id = chat.id,
      title = chat.title.orEmpty(),
      modelId = chat.modelId,
      modelDisplayName = display,
      relativeDate = formatRelative(chat.lastMessageAt, zone),
      isModelAvailable = available,
    )
  }

  private fun formatRelative(ms: Long, zone: ZoneId): String {
    val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = clock()
    val pattern = if (date == today) "HH:mm" else "d MMM"
    val formatter = SimpleDateFormat(pattern, Locale.getDefault())
    formatter.timeZone = java.util.TimeZone.getTimeZone(zone)
    return formatter.format(Date(ms))
  }
}
