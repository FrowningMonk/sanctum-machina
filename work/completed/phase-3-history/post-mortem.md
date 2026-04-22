# Phase 3 Implementation Post-Mortem

_Scope: Waves 1–5.5 (Tasks 0–11, 17). Audit wave (Tasks 12–16) excluded._
_Date: 2026-04-22. Commit range: `4f122ec` (Task 0 impl) to `3db75a2` (Task 9 drawer regression fix)._

---

## 1. Numbers at a glance

- **Tasks done in scope:** 13 (0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 17).
- **Commits on `phase/3-history` since branching from `main`:** 67.
- **Review rounds per task:** 11×{2 rounds}, 3×{1 round: T0, T7, T11}, 1×{3 rounds: T17}. Total 29 review passes across the 13 tasks.
- **Major/critical findings aggregated across reviewer JSONs:** 36 major + 1 critical (T17-R1 filename race). Heaviest tasks by count: T17 (11 major across 2 rounds), T8 (7 major across 2 rounds), T5 (6 major across 2 rounds), T6 (6 major).
- **Post-completion fallout commits after Task 11 was marked Done (`5fe23b2`):** 6 — `a4e035f`, `b5beac5`, `85cb71f`, `58bb44e`, `8ebd2ce`, `3db75a2`. All landed same day, `2026-04-22`, during Honor 200 device smoke.
- **Deferred items in `NOTES.md` that originated in Phase 3:** 3 (free-scroll polish, IME + navbar gap on HarmonyOS, error logging around model init that was already partly addressed). Of these, only one (the HarmonyOS IME gap) is specific to Phase 3 implementation; the other two are cross-phase backlog.

## 2. What went to plan

- **Task 0 (design system) and Task 7 (HomeScreen + navigation) each closed in a single review round with zero major or critical findings** (`logs/working/task-0/code-reviewer-1.json`, `logs/working/task-7/code-reviewer-1.json` — status `APPROVED_WITH_SUGGESTIONS` with 0 critical / 0 major / 7 minor). Tokens, typography, and the `HomeScreen` composable with `hasDownloadedModels` gating shipped as specified.
- **Task 10's `TopAppBarState` sealed class and the `combine(registry.models, _chatModelId, isWarmupInProgress, activeModelName)` reactive derivation survived both review rounds and Honor 200 smoke without post-completion fixes** (`decisions.md` §Task 10; commit `55dcd7a`). The state machine held up across Draft / Loading / Failed / Ready transitions under cross-model switching.
- **Task 1's Room foundation (schema v1, FK cascade, `PRAGMA foreign_keys = ON` via `addCallback.onOpen`) passed 18/18 instrumented tests on real hardware on the first merge-ready build** (`decisions.md` §Task 1 Verification). The only iteration cost came from one low-value instrumented test (`freshDbOpens`) that both reviewers had already flagged as weak.
- **Task 2's atomic sentinel+rekey migration held after one round-1 security-auditor finding** (`logs/working/task-2/security-auditor-1.json` — medium: non-atomic sentinel write). Fix merged into a single `updateData` transform; round 2 confirmed the kill-window closed with a regression test (`migration_writesRekeyAndSentinel_inSingleAtomicWrite`).
- **The `WarmupCoordinator` `isWarmupInProgress` ownership handshake (flag set true BEFORE `cancelAndJoin`, inner finally gated by `warmupJob === coroutineContext[Job]`) shipped correctly** after a round-1 code-reviewer major (`logs/working/task-5/code-reviewer-1.json` — flag flicker). No post-completion regression on this specific concurrency construct.

## 3. What did not

### 3.1 The `modelId` vs `Model.name` blind spot (commit `85cb71f`)

`DefaultModelRegistry.initialize(modelName)` keys on `Model.name` (storage filename). `WarmupCoordinator.launchWarmup(modelId)` was passing the HF-style `modelId` string through unchanged. Both are Kotlin `String`; the compiler cannot distinguish them. The native engine threw `IllegalArgumentException: unknown model: <modelId>` which was logged to `engine-warmup` and the UI spun forever (`decisions.md` §"Task 11 follow-ups" #3).

Tasks that touched this interface without catching the mismatch: Task 2 (`Model.modelId` field addition), Task 3 (`activeModelName` that actually returns `modelId`), Task 5 (coordinator), Task 7 (navigation), Task 8 (ChatViewModel reactive observer), Task 11 (Model Manager wiring).

Gates that should have caught it and didn't:

- **Tech-spec (`tech-spec.md:16`, `:57`, `:112`)** literally writes `registry.initialize(modelId)`. The spec instructed the mismatch. The tech-spec-validator did not flag that `registry.initialize` had historically accepted `modelName` and that renaming the parameter in prose silently changed the contract.
- **Task 3 code-reviewer-1 (`logs/working/task-3/code-reviewer-1.json:3`)** summary says verbatim: _"a map+stateIn derivation that projects `model.modelId` (HF id) of the single Ready entry"_. The reviewer observed that the property `activeModelName` returns `modelId`, did not flag the naming/semantic mismatch. The type is `StateFlow<String?>` so there is no type cue; the name lies.
- **Task 5 security-auditor-1 (`logs/working/task-5/security-auditor-1.json:16–17`)** wrote: _"File path construction — WarmupCoordinator does no file I/O with modelId; registry.initialize uses allowlist-resolved Model objects, not the raw string, for file paths (DefaultModelRegistry resolves by model.name lookup on an in-memory allowlist list, not by concatenation)."_ This is factually wrong and it was the one claim that should have caught the bug. Security-auditor asserted the lookup contract without exercising it, because its threat model was "injection into a path", not "contract mismatch between caller and callee".
- **Task 5 code-reviewer-1 (`logs/working/task-5/code-reviewer-1.json:38`)** inspected `cancelAndRestart(modelId)` signature and suggested returning `Deferred<Result<Unit>>` — completely missed that the single argument was named after one identifier and used as the other.
- **Task 8 code-reviewer-1 (`logs/working/task-8/code-reviewer-1.json:21`)** flagged M2 (`applyHeavySetting` still calls `registry.cleanup + registry.initialize`). The recommendation was literally _"replace with `warmupCoordinator.cancelAndRestart(rawModel.modelId)`"_ — a reviewer instruction to propagate the exact bug.
- **Task 11 code-reviewer-1 (`logs/working/task-11/code-reviewer-1.json:11`)** noticed `LazyColumn key is entry.model.name; the rest of the card keys on entry.model.modelId` — the semantic split was visible in the same file and rationalised as "non-blocking observation".
- **Unit tests in `WarmupCoordinatorTest`, `ChatViewModelTest`** used hand-rolled `FakeModelRegistry` whose `initialize(name)` fake accepted any string and recorded it. The fake mirrored the interface contract (`initialize(name: String)`) but was fed `modelId` by the production code; the fake had no allowlist check. The fakes codified the bug.

This is not a task-specific oversight. It is a class: **two strings with different referents flowing through the same untyped boundary, where every gate inspects one side in isolation.** Skeptic and tech-spec-validator read tech-spec for file existence and AC coverage; they do not simulate data flow end-to-end. Code-reviewers examine one file at a time. Security-auditor reasons about `modelId` as a threat vector (injection, PII) not as a semantic token with a contract.

### 3.2 Post-Task-11 device-smoke fallout classification

Six commits after `5fe23b2`. Classification per prompt rubric:

| SHA | Issue | Class | Gate that should have caught it |
|---|---|---|---|
| `a4e035f` | Explicit-modelId warmup not triggered in `ChatViewModel.bootstrapChatModelId` | **(a) planning gap** — Task 8 spec did not enumerate "user enters via Model Manager `chat/quick?modelId=...` with cold registry, no default, no last-used". `tasks/8.md` implicitly assumed either default-warmup had already fired or the user used Home's quick-chat. | tech-spec-planning + Task 8 acceptance criteria. Integration flow matrix was missing the `(cross-model cold, explicit modelId)` cell. |
| `b5beac5` | First download sets `default_model_id` via AC-F3 observer but no warmup triggered → Home quick-chat suspends forever on `registry.activeModelName.first()` | **(a) planning gap** — AC-F3 spec (`tech-spec.md:254`) says "sets `default_model_id` on first-downloaded model when previously empty" and says nothing about subsequent warmup. Tasks 5 and 6 independently correct in their units; the composition was wrong. | tech-spec AC-F3 wording. Also a test-strategy gap: AC-F3 regression test `ac_f3Observer_triggersWarmup_afterAutoSettingDefault` (written in the fix) would have been writable on day one. |
| `85cb71f` | modelId → Model.name translation | **(b) integration gap** — see §3.1. Each unit compiled and passed tests; the composition was wrong. | every gate; root cause is methodology, not a specific reviewer. |
| `58bb44e` | Single-engine invariant: cross-model switch left prior Ready engine allocated | **(c) regression** — the invariant existed pre-Phase-3 (D-T9, R3). `DefaultModelRegistry.initialize(B)` called `releaseEngine(B)` (idempotent no-op) but did not release A. Phase 2 flow never exercised cross-model because registry reinit was always same-name. Task 3's `activeModelName` made cross-model a real path; `DefaultModelRegistry.initialize` was not updated to match. | This is arguably pre-existing dormant bug surfaced by new access pattern. Neither tech-spec nor task spec required Phase 3 to audit `DefaultModelRegistry.initialize`'s release path. Skeptic should have, but its scope is tech-spec compliance, not invariant audit. |
| `8ebd2ce` | IME + navbar double inset | **(d) device-specific** on Honor 200. The `.imePadding()` logic from Task 10 is correct in Compose semantics. HarmonyOS reports insets differently; caught only on hardware. `consumeWindowInsets(innerPadding).imePadding()` is clean in the emulator and on standard Android; Huawei OEM layer doesn't match. Deferred to NOTES. | No CI-affordable gate would catch this. Emulator smoke is insufficient for HarmonyOS-specific layout. |
| `3db75a2` | Drawer "Новый чат" routed to `chat/quick` (incognito) instead of `chat/draft` (model picker) | **(c) regression / user-intent drift** — Task 9 wired it to `quick` because `chat/draft` was a placeholder at the time Task 9 shipped. Task 10 filled in `chat/draft` with the picker. No one went back and re-audited Task 9's nav targets. | Task 9 → Task 10 dependency link in `decisions.md`. This is the cost of wave-level review without a wave-closing integration pass. |

Summary: **3 of 6 fallout commits are composition/integration failures, 1 is a dormant invariant surfaced by Phase 3 access patterns, 1 is a planning gap, 1 is device-only.** The emulator/unit suite (241 tests, all green — `decisions.md` §Task 11 Verification) caught zero of them because all six failures live in wiring between modules, not inside any single module.

### 3.3 Drawer wiring miss as a wave-composition failure

Task 9 and Task 10 were adjacent. Task 9's `DrawerContent.onNewChat` target was set when `chat/draft` was a stub. Task 10 activated `chat/draft` without auditing Task 9's caller. Both tasks have "Done" status with approved reviews. The defect exists in the seam.

This is the same class as §3.2's a4e035f and b5beac5: every task's unit was correct; the composition was not validated. Our review unit is the task, not the wave.

## 4. Review-round analysis

**Round-2 frequency.** 11 of 13 tasks required a round-2 pass. The three that closed in one round (T0, T7, T11) are smaller-scope or purely additive: T0 is tokens, T7 is a composable stack that depends on existing types, T11 is three VM methods. The eight tasks with 2+ rounds are the ones that introduce cross-module surface area.

**Test-reviewer vs code-reviewer balance.** In round 1, test-reviewer produced 18 major findings, code-reviewer produced 16. **Every task with a round-2 test-reviewer pass had majors classified as either `empty_test` or `litmus_false_positive`** (T1, T3, T5, T6, T8, T9, T10, T17). The recurring pattern (`logs/working/task-5/test-reviewer-1.json:3`, `logs/working/task-10/test-reviewer-1.json`, `logs/working/task-17/test-reviewer-2.json`): **the test asserts a terminal state that the initial condition already satisfies.** T5's `isWarmupInProgress_emitsFalse_afterCompletion` was the archetype — flag starts false, test asserts false after warmup completes, an implementation that never flips the flag to true passes.

**Highest-findings tasks and what they share.**
1. **Task 17 (attachment staging)** — 1 critical + 8 major across 3 rounds. Combines filesystem atomicity, Room transactions, security containment, and a payload writer seam. Multiple concerns in one task.
2. **Task 8 (ChatViewModel rework)** — 7 major across 2 rounds. Reactive rewrite of a stateful component plus a double-bubble invariant plus Draft→Persistent handoff plus a deferred debt (Settings gate to Task 10).
3. **Task 5 (WarmupCoordinator)** — 6 major across 2 rounds. Coroutine lifecycle + StateFlow transitions + AC-F3 observer. `UnconfinedTestDispatcher` vs `StandardTestDispatcher` deviation masked scheduler-sensitive bugs in initial tests.
4. **Task 6 (SanctumApplication)** — 6 major across 2 rounds. Cold-start sequence, Room corruption recovery, `@Volatile` memory barrier, housekeeping extraction.

All four share: **they own cross-cutting invariants**. Their correctness lives in the composition of collaborators, not in any single method. The same reviewers who closed T0 and T7 in one round burned two rounds on these. The signal is not author quality — it is scope: tasks that own invariants compose poorly under per-file review.

**Reviewer specialisation.** Security-auditor produced exactly **one security-specific major (T2 non-atomic sentinel)** across all rounds. Every other security finding was either low or deferred. Security-auditor also missed §3.1 (the `modelId` contract mismatch) while writing a summary that asserted its correctness. The current security-auditor brief is OWASP-sized; it has no handle on "contract mismatch between caller and callee that isn't a threat vector". That is a review-methodology gap.

## 5. Deviation analysis

Aggregate of `decisions.md` **Deviations:** sections, 13 tasks:

- **(a) tech-spec wrong / oversimplified:** 7 instances. Task 0 (13 vs 15 color tokens), Task 2 (thunk vs nullable registry for JVM signature clash), Task 4 (T4-S1 containment requires explicit `filesDir`; rename inside Room transaction to close kill-window), Task 6 (housekeeping inline → extracted class; `sweepZombieChats` logging component; `DefaultDownloadRepository.workManager` lazy), Task 8 (M2 Settings gate deferred to Task 10), Task 17 (multi-image honest MVP; UUID vs sequential index; `payloadWriter` seam), Task 9 (swipe-to-confirm pattern).
- **(b) tech-spec right, implementation chose better path:** 3 instances. Task 1 (KSP top-level block), Task 3 (writer-loop + UUID probe for wiring test), Task 5 (`@VisibleForTesting` ctor for scope injection).
- **(c) tech-spec right, implementation chose worse path and got away with it:** 2 instances, both implicit in the §3.1 analysis — Task 3's `activeModelName` naming that the spec literally blessed ("name of the model currently in `ModelInitStatus.Ready`") while returning modelId; Task 5's `launchWarmup(modelId)` signature that codified the bug. Neither is a deviation from tech-spec in decisions.md — they follow tech-spec. The tech-spec itself is the (c) case.
- **(d) emergent during implementation:** 4 instances. Task 4 (T4-S6 orphan dir known debt deferred to Task 6), Task 8 (draft-attachment debt surfaced → Task 17), Task 9 (stale StateFlow read on `checkModelAvailable` after `WhileSubscribed(5_000)` expiry), Task 10 (tests live in `ChatViewModelTest` not separate file due to fake-reuse).

**(a) dominates** by 7:3:2:4. The instructive deviation is Task 4's **"rename inside Room transaction"** (`decisions.md` §Task 4 Deviations): tech-spec prescribed a two-step (transaction, then rename, then deleteById-on-rename-fail) schema. Security round 1 flagged the kill-window between rename-fail and deleteById. Implementation collapsed both into one transaction and the outer `try/catch` cleans staging. This is a case where the (a)-deviation was driven by security review, not by reality-check — the bug would have existed in spec-faithful code.

## 6. Process gaps — root causes

1. **The skeptic agent validates tech-spec for AC coverage and file existence, not for interface-contract mismatches.** Both `registry.initialize(name)` and `activeModelName (which returns modelId)` existed and were valid in isolation. Their disagreement is exactly the class of bug skeptic cannot see. **Proposed change:** skeptic gains a "cross-boundary identifier trace" step: for every identifier that appears on both sides of an interface (Kotlin String, Long, primitive), verify the producing and consuming contracts agree on semantics, not just type. This should be a required output, not optional.

2. **Security-auditor's threat model is OWASP-first.** It reasons about `modelId` as injection surface, not as a token with a contract. Its write-up in §3.1 explicitly claimed registry resolution behavior it had not verified. **Proposed change:** security-auditor receives an "assumptions" output section it must populate. Any claim of the form "method X uses Y not Z" requires a code-excerpt citation. No citation → the assumption becomes an explicit `INFO` finding for code-reviewer to verify.

3. **Fakes mirror interface contracts, not production behavior.** `FakeModelRegistry.initialize(name: String)` accepts any string and records it. The real `DefaultModelRegistry.initialize(name)` lookups an allowlist by `model.name`. Unit tests passed because the fake has no allowlist. **Proposed change:** for fakes of types that enforce an identity contract (registry, DAO lookup by key), the fake must seed a canonical set and reject non-matching inputs by default. Opt out only with explicit `acceptAny = true` for edge-case tests. `seedAllowlist(vararg modelIds)` (added in commit `85cb71f`'s fix) is the right pattern; it should have been the starting pattern.

4. **Review unit is the task, not the wave.** Drawer routing (§3.2 `3db75a2`), explicit-modelId warmup (§3.2 `a4e035f`), auto-warmup after first download (§3.2 `b5beac5`), and the composition failure of §3.1 all live in seams between adjacent tasks. There is no wave-closing review pass. **Proposed change:** after the last task of a wave is marked Done, a "wave integration sweep" runs: list every `tasks/N.md` → `tasks/(N+1).md` caller/callee pair, verify each resolves. This is cheaper than a full cross-task review and targets exactly the bug class that leaked.

5. **Device smoke is last-mile, not continuous.** All 241 unit tests were green when Task 11 closed. Six defects waited on hardware. The user memory "Verify UI chain before device smoke" defers device smoke to phase-level QA, which is correct for cost reasons, but bunching six defects into a single post-Task-11 session made diagnosis fragile (several fixes interacted). **Proposed change:** after a wave that closes a user-visible flow end-to-end (Wave 4-5 in Phase 3), schedule a device smoke at wave close, not phase close. This is a project-knowledge / methodology update, not a per-task requirement.

## 7. Device vs emulator gap

Six fallout commits on Honor 200. Classification:

- **Device-only (1):** `8ebd2ce` (HarmonyOS IME + navbar insets). Emulator renders `consumeWindowInsets(innerPadding).imePadding()` correctly. Huawei's OEM layer is non-standard. The cheapest preventive test infrastructure change is `null` — this one genuinely needs Honor 200 hardware. Parking it in NOTES is correct.
- **Logic bugs device happened to surface first (5):** `a4e035f`, `b5beac5`, `85cb71f`, `58bb44e`, `3db75a2`. Each of these has a unit-testable failure:
  - `a4e035f`: `ChatViewModel.bootstrapChatModelId` with `explicitModelId != null && registry.activeModelName.value == null && warmupCoordinator.isWarmupInProgress.value == false` → assert `warmupCoordinator.cancelAndRestart` is called. Writable in `ChatViewModelTest` against existing fakes; the fixture gap was "we never tested the cold-registry + explicit modelId path".
  - `b5beac5`: `WarmupCoordinator` with `appSettings.defaultModelId = ""`, registry emits first `SUCCEEDED` entry → assert `setDefaultModelId` AND `launchWarmup` both called. Regression test `ac_f3Observer_triggersWarmup_afterAutoSettingDefault` was added with the fix and pins it.
  - `85cb71f`: `FakeModelRegistry` with `seedAllowlist("hf/modelA")` + `Model("modelA", modelId="hf/modelA")` → calling `warmupCoordinator.cancelAndRestart("hf/modelA")` must result in `registry.initialize("modelA")` (Model.name), not `registry.initialize("hf/modelA")`. Trivially writable once the fake enforces allowlist.
  - `58bb44e`: `DefaultModelRegistry` with two Ready entries A,B; call `initialize(B)` → assert A transitions Idle and `releaseEngine(A)` was invoked exactly once. Writable in `DefaultModelRegistryTest`.
  - `3db75a2`: Navigation integration test — `DrawerContent.onNewChat` callback → asserts route `chat/draft` not `chat/quick`. Writable as a Composable test or as a Navigation-graph unit test; the current `logs/working/task-9/code-reviewer-1.json` and `test-reviewer-1.json` don't touch it because neither reviewer checks route strings against the nav graph defined in another file.

**Cheapest infrastructure change that would have caught the logic ones:** hand-rolled fakes adopt the seeding discipline in process-gap #3. Three of the five (85cb71f, 58bb44e, a4e035f) surface immediately once fakes enforce contracts. `b5beac5` needs one new test. `3db75a2` needs a cross-file check that the wave integration sweep (process-gap #4) would do.

Estimated wall-clock cost of these changes in reviewer JSONs: small. Estimated wall-clock cost of the device smoke session on 2026-04-22 that found them: half a day including the diagnostic hunt for `85cb71f` through `errors.log`.

## 8. Action items for audit wave (Tasks 13–16)

1. **Code audit (T13) — registry/coordinator contract trace.** For every call site of `DefaultModelRegistry.initialize`, `registry.cleanup`, `WarmupCoordinator.cancelAndRestart`, and `WarmupCoordinator.warmupDefault`, verify the argument is `Model.name` where required and `modelId` where required. Files: `app/src/main/kotlin/app/sanctum/machina/engine/WarmupCoordinator.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerViewModel.kt`. Deliverable: a table of boundaries and which identifier crosses each.

2. **Code audit (T13) — single-engine invariant re-audit.** `58bb44e` closed one violation path. Verify no other call to `initialize` assumes "prior Ready stays Ready". Specifically scan `ChatViewModel.applyHeavySetting` (unchanged per `decisions.md` §Task 8 Deviations M2), `WarmupCoordinator.cancelAndRestart`, and any code path that can call `registry.initialize` directly without going through the coordinator.

3. **Security audit (T14) — fake discipline across the unit suite.** Check every `FakeModelRegistry`, `FakeChatDao`, `FakeMessageDao`, `FakeAppSettingsRepository` in the test tree for the bug in process-gap #3: do lookup fakes accept any key or do they enforce a seeded set? Target files: `app/src/test/kotlin/app/sanctum/machina/**/Fake*.kt` and inline `private class Fake*` definitions in `ChatViewModelTest`, `ChatRepositoryTest`, `DrawerViewModelTest`, `WarmupCoordinatorTest`.

4. **Security audit (T14) — staging containment re-check on Task 17 paths.** Task 17 introduced `writeAttachmentStaging`, `savePersistentAttachment`, `deleteStagedAttachment`, `pruneStagingDir`. T17-R2-S1 (security round 2 minor) left non-regular-file skip (symlinks/sub-dirs) as a documented contract. Confirm `pruneStagingDir` in `ChatRepository.kt` honors this under adversarial `stagingDir` contents.

5. **Test audit (T15) — terminal-state-only assertion scan.** Grep all test files for patterns matching `logs/working/task-5/test-reviewer-1.json` archetype (assertion checks only post-condition of a StateFlow that has the same initial value). Candidates: `WarmupCoordinatorTest`, `ChatViewModelTest.topAppBarState_*`, `ModelManagerViewModelTest`, `HomeViewModelTest`. Deliverable: list of tests that would pass under a null implementation.

6. **Test audit (T15) — ensure `WarmupCoordinatorTest.ac_f3Observer_triggersWarmup_afterAutoSettingDefault` is present and pins.** This is the regression test added with `b5beac5`. Confirm it is still asserting the coupled write (both `setDefaultModelId` AND `launchWarmup`).

7. **Pre-deploy QA (T16) — wave integration sweep before phase close.** Walk every user entry point (Home, Model Manager, Drawer, deep-link) × every chat identity (Quick, Draft, Persistent) × cold-registry vs warm-registry × default-set vs default-unset. Device: Honor 200 + one non-HarmonyOS device if available. Record which cells 3db75a2, a4e035f, b5beac5 fixes have already exercised.

### 8.bis Task 18 closure addendum (2026-04-22)

Task 18 batched five device-smoke defects (B1–B5, commit `b3074eb`) and a post-smoke round (commit `3fc1104`) that addressed three further user-reported bugs from the same session. Updated status of the §8 audit items:

- **§8.1 (contract trace)** — still open for T13. Task 18 B2 did not introduce new boundaries; the existing `modelId`/`Model.name` trace is unchanged.
- **§8.2 (single-engine invariant re-audit)** — partially addressed. B2 extends the release filter from `Ready` to `Ready || Initializing` (closes scenario-3: stale Initializing on prior target when cancelled warmup races a new one) and adds `catch(CancellationException) → Idle` so a cancelled `initialize` never leaves the target pinned at `Initializing`. T13 still runs against the rest of the codebase but these two paths are locked in.
- **§8.4 (staging containment)** — Task 18 B1 added a symmetric read-side containment check (`resolveInsideAttachmentsRoot` in `ChatViewModel`) mirroring the write-side check in `DefaultChatRepository`. T14 scope should verify both paths canonicalise to the same attachments root.

**New failure classes surfaced by Task 18, not previously in the taxonomy:**

- **Stale VM state variable after user-intent change.** `ChatViewModel.loadModel(modelId)` delegated to the coordinator but never updated `_chatModelId` — `observeEngineState` kept watching the prior model's entry after the single-engine release flipped it to `Idle`, so `uiState` froze at `Loading` indefinitely. Class: same as §3.1 (two representations of intent that can diverge silently), but the divergence is between VM state and user action, not between caller/callee types. No gate in the current methodology catches "state X should follow user action Y"; test-reviewer can only see it via a terminal assertion that crosses the handover.
- **Draft→Persistent handover data loss.** B4 auto-resume started at `pending = emptyList()` as a documented MVP trade-off. The user flagged it as a first-send bug because the image was saved to disk AND rendered in the history bubble (B1), so "the image is there" was the user's mental model — the fact that it wasn't forwarded to inference was invisible from the UI. **Lesson: when a feature's MVP scope drops data that is already persisted and visible, the "drop" is a regression in the user's view, not a deferred enhancement.** Add to the methodology: MVP-scope decisions that drop already-persisted data must be called out in user-spec AC, not just in task-file Implementation hints.
- **Decomposition gap: user-spec section not referenced by any task.** B5 drawer footer pins (user-spec §37) were not listed in any task's "What to do" or AC. Task 9 implemented the drawer but not the footer; no task-validator caught the missing reference. **Lesson: task-validator should fail if a user-spec section is not cited by at least one task file.** Orphan spec sections are a high-signal indicator of decomposition drift.
- **Legacy full-screen overlay from earlier phase survives past its scope.** `ChatScreen.LoadingContent` was a Phase-1 paradigm (full-screen "loading model" view) that Phase 3's TopAppBarState state machine had structurally replaced — but the composable was still rendered on `ChatUiState.Loading`. Cross-model reinit hit two loading indicators stacked (topbar chip + full-screen overlay), and when `_chatModelId` failed to track the new target, the overlay became a permanent stuck state. **Lesson: when a phase introduces a new UX pattern (TopAppBar state machine in Task 10), the phase-close audit must enumerate legacy composables that overlap with it and explicitly accept or remove them.** `LoadingContent` should have been removed in Task 10 along with the TopAppBarState rollout; it survived until Task 18 round-2.

## 9. Action items for Phase 4 planning

1. **user-spec or tech-spec must explicitly document which identifier (Model.name vs modelId) flows through each interface boundary.** Both are `String`; type-checking cannot catch the mismatch. Add a "Identifier glossary" section to Phase 4 tech-spec that enumerates each boundary: `ModelRegistry.initialize` takes `Model.name`, `WarmupCoordinator.warmupDefault()` resolves an ID that goes through `Model.name` translation, etc. Skeptic validates this section exists and is consistent with code.

2. **Add a wave-closing integration pass to the methodology.** After the last task of a wave, run a "wave-close" review that enumerates inter-task call sites and verifies they resolve to the final target (not a placeholder from earlier in the wave). This is separate from code-reviewer, test-reviewer, security-auditor. Scope: adjacent task pairs only; bounded cost.

3. **Hand-rolled fake discipline becomes a documented pattern in `patterns.md`.** Write-up from process-gap #3: lookup fakes enforce a seeded set by default; `acceptAny = true` is explicit opt-out. Update the existing `seedAllowlist(vararg modelIds)` helper (commit `85cb71f`) into the canonical example.

4. **Security-auditor gets an "assumptions" output contract.** Every claim of the form "method X uses Y not Z" requires a code-excerpt citation with a file:line link. Uncited assumptions convert to `INFO` findings for code-reviewer. Retrofitting this on `logs/working/task-5/security-auditor-1.json` would have caught §3.1.

5. **Device smoke moves to wave close, not phase close, when a wave closes a user-visible flow.** Phase 4 (Projects + RAG) is likely to have multiple such waves. Update `.claude/skills/project-knowledge/` with this rule. Memory "Verify UI chain before device smoke" stays correct for tasks that do not close the chain; it should not apply once the chain is closed.

_Word count: ~3470._
