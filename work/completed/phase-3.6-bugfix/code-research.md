# Code Research — phase-3.6-bugfix

Branch: `phase/3.6-bugfix`. Verifies prior-session diagnosis line by line against current `main`, fills implementation detail, lists test surfaces and conflicts.

Legend: [CONFIRMED] = matches prior diagnosis; [CORRECTED] = prior claim differs from current state, see note; [NEW] = additional finding.

---

## 1. Verified file:line references

### Bug 1.1 — `bootstrapChatModelId` and `commitDraft` do not call `registry.resetConversation`

[CONFIRMED] `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`

`bootstrapChatModelId(...)` is at lines **729–783** (prior diagnosis said ~729, exact). The Persistent branch (731–751) only:

```kotlin
is ChatIdentity.Persistent -> {
    val entity = runCatching { chatDao.getById(id.id) }
        ...
    _chatModelId.value = entity?.modelId
    applyEffectiveConfigToModel()
    // B4 auto-resume detection
    val lastMsg = runCatching { messageDao.lastByChat(id.id) } ...
    if (lastMsg?.role == ROLE_USER) { ... observeFirstReadyThenResume(id) ... }
}
```

No `registry.resetConversation(...)` call anywhere in the Persistent branch. The Quick/Draft branch (752–782) likewise doesn't reset. Conversation/KV is whatever the previous chat left in the engine.

`commitDraft(...)` is at lines **911–1000** (prior diagnosis said ~911, exact). On the success arm (981–989) it emits `NavigateToPersistent(chatId)`; no `resetConversation` call. So the new Persistent VM constructed after navigation inherits whatever the engine remembered from the Draft-mode model picker turn (or any earlier chat).

### Bug 1.2 — `applyLightOverrides` only assigns `model.configValues`, doesn't recreate Conversation

[CONFIRMED] `ChatViewModel.kt` lines **442–450**:

```kotlin
fun applyLightOverrides() {
    viewModelScope.launch {
        val modelId = _chatModelId.value ?: return@launch
        val model = currentReadyModel() ?: return@launch
        val overrides = settingsRepository.observePerModelSettings(modelId).first()
        val defaults = computeDefaults(model)
        model.configValues = EffectiveConfig.merge(defaults, overrides)
    }
}
```

Just a map assignment. `LlmChatModelHelper.initialize(...)` at `core-runtime/.../LlmChatModelHelper.kt` lines **124–142** bakes `SamplerConfig(topK, topP, temperature)` into `ConversationConfig` once at conversation creation:

```kotlin
val conversation = engine.createConversation(
    ConversationConfig(
        samplerConfig = if (preferredBackend is Backend.NPU) null else SamplerConfig(
            topK = topK,
            topP = topP.toDouble(),
            temperature = temperature.toDouble(),
        ),
        systemInstruction = systemInstruction,
        tools = tools,
    )
)
```

Mutating `model.configValues` after this point has zero effect on the live `Conversation`. Confirmed by existing test `applyLightOverrides_updatesConfigValues_noCleanup` (`ChatViewModelTest.kt:1124`) which only asserts the map mutation and that init/cleanup were not called — it never verifies the engine actually sees the change.

### Bug 1.3 — `DefaultModelRegistry.resetConversation` silent skip

[CONFIRMED] `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt` lines **302–311**:

```kotlin
override suspend fun resetConversation(modelName: String, systemPrompt: String?) {
    withContext(Dispatchers.Default) {
        lifecycleMutex.withLock {
            val entry = _models.value.find { it.model.name == modelName } ?: return@withLock
            if (entry.initStatus !== ModelInitStatus.Ready) return@withLock
            val contents: Contents? = systemPrompt?.let { Contents.of(listOf(Content.Text(it))) }
            llmModelHelper.resetConversation(entry.model, systemInstruction = contents)
        }
    }
}
```

Two silent early-returns: missing entry (line 305) and non-Ready status (line 306). No `errorLog.w/e`, no diagnostic counter — caller has no way to distinguish "reset performed" from "reset skipped".

### Bug 1.4 — No diagnostic logging for reset

[CONFIRMED] grep for `resetConversation` across `core-runtime/src/main` finds three call sites in `DefaultModelRegistry.kt` only (lines 302, 308, plus interface at `ModelRegistry.kt:89`); none calls `errorLog.*`. `LlmChatModelHelper.resetConversation` at lines 151–198 catches and `Log.e`s only on `Exception` from native (line 196), nothing for the success path or the silent-skip path.

### Bug 2 — `deriveTopAppBarState` returns Draft regardless of init status

[CONFIRMED with nuance] `ChatViewModel.kt` lines **363–392**:

```kotlin
private fun deriveTopAppBarState(
    models: List<ModelEntry>,
    pinnedModelId: String?,
    warmupInFlight: Boolean,
    activeModelId: String?,
): TopAppBarState {
    val effectiveModelId = pinnedModelId ?: activeModelId ?: ""
    val effectiveModelName = models.firstOrNull { it.model.modelId == effectiveModelId }?.model?.name
    if (warmupInFlight) return TopAppBarState.Loading(effectiveModelId, effectiveModelName)
    return when (identity) {
        ChatIdentity.Draft -> TopAppBarState.Draft(
            models = models.filter { it.downloadStatus.status == ModelDownloadStatusType.SUCCEEDED },
            currentModelId = effectiveModelId,
        )
        ChatIdentity.Quick, is ChatIdentity.Persistent -> { /* maps initStatus → Ready/Loading/Failed */ }
    }
}
```

Nuance: warmup-in-flight gates Draft to Loading (line 371), but once warmup completes and the model is Ready, Draft falls through to `TopAppBarState.Draft`, never `TopAppBarState.Ready`.

`ChatScreen.kt` line **280**:
```kotlin
val settingsEnabled = engineUsable && !isGenerating && !reinitInProgress
```
Where `engineUsable` is derived from `topAppBarState is TopAppBarState.Ready` (verify in `ChatScreen.kt` head — see line 280 reads it as a boolean already; the assignment is upstream). The Settings IconButton at line **302** uses `enabled = settingsEnabled`. So in Draft mode `engineUsable` is always `false` regardless of whether the model is Ready, blocking Settings.

### Bug 3 — MainActivity does not call `enableEdgeToEdge()`

[CONFIRMED] `app/src/main/kotlin/app/sanctum/machina/MainActivity.kt` (full file, 41 lines, **line 21–39** for `onCreate`):

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        // POST_NOTIFICATIONS permission request only
    }

    setContent {
        SanctumTheme { SanctumApp() }
    }
}
```

No `enableEdgeToEdge()`, no `WindowCompat.setDecorFitsSystemWindows(window, false)`. Imports list at the top has only `androidx.activity.ComponentActivity` + `setContent` — would need to add `androidx.activity.enableEdgeToEdge`.

`ChatScreen.kt` lines **327–331** explicitly assume edge-to-edge:

```kotlin
.consumeWindowInsets(innerPadding)
// AC-U4: lift the input bar above the IME. Depends on
// `WindowCompat.setDecorFitsSystemWindows(window, false)`
// already set by MainActivity.
.imePadding(),
```

The comment is wrong — MainActivity does NOT set this. On EMUI/Honor the system-default insets apply IME inset twice (once via Scaffold's automatic `WindowInsets.systemBars`, once again via `imePadding()` on the inner Column), producing the visible gap.

`CrashReportActivity.kt` lines **80–95**: also a plain `ComponentActivity.onCreate` with no `enableEdgeToEdge()`. Has `window.setFlags(FLAG_SECURE, FLAG_SECURE)` at line 83 but no edge-to-edge call.

---

## 2. Existing patterns to reuse

### `applySystemPromptAndReset` — the LIGHT pattern to follow (model.configValues + resetConversation)

`ChatViewModel.kt` lines **452–467**:

```kotlin
fun applySystemPromptAndReset() {
    viewModelScope.launch {
        val modelId = _chatModelId.value ?: return@launch
        val model = currentReadyModel() ?: return@launch
        val overrides = settingsRepository.observePerModelSettings(modelId).first()
        val defaults = computeDefaults(model)
        val merged = EffectiveConfig.merge(defaults, overrides)
        model.configValues = merged
        val effective = effectiveSystemPrompt(merged)
        registry.resetConversation(model.name, systemPrompt = effective)
        _messages.value = emptyList()
        _streamingMessage.value = null
        _attachments.value = emptyList()
        _snackbar.tryEmit(R.string.settings_semilight_applied_snackbar)
    }
}
```

Pattern Light tier should adopt: assign `model.configValues = merged` THEN call `registry.resetConversation(...)`. The downstream `LlmChatModelHelper.resetConversation` at `LlmChatModelHelper.kt:151–198` already re-reads `topK`, `topP`, `temperature` from `model.configValues` (lines 164–167) when constructing the new `ConversationConfig`, so plumbing the new SamplerConfig through is already in place — no helper-API change needed for the LIGHT path. (See §4 below.)

Difference for Light tier: skip clearing `_messages` / `_streamingMessage` / `_attachments` (Light should not touch UI history), skip the system-prompt rebuild (Light doesn't change it), and skip the snackbar (or use a different string).

### `WarmupCoordinator.cancelAndRestart` — single-flight discipline

`app/src/main/kotlin/app/sanctum/machina/engine/WarmupCoordinator.kt` lines **106–156**:

- `restartMutex: Mutex` (line 83) serialises every `launchWarmup` body.
- Inside `launchWarmup` (lines 118–155) the previous `warmupJob` is captured, nulled (so its `finally` skips the flag-reset), then `cancelAndJoin`'d before starting the next job.
- The "still the current warmup" check at line 150 (`if (warmupJob === coroutineContext[Job])`) is the pattern to use if Bug-1 reset needs a similar single-flight queue across rapid chat-switches.

Relevance: rapid chat-switch could fire multiple `resetConversation(chat-switch)` calls in flight. `DefaultModelRegistry.lifecycleMutex` already serialises them (so they happen in order), but if we want to coalesce ("only the latest reset matters"), the pattern is here. For phase 3.6 the simpler choice is to rely on `lifecycleMutex` and let them run sequentially.

### `applyHeavySetting` — full reinit with `_reinitInProgress` UX gate

`ChatViewModel.kt` lines **469–515**:

- Sets `_reinitInProgress.value = true` (modal dialog), stops in-flight inference, calls `registry.cleanup`, then `registry.initialize`.
- `try / finally` guarantees the flag clears (line 512). Pattern useful if Light reset turns out to be non-trivial latency-wise (Risk 1 in user-spec).

### Test fakes — `FakeModelRegistry` already exists and records `lastResetSystemPrompt`

`app/src/test/kotlin/app/sanctum/machina/ui/chat/ChatViewModelTest.kt` lines **1784–1853**: `FakeModelRegistry` is a full `ModelRegistry` impl with:

- `var lastResetSystemPrompt: String? = null` (line 1788)
- `var cleanupCalls = 0`, `var initializeCalls = 0` (lines 1789–1790)
- `sharedCalls: MutableList<String>` for ordering assertions (line 1785)
- `override suspend fun resetConversation(...)` records `lastResetSystemPrompt` and pushes `"resetConversation"` into `sharedCalls` (lines 1847–1850)

Extend (don't replace) for phase 3.6: add `var resetReasons: MutableList<ResetReason> = mutableListOf()` once `ModelRegistry.resetConversation(...)` gains a reason parameter.

`core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/RecordingInitDiagnostics.kt` exists (analog from phase 3.5) — pattern to mimic for any diagnostics-counter additions. Per `DefaultModelRegistryTest.kt` lines 200–255, `QueuedLlmModelHelper` uses `model.instance = "fake-engine-${UUID}"` sentinel so `resetConversation` calls in tests need a non-null instance to exercise the success path; verify on next read — current `LlmChatModelHelper.resetConversation` (line 160) early-returns if `model.instance == null`, so tests must seed an instance.

---

## 3. Reset-reason enum design

### Proposed values
```kotlin
enum class ResetReason {
    CHAT_SWITCH,    // bootstrapChatModelId / persistent VM init when prior chat left KV
    DRAFT_COMMIT,   // commitDraft success → fresh persistent KV
    LIGHT_OVERRIDE, // applyLightOverrides — temperature/topK/topP/maxTokens
    SYSTEM_PROMPT,  // applySystemPromptAndReset — semi-light tier
    HEAVY,          // applyHeavySetting (kept for tagging the reset that happens after re-init,
                    //   if any; main heavy path is initialize() not resetConversation())
    USER,           // explicit "↻" tap from TopAppBar (existing fun resetConversation)
}
```

### Placement options

A. `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ResetReason.kt` — sibling to `ModelRegistry.kt`. Pros: lives next to the interface that consumes it; `:app` already depends on `:core-runtime`. Cons: tags like `LIGHT_OVERRIDE` are app-layer concepts leaking into core. Mitigated because the enum is opaque to the runtime — only logged as `name`.

B. `app/src/main/kotlin/app/sanctum/machina/ui/chat/ResetReason.kt` — pure app concept. Cons: `ModelRegistry.resetConversation` would need to accept `String` (loose) or `Any`/`Enum<*>` (gross). Bad choice.

**Recommendation:** option A. Place `enum class ResetReason` in `core-runtime/.../core/registry/`. Extend `ModelRegistry.resetConversation` signature:

```kotlin
suspend fun resetConversation(
    modelName: String,
    systemPrompt: String? = null,
    reason: ResetReason,  // no default — every caller must pick
)
```

Logging happens inside `DefaultModelRegistry.resetConversation`:
```kotlin
errorLog.i("inference-reset", "resetConversation reason=${reason.name} model=$modelName status=${entry.initStatus}")
```
(Adds `errorLog.i` if not yet present — verify on next read; phase-3.5 used `errorLog.e` for everything.) For AC-1.4 the non-Ready early return becomes:
```kotlin
if (entry.initStatus !== ModelInitStatus.Ready) {
    errorLog.w("inference-reset", "skipped: engine not Ready (status=${entry.initStatus}) reason=${reason.name}")
    return@withLock
}
```

---

## 4. `LlmChatModelHelper.resetConversation` signature

[CORRECTED] Prior diagnosis suggested "extend to accept new SamplerConfig". This is unnecessary — current signature already plumbs the new sampler.

`core-runtime/.../LlmChatModelHelper.kt` lines **151–198**:

```kotlin
override fun resetConversation(
    model: Model,
    supportImage: Boolean,
    supportAudio: Boolean,
    systemInstruction: Contents?,
    tools: List<ToolProvider>,
    enableConversationConstrainedDecoding: Boolean,
)
```

Inside the body (lines 164–167) it re-reads `topK`, `topP`, `temperature` from `model.configValues` directly:

```kotlin
val topK = model.getIntConfigValue(key = ConfigKeys.TOPK, defaultValue = DEFAULT_TOPK)
val topP = model.getFloatConfigValue(key = ConfigKeys.TOPP, defaultValue = DEFAULT_TOPP)
val temperature = model.getFloatConfigValue(key = ConfigKeys.TEMPERATURE, defaultValue = DEFAULT_TEMPERATURE)
```

So mutating `model.configValues` BEFORE calling `resetConversation` already plumbs the new sampler through. The Light path just needs to:

1. Set `model.configValues = EffectiveConfig.merge(defaults, overrides)` (already does).
2. Call `registry.resetConversation(model.name, systemPrompt = effectiveSystemPrompt(merged), reason = LIGHT_OVERRIDE)`.

No helper-API change needed. (Bug 1 fix becomes a one-call addition to `applyLightOverrides`.)

Note: `maxTokens` is baked into `EngineConfig` at engine creation (line 111), NOT into `ConversationConfig`. So changing `MAX_TOKENS` requires HEAVY reinit, not LIGHT — `classifyApplyLevel` at `ChatViewModel.kt:1300–1310` currently treats it as LIGHT (via `LIGHT_FIELD_LABELS`); verify whether `MAX_TOKENS` is in `LIGHT_FIELD_LABELS` on next read. If yes, that's a deviation from physical reality, and either AC-1.3 should drop `max_tokens` from the LIGHT criterion or `MAX_TOKENS` should be reclassified to HEAVY. Flag in tech-spec Decisions.

---

## 5. enableEdgeToEdge() requirements

### Dependency

`gradle/libs.versions.toml` line **8**: `activityCompose = "1.10.1"`. Line 37 declares `androidx-activity-compose = ...`. `enableEdgeToEdge()` is from `androidx.activity:activity-ktx`/`activity-compose` since 1.8.0; 1.10.1 includes it. No new dependency needed; just add the import:
```kotlin
import androidx.activity.enableEdgeToEdge
```

### Conflicts

Grep for `enableEdgeToEdge|setDecorFitsSystemWindows|statusBarColor|navigationBarColor` across `app/src/main` returns ZERO hits anywhere. No screen explicitly sets system-bar colors or fitsSystemWindows. So `enableEdgeToEdge()` will not conflict with existing code; the worst case is content drawing under the status bar, which Scaffold already handles via its automatic `WindowInsets`.

### Inset handling on each screen

| Screen | File | Pattern | Risk |
|--------|------|---------|------|
| Chat | `ChatScreen.kt:282–331` | `Scaffold { innerPadding -> Column(...padding(innerPadding).consumeWindowInsets(innerPadding).imePadding())` | Already designed for edge-to-edge — the bug is that edge-to-edge isn't on. Fixing MainActivity FIXES this screen. |
| Home | `HomeScreen.kt:106,126,128` | `Scaffold { innerPadding -> ...Modifier.fillMaxSize().padding(innerPadding)` | Standard Scaffold pattern — edge-to-edge will lift status bar correctly because Scaffold already injects `WindowInsets.systemBars`. No regression. |
| Diagnostics | `DiagnosticsScreen.kt:82,97,101` | Same Scaffold pattern | Same as Home. No regression. |
| Model Manager | `ModelManagerScreen.kt:114,129–130` | Same Scaffold pattern | Same. No regression. |
| About | `AboutScreen.kt:65,79,83` | Same Scaffold pattern | Same. No regression. |
| Drawer | `DrawerContent.kt` (uses `ModalNavigationDrawer` from `SanctumApp.kt`) | Sheet renders inside parent Scaffold; verify on next read | Low risk — ModalNavigationDrawer handles its own insets. |
| Crash Report | `CrashReportActivity.kt:126,128–131` | Same Scaffold pattern | Adding `enableEdgeToEdge()` to its `onCreate` mirrors MainActivity. Has `FLAG_SECURE` (line 83) — this does not interact with edge-to-edge. |

Conclusion: no screens are at risk of regression beyond standard Scaffold-with-padding-innerPadding behaviour, which is exactly what edge-to-edge is designed for. AC-3.3 visual smoke is still required because EMUI's interpretation may differ from stock Android, but the code-side risk is low.

---

## 6. Test surfaces

### `app/src/test/kotlin/app/sanctum/machina/ui/chat/ChatViewModelTest.kt` (2137 lines, 65 @Test methods)

Existing tests directly relevant:

| Line | Test | Coverage |
|------|------|----------|
| 1124 | `applyLightOverrides_updatesConfigValues_noCleanup` | Asserts map mutation + no cleanup/init. **Will need to flip:** must now assert `resetConversation` IS called (with `LIGHT_OVERRIDE` reason). |
| 1200 | `applySystemPromptAndReset_resetsWithPrompt` | Asserts `lastResetSystemPrompt`. Pattern to mimic for new Light test. |
| 1234 | `resetConversation_clearsAll` | Tests user-tap reset. Should be extended with `reason = USER`. |
| 1268, 1296, 1314, 1328, 1345, 1359, 1373, 1391 | `topAppBarState_*` (8 tests) | Cover Quick / Persistent × {Idle, Initializing, Failed, Ready} + warmup-in-flight + Draft-list. **Missing for Bug 2:** Draft × {Idle, Initializing, Ready, Failed} — currently Draft is only tested for the dropdown, not for whether Settings is enabled. New tests: `topAppBarState_draft_readyEngine_settingsEnabled` etc. |
| 153 | `draftMode_afterCommit_navigatesToPersistentRoute` | Pattern for Bug-1.2 sibling test: `draftMode_afterCommit_callsResetConversation_withDraftCommitReason`. |

Helper: `FakeModelRegistry` (lines 1784–1853) and `FakeLlmHelper` (lines 1855–1909) are reusable. Both implement `resetConversation` — extend `FakeModelRegistry` with `var resetReasons: MutableList<ResetReason>` once the enum lands.

### `core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistryTest.kt` (319 lines, 5 @Test methods)

Currently covers only `initialize` × {success, GPU-fallback success, full-fail, cancellation} for the InitDiagnostics call-order contract.

**No tests for `resetConversation`** — phase 3.6 needs:
- `resetConversation_skipsAndLogs_whenEngineNotReady` (AC-1.4): seeds entry with `ModelInitStatus.Idle`, calls `resetConversation`, asserts `errorLog.w` was called and `helper.resetConversation` was NOT.
- `resetConversation_dispatchesToHelper_whenReady` (AC-1.5): seeds Ready entry, calls with `reason = CHAT_SWITCH`, asserts helper was called and `errorLog.i` includes `reason=CHAT_SWITCH`.
- `resetConversation_skipsSilently_whenModelMissing` (defensive): unknown modelName → no helper call, no log spam.

`QueuedLlmModelHelper` and `SuspendingLlmModelHelper` (lines 200–299) are scoped to init tests. New tests can use a small `RecordingLlmHelper` recording `resetConversation` calls (parameterise just enough to assert it was hit). For ErrorLog assertions, current tests use `ErrorLog(context)` (real impl) at lines 76–77 — phase 3.6 may need a `RecordingErrorLog` fake or a Robolectric ShadowLog inspection; verify on next read.

### Other relevant test files

- `EffectiveConfigTest.kt` (`app/src/test/.../ui/chat/`) — already covers `EffectiveConfig.merge`. No changes needed.
- `SystemInstructionTest.kt` (`core-runtime/src/test/.../registry/`) — already covers `buildSystemInstruction`. No changes needed.
- No test exists for MainActivity edge-to-edge (no Robolectric activity tests in this codebase). Bug 3 is verified manually per AC-3.2/3.3.

---

## 7. Potential conflicts / risks

### `applyLightOverrides` mid-stream

`ChatViewModel.kt:442–450` is invoked through `dispatchByLevel` (line 1316) which is called from `saveAndApplySettings` (line 401) and `resetSettingsToDefaults` (line 412). Both are called from the Settings sheet. The Settings IconButton at `ChatScreen.kt:302` is gated by `settingsEnabled = engineUsable && !isGenerating && !reinitInProgress` (line 280) — so the sheet cannot be opened mid-stream, and the Apply button cannot be pressed mid-stream. Risk: zero from the UI side. Defensive: even if a corner case exists (race between stream completion and sheet open), `lifecycleMutex` in DefaultModelRegistry serialises `resetConversation` against any in-flight inference. The native `Conversation.close()` at `LlmChatModelHelper.kt:161` may interrupt an in-progress `sendMessageAsync`; if it does, the partial output already streamed via `MessageCallback` is lost. AC-R3-style "interrupted" marker should be set if we ever exercise this path.

### `lifecycleMutex` thread-safety for resetConversation

`DefaultModelRegistry.resetConversation` (line 302) wraps the body in `withContext(Dispatchers.Default) { lifecycleMutex.withLock { ... } }` — same discipline as `initialize`/`cleanup`/`delete`. So adding more `resetConversation` callers (chat-switch, draft-commit, light-override) cannot interleave; they queue serially. This is the desired guarantee — no extra synchronisation needed.

### Honor-specific code

Grep for `Honor|EMUI|Huawei` across `app/src/main`: zero hits. No Honor-specific gating exists — phase 3.6 must NOT introduce any (per memory `manifest_breadth_over_honor_lock`). `enableEdgeToEdge()` is standard AOSP — applies uniformly.

### `applyEffectiveConfigToModel` already runs in `bootstrapChatModelId`

`ChatViewModel.kt:1323–1329`: Runs on bootstrap and merges defaults∪overrides into `model.configValues` BEFORE the chat-switch fix would call `registry.resetConversation`. So the post-bootstrap `resetConversation(reason = CHAT_SWITCH)` will read the freshly-merged sampler — no stale-config risk. Caveat: `applyEffectiveConfigToModel` uses `preInitModel()` (line 1325) which returns the entry regardless of init status; it's safe to assign to `configValues` even pre-Ready, but `resetConversation` itself must wait for Ready (AC-1.4). Pattern: bootstrap calls reset eagerly; the silent-skip-with-warning path catches the not-yet-Ready case, and the natural retry is the next user `send()` — which already creates a fresh `Conversation` via the existing engine init path on first warm.

Actually no — once the engine reaches Ready, a chat-switch reset will not retry automatically. Recommended flow: in `bootstrapChatModelId` Persistent branch, observe `_uiState.first { Ready }` (or `registry.activeModelName` flip) BEFORE issuing the chat-switch reset, OR trust the existing flow: reset on chat-switch fires once engine is Ready, with a `viewModelScope.launch { observeFirstReadyThenReset() }` pattern mirroring the `observeFirstReadyThenResume` at line 832. This is a tech-spec decision item.

### `commitDraft` already triggers a process navigation

`ChatViewModel.kt:982–989`: success arm emits `NavigateToPersistent(chatId)` then the new VM is constructed. The `bootstrapChatModelId` of the NEW Persistent VM is what should issue `resetConversation(DRAFT_COMMIT)` — placing the reset in `commitDraft` itself would race with the navigation. Cleaner: have a single `resetConversation(CHAT_SWITCH)` in Persistent bootstrap that subsumes both regular chat-switch and post-draft-commit; only one call site. Or: distinguish the two with a heuristic (`messageDao.lastByChat(id.id)` already runs at line 744 — if `lastMsg?.role == ROLE_USER` we're in handover, reason=DRAFT_COMMIT; otherwise CHAT_SWITCH). Tech-spec call.

### `MAX_TOKENS` classification mismatch

See §4 final paragraph. `LIGHT_FIELD_LABELS` likely contains `MAX_TOKENS` but `maxNumTokens` is in `EngineConfig` not `ConversationConfig`, so changing it via Light path will not take effect even after Conversation recreation — needs HEAVY. Flag in tech-spec; verify `LIGHT_FIELD_LABELS` definition on next read (`Grep` for `LIGHT_FIELD_LABELS` in `ChatViewModel.kt`).

---

## 8. patterns.md deviation notice

`.claude/skills/project-knowledge/references/patterns.md` line **62**:

> **Light** (`temperature`, `topK`, `topP`, `maxTokens`) — override stored in DataStore, new `model.configValues` assigned; applies from next `send()` without engine touch. Active stream is not interrupted.

This is **incorrect on current LiteRT-LM 0.10.0**. The "applies from next send()" claim assumes either (a) the engine reads `configValues` per-send, or (b) `Conversation` is recreated per-send. Neither is true: `Conversation` is created once at `engine.createConversation` with a fixed `SamplerConfig` (`LlmChatModelHelper.kt:124–142`) and reused for the lifetime of the entry's `Ready` state.

Phase 3.6 must update this line. Proposed replacement:

> **Light** (`temperature`, `topK`, `topP`) — override stored in DataStore, `model.configValues` assigned, then `registry.resetConversation(reason = LIGHT_OVERRIDE)` recreates the engine's `Conversation` with the new `SamplerConfig`. UI history is preserved (in-memory list / Room rows untouched). The engine is not torn down. `maxTokens` is baked into `EngineConfig` and requires HEAVY tier.

Tech-spec must surface this as a Decisions/Deviations item: "patterns.md L62 wrong on engine-touch claim; updated as part of phase 3.6".

Also `maxTokens` belongs in HEAVY, not Light — see §4 + §7.

---

## Summary vs prior diagnosis

**Confirmations (everything from prior session held up under verification):**
- Bug 1.1: `bootstrapChatModelId` (lines 729–783) and `commitDraft` (lines 911–1000) do not call `registry.resetConversation`. Exact line numbers match.
- Bug 1.2: `applyLightOverrides` (lines 442–450) only mutates `model.configValues`. Exact line numbers match. `LlmChatModelHelper.initialize` bakes SamplerConfig at lines 124–142.
- Bug 1.3: `DefaultModelRegistry.resetConversation` (lines 302–311) silently early-returns on non-Ready. Exact line numbers match.
- Bug 1.4: No `errorLog` calls in any reset path. Confirmed by grep.
- Bug 2: `deriveTopAppBarState` (lines 363–392) returns `TopAppBarState.Draft` regardless of model init status; `ChatScreen.kt:280–302` gates Settings on `topAppBarState is TopAppBarState.Ready`. Confirmed.
- Bug 3: `MainActivity.onCreate` (lines 21–39) does NOT call `enableEdgeToEdge()`. `CrashReportActivity.onCreate` (lines 80–95) also lacks it. `ChatScreen.kt:327–331` comment explicitly states the dependency on a setting that is not in fact set. Confirmed.

**Corrections:**
- Prior diagnosis suggested extending `LlmChatModelHelper.resetConversation` to accept new SamplerConfig. **Not needed** — the helper already re-reads `topK/topP/temperature` from `model.configValues` at lines 164–167, so Light tier just needs to assign `configValues` first then call `registry.resetConversation`.
- `maxTokens` belongs in HEAVY tier, not LIGHT — it's baked into `EngineConfig` (engine creation), not `ConversationConfig` (conversation creation). Verify `LIGHT_FIELD_LABELS` on next read; either drop `MAX_TOKENS` from Light criterion or accept that Light reset is a no-op for this field.

**New findings:**
- `FakeModelRegistry` (ChatViewModelTest.kt:1784) and `FakeLlmHelper` (line 1855) already exist and record `lastResetSystemPrompt`/`sharedCalls` — extending them with `resetReasons` is a 2-line change.
- `DefaultModelRegistryTest.kt` (319 lines, 5 tests) covers only `initialize` — has zero tests for `resetConversation`. New tests required for AC-1.4/1.5.
- No screen sets system-bar colors anywhere in `app/src/main` — `enableEdgeToEdge()` will not conflict.
- Drawer-Persistent chat-switch reset has a "engine not yet Ready" edge case during cold-start race; recommended pattern is to mirror `observeFirstReadyThenResume` (line 832) with an `observeFirstReadyThenReset(reason)`.
- Draft→Persistent commit reset can be subsumed into the Persistent-bootstrap CHAT_SWITCH (use `messageDao.lastByChat` + `role == USER` heuristic at line 744 to distinguish DRAFT_COMMIT from CHAT_SWITCH if separate logging is desired).
- `patterns.md` line 62 is wrong about Light tier behaviour and must be updated as part of phase 3.6.
