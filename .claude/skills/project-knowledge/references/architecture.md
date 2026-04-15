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

**Audio recording:** Android `MediaRecorder` (platform API, no extra dep)
- **Why:** Simple voice input, no need for a library.

**Markdown rendering in chat:** com.halilibo.compose-richtext
- **Why:** Matches Gallery, handles code blocks + math + standard markdown in streamed responses.

**Testing:** JUnit 5 + MockK for unit tests on `:core-runtime`; androidx.test + Room testing for DAO tests.
- **Why:** Minimal viable test stack. Compose UI tests are explicitly not in scope.

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
│   ├── src/main/AndroidManifest.xml        # final merged manifest: permissions, SystemForegroundService, hardening flags
│   └── src/main/kotlin/app/sanctum/machina/
│       ├── ui/                             # Compose screens (chat/, modelmanager/, theme/); SanctumApp.kt NavHost
│       ├── MainActivity.kt
│       └── SanctumApplication.kt           # @HiltAndroidApp; wires DefaultDownloadRepository.mainActivityFqn
│                                           # Phase 2+: data/ (Room repos), di/ (app-level Hilt modules)
│
├── core-runtime/                           # :core-runtime gradle module — extracted Gallery core
│   ├── build.gradle.kts                    # namespace = "app.sanctum.machina.core"
│   ├── src/main/AndroidManifest.xml        # library-scope hygiene: uses-permission + service merge-override (see patterns.md)
│   └── src/main/kotlin/app/sanctum/machina/core/
│       ├── common/                         # shared utilities (enums, helpers)
│       ├── data/                           # Model / ModelAllowlist / Config / DownloadRepository (data classes + repo)
│       ├── di/                             # Hilt modules (CoreRuntimeModule, @Provides graph)
│       ├── inference/                      # LlmChatModelHelper (litertlm wrapper)
│       ├── log/                            # ErrorLog (on-device error writer)
│       ├── registry/                       # ModelRegistry + AllowlistLoader (+ schema guards)
│       ├── runtime/                        # runtime helpers (ModelHelperExt, coroutine scope)
│       └── worker/                         # DownloadWorker (WorkManager foreground service)
│                                           # Phase 2: auth/ (HF OAuth), Phase 4: embed/ (Embedder interface)
│
├── build.gradle.kts                        # Root build config
├── settings.gradle.kts                     # Module declarations (:app, :core-runtime)
├── gradle/
│   └── libs.versions.toml                  # Pinned version catalog
└── .gitignore
```

**Module boundary rule:** `:app` depends on `:core-runtime`; `:core-runtime` does NOT depend on `:app` or on Compose. The core module must remain UI-free so it stays extractable for future reuse (including KMP).

---

## Key Dependencies

**Critical packages:**
- `com.google.ai.edge.litertlm:litertlm-android` (0.10.0) — **the** inference engine. CPU/GPU/NPU backends, streaming token generation, multimodal `Contents` API, system instruction support. Pinned version; upgrades planned carefully.
- `androidx.hilt:hilt-android` + `dagger-hilt-android-compiler` (2.57.1, KSP) — dependency injection across both modules.
- `androidx.room:room-runtime` / `room-ktx` / `room-compiler` (2.7.x) — persistent store for projects, chats, messages. KSP code generation.
- `androidx.work:work-runtime-ktx` (2.10.0) — `ModelDownloadWorker` runs as foreground service with notification, survives app kill.
- `net.openid:appauth` (0.11.1) — OAuth for HuggingFace when downloading gated models.
- `androidx.camera:camera-camera2` / `camera-lifecycle` / `camera-view` (1.4.2) — photo capture for multimodal input.
- `com.halilibo:compose-richtext-commonmark` — markdown rendering in chat messages.

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

- On app start: `ModelRegistry` loads allowlist from bundled asset, scans local files for downloaded models, populates a `StateFlow<List<ModelState>>`.
- On first chat open: `ModelInitService.initialize(model)` calls `LlmModelHelper.initialize(...)` — this is expensive (seconds). Resulting `ModelRuntimeHandle` is held as an application-scoped singleton; subsequent chat switches within the same model call `resetConversation(...)` which is cheap.
- On model switch: old handle is cleaned up via `LlmModelHelper.cleanUp`, new handle initialized.

---

## Data Model

**Database:** SQLite via Room 2.7.x. Single database `sanctum.db` in the app's private storage.

### Main Tables

**projects**
- Purpose: A user-defined grouping of chats sharing a system prompt (and later, shared files).
- Key fields: `id` (PK, autogenerated), `name` (TEXT, not null), `system_prompt` (TEXT, nullable — inherits model default if null), `default_model_id` (TEXT, nullable — suggested model for new chats), `created_at` (INTEGER, epoch ms).
- Relationships: One project has many chats (`chats.project_id`).

**chats**
- Purpose: A persistent conversation with a model, belonging to exactly one project.
- Key fields: `id` (PK), `project_id` (FK → projects.id, **not null** — every persistent chat belongs to a project), `model_id` (TEXT, not null — which model was used; allows reopening a chat even if the user switched default models), `title` (TEXT, nullable — auto-generated from first message if null), `created_at` (INTEGER), `last_message_at` (INTEGER).
- Relationships: One chat has many messages (`messages.chat_id`). One chat belongs to exactly one project.
- Note: **Quick chats are NOT stored in Room.** They are in-memory only — see "Quick chats" below.

**messages**
- Purpose: A single message in a chat — user input or assistant output.
- Key fields: `id` (PK), `chat_id` (FK → chats.id, not null, indexed), `role` (TEXT, not null — `user` or `assistant`), `text` (TEXT — primary content), `thinking_text` (TEXT, nullable — assistant's reasoning channel), `image_path` (TEXT, nullable — relative path to stored image in app files), `audio_path` (TEXT, nullable — relative path to stored audio), `created_at` (INTEGER, indexed), `token_count` (INTEGER, nullable — cached for context window accounting).
- Relationships: Belongs to exactly one chat.

**project_files** *(Phase 4 — schema reserved now, populated later)*
- Purpose: Files uploaded into a project, used as shared context for all chats in that project.
- Key fields: `id` (PK), `project_id` (FK → projects.id, not null, indexed), `name` (TEXT), `storage_path` (TEXT), `text_content` (TEXT, nullable — extracted plain text), `embedding` (BLOB, nullable — populated by future `Embedder`), `created_at` (INTEGER).
- Relationships: Belongs to exactly one project.
- Note: Table created in v1 schema with all columns nullable so Phase 4 does not require a migration. No DAO methods exposed until Phase 4.

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

- **Foreign keys:** `chats.project_id` → `projects.id` (ON DELETE SET NULL — deleting a project turns its chats into quick chats, does not destroy them). `messages.chat_id` → `chats.id` (ON DELETE CASCADE — deleting a chat deletes its messages). `project_files.project_id` → `projects.id` (ON DELETE CASCADE).
- **Indexes:** `messages.chat_id`, `messages.created_at`, `chats.project_id`, `chats.last_message_at`, `project_files.project_id`.
- **No unique business constraints** on user-facing fields (projects can share names, chats can share titles).

### Migration Strategy

**Tool:** Room migrations via `Migration(from, to)` classes registered in the `RoomDatabase.Builder`.

**Process:** Every schema change requires (a) bumping `@Database(version = N+1)`, (b) writing a `Migration(N, N+1)` that mutates the schema, (c) exporting the new schema JSON (Room generates these into `app/schemas/` — committed to git). Never editing past migrations.

**Testing:** DAO and migration tests run as androidTest (real SQLite) using `androidx.room:room-testing`.

### Sensitive Data

- **HuggingFace OAuth token** — lives in encrypted DataStore via `androidx.security.crypto`, never in Room and never in git.
- **No PII stored.** No user account, no email, no phone, no name. The only user-generated content is the chat messages themselves (text, optional image/audio attachments) — these never leave the device.
- **Chat attachments** (image, audio) stored as files in app-private storage (`context.filesDir/attachments/{chat_id}/`), path referenced from `messages` row. Not accessible to other apps.
