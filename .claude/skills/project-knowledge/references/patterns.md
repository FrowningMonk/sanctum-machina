# Patterns & Conventions

Coding conventions, development workflow, and project-specific practices.
For universal coding standards, see `~/.claude/skills/code-writing/references/universal-patterns.md`.

---

## Project-Specific Code Patterns

**Module boundary:** `:core-runtime` must have zero Compose / Activity / ViewModel imports. If a core class needs a Context, accept it as a constructor parameter — do not reach for `android.app.Application`. Check during code review: grep `:core-runtime` for `androidx.compose.*` and `androidx.activity.*` — zero hits expected.

**Model lifecycle:** LiteRT-LM inference engines are expensive to create (multiple seconds) and hold native state. Never call `LlmModelHelper.initialize` on a fresh handle when the user just sends a follow-up message — use `resetConversation` (cheap) instead. Full re-initialization only when the active model identity changes.

**Streaming assistant messages:** do not insert a new DB row per streamed token. Stream to an in-memory `StateFlow<String>` held by `ChatViewModel`; write the completed message to Room once the `done = true` signal arrives. UI reads the in-memory flow during streaming, then transitions to the Room-backed flow when the assistant message is persisted.

**Error logging conventions:**
- **ERROR level only.** No `Log.i`, `Log.w`, `Log.d` in production code paths. Every `Log.e` is a real incident worth writing to the on-device log file.
- **One log = one event.** No multi-line logs, no emoji, no ornamental dividers. Format: `ERROR [component] short description :: cause`.
- **Components that must log errors** (mapped one-to-one to specific failure modes): model initialize failed, inference failed, model download failed, HuggingFace auth failed, Room migration failed, history load failed, history write failed, attachment write failed, attachment read failed.
- **Log file location:** `context.filesDir/logs/errors.log`. Rotate when size exceeds 2 MB: rename to `errors.log.1` (overwriting any previous rotated copy) and start fresh. Never uploaded anywhere.

**`Model.instance: Any?` is an anti-pattern — do not port it.** Gallery stores runtime state inside the data model. In `:core-runtime` the separation is: `ModelDefinition` (immutable config — id, paths, backend choices) vs `ModelRuntimeHandle` (mutable runtime state — engine, conversation). The registry maps definition → optional handle.

**Room DAOs are interfaces with suspend/Flow functions only.** No blocking DAO methods. ViewModels collect `Flow<List<T>>` and expose `StateFlow` to Compose.

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
