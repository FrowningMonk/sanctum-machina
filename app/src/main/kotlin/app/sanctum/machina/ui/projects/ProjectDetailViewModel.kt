/*
 * Copyright 2026 Sanctum Machina authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package app.sanctum.machina.ui.projects

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sanctum.machina.core.log.ErrorLog
import app.sanctum.machina.data.ProjectRepository
import app.sanctum.machina.data.dao.ProjectFileDao
import app.sanctum.machina.data.model.ProjectEntity
import app.sanctum.machina.data.model.ProjectFileEntity
import app.sanctum.machina.engine.EmbedderGate
import app.sanctum.machina.engine.EmbedderState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val NAV_ARG_PROJECT_ID: String = "projectId"

internal const val PROJECT_FILE_STATUS_PENDING: String = "pending"
internal const val PROJECT_FILE_STATUS_INDEXING: String = "indexing"
internal const val PROJECT_FILE_STATUS_READY: String = "ready"
internal const val PROJECT_FILE_STATUS_FAILED: String = "failed"

/**
 * Marker used by `IngestWorker` (Task 7) when an ingest is interrupted by a `CancellationException`
 * mid-batch (notification action, OS kill mid-flight). The failed-docs banner on
 * [ProjectDetailScreen] picks up rows with exactly this marker — other failure modes (encrypted PDF,
 * malformed header) surface as a per-row chip on the document list, not the banner.
 */
internal const val STATUS_MESSAGE_INTERRUPTED: String = "Прервано"

/** First 100 KB of the file — same window the IngestWorker hashes per Decision 5. */
private const val HASH_BYTES: Int = 100 * 1024

/**
 * Phase 4 Task 9 — drives [ProjectDetailScreen]. Owns:
 *  - Project metadata stream + per-project file stream (Room-backed via [ProjectRepository]).
 *  - SAF-picked Uri handling (SHA-256 dedup + stream copy + `addFile` + `enqueueIngest`),
 *    race-guarded against a multi-select submit that contains the same hash twice or against
 *    two concurrent `addDocuments` calls firing during a fast double-tap.
 *  - Failed-docs banner state — files whose status_message is the [STATUS_MESSAGE_INTERRUPTED]
 *    marker IngestWorker writes on CancellationException (banner-dismiss is in-memory; reopening
 *    the screen surfaces the banner again per task spec).
 *  - FAB gate against [EmbedderGate.state] — disabled when the embedder is not downloaded
 *    (US-6 / AC-4); tap-on-disabled emits a snackbar with a CTA to Model Manager.
 *
 * Warmup is fired from [init] per Decision 2 (trigger point: entry to ProjectDetailScreen).
 * Failures during warmup surface through `EmbedderGate.state == Failed`, observable as `state`.
 */
@HiltViewModel
open class ProjectDetailViewModel
@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal constructor(
  private val projectId: Long,
  private val projectRepository: ProjectRepository,
  private val projectFileDao: ProjectFileDao,
  private val embedderGate: EmbedderGate,
  private val errorLog: ErrorLog,
  private val context: Context,
  private val ioDispatcher: CoroutineDispatcher,
  private val warmupOnInit: Boolean,
) : ViewModel() {

  @Inject
  constructor(
    savedStateHandle: SavedStateHandle,
    projectRepository: ProjectRepository,
    projectFileDao: ProjectFileDao,
    embedderGate: EmbedderGate,
    errorLog: ErrorLog,
    @ApplicationContext context: Context,
  ) : this(
    projectId = requireNotNull(savedStateHandle.get<Long>(NAV_ARG_PROJECT_ID)) {
      "ProjectDetailViewModel requires '$NAV_ARG_PROJECT_ID' nav arg"
    },
    projectRepository = projectRepository,
    projectFileDao = projectFileDao,
    embedderGate = embedderGate,
    errorLog = errorLog,
    context = context,
    ioDispatcher = Dispatchers.IO,
    warmupOnInit = true,
  )

  init {
    if (warmupOnInit) {
      viewModelScope.launch { embedderGate.warmup() }
    }
  }

  /** Hot stream of the underlying project row — emits `null` once the project is deleted. */
  val project: StateFlow<ProjectEntity?> = projectRepository.observeProjectById(projectId)
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = null,
    )

  /** Files in this project, ordered by `created_at ASC` (Room-side). */
  val files: StateFlow<List<ProjectFileEntity>> = projectRepository.observeFiles(projectId)
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = emptyList(),
    )

  val embedderState: StateFlow<EmbedderState> = embedderGate.state

  /** True when the embedder is downloaded (any state other than NotDownloaded). */
  val fabEnabled: StateFlow<Boolean> = embedderState
    .map { it !is EmbedderState.NotDownloaded }
    .stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = false,
    )

  /**
   * In-session dismissal — Set of file ids the user explicitly cleared from the banner.
   * Not persisted (task spec: «Dismiss скрывает баннер до следующего захода на экран»).
   */
  private val dismissedBannerFileIds: MutableStateFlow<Set<Long>> = MutableStateFlow(emptySet())

  /**
   * Files that need the «Переиндексировать» action surface — failed with the IngestWorker
   * cancellation marker, minus user-dismissed rows for the current session.
   */
  val failedDocsBanner: StateFlow<List<ProjectFileEntity>> =
    combine(files, dismissedBannerFileIds) { rows, dismissed ->
      rows.filter {
        it.status == PROJECT_FILE_STATUS_FAILED &&
          it.statusMessage == STATUS_MESSAGE_INTERRUPTED &&
          it.id !in dismissed
      }
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000L),
      initialValue = emptyList(),
    )

  private val _events: MutableSharedFlow<ProjectDetailEvent> =
    MutableSharedFlow(extraBufferCapacity = 4)

  /** One-shot UI events (snackbars, navigation requests). */
  val events: SharedFlow<ProjectDetailEvent> = _events.asSharedFlow()

  /**
   * Race-guard: hashes whose `processUri` is in flight. Cleared in `finally` so a thrown
   * exception cannot strand a hash and silently mute future submissions.
   */
  private val inFlightHashes = ConcurrentHashMap.newKeySet<String>()

  fun dismissFailedDocsBanner() {
    val current = failedDocsBanner.value
    dismissedBannerFileIds.value = dismissedBannerFileIds.value + current.map { it.id }
  }

  fun onFabTapWhileDisabled() {
    viewModelScope.launch { _events.emit(ProjectDetailEvent.EmbedderRequired) }
  }

  fun requestModelManagerNav() {
    viewModelScope.launch { _events.emit(ProjectDetailEvent.NavigateToModelManager) }
  }

  fun addDocuments(uris: List<Uri>) {
    if (uris.isEmpty()) return
    viewModelScope.launch {
      withContext(ioDispatcher) {
        uris.forEach { uri -> processUri(uri) }
      }
    }
  }

  private suspend fun processUri(uri: Uri) {
    val displayName = resolveDisplayName(uri)
    val hash = readContentHash(uri)
    if (hash == null) {
      errorLog.e("rag-index", "addDocuments: failed to read hash for $displayName")
      _events.emit(ProjectDetailEvent.DocumentImportFailed)
      return
    }

    // Reserve the hash slot before doing any further work. If another in-flight processUri
    // is already handling this hash (multi-select duplicate or double-submit), short-circuit
    // with the duplicate signal — the persisted dedup below would also catch it once the
    // first run finishes, but skipping early saves a stream copy + IO call.
    if (!inFlightHashes.add(hash)) {
      _events.emit(ProjectDetailEvent.DuplicateDocument)
      return
    }

    try {
      // Persisted dedup: same hash already in this project's file table.
      if (projectFileDao.getByProjectAndHash(projectId, hash) != null) {
        _events.emit(ProjectDetailEvent.DuplicateDocument)
        return
      }

      val projectsDir = File(context.filesDir, "projects/$projectId/docs")
      if (!projectsDir.exists() && !projectsDir.mkdirs()) {
        errorLog.e("rag-index", "addDocuments: failed to create dir $projectsDir")
        _events.emit(ProjectDetailEvent.DocumentImportFailed)
        return
      }
      val targetFile = File(projectsDir, "${UUID.randomUUID()}.pdf")
      val copied = copyUriToFile(uri, targetFile)
      if (!copied) {
        // copy failed mid-way — best-effort remove partial bytes and surface the error.
        if (targetFile.exists() && !targetFile.delete()) {
          errorLog.e(
            "rag-index",
            "addDocuments: failed to remove partial copy ${targetFile.absolutePath}",
          )
        }
        _events.emit(ProjectDetailEvent.DocumentImportFailed)
        return
      }

      // Store the path relative to `filesDir` — matches the convention DefaultProjectRepository
      // expects in `deleteFile` (joins relativePath against filesDir for the disk cleanup).
      val relativePath = "projects/$projectId/docs/${targetFile.name}"
      val fileId = projectRepository.addFile(
        projectId = projectId,
        fileName = displayName,
        contentHash = hash,
        localPath = relativePath,
      )
      projectRepository.enqueueIngest(projectId, fileId, targetFile.absolutePath)
    } finally {
      inFlightHashes.remove(hash)
    }
  }

  private fun resolveDisplayName(uri: Uri): String {
    // SAF Uris carry the original file name in OpenableColumns.DISPLAY_NAME; fall back to the
    // last path segment when the cursor is empty (e.g. file:// uris in tests).
    val resolver = context.contentResolver
    return try {
      resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx) else null
          } else null
        } ?: uri.lastPathSegment ?: "document.pdf"
    } catch (_: Throwable) {
      uri.lastPathSegment ?: "document.pdf"
    }
  }

  private fun readContentHash(uri: Uri): String? {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8 * 1024)
    return try {
      context.contentResolver.openInputStream(uri)?.use { input ->
        var totalRead = 0
        while (totalRead < HASH_BYTES) {
          val want = minOf(buffer.size, HASH_BYTES - totalRead)
          val read = input.read(buffer, 0, want)
          if (read <= 0) break
          digest.update(buffer, 0, read)
          totalRead += read
        }
        if (totalRead == 0) null else digest.digest().toHex()
      }
    } catch (_: IOException) {
      null
    } catch (_: SecurityException) {
      null
    }
  }

  private suspend fun copyUriToFile(uri: Uri, target: File): Boolean = try {
    context.contentResolver.openInputStream(uri)?.use { input ->
      target.outputStream().use { output ->
        input.copyTo(output)
      }
      true
    } ?: false
  } catch (e: IOException) {
    errorLog.e("rag-index", "stream copy failed for ${target.absolutePath} :: ${e.message}")
    false
  }

  fun reindex(fileId: Long) {
    viewModelScope.launch {
      withContext(ioDispatcher) {
        projectRepository.reindexFile(fileId)
      }
      // Reindex resolves the banner for this row — drop it from dismissed so a future
      // genuine failure for the same row surfaces again.
      dismissedBannerFileIds.value = dismissedBannerFileIds.value - fileId
    }
  }

  fun deleteFile(fileId: Long) {
    viewModelScope.launch {
      withContext(ioDispatcher) {
        projectRepository.deleteFile(fileId, context.filesDir)
      }
    }
  }

  fun deleteProject() {
    viewModelScope.launch {
      withContext(ioDispatcher) {
        projectRepository.delete(projectId, context.filesDir)
      }
      _events.emit(ProjectDetailEvent.ProjectDeleted)
    }
  }

  companion object {
    /**
     * The default chat model id for a freshly-created project chat — Decision 16 cascade:
     * `project.defaultModelId ?: appSettings.defaultModelId ?: appSettings.lastUsedModelId`.
     * The actual resolution is performed by [ChatViewModel] on Persistent chat bootstrap;
     * this helper exists so [ProjectDetailScreen]'s «+ Новый чат» tap can pre-compute the
     * project's contribution without duplicating the cascade in another consumer.
     */
    fun resolveDefaultModelId(project: ProjectEntity?): String? = project?.defaultModelId
  }
}

/** One-shot UI events emitted by [ProjectDetailViewModel] for snackbar / navigation. */
sealed class ProjectDetailEvent {
  data object DuplicateDocument : ProjectDetailEvent()
  data object EmbedderRequired : ProjectDetailEvent()
  data object NavigateToModelManager : ProjectDetailEvent()
  data object ProjectDeleted : ProjectDetailEvent()
  data object DocumentImportFailed : ProjectDetailEvent()
}

private fun ByteArray.toHex(): String {
  val out = StringBuilder(size * 2)
  for (b in this) {
    val v = b.toInt() and 0xff
    out.append(HEX_CHARS[v ushr 4])
    out.append(HEX_CHARS[v and 0x0f])
  }
  return out.toString()
}

private val HEX_CHARS: CharArray = "0123456789abcdef".toCharArray()
