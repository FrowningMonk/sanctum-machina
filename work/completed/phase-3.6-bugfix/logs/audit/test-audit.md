# Test Audit — phase-3.6-bugfix

**Auditor:** main agent (Task 9)
**Date:** 2026-05-06
**Inputs:** user-spec.md, tech-spec.md (§ Testing Strategy + § Acceptance Criteria), three test files under audit, decisions.md (Tasks 1–6), code-research.md.

## Summary

All 12 user-spec acceptance criteria (AC-1.1 … AC-3.3) plus all 8 technical AC items from tech-spec § Acceptance Criteria are covered by either a unit test, a code-only static check (whitelist + grep), or an explicit USER-ONLY Honor 200 smoke as documented in user-spec § Как проверить. Tech-spec § Testing Strategy plan is faithfully implemented — 13 + 12 + ~10 phase-3.6 tests across the three files, with two minor positive deviations (bonus regression guards added in review rounds) and one minor methodological deviation noted below. **Verdict: APPROVE** — 0 blocker / 0 major / 1 minor / 4 info. No GAPs to close before Task 10 Pre-deploy QA.

## Build status

Local `./gradlew :core-runtime:testDebugUnitTest :app:testDebugUnitTest` in this audit environment failed with `IllegalArgumentException: 25.0.3` — the bundled Kotlin compiler's `JavaVersion.parse` does not recognise JDK 25.0.3. This is the same non-code, host-toolchain issue documented in Task 8 (Security Audit) and does not reflect a test regression. Audit relies on the per-task recorded green builds in `decisions.md`:

- Task 1 (`ErrorLogTest` — 13 tests, 9 baseline + 4 new): green.
- Task 2 (`DefaultModelRegistryTest` — 12 tests, 5 baseline + 7 new): green.
- Task 3 (`ChatViewModelTest` — full module 302 tests, 0 failures): green.
- Task 6 (full `:app` module after `engineReady` addition): green.

Recommendation for Task 10: re-run on the maintainer's primary host where the JDK bundle resolves correctly.

## AC coverage table

User-spec ACs:

| AC | Test(s) — `Class::method` | Status |
|----|---------------------------|--------|
| AC-1.1 (chat switch → Conversation reset) | `ChatViewModelTest::bootstrapPersistent_chatSwitchReset_waitsForReady` | covered |
| AC-1.2 (commit draft → Conversation reset) | `ChatViewModelTest::bootstrapPersistent_emitsDraftCommitReset_whenLastIsUnpairedUser` + `bootstrapPersistent_draftCommit_resetsBeforeAutoResumeRunInference` (ordering guard) | covered |
| AC-1.3a (temperature/topK/topP via Light) | `ChatViewModelTest::applyLightOverrides_callsResetConversation_withLightOverrideReason` + `classifyApplyLevel_returnsLight_forTemperature` | covered |
| AC-1.3b (max_tokens via Heavy) | `ChatViewModelTest::classifyApplyLevel_returnsHeavy_forMaxTokens` + `applyMaxTokens_followsHeavyDialogSequence` | covered |
| AC-1.4 (non-Ready skip = warning) | `DefaultModelRegistryTest::resetConversation_skipsAndLogsWarning_whenEngineIdle` + `..._whenEngineInitializing` + `..._whenEngineFailed` | covered |
| AC-1.5 (every reset logged with reason tag) | `DefaultModelRegistryTest::resetConversation_dispatchesAndLogsInfo_whenReady` + `ChatViewModelTest::applySystemPromptAndReset_resetsWithPrompt` (SYSTEM_PROMPT) + `resetConversation_clearsAll` (USER) + `ErrorLogTest::whitelistCount_is15` + `inferenceResetComponent_acceptedByAllLevels` + `unknownComponent_stillRejected_negative` | covered |
| AC-2.1 (Settings active in draft after Ready) | `ChatViewModelTest::engineReady_combinatorics` (Ready + no-warmup → true cell) | covered |
| AC-2.2 (changes before first message apply) | implicit per tech-spec § User-Spec Deviations (DataStore → bootstrap re-merge → first-inference read; chain pre-existed in code, no new mechanism) | covered-implicit |
| AC-2.3 (Settings disabled when non-Ready) | `ChatViewModelTest::engineReady_combinatorics` (Idle / Initializing / Failed / Ready+warmup → false cells, plus 6th `entry == null` cell) | covered |
| AC-3.1 (`enableEdgeToEdge()` in both activities) | code-only — no unit test (Robolectric Activity `onCreate` not in test infra). Verified statically by Task 4 code-reviewer + Task 7 code-audit grep. **USER-ONLY-static** in Task 10 Pre-deploy QA: `Grep "enableEdgeToEdge" app/src/main/kotlin/app/sanctum/machina/MainActivity.kt CrashReportActivity.kt` → both must hit before `setContent`. | covered-static |
| AC-3.2 (no IME gap on Honor 200) | **USER-ONLY** per user-spec § Как проверить → Пользователь проверяет → Bug 3 | user-only |
| AC-3.3 (no regression on 6 screens) | **USER-ONLY** per user-spec § Как проверить → Пользователь проверяет → Bug 3 | user-only |

Tech-spec § Acceptance Criteria (technical):

| AC item | Verification | Status |
|---------|-------------|--------|
| `:app:testDebugUnitTest :core-runtime:testDebugUnitTest` green | Tasks 1/2/3/6 recorded green; full `:app` 302 tests, 0 failures (Task 3) | covered (per-task) |
| `:app:lintDebug` no new warnings | Tasks 3/4/6 recorded green | covered (per-task) |
| `:app:assembleDebug` builds | Tasks 3/4/6 recorded green | covered (per-task) |
| `:core-runtime` UI-free | Task 7 grep `androidx.(compose|activity)` → 0 hits | covered |
| `ErrorLog.ALLOWED_COMPONENTS` updated | `ErrorLogTest::whitelistCount_is15` + Task 5 patterns.md | covered |
| `patterns.md` § D15 Light bullet rewritten | Task 5 (commit `c372d5f`) | covered |
| All callers pass explicit `reason` | Task 7 grep `resetConversation\(` in `ChatViewModel.kt` → 4 sites, all `reason = ResetReason.<NAME>`; interface signature has no default (Task 2 grep `reason: ResetReason\s*=` → 0 hits) | covered |
| `LIGHT_FIELD_LABELS` no `MAX_TOKENS` | Task 3 grep | covered |

## Reverse mapping (test → AC)

Phase-3.6-introduced or modified tests, with the AC each is the primary witness for. Tests not listed below either pre-date Phase 3.6 or are unmodified pre-existing regression guards (counted but not analysed).

`ErrorLogTest.kt`:

| Test | Primary AC |
|------|-----------|
| `whitelistCount_is15` | AC-1.5 (whitelist invariant for `inference-reset` tag) |
| `inferenceResetComponent_acceptedByAllLevels` | AC-1.5 (e/i/w accept the new component) |
| `unknownComponent_stillRejected_negative` | AC-1.5 (closed-whitelist invariant preserved across all three levels) |
| `iAndW_lengthBoundingMatchesE` | AC-1.4 + AC-1.5 (verifies new `i`/`w` levels share the single `write(level, ...)` pipeline — Decision 5) |
| `knownComponents_accepted` (extended to include `inference-reset`) | AC-1.5 (whitelist coverage of all 15 components) |

`DefaultModelRegistryTest.kt`:

| Test | Primary AC |
|------|-----------|
| `resetConversation_skipsAndLogsWarning_whenEngineIdle` | AC-1.4 (canonical skip-arm format pin) |
| `resetConversation_skipsAndLogsWarning_whenEngineInitializing` | AC-1.4 |
| `resetConversation_skipsAndLogsWarning_whenEngineFailed` | AC-1.4 + Decision 5 (sanitize + 500-char bound for cause text — control-whitespace and 600-char input asserted) |
| `resetConversation_dispatchesAndLogsInfo_whenReady` | AC-1.5 (success path emits `INFO [inference-reset] reason=...`) |
| `resetConversation_skipsSilently_whenModelMissing` | Decision 1 (unknown-name silence — defends against log spam during chat-switch races) |
| `resetConversation_isSerializedByLifecycleMutex` | regression guard for tech-spec § Shared resources `lifecycleMutex` invariant; no direct AC, but enforces single-engine serialisation |
| `resetConversation_propagatesHelperException` | regression guard — exception propagation + mutex release on throw; no direct AC, supplements Decision 1 |

`ChatViewModelTest.kt` (Phase 3.6 tests only):

| Test | Primary AC |
|------|-----------|
| `applyLightOverrides_callsResetConversation_withLightOverrideReason` | AC-1.3a (replaces `_updatesConfigValues_noCleanup` while preserving the cleanup/init no-op guard) |
| `bootstrapPersistent_chatSwitchReset_waitsForReady` | AC-1.1 (two-phase cold-start race guard: Initializing → empty, Ready → [CHAT_SWITCH]) |
| `bootstrapPersistent_emitsDraftCommitReset_whenLastIsUnpairedUser` | AC-1.2 (USER-tail heuristic → DRAFT_COMMIT) |
| `bootstrapPersistent_draftCommit_resetsBeforeAutoResumeRunInference` | AC-1.2 (ordering: reset must precede `runInference`; bonus from Round 2 race fix — security-auditor-1 mitigation) |
| `engineReady_combinatorics` | AC-2.1 + AC-2.3 (5 cells from tech-spec + 6th `entry == null` cell from test-reviewer-1 review) |
| `applySystemPromptAndReset_resetsWithPrompt` (extended) | AC-1.5 (asserts `lastResetReason == SYSTEM_PROMPT`) |
| `resetConversation_clearsAll` (extended) | AC-1.5 (asserts `lastResetReason == USER`) |
| `classifyApplyLevel_returnsHeavy_forMaxTokens` | AC-1.3b (Heavy classification of the reclassified field) |
| `classifyApplyLevel_returnsHeavy_forAccelerator` | AC-1.3b (regression guard — accelerator still HEAVY) |
| `classifyApplyLevel_returnsLight_forTemperature` | AC-1.3a (Light classification + drives `saveAndApplySettings` to confirm dispatch reaches `applyLightOverrides`) |
| `applyMaxTokens_followsHeavyDialogSequence` | AC-1.3b (executes `applyHeavySetting()` and pins ordered `["stopResponse","cleanup","initialize"]`) |

No Phase-3.6 test exists outside an AC mapping. No orphan regression guards introduced by this phase.

## Quality findings

### 1. Test pyramid sanity — PASS (no finding)

`Glob **/test/**/*IntegrationTest.kt` → no files. `Glob **/test/**/*E2ETest.kt` → no files. `Glob **/androidTest/**/*.kt` → 3 files (`SanctumDatabaseTest`, `ChatDaoTest`, `MessageDaoTest`) all pre-Phase-3.6 — no new androidTest files added by Tasks 1–6. Strategy "size S → unit-only" preserved.

### 2. Meaningful assertions check — PASS with one INFO

| Test | Required assertion | Verdict |
|------|-------------------|---------|
| `applyMaxTokens_followsHeavyDialogSequence` (`ChatViewModelTest.kt:1322`) | ordered `sharedCalls == ["stopResponse","cleanup","initialize"]` | PASS — `assertEquals(listOf("stopResponse", "cleanup", "initialize"), sequence)` at line 1348. Also asserts post-state `model.configValues[MAX_TOKENS] == 2048` and `vm.reinitInProgress.value == false` after completion. |
| `bootstrapPersistent_chatSwitchReset_waitsForReady` (`ChatViewModelTest.kt:1160`) | two-phase: (a) Initializing → `resetReasons.isEmpty()`, (b) Ready → `resetReasons == [CHAT_SWITCH]` | PASS — both phases present at lines 1178-1182 and 1187-1191. |
| `iAndW_lengthBoundingMatchesE` (`ErrorLogTest.kt:121`) | 600-char description + 300-char cause for both `i` and `w`, asserts truncation to 500/200 | PASS — lines 138-141 (INFO) and 145-148 (WARN) assert exact lengths AND that the truncated content is all-`a` / all-`x` (defends against silent corruption). |

**INFO 1** — `applyMaxTokens_followsHeavyDialogSequence` triggers `vm.applyHeavySetting()` directly rather than `vm.saveAndApplySettings(target)` per tech-spec § Testing Strategy hint. The composed coverage still holds: `classifyApplyLevel_returnsHeavy_forMaxTokens` (line 1255) proves `saveAndApplySettings` will route a `MAX_TOKENS` delta to the Heavy dispatcher, and the sequencing test proves the Heavy dispatcher's ordered cleanup+init. The simpler `["stopResponse","cleanup","initialize"]` assertion (vs the more elaborate `["heavyDialogShown","userConfirmed","reinitDialogShown","cleanup","initialize"]` form mentioned in Task 9 description) intentionally mirrors the existing `applyHeavySetting_sequencing_stopCleanupInitialize` baseline at line 1354 — keeping the two tests structurally identical so a regression that breaks the sequence fails identically across both. The dialog/`reinitInProgress` UI-state assertions are not in scope here; tests like `applyHeavySetting_initCrash_failedState` (line 1382) cover the broader dialog path. No code change recommended.

### 3. No test-only behaviour leaks in production — PASS (no finding)

- `Grep @VisibleForTesting core-runtime/src/main` → 2 hits, both pre-Phase-3.6: `AllowlistLoader.kt:47` (Phase 3.5) and `DefaultModelRegistry.kt:116` (`_models` exposed to same-module tests; pre-existed before Phase 3.6 — verified by the surrounding `// internal to let same-module unit tests drive the derived StateFlow deterministically` comment). The annotation does not add an alternate code path; same-module reads still go through the StateFlow contract.
- `Grep @VisibleForTesting app/src/main` → 5 hits, all pre-Phase-3.6 (`DefaultChatRepository`, `WarmupCoordinator`, `StartupHousekeeper.deleter`, `DrawerViewModel`).
- `Grep "BuildConfig.DEBUG|testMode|isTestMode" core-runtime/src/main` → 0 hits.
- `Grep "BuildConfig.DEBUG|testMode|isTestMode" app/src/main` → 1 hit at `DeviceInfoCollector.kt:244` (pre-Phase-3.6 — `isDebug() = BuildConfig.DEBUG` for diagnostic export, not a test path).
- `Grep "kotlin.allopen|allOpen|all-open" *.kts` → 0 hits. No `open` modifier added to previously `final` classes for moc-ability.

No new test-driven branching introduced by Phase 3.6.

### 4. Regression guards preserved — PASS (no finding)

`applyLightOverrides_callsResetConversation_withLightOverrideReason` (`ChatViewModelTest.kt:1124`) preserves the no-cleanup/no-init regression guard from the original `applyLightOverrides_updatesConfigValues_noCleanup` it replaces:

```
val cleanupCountBefore = fakeRegistry.cleanupCalls
val initCountBefore = fakeRegistry.initializeCalls
…
assertEquals(cleanupCountBefore, fakeRegistry.cleanupCalls)   // line 1147
assertEquals(initCountBefore, fakeRegistry.initializeCalls)   // line 1148
```

…AND adds the new behavioural assertions (`lastResetReason == LIGHT_OVERRIDE`, `lastResetSystemPrompt == null` per blank-default-prompt rule). Per tech-spec explicit instruction. The same delta-style guard is replicated in `classifyApplyLevel_returnsLight_forTemperature` (lines 1312-1317) and `applySystemPromptAndReset_resetsWithPrompt` (lines 1435-1436) and `resetConversation_clearsAll` (lines 1466-1467) — defends against future regressions on every Light-tier or Conversation-only path that might accidentally touch cleanup/initialize.

### 5. Recording-fake quality — PASS with one MINOR

`FakeModelRegistry` (`ChatViewModelTest.kt:2043`) records:

- `lastResetReason: ResetReason?` (line 2048) ✓
- `lastResetSystemPrompt: String?` (line 2047) ✓
- `resetReasons: MutableList<ResetReason>` (line 2049) ✓ — used by two-phase bootstrap tests
- `cleanupCalls: Int` (line 2050) ✓
- `initializeCalls: Int` (line 2051) ✓
- `sharedCalls: MutableList<String>` (constructor injected, shared with `FakeLlmHelper`) — records `"initialize"`, `"cleanup"`, `"resetConversation"`, `"runInference"`, `"stopResponse"` in chronological order ✓

Hand-rolled fakes throughout — no mockk / mockito usage in any of the three files (`Grep "mockk|mockito"` → 0 hits in audited files). Per project pattern.

**MINOR 1** — `sharedCalls` does NOT distinguish multiple `resetConversation` calls by reason inside the ordered string list (it always appends the literal `"resetConversation"`). Tests that need reason-sequence pinning use the parallel `resetReasons: List<ResetReason>` field instead, which is correctly populated. Acceptable separation of concerns; mention as design observation rather than defect. No code change recommended.

### 6. Tech-spec § Testing Strategy ↔ reality cross-check — PASS

Per `tech-spec.md:175-200`:

| Tech-spec name | Reality (file:line) | Notes |
|----------------|---------------------|-------|
| `resetConversation_skipsAndLogsWarning_whenEngineIdle` | `DefaultModelRegistryTest.kt:228` | exact match |
| `resetConversation_skipsAndLogsWarning_whenEngineInitializing` | `DefaultModelRegistryTest.kt:249` | exact match |
| `resetConversation_skipsAndLogsWarning_whenEngineFailed` | `DefaultModelRegistryTest.kt:266` | exact match — also exercises the shared-pipeline sanitize/length-bound (description capped at 500, no raw `\n` / `\t` / `\r`) |
| `resetConversation_dispatchesAndLogsInfo_whenReady` | `DefaultModelRegistryTest.kt:302` | exact match |
| `resetConversation_skipsSilently_whenModelMissing` | `DefaultModelRegistryTest.kt:327` | exact match |
| `resetConversation_isSerializedByLifecycleMutex` | `DefaultModelRegistryTest.kt:347` | name match; deviation in assertion: peak-concurrency `AtomicInteger` instead of `[1, 2]` literal — documented in `decisions.md` Task 2 (peak-concurrency strictly stronger; `incrementAndGet` would trivially pass `[1,2]` even without mutex) |
| `resetConversation_propagatesHelperException` | `DefaultModelRegistryTest.kt:393` | exact match |
| `applyLightOverrides_callsResetConversation_withLightOverrideReason` | `ChatViewModelTest.kt:1125` | exact match |
| `bootstrapPersistent_chatSwitchReset_waitsForReady` | `ChatViewModelTest.kt:1160` | exact match |
| `bootstrapPersistent_emitsDraftCommitReset_whenLastIsUnpairedUser` | `ChatViewModelTest.kt:1227` | exact match |
| `engineReady_combinatorics` | `ChatViewModelTest.kt:1615` | name match; expanded from 5 cells (tech-spec) to 6 cells (added `entry == null` per test-reviewer-1 round 1) |
| `applySystemPromptAndReset_passesSystemPromptReason` | `ChatViewModelTest.kt:1405` (`applySystemPromptAndReset_resetsWithPrompt`) | name deviation — kept the existing test name and extended in place per tech-spec instruction "extend existing"; reason-tag assertion present at line 1431 |
| `userTapReset_passesUserReason` | `ChatViewModelTest.kt:1440` (`resetConversation_clearsAll`) | name deviation — kept the existing test name and extended in place per tech-spec instruction "extend existing"; reason-tag assertion present at line 1465 |
| `classifyApplyLevel_returnsHeavy_forMaxTokens` | `ChatViewModelTest.kt:1255` | exact match |
| `classifyApplyLevel_returnsHeavy_forAccelerator` | `ChatViewModelTest.kt:1274` | exact match |
| `classifyApplyLevel_returnsLight_forTemperature` | `ChatViewModelTest.kt:1295` | exact match — also drives `saveAndApplySettings` end-to-end to pin Light dispatch (bonus) |
| `applyMaxTokens_followsHeavyDialogSequence` | `ChatViewModelTest.kt:1322` | name match; trigger uses `applyHeavySetting()` directly (see INFO 1) |
| `whitelistCount_is15` | `ErrorLogTest.kt:72` | exact match |
| `inferenceResetComponent_acceptedByAllLevels` | `ErrorLogTest.kt:78` | exact match |
| `unknownComponent_stillRejected_negative` | `ErrorLogTest.kt:92` | exact match — covers all three levels (`e`, `i`, `w`) |
| `iAndW_lengthBoundingMatchesE` | `ErrorLogTest.kt:121` | exact match |

Plus one positive deviation:

- **INFO 2** — `bootstrapPersistent_draftCommit_resetsBeforeAutoResumeRunInference` (`ChatViewModelTest.kt:1195`) — bonus regression guard added in Round 2 of Task 3 review, pinning `resetConversation < runInference` ordering in `sharedCalls`. Not in tech-spec § Testing Strategy. Defends against the race surfaced by security-auditor-1 (auto-resume firing against a still-dirty Conversation). Recommend acknowledging in tech-spec § Testing Strategy or carrying forward as decisions.md note (already done).

Total Phase-3.6 tests delta:

- `ErrorLogTest`: 9 baseline + 4 new (whitelistCount_is15, inferenceResetComponent_acceptedByAllLevels, unknownComponent_stillRejected_negative, iAndW_lengthBoundingMatchesE) = 13 ✓
- `DefaultModelRegistryTest`: 5 baseline + 7 new = 12 ✓
- `ChatViewModelTest`: 65 baseline + 11 new/extended (10 from tech-spec + 1 bonus race-ordering) = 73 (full module total — matches Task 6 verification: 302 tests across 25 suites, no failures)

### 7. Edge cases noted

- **AC-2.2 (changes before first message apply)**: implicit per tech-spec § User-Spec Deviations. No dedicated unit test — DataStore write → bootstrap re-merge → first-inference read chain pre-existed the phase. Captured as `covered-implicit` in the AC table; flagged here so reviewers tracking against tech-spec line 243 see the deliberate omission.
- **AC-3.1 (`enableEdgeToEdge()` in both activities)**: code-only. Robolectric Activity `onCreate` instrumentation is not configured in this project, so a unit test would require new test infrastructure that the size-S phase does not budget for. Static verification (Task 4 code-reviewer + Task 7 grep) plus Task 10 grep pre-deploy handle this AC.

## Recommendations

1. **No code/test changes required before Task 10.** All six task-9 audit checklist items pass; no GAPs to close.
2. **Forward to Task 10 (Pre-deploy QA):** AC-3.1 grep gate (`enableEdgeToEdge` present in both `MainActivity.onCreate` and `CrashReportActivity.onCreate` before `setContent`); AC-1.1 / AC-1.2 / AC-1.3a / AC-1.3b / AC-2.1 / AC-2.2 / AC-3.2 / AC-3.3 visual smoke on Honor 200 in one bundled sweep per memory rule `feedback_smoke_verification.md` (already noted in Tasks 3/4/6 decisions).
3. **Optional** — when next touching the tech-spec, capture the bonus `bootstrapPersistent_draftCommit_resetsBeforeAutoResumeRunInference` test and the 6th `entry == null` cell of `engineReady_combinatorics` in § Testing Strategy so future audits see them as planned-and-present rather than positive deviations.

## Verdict

**APPROVE** — 0 blocker / 0 major / 1 minor / 4 info. Phase 3.6 test suite is ready for Task 10 Pre-deploy QA.
