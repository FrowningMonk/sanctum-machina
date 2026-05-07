---
created: 2026-05-05
status: approved
branch: phase/3.6-bugfix
size: S
---

# Tech Spec: phase-3.6-bugfix

Three Honor 200 testing bugs: KV-cache leakage across chats, settings unavailable in new persistent chat before first message, IME gap under input bar. No new features — pure bugfix scoped to LiteRT-LM 0.10.0 lifecycle, top-app-bar gating, and edge-to-edge.

## Solution

The three bugs share two underlying causes:

1. **Conversation lifecycle is implicit and underused.** `LiteRT-LM`'s `Conversation` object holds the KV-cache and bakes `SamplerConfig` at creation time (`LlmChatModelHelper.kt:124-142`). The codebase only resets it on explicit ↻ tap and on the SYSTEM_PROMPT tier; chat switches, draft commits, and Light-tier slider changes don't trigger a reset, so the engine carries forward stale KV and ignores user-changed temperature/topK/topP. Fix: every chat boundary and every Light-tier change must call `registry.resetConversation(reason)`. Diagnostic logging tagged with the reason makes future regressions debuggable.

   Recreating Conversation on CHAT_SWITCH/LIGHT_OVERRIDE without passing history clears KV-cache and loses re-entry context. LiteRT-LM 0.10.0 `ConversationConfig` accepts `initialMessages: List<Message>` — engine prefills these at creation. ChatViewModel loads paired history from Room (`messageDao.getByChatId`), maps each row through `MultimodalContentsBuilder` + `Contents.of(...)` to a `litertlm.Message` (USER → `Message.user`, ASSISTANT → `Message.model`), and passes the list through `registry.resetConversation(initialMessages = …)`. DRAFT_COMMIT / SYSTEM_PROMPT / USER / HEAVY receive `emptyList()` (fresh-context semantics); CHAT_SWITCH (return to a chat) and LIGHT_OVERRIDE (Persistent slider-Apply) carry the paired tail with the unpaired USER row dropped when auto-resume needs to dispatch it.

2. **Window insets are configured for one half of the contract.** `ChatScreen` already uses `Scaffold + consumeWindowInsets + imePadding` — code that's correct only when the host activity has called `enableEdgeToEdge()`. `MainActivity.onCreate` and `CrashReportActivity.onCreate` never make that call, so EMUI on Honor 200 double-counts the IME inset and produces the gap. Fix: add `enableEdgeToEdge()` in both activities. Code research confirmed no screen sets `statusBarColor` / `navigationBarColor` / `fitsSystemWindows`, so no other screens regress.

A third issue surfaced during code research: `MAX_TOKENS` is misclassified as Light tier in `LIGHT_FIELD_LABELS` (`ChatViewModel.kt:1518`). It's physically baked into `EngineConfig` (engine creation, not `Conversation`), so even after the Light-tier Conversation-recreation fix, `max_tokens` slider changes would silently no-op. Fix: reclassify to Heavy — `classifyApplyLevel` returns HEAVY when `MAX_TOKENS` differs, triggering `HeavyChangeDialog` → `ReinitProgressDialog` → `cleanup + initialize`, the only path that actually applies the new limit.

Bug 2 is a one-line fix to `deriveTopAppBarState` Draft branch — when warmup completes and the chat-model entry is `Ready`, return `TopAppBarState.Ready` instead of `TopAppBarState.Draft` for the gating purpose. The Draft model picker remains accessible because `TopAppBarState.Draft` carries dropdown state separately; we must keep the picker working AND enable Settings. Solution: Settings gating in `ChatScreen.kt` switches from `topAppBarState is TopAppBarState.Ready` to a dedicated `engineReady: StateFlow<Boolean>` exposed by the VM. Both Draft and Persistent observe the same boolean.

`patterns.md` line 62 states the now-disproven claim that Light tier "applies from next send() without engine touch". It must be rewritten as part of this phase to prevent the wrong rule from being repeated by future phases.

## Architecture

### What we're building/modifying

- **`ResetReason` enum** — new file in `:core-runtime/.../core/registry/`. Values: `CHAT_SWITCH`, `DRAFT_COMMIT`, `LIGHT_OVERRIDE`, `SYSTEM_PROMPT`, `HEAVY`, `USER`. Tags every `resetConversation` call so logs distinguish causes.
- **`ModelRegistry.resetConversation`** — interface + impl gain `reason: ResetReason` parameter. `DefaultModelRegistry.resetConversation` gains diagnostic logging (success path: `errorLog.i`, non-Ready skip: `errorLog.w`) and removes the silent-skip behaviour. `ErrorLog.ALLOWED_COMPONENTS` gains a single new entry: `"inference-reset"`.
- **`ChatViewModel`:**
  - `bootstrapChatModelId` — Persistent branch issues `resetConversation(CHAT_SWITCH or DRAFT_COMMIT)` once the engine is Ready (uses the existing `observeFirstReadyThenResume`-style pattern). Distinguishes DRAFT_COMMIT from CHAT_SWITCH by the heuristic: last persisted message is unpaired USER → DRAFT_COMMIT (handover from draft); else CHAT_SWITCH.
  - `applyLightOverrides` — after `model.configValues` mutation, calls `registry.resetConversation(reason = LIGHT_OVERRIDE)`. UI history (`_messages`, `_streamingMessage`, `_attachments`) is not touched (Light is a sampler refresh, not a context wipe).
  - `LIGHT_FIELD_LABELS` — `MAX_TOKENS` removed.
  - `classifyApplyLevel` — Heavy condition becomes `acceleratorChanged || maxTokensChanged`.
  - `deriveTopAppBarState` — unchanged; it must continue to return `TopAppBarState.Draft` for Draft so the model picker dropdown works.
  - New `engineReady: StateFlow<Boolean>` — `true` iff the entry for the current model is `ModelInitStatus.Ready` and not warmup-in-flight. Settings gating consumes this.
- **`ChatScreen.kt`:**
  - Settings IconButton gating switches from `topAppBarState is TopAppBarState.Ready` to `engineReady && !isGenerating && !reinitInProgress`. The wrong comment about MainActivity at line 329 stays but becomes correct after MainActivity is fixed.
- **`MainActivity.onCreate`** — adds `enableEdgeToEdge()` before `setContent`.
- **`CrashReportActivity.onCreate`** — adds `enableEdgeToEdge()` before `setContent`. `FLAG_SECURE` is unaffected.
- **`patterns.md` § Three-tier settings application classification (D15)** — Light bullet rewritten to reflect Conversation-recreation reality and `MAX_TOKENS` migration to Heavy.

### How it works

**Chat switch flow (Persistent → Persistent):**
```
Drawer tap → NavController → ChatScreen(chatId) → new ChatViewModel
  → bootstrapChatModelId(Persistent(id))
    → applyEffectiveConfigToModel()  // existing
    → observeFirstReady {
        if (lastMsg?.role == USER) reason = DRAFT_COMMIT else CHAT_SWITCH
        registry.resetConversation(modelName, systemPrompt = effective, reason)
      }
    → if unpaired USER tail → observeFirstReadyThenResume(id)  // existing AC-R3
```

**Light-tier slider apply:**
```
User changes temperature in InferenceSettingsBottomSheet → "Применить"
  → ChatViewModel.saveAndApplySettings()
    → classifyApplyLevel() → LIGHT  (max_tokens removed from set)
    → applyLightOverrides()
      → settingsRepository.observePerModelSettings(...).first()
      → model.configValues = EffectiveConfig.merge(defaults, overrides)  // existing
      → registry.resetConversation(model.name, systemPrompt = effective, reason = LIGHT_OVERRIDE)  // NEW
        → DefaultModelRegistry under lifecycleMutex:
          → if !Ready → errorLog.w("inference-reset", "skipped reason=LIGHT_OVERRIDE status=...")
          → else → llmHelper.resetConversation(...) re-reads topK/topP/temperature from configValues
                    → errorLog.i("inference-reset", "reason=LIGHT_OVERRIDE")
      → no UI history clear (Light tier preserves message list)
```

**max_tokens change (now Heavy):**
```
User changes max_tokens in sheet → "Применить"
  → classifyApplyLevel() → HEAVY  (NEW: max_tokens added to heavy condition)
  → applyHeavySetting()  // existing — HeavyChangeDialog → confirm → ReinitProgressDialog → cleanup+initialize
```

**Edge-to-edge fix:**
```
MainActivity.onCreate → enableEdgeToEdge() → setContent { SanctumApp() }
  → SanctumApp routes Scaffold → ChatScreen
    → Scaffold(contentWindowInsets = WindowInsets.systemBars)  // implicit M3 default
    → Column(...padding(innerPadding).consumeWindowInsets(innerPadding).imePadding())
    → IME up → exactly one IME inset application → no gap.
```

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `Engine` (LiteRT-LM native) | `DefaultModelRegistry.initialize` (via `LlmChatModelHelper`) | `ChatViewModel` (read via registry), `WarmupCoordinator` | 1 (single-engine invariant — see `architecture.md`) |
| `Conversation` (per-Engine) | `LlmChatModelHelper.initialize` / `.resetConversation` | `LlmChatModelHelper.runInference` | 1 per Engine; recreated on every reset |
| `lifecycleMutex` (`DefaultModelRegistry`) | DefaultModelRegistry singleton | `initialize`, `cleanup`, `resetConversation`, `delete` | 1 (singleton) — already serialises the new reset call sites |

## Decisions

### Decision 1: `ResetReason` lives in `:core-runtime/.../core/registry/`; unknown-model name skip stays silent
**Decision:** Place the `ResetReason` enum next to `ModelRegistry.kt` interface, not in `:app`. Keep the early-return for unknown model names silent (no log).
**Rationale (placement):** `ModelRegistry.resetConversation(reason: ResetReason)` is the cross-module API surface. Keeping the type in `:core-runtime` avoids passing `String` (loose) or `Any` (gross) and keeps `:core-runtime` self-contained. The tag values themselves (`LIGHT_OVERRIDE` etc.) are app-layer concepts but they're opaque to the runtime — only logged as `name`.
**Rationale (silent unknown-name):** Rapid chat-switch races between `bootstrapChatModelId` and `WarmupCoordinator.cancelAndRestart` can briefly leave the registry without an entry for the target model name. Logging every such transient miss would generate spam without diagnostic value (the warmup coordinator itself logs the actual init failure). The non-Ready skip path (`errorLog.w`) already covers the meaningful misuse pattern: model exists, engine is not ready.
**Alternatives considered:** Place enum in `:app/ui/chat/` — rejected, `ModelRegistry` would lose type safety. Pass `String` reason — rejected, typo-prone. Log unknown-name skip too — rejected, log spam without value.
**User-spec link:** Supports AC-1.4 (only meaningful skips warning-logged) and AC-1.5 (every successful reset logged with reason tag).

### Decision 2: Distinguish `DRAFT_COMMIT` from `CHAT_SWITCH` via existing `messageDao.lastByChat` heuristic, not a new state flag
**Decision:** In `bootstrapChatModelId`, after the existing `lastMsg = messageDao.lastByChat(id.id)` call (line 744), use `lastMsg?.role == ROLE_USER` as the heuristic: USER tail = handover from draft commit (DRAFT_COMMIT); else CHAT_SWITCH.
**Rationale:** Both reasons fire from the same code path (Persistent VM bootstrap after navigation). Adding a separate flag (e.g., a `NavigateToPersistent.fromDraft: Boolean` argument) couples `commitDraft` to bootstrap state and creates a parallel signal. The lastByChat heuristic is already computed for AC-R3 auto-resume — reusing it costs nothing.
**Alternatives considered:** Reset inside `commitDraft` itself — rejected because it races with `NavigateToPersistent` and the new VM. New flag in nav args — rejected because of YAGNI (one heuristic suffices) and additional state surface.
**User-spec link:** Supports AC-1.2 (commit draft → fresh KV). [TECHNICAL] for the choice of heuristic — user-spec doesn't require log-tag granularity to distinguish the two, only that both happen.

### Decision 3: Light-tier reset happens on Apply, not on slider drag
**Decision:** `applyLightOverrides` is invoked from the bottom sheet's "Применить" action via the existing `dispatchByLevel` path. Slider drags inside the sheet update DataStore but do not call `resetConversation`.
**Rationale:** Recreating Conversation is fast (milliseconds), but doing it on every Slider value change would (a) generate log spam for `inference-reset`, (b) potentially race with mid-drag DataStore writes, (c) wastes work the user is going to overwrite three frames later. The existing dispatch flow already gates Apply behind a single button — we keep that.
**Alternatives considered:** Debounce slider with 300 ms timer — rejected as YAGNI for size-S phase. Apply on sheet dismiss — rejected because the existing UX is "Apply or Cancel" with explicit user intent.
**User-spec link:** Supports AC-1.3a (applies to next response of same chat). [TECHNICAL] for the apply-on-button choice.

### Decision 4: `MAX_TOKENS` reclassified to Heavy
**Decision:** Remove `ConfigKeys.MAX_TOKENS.label` from `LIGHT_FIELD_LABELS`. Extend `classifyApplyLevel` so HEAVY fires on `acceleratorChanged || maxTokensChanged`.
**Rationale:** `maxNumTokens` is a field of `EngineConfig` (passed at `engine.create`), not `ConversationConfig`. Even after Light-tier Conversation recreation, the engine's `maxNumTokens` is fixed; the slider was visually responsive but functionally a no-op. Heavy path (`cleanup + initialize`) is the only place where a new `EngineConfig` is constructed. UX cost: `HeavyChangeDialog` confirmation + 5–30 sec `ReinitProgressDialog`. Acceptable — same as accelerator flip.
**Alternatives considered:** Add a SEMI_HEAVY tier between Light and Heavy — rejected as overengineering for a single field. Hide `max_tokens` slider entirely — rejected because the user wants the control; the limit affects context-window usage materially.
**User-spec link:** Supports AC-1.3b (max_tokens via Heavy). Documented in user-spec § Технические решения.

### Decision 5: Add `i` and `w` levels to `ErrorLog` via shared `write(level, ...)` helper; whitelist `"inference-reset"`
**Decision:** Skeptic + security review confirmed `ErrorLog.kt` exposes only `e()`. Add `i()` and `w()` as new public methods routing through a single private `write(level, component, description, cause)` helper that owns the existing length-bounding (description ≤500, cause ≤200), `sanitize()` whitespace-collapse, and rotation. The `e()` method becomes a thin wrapper. All three levels share the exact same input pipeline — adding a fourth level later is one line. Add `"inference-reset"` to `ALLOWED_COMPONENTS` (size 14 → 15).
**Rationale:** A parallel formatter for `i`/`w` would duplicate the bounding/sanitize logic and risk drift (security finding #2 + #3). The single-helper refactor is small, makes all levels uniformly safe, and is a well-defined unit of test coverage (`iAndW_lengthBoundingMatchesE`). Reset events are operational at success path (`i`), warning at non-Ready skip (`w`); `ErrorLog` already handles rotation/SAF/export — no new infrastructure.
**Alternatives considered:** Three independent methods each with their own bounding code — rejected, drift risk. Use Android `Log.d/i/w` only — rejected because those don't reach diagnostic export (AC-1.5 requires the tag in exported logs). Defer the refactor and inline `i`/`w` quickly — rejected because security review explicitly flagged the drift (`status=...` could interpolate `Throwable.message`).
**User-spec link:** Supports AC-1.4 (non-Ready warning) and AC-1.5 (every reset logged with reason).

### Decision 6: New `engineReady: StateFlow<Boolean>` in `ChatViewModel`, not a new TopAppBarState branch
**Decision:** Expose `val engineReady: StateFlow<Boolean>` derived from `_uiState` (Ready iff matching entry's `initStatus is Ready` and `!warmupInFlight`). `ChatScreen` consumes this for the Settings IconButton gating. `deriveTopAppBarState` keeps returning `TopAppBarState.Draft` for Draft (model picker dropdown).
**Rationale:** Bug 2 fix shouldn't break the Draft model picker. Adding a `Ready` branch to `TopAppBarState.Draft` would entangle two orthogonal concerns (dropdown state vs settings-button readiness). A simple `Boolean` flag is the right primitive.
**Alternatives considered:** Add `TopAppBarState.DraftReady` — rejected as a state explosion (×4 init-status × draft/persistent already). Compute readiness inline in ChatScreen from existing flows — rejected because the readiness logic (warmup-in-flight + Ready status) is non-trivial and belongs in the VM.
**User-spec link:** Supports AC-2.1 (Settings active in draft after Ready), AC-2.3 (blocked when not Ready).

### Decision 7: `enableEdgeToEdge()` is the only edge-to-edge mechanism — no manual `setDecorFitsSystemWindows`
**Decision:** Use the AndroidX `enableEdgeToEdge()` extension from `androidx.activity:activity-compose` 1.10.1 (already on classpath). Apply in `MainActivity.onCreate` and `CrashReportActivity.onCreate` before `setContent`. Do not touch `WindowCompat.setDecorFitsSystemWindows` directly.
**Rationale:** `enableEdgeToEdge()` is the documented, future-proof API; it handles status-bar / nav-bar contrast correctly across light/dark themes and doesn't require manual color management. Per `code-research.md` § 5, no screen in the codebase sets `statusBarColor` / `navigationBarColor` / `fitsSystemWindows` — there is no conflicting state. Honor 200 (EMUI) bug is the lack of this call, not a custom inset implementation.
**Alternatives considered:** `WindowCompat.setDecorFitsSystemWindows(window, false)` directly — rejected because the comment in `ChatScreen.kt:329` mentioning it stays accurate either way, and `enableEdgeToEdge()` is a strict superset that also calls into `SystemBarStyle` defaults. Wrap in version check `if (Build.VERSION.SDK_INT >= ...)` — rejected because `minSdk = 31` and `enableEdgeToEdge()` is API 21+.
**User-spec link:** Supports AC-3.1, AC-3.2, AC-3.3.

### Decision 8: Update `patterns.md` § Three-tier classification AS PART OF this phase
**Decision:** Phase 3.6 includes a documentation-writing task (Task 5 in Wave 2) that rewrites the Light bullet of `.claude/skills/project-knowledge/references/patterns.md` to reflect the Conversation-recreation reality and the `MAX_TOKENS` migration. New text drafted in code-research.md § 8.
**Rationale:** The Phase 3.6 implementation invalidates the current rule. Leaving the wrong rule in patterns.md is a future trap — agents and humans will follow it and re-introduce the bug. The doc fix is small (one paragraph) and naturally co-located with the code fix.
**Alternatives considered:** Defer to a separate doc phase — rejected because it leaves a broken rule in PK during the gap. File an issue — there's no issue tracker; PK is the system of record.
**User-spec link:** Supports user-spec § Технические решения (explicit decision to update PK).

### Decision 9: `initialMessages` is passed only for CHAT_SWITCH and LIGHT_OVERRIDE
**Decision:** `ChatViewModel` builds `initialMessages` from `messageDao.getByChatId(chatId)` (paired tail, unpaired USER dropped when auto-resume will dispatch it) and passes the list to `registry.resetConversation` for two reasons: CHAT_SWITCH (re-entering a chat — restores own KV) and LIGHT_OVERRIDE in Persistent identity (slider-Apply preserves visible context). DRAFT_COMMIT, SYSTEM_PROMPT, USER, HEAVY receive `emptyList()` (default-parameter path — callers don't even mention the parameter). Quick / Draft LIGHT_OVERRIDE also receives `emptyList()` because those identities have no persistent history.
**Rationale:** Re-entering a chat without prefill loses everything the user can still see in the message list — the bug 1 fix would otherwise be only half done (isolation between chats works, return to a chat is broken). DRAFT_COMMIT cannot meaningfully replay (the chat is by construction either empty or has a single unpaired USER tail that auto-resume picks up). SYSTEM_PROMPT clears UI history on purpose — replaying it engine-side would re-introduce the very state the user just elected to wipe. USER ↻ tap is an explicit wipe. HEAVY uses `cleanup + initialize`, not `resetConversation`, so the parameter is irrelevant on that path.
**Alternatives considered:** Always pass paired history regardless of reason — rejected: SYSTEM_PROMPT / USER would silently no-op the user's wipe. Persist KV-cache to disk — rejected: not in LiteRT-LM 0.10.0's public API. Run a long-lived engine per chat — rejected: violates the single-engine invariant (Decision T9, Phase 1) and would multiply RAM use by the number of open chats.
**User-spec link:** Supports refined AC-1.1 and Сценарий 1 шаг 4 (return to A → model remembers).

## Data Models

No new data models. `PerModelSettings` proto unchanged. No Room migration. `ResetReason` is an in-memory `enum class` — never serialized.

`ErrorLog.ALLOWED_COMPONENTS` constant set gains one entry: `"inference-reset"`. This is a code-level whitelist, not a data model — but documented in `patterns.md` § ErrorLog component strings.

## Dependencies

### New packages

None. `enableEdgeToEdge()` is in `androidx.activity:activity-compose:1.10.1` already declared in `gradle/libs.versions.toml:8`.

### Using existing (from project)

- `:core-runtime` — `ModelRegistry`, `DefaultModelRegistry`, `LlmChatModelHelper`, `ErrorLog`. Modified.
- `:app` — `ChatViewModel`, `ChatScreen`, `MainActivity`, `CrashReportActivity`. Modified.
- `androidx.activity:activity-compose` — `enableEdgeToEdge()`. Already present.
- `JUnit 4.13.2 + Robolectric 4.12` — existing test infra.

## Testing Strategy

**Feature size:** S

### Unit tests

**`DefaultModelRegistryTest.kt` (currently 5 tests, all init-only — phase 3.6 adds reset coverage):**
- `resetConversation_skipsAndLogsWarning_whenEngineIdle` — seed entry with `ModelInitStatus.Idle`, call with `reason = CHAT_SWITCH`. Assert: helper NOT called; `errorLog.w("inference-reset", ...)` containing `"skipped"`, `"CHAT_SWITCH"`, `"Idle"`.
- `resetConversation_skipsAndLogsWarning_whenEngineInitializing` — seed Initializing. Same assertions; status string contains `"Initializing"`.
- `resetConversation_skipsAndLogsWarning_whenEngineFailed` — seed Failed. Same; status string contains `"Failed"`. Failure cause text MUST go through the existing `sanitize()` + length-bound pipeline (no parallel formatter — see Decision 5).
- `resetConversation_dispatchesAndLogsInfo_whenReady` — seed Ready. Call with `reason = LIGHT_OVERRIDE`. Assert helper called once with merged `model.configValues`; `errorLog.i("inference-reset", "...LIGHT_OVERRIDE...")`.
- `resetConversation_skipsSilently_whenModelMissing` — unknown name → no helper call, no log.
- `resetConversation_isSerializedByLifecycleMutex` — use `runTest` with explicit sequence numbers and a delay-injected helper to prove serialisation; not a single-thread dispatcher artifact.
- `resetConversation_propagatesHelperException` — helper throws; assert exception bubbles, lifecycleMutex is released (a follow-up call succeeds).

**`ChatViewModelTest.kt` (extend existing, add new):**
- `applyLightOverrides_callsResetConversation_withLightOverrideReason` — replaces existing `applyLightOverrides_updatesConfigValues_noCleanup` (line 1124). KEEP the no-cleanup/no-init regression guard from the original: `assertEquals(0, fakeRegistry.cleanupCalls); assertEquals(0, fakeRegistry.initializeCalls)`. NEW assertions: `lastResetReason == ResetReason.LIGHT_OVERRIDE`; `lastResetSystemPrompt` matches merged effective prompt.
- `bootstrapPersistent_chatSwitchReset_waitsForReady` — two-phase: (a) seed Initializing, bootstrap Persistent with ROLE_ASSISTANT lastByChat, advance dispatcher; assert `resetReasons` is empty (cold-start race not fired prematurely). (b) flip entry to Ready, advance; assert `resetReasons == [CHAT_SWITCH]` exactly once.
- `bootstrapPersistent_emitsDraftCommitReset_whenLastIsUnpairedUser` — same shape; lastByChat returns USER; assert `[DRAFT_COMMIT]`.
- `engineReady_combinatorics` (parameterised across the 5 states) — Idle → false; Initializing → false; Failed → false; Ready + warmup-in-flight → false; Ready + no-warmup → true.
- `applySystemPromptAndReset_passesSystemPromptReason` — extend existing `applySystemPromptAndReset_resetsWithPrompt` (line ~1200) to assert `lastResetReason == SYSTEM_PROMPT`.
- `userTapReset_passesUserReason` — extend existing `resetConversation_clearsAll` (line ~1234) to assert `lastResetReason == USER`.
- `classifyApplyLevel_returnsHeavy_forMaxTokens` — change `MAX_TOKENS` only → `HEAVY`.
- `classifyApplyLevel_returnsHeavy_forAccelerator` — keep existing baseline.
- `classifyApplyLevel_returnsLight_forTemperature` — `LIGHT` for temperature only.
- `applyMaxTokens_followsHeavyDialogSequence` — mirror existing `applyHeavySetting_sequencing_stopCleanupInitialize` pattern. Trigger via `saveAndApplySettings(maxTokens=X)`. Assert ordered sequence in `fakeRegistry.sharedCalls`: `["heavyDialogShown", "userConfirmed", "reinitDialogShown", "cleanup", "initialize"]` — pinning that the dialog path is taken, not just that cleanup+init were called.

**`ErrorLogTest.kt` (extend):**
- `whitelistCount_is15` — replace existing `assertEquals(14, ALLOWED_COMPONENTS.size)` with `15`.
- `inferenceResetComponent_acceptedByAllLevels` — `e("inference-reset", "x")` AND `i(...)` AND `w(...)` all succeed.
- `unknownComponent_stillRejected_negative` — `e("inference-reset-x", "x")` throws `IllegalArgumentException` (closed-whitelist invariant preserved).
- `iAndW_lengthBoundingMatchesE` — pass 600-char description and 300-char cause to `i` and `w`; assert truncation to 500/200 (verifies the single private `write(level, ...)` helper from Decision 5 inherits length-bounding).

### Integration tests

None — fix is isolated within already-tested module boundaries.

### E2E tests

None — bug 3 is verified manually on Honor 200 per AC-3.2/3.3.

## Agent Verification Plan

**Source:** user-spec § Как проверить.

### Verification approach

Per-task verification is via Gradle unit tests (every task with code changes) and lint (`./gradlew :app:lintDebug`). Bug 3 is the only one that requires a real device — no agent verification possible. The agent verifies build success, test pass, and lint cleanness; the user verifies visual behaviour on Honor 200.

### Tools required

- Gradle CLI (`./gradlew`) — unit tests + lint + assemble.
- That's it. No MCP, no Playwright, no curl. No deploy step (mobile app, no CI).

## Risks

| Risk | Mitigation |
|------|-----------|
| Recreating `Conversation` for Light-tier change is observably slow (>200 ms perceptible delay between Apply and next response) | Measure on Honor 200 during implementation. If slow, surface a brief loading indicator on Apply. Code research pattern (`applyHeavySetting._reinitInProgress`) is reusable. |
| `enableEdgeToEdge()` regresses one of the 6 screens in dark mode / unusual nav-bar transparency | AC-3.3 mandatory smoke on all 6 screens before merge. `code-research.md` § 5 confirmed code-side risk is low — no manual `statusBarColor` set anywhere. |
| `bootstrapChatModelId` reset fires before engine reaches Ready (cold-start race) and is silently warning-logged, leaving stale KV | Mirror `observeFirstReadyThenResume` (line 832) — wait for first Ready signal before issuing reset. Test `bootstrapPersistent_emitsChatSwitchReset_onceEngineReady` covers this. |
| `MAX_TOKENS` reclassification to Heavy surprises the user — slider movement now triggers a 5–30 sec dialog where it previously felt instant (silently broken) | Document in `patterns.md` D15 update (Decision 8). The new behaviour matches accelerator flip. The previous "instant" feel was a lie — slider had no effect. |
| Adding `i` / `w` levels to `ErrorLog` if absent introduces log-volume regression (rotation hits more often) | Verify on first read of `ErrorLog.kt` whether they exist. If not, the existing 2 MB rotation cap absorbs reset traffic — typical reset is one line, ~120 chars, ≤10/min in active use. Worst case: 1.4 KB/min, 84 KB/hr — comfortably under cap. |
| `initialMessages` prefill on CHAT_SWITCH adds a one-shot cost on the first Ready of the new chat. Honor 200 + Gemma-4-E4B on ~30 short messages (~1500 tokens) is seconds, not minutes — but a long history could surprise the user with a perceived "extra warmup". | Measure empirically during implementation (see § Verification → User smoke). If visibly slow in the UI, migrate to the `applyHeavySetting._reinitInProgress` pattern with an explicit indicator. For Phase 3.6 baseline — no indicator, since the user already waits on first Ready (warmup) and prefill happens within the same loading-state window. |

## User-Spec Deviations

- **AC-1.3 (split into 1.3a + 1.3b):** user-spec originally stated a single AC for Light-tier behaviour of `temperature/topK/topP/max_tokens`. Tech-spec splits this — Light path covers temperature/topK/topP only; max_tokens moves to Heavy path because it's baked into `EngineConfig`, not `ConversationConfig`. Reason: code research uncovered the misclassification. Updated AC list in user-spec already reflects the split. → [APPROVED IN PRE-DRAFT — user confirmed Option A]

- **Added: `LIGHT_FIELD_LABELS` reclassification of `MAX_TOKENS`** — not originally in user-spec scope. Reason: emerged from code research; leaving the misclassification means the slider stays a no-op, and the phase title "fix three Honor 200 bugs" implicitly requires settings to work as advertised. → [APPROVED IN PRE-DRAFT]

- **Added: Update `patterns.md` § D15 Light bullet** — not originally in user-spec scope. Reason: the patterns.md text becomes wrong the moment Light-tier code changes; PK is the methodology source of truth and must stay correct. Documentation-writing task added to Wave 3. → [APPROVED IN PRE-DRAFT]

- **Added: `ErrorLog.ALLOWED_COMPONENTS` += `"inference-reset"`** — not in user-spec, follows from AC-1.4 / AC-1.5 (logging requirements). Reason: `patterns.md` § ErrorLog component strings is a closed whitelist enforced at runtime; a new logging surface requires whitelisting. → [APPROVED IN PRE-DRAFT — implicit via AC-1.4/1.5 logging requirements]

- **Clarification on AC-2.2:** "Изменения, сделанные до первого сообщения, применяются к первому сообщению" is satisfied by the existing data flow — settings sheet writes per-model overrides to DataStore (via `SettingsRepository`); the first inference reads `model.configValues` (merged defaults + overrides) at `LlmChatModelHelper.runInference`. No additional plumbing needed, but the chain (DataStore write → bootstrap re-merge → first inference read) is implicit in the codebase, not a new mechanism in this phase. → [APPROVED IN PRE-DRAFT — documented for traceability]

## Acceptance Criteria

Технические критерии (дополняют пользовательские из user-spec):

- [ ] `./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest` — все тесты зелёные.
- [ ] `./gradlew :app:lintDebug` — нет новых warnings в затронутых файлах.
- [ ] `./gradlew :app:assembleDebug` — debug APK собран.
- [ ] Module boundary preserved: `:core-runtime` остаётся UI-free (grep `androidx.compose|androidx.activity` в `core-runtime/src/main` → 0 hits).
- [x] `ErrorLog.ALLOWED_COMPONENTS` обновлён в `ErrorLog.kt` И в `patterns.md` § ErrorLog component strings.
- [x] `patterns.md` § D15 Light bullet перезаписан — больше нет утверждения «applies from next send() without engine touch».
- [ ] Все callers `registry.resetConversation` передают явный `reason` — не `default`-параметр.
- [ ] `LIGHT_FIELD_LABELS` не содержит `MAX_TOKENS`.

## Implementation Tasks

Wave structure avoids parallel edits to the same file. `ChatViewModel.kt` is touched by Tasks 3 (Wave 3) and 6 (Wave 4) — they serialise across waves. `ChatScreen.kt` is touched only by Task 6 (gating + stale-comment cleanup folded in). `ErrorLog.kt` (Task 1, Wave 1) must land before `DefaultModelRegistry` calls `errorLog.i/w` (Task 2, Wave 2).

### Wave 1 — `ErrorLog` levels (prerequisite for Wave 2 Task 2)

#### Task 1: `ErrorLog` refactor — shared `write(level, ...)` helper + add `i()` / `w()` + whitelist `"inference-reset"`
- **Description:** Refactor `ErrorLog` so that `e()`, `i()`, `w()` all route through a private `write(level, component, description, cause)` helper owning the existing length-bounding and `sanitize()` whitespace collapse (limits in Decision 5). Add `"inference-reset"` to `ALLOWED_COMPONENTS` (size 14 → 15). Update tests to cover all three levels and the closed-whitelist invariant.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/log/ErrorLogTest.kt`
- **Files to read:** `.claude/skills/project-knowledge/references/patterns.md` (§ ErrorLog component strings + § ErrorLog length bounding)

### Wave 2 — `ResetReason` + `ModelRegistry` logging (depends on Task 1)

#### Task 2: `ResetReason` enum + `ModelRegistry.resetConversation` signature + `DefaultModelRegistry` logging
- **Description:** Create `ResetReason` enum (CHAT_SWITCH, DRAFT_COMMIT, LIGHT_OVERRIDE, SYSTEM_PROMPT, HEAVY, USER) in `:core-runtime/.../core/registry/`. Extend `ModelRegistry.resetConversation` interface with `reason: ResetReason` parameter (no default — every caller must pick). Replace silent non-Ready skip in `DefaultModelRegistry.resetConversation` with `errorLog.w("inference-reset", ...)`; on success path, `errorLog.i("inference-reset", ...)`. Unknown-model name remains silent (rationale in Decisions § 1).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ModelRegistry.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ResetReason.kt` (new)
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt` (note new `i`/`w` from Task 1), `work/phase-3.6-bugfix/code-research.md`

### Wave 3 — Bug 1 wiring + edge-to-edge + docs

Tasks 3, 4, 5 in parallel — disjoint file sets. Task 3 owns `ChatViewModel.kt` for this wave.

#### Task 3: Wire reset reasons across `ChatViewModel` and reclassify `MAX_TOKENS` to Heavy
- **Description:** Combined Bug-1 fix in `ChatViewModel`. Persistent branch of `bootstrapChatModelId` waits for first Ready signal (mirror `observeFirstReadyThenResume`), then issues `registry.resetConversation(reason)` with reason chosen by `lastByChat` heuristic (USER tail → DRAFT_COMMIT, else CHAT_SWITCH). `applyLightOverrides` calls reset with `LIGHT_OVERRIDE` after the `model.configValues = merged` mutation; UI history is preserved. `applySystemPromptAndReset` passes `SYSTEM_PROMPT`; existing user-tap reset passes `USER`. Remove `ConfigKeys.MAX_TOKENS.label` from `LIGHT_FIELD_LABELS`; update `classifyApplyLevel` so HEAVY fires on `acceleratorChanged || maxTokensChanged`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** На Honor 200: (a) persistent чат A с длинной перепиской → переключиться в чат B через drawer → «о чём мы говорили выше?» отвечает только про B. (b) Поймать repetition loop, открыть Settings → снизить temperature → следующий ответ другой. (c) Изменить max_tokens → появляется HeavyChangeDialog (как у акселератора).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ResetReason.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Config.kt` (defines `ConfigKeys.MAX_TOKENS`), `work/phase-3.6-bugfix/code-research.md` (§ 2 patterns + § 4 + § 7 conflicts)

#### Task 4: `enableEdgeToEdge()` in `MainActivity` and `CrashReportActivity`
- **Description:** Call `enableEdgeToEdge()` in both activities' `onCreate` before `setContent`. In `CrashReportActivity` it goes after the existing `FLAG_SECURE` `setFlags` line — they're independent window APIs and order is preserved. No other changes — no manual status-bar / nav-bar colors, no `setDecorFitsSystemWindows`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor
- **Verify-user:** На Honor 200: открыть чат → тапнуть по input bar → клавиатура поднимается → нет зазора между ней и input bar. Затем пройти Home / Drawer / Model Manager / Diagnostics / About / Crash Report — нет регрессий: контент не уезжает под status/nav bar, текст читаем.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/MainActivity.kt`, `app/src/main/kotlin/app/sanctum/machina/crash/CrashReportActivity.kt`
- **Files to read:** `work/phase-3.6-bugfix/code-research.md` (§ 5 inset table)

#### Task 5: Update `patterns.md` § D15 Light bullet and § ErrorLog component strings
- **Description:** Rewrite the Light bullet of `patterns.md` § Three-tier settings application classification (D15) per code-research.md § 8 — Conversation-recreation reality, MAX_TOKENS migrated to Heavy. Append `"inference-reset"` to the whitelist enumeration in § ErrorLog component strings with a one-paragraph rationale.
- **Skill:** documentation-writing
- **Reviewers:** code-reviewer, documentation-reviewer
- **Files to modify:** `.claude/skills/project-knowledge/references/patterns.md`
- **Files to read:** `work/phase-3.6-bugfix/code-research.md` (§ 8), `work/phase-3.6-bugfix/tech-spec.md` (Decision 4, Decision 5)

### Wave 4 — Bug 2 wiring (depends on Task 3 landing in `ChatViewModel`)

#### Task 6: `engineReady` StateFlow + Settings gating switch in `ChatScreen` (+ stale comment cleanup)
- **Description:** Expose `val engineReady: StateFlow<Boolean>` in `ChatViewModel` — `true` iff the entry for the current model is `ModelInitStatus.Ready` AND `!warmupInFlight`. In `ChatScreen.kt`, replace the existing `topAppBarState is TopAppBarState.Ready` boolean (source for `engineUsable`/`settingsEnabled`) with `engineReady` collected as state. `deriveTopAppBarState` keeps returning `TopAppBarState.Draft` for Draft (model picker dropdown unchanged). Also remove the now-stale comment in `ChatScreen.kt` (the line about MainActivity setting decorFitsSystemWindows — becomes implicit after Task 4).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** На Honor 200: создать новый persistent чат → дождаться прогрева модели → НЕ отправляя сообщение, тапнуть по иконке Settings — sheet открывается. До прогрева — кнопка серая.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`
- **Files to read:** `work/phase-3.6-bugfix/code-research.md` (§ 1 Bug 2 nuance + § 5 inset table for the comment context)

### Audit Wave

#### Task 7: Code Audit
- **Description:** Full-feature code quality audit. Read all modified files (Tasks 1–6). Review for cross-component issues: `ResetReason` placement, mutex discipline preserved, no Honor-specific code introduced (per memory `manifest_breadth_over_honor_lock`), `:core-runtime` UI-free invariant preserved. Write audit report to `work/phase-3.6-bugfix/logs/audit/code-audit.md`.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 8: Security Audit
- **Description:** Full-feature security audit. Read all modified files. OWASP Top 10 review. Specific points: log-injection through reset message construction (`status=...` interpolating `Throwable.message` — must route through `sanitize()`), edge-to-edge does not clobber `FLAG_SECURE` in `CrashReportActivity`, no new permission, `ErrorLog` length-bounding still in effect for new `i`/`w` levels. Write report to `work/phase-3.6-bugfix/logs/audit/security-audit.md`.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 9: Test Audit
- **Description:** Full-feature test audit. Read all new and modified test files. Verify coverage of every AC, meaningful assertions (not just smoke), no test-only behaviour leaking into production. Test pyramid: unit-only (no integration / E2E for size S) — that's the chosen strategy. Write report to `work/phase-3.6-bugfix/logs/audit/test-audit.md`.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 10: Pre-deploy QA
- **Description:** Acceptance testing. Run `./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest :app:lintDebug :app:assembleDebug`. Verify every AC from user-spec (AC-1.1 … AC-3.3) and from tech-spec (this file). For AC-3.2 / AC-3.3 / AC-1.1 / AC-1.2 / AC-1.3a / AC-1.3b / AC-2.1 / AC-2.2 — emit a Verify-user request to the user (Honor 200 smoke). Aggregate results in `work/phase-3.6-bugfix/logs/qa/pre-deploy-qa.md`.
- **Skill:** pre-deploy-qa
- **Reviewers:** none

(No Deploy / Post-deploy verification — mobile app, no CI/CD per CLAUDE.md.)
