# Patterns & Conventions

Coding conventions, development workflow, and project-specific practices.
For universal coding standards, see `~/.claude/skills/code-writing/references/universal-patterns.md`.

---

## Project-Specific Code Patterns

**Module boundary:** both `:core-runtime` AND `:core-settings` must have zero Compose / Activity / ViewModel imports. If a core class needs a Context, accept it as a constructor parameter — do not reach for `android.app.Application`. Check during code review: `grep -rEn "androidx.compose|androidx.activity" core-runtime/src/main core-settings/src/main` — zero hits expected (TAC-7, TAC-8 in Phase 2 tech-spec).

**Library-module manifest hygiene:** `:core-runtime/src/main/AndroidManifest.xml` must declare every runtime surface that library code touches — `<uses-permission>` for every permission-annotated API call made from library code (e.g., `POST_NOTIFICATIONS` for `NotificationManagerCompat.notify`), and a `<service tools:node="merge">` override for every system-provided service the library relies on (e.g., `androidx.work.impl.foreground.SystemForegroundService` with `foregroundServiceType="dataSync"`). Rationale: Android lint is scoped per module. `:app`'s final merged manifest is invisible to library-lint. Empty library manifest → false-positive `MissingPermission` / `SpecifyForegroundServiceType` errors even though the merged APK is runtime-correct. `tools:node="merge"` makes manifest-merger silently reconcile the duplicate with `:app`'s declaration, so there is no conflict at build time. Companion rule: any WorkManager-class-name reference inside `:app`'s own manifest (e.g., the `<service>` declaration for `SystemForegroundService`) requires `androidx.work:work-runtime-ktx` to be exposed as `api` (not `implementation`) from `:core-runtime`, otherwise `:app:lintDebug` fires `MissingClass`.

**Cross-module FQN plumbing via companion fields:** classes that live in `:core-runtime` but need to reference `:app`-only types (e.g., `MainActivity` for `PendingIntent`) must never hardcode the FQN. `:app` sets the string on a companion field during `SanctumApplication.onCreate` (see `DefaultDownloadRepository.mainActivityFqn`). Consumers inside `:core-runtime` (e.g., `DownloadWorker`) must `require(fqn.startsWith("app.sanctum.machina."))` before `Class.forName` — this is a package-prefix guard, not a complete whitelist, and it exists specifically to prevent arbitrary-class-load escalation if an attacker ever controls the intent extras (see TAC-14 in Phase 1 spec).

**ErrorLog component strings are a closed whitelist, enforced at runtime.** Only these values are allowed as the `component` argument to `ErrorLog.e(component, description, cause?)`: `"download"`, `"inference-init"`, `"inference"`, `"inference-cleanup"`, `"settings-io"`, `"camera"`, `"audio"`, `"attachment-decode"`. Unknown component → `IllegalArgumentException` thrown at call site (covered by `ErrorLogTest`). Adding a new component requires updating the constant set in `ErrorLog.kt` AND this doc AND the reviewing spec. Rationale: grep-friendly, bounded taxonomy, mapped one-to-one to specific failure modes.

**ErrorLog length bounding** (Phase 2 addition per TAC-15): `description` truncated at 500 chars, `cause.message` truncated at 200 chars, control whitespace collapsed. Prevents dumping full stacktraces with SELinux context / hardware identifiers into the on-device log.

**Model lifecycle:** LiteRT-LM inference engines are expensive to create (multiple seconds) and hold native state. Never call `LlmModelHelper.initialize` on a fresh handle when the user just sends a follow-up message — use `resetConversation` (cheap) instead. Full re-initialization only when the active model identity changes.

**Streaming assistant messages:** Phase 2 is in-memory-only — messages live in `ChatViewModel._messages: StateFlow<List<Message>>` and are discarded on process death (D1 Phase 2 user-spec). The thinking channel accumulates separately into `thinkingSb: StringBuilder`, gated once per send on `model.llmSupportThinking && effectiveConfig[ENABLE_THINKING]`; mid-stream config flip cannot half-populate the bubble. Attachments (`Bitmap` / raw PCM `ByteArray`) are snapshotted into `Message.attachments` when the USER message is appended and cleared from `_attachments` immediately after — so the history bubble reflects exactly what was dispatched (AC-26). Phase 3 will route the completed message into Room on `done = true`; the in-memory flow pattern from above will carry over.

**Error logging conventions:**
- **ERROR level only.** No `Log.i`, `Log.w`, `Log.d` in production code paths. Every `Log.e` is a real incident worth writing to the on-device log file.
- **One log = one event.** No multi-line logs, no emoji, no ornamental dividers. Format: `ERROR [component] short description :: cause`.
- **Components that must log errors** (mapped one-to-one to specific failure modes): model initialize failed, inference failed, model download failed, HuggingFace auth failed, Room migration failed, history load failed, history write failed, attachment write failed, attachment read failed.
- **Log file location:** `context.filesDir/logs/errors.log`. Rotate when size exceeds 2 MB: rename to `errors.log.1` (overwriting any previous rotated copy) and start fresh. Never uploaded anywhere.

**`Model.instance: Any?` is an anti-pattern — do not port it.** Gallery stores runtime state inside the data model. In `:core-runtime` the separation is: `ModelDefinition` (immutable config — id, paths, backend choices) vs `ModelRuntimeHandle` (mutable runtime state — engine, conversation). The registry maps definition → optional handle.

**Room DAOs are interfaces with suspend/Flow functions only.** No blocking DAO methods. ViewModels collect `Flow<List<T>>` and expose `StateFlow` to Compose. (Phase 3 — Room is not yet integrated as of `v0.2-ui`.)

**Markdown rendering goes through `SafeMarkdown`, never `RichText` / `Markdown` directly.** `SafeMarkdown` installs a scheme-whitelisted `LocalUriHandler` (`SafeUriHandler`) that allows only `http` / `https` (case-insensitive); all other schemes — `intent:`, `sms:`, `tel:`, `javascript:`, `file:`, `content:`, `data:`, `market:`, malformed — are silently ignored (blocked-scheme clicks are expected UX, not errors; no `ErrorLog` entry). LLM output is rendered through this wrapper so a `[text](intent://...)` in the assistant's markdown cannot launch arbitrary intents. Applies everywhere markdown lands: `MessageBubble`, `ThinkingBlock`, `AboutScreen`.

**Privacy hardening at the manifest level.** `:app/AndroidManifest.xml` must keep `android:allowBackup="false"` AND `android:dataExtractionRules="@xml/data_extraction_rules"`. The XML file excludes `<cloud-backup>` + `<device-transfer>` at `domain="root" path="."`. Both together block Google auto-backup AND device-to-device transfer channels. `FLAG_SECURE` is explicitly NOT added — user must be able to screenshot their own chats. Rationale: no cloud sync is a manifesto constraint (see `project.md § Out of Scope`); default Android backup silently ships DataStore and (Phase 3+) Room history to Google Drive. Closing this in Phase 2 is cheaper than after Room exists.

**Three-tier settings application classification (D15).** When applying inference-setting changes to a live engine, the field determines the path:
- **Light** (`temperature`, `topK`, `topP`, `maxTokens`) — override stored in DataStore, new `model.configValues` assigned; applies from next `send()` without engine touch. Active stream is not interrupted.
- **Semi-light** (`systemPromptDefault`, `enableThinking`) — override stored, then `registry.resetConversation(modelName, systemPrompt = effective)` is called: engine stays initialised but KV-cache clears and Jinja template re-renders with the new values. Chat UI history is also cleared (consistency with engine state). Snackbar "Настройки применены, контекст чата сброшен".
- **Heavy** (`accelerator`) — modal `HeavyChangeDialog` → confirm → `ReinitProgressDialog` → `cleanup + initialize` (5–30 sec). UI message list survives; engine context resets.

`ChatViewModel.classifyApplyLevel(overrides)` is the single entry point; any new inference field must be added to this classifier with explicit justification for its tier. `enableThinking` was reclassified from heavy to semi-light after Honor 200 smoke in Task 11 — `litertlm 0.10.0` accepts the flag change through `resetConversation` alone.



**Hilt module organization:** one module per concern (`InferenceModule`, `PersistenceModule`, `DownloadModule`, `AuthModule`). Avoid a single "god module". `:core-runtime` exposes services via interfaces so `:app` can swap or stub them in tests.

---

## Git Workflow

### Branch Structure

- **`master`** (tag, not branch) — immutable marker on the initial commit. `git checkout master` returns the project to its zero state. Used only as a nuclear "restart everything from scratch" escape hatch.
- **`main`** — the linear history of approved phases. Only merged into after the user explicitly says "утверждаю". Never force-pushed.
- **`phase/N-name`** — working branches for each phase: `phase/1-foundation`, `phase/2-ui`, `phase/3-history`, `phase/4-projects`, `phase/5-release`. Created from `main`. All work for a phase happens here. Merged to `main` via `--no-ff` (preserving the phase boundary) once approved. After merge, tagged (`v0.1-foundation`, etc.) and the branch may be deleted.
- **`phase/N-name.experiment-*`** — optional sub-branches inside a phase branch, for risky refactors where failure should not touch the phase branch. Example: `phase/1-foundation.split-model-manager` while dismantling Gallery's `ModelManagerViewModel` god-object. Merged back to the parent phase branch when the experiment lands.

### Commit Style

Conventional Commits: `feat: …`, `fix: …`, `refactor: …`, `chore: …`, `docs: …`, `test: …`. One concern per commit. Commit messages in English.

Commit frequency follows the methodology's commit strategy — one commit per stable step (draft → validation round → approval for specs; implementation → review-fix rounds → decisions for tasks), not per keystroke.

### Tags

- `master` — initial commit (immutable start point).
- `v0.1-foundation` — end of Phase 1.
- `v0.2-ui` — end of Phase 2.
- `v0.3-history` — end of Phase 3.
- `v0.4-projects` — end of Phase 4.
- `v1.0-release` — end of Phase 5 (first public release).

### Security & Quality Gates

- **Pre-commit:** `gitleaks` scans staged changes for secrets (HuggingFace tokens, API keys, keystore passwords). Commit blocked if anything matches. The repo should never contain a secret even once, since it will become public after Phase 5 testing and git history is permanent.
- **Pre-push:** run `:core-runtime` unit tests. Push blocked on failure. Keeps `main` always green for the core.

### `.gitignore` essentials (enforced from the start)

`.idea/`, `*.iml`, `build/`, `local.properties`, `.env`, `*.keystore`, `*.jks`, `gradle.properties.local`, `release-keys/`, `app/schemas/*.local.json`, log output files.

---

## Testing & Verification

### Test Infrastructure

- **Unit tests (`:core-runtime/src/test/`)**: JUnit 5 + MockK. Run via `./gradlew :core-runtime:test`. Target: most of the core is covered — `LlmModelHelper` wrapper logic (excluding actual litertlm native calls), `ModelRegistry`, `DownloadRepository` edge cases, allowlist parser, config validation, log formatter.
- **Instrumentation DAO tests (`:app/src/androidTest/`)**: androidx.test + `androidx.room:room-testing`. Run via `./gradlew :app:connectedAndroidTest` on a connected device or emulator. Target: every Room DAO + every schema migration.
- **Manual smoke test with real LiteRT-LM**: run before every phase release. Checklist in the per-phase tech-spec. Minimum: load a downloaded Gemma-4-E2B-it, send "Hello", receive a non-empty response, receive streamed tokens, reset conversation, receive a second response.

**What we explicitly do not test:** Compose UI (no `ComposeTestRule`-based UI tests), visual appearance (checked manually in Phase 2 and Phase 5), end-to-end inference in CI (no model in CI; see manual smoke test above).

### Agent Verification Methods

- **Gradle build / unit tests:** `./gradlew :core-runtime:test` and `./gradlew build` can be executed by the agent. Treat `BUILD FAILED` as hard stop. Treat test failures likewise.
- **Static checks:** `./gradlew lintDebug` catches Android-specific issues; `./gradlew detekt` (if configured) catches Kotlin style.
- **Schema diff check:** when touching Room entities, compare generated `app/schemas/{db}/{version}.json` against previous — any diff without a migration means a broken change.

### User Verification Methods

- **Runtime behavior on device:** only the user can install the APK on the Honor 200 and verify UI responsiveness, model loading speed, multimodal input, visual polish. Agents can describe what to look for; they cannot see the screen.
- **Comparative speed vs Gallery:** "на той же модели скорость не хуже" — verified by the user running the same prompt on Gallery and Sanctum Machina back-to-back. This is the hard success criterion for Phase 1.

---

## Business Rules

### Chat context window

Each chat is constrained by the active model's context window (e.g., 32K tokens for Gemma-4-E2B/E4B). Once the running token count of all messages in the chat exceeds the window, the tail of older messages is dropped from the prompt sent to the model — but NOT from Room storage. The user sees the full chat history; only the model sees a truncated tail.

Policy decision deferred to Phase 3: exact truncation strategy (hard cut at oldest-first, summarize-then-drop, or "start a new chat" prompt to the user). Default for Phase 3 MVP: hard cut, oldest-first.

### Project system prompt inheritance

When the user opens a new chat inside a project, the chat's system prompt is inherited from the project. The user may override it for a single chat — that override is stored on the chat row, not the project row. Changing the project's system prompt does not retroactively change existing chats' effective system prompt (they keep whatever they started with), but affects new chats in the project.

### Model switching mid-chat

Deferred. MVP: a chat is bound to one `model_id` for its lifetime. If the user wants to try a different model on the same topic, they start a new chat (optionally in the same project, inheriting the system prompt).

### Quick chat lifecycle (incognito)

A quick chat is ephemeral. It lives in a `QuickChatSession` object attached to the chat coordinator and is **never** written to Room. Termination conditions (any one of these):
- User navigates away from the quick-chat screen (back press, switching to a project, opening settings).
- App process is killed (either by the system or by the user).

On termination: session object is dropped, and any attachments the user added during the chat (photos, audio) are deleted from `filesDir/quick/` immediately. No history remains.

**UX implications:** no title autogeneration (there is nothing to title), no "Continue last chat" entry for quick chats. Leaving the screen shows a confirmation dialog only if a streaming response is in progress — otherwise silent exit.

**Code rule:** `QuickChatSession` must never take a `chat_id` from Room; it must never be passed to any `MessageDao` or `ChatDao`. Reviewers (manual and automated) check for this.

### Per-model inference settings override

Defaults come from the allowlist (`models_meta.default_config_json`). User overrides live in `models_meta.user_overrides_json` as a JSON object containing only the fields the user changed; effective config is `default ∪ overrides`. "Reset to defaults" sets `user_overrides_json` to null. Overrides do not travel with chats — changing overrides affects all future inferences with that model, including reopened old chats.
