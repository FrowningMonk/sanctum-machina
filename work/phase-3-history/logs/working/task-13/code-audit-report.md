# Phase-3 Code Audit Report

**Scope:** all source files created or modified by Phase-3 tasks 0–12, 17, 18 (per `work/phase-3-history/decisions.md`).
**Auditor:** main agent (Task 13).
**Date:** 2026-04-22.
**Deliverable:** this document. No code changes were made.

---

## Summary

Phase-3 implementation is in solid shape. The tech-spec's seven focus areas pass with one *Major* finding (Main-thread Bitmap/WAV decode in `ChatViewModel.buildMessagesFlow`) and a small tail of *Minor* / *Suggestion* items. All seven invariants the audit was asked to verify (single-engine, `ChatIdentity` branch coverage, staging cleanup, Hilt scopes, `modelId` vs `name` discipline, `onCleared` hygiene, `sweepZombieChats`) are preserved. The `runBlocking` inside `AppModule.provideSanctumDatabase` is the only other notable concern and is a conscious Hilt-provider trade-off documented in Task 6.

**Verdicts by focus area:**

| # | Area | Verdict |
|---|---|---|
| 1 | Single-engine invariant (`WarmupCoordinator`) | PASS |
| 2 | `ChatIdentity` branch coverage (`ChatViewModel`) | PASS |
| 3 | Staging-dir cleanup completeness | PASS |
| 4 | No blocking calls on Main thread | **PASS WITH NOTES** (Major: Room→BitmapFactory on Main; Minor: `runBlocking` in DB provider) |
| 5 | Hilt scope correctness | PASS |
| 6 | No `Model.name` vs `Model.modelId` mix | PASS |
| 7 | `onCleared()` does not call `registry.cleanup()` | PASS |

**Finding counts:** 0 Critical · 1 Major · 5 Minor · 3 Suggestion.

---

## Critical findings

None.

---

## Major findings

### M1. Room-backed message flow decodes Bitmap/WAV on Main thread

- **Affected files / lines:**
  - `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt:785-814` (`buildMessagesFlow`)
  - `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt:1378-1466` (`toDomainMessageWithAttachments` → `decodeAttachmentsForEntity` → `decodeImageFromRelativePath` / `decodeAudioFromRelativePath`)
- **Description:** In `buildMessagesFlow` (Persistent identity) the DAO flow chain is:
  ```kotlin
  val decoded = messageDao.observeByChat(id.id)
      .map { list -> list.map { toDomainMessageWithAttachments(it) } }
  combine(decoded, _streamingMessage) { ... }
      .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
  ```
  There is no `.flowOn(Dispatchers.IO)` on the `decoded` flow. Room's internal `flowOn(queryExecutor)` applies only to the UPSTREAM query emission — the downstream `.map` operator (and the `combine` transformer) run in the COLLECTOR context, which for `viewModelScope.stateIn(...)` is `Dispatchers.Main.immediate`. The `toDomainMessageWithAttachments` function calls `BitmapFactory.decodeFile(...)` (line 1447) and `wavToPcm(file)` (line 1464) directly. On the first emission carrying new rows these calls execute on Main.

  The comment at lines 789-793 explicitly states the decode work "inherits" the Room executor context "no explicit `flowOn(Dispatchers.IO)` is needed". This is incorrect for downstream operators. The KDoc on `toDomainMessageWithAttachments` (line 1384) further says "Called from the `.map { }.flowOn(Dispatchers.IO)` stage of [buildMessagesFlow]" — but no such `flowOn` exists.

  `attachmentCache` mitigates repeat emissions, but does not help on first chat-open with N history rows carrying attachments. A 20-row history with mid-size PNGs can easily spend 300–600 ms of BitmapFactory / PCM decode work on Main, dropping frames and violating AC-A1 ("all file I/O confirmed running on `Dispatchers.IO`"). Long chats after Phase 4 will degrade further.

- **Recommended fix:** Insert `.flowOn(Dispatchers.IO)` between the `.map { toDomainMessageWithAttachments(...) }` and the `combine` so the decode executes on IO:
  ```kotlin
  val decoded = messageDao.observeByChat(id.id)
      .map { list -> list.map { toDomainMessageWithAttachments(it) } }
      .flowOn(Dispatchers.IO)
  ```
  The unit-test concern noted in the existing comment (UnconfinedTestDispatcher flakiness) can be handled by making the dispatcher injectable in the VM's `@VisibleForTesting` constructor and defaulting to `Dispatchers.IO` in production — the same seam pattern used in `DefaultChatRepository` and `WarmupCoordinator`. Delete the now-stale comment block at lines 788-794.

---

## Minor findings

### m1. `runBlocking` inside `AppModule.provideSanctumDatabase`

- **Affected file / lines:** `app/src/main/kotlin/app/sanctum/machina/di/AppModule.kt:93-95`
- **Description:** On DB open failure, `runBlocking { errorLog.e(LOG_COMPONENT, ...) }` is called inside a synchronous Hilt `@Provides` method. In practice this runs on whatever thread performs the first injection of `SanctumDatabase`. Because `WarmupCoordinator` is injected into `SanctumApplication` and kicks off its own scope, the provider itself is also typically hit off-Main — but this is not guaranteed by Hilt; if a future Activity injects `SanctumDatabase` eagerly via field injection, the `runBlocking` could land on Main during the corruption recovery path.
  `ErrorLog.e` already uses a `Mutex` and hops to `Dispatchers.IO`, so the `runBlocking` nests a Main-waiting coroutine on an IO-bound lock. Task-6 security-auditor round 1 flagged this as "ANR-class" and the team deferred it. The finding is kept here as an artefact of the audit surface — behaviour is not regressed.
- **Recommended fix:** Option A — refactor corruption logging to a fire-and-forget `ProcessLifecycleOwner`-scoped coroutine launched from `SanctumApplication` after the DB has been provided; Option B — accept the trade-off and wrap the logging in a dedicated `CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { }` (no `runBlocking`) since the provider only needs the side-effect, not the `Result`.

### m2. `DefaultChatRepository.updateChatLastMessage` uses read-modify-write without a focused `@Query`

- **Affected file / lines:** `app/src/main/kotlin/app/sanctum/machina/data/DefaultChatRepository.kt:304-309` (also `updateChatTitle` at 311-325)
- **Description:** Both updates do `val current = chatDao.getById(chatId); chatDao.update(current.copy(...))` outside a transaction. In the absence of a serialising caller (e.g. concurrent `updateChatLastMessage` + `updateChatTitle`) this is a lost-update risk. Phase-3 code paths call these methods from within sequential VM coroutines on `viewModelScope`, so the race is not currently reachable — but the contract is weaker than it needs to be.
- **Recommended fix:** Add focused `@Query("UPDATE chats SET last_message_at = :ts WHERE id = :id")` / `@Query("UPDATE chats SET title = :title, is_manually_titled = :flag WHERE id = :id")` methods to `ChatDao`. Task-4 round-1 code-reviewer raised this as T4-m2 and it was explicitly deferred; the audit leaves it parked but flags it here so it isn't lost.

### m3. `AutoTitleGenerator.fallbackTitle` uses `Locale.getDefault()` inside `SimpleDateFormat`

- **Affected file / lines:** `app/src/main/kotlin/app/sanctum/machina/data/AutoTitleGenerator.kt:38-43`
- **Description:** The fallback title is built with `SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())`. This is correct behaviour (the title is user-visible), but `SimpleDateFormat` is not thread-safe and is allocated per call. Not a bug — just an allocation hot-spot if the app ever starts generating titles in a tight loop (e.g. bulk import). Parity with `DrawerViewModel.formatRelative` which has the same pattern.
- **Recommended fix:** None required for Phase 3. If touched later, migrate to `DateTimeFormatter.ofPattern` / `Instant.atZone(...)` (already used in `DrawerViewModel.buildSections`).

### m4. `DefaultChatRepository.sweepZombieChats` uses `observeAll().first()` instead of a suspend getter

- **Affected file / lines:** `app/src/main/kotlin/app/sanctum/machina/data/DefaultChatRepository.kt:356-374`
- **Description:** `observeAll()` is a hot `Flow` subscribed to by `DrawerViewModel`. Calling `.first()` from `sweepZombieChats` starts an extra subscription for a single snapshot read, with unclear interaction with Room's invalidation tracker. A `@Query` `suspend fun getAll(): List<ChatEntity>` would be cleaner.
- **Recommended fix:** Add `suspend fun getAll(): List<ChatEntity>` to `ChatDao` and use it here. Task-4 round-1 code-reviewer raised this as T4-m3 and deferred it.

### m5. KDoc on `toDomainMessageWithAttachments` mentions a `flowOn(Dispatchers.IO)` stage that does not exist

- **Affected file / lines:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt:1384`
- **Description:** Stale KDoc referencing a design that didn't ship. Cross-references the M1 finding above; if M1 is accepted the comment becomes accurate and this item closes. If M1 is rejected the comment must be corrected to match reality.

---

## Suggestions

### s1. Consider consolidating the `@VisibleForTesting` constructor pattern

- **Affected files:** `DefaultChatRepository`, `WarmupCoordinator`, `SettingsMigrationHelper`, `StartupHousekeeper`, `DrawerViewModel`.
- **Observation:** Five Phase-3 classes use the same idiom of a `@VisibleForTesting` primary constructor with test seams (dispatcher, rename function, clock, deleter) and an `@Inject` secondary constructor. The pattern is clean but duplicated. A follow-up refactor could extract the seam shape into a small `TestSeam`-style pattern or just lean on `@AssistedInject` for a subset of these.
- **Priority:** Low. The current duplication is honest and locally clear.

### s2. `WarmupCoordinator.scope` leak on test teardown

- **Affected file / lines:** `app/src/main/kotlin/app/sanctum/machina/engine/WarmupCoordinator.kt:65` (default scope = `CoroutineScope(SupervisorJob() + Dispatchers.Default)`)
- **Observation:** The production scope has no cancellation path — the coordinator lives for the app lifetime. In tests this is a known-issue; `test-reviewer-2` round 3 for Task 5 marked it as non-blocking. Not a Phase-3 regression, but worth noting for any future integration-test refactor.

### s3. `DrawerViewModel.formatRelative` allocates a `SimpleDateFormat` per row per emission

- **Affected file / lines:** `app/src/main/kotlin/app/sanctum/machina/ui/drawer/DrawerViewModel.kt:261-268`
- **Observation:** Same concern as m3. Mirror fix: cache two formatters (today pattern + date pattern) as `@Volatile var` or `ThreadLocal` if thread-safety matters. Negligible impact until the drawer has hundreds of chats.

---

## Per-focus-area verdict

### 1. Single-engine invariant — PASS

- `WarmupCoordinator.launchWarmup` (lines 118-156) holds `restartMutex.withLock { ... }`, nulls out `warmupJob` and sets `_isWarmupInProgress = true` BEFORE `previous?.cancelAndJoin()`, then launches the new Job. The in-flight Job is cancelled-and-joined before any call to `registry.initialize()` for the new target. The inner `finally { if (warmupJob === coroutineContext[Job]) _isWarmupInProgress.value = false }` prevents a transient `false` flicker during cross-model handover.
- `SanctumApplication.onCreate` (line 38) calls `warmupCoordinator.warmupDefault()` inside `if (getProcessName() == packageName)` AFTER `installCrashHandler()` at line 33. ✓
- AC-F3 observer lives in `WarmupCoordinator.startDefaultModelObserver` (lines 171-203). It collects `registry.models`, picks the first `ModelEntry` with `downloadStatus.status == SUCCEEDED`, and writes `default_model_id` only when `appSettings.getDefaultModelId().isEmpty()`. The post-Task-11 follow-up (commit `b5beac5`) also triggers `launchWarmup(modelId)` on that path, and the observer cancels its own Job after the one-shot write to avoid repeat firings. ✓
- `DefaultModelRegistry.initialize` (lines 218-227) additionally releases every OTHER model currently in `Ready` OR `Initializing` before flipping the target to `Initializing`, under `lifecycleMutex`. This is the second layer of defence that covers the race the post-smoke round fix `58bb44e` addressed.

### 2. `ChatIdentity` branch coverage — PASS

- All three branches are enumerated in `ChatIdentity` (see references in `ChatViewModel.kt:289-293` `send()` dispatch, `buildMessagesFlow` at lines 785-814, `bootstrapChatModelId` at lines 729-783).
- **Quick**: engine state observed reactively via `observeEngineState` + `_chatModelId` seeded from `explicitModelId ?: registry.activeModelName` (lines 752-780). No `MessageDao` write paths in the Quick send chain (`runInferenceQuick` at 1002-1010 operates on in-memory `_messages`). `onCleared` does not call `registry.cleanup`.
- **Draft**: `commitDraft` (lines 911-1000) writes via `chatRepository.commitDraftChat`, emits `ChatNavigationEvent.NavigateToPersistent(chatId)`, and the nav host uses `popUpTo("chat/draft") { inclusive = true }` (`SanctumApp.kt:107-109`). Staging is delegated to the repository. ✓
- **Persistent**: `buildMessagesFlow` uses `messageDao.observeByChat(chatId)` combined with `_streamingMessage` (with the atomic double-bubble handover at lines 798-807). Cross-model "Загрузить" goes through `loadModel` → `warmupCoordinator.cancelAndRestart` (lines 358-361), which re-pins `_chatModelId` before delegating (post-smoke fix `3fc1104`). The B4 auto-resume path (`observeFirstReadyThenResume`, `resumePendingAssistantPersistent`) is also Persistent-only. ✓

### 3. Staging-dir cleanup — PASS

- `commitDraftChat` success path: rename into `attachments/{chatId}/` inside the Room `withTransaction { ... }` (lines 135-159). `rename()` returns false → thrown `IOException` inside the transaction → Room rolls back.
- `commitDraftChat` failure path: outer `catch (t: Throwable) { stagingDir?.deleteRecursively(); throw t }` (lines 160-166). Partial payload from `writeAttachmentPayload` is cleaned up in-place (lines 257-273).
- `pruneStagingDir` removes non-referenced attachments right before the commit rename (invoked at `ChatViewModel.kt:964-970`). Per-file failures are logged under `attachment-save` and do not abort the commit.
- `StartupHousekeeper.cleanupOrphanStagingDirs` (lines 80-93) lists `.staging-*` under `filesDir/attachments/` and recursively deletes each; failures logged under `attachment-save` and swallowed. `purgeQuickDir` and `chatRepository.sweepZombieChats` round out the startup sweep. ✓

### 4. No blocking calls on Main thread — PASS WITH NOTES

- `DefaultChatRepository` runs every I/O method under `withContext(ioDispatcher)`. `commitDraftChat`, `writeAttachmentStaging`, `savePersistentAttachment`, `savePersistentMessage`, `updateChatLastMessage`, `updateChatTitle`, `deleteChat`, `sweepZombieChats`, `pruneStagingDir`, `deleteStagedAttachment` — all `withContext(ioDispatcher) { ... }`. ✓
- `WarmupCoordinator.launchWarmup` runs on its injected scope (`Dispatchers.Default`); `DefaultModelRegistry.initialize` uses `withContext(Dispatchers.Default)`. No Main-thread engine work. ✓
- `StartupHousekeeper` is scheduled by `SanctumApplication.onCreate` on `appScope.launch(Dispatchers.IO)`. ✓
- `ErrorLog.e` hops to `Dispatchers.IO` internally. ✓
- **Note (M1):** `ChatViewModel.buildMessagesFlow` → `toDomainMessageWithAttachments` runs `BitmapFactory.decodeFile` and `wavToPcm(file)` in the collector context (Main). See finding M1.
- **Note (m1):** `AppModule.provideSanctumDatabase` uses `runBlocking { errorLog.e(...) }` in the corruption-recovery branch. See finding m1.
- No `runBlocking` elsewhere in production code (grepped `app/src/main`).

### 5. Hilt scope correctness — PASS

All tech-spec-mandated `@Singleton` annotations are present:
- `WarmupCoordinator` — `@Singleton` (line 46). ✓
- `SanctumDatabase` — via `@Provides @Singleton` in `AppModule.provideSanctumDatabase`. ✓
- `ChatRepository` — `@Binds @Singleton` in `AppModule.bindChatRepository`. ✓
- `AppCorruptionState` — `@Singleton` (line 16). ✓
- `SettingsMigrationHelper` — `@Singleton` (line 28). ✓
- `StartupHousekeeper` — `@Singleton` (line 38). ✓
- `ChatDao`, `MessageDao` — `@Provides @Singleton` off the DB. ✓
- `DefaultModelRegistry` — `@Singleton` (confirmed via `Singleton` import and annotation at the class level in the read above). ✓
- ViewModels (`ChatViewModel`, `HomeViewModel`, `DrawerViewModel`, `ModelManagerViewModel`) use `@HiltViewModel` — ViewModel scope. ✓

No `@Singleton` leaks into Activity-scoped objects were observed. `SanctumDatabase` is only built inside `AppModule.provideSanctumDatabase`; no other call site constructs it (the dead `SanctumDatabase.create(context)` was removed in Task 6).

### 6. No `Model.name` vs `Model.modelId` mix — PASS

Verified through grep-plus-reasoning across the persistence, settings, and UI surface:

- **Room `chats.model_id`** — `ChatEntity.modelId: String` (line 20); populated by `DefaultChatRepository.commitDraftChat(modelId, ...)` which passes `modelId` through to `ChatEntity(modelId = ...)` (line 128). ✓
- **DataStore `default_model_id` / `last_used_model_id`** — `AppSettings.default_model_id` is set by `WarmupCoordinator.startDefaultModelObserver` with `downloaded.model.modelId` (line 185), and by `ModelManagerViewModel.setDefaultModel(modelId, modelName)` with the HF id (line 89). `last_used_model_id` is set by `WarmupCoordinator.persistLastUsedModelId(modelId)` with the HF id (line 142). ✓
- **DataStore `per_model_overrides`** — keyed by `modelId` since the Task-2 migration. `SettingsMigrationHelper` remaps `name → modelId` once and sets `settings_keys_migrated = true` atomically (lines 39-73). ✓
- **Registry engine calls** — `WarmupCoordinator.launchWarmup` resolves `modelId → entry.model.name` via `registry.models.value.firstOrNull { it.model.modelId == modelId }?.model?.name` and passes the NAME to `registry.initialize(entry.model.name)` (lines 136-141). This is correct: `DefaultModelRegistry.initialize(modelName)` internally keys by `Model.name` (storage filename), so the translation happens exactly at the boundary. Post-Task-11 follow-up commit `85cb71f` codified this.
- **UI display strings** — `TopAppBarState.Ready(modelName)` / `Loading(modelName)` render `Model.name` as a user-facing label. This is display-only and does not affect any persistence path.
- **`DeviceInfoCollector`** — `activeModelId()` and `downloadedModels()` return `Model.modelId` (lines 151-165). ✓
- **`DrawerViewModel.toRow` / `checkModelAvailable`** — look up `ModelEntry` by `entry.model.modelId == chat.modelId` (lines 199-200, 248). ✓
- **`ChatViewModel.bootstrapChatModelId`** — reads `entity?.modelId` from `ChatDao.getById`, not `chat.modelName` or similar (line 735). ✓
- **`chat/{modelName}` tombstone** — still registered in `SanctumApp.kt:150-159` only as a deep-link safety net with a `LaunchedEffect` redirect to `home`. Documented in Task 12 Minor 1; accepted as intentional residual behaviour.

### 7. `ChatViewModel.onCleared()` does not call `registry.cleanup()` — PASS

`ChatViewModel.onCleared()` lives at lines 694-699. Body:
```kotlin
override fun onCleared() {
    super.onCleared()
    // Decision 5 / AC-E6: do NOT call registry.cleanup here. ...
}
```
Grep across `app/src/main` shows only two `registry.cleanup` call sites:
1. `ChatViewModel.kt:493` — inside `applyHeavySetting`, on intentional accelerator-change reinit. Documented in Task 8 deviation M2 and closed architecturally in Task 10 via the Settings IconButton gate (`enabled = !isGenerating && !reinitInProgress`, `ChatScreen.kt`). Acceptable.
2. `ChatViewModel.kt:696` — the comment line itself.

No accidental re-introduction. ✓

---

## Additional checks

These items are out of the 7 focus areas but called out in the task's Additional checks list:

- **`@Transaction` semantics on `commitDraftChat`:** implementation uses `database.withTransaction { ... }` (Room-ktx suspend extension) rather than a DAO-level `@Transaction` annotation. Functionally equivalent for coroutine callers and is the idiomatic choice when the work is cross-DAO. Accepted.
- **`ErrorLog.ALLOWED_COMPONENTS` size = 14:** verified. Order: `download`, `inference-init`, `inference`, `inference-cleanup`, `settings-io`, `camera`, `audio`, `attachment-decode`, `model`, `engine-warmup`, `history-read`, `history-write`, `attachment-save`, `attachment-read`. Matches tech-spec AC exactly.
- **`app_settings.proto`:** `optional string default_model_id = 2`, `optional string last_used_model_id = 3`, `optional bool settings_keys_migrated = 4` — all present.
- **`ModelRegistry` interface:** `val activeModelName: StateFlow<String?>` present at line 37.
- **`Model.kt`:** `val modelId: String = ""` present at line 53.
- **`SanctumDatabase` metadata:** `entities = [ChatEntity, MessageEntity]`, `version = 1`, `exportSchema = true` — lines 11-18. No `project_files` entity.
- **`MessageEntity.chat_id` FK:** `@ForeignKey(entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["chat_id"], onDelete = ForeignKey.CASCADE)` — lines 11-17. ✓
- **`ChatEntity.project_id`:** `val projectId: Long? = null`, no `@ForeignKey` annotation — lines 16-17. ✓
- **Schema JSON present:** `app/schemas/app.sanctum.machina.data.SanctumDatabase/1.json` exists.
- **Module boundary (`:core-runtime` / `:core-settings`):** grep `androidx.compose|androidx.activity|androidx.lifecycle.viewmodel` against `core-runtime/src/main` and `core-settings/src/main` returned zero matches. ✓
- **`DeviceInfoCollector` / `AndroidDeviceInfoProvider`:** primary constructor reads `registry.models.value` through an `entriesProvider` thunk; secondary `(Context)` constructor passes `{ emptyList() }` — crash process returns null/empty stubs. Decision 10 satisfied.
- **`SanctumApp.kt` routes:** `home` is the start destination; `chat/quick?modelId={modelId}`, `chat/draft`, `chat/{chatId}` present; `chat/{modelName}` kept as tombstone only. ✓
- **DB corruption handler:** `AppModule.provideSanctumDatabase` wraps `buildSanctumDatabase` and the forced `openHelper.writableDatabase` in a `try/catch(Exception)`; renames file, deletes sidecars, sets `AppCorruptionState.corruptionOccurred = true`, logs `ErrorLog.e("history-read", ...)`, and rebuilds. ✓

---

## Files reviewed

**New (Phase 3):**
- `app/src/main/kotlin/app/sanctum/machina/data/model/ChatEntity.kt`
- `app/src/main/kotlin/app/sanctum/machina/data/model/MessageEntity.kt`
- `app/src/main/kotlin/app/sanctum/machina/data/dao/ChatDao.kt`
- `app/src/main/kotlin/app/sanctum/machina/data/dao/MessageDao.kt`
- `app/src/main/kotlin/app/sanctum/machina/data/SanctumDatabase.kt`
- `app/src/main/kotlin/app/sanctum/machina/data/ChatRepository.kt`
- `app/src/main/kotlin/app/sanctum/machina/data/DefaultChatRepository.kt`
- `app/src/main/kotlin/app/sanctum/machina/data/AutoTitleGenerator.kt`
- `app/src/main/kotlin/app/sanctum/machina/data/SettingsMigrationHelper.kt`
- `app/src/main/kotlin/app/sanctum/machina/engine/WarmupCoordinator.kt`
- `app/src/main/kotlin/app/sanctum/machina/engine/AppCorruptionState.kt`
- `app/src/main/kotlin/app/sanctum/machina/engine/StartupHousekeeper.kt`
- `app/src/main/kotlin/app/sanctum/machina/di/AppModule.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/home/HomeViewModel.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/drawer/DrawerViewModel.kt`
- `core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/ModelRegistryActiveModelTest.kt` (spot check for test coverage only; full test audit is Task 15)

**Modified (Phase 3):**
- `app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerViewModel.kt`
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ModelRegistry.kt`
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Model.kt`
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`
- `core-settings/src/main/proto/app_settings.proto`
- `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/AppSettingsRepository.kt`
- `app/src/main/kotlin/app/sanctum/machina/logexport/DeviceInfoCollector.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt` (spot-checked for `modelName` use — display-only)
- `app/src/main/kotlin/app/sanctum/machina/ui/home/HomeScreen.kt` (spot-checked for corruption banner wiring)
- `app/src/main/kotlin/app/sanctum/machina/ui/drawer/DrawerContent.kt` (spot-checked for swipe/rename path)
- `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt` (spot-checked for default-model overflow)

**Not reviewed (out of Phase-3 audit scope):**
- Test files other than `ModelRegistryActiveModelTest.kt` — covered by Task 15 (Test Audit).
- `LlmModelHelper`, `DownloadWorker`, `CrashHandler` — Phase 1/2 code unchanged by Phase 3.
