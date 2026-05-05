# Decisions Log: phase-3.6-bugfix

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Task 1: ErrorLog refactor — shared `write(level, ...)` helper + `i()` / `w()` + whitelist `"inference-reset"`

**Status:** Done
**Commit:** 34032ea
**Agent:** main agent
**Summary:** Extracted `e()`'s body into a private `suspend fun write(level, component, description, cause)` that owns whitelist enforcement, `sanitize()`, length-bounding, mutex, append, and rotation; `e/i/w` are now thin public wrappers. `Level` enum (private, in-file) drives the format prefix via `level.name`. `ALLOWED_COMPONENTS` grew 14→15 with `"inference-reset"` for Phase 3.6 reset diagnostics. Closed-whitelist invariant preserved for all three levels (`require()` runs before any I/O).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 2 minor informational nits (no fix required) → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: approve, Decision 5 drift vector structurally eliminated → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- test-reviewer: approve, all 4 prescribed tests substantive and mutation-resistant → [logs/working/task-1/test-reviewer-1.json](logs/working/task-1/test-reviewer-1.json)

**Verification:**
- `./gradlew :core-runtime:testDebugUnitTest --tests ErrorLogTest` → green (13 tests: 9 existing + 4 new).
- `./gradlew :core-runtime:testDebugUnitTest` (full module suite) → green (no regression in `DefaultModelRegistryTest` etc.).
- `./gradlew :core-runtime:lintDebug` → green; no new warnings in `ErrorLog.kt` (2 pre-existing warnings in `build.gradle.kts` and `Consts.kt`, unrelated).
- `./gradlew :core-runtime:assembleDebug` → AAR built.
- Module boundary: `grep -rE "androidx.compose|androidx.activity" core-runtime/src/main` → 0 hits.

---

## Task 2: `ResetReason` enum + `ModelRegistry.resetConversation` signature + `DefaultModelRegistry` logging

**Status:** Done
**Commit:** 8f46a38 (impl `b8b763b` + review-fix `8f46a38`)
**Agent:** main agent
**Summary:** Added `ResetReason` enum (six values) in `:core-runtime/.../core/registry/`, extended `ModelRegistry.resetConversation` with mandatory `reason: ResetReason` (no default — Decision 1), and replaced the silent non-Ready skip in `DefaultModelRegistry` with `errorLog.w("inference-reset", "skipped reason=<NAME> status=<STATUS>")`; success path now emits `errorLog.i("inference-reset", "reason=<NAME>")`. All description text routes through Task 1's shared `write(level, …)` pipeline (sanitize + 500-char bound) — no parallel formatter (Decision 5). Mutex discipline preserved on every path. `:app` build is intentionally red until Task 3 wires reasons through `ChatViewModel`.
**Deviations:** Test 6 (`resetConversation_isSerializedByLifecycleMutex`) replaces the literal `recorded == [1, 2]` hint from the TDD anchor with a peak-concurrency `AtomicInteger` check (`peak == 1`); `AtomicInteger.incrementAndGet` is itself atomic so the original assertion would pass even without the mutex — peak-concurrency directly pins serialisation. `Thread.sleep(50)` instead of `delay(50)` because `LlmModelHelper.resetConversation` is non-suspend.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 0 blocker / 0 major / 2 minor (KDoc nudges) / 3 info → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: approve, 0 blocker / 0 major / 0 minor, 2 info notes only → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: approve, 0 blocker / 0 major / 5 strengthening nits — three applied (canonical Idle-line format pin; Failed-arm asserts cause text survives; throw-arm asserts no WARN line) → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

**Verification:**
- `./gradlew :core-runtime:testDebugUnitTest --tests DefaultModelRegistryTest` → 12 tests pass (5 existing + 7 new).
- `./gradlew :core-runtime:testDebugUnitTest` (full module) → all suites green; no regressions in `ErrorLogTest`, `AllowlistLoaderTest`, `ModelRegistryActiveModelTest`, etc.
- `./gradlew :core-runtime:compileDebugKotlin` → UP-TO-DATE.
- Module boundary: `Get-ChildItem core-runtime/src/main -Recurse -Include *.kt | Select-String "androidx\.(compose|activity)"` → 0 hits.
- Enum completeness: `Select-String ResetReason.kt -Pattern "CHAT_SWITCH|DRAFT_COMMIT|LIGHT_OVERRIDE|SYSTEM_PROMPT|HEAVY|USER"` → 6 hits.
- No-default check: `Select-String ModelRegistry.kt -Pattern "reason: ResetReason\s*="` → 0 hits (Decision 1).

---

## Task 3: Wire `ResetReason` across `ChatViewModel` + reclassify `MAX_TOKENS` to Heavy

**Status:** Done
**Commit:** 2bb21c6 (impl `728665a` + race fix `2bb21c6`)
**Agent:** main agent
**Summary:** Wired `ResetReason` to all four `ChatViewModel` reset call sites (Persistent bootstrap, Light overrides, system-prompt reset, user-tap reset) and gated the bootstrap reset on first `Ready` via a new private `observeFirstReadyThenReset(reason)` mirroring `observeFirstReadyThenResume`. `lastByChat` heuristic distinguishes `DRAFT_COMMIT` from `CHAT_SWITCH` (Decision 2). Removed `ConfigKeys.MAX_TOKENS.label` from `LIGHT_FIELD_LABELS` and extended `classifyApplyLevel`'s HEAVY condition to `acceleratorChanged || maxTokensChanged` (Decision 4). Six other `FakeModelRegistry` test doubles (Sanctum/SettingsMigration/Warmup/Drawer/Home/ModelManager) updated for the post-Task-2 signature so the `:app` build is green again.
**Deviations:** None against tech-spec scope. `engineReady` StateFlow and `deriveTopAppBarState` deliberately untouched — Task 6 owns Bug 2.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 0 blocker / 0 major / 2 minor (consistency notes for Task 6 follow-up) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- security-auditor: approve_with_concerns, 1 major (race between `observeFirstReadyThenReset` and `observeFirstReadyThenResume` siblings — `withContext(Dispatchers.Default)` hop releases Main, letting `runInference` fire against still-dirty Conversation) → [logs/working/task-3/security-auditor-1.json](logs/working/task-3/security-auditor-1.json)
- test-reviewer: approve, 0 blocker / 0 major / 5 minor (cosmetic) → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

*Round 2 (after fixes):*
- security-auditor: approve, race structurally closed by chaining reset → resume in a single `viewModelScope.launch`; Round-1 minor (silent perma-Failed branch) deferred to Task 6 alongside `engineReady` → [logs/working/task-3/security-auditor-2.json](logs/working/task-3/security-auditor-2.json)

Added regression test `bootstrapPersistent_draftCommit_resetsBeforeAutoResumeRunInference` pinning `resetConversation < runInference` order in `sharedCalls` so a future refactor that re-splits the chained launch fails loudly.

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests ChatViewModelTest` → 72 tests pass (65 baseline + 7 new/extended).
- `./gradlew :app:testDebugUnitTest` (full module) → 302 tests across 25 suites, 0 failures / 0 errors.
- `./gradlew :core-runtime:testDebugUnitTest` → green; no regressions in `DefaultModelRegistryTest` after the test-double signature update propagated through the chain.
- `./gradlew :app:lintDebug` → green; only pre-existing `Bitmap.scale` UseKtx warning at `ChatViewModel.kt:1458` (unrelated to this task).
- `./gradlew :app:assembleDebug` → debug APK built.
- Smoke grep `resetConversation\(` in `ChatViewModel.kt` → 4 production call sites (lines 334, 463, 480, 910), each with explicit `reason = ResetReason.<NAME>`; no implicit defaults.
- Smoke grep `MAX_TOKENS` in `ChatViewModel.kt` → only in `classifyApplyLevel` HEAVY locals (lines 1383-1384) and a justification comment in the `LIGHT_FIELD_LABELS` block (line 1597); not in the Light set.
- User-on-device verification (Honor 200 a/b/c per task spec) deferred to Task 10 pre-deploy QA per memory rule `feedback_smoke_verification.md` — bundled with Task 4/6 device smoke for one Honor 200 sweep instead of three.

---

## Task 4: `enableEdgeToEdge()` in `MainActivity` and `CrashReportActivity`

**Status:** Done
**Commit:** c713498
**Agent:** main agent
**Summary:** Added `androidx.activity.enableEdgeToEdge` import and call to both hosted Activities per Decision 7. In `MainActivity.onCreate` it sits immediately after `super.onCreate(...)` and before the POST_NOTIFICATIONS block / `setContent`. In `CrashReportActivity.onCreate` it sits AFTER the existing `window.setFlags(FLAG_SECURE, FLAG_SECURE)` and BEFORE `logExportManager = ...` / `setContent`, so FLAG_SECURE remains set first and is unaffected by the AndroidX call (which only touches `SystemBarStyle` + `setDecorFitsSystemWindows(false)`). No manual `setDecorFitsSystemWindows` / `statusBarColor` / `navigationBarColor` introduced; no `Build.VERSION.SDK_INT` guard (minSdk=31, API 21+).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 0 findings — all Decision 7 constraints verified (call ordering before `setContent`, no forbidden APIs, no version-guard, FLAG_SECURE ordered before `enableEdgeToEdge`, scope limited to two files) → [logs/working/task-4/code-reviewer-1.json](logs/working/task-4/code-reviewer-1.json)
- security-auditor: approve, 0 blocker / 0 major / 0 minor, 1 info note (suggest a future instrumentation assertion that `FLAG_SECURE` survives on `CrashReportActivity` — deferred to Audit Wave Task 8, not a Task 4 blocker) → [logs/working/task-4/security-auditor-1.json](logs/working/task-4/security-auditor-1.json)

**Verification:**
- `./gradlew :app:lintDebug` → BUILD SUCCESSFUL; only pre-existing warnings (`-Xcontext-receivers` deprecation, `DeviceInfoCollector.kt:213` annotation-target migration) — unrelated to this task.
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL; debug APK packaged. Confirms `androidx.activity.enableEdgeToEdge` resolves from `activity-compose 1.10.1` already on classpath.
- Smoke grep `setDecorFitsSystemWindows|statusBarColor|navigationBarColor|fitsSystemWindows` in the two modified files → 0 hits.
- AC-3.1 verified statically by code-reviewer: call present in both `onCreate` before `setContent`.
- AC-3.2 / AC-3.3 (Honor 200 visual smoke on 6 screens + IME-gap check) and FLAG_SECURE on-device check deferred to Task 10 pre-deploy QA per memory rule `feedback_smoke_verification.md` — bundled with Task 3 + Task 6 into a single Honor 200 sweep once the full UI chain (Bug 1, Bug 2, Bug 3) is in place.

---

## Task 5: Update `patterns.md` § D15 Light bullet and § ErrorLog component strings

**Status:** Done
**Commit:** c372d5f
**Agent:** main agent
**Summary:** Doc-only sync of `.claude/skills/project-knowledge/references/patterns.md` to Phase 3.6 reality. Light bullet of § D15 rewritten — field list trimmed to `temperature`/`topK`/`topP`, the runtime mechanism named (`registry.resetConversation(reason = LIGHT_OVERRIDE)` recreates `Conversation` with new `SamplerConfig`), UI-history-preserved + engine-not-torn-down clauses added, `!isGenerating`-gate noted, `maxTokens` redirected to Heavy tier per Decision 4. § ErrorLog component strings: count 14→15, `"inference-reset"` inserted between `"inference"` and `"inference-cleanup"`, signature updated to `e/i/w`, Phase-origin breakdown extended with a forward pointer, and a sibling rationale paragraph added covering caller (`DefaultModelRegistry.resetConversation`), all six `ResetReason` values, level convention (`i` = success, `w` = non-Ready skip), and SAF-export operational role.
**Deviations:** None. Touched `e/i/w` signature inside § ErrorLog component strings (Task 1 introduced i/w but did not update patterns.md); within section scope per AC.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 0 blocker / 0 major / 0 minor / 2 info → [logs/working/task-5/code-reviewer-1.json](logs/working/task-5/code-reviewer-1.json)
- documentation-reviewer: approved, 2 optional minor suggestions (light cross-reference overlap between L18 and L20; rationale closing sentence slightly longer than Phase-origin density) — not blocking → [logs/working/task-5/documentation-reviewer-1.json](logs/working/task-5/documentation-reviewer-1.json)

**Verification:**
- `grep -n "applies from next send" .claude/.../patterns.md` → 0 hits.
- `grep -n "inference-reset" .claude/.../patterns.md` → 3 hits (whitelist, Phase-origin breakdown extension, rationale paragraph).
- `grep -n "15 values are allowed" .claude/.../patterns.md` → 1 hit; `"14 values are allowed"` → 0 hits.
- `grep -n "LIGHT_OVERRIDE" .claude/.../patterns.md` → 2 hits (Light bullet + reset-reason enumeration).
- Diff scope (read-verified, since `.claude/` is gitignored): edits confined to lines 16, 18, 20 (§ ErrorLog component strings) and line 64 (§ D15 Light bullet). Semi-light/Heavy bullets and § D15 closing paragraph at line 68 untouched.

---

## Task 6: `engineReady` StateFlow + Settings gating switch in `ChatScreen` (+ stale comment cleanup)

**Status:** Done
**Commit:** 2d85ca1 (impl `d7685e9` + review-fix `2d85ca1`)
**Agent:** main agent
**Summary:** Added public `val engineReady: StateFlow<Boolean>` to `ChatViewModel`, derived from the same three source flows that feed `topAppBarState` (`registry.models`, `_chatModelId`, `warmupCoordinator.isWarmupInProgress`); emits `true` iff the entry for the chat-pinned model is `ModelInitStatus.Ready` AND `!warmupInFlight`, `false` otherwise (including `modelId == null` and missing-entry cases). `ChatScreen` collects it once in `ChatScreenBody`, threads it as a `Boolean` parameter into `ReadyContent`, and replaces the existing `engineUsable = topAppBarState is TopAppBarState.Ready` derivation — Settings gate `enabled = engineReady && !isGenerating && !reinitInProgress`, Reset gate `enabled = engineReady && !isGenerating`. `deriveTopAppBarState` is untouched, so the Draft model picker dropdown continues to ride on `TopAppBarState.Draft`. Stale 5-line comment near `ChatScreen.kt` `.imePadding()` (about `WindowCompat.setDecorFitsSystemWindows` "already set by MainActivity") removed — Task 4's `enableEdgeToEdge()` makes the contract implicit.
**Deviations:** Sharing strategy is `SharingStarted.Eagerly` instead of the task hint `SharingStarted.WhileSubscribed(5_000)`. Reason: `engineReady` is a peer of `topAppBarState`, which uses `Eagerly` for the same source flows and the same TopAppBar surface. `WhileSubscribed` would let `.value` lag behind the registry during the 5-second grace window after the last collector unsubscribes — exactly the failure mode of Bug 2 if a sheet open/close cycle straddled the gap. Inline comment in `ChatViewModel.kt` documents the choice. The deviation is noted here so reviewers tracking against tech-spec Decision 6 (silent on the start mode) see the rationale.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 0 blocker / 0 major / 0 minor / 7 info (sharing-strategy rationale, `activeModelId`-fallback omission as deliberate, single-collection placement, race-avoidance ordering in test, `:core-runtime` UI-free invariant intact) → [logs/working/task-6/code-reviewer-1.json](logs/working/task-6/code-reviewer-1.json)
- security-auditor: approve, 0 findings across all severities. Defense-in-depth confirmed — primary authorization is `ChatViewModel.currentReadyModel()` at every apply-* / reset call site; `engineReady` is the UI gate only. No new attack surface, no Honor-lock, no `:core-runtime` boundary breach → [logs/working/task-6/security-auditor-1.json](logs/working/task-6/security-auditor-1.json)
- test-reviewer: approve, 3 minor (single-@Test layout vs split, same-VM threading vs fresh-VM-per-cell, `entry==null` cell not explicitly asserted) → [logs/working/task-6/test-reviewer-1.json](logs/working/task-6/test-reviewer-1.json)

Applied the third minor by adding a 6th assertion to `engineReady_combinatorics`: drop the registry to empty (`fakeRegistry.publishEntries()`) after Ready+no-warmup, pin `engineReady=false`. Closes the regression vector where a missing-entry default of `true` (e.g. `?: true`) would slip past the original 5-cell layout. The first two minors are stylistic and explicitly permitted by the task spec ("параметризованный (или 5 ассертов в одном @Test с reset state)"); kept the single-@Test layout for parity with the existing `topAppBarState_*` tests.

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "*ChatViewModelTest.engineReady_combinatorics*"` → green (6 assertions, all cells pass).
- `./gradlew :app:testDebugUnitTest` (full module) → green; no regressions in `topAppBarState_*` (warmup-gate path), `applyLightOverrides_*`, `bootstrapPersistent_*`.
- `./gradlew :app:lintDebug` → green; only pre-existing `Bitmap.createScaledBitmap` UseKtx warning at `ChatViewModel.kt:1492` (carried over from Task 3, line shifted by the new `engineReady` block — unrelated).
- `./gradlew :app:assembleDebug` → debug APK built (sanity check after the new public StateFlow declaration + Compose-side parameter threading).
- Module boundary: `Grep "androidx\.(compose|activity)"` over `core-runtime/src/main` → 0 hits. Engine-ready flag stays in `:app/ui/chat/`.
- Stale-comment removal verified: `Grep "setDecorFitsSystemWindows" app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt` → 0 hits.
- User-on-device verification (Honor 200 AC-2.1 / AC-2.3 sweep) deferred to Task 10 pre-deploy QA per memory rule `feedback_smoke_verification.md` — bundled with Task 3 + Task 4 device smoke into a single Honor 200 pass once the full UI chain (Bug 1, Bug 2, Bug 3) is in place. Same approach used in Tasks 3 and 4.

---

## Task 7: Code Audit

**Status:** Done
**Commit:** _this commit_
**Agent:** main agent
**Summary:** Cross-component code audit of Tasks 1–6. All 8 acceptance-checklist items pass; report at [logs/audit/code-audit.md](logs/audit/code-audit.md). Overall verdict **APPROVE** — 0 BLOCKER / 0 MAJOR / 0 MINOR / 4 NIT (style consistency on `is` vs `===` for `ModelInitStatus.Ready`, optional snackbar in `applyLightOverrides`, comment-vs-decision-doc duplication on `MAX_TOKENS`, `formatStatus`-then-sanitize ordering noted for security audit). No deviations from tech-spec discovered. `:core-runtime` UI-free invariant, mutex discipline on every `resetConversation` path, and the no-Honor-specific-code rule are all preserved.
**Deviations:** None.

**Reviews:**

Audit task — auditor IS the review (no secondary reviewers per task spec).

**Verification:**
- `grep -rE "androidx.compose|androidx.activity" core-runtime/src/main` → 0 hits.
- `git diff main...HEAD -- '*.kt' | grep -iE "Honor|EMUI|MANUFACTURER|BRAND"` → 0 hits in phase diff (pre-existing `Build.MANUFACTURER` in `DeviceInfoCollector.kt:246` and `Honor 200` *comment* in `MediaUtils.kt:120` were not modified by Tasks 1–6).
- `grep -n "setDecorFitsSystemWindows\|MainActivity" ChatScreen.kt` → 0 hits (stale comment removed; new comment block describes `consumeWindowInsets`/`imePadding` ordering).
- 4 production `registry.resetConversation` call sites in `ChatViewModel.kt` (368, 497, 514, 944) — all pass explicit `reason = ResetReason.<NAME>`; interface signature has no default value.
- Audit report `work/phase-3.6-bugfix/logs/audit/code-audit.md` exists with Summary + Checklist results + Findings + Overall verdict.
