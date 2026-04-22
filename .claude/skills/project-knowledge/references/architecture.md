# Architecture

## Purpose
Technical architecture overview for AI agents. Helps agents understand HOW the system is built.

---

## Tech Stack

**Language:** Kotlin 2.2.x
- **Why:** Matches Gallery (fork basis), first-class on Android, coroutines + Flow fit the streaming LLM model, prepared for KMP expansion later.

**UI:** Jetpack Compose + Material 3, with Jetpack Compose BOM `2026.03.00`
- **Why:** Declarative UI is a significantly better fit for streaming message lists and state-driven chat than legacy XML views. Material 3 gives a solid default that we shape towards the Pocket LLM / Claude visual direction (see `ux-guidelines.md`).
- **Compose Compiler:** bundled with Kotlin 2.x via the `org.jetbrains.kotlin.plugin.compose` Gradle plugin — no separate Compose Compiler version needed.

**Navigation:** Navigation Compose
- **Why:** Matches Gallery idiomatically. Handles the projects → chats → messages hierarchy naturally.

**Dependency Injection:** Hilt 2.57.1 via KSP
- **Why:** Standard on modern Android, used by Gallery (easier to port extracted code), KSP is ~2× faster than KAPT at build time.

**Inference Engine:** `com.google.ai.edge.litertlm:litertlm-android:0.10.0` (pinned, not `latest.release`)
- **Why:** This is the core value extracted from Gallery. Native CPU/GPU/NPU backend selection, streaming, multimodal (text + image + audio), thinking-channel, Apache 2.0 licensed. Pinned because the API is marked `@OptIn(ExperimentalApi::class)` throughout and minor versions may break.
- **Model family:** Gemma 4 (E2B / E4B edge variants), released 2026-04-02 under Apache 2.0 by Google DeepMind. The naming `gemma-4-*-it-litert-lm` is the `litert-community/*` HuggingFace repo convention; models are already quantized and packaged as `.litertlm` files.

**Persistence (relational):** Room 2.7.x (stable, KMP-ready)
- **Why:** Chat/message/project schema is relational with queries ("all messages in chat", "all chats in project"). Room 3.0 (released as alpha March 2026) is the KMP-first future, but alpha status means we stay on 2.7.x until 3.0 stabilizes. Migrating 2.7 → 3.0 later is planned but not urgent.

**Persistence (key-value):** androidx.datastore (Proto DataStore)
- **Why:** Matches Gallery, handles settings / secrets / model allowlist cache. Good for small structured state that doesn't need SQL.

**HuggingFace OAuth:** net.openid:appauth 0.11.1
- **Why:** Matches Gallery. Some models on HuggingFace require login to download (gated models).

**Background work:** androidx.work 2.10.0 (WorkManager)
- **Why:** Model downloads are multi-GB — need foreground-service progress notification, resumable state, survives process kill. Matches Gallery's approach.

**Camera / photos:** androidx.camera 1.4.2 (CameraX)
- **Why:** Multimodal input requires camera access. CameraX is the standard, matches Gallery.

**Audio recording:** `AudioRecord` (platform API, no extra dep)
- **Why:** litertlm consumes raw PCM 16 kHz mono directly; `AudioRecord` delivers it without the AAC→PCM round-trip that `MediaRecorder` would force. A RIFF/WAVE header is added at the litertlm boundary in `ChatViewModel.send` via `pcmToWav` (ported from Gallery) — headerless PCM triggers an `onError` on-device. `Attachment.Audio.pcm` still stores raw PCM for Phase-3 Room compactness.

**Markdown rendering in chat:** `com.halilibo.compose-richtext:richtext-commonmark` + `richtext-ui-material3` (both `1.0.0-alpha02`, group `com.halilibo.compose-richtext` — NOT `com.halilibo.richtext`)
- **Why:** Matches Gallery. Wrapped by project-specific `SafeMarkdown` composable that installs a scheme-whitelisted `LocalUriHandler` (http/https only — blocks `intent://`, `sms:`, `tel:`, `javascript:`, `file:`, `content:`, `data:`, `market:` from LLM-rendered markdown links).

**Settings persistence (per-model overrides):** `androidx.datastore:datastore` 1.1.7 + `com.google.protobuf:protobuf-javalite` 4.28.3 + protobuf gradle plugin 0.9.5, isolated in the `:core-settings` module
- **Why:** `:core-runtime` must stay UI-free; settings must be injectable via Hilt into both `:app` and `:core-runtime`. A separate module achieves build isolation and keeps a KMP path open. Schema lives in `core-settings/src/main/proto/app_settings.proto`; key for per-model overrides is `Model.modelId` (stable across renames).

**Testing:** JUnit 4.13.2 + Robolectric 4.12 (for tests that need `Bitmap` / Compose resources in JVM). MockK is available but most tests use hand-rolled fakes or `FakeDataStore` + `TemporaryFolder`.
- **Why:** JUnit 4 kept from Phase 1 (D8) — JUnit 5 migration deferred. Robolectric added in Phase 2 (D20) because `Bitmap` cannot be instantiated in pure JVM; pure-JVM helpers (`calculateInSampleSize`, `calculatePeakAmplitude`, `formatTimer`, `EffectiveConfig.merge`) are tested without Robolectric for speed. Compose UI tests explicitly not in scope.

**Build tooling:** Gradle 8.x, Android Gradle Plugin 8.8.2, single `libs.versions.toml` catalog.
- **Why:** Standard for this Android version combo; single catalog ensures pinned, reproducible versions across both modules.

---

## Project Structure

**Namespace / package root:** `app.sanctum.machina.*` for both modules. The `:app` module uses `app.sanctum.machina.*`, and `:core-runtime` uses `app.sanctum.machina.core.*`. Android `applicationId` and Gradle `namespace` in `:app/build.gradle.kts` are both `app.sanctum.machina`. The `app.` prefix is used instead of `com.` because there is no owned sanctum.com domain and no intention to claim one; `app.` is a valid TLD-first convention for Android apps without a backing domain.

```
PhoneWrap/                                  # Repository root, working dir
├── CLAUDE.md                               # Minimal project preamble + pointer to PK
├── .claude/
│   ├── skills/
│   │   └── project-knowledge/references/   # This documentation set
│   ├── agents/                             # Methodology agents (validators, reviewers)
│   └── commands/                           # Slash commands
│
├── gallery-source/                         # READ-ONLY reference clone of google-ai-edge/gallery.
│                                           # Cloned manually once via
│                                           #   git clone https://github.com/google-ai-edge/gallery gallery-source
│                                           # Listed in .gitignore. Not part of our Gradle build.
│                                           # Used to look up litertlm integration patterns,
│                                           # allowlist structure, and specific file references.
│
├── work/                                   # Spec-driven development artifacts
│   ├── research/                           # gallery-analysis.md, code-research.md
│   ├── {feature}/                          # Per-feature user-spec.md / tech-spec.md / tasks/
│   └── completed/                          # Archived features
│
├── app/                                    # :app gradle module — the Android application
│   ├── build.gradle.kts                    # namespace = "app.sanctum.machina"
│   ├── src/main/AndroidManifest.xml        # permissions (CAMERA, RECORD_AUDIO, INTERNET), SystemForegroundService,
│   │                                       # CrashReportActivity (process=":crash", exported=false, excludeFromRecents=true),
│   │                                       # allowBackup=false + dataExtractionRules (see patterns.md § Privacy hardening)
│   ├── src/main/assets/about.md            # Sanctum Machina manifesto (editable markdown, rendered by AboutScreen)
│   ├── src/main/res/xml/data_extraction_rules.xml  # cloud-backup + device-transfer excluded at root
│   └── src/main/kotlin/app/sanctum/machina/
│       ├── crash/                          # Phase 2.5 — crash capture & recovery (runs in both main + :crash processes)
│       │   ├── CrashHandler.kt             # Thread.UncaughtExceptionHandler: writes crash.log, starts CrashReportActivity, kills main process
│       │   ├── CrashReportActivity.kt      # Plain ComponentActivity (no Hilt) in android:process=":crash"; SAF export + two-button UI
│       │   ├── CrashState.kt              # @Singleton; StateFlow<Boolean> backed by crash.log + crash.log.dismissed filesystem state
│       │   ├── RestartCrashBanner.kt       # Stateless Compose composable; hosted by ModelManagerScreen
│       │   └── Killer.kt                   # Test seam over Process.killProcess
│       ├── logexport/                      # Phase 2.5 — diagnostic log assembly & export
│       │   ├── LogExportManager.kt         # @Singleton; buildExport(ExportSource) + writeTo(uri); secondary non-Hilt ctor for :crash process
│       │   ├── DeviceInfoCollector.kt      # Builds the .txt header; DeviceInfoProvider interface for test stubs
│       │   ├── LogcatReader.kt             # Spawns logcat -d --pid=<own> *:E with 2-s timeout; CommandRunner seam
│       │   ├── TapCounter.kt              # Pure-JVM 7-tap detector (no Android deps); nowNanos seam
│       │   └── LogExportModule.kt          # Hilt @Binds: CommandRunner → DefaultCommandRunner, DeviceInfoProvider → AndroidDeviceInfoProvider
│       ├── ui/
│       │   ├── about/AboutScreen.kt        # SafeMarkdown-wrapped assets/about.md; «Диагностика» section (SAF export); 7-tap dev-gesture on version line
│       │   ├── chat/                       # ChatScreen + ChatViewModel; Attachment sealed class;
│       │   │                               # MultimodalInputBar + ThumbnailStrip; CameraBottomSheet (CameraX);
│       │   │                               # AudioRecorderBottomSheet (AudioRecord); MessageBubble + ThinkingBlock;
│       │   │                               # InferenceSettingsBottomSheet + HeavyChangeDialog + ReinitProgressDialog;
│       │   │                               # EffectiveConfig; SafeMarkdown + SafeUriHandler
│       │   ├── drawer/                     # Phase 3 — DrawerContent + DrawerViewModel; grouped chat list (Today/Yesterday/ThisWeek/Earlier);
│       │   │                                 # long-press rename, swipe-to-confirm-delete, model-unavailable dialog;
│       │   │                                 # footer «Модели» → model_manager + «О приложении» → about (user-spec §37)
│       │   ├── home/                         # Phase 3 — HomeScreen + HomeViewModel; hasDownloadedModels gating,
│       │   │                                 # clickable default-model label, corruption banner (AC-D5)
│       │   ├── modelmanager/               # ModelManagerScreen: RestartCrashBanner above model list (Phase 2.5); SnackbarHost;
│       │   │                                 # Phase 3 — default-model star badge + overflow «Сделать по умолчанию»
│       │   └── theme/, SanctumApp.kt       # NavHost: home / chat/quick?modelId={id} / chat/draft / chat/{chatId} (Long) / model_manager / about
│       ├── MainActivity.kt
│       └── SanctumApplication.kt           # @HiltAndroidApp; process-guard (getProcessName() == packageName) separates main
│                                           # from :crash process. Main-process work: installs CrashHandler, assigns
│                                           # DefaultDownloadRepository.mainActivityFqn, triggers SettingsMigrationHelper.migrateIfNeeded()
│                                           # (Phase 2.5 → 3 per-model settings migration), kicks off
│                                           # WarmupCoordinator.warmupDefault() on background scope (Phase 3 cold-start engine warm);
│                                           # StartupHousekeeper coroutines: filesDir/quick/ purge, orphan .staging-* cleanup,
│                                           # chatRepository.sweepZombieChats() — each wrapped in try/catch + ErrorLog.e;
│                                           # installs Room corruption handler that flips AppCorruptionState.corruptionOccurred
│                                           # and renames the db to sanctum.db.corrupt_{ts} so HomeScreen can surface AC-D5 banner.
│
├── core-runtime/                           # :core-runtime gradle module — extracted Gallery core
│   ├── build.gradle.kts                    # namespace = "app.sanctum.machina.core"
│   ├── src/main/AndroidManifest.xml        # library-scope hygiene: uses-permission + service merge-override (see patterns.md)
│   └── src/main/kotlin/app/sanctum/machina/core/
│       ├── common/                         # enums, helpers, MediaUtils (decodeSampledBitmapFromUri / rotateBitmap /
│       │                                   # calculatePeakAmplitude / pcmToWav), AudioClip, MultimodalContentsBuilder
│       ├── data/                           # Model / ModelAllowlist / Config / DownloadRepository
│       ├── di/                             # Hilt modules (CoreRuntimeModule)
│       ├── inference/                      # LlmChatModelHelper (litertlm wrapper)
│       ├── log/                            # ErrorLog (on-device error writer; whitelist + bounding — see patterns.md)
│       ├── registry/                       # ModelRegistry + AllowlistLoader
│       ├── runtime/                        # runtime helpers
│       └── worker/                         # DownloadWorker (WorkManager foreground service)
│                                           # Phase 3+: auth/ (HF OAuth), Phase 4: embed/ (Embedder interface)
│
├── core-settings/                          # :core-settings gradle module — Proto DataStore for per-model overrides
│   ├── build.gradle.kts                    # namespace = "app.sanctum.machina.core.settings"; protobuf-javalite 4.28.3
│   ├── src/main/AndroidManifest.xml        # library hygiene: self-closing <application> (no overrides of :app flags)
│   ├── src/main/proto/app_settings.proto   # AppSettings { map<string, PerModelSettings> per_model_overrides }
│   │                                       # PerModelSettings: all seven fields `optional` (proto3 explicit-optional)
│   └── src/main/kotlin/app/sanctum/machina/core/settings/
│       ├── AppSettingsSerializer
│       ├── AppSettingsRepository + DefaultAppSettingsRepository  # wraps IOException / CorruptionException
│       └── di/CoreSettingsModule.kt        # Hilt @Singleton DataStore<AppSettings> at filesDir/datastore/app_settings.pb
│
├── build.gradle.kts                        # Root build config
├── settings.gradle.kts                     # Module declarations (:app, :core-runtime)
├── gradle/
│   └── libs.versions.toml                  # Pinned version catalog
└── .gitignore
```

**Module graph:** `:app` → `:core-runtime`, `:core-settings`. `:core-settings` → `:core-runtime` (for `ErrorLog`). `:core-runtime` has no internal dependencies.

**Module boundary rule:** both `:core-runtime` AND `:core-settings` must remain UI-free — no Compose, no Activity, no ViewModel imports. Enforced by grep at audit time (TAC-7, TAC-8 in Phase 2 tech-spec). The core modules stay extractable for future reuse (KMP path).

---

## Key Dependencies

**Critical packages:**
- `com.google.ai.edge.litertlm:litertlm-android` (0.10.0) — **the** inference engine. CPU/GPU/NPU backends, streaming token generation, multimodal `Contents` API, system instruction support. Pinned version; upgrades planned carefully.
- `androidx.hilt:hilt-android` + `dagger-hilt-android-compiler` (2.57.1, KSP) — dependency injection across both modules.
- `androidx.room:room-runtime` / `room-ktx` / `room-compiler` (2.7.x) — persistent store for projects, chats, messages. KSP code generation.
- `androidx.work:work-runtime-ktx` (2.10.0) — `ModelDownloadWorker` runs as foreground service with notification, survives app kill.
- `net.openid:appauth` (0.11.1) — OAuth for HuggingFace when downloading gated models.
- `androidx.camera:camera-camera2` / `camera-lifecycle` / `camera-view` (1.4.2) — photo capture for multimodal input.
- `com.halilibo.compose-richtext:richtext-commonmark` + `richtext-ui-material3` (1.0.0-alpha02) — markdown rendering in chat messages; consumed through project-local `SafeMarkdown` wrapper (not directly).
- `com.google.protobuf:protobuf-javalite` (4.28.3) + protobuf gradle plugin (0.9.5) — proto3 runtime for `:core-settings` DataStore schema.
- `androidx.datastore:datastore` (1.1.7) — key-value store used in `:core-settings` for per-model inference overrides.
- `androidx.exifinterface` (1.4.1) — EXIF orientation handling in `MediaUtils.rotateBitmap` (ported from Gallery).
- `org.robolectric:robolectric` (4.12) — test-only, enables `Bitmap` / Compose-resources in JVM unit tests.

**Explicitly NOT included** (to preserve the "open-source only" manifesto and remove inherited bloat):
- No `google-services` plugin, no `firebase-*`, no `mlkit-genai-prompt` (AICore), no `play-services-oss-licenses`.
- No analytics, no crash reporting, no FCM.

---

## External Integrations

**HuggingFace (huggingface.co)**
- **Purpose:** Downloading model weights. The allowlist (forked from Gallery `model_allowlists/1_0_11.json`) contains `modelId` values like `litert-community/gemma-4-E2B-it-litert-lm` pointing to HF repos.
- **Auth method:** OAuth via AppAuth for gated models (e.g., official `google/*` repos). Token stored in encrypted DataStore on-device, never in git.
- **Fallback:** Models without authentication gate (community repos) download without any credentials.

No other external services. No telemetry endpoints. No update-check endpoints. No remote allowlist refresh in MVP (allowlist bundled as asset; may add opt-in refresh later).

---

## Data Flow

**Main inference flow (a user sends a message in a chat):**

1. User types / records / snaps input in `ChatScreen` Compose.
2. `ChatViewModel` builds a `ChatMessage` entity, inserts into Room (via `MessageDao`).
3. `ChatViewModel` calls `:core-runtime`'s `LlmModelHelper.runInference(contents, resultListener)` with the current model's `ModelRuntimeHandle` + current chat history (trimmed to model context window) + project system prompt.
4. LiteRT-LM streams tokens through `resultListener` — partial text + optional thinking-channel text.
5. `ChatViewModel` appends streamed text to the in-memory message state; on `done = true` it writes the final message to Room.
6. UI re-renders from the `Flow<List<ChatMessage>>` that `ChatScreen` collects from Room via `MessageDao.observeByChat(chatId)`.

**Model lifecycle:**

- On app start: `DefaultModelRegistry` loads allowlist from bundled asset, scans local files for downloaded models, populates `StateFlow<List<ModelEntry>>` (each entry carries `downloadStatus` + `initStatus`). `SanctumApplication` then kicks off `WarmupCoordinator.warmupDefault()` on a background coroutine — resolves `default_model_id` → `last_used_model_id` → no-op (AC-F5), translates `modelId` → `Model.name` via the registry snapshot, and calls `registry.initialize(modelName)` which invokes `LlmModelHelper.initialize(...)`. This is expensive (5–30 sec on real hardware); running it from `Application.onCreate` lets the Home screen render first while the engine warms in the background.
- `ModelRegistry.activeModelName` is a derived `StateFlow<String?>` — projects the stable HF `modelId` of the single entry in `ModelInitStatus.Ready`, or null. Consumers (Home, ChatViewModel, Drawer) observe this reactively.
- `ChatViewModel.onCleared()` does NOT call `registry.cleanup` — the engine outlives any `NavBackStackEntry`. Re-entering the same chat reuses the warm engine; same-model navigation is instant.
- Cross-model switch goes through `WarmupCoordinator.cancelAndRestart(modelId)` — serialises via `restartMutex`, cancels the in-flight warmup Job (if any), then starts a new Job that calls `registry.initialize(newName)`. Inside `DefaultModelRegistry.initialize` the single-engine invariant is enforced: any prior `Ready` or `Initializing` entry with a different name is `releaseEngine`-d and flipped to `Idle` before the target flips to `Initializing`. A `catch(CancellationException)` resets the target's status to `Idle` so a cancelled warmup never leaves a stale `Initializing` state stranded in the registry.
- Same-model "reset conversation" (user tap on ↻) goes through `registry.resetConversation(name, systemPrompt)` — keeps the native engine allocated, clears KV cache + re-renders Jinja with the effective system prompt. Cheap (milliseconds).
- Heavy-setting reinit (accelerator flip) goes through `ChatViewModel.applyHeavySetting` → `registry.cleanup + registry.initialize` directly under the same lifecycle mutex; `WarmupCoordinator` is bypassed because the chat-modal `ReinitProgressDialog` owns the blocking UX (D15).

---

## Data Model

**Phase 2 reality (as of `v0.2-ui`):** Room is NOT yet introduced. Chats, messages, attachments all live in `ChatViewModel.StateFlow` and are discarded on process death. The Room schema described below is the Phase 3 target. Only the `models_meta` equivalent ships in Phase 2, implemented via Proto DataStore in `:core-settings` (schema: [core-settings/src/main/proto/app_settings.proto](core-settings/src/main/proto/app_settings.proto) — `map<string, PerModelSettings>` keyed by `Model.modelId`, every field `optional` for "use allowlist default" semantics).

**Database:** SQLite via Room 2.7.x. Single database `sanctum.db` in the app's private storage.

### Main Tables

**projects**
- Purpose: A user-defined grouping of chats sharing a system prompt (and later, shared files).
- Key fields: `id` (PK, autogenerated), `name` (TEXT, not null), `system_prompt` (TEXT, nullable — inherits model default if null), `default_model_id` (TEXT, nullable — suggested model for new chats), `created_at` (INTEGER, epoch ms).
- Relationships: One project has many chats (`chats.project_id`).

**chats**
- Purpose: A persistent conversation with a model. Optionally belongs to a project (Phase 4+).
- Key fields: `id` (PK), `project_id` (INTEGER, **nullable — no FK constraint in v1**; the `projects` table is added via `Migration(1,2)` in Phase 4 per AC-R4/AC-R7), `model_id` (TEXT, not null — which model was used; allows reopening a chat even if the user switched default models), `title` (TEXT, not null — auto-generated from first message, user-renameable), `is_manually_titled` (INTEGER 0/1 — distinguishes auto- from user-typed title, rename-blank resets to auto), `created_at` (INTEGER), `last_message_at` (INTEGER).
- Relationships: One chat has many messages (`messages.chat_id`).
- Note: **Quick chats are NOT stored in Room.** They are in-memory only — see "Quick chats" below.

**messages**
- Purpose: A single message in a chat — user input or assistant output.
- Key fields: `id` (PK), `chat_id` (FK → chats.id, not null, indexed), `role` (TEXT, not null — `user` or `assistant`), `text` (TEXT — primary content), `thinking_text` (TEXT, nullable — assistant's reasoning channel), `image_path` (TEXT, nullable — relative path to stored image in app files), `audio_path` (TEXT, nullable — relative path to stored audio), `created_at` (INTEGER, indexed), `token_count` (INTEGER, nullable — cached for context window accounting).
- Relationships: Belongs to exactly one chat.

**project_files** — NOT created in v1 schema (AC-R7). Added via `Migration(1,2)` in Phase 4 together with the `projects` table. v1 leaves `chats.project_id` as a plain nullable INTEGER with no FK and no index; the FK + index land in Phase 4 when the parent table exists.

**models_meta** *(cached allowlist + local state + user overrides)*
- Purpose: Persistent view of known models — both bundled allowlist entries and any imported/custom models. Local download state. User overrides of inference parameters.
- Key fields: `model_id` (PK, TEXT — e.g., `litert-community/gemma-4-E4B-it-litert-lm`), `display_name`, `file_name`, `file_path` (nullable — present when downloaded), `default_config_json` (TEXT — immutable defaults, see Inference Settings Schema below), `user_overrides_json` (TEXT, nullable — any subset of the same schema), `downloaded_at` (INTEGER, nullable).
- Effective inference config = `default_config_json` overlayed with `user_overrides_json`. Overrides UI lives in `InferenceSettingsScreen`; "Reset to defaults" clears `user_overrides_json`.

### Inference Settings Schema

The same schema shape lives in both `default_config_json` and `user_overrides_json`. Defaults match Google AI Edge Gallery, so users coming from Gallery get identical behavior out of the box:

| Field | Type | Default | Notes |
|---|---|---|---|
| `maxTokens` | int | 4000 | Maximum tokens per response. |
| `topK` | int | 64 | Sampler top-K. |
| `topP` | float | 0.95 | Sampler top-P. |
| `temperature` | float | 1.0 | Sampler temperature. |
| `enableThinking` | bool | false | Toggles litertlm thinking-channel (`message.channels["thought"]`). |
| `accelerator` | enum | `GPU` | `GPU` or `CPU`. User-facing choice. NPU is used transparently under the hood when both the device and the model support it; not exposed as a user choice (Gallery behaves the same way). |
| `systemPromptDefault` | string | "" | Default system prompt applied to quick chats with this model and to new chats in projects that have no project-level system prompt. |

Effective system prompt resolution order (most specific wins): chat override > project system prompt > `models_meta.user_overrides_json.systemPromptDefault` > `models_meta.default_config_json.systemPromptDefault`.

### Quick chats (not a table)

Quick chats are **not** stored in Room. They live only as a `QuickChatSession` object held in-memory by the application-scoped chat coordinator. Lifecycle: created when user taps the "new quick chat" action; discarded when the user leaves the quick-chat screen OR when the process is killed. Attachments (photo/audio) written during a quick chat go to a dedicated `filesDir/quick/` folder and are purged when the session ends.

This is the Incognito-mode equivalent: the only way to have a conversation that leaves no on-disk trace.

### Key Constraints

- **Foreign keys (v1):** `messages.chat_id` → `chats.id` (ON DELETE CASCADE — deleting a chat deletes its messages). `chats.project_id` has NO FK in v1 — it is a plain nullable column until `Migration(1,2)` in Phase 4 introduces the `projects` table.
- **Indexes (v1):** `messages.chat_id`, `messages.created_at`, `chats.last_message_at`. `chats.project_id` is not indexed in v1 (no lookups by project yet).
- **No unique business constraints** on user-facing fields (projects can share names, chats can share titles).

### Migration Strategy

**Tool:** Room migrations via `Migration(from, to)` classes registered in the `RoomDatabase.Builder`.

**Process:** Every schema change requires (a) bumping `@Database(version = N+1)`, (b) writing a `Migration(N, N+1)` that mutates the schema, (c) exporting the new schema JSON (Room generates these into `app/schemas/` — committed to git). Never editing past migrations.

**Testing:** DAO and migration tests run as androidTest (real SQLite) using `androidx.room:room-testing`.

### On-disk log layout (Phase 2.5+)

`context.filesDir/logs/` contains up to four files — all written and read exclusively by the app's own code, never backed up (see `data_extraction_rules.xml`):

| File | Writer | Purpose |
|---|---|---|
| `errors.log` | `ErrorLog` (`:core-runtime`) | Bounded on-device error log; rotated at 2 MB → `errors.log.1` |
| `errors.log.1` | `ErrorLog` rotation | Previous `errors.log` copy; may not exist |
| `crash.log` | `CrashHandler` (`:app/crash/`) | Latest uncaught exception record; overwritten on each new crash; head-truncated to 100 KB |
| `crash.log.dismissed` | `CrashState.markDismissed` | Zero-byte presence flag: banner is hidden. Deleted by `CrashHandler` on every new crash write so the banner resurfaces. |

`crash.log` and `crash.log.dismissed` are the sole cross-process state channel between the `:crash` OS process and the main process — DataStore is not used here (see patterns.md § Crash reporting and cross-process state).

### Sensitive Data

- **HuggingFace OAuth token** — lives in encrypted DataStore via `androidx.security.crypto`, never in Room and never in git.
- **No PII stored.** No user account, no email, no phone, no name. The only user-generated content is the chat messages themselves (text, optional image/audio attachments) — these never leave the device.
- **Chat attachments** (image, audio) stored as files in app-private storage (`context.filesDir/attachments/{chat_id}/`), path referenced from `messages` row. Not accessible to other apps.
