---
created: 2026-05-05
status: draft
branch: phase/3.6-bugfix
size: S
---

# Tech Spec: phase-3.6-bugfix

Three Honor 200 testing bugs: KV-cache leakage across chats, settings unavailable in new persistent chat before first message, IME gap under input bar. No new features — pure bugfix scoped to LiteRT-LM 0.10.0 lifecycle, top-app-bar gating, and edge-to-edge.

## Solution

The three bugs share two underlying causes:

1. **Conversation lifecycle is implicit and underused.** `LiteRT-LM`'s `Conversation` object holds the KV-cache and bakes `SamplerConfig` at creation time (`LlmChatModelHelper.kt:124-142`). The codebase only resets it on explicit ↻ tap and on the SYSTEM_PROMPT tier; chat switches, draft commits, and Light-tier slider changes don't trigger a reset, so the engine carries forward stale KV and ignores user-changed temperature/topK/topP. Fix: every chat boundary and every Light-tier change must call `registry.resetConversation(reason)`. Diagnostic logging tagged with the reason makes future regressions debuggable.

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

### Decision 1: `ResetReason` lives in `:core-runtime/.../core/registry/`
**Decision:** Place the enum next to `ModelRegistry.kt` interface, not in `:app`.
**Rationale:** `ModelRegistry.resetConversation(reason: ResetReason)` is the cross-module API surface. Keeping the type in `:core-runtime` avoids passing `String` (loose) or `Any` (gross) and keeps `:core-runtime` self-contained. The tag values themselves (`LIGHT_OVERRIDE` etc.) are app-layer concepts but they're opaque to the runtime — only logged as `name`.
**Alternatives considered:** Place in `:app/ui/chat/` — rejected because `ModelRegistry` would lose type safety. Pass `String` — rejected because typo-prone and unenforced.
**User-spec link:** Supports AC-1.5 (logged with reason tag).

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

### Decision 5: Use existing `ErrorLog.i` / `ErrorLog.w` and add component `"inference-reset"` to `ALLOWED_COMPONENTS`
**Decision:** Add `"inference-reset"` to `ErrorLog.ALLOWED_COMPONENTS`. Use existing `errorLog.i(component, message)` for success-path reset logs (one line per reset, with reason + status), `errorLog.w(component, message)` for the non-Ready skip path (AC-1.4).
**Rationale:** Matches the closed-whitelist convention from `patterns.md` (14 existing values, every addition is a documented decision). Reset events are operational, not incidents, so `i` level is appropriate for the success path; `w` for the skip-when-not-Ready signals a misuse pattern that we want surfaced. `ErrorLog` already handles file rotation, length bounding, and SAF export integration — no new logging infrastructure.
**Alternatives considered:** Use Android's `Log.d/Log.i` only — rejected because those entries are not captured in the diagnostic export (AC-1.5 requires the user to be able to see the tag in exported logs). Add `i` and `w` levels to `ErrorLog` if missing — verify during implementation; if `ErrorLog.kt` only has `e`, add `i` and `w` as part of Task 1 with a one-paragraph rationale in the file.
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
**Decision:** Phase 3.6 includes a documentation-writing task that rewrites the Light bullet of `.claude/skills/project-knowledge/references/patterns.md` to reflect the Conversation-recreation reality and the `MAX_TOKENS` migration. New text drafted in code-research.md § 8.
**Rationale:** The Phase 3.6 implementation invalidates the current rule. Leaving the wrong rule in patterns.md is a future trap — agents and humans will follow it and re-introduce the bug. The doc fix is small (one paragraph) and naturally co-located with the code fix.
**Alternatives considered:** Defer to a separate doc phase — rejected because it leaves a broken rule in PK during the gap. File an issue — there's no issue tracker; PK is the system of record.
**User-spec link:** Supports user-spec § Технические решения (explicit decision to update PK).

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
- `resetConversation_skipsAndLogsWarning_whenEngineNotReady` — seed entry with `ModelInitStatus.Idle`, call `resetConversation(name, prompt, reason = CHAT_SWITCH)`. Assert: `helper.resetConversation` NOT called; `errorLog.w("inference-reset", containing "skipped" and "CHAT_SWITCH")` was called once.
- `resetConversation_dispatchesAndLogsInfo_whenReady` — seed Ready entry. Call with `reason = LIGHT_OVERRIDE`. Assert: helper called once with the merged `model.configValues`; `errorLog.i("inference-reset", containing "LIGHT_OVERRIDE")` was called.
- `resetConversation_skipsSilently_whenModelMissing` — call with unknown name. Assert: no helper call, no log.
- `resetConversation_isSerializedByLifecycleMutex` — launch two parallel coroutines calling `resetConversation`; assert second waits for first (use the existing pattern with a recording lock or sequence numbers).

**`ChatViewModelTest.kt` (extend existing, add new):**
- `applyLightOverrides_callsResetConversation_withLightOverrideReason` — flip the existing `applyLightOverrides_updatesConfigValues_noCleanup` test (line 1124). Now must assert `FakeModelRegistry.lastResetReason == ResetReason.LIGHT_OVERRIDE` and `lastResetSystemPrompt` matches the merged effective prompt.
- `bootstrapPersistent_emitsChatSwitchReset_onceEngineReady` — seed entry as Initializing, bootstrap a Persistent VM, flip entry to Ready; assert `FakeModelRegistry.resetReasons` contains `CHAT_SWITCH` exactly once. Use `ROLE_ASSISTANT` for the persisted lastByChat to get CHAT_SWITCH (not DRAFT_COMMIT).
- `bootstrapPersistent_emitsDraftCommitReset_whenLastIsUnpairedUser` — same setup but `lastByChat` returns USER role. Assert `resetReasons` contains `DRAFT_COMMIT`.
- `engineReady_isFalse_whenWarmupInFlight` — assert `engineReady.value == false` while warmup-in-flight flag is true even if entry is Ready.
- `engineReady_isFalse_whenStatusIdle` — initial state.
- `engineReady_isTrue_whenStatusReadyAndNoWarmup` — happy path.
- `classifyApplyLevel_returnsHeavy_forMaxTokens` — change `MAX_TOKENS` only. Assert `classifyApplyLevel` returns `HEAVY`.
- `classifyApplyLevel_returnsLight_forTemperature` — assert `LIGHT` for temperature only (max_tokens unchanged).
- `applyMaxTokens_dispatchesHeavyPath` — change max_tokens, call saveAndApplySettings; assert `cleanupCalls == 1` and `initializeCalls == 1` on `FakeModelRegistry` (Heavy path).

**`ErrorLogTest.kt` (extend):**
- `accept_inferenceResetComponent` — assert `ErrorLog.e("inference-reset", "x")` does NOT throw; current whitelist enforcement test pattern (existing tests reject unknown components — extend whitelist of allowed components).

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

## User-Spec Deviations

- **AC-1.3 (split into 1.3a + 1.3b):** user-spec originally stated a single AC for Light-tier behaviour of `temperature/topK/topP/max_tokens`. Tech-spec splits this — Light path covers temperature/topK/topP only; max_tokens moves to Heavy path because it's baked into `EngineConfig`, not `ConversationConfig`. Reason: code research uncovered the misclassification. Updated AC list in user-spec already reflects the split. → [APPROVED IN PRE-DRAFT — user confirmed Option A]

- **Added: `LIGHT_FIELD_LABELS` reclassification of `MAX_TOKENS`** — not originally in user-spec scope. Reason: emerged from code research; leaving the misclassification means the slider stays a no-op, and the phase title "fix three Honor 200 bugs" implicitly requires settings to work as advertised. → [APPROVED IN PRE-DRAFT]

- **Added: Update `patterns.md` § D15 Light bullet** — not originally in user-spec scope. Reason: the patterns.md text becomes wrong the moment Light-tier code changes; PK is the methodology source of truth and must stay correct. Documentation-writing task added to Wave 3. → [APPROVED IN PRE-DRAFT]

- **Added: `ErrorLog.ALLOWED_COMPONENTS` += `"inference-reset"`** — not in user-spec, follows from AC-1.4 / AC-1.5 (logging requirements). Reason: `patterns.md` § ErrorLog component strings is a closed whitelist enforced at runtime; a new logging surface requires whitelisting. → [APPROVED IMPLICITLY VIA AC-1.4/1.5]

## Acceptance Criteria

Технические критерии (дополняют пользовательские из user-spec):

- [ ] `./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest` — все тесты зелёные.
- [ ] `./gradlew :app:lintDebug` — нет новых warnings в затронутых файлах.
- [ ] `./gradlew :app:assembleDebug` — debug APK собран.
- [ ] Module boundary preserved: `:core-runtime` остаётся UI-free (grep `androidx.compose|androidx.activity` в `core-runtime/src/main` → 0 hits).
- [ ] `ErrorLog.ALLOWED_COMPONENTS` обновлён в `ErrorLog.kt` И в `patterns.md` § ErrorLog component strings.
- [ ] `patterns.md` § D15 Light bullet перезаписан — больше нет утверждения «applies from next send() without engine touch».
- [ ] Все callers `registry.resetConversation` передают явный `reason` — не `default`-параметр.
- [ ] `LIGHT_FIELD_LABELS` не содержит `MAX_TOKENS`.

## Implementation Tasks

### Wave 1 — :core-runtime foundation (independent)

#### Task 1: Add `ResetReason` enum and extend `ModelRegistry.resetConversation` signature
- **Description:** Create `ResetReason` enum with values CHAT_SWITCH, DRAFT_COMMIT, LIGHT_OVERRIDE, SYSTEM_PROMPT, HEAVY, USER. Extend `ModelRegistry.resetConversation` interface to accept `reason: ResetReason` (no default). Update `DefaultModelRegistry.resetConversation` to log `errorLog.i("inference-reset", ...)` on success path and `errorLog.w("inference-reset", ...)` on the non-Ready skip path; remove silent skip on missing model only (keep silent for unknown name — defensive). Update existing internal callers within `:core-runtime` (none expected — verify with grep).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ModelRegistry.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ResetReason.kt` (new)
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`, `work/phase-3.6-bugfix/code-research.md`

#### Task 2: Whitelist `"inference-reset"` in `ErrorLog`
- **Description:** Add `"inference-reset"` to `ErrorLog.ALLOWED_COMPONENTS`. Verify `ErrorLog` exposes `i` and `w` level methods; if only `e` exists, add `i` and `w` with the same length-bounding behaviour (description ≤500, cause ≤200, control whitespace collapsed). Update `ErrorLogTest` accordingly.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/log/ErrorLogTest.kt`
- **Files to read:** `.claude/skills/project-knowledge/references/patterns.md` (whitelist section)

### Wave 2 — :app wiring (depends on Wave 1)

#### Task 3: Wire `resetConversation` reasons into `ChatViewModel.bootstrapChatModelId` and `applyLightOverrides`
- **Description:** Persistent branch of `bootstrapChatModelId` issues `registry.resetConversation(name, systemPrompt = effective, reason)` once engine reaches Ready (mirror `observeFirstReadyThenResume` pattern). Reason chosen by `lastByChat` heuristic: USER tail → DRAFT_COMMIT, else CHAT_SWITCH. `applyLightOverrides` calls `registry.resetConversation(reason = LIGHT_OVERRIDE)` after the existing `model.configValues = merged` line; UI history is NOT cleared. `applySystemPromptAndReset` keeps current behaviour but passes `reason = SYSTEM_PROMPT`. The existing user-tap reset (↻ button) passes `reason = USER`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** На Honor 200: создать persistent чат A с длинной перепиской → переключиться в чат B через drawer → задать вопрос «о чём мы говорили выше?» → ответ должен относиться только к чату B, не к A.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/ResetReason.kt`, `work/phase-3.6-bugfix/code-research.md` (§ 2 patterns + § 7 conflicts)

#### Task 4: Reclassify `MAX_TOKENS` to Heavy tier
- **Description:** Remove `ConfigKeys.MAX_TOKENS.label` from `LIGHT_FIELD_LABELS`. Update `classifyApplyLevel`: HEAVY now fires on `acceleratorChanged || maxTokensChanged`. Tests `classifyApplyLevel_returnsHeavy_forMaxTokens` and `applyMaxTokens_dispatchesHeavyPath` cover the change.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/ConfigKeys.kt` (verify label constants), `work/phase-3.6-bugfix/code-research.md` (§ 4)

#### Task 5: Add `engineReady` StateFlow and rewire Settings gating
- **Description:** Expose `val engineReady: StateFlow<Boolean>` in `ChatViewModel` derived from `_uiState` (`true` iff matching entry's `initStatus is Ready` and `!warmupInFlight`). In `ChatScreen.kt`, replace `topAppBarState is TopAppBarState.Ready` (the boolean source for `engineUsable`/`settingsEnabled`) with `engineReady` collected as state. `deriveTopAppBarState` keeps returning `TopAppBarState.Draft` for Draft (model picker dropdown unchanged).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** На Honor 200: создать новый persistent чат → дождаться прогрева модели (статус Ready) → НЕ отправляя сообщение, тапнуть по иконке Settings — sheet открывается. До прогрева — кнопка серая.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`
- **Files to read:** `work/phase-3.6-bugfix/code-research.md` (§ 1 Bug 2 nuance)

### Wave 3 — edge-to-edge + docs (independent of Wave 2)

#### Task 6: Add `enableEdgeToEdge()` to MainActivity and CrashReportActivity
- **Description:** Call `enableEdgeToEdge()` (from `androidx.activity:activity-compose`) in `MainActivity.onCreate` before `setContent`. Same in `CrashReportActivity.onCreate` (after the `FLAG_SECURE` setFlags, before `setContent`). No other changes — no manual status-bar / nav-bar color, no `setDecorFitsSystemWindows`. Remove the now-stale comment in `ChatScreen.kt` line 327-330 about MainActivity dependency (becomes implicit).
- **Skill:** code-writing
- **Reviewers:** code-reviewer
- **Verify-user:** На Honor 200: открыть чат → тапнуть по input bar → клавиатура поднимается → нет зазора между ней и input bar. Затем пройти Home / Drawer / Model Manager / Diagnostics / About / Crash Report — нет регрессий: контент не уезжает под status/nav bar, текст читаем.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/MainActivity.kt`, `app/src/main/kotlin/app/sanctum/machina/crash/CrashReportActivity.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt` (comment cleanup only)
- **Files to read:** `work/phase-3.6-bugfix/code-research.md` (§ 5 inset table)

#### Task 7: Update `patterns.md` § D15 Light bullet and § ErrorLog component strings
- **Description:** Rewrite the Light bullet of `patterns.md` § Three-tier settings application classification (D15) per code-research.md § 8. Append `"inference-reset"` to the closed whitelist enumeration in § ErrorLog component strings (with one-paragraph rationale: phase 3.6 added this for KV-cache reset diagnostics).
- **Skill:** documentation-writing
- **Reviewers:** code-reviewer
- **Files to modify:** `.claude/skills/project-knowledge/references/patterns.md`
- **Files to read:** `work/phase-3.6-bugfix/code-research.md` (§ 8), `work/phase-3.6-bugfix/tech-spec.md` (Decision 4, Decision 5)

### Audit Wave

#### Task 8: Code Audit
- **Description:** Full-feature code quality audit. Read all modified files (Tasks 1–7). Review for cross-component issues: `ResetReason` placement, mutex discipline preserved, no Honor-specific code introduced (per memory `manifest_breadth_over_honor_lock`), `:core-runtime` UI-free invariant preserved. Write audit report to `work/phase-3.6-bugfix/logs/audit/code-audit.md`.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 9: Security Audit
- **Description:** Full-feature security audit. Read all modified files. OWASP Top 10 review. Specific points: log-injection through `ResetReason.name` (enum so safe — verify), edge-to-edge does not expose `FLAG_SECURE`-protected content, no new permission, `ErrorLog` length-bounding still in effect for new component. Write report to `work/phase-3.6-bugfix/logs/audit/security-audit.md`.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 10: Test Audit
- **Description:** Full-feature test audit. Read all new and modified test files. Verify coverage of every AC, meaningful assertions (not just smoke), no test-only behaviour leaking into production. Test pyramid: unit-only (no integration / E2E for size S) — that's the chosen strategy. Write report to `work/phase-3.6-bugfix/logs/audit/test-audit.md`.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 11: Pre-deploy QA
- **Description:** Acceptance testing. Run `./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest :app:lintDebug :app:assembleDebug`. Verify every AC from user-spec (AC-1.1 … AC-3.3) and from tech-spec (this file). For AC-3.2 / AC-3.3 / AC-1.1 / AC-1.2 / AC-1.3a / AC-1.3b / AC-2.1 / AC-2.2 — emit a Verify-user request to the user (Honor 200 smoke). Aggregate results in `work/phase-3.6-bugfix/logs/qa/pre-deploy-qa.md`.
- **Skill:** pre-deploy-qa
- **Reviewers:** none

(No Deploy / Post-deploy verification — mobile app, no CI/CD per CLAUDE.md.)
