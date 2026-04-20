---
created: 2026-04-20
status: draft
branch: phase/3-history
size: L
---

# Tech Spec: Phase 3 — Chat History

## Solution

Phase 3 solves Sanctum Machina's two critical daily-driver blockers: chat history lost on every process death, and 20–30 s model initialization blocking the first message. Three coordinated pillars:

1. **Room persistence layer.** New `SanctumDatabase` v1 schema: `chats` + `messages` tables. Attachments stored as files in `filesDir/attachments/{chatId}/`; paths referenced from message rows. Quick chats remain fully in-memory; `filesDir/quick/` is purged on each startup.

2. **Application-scope warmup.** `SanctumApplication.onCreate` fires `WarmupCoordinator.warmupDefault()` immediately after CrashHandler install, behind a `getProcessName() == packageName` guard. The coordinator reads `AppSettings.default_model_id` (fallback: `last_used_model_id`) and calls `registry.initialize(modelId)` on a background coroutine. ChatScreen opens optimistically — Room history is visible immediately, Send is disabled until the engine reports `ModelInitStatus.Ready`.

3. **Navigation overhaul.** `HomeScreen` replaces `ModelManagerScreen` as the start destination. `ModalNavigationDrawer` wraps the `NavHost` with the persistent-chat history list. Chat routes change from `chat/{modelName}` to typed routes: `chat/quick`, `chat/draft`, `chat/{chatId}`. A single `ChatViewModel` serves all three modes via a `ChatIdentity` sealed class.

Bundled debt: `Model.name → Model.modelId` DataStore key migration (AC-R8), `ErrorLog` whitelist expansion (+6 components), `DeviceInfoCollector` real-data fix, `imePadding` on the input bar.

---

## Architecture

### What we're building / modifying

**New components:**
- `SanctumDatabase` — Room `@Database`, v1 schema, `exportSchema = true` targeting `app/schemas/`
- `ChatEntity`, `MessageEntity` — Room `@Entity` POJOs
- `ChatDao`, `MessageDao` — Room DAOs (suspend + Flow, no blocking methods)
- `ChatRepository` / `DefaultChatRepository` — orchestrates CRUD, staging file writes, auto-title, zombie sweep
- `AutoTitleGenerator` — pure function: first 20 chars of first USER message text (AC-U2 algorithm)
- `SettingsMigrationHelper` — one-shot atomic DataStore rekey from `Model.name` to `Model.modelId`
- `WarmupCoordinator` — `:app`-level `@Singleton`: holds the cancellable warmup `Job`, resolves default model, updates `last_used_model_id` on success, logs failure
- `AppModule` — Hilt `@InstallIn(SingletonComponent::class)` module in `:app/di/`; provides `SanctumDatabase`, DAOs, `ChatRepository`, `WarmupCoordinator`
- `HomeScreen` + `HomeViewModel` — new start destination; "Новый быстрый чат" button, no-models placeholder
- `DrawerContent` + `DrawerViewModel` — `ModalNavigationDrawer` content: sorted chat list, swipe-delete, long-press rename, empty state, pre-navigation model-unavailable dialog

**Modified components:**
- `SanctumApplication` — warmup wiring via `@Inject WarmupCoordinator`; housekeeping: `filesDir/quick/` purge, orphan staging-dir cleanup, zombie chat sweep, DB corruption handler, DataStore migration trigger, `corruptionOccurred: Boolean` flag for banner
- `SanctumApp.kt` — `ModalNavigationDrawer` wrapper around `NavHost`; new routes: `home`, `chat/quick`, `chat/draft`, `chat/{chatId}`; retire `chat/{modelName}`
- `ChatViewModel` — replace `init {} registry.initialize()` with reactive `registry.models.collect {}` observer; `ChatIdentity` sealed class (`Quick / Draft / Persistent(id)`); Room-backed message flow + in-memory streaming combine; draft→committed atomic transition (`popUpTo("chat/draft"){inclusive=true}`); delete `onCleared()` cleanup call entirely
- `ChatScreen` — TopAppBar state machine (model-picker / "Загрузить" / reinit-spinner / read-only); incognito indicator; `imePadding` on `MultimodalInputBar`; corruption banner on `HomeScreen`
- `ModelManagerScreen` + `ModelManagerViewModel` — ⭐ star on current default, overflow "Сделать по умолчанию", `setDefaultModel(modelId)` method, `onLoad` navigates to `chat/quick?modelId={id}`
- `DefaultModelRegistry` + `ModelRegistry` interface — add `activeModelName: StateFlow<String?>`; derived from `models` StateFlow (name of the first entry in `ModelInitStatus.Ready`)
- `app_settings.proto` — add `optional string default_model_id = 2`, `optional string last_used_model_id = 3`, `optional bool settings_keys_migrated = 4`
- `AppSettingsRepository` / `DefaultAppSettingsRepository` — new methods: `getDefaultModelId()`, `setDefaultModelId()`, `getLastUsedModelId()`, `setLastUsedModelId()`, `isSettingsMigrated()`, `markSettingsMigrated()`
- `Model.kt` + `AllowedModel.kt` — expose `modelId: String` on `Model` (populated from `AllowedModel.modelId` in `toModel()`)
- `ErrorLog.kt` — add to `ALLOWED_COMPONENTS`: `"model"`, `"engine-warmup"`, `"history-read"`, `"history-write"`, `"attachment-save"`, `"attachment-read"` (keeping existing `"settings-io"`)
- `DeviceInfoCollector` / `AndroidDeviceInfoProvider` — implement `activeModelId()` and `downloadedModels()` from `registry.models.value`; secondary (no-Hilt, crash-process) constructor keeps stub returns

### How it works

**Cold start sequence:**
1. `SanctumApplication.onCreate` → `super.onCreate()` triggers Hilt injection → `installCrashHandler()` → (inside `packageName` guard) `warmupCoordinator.warmupDefault()` launched on `Dispatchers.Default`.
2. `WarmupCoordinator` reads `AppSettings.default_model_id` (fallback `last_used_model_id`) → calls `registry.initialize(modelId)` → on success updates `last_used_model_id` → on failure `ErrorLog.e("engine-warmup", ...)`.
3. Concurrent housekeeping (also inside onCreate, separate coroutine): purge `filesDir/quick/`, delete orphan `filesDir/attachments/.staging-*/`, call `chatRepository.sweepZombieChats()`, detect DB corruption.
4. UI starts at `HomeScreen` — warmup runs in the background.

**Persistent chat send sequence (US-2 first message, AC-P7):**
1. User at `chat/draft`, types, taps Send.
2. ChatViewModel (Draft mode): writes attachments to `filesDir/attachments/.staging-{uuid}/` via `chatRepository.writeAttachmentsStaging(attachments)` on `Dispatchers.IO`.
3. `chatRepository.commitDraftChat(model_id, firstMessage, stagingDir)` executes `@Transaction`: INSERT `chats` → capture `chatId` → INSERT `messages` → rename staging dir to `filesDir/attachments/{chatId}/`.
4. On transaction success: `navController.navigate("chat/$chatId") { popUpTo("chat/draft") { inclusive = true } }`.
5. On `done=true` from engine: Room INSERT ASSISTANT message, update `chats.last_message_at`, run `autoTitleGenerator` if `!chat.isManuallyTitled`.

**Optimistic-UI message display:**
- `persistedMessages: Flow<List<MessageEntity>> = messageDao.observeByChat(chatId)` (Room-sourced).
- `_streamingMessage: StateFlow<Message?>` (in-memory, current streaming ASSISTANT bubble).
- UI: `val displayMessages = combine(persistedMessages, _streamingMessage) { persisted, streaming -> persisted.map { it.toDomain() } + listOfNotNull(streaming) }`.
- Atomic handover at `done=true`: Room INSERT fires, persisted list emits with new ASSISTANT row, VM clears `_streamingMessage` in the same `mapLatest` block observing the new emission — prevents double-bubble flicker.

**Cross-model read-first (US-4):**
- Drawer tap → chat's `model_id ≠ registry.activeModelName.value` → navigate immediately to `chat/{chatId}` (no pre-nav dialog).
- ChatScreen: `displayMessages` from Room visible instantly. `ChatIdentity.Persistent` mode observes registry status for `chat.model_id` — status is `Idle` (not the warm model), so TopAppBar shows "Загрузить", Send is disabled.
- User taps "Загрузить" → `warmupCoordinator.cancelAndRestart(chat.model_id)` → `registry.cleanup(activeModel) + registry.initialize(chat.model_id)`.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|---|---|---|---|
| `SanctumDatabase` | `AppModule` (`@Singleton @Provides`) | `ChatDao`, `MessageDao` (provided from DB instance) | 1 (process singleton) |
| `ModelRegistry` (engine) | `CoreRuntimeModule` | `ChatViewModel`, `WarmupCoordinator`, `ModelManagerViewModel` | 1 (process singleton) |
| `WarmupCoordinator` | `AppModule` (`@Singleton`) | `SanctumApplication` (drives warmup), `ChatViewModel` (calls `cancelAndRestart`) | 1 (app singleton) |
| `AppSettingsRepository` | `CoreSettingsModule` | `WarmupCoordinator`, `ChatViewModel`, `ModelManagerViewModel`, `SettingsMigrationHelper` | 1 (process singleton) |

---

## Decisions

### Decision 1: V1 Room schema — no `projects` table, no `project_files`, `chats.project_id` nullable without FK
**Decision:** V1 schema contains only `chats` and `messages`. `chats.project_id` is a nullable INTEGER with no FK constraint (projects table doesn't exist yet). `project_files` table is not created.
**Rationale:** Supports US-1–US-13 fully. Projects ship in Phase 4 via `Migration(1,2)`. Adding a `projects` FK in v1 without the referenced table would be invalid SQL. An empty `project_files` table creates dead schema and confuses static analysis.
**Alternatives considered:** Create stub tables now (architecture.md described this as the plan). Rejected — user-spec AC-R4 and AC-R7 explicitly override this. A clean Migration(1,2) in Phase 4 is simpler than working around dead tables.
**References:** AC-R4, AC-R7

### Decision 2: Single `ChatViewModel` with `ChatIdentity` sealed class
**Decision:** One `ChatViewModel` class handles Quick / Draft / Persistent modes, reading mode from `SavedStateHandle` nav args. `ChatIdentity` = `sealed { object Quick; object Draft; data class Persistent(val id: Long) }`.
**Rationale:** All three modes share 90% of logic: engine state observation, attachment handling, streaming, TopAppBar state. Separate VMs would duplicate this and complicate the `SavedStateHandle` / rotation story. Mode-specific branches are small and clearly guarded.
**Alternatives considered:** Separate `QuickChatViewModel` + `PersistentChatViewModel`. Rejected — significant duplication, harder to test shared state transitions.
**References:** AC-P5, AC-E3, AC-E4, AC-E7, US-1, US-2

### Decision 3: `WarmupCoordinator` as `:app`-level singleton (not embedded in `DefaultModelRegistry`)
**Decision:** New `WarmupCoordinator` class in `app/engine/`. It holds the cancellable warmup `Job`, reads `AppSettingsRepository` for model resolution, and calls `registry.initialize()`. `DefaultModelRegistry` gains only `activeModelName: StateFlow<String?>`.
**Rationale:** `DefaultModelRegistry` lives in `:core-runtime`, which has no dependency on `:core-settings`. Placing warmup logic there would require a new cross-module dependency. The coordinator belongs in `:app` where it can freely access both modules. Also improves testability — coordinator mocks registry and settings.
**Alternatives considered:** Embed warmup in `DefaultModelRegistry`. Rejected — breaks module boundary (`:core-runtime` cannot depend on `:core-settings`).
**References:** AC-E1, AC-E5, user-spec Risk #8

### Decision 4: In-memory streaming state + Room combine pattern (AC-R2)
**Decision:** Room stores only committed messages (USER at send-time, ASSISTANT at `done=true`). Streaming ASSISTANT token accumulation is `_streamingMessage: StateFlow<Message?>` in VM. UI combines both flows.
**Rationale:** At 10–50 tokens/s, writing to Room per-token would cause Flow backpressure and I/O-bound UI (`J1` hazard in code-research). The combine pattern keeps inference performance identical to Phase 2 (in-memory accumulation) while adding persistence for completed messages.
**Alternatives considered:** Write ASSISTANT to Room incrementally. Rejected — I/O bound, complex partial-message semantics (what does a partial ASSISTANT row mean on reopen?).
**References:** AC-R2, AC-R3

### Decision 5: `ChatViewModel.onCleared()` does NOT call `registry.cleanup()` (AC-E6)
**Decision:** Delete the `CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { registry.cleanup(modelName) }` block from `ChatViewModel.onCleared()` entirely. Engine cleanup happens only on explicit cross-model confirm or process death.
**Rationale:** With Application-scope warmup, the engine outlives any single `NavBackStackEntry`. The current `onCleared` tears down the warm engine on every Back navigation from a chat, making background warmup useless. The engine lifecycle is now owned by `WarmupCoordinator` / `SanctumApplication`, not the VM.
**Alternatives considered:** Gate cleanup on "last observer" refcount. Rejected — unnecessary complexity; process death already handles cleanup, cross-model handles explicit cleanup.
**References:** AC-E6, user-spec Risk #1, code-research J2

### Decision 6: Staging directory for multi-attachment atomicity
**Decision:** All attachment writes go through `filesDir/attachments/.staging-{uuid}/` first; renamed to `filesDir/attachments/{chatId}/` only after the Room `@Transaction` (INSERT chats + messages) succeeds. Orphan `.staging-*` dirs cleaned up in `SanctumApplication.onCreate`.
**Rationale:** Android's filesystem `rename()` is atomic within the same partition. This is the only way to guarantee "all files or none" without transactional FS primitives. Orphan cleanup handles crash-midway scenarios.
**Alternatives considered:** Write directly to `{chatId}/`. Rejected — if Room INSERT fails after partial file write, orphaned files accumulate silently.
**References:** AC-A6, AC-A3, user-spec Risk #11

### Decision 7: `activeModelName: StateFlow<String?>` as single source of truth for engine state
**Decision:** `DefaultModelRegistry` adds `activeModelName: StateFlow<String?>` — the name of the model currently in `ModelInitStatus.Ready` state (null if none). Derived from `models` StateFlow. `ChatViewModel` observes this to determine whether the chat's model is ready.
**Rationale:** Code-research K1 confirms `ModelInitStatus` lives inside `ModelEntry` in `registry.models`. A dedicated `StateFlow<String?>` avoids repeated `.find {}` transforms in multiple UI consumers. Used by `DrawerContent` (pre-nav check) and `ChatViewModel` (TopAppBar state machine).
**Alternatives considered:** Expose `ModelInitStatus` per model via a `statusOf(modelName)` flow. Both approaches are valid; `activeModelName` is simpler since only one model is ever `Ready` at a time (single-engine invariant).
**References:** AC-E2, AC-E3, AC-U6, user-spec Risk #6

### Decision 8: One-shot DataStore migration with atomic `updateData` + sentinel `settings_keys_migrated`
**Decision:** On first Phase 3 startup, `SettingsMigrationHelper.migrateIfNeeded()` reads all `per_model_overrides` keys, remaps from `Model.name` to `Model.modelId`, drops orphan keys (no matching allowlist entry), and writes the result in a single `DataStore.updateData { ... }`. After completion, sets `AppSettings.settings_keys_migrated = true`. Subsequent startups check the sentinel and skip.
**Rationale:** DataStore's `updateData` is atomic — a crash mid-migration leaves either the old state or the new state, never a partial mix. The sentinel prevents re-running on every cold start. Orphan keys are logged via `ErrorLog.e("settings-io", "orphan key ...", null)`.
**Alternatives considered:** Lazy migration (migrate one key per access). Rejected — creates a split-state window where some keys are new format and some are old, causing silent misses.
**References:** AC-R8, user-spec Risk #2

### Decision 9: DB corruption handler — rename + fresh DB + in-memory banner signal
**Decision:** `SanctumApplication.onCreate` wraps `Room.databaseBuilder.build()` in `try/catch`. On exception: rename `sanctum.db` to `sanctum.db.corrupt_{YYYYMMDD-HHmmss}`, create a fresh empty DB, set `corruptionOccurred = true` on a singleton state object, log `ErrorLog.e("history-read", "db open failed — renamed", cause)`. `HomeScreen` observes this flag and shows a one-time banner with "Сохранить лог" / "Закрыть" actions.
**Rationale:** A corrupt DB on startup must not crash the app — that defeats the purpose of `CrashHandler`. The renamed backup preserves the corrupt file for the developer's analysis. The banner is in-memory (not persisted) so it won't reappear after app restart.
**Alternatives considered:** Fallback silently with empty history. Rejected — user loses trust without explanation. Adding a banner is minimal UX cost.
**References:** AC-D5, US-14

### Decision 10: `DeviceInfoCollector` secondary constructor keeps null stubs for `:crash` process
**Decision:** `AndroidDeviceInfoProvider.activeModelId()` and `downloadedModels()` are implemented from `registry.models.value` in the primary Hilt-injected constructor. The secondary no-arg constructor (used by `LogExportManager(context)` in the `:crash` process) retains `null` / `emptyList()` stubs — the crash process has no running engine, so these values are genuinely unavailable.
**Rationale:** Code-research K5 confirms `AndroidDeviceInfoProvider(context)` is called without Hilt in the crash recovery path. Injecting `ModelRegistry` there would require a non-Hilt graph in the crash process, violating the isolation established in Phase 2.5. Stubs in crash context are honest — there IS no active model when the app crashed.
**Alternatives considered:** Shared nullable ModelRegistry parameter on secondary constructor. Viable but adds complexity without real benefit; crash logs don't need inference state.
**References:** AC-D1, US-10

---

## Data Models

### Room v1 Schema (`SanctumDatabase`, version 1)

```sql
CREATE TABLE chats (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id    INTEGER,                      -- nullable; no FK (projects table added via Migration(1,2) in Phase 4)
    model_id      TEXT    NOT NULL,             -- stable HF id: "litert-community/gemma-4-E4B-it-litert-lm"
    title         TEXT,                         -- NULL until auto-title runs; user rename sets is_manually_titled=1
    is_manually_titled INTEGER NOT NULL DEFAULT 0,
    created_at    INTEGER NOT NULL,             -- epoch ms
    last_message_at INTEGER NOT NULL            -- epoch ms; updated on each committed message
);

CREATE TABLE messages (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id       INTEGER NOT NULL REFERENCES chats(id) ON DELETE CASCADE,
    role          TEXT    NOT NULL,             -- "user" or "assistant"
    text          TEXT    NOT NULL DEFAULT '',
    thinking_text TEXT,
    image_path    TEXT,                         -- relative path under filesDir
    audio_path    TEXT,                         -- relative path under filesDir
    created_at    INTEGER NOT NULL,
    token_count   INTEGER                       -- nullable; for context-window accounting
);

CREATE INDEX idx_messages_chat_id    ON messages(chat_id);
CREATE INDEX idx_messages_created_at ON messages(created_at);
CREATE INDEX idx_chats_last_msg      ON chats(last_message_at);
```

**Foreign keys:** `messages.chat_id ON DELETE CASCADE` — verified by instrumented test (AC-R6).
**`chats.project_id`:** nullable INTEGER, no FK constraint in v1 — `projects` table added in Phase 4 via `Migration(1,2)`.
**`project_files` table:** NOT created in v1 (AC-R7).
**Schema JSON:** exported to `app/schemas/app.sanctum.machina.data.SanctumDatabase/1.json`, committed to git (AC-R5).

### AppSettings proto additions

```protobuf
message AppSettings {
  map<string, PerModelSettings> per_model_overrides = 1;
  optional string default_model_id    = 2;
  optional string last_used_model_id  = 3;
  optional bool   settings_keys_migrated = 4;  // DataStore migration sentinel (AC-R8)
}
```

### ChatIdentity (sealed class, in `:app`)

```kotlin
sealed class ChatIdentity {
    object Quick : ChatIdentity()                   // route: chat/quick
    object Draft : ChatIdentity()                   // route: chat/draft
    data class Persistent(val id: Long) : ChatIdentity() // route: chat/{chatId}
}
```

Stored in `SavedStateHandle` via nav arg (`chatId: Long?` optional; absence + `kind: String` arg distinguish Quick vs Draft). Route definitions in `SanctumApp.kt`:
- `chat/quick` with optional `?modelId={modelId}` query arg (for Model Manager "Load" flow)
- `chat/draft` no args
- `chat/{chatId}` with `chatId: Long` nav arg

---

## Dependencies

### New packages (to add to `gradle/libs.versions.toml` + `app/build.gradle.kts`)

- `androidx.room:room-runtime:2.7.x` — Room persistence runtime
- `androidx.room:room-ktx:2.7.x` — Kotlin coroutine / Flow extensions
- `androidx.room:room-compiler:2.7.x` — KSP annotation processor (ksp, not kapt)
- `androidx.room:room-testing:2.7.x` — `androidTestImplementation` only

### Using existing

- `app.sanctum.machina.core.registry.ModelRegistry` — engine lifecycle, `activeModelName` (new)
- `app.sanctum.machina.core.settings.AppSettingsRepository` — read/write default & last-used model ids
- `app.sanctum.machina.core.log.ErrorLog` — all failure paths; whitelist extended with 6 components
- `app.sanctum.machina.logexport.LogExportManager` — unchanged; new failure-path logs flow through existing `errors.log` export
- `app.sanctum.machina.crash.CrashHandler` — installed before warmup (ordering preserved, AC-E1)

---

## Testing Strategy

**Feature size:** L

### Unit tests

- `AutoTitleGeneratorTest` — all AC-U2 cases: trim, whitespace collapse, cut at last space ≤20, append "…", fallback "Чат от DD.MM HH:mm", manual-title flag suppresses auto-title
- `SettingsMigrationHelperTest` — happy path (rekey name→id), orphan key dropped + logged, sentinel prevents re-run, single `updateData` call per run
- `ChatRepositoryTest` — save draft→commit happy path, IOException on staging write → rollback + no Room row, zombie sweep logic (0-message chat with missing dir → delete), auto-title triggers on commit
- `WarmupCoordinatorTest` — model resolution order a/b/c (AC-F5), `cancelAndRestart` while warmup in flight, warmup failure → `ErrorLog.e("engine-warmup", ...)`, `last_used_model_id` updated on success
- `ModelRegistryTest` (extended) — `activeModelName` reflects `ModelInitStatus.Ready`; null when Idle/Initializing/Failed; concurrent reads during initialize
- `ErrorLogTest` (extended) — 6 new components pass validation; existing components unchanged
- `ChatViewModelTest` — Draft mode sends commit chain, Quick mode never touches Room, `onCleared` does not call `registry.cleanup`, engine-state transitions drive TopAppBar state

### Integration tests (instrumented, `connectedAndroidTest`, Honor 200)

- `ChatDaoTest` — CRUD: insert chat, query by id, update title, delete by id; `observeByChat` Flow emits on insert
- `MessageDaoTest` — CRUD; CASCADE: delete chat row → message rows gone; `observeByChat` sorted by `created_at`
- `SanctumDatabaseTest` — fresh DB opens without error; schema JSON matches `@Entity` definitions (Room compiler snapshot comparison); `messages.chat_id` FK with `ON DELETE CASCADE` fires correctly

### E2E tests

None — Compose UI tests are explicitly out of scope per `patterns.md`. End-to-end inference verified manually on Honor 200 (AC-G4 benchmark, AC-P1 kill-and-reopen).

---

## Agent Verification Plan

**Source:** user-spec "Агент проверяет" section.

### Verification approach

All agent verification is via `./gradlew` commands. No deployment needed; no MCP tools needed. Per-task smoke checks are in the Verify-smoke fields of Implementation Tasks. The QA task executes the full checklist below.

### Verification steps

| Step | Command | Expected |
|------|---------|----------|
| 1. Full build | `./gradlew build` | `BUILD SUCCESSFUL` |
| 2. App unit tests | `./gradlew :app:testDebugUnitTest` | Green; includes Phase-3 units |
| 3. core-runtime tests | `./gradlew :core-runtime:testDebugUnitTest` | Green; includes ErrorLog new-component tests |
| 4. core-settings tests | `./gradlew :core-settings:testDebugUnitTest` | Green; includes migration sentinel tests |
| 5. DAO instrumented (device) | `./gradlew :app:connectedAndroidTest` | All DAO tests green; cascade test passes |
| 6. Lint | `./gradlew lintDebug` | No critical errors |
| 7. Schema JSON | `ls app/schemas/app.sanctum.machina.data.SanctumDatabase/1.json` | File present |
| 8. ErrorLog whitelist | grep for all 6 new components + `"settings-io"` in `ErrorLog.kt` | 7 components found |
| 9. Proto fields | grep `default_model_id\|last_used_model_id` in `app_settings.proto` | Both `optional` fields present |
| 10. Debug APK | `./gradlew :app:assembleDebug` | `app-debug.apk` produced |

### Tools required

`bash` (gradlew commands). No Playwright, Telegram MCP, or curl needed.

---

## Risks

| Risk | Mitigation |
|------|-----------|
| `ChatViewModel.onCleared()` tears down engine on every Back-nav | Delete cleanup call entirely (Decision 5, AC-E6) |
| `lifecycleMutex` serialises warmup + cross-model → 50–60 s wait | `WarmupCoordinator.cancelAndRestart()` cancels the in-flight warmup Job before cross-switch (Decision 3) |
| Room Flow re-emits full list at 50 Hz during streaming | In-memory `_streamingMessage`; Room write only on `done=true`; combine pattern (Decision 4, AC-R2) |
| `Model.name` / `Model.modelId` drift causes silent PerModelSettings miss | One-shot atomic DataStore migration with sentinel (Decision 8, AC-R8) |
| Attachment Bitmap.compress(PNG) on Main → frame drops | All file I/O on `Dispatchers.IO` (AC-A1) |
| DB corruption on startup → app unlaunchable | Rename + fresh DB + in-memory banner (Decision 9, AC-D5) |
| Multi-attachment partial write → orphaned files | Staging dir + atomic rename; startup orphan cleanup (Decision 6, AC-A6) |
| Delete open chat while stream in progress → callback writes to deleted row | `registry.stopResponse()` before CASCADE DELETE; VM checks stale `chatId` in callbacks (US-7, J4 in code-research) |
| Warmup failure → silent black screen (realme RMX3085 case) | `try/catch` around `registry.initialize`, `ErrorLog.e("engine-warmup", ...)`, "Загрузить" recovery button (AC-U6, US-9, AC-D2) |
| Double-bubble flicker: persisted + streaming at `done=true` | Atomic handover: `_streamingMessage` cleared inside same `mapLatest` block on first persisted emission containing the new ASSISTANT row (user-spec Risk end-of-section) |
| `AndroidDeviceInfoProvider` secondary constructor in `:crash` process breaks if ModelRegistry injected | Secondary constructor retains null stubs — crash process has no running engine (Decision 10, AC-D1) |

---

## User-Spec Deviations

None — tech-spec follows user-spec faithfully on all requirements. The `architecture.md` document (which pre-dates the phased plan finalization) describes `chats.project_id NOT NULL` and a `project_files` table in v1; user-spec AC-R4 and AC-R7 explicitly supersede this. Task 12 (Docs update) aligns `architecture.md` with reality.

---

## Acceptance Criteria

Technical criteria (complement user-spec acceptance criteria):

- [ ] `./gradlew build` produces `BUILD SUCCESSFUL` with zero compile errors
- [ ] All unit tests pass: `./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest :core-settings:testDebugUnitTest`
- [ ] All instrumented DAO tests pass on Honor 200: `./gradlew :app:connectedAndroidTest`
- [ ] `app/schemas/app.sanctum.machina.data.SanctumDatabase/1.json` committed to git and matches entity definitions
- [ ] `lintDebug` reports no critical errors
- [ ] `ErrorLog.ALLOWED_COMPONENTS` contains exactly: `"download"`, `"inference-init"`, `"inference"`, `"inference-cleanup"`, `"settings-io"`, `"camera"`, `"audio"`, `"attachment-decode"`, `"model"`, `"engine-warmup"`, `"history-read"`, `"history-write"`, `"attachment-save"`, `"attachment-read"` (14 total)
- [ ] `app_settings.proto` contains `optional string default_model_id = 2`, `optional string last_used_model_id = 3`, `optional bool settings_keys_migrated = 4`
- [ ] `ModelRegistry` interface exposes `val activeModelName: StateFlow<String?>`
- [ ] `Model.kt` has `val modelId: String` field
- [ ] `ChatViewModel.onCleared()` contains no `registry.cleanup()` call
- [ ] `filesDir/attachments/.staging-*` directories are cleaned up by `SanctumApplication.onCreate`
- [ ] No regression in existing unit tests from Phase 2.5

---

## Implementation Tasks

### Wave 1 (независимые)

#### Task 1: Room foundation
- **Description:** Add Room 2.7.x dependencies to `libs.versions.toml` and `app/build.gradle.kts` (runtime, ktx, compiler via KSP, testing via androidTestImplementation). Create `ChatEntity`, `MessageEntity` (v1 schema), `ChatDao` (suspend + Flow methods), `MessageDao` (suspend + Flow, including `observeByChat`), and `SanctumDatabase` with `exportSchema = true` pointing to `app/schemas/`. Create `app/src/androidTest/` directory and write `ChatDaoTest` + `MessageDaoTest` covering CRUD + ON DELETE CASCADE. This is the data-layer foundation everything else builds on.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :app:kspDebugKotlin` → no errors; `ls app/schemas/app.sanctum.machina.data.SanctumDatabase/1.json` → file exists
- **Files to modify:** `gradle/libs.versions.toml`, `app/build.gradle.kts`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/data/model/ChatEntity.kt`, `app/src/main/kotlin/app/sanctum/machina/data/model/MessageEntity.kt`, `app/src/main/kotlin/app/sanctum/machina/data/dao/ChatDao.kt`, `app/src/main/kotlin/app/sanctum/machina/data/dao/MessageDao.kt`, `app/src/main/kotlin/app/sanctum/machina/data/SanctumDatabase.kt`, `app/src/androidTest/kotlin/app/sanctum/machina/data/dao/ChatDaoTest.kt`, `app/src/androidTest/kotlin/app/sanctum/machina/data/dao/MessageDaoTest.kt`, `app/src/androidTest/kotlin/app/sanctum/machina/data/SanctumDatabaseTest.kt`
- **Files to read:** `gradle/libs.versions.toml`, `app/build.gradle.kts`, `work/phase-3-history/tech-spec.md` (Data Models section)

#### Task 2: Settings + core fixes
- **Description:** Extend `app_settings.proto` with three new optional fields (`default_model_id`, `last_used_model_id`, `settings_keys_migrated`); add corresponding methods to `AppSettingsRepository` / `DefaultAppSettingsRepository`. Expose `modelId: String` on `Model` by plumbing it through `AllowedModel.toModel()` (one-line change per code-research K2). Implement `SettingsMigrationHelper` (atomic DataStore rekey + sentinel, orphan-key logging). Expand `ErrorLog.ALLOWED_COMPONENTS` with 6 Phase-3 components. Fix `AndroidDeviceInfoProvider` stubs to read from `ModelRegistry` (primary Hilt constructor only; secondary crash-process constructor keeps null stubs per Decision 10). This task fixes two Phase-2.5 bugs (DeviceInfoCollector stubs AC-D1) and prepares the key-stability foundation for Room (AC-R8).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :core-settings:testDebugUnitTest :core-runtime:testDebugUnitTest` → green; `grep -c "engine-warmup\|history-read\|history-write\|attachment-save\|attachment-read\|\"model\"" core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt` → 6
- **Files to modify:** `core-settings/src/main/proto/app_settings.proto`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/AppSettingsRepository.kt`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/DefaultAppSettingsRepository.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Model.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/ModelAllowlist.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`, `app/src/main/kotlin/app/sanctum/machina/logexport/DeviceInfoCollector.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/log/ErrorLogTest.kt`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/data/SettingsMigrationHelper.kt`, `app/src/test/kotlin/app/sanctum/machina/data/SettingsMigrationHelperTest.kt`
- **Files to read:** `core-settings/src/main/proto/app_settings.proto`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Model.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/ModelAllowlist.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`, `app/src/main/kotlin/app/sanctum/machina/logexport/DeviceInfoCollector.kt`, `app/src/main/kotlin/app/sanctum/machina/logexport/LogExportModule.kt`, `work/phase-3-history/code-research.md` (sections F, I, K2, K5)

#### Task 3: ModelRegistry — `activeModelName` StateFlow
- **Description:** Add `val activeModelName: StateFlow<String?>` to the `ModelRegistry` interface and implement it in `DefaultModelRegistry` as a derived StateFlow from `models`: emits the `modelName` of the first entry with `ModelInitStatus.Ready`, or `null` if none. Write a unit test verifying that concurrent `initialize` + reads produce consistent values (addresses J5 hazard in code-research). This is the single source of truth enabling ChatViewModel and DrawerViewModel to detect which model is currently warm without repeated `.find {}` scans.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ModelRegistry.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`
- **Files to create:** `core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/ModelRegistryActiveModelTest.kt`
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ModelRegistry.kt`, `work/phase-3-history/code-research.md` (sections A1–A3, K1)

### Wave 2 (зависит от Wave 1)

#### Task 4: ChatRepository
- **Description:** Create `ChatRepository` interface and `DefaultChatRepository` implementing: `commitDraftChat()` (atomic: staging write → `@Transaction` INSERT chats + messages → rename staging dir), `savePersistentMessage()`, `updateChatLastMessage()`, `updateChatTitle()`, `deleteChat()` (CASCADE + `deleteRecursively(filesDir/attachments/{chatId}/)`), `observeChats()` / `observeMessages()`, `sweepZombieChats()` (delete 0-message chats with missing attachment dir). Create `AutoTitleGenerator` as a pure function. All file I/O runs on `Dispatchers.IO`. Unit-test with faked DAOs and mock `File`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/data/ChatRepository.kt`, `app/src/main/kotlin/app/sanctum/machina/data/DefaultChatRepository.kt`, `app/src/main/kotlin/app/sanctum/machina/data/AutoTitleGenerator.kt`, `app/src/test/kotlin/app/sanctum/machina/data/AutoTitleGeneratorTest.kt`, `app/src/test/kotlin/app/sanctum/machina/data/ChatRepositoryTest.kt`
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/data/dao/ChatDao.kt`, `app/src/main/kotlin/app/sanctum/machina/data/dao/MessageDao.kt`, `app/src/main/kotlin/app/sanctum/machina/data/model/ChatEntity.kt`, `app/src/main/kotlin/app/sanctum/machina/data/model/MessageEntity.kt`, `work/phase-3-history/tech-spec.md` (Architecture → How it works, AC-A1–A6, AC-U2, AC-P7, AC-P8)

#### Task 5: WarmupCoordinator + AppModule
- **Description:** Create `WarmupCoordinator`: reads `AppSettings.default_model_id` (fallback `last_used_model_id`), calls `registry.initialize(modelId)` in a cancellable coroutine, updates `last_used_model_id` on success, calls `ErrorLog.e("engine-warmup", ...)` on failure; exposes `warmupDefault()` and `cancelAndRestart(modelId: String)`. Create `AppModule` (Hilt `@InstallIn(SingletonComponent::class)`) providing `SanctumDatabase` via `Room.databaseBuilder`, `ChatDao`, `MessageDao`, `ChatRepository`, and `WarmupCoordinator`. Unit-test model resolution order (a/b/c per AC-F5) and `cancelAndRestart` racing with in-flight warmup.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/engine/WarmupCoordinator.kt`, `app/src/main/kotlin/app/sanctum/machina/di/AppModule.kt`, `app/src/test/kotlin/app/sanctum/machina/engine/WarmupCoordinatorTest.kt`
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ModelRegistry.kt`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/AppSettingsRepository.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`, `app/src/main/kotlin/app/sanctum/machina/data/SanctumDatabase.kt`, `work/phase-3-history/code-research.md` (sections A, E, J7), `work/phase-3-history/tech-spec.md` (Decision 3, AC-F5, AC-F6)

### Wave 3 (зависит от Wave 2)

#### Task 6: SanctumApplication rework
- **Description:** Extend `SanctumApplication.onCreate` (behind `getProcessName() == packageName` guard, after `installCrashHandler()`): inject `WarmupCoordinator` + `ChatRepository` via `@Inject`; call `warmupCoordinator.warmupDefault()`; run housekeeping coroutines (`filesDir/quick/` purge, orphan `.staging-*` cleanup, `chatRepository.sweepZombieChats()`). Wrap `Room.databaseBuilder.build()` (in AppModule) with corruption handler: on exception, rename `sanctum.db` to `sanctum.db.corrupt_{ts}`, create fresh DB, expose `corruptionOccurred` via `AppCorruptionState` singleton, log `ErrorLog.e("history-read", ...)`. Trigger `SettingsMigrationHelper.migrateIfNeeded()` once. This task wires all Phase-3 application-startup orchestration.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt`, `app/src/main/kotlin/app/sanctum/machina/di/AppModule.kt`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/engine/AppCorruptionState.kt`
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt`, `app/src/main/kotlin/app/sanctum/machina/crash/CrashHandler.kt`, `app/src/main/kotlin/app/sanctum/machina/engine/WarmupCoordinator.kt`, `app/src/main/kotlin/app/sanctum/machina/data/ChatRepository.kt`, `app/src/main/kotlin/app/sanctum/machina/data/SettingsMigrationHelper.kt`, `work/phase-3-history/code-research.md` (section A5), `work/phase-3-history/tech-spec.md` (Architecture → Cold start sequence, Decision 9, AC-E1, AC-A3)

### Wave 4 (зависит от Wave 3)

#### Task 7: Navigation + HomeScreen
- **Description:** Replace `model_manager` start destination with `home` in `SanctumApp.kt`. Wrap `NavHost` with `ModalNavigationDrawer` (scaffold pattern: `DrawerState` + `rememberCoroutineScope` + hamburger tap handler passed down). Add new routes: `chat/quick` (optional `?modelId={String}` query param), `chat/draft`, `chat/{chatId}` (Long nav arg); retire `chat/{modelName}`. Create `HomeScreen` composable: centered "Новый быстрый чат" `FilledTonalButton`, "Открыть Model Manager" `TextButton`, and a no-models placeholder shown when `ModelRegistry.models` has no downloaded entry. Create `HomeViewModel` providing the downloaded-models flag. `ModelManagerScreen` entry moved to drawer nav (also accessible from HomeScreen placeholder).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** Install APK → app opens on HomeScreen (not Model Manager); hamburger opens drawer; "Новый быстрый чат" button navigates to a chat screen
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/home/HomeScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/home/HomeViewModel.kt`
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ModelRegistry.kt`, `work/phase-3-history/tech-spec.md` (Data Models → ChatIdentity, AC-P3, AC-P5, US-8)

#### Task 8: ChatViewModel rework
- **Description:** Redesign `ChatViewModel` around `ChatIdentity` (read from `SavedStateHandle`). Replace the imperative `init {} registry.initialize()` call with a reactive `registry.models.collect {}` observer that derives engine-ready state (no automatic initialize — warmup is WarmupCoordinator's job). Add Room-backed message flow (`messageDao.observeByChat(chatId)`) combined with `_streamingMessage: StateFlow<Message?>` via `combine` for display (Quick and Draft modes use in-memory list only). Implement draft→committed atomic transition (`chatRepository.commitDraftChat()` + `popUpTo("chat/draft"){inclusive=true}`). Delete `onCleared()` cleanup call entirely (AC-E6). Write `ChatViewModelTest` covering Quick lifecycle, Draft→Persistent promotion, and engine state transitions.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`
- **Files to create:** `app/src/test/kotlin/app/sanctum/machina/ui/chat/ChatViewModelTest.kt`
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/kotlin/app/sanctum/machina/data/ChatRepository.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`, `app/src/main/kotlin/app/sanctum/machina/engine/WarmupCoordinator.kt`, `work/phase-3-history/code-research.md` (sections B, J), `work/phase-3-history/tech-spec.md` (Architecture → Optimistic-UI message display, Decision 2, Decision 4, Decision 5, AC-E4, AC-E6, AC-R1–R3, AC-P7)

### Wave 5 (зависит от Wave 4)

#### Task 9: Drawer UI
- **Description:** Implement `DrawerContent` composable + `DrawerViewModel`: chat list from `chatRepository.observeChats()` sorted by `last_message_at DESC`, each row showing title + model name + relative date. Swipe-left on row → red "Удалить" reveal → confirmation `AlertDialog` (with chat title + message count); on confirm calls `chatRepository.deleteChat()` and pops back if the deleted chat is currently open. Long-press on row → "Переименовать" `AlertDialog` with pre-filled `TextField` (60-char limit; empty input resets to auto-title by clearing `is_manually_titled`). Empty state: "Нет сохранённых чатов." + "Новый чат" button. Pre-navigation check: if tap target chat's `model_id` has no downloaded file → show "Модель недоступна" `AlertDialog` with "Отмена" / "Скачать" (navigates to `model_manager`).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** Open drawer → chat list shows; swipe-delete shows confirmation; long-press shows rename; tapping chat with missing model shows dialog
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/drawer/DrawerContent.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/drawer/DrawerViewModel.kt`
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`, `app/src/main/kotlin/app/sanctum/machina/data/ChatRepository.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ModelRegistry.kt`, `work/phase-3-history/tech-spec.md` (US-6, US-7, US-13, AC-P4, AC-U1, AC-U3, AC-M1)

#### Task 10: ChatScreen + HomeScreen updates
- **Description:** Implement the TopAppBar state machine in `ChatScreen`: (a) Draft mode → model-picker `DropdownMenu` listing downloaded models, tap non-active model → cross-model `AlertDialog` → on confirm → `warmupCoordinator.cancelAndRestart(modelId)`; (b) `ModelInitStatus.Failed` or `Idle` (non-draft) → "Загрузить" `Button` → `warmupCoordinator.cancelAndRestart(chat.modelId)`; (c) reinit in flight → spinner + "Модель перезагружается…" label, Send disabled; (d) `ModelInitStatus.Ready` (non-draft) → read-only model name label. Add incognito indicator for Quick mode (perchёrknuty-glaz icon + "Быстрый чат" subtitle). Add `Modifier.imePadding()` to `MultimodalInputBar` container (AC-U4). Display one-time corruption banner on `HomeScreen` when `AppCorruptionState.corruptionOccurred == true` (with "Сохранить лог" calling `logExportManager` + "Закрыть"). This task completes all ChatScreen UX affordances from the user-spec.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** Tap input field → Send button stays visible above keyboard; open draft → model dropdown works; warmup failure shows "Загрузить"; quick chat shows incognito indicator
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/home/HomeScreen.kt`
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/kotlin/app/sanctum/machina/engine/AppCorruptionState.kt`, `app/src/main/kotlin/app/sanctum/machina/logexport/LogExportManager.kt`, `work/phase-3-history/tech-spec.md` (AC-E3, AC-E3b, AC-U4–U7, US-2-bis, US-4, US-9, US-14)

#### Task 11: Model Manager updates
- **Description:** Add ⭐ star indicator (leading icon) on the row of the current default model in `ModelManagerScreen` (derived from `AppSettings.default_model_id`). Add "Сделать по умолчанию" to the overflow menu of each downloaded model row; tap calls `modelManagerViewModel.setDefaultModel(modelId)` → `appSettings.setDefaultModelId(modelId)` → Snackbar "Модель по умолчанию: {name}" (AC-F7). Change `onLoad` routing: instead of `NavEvent.OpenChat(modelName)`, emit `NavEvent.OpenQuickChat(modelId)` → navigate to `chat/quick?modelId={modelId}` (AC-F4). Inject `AppSettingsRepository` into `ModelManagerViewModel`. This closes the default-model selection UX and the "try model" quick-chat flow.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** Model Manager → overflow on non-default model → "Сделать по умолчанию" → Snackbar + ⭐ moves; "Загрузить" on a model → opens quick chat
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerViewModel.kt`
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerViewModel.kt`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/AppSettingsRepository.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`, `work/phase-3-history/code-research.md` (section K3), `work/phase-3-history/tech-spec.md` (AC-F4, AC-F7, US-8, US-12)

### Wave 6 (зависит от Wave 5)

#### Task 12: Project knowledge docs update
- **Description:** Update `architecture.md` and `patterns.md` to reflect Phase 3 reality. In `architecture.md`: change `chats.project_id` from `NOT NULL` to `nullable (no FK in v1 — projects table via Migration(1,2) in Phase 4)`, remove `project_files` from v1 schema description, update nav routes (`chat/{modelName}` → typed routes), update `SanctumApplication` description with warmup + housekeeping. In `patterns.md`: extend `ErrorLog component strings` whitelist entry with all 6 Phase-3 components + `settings-io`; update `Model lifecycle` entry (cleanup only on cross-model confirm or process death; `onCleared` does not cleanup); add `model-switch in draft` business rule note. This ensures new agents working from project-knowledge get accurate context.
- **Skill:** documentation-writing
- **Reviewers:** code-reviewer
- **Files to modify:** `.claude/skills/project-knowledge/references/architecture.md`, `.claude/skills/project-knowledge/references/patterns.md`
- **Files to read:** `.claude/skills/project-knowledge/references/architecture.md`, `.claude/skills/project-knowledge/references/patterns.md`, `work/phase-3-history/tech-spec.md` (all decisions + data models)

### Audit Wave

#### Task 13: Code Audit
- **Description:** Full Phase-3 code quality audit. Read all source files created/modified in this feature (from `work/phase-3-history/decisions.md` + this tech-spec "Files to modify/create" lists). Review holistically: single-engine invariant compliance, `ChatIdentity` branch coverage, staging-dir cleanup completeness, no blocking calls on Main thread, Hilt scope correctness, no `Model.name` vs `Model.modelId` mix after migration. Write audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 14: Security Audit
- **Description:** Full Phase-3 security audit. Read all source files created/modified in this feature. Analyze: file-path injection risks in `filesDir/attachments/{chatId}/` path construction, SQL injection surface in DAOs (parameterized queries only), privacy guarantees (quick-chat files actually purged, no PII in logs), attachment decode security, cross-process crash state file security. Write audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 15: Test Audit
- **Description:** Full Phase-3 test quality audit. Read all test files created in this feature (`AutoTitleGeneratorTest`, `SettingsMigrationHelperTest`, `ChatRepositoryTest`, `WarmupCoordinatorTest`, `ModelRegistryActiveModelTest`, `ChatViewModelTest`, `ChatDaoTest`, `MessageDaoTest`, `SanctumDatabaseTest`). Verify: atomic transition coverage, concurrency scenarios, error paths, test pyramid balance (unit vs integration split), meaningful assertions vs trivial checks. Write audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 16: Pre-deploy QA
- **Description:** Acceptance testing for Phase 3. Run complete test suite: `./gradlew build :app:testDebugUnitTest :core-runtime:testDebugUnitTest :core-settings:testDebugUnitTest lintDebug :app:assembleDebug`. Verify all agent-verifiable acceptance criteria from user-spec "Агент проверяет" table and tech-spec "Acceptance Criteria" section. Confirm schema JSON exists and is committed. Check ErrorLog whitelist and proto fields. Report any remaining failures.
- **Skill:** pre-deploy-qa
- **Reviewers:** none
