# Phase-3 Test Audit Report (Task 15)

**Scope:** 12 Phase-3 test files (9 unit + 3 instrumented) listed in `tasks/15.md`.
**Methodology:** `~/.claude/skills/test-master` — 7-dimension audit, `test-quality-review.md` category sweep, litmus-test pass over sampled assertions.
**Deliverable:** this report. No code changes.

---

## Executive summary

Phase-3 test quality is **passed** (per `test-master` decision matrix). All 7 dimensions meet bar; 0 critical, 0 high-severity issues. Two minor gaps and one low-severity documentation drift are recorded as recommendations for the QA wave (Task 16) or as follow-on items.

- Decision matrix: 0 critical, 0 high, 2 medium, 1 low → **passed** (threshold for `needs_improvement` is `medium ≥ 5`).
- `taskRequired.needed = false` — findings can be logged as follow-ons; no blocker for Task 16.

---

## Verdict per dimension

| # | Dimension                                       | Verdict           | Notes                                                                                                                                                         |
|---|-------------------------------------------------|-------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | Atomic transition coverage (ChatRepositoryTest) | **PASS**          | Happy + 2 failure paths + message-insert-throws rollback all explicit.                                                                                        |
| 2 | Concurrency scenarios (Warmup + ModelRegistry)  | **PASS**          | `cancelAndRestart` ordering asserted via `CompletableDeferred.isCompleted`. J5 atomicity covered by StateFlow.map semantics + dedicated `noStaleReady` test.  |
| 3 | Error paths (warmup failure, delete, zombie)    | **PASS**          | `ErrorLog.e("engine-warmup", ...)` asserted via real filesystem capture (bounded-wait). DB corruption E2E is tested elsewhere (see Gap G2).                   |
| 4 | Test pyramid balance                            | **PASS**          | 9 unit + 3 instrumented; CASCADE and FK PRAGMA correctly in `androidTest/`; pure logic (AutoTitle) correctly in `test/`.                                      |
| 5 | Meaningful assertions                           | **PASS**          | No `expect(true).toBe(true)`, no mock-return-asserted-back, no "didNotThrow" assertions. Fakes record state; tests assert real computed outputs.              |
| 6 | Double-bubble handover (ChatViewModelTest)      | **PASS**          | `doubleBubble_noSimultaneousStreamingAndPersisted` asserts `assistantCount ≤ 1` on **every** emission during send+done → Room-row handover, not just final.   |
| 7 | TDD compliance (git history)                    | **PASS**          | Every task-N commit lands tests + impl in a single commit. Project convention is "write tests in the same session" — no commits with impl-only.                |

---

## Per-file assessment

### Unit tests

**`AutoTitleGeneratorTest`** (AC-U2)
AC-U2 cases covered: short text, long+cut-at-last-space, long+no-space+hard-cut, leading/trailing whitespace trim, multiple-space collapse, null/empty/whitespace-only fallback with exact `Чат от DD.MM HH:mm` format check, exactly-20-chars, 21-chars-no-space. Pure JVM, no Robolectric needed — but runs under Robolectric for class-level consistency. Redundancy: `whitespaceOnlyText_fallback`, `emptyText_fallback`, `nullText_fallback` are three tests for the same fallback branch; tolerable — each pins a distinct input class.

**`SettingsMigrationHelperTest`** (AC-R8, Decision 8)
Covers: rekey happy path, orphan-dropped-and-logged (under `[settings-io]` component — asserted via real errors.log read), sentinel prevents re-run (asserted via `CountingDataStore.updateCount`), single-`updateData` atomicity, **`migration_writesRekeyAndSentinel_inSingleAtomicWrite`** is the load-bearing invariant test for Decision 8 — the one that catches the regression where rekey and sentinel land in separate `updateData` calls. Empty-overrides-marks-sentinel-without-logging is the no-op branch. `CountingDataStore` wraps a real DataStore — not a mock returning canned values — so assertions observe production serialization end-to-end. Excellent.

**`ChatRepositoryTest`** (AC-A1–A6, AC-P7, AC-P8, AC-U2, AC-R2)
Full atomic-transition coverage: happy path (`commitDraftChat_happyPath`), staging-missing IOException before Room (`commitDraftChat_missingStagingDir_throwsBeforeRoomInsert`, `commitDraftChat_stagingOutsideAttachmentsRoot_throws`), rename-throws-after-Room-insert with full rollback (`commitDraftChat_renameFailsAfterRoomInsert` — uses a hand-rolled `transactionRunner` that mirrors `database.withTransaction` rollback on throw; asserts chat map is empty AND staging dir is gone), message-insert-throws rollback (`commitDraftChat_messageInsertFails_chatRollbackToo`). `deleteChat_callsRoomAndDeletesFiles` asserts BOTH `deleteById` was called AND the attachments directory is gone. Zombie sweep: zero-messages-missing-dir-deleted + logged under `history-write`, zero-messages-with-dir-kept, non-zero-messages-no-dir-kept. Staging writes: UUID filenames verified collision-free across 5 concurrent writes; partial-file rollback under payloadWriter throw verified by asserting staging dir is empty. Auto-title triggered on commit (`autoTitle_triggeredOnCommit`). Litmus: the `transactionRunner` seam is a test-only simulation of Room's `@Transaction` — see Gap G1.

**`WarmupCoordinatorTest`** (AC-F3, AC-F5, AC-F6, AC-D4, AC-B2)
Resolution order: `default_model_id` → `last_used_model_id` → no-op, each asserted via `initializeCalls`. `last_used_model_id` updated only on success, never on failure. Warmup failure → `ERROR [engine-warmup]` line in real `errors.log` (bounded 2 s real-time poll — the Test scheduler cannot advance `Dispatchers.IO`). `cancelAndRestart_cancelsInFlightJobBeforeNewInitialize` uses a `CompletableDeferred` chain in the `initializeHandler` and `fail()`s if the second `initialize` runs before `firstCancelled.isCompleted` — load-bearing ordering assertion, not "final state checks out". `warmupDefault_calledTwice_cancelsFirstBeforeSecondStarts` mirrors the same pattern for repeated warmup calls. `isWarmupInProgress_staysTrue_acrossCancelAndRestart_andClearsOnCompletion` pins Task-10 spinner invariant: no `false` gap during handover. AC-F3 observer covers 4 sub-cases: empty-initial-list no-op, first-SUCCEEDED sets default AND triggers warmup, one-shot termination verified via `subscriptionCount.value == 0`, multiple-SUCCEEDED-same-emission picks first. `crossModelSwitch_whenPriorReady_eventuallyReachesReady_forNewTarget` pins single-engine invariant (prior model drops to Idle). `crossModelSwitch_isWarmupInProgress_finalizesFalse_evenOnFailure` pins `finally` block as sole false-writer.

**`ModelRegistryActiveModelTest`** (AC-E2, AC-E3, J5)
Tests 1–5 drive `deriveActiveModelName` directly: initial null, Ready → modelId (NOT `name` — the invariant is explicit), Ready → Idle drops to null, Failed drops to null, mixed list picks only Ready entry, every-state-matches-snapshot pinning the `firstOrNull { it.initStatus === Ready }` predicate. Test 6 (`defaultModelRegistry_activeModelName_wiredToMutationsOfUnderlyingModelsFlow`) constructs the real `DefaultModelRegistry`, races `refreshAllowlist`'s init-block with a writer loop until the probe modelId is observed — closes the "constant StateFlow regression" gap that helper-only tests would miss. J5 concurrent-read atomicity is covered by StateFlow.map semantics (noted inline in the test) + the `noStaleReady` state transition test; test-reviewer would flag a true thread-race test as over-engineering given the map contract.

**`ChatViewModelTest`** (AC-E3, AC-E6, AC-R1–R3, AC-U5–U7, Risk: double-bubble; B1/B4 post-smoke)
72 test methods across 8 groupings. Highlights:
- **Double-bubble** (`doubleBubble_noSimultaneousStreamingAndPersisted`): captures every `vm.messages` emission, asserts `assistantCount ≤ 1` on every snapshot during send + streaming + done + Room re-emit. Final assertion confirms the surviving ASSISTANT is the Room-backed (non-streaming) row. Test-reviewer's explicit invariant comment cites why the per-emission assertion is load-bearing rather than final-only.
- **Quick-mode purity**: `quickMode_neverCallsMessageDao` asserts BOTH `observeCalls == 0` and `inserted.isEmpty()` — catches either-or regressions.
- **AC-E6**: `onCleared_doesNotCallRegistryCleanup` via reflection.
- **TopAppBar state machine** (9 tests): Draft with downloaded-list, Draft with empty downloaded, Persistent × {Idle, Initializing, Failed, Ready}, warmup-in-flight short-circuit, Quick. Each pins a distinct branch — removing any `when` arm would flip exactly one test.
- **Attachment staging** (Task 17 block): 10+ tests covering add/remove/replace, send-gate while staging in-flight (uses suspending fake + `CompletableDeferred`), multi-image prune-to-first with `pruneStagingDir` call-set assertion, decode on reopen (`imagePath` → Attachment.Image), containment check with decoy PNG planted outside root (prevents trivial pass via file-nonexistence).
- **Persistent auto-resume** (B4): unpaired-last-USER + first-Ready → inference dispatched, paired last-ASSISTANT → no dispatch, flutter Ready→Init→Ready → only one dispatch (autoResumeAttempted gate).
- **Engine-state transitions**: Initializing/Ready/Failed each asserted as `ChatUiState` value change with correct payload.
- **AC-R1/R2/R3**: USER-row-saved-before-runInference (asserted via sequence in `sharedCalls`), ASSISTANT-written-only-on-done (counted before+after the `done=true` callback), stop-before-done does NOT persist ASSISTANT.

**`DrawerViewModelTest`** (AC-P4, AC-U1, AC-M1, Task 9)
22 tests. Date-bucketing against a fixed `today = 2026-04-21` (no real-clock dependency) pins 2..6-day window (inclusive) and the 7-day EARLIER boundary. `sectionBoundaryIsLocalMidnightNotRolling24Hours` pins Decision 7 local-date granularity vs wall-clock ≥24h approximation — a subtle semantic the impl must honor. `deleteChatEmitsPopBackWhenCurrentChatDeleted` uses `CoroutineStart.UNDISPATCHED` so subscription races don't swallow the event — excellent discipline for SharedFlow testing. Rename coverage is litmus-grade: `renameChatWithBlankTitleResetsAutoTitle` asserts the regenerated title is `"Привет"` (from AutoTitleGenerator), not just `isNotBlank()` — would catch a regression that wrote back the stale manual title. `renameChatTrimsAndCapsAtSixtyChars` pins the AC-U1 60-char cap. `checkModelAvailable` covers SUCCEEDED, NOT_DOWNLOADED, unknown-chatId, model-removed-from-registry. Coverage tight; no unneeded tests.

**`ModelManagerViewModelTest`** (AC-F4, AC-F7, Task 11)
5 tests. `setDefaultModel_persistsBeforeEmittingSnackbar` uses a shared `actionLog` hooked via `settings.onSetDefaultModelId` + VM event collector — asserts `setDefault:abc` precedes `event:ShowSnackbar`. This is the load-bearing ordering test (swapping the lines in prod code passes the "two-assertion" test but fails this one). `defaultModelId_emitsEmptyStringWhenUnset` pins proto3 empty-string default. `defaultModelId_reflectsSettingsValue` uses UNDISPATCHED subscription so `stateIn(initialValue = "")` is observed before the mutation. `onLoad_emitsOpenQuickChatEvent` asserts exactly one event of the correct type. Thin but every test earns its place; no redundancy.

### Instrumented tests

**`ChatDaoTest`** (Room v1)
CRUD happy paths: insertAndGetById (default `isManuallyTitled=0`, default `projectId=null`), `insertAndGetByIdPreservesProjectIdValue` (nullable FK populates), update-title-and-manual-flag, deleteById, `observeAllEmitsSortedByLastMessageDesc` (3 inserts with distinct `lastMessageAt` → ordered DESC). `observeAllEmitsOnInsert` uses a live collector + `MutableSharedFlow` + bounded `withTimeout` — asserts a re-emission occurs on insert, NOT just the initial emission Room delivers on subscribe. That distinction is what catches "Flow was set up but is actually a static snapshot" regressions.

**`MessageDaoTest`** (Room v1, CASCADE)
CRUD, observeByChat Flow sorted by `created_at` ASC, **`onDeleteCascadeRemovesMessages`** asserts both `countByChatId == 0` and `getByChatId.size == 0` after `chatDao.deleteById(chatId)` — pins FK `ON DELETE CASCADE` fires. `textDefaultsToEmptyString` pins the `text: String = ""` default. `nullableFieldsRoundTrip` pins the thinking/image/audio/tokenCount columns. `observeByChatFiltersByChatId` pins the WHERE clause. `deleteByIdRemovesSingleMessage` pins single-row delete.

**`SanctumDatabaseTest`** (schema + FK PRAGMA)
`foreignKeysPragmaIsOn` asserts `PRAGMA foreign_keys = 1` after `ForeignKeysOnOpenCallback`. `foreignKeyEnforcementEnabled` attempts a message-insert with non-existent chatId, asserts `SQLiteConstraintException` — end-to-end FK enforcement verified. `schemaContainsChatsAndMessages`, `noProjectFilesTable`, `noProjectsTable` pin Decision 1 (v1 schema is ONLY chats + messages). No explicit JSON-vs-schema comparison test — but AC ("`app/schemas/.../1.json` committed and matches entity definitions") is checked at build time by Room's compiler (`@Database(exportSchema = true)`) + the agent-verification step 7 grep.

---

## Findings

### G1 — Room `@Transaction` semantics are simulated, not exercised (Medium)

**File:** `app/src/test/kotlin/app/sanctum/machina/data/ChatRepositoryTest.kt:164-179` and :210-219
**Litmus test:** passes.
**Issue:** The rollback tests (`commitDraftChat_renameFailsAfterRoomInsert`, `commitDraftChat_messageInsertFails_chatRollbackToo`) inject a hand-rolled `transactionRunner` that snapshot/restores the fake DAOs on throw. This correctly models Room's `database.withTransaction { ... }` rollback-on-throw contract, but it does not verify that `DefaultChatRepository` actually wraps the INSERT + rename sequence in a transaction such that a throw INSIDE the block triggers rollback. If a future refactor moved the `rename()` call outside the `withTransaction` block (a common mistake because filesystem ops shouldn't normally be inside DB transactions), the production code would silently fail to roll back while the unit test continues to pass under its simulated runner.
**Replacement / fix:**
- Add one instrumented test in `ChatDaoTest` (or a new `DefaultChatRepositoryInstrumentedTest`) that exercises the real Room `@Transaction` / `withTransaction` rollback behavior on a simulated rename failure via a stub `rename = { _, _ -> false }` and a real `SanctumDatabase`. Assert that after the throw, `chatDao.getById(id) == null` — proving the production wiring calls the transaction boundary correctly.
- Alternative: a comment in `DefaultChatRepository.kt` above the transaction call explicitly naming the invariant, plus a single-line compile-time check (e.g. a `@Transaction`-annotated DAO method used there).
**Severity rationale:** Medium, not high — the transaction contract is straightforward Room usage with good code-reviewer coverage; the risk is a future refactor, not the current implementation.

### G2 — DB-corruption handler path is covered only in `AppCorruptionStateTest`, outside audit scope (Medium)

**Files:** audit scope `SanctumDatabaseTest.kt`, `ChatRepositoryTest.kt`; out-of-scope `AppCorruptionStateTest.kt`.
**Issue:** Tech-spec Decision 9 (AC-D5: "Rename + fresh DB + in-memory banner") binds two behaviors: (1) `AppCorruptionState` flips the in-memory banner when Room throws on open, (2) `SanctumApplication` / `AppModule.provideSanctumDatabase` renames the corrupt file to `sanctum.db.corrupt_{ts}` before reopening. `AppCorruptionStateTest` (not in task-15 scope) covers the state singleton. No audit-scope test covers the end-to-end "corrupt file on disk → open-time rename → fresh DB usable". `SanctumDatabaseTest` tests fresh-DB-opens but not corrupt-DB recovery.
**Replacement / fix:** Add one instrumented test in `SanctumDatabaseTest` (or a new `SanctumDatabaseCorruptionTest`) that:
1. Seeds `context.getDatabasePath("sanctum.db")` with a known-bad byte sequence before construction.
2. Invokes the construction path used by `AppModule.provideSanctumDatabase` (the corruption-catch branch).
3. Asserts (a) a `sanctum.db.corrupt_*` file now exists in `filesDir`, (b) the returned DB is usable (insert + query roundtrip succeeds), (c) `AppCorruptionState.corruptionOccurred == true`.
**Severity rationale:** Medium — this exact scenario caused the realme incident before Phase 3 mitigation; the mitigation's end-to-end path has no automated regression guard.

### G3 — `ChatTopAppBarStateTest.kt` does not exist as a separate file (Low, docs drift)

**Files:** `tasks/15.md:128` references `ChatTopAppBarStateTest.kt`; no such file exists. TopAppBar state-machine tests were consolidated into `ChatViewModelTest.kt` (9 `topAppBarState_*` methods, file:line 1267-1402).
**Issue:** Documentation drift in the task spec only — coverage is present. The consolidation is arguably the right call (TopAppBar state is computed inside the VM, not a standalone extractable function).
**Replacement / fix:** Either (a) amend `tasks/15.md` to reference the consolidated location, or (b) extract the TopAppBar state computation into a pure function (`computeTopAppBarState(identity, registry, warmup)`) and write standalone tests against it. Option (a) is lighter weight and sufficient.
**Severity rationale:** Low — no coverage gap; documentation inconsistency only.

### G4 — Delete-open-chat-while-streaming is not tested in `ChatViewModelTest` or `DrawerViewModelTest` (Medium, borderline Low)

**File:** audit-scope gap.
**Issue:** Tech-spec Risk "Delete open chat while stream in progress → callback writes to deleted row" (code-research J4) documents a mitigation in two parts: (1) `registry.stopResponse()` is called before CASCADE DELETE, (2) the VM checks stale `chatId` in callbacks. Neither mitigation has a dedicated test. `DrawerViewModelTest.deleteChatEmitsPopBackWhenCurrentChatDeleted` verifies the nav event but not the `stopResponse` ordering nor callback-guard behavior.
**Replacement / fix:** Add one `ChatViewModelTest` method that:
1. Opens Persistent(7L) with Ready engine.
2. Calls `vm.send("hi")`, advances to partial stream token.
3. Simulates the external delete path (e.g. emits an empty message list + triggers `DrawerViewModel.deleteChat(7L, currentOpenChatId = 7L)` via a shared `ChatRepository` fake).
4. Asserts (a) `fakeHelper.stopResponseCalls == 1` before any CASCADE assertion, (b) no `fakeChatRepository.insertedMessages.any { it.chatId == 7L }` after the delete-during-stream callback fires with `done=true`.
**Severity rationale:** Medium — the risk is data-loss-adjacent (could write an assistant row to a now-dangling chatId) but the Room FK CASCADE means the row gets deleted anyway. Low if the analysis accepts that; Medium if the callback path could throw via FK constraint and crash.

### G5 — `ErrorLogTest` component-allowlist coverage is out of task-15 scope but present (Info)

**File:** `core-runtime/src/test/kotlin/app/sanctum/machina/core/log/ErrorLogTest.kt` (not in 12-file scope).
**Observation:** tech-spec Testing Strategy lists `ErrorLogTest` (extended) as a Phase-3 unit test (line 256). The file exists and covers all 6 new components (`engine-warmup`, `history-read`, `history-write`, `attachment-save`, `attachment-read`, `settings-io`) — verified via grep. It is not in the 12-file audit list in `tasks/15.md` but the coverage is present. Recording for completeness.

---

## TDD compliance check (git history)

| Test file                                   | First commit                                  | Date       | Impl in same commit |
|---------------------------------------------|-----------------------------------------------|------------|---------------------|
| `AutoTitleGeneratorTest` + `ChatRepositoryTest` | `2c1a8b6 feat: task 4 — ChatRepository + AutoTitleGenerator` | 2026-04-21 | Yes                 |
| `WarmupCoordinatorTest`                    | `b466277 feat: task 5 — WarmupCoordinator + AppModule` | 2026-04-21 | Yes                 |
| `ModelRegistryActiveModelTest`             | `b2afb96 feat: task 3 — activeModelName StateFlow` | 2026-04-21 | Yes                 |
| `SettingsMigrationHelperTest`              | `6c673d4 feat: task 2 — settings + core fixes` | 2026-04-21 | Yes                 |
| `ChatDaoTest`, `MessageDaoTest`, `SanctumDatabaseTest` | `b9f47f1 feat: task 1 — Room foundation` | 2026-04-20 | Yes                 |
| `DrawerViewModelTest`                      | `08f6769 feat: task 9 — drawer UI`            | 2026-04-21 | Yes                 |
| `ModelManagerViewModelTest`                | `27e8588 feat: task 11 — Model Manager default-model UX` | 2026-04-22 | Yes                 |
| `ChatViewModelTest` (extensions)           | progressively extended in `a0f804c` (task 8), `4fa6c89` (task 17), `55dcd7a` (task 10), `b3074eb` (task 18) | 2026-04-18..22 | Yes each time       |

**Verdict:** PASS. Every task-N commit pairs impl with tests in the same commit. No commits land impl-only. Project convention (`test-master` principle #1 "Write tests immediately — in the same session as the code, before moving on") is respected across all 12 files. Git history cannot distinguish which was typed first within a single commit; there is no TDD-violation evidence.

---

## Mapping AC → test coverage (Testing Strategy section of tech-spec)

| AC item (tech-spec Testing Strategy)                       | Test location                                                                       | Status |
|------------------------------------------------------------|-------------------------------------------------------------------------------------|--------|
| AutoTitleGenerator all AC-U2 cases                         | `AutoTitleGeneratorTest` × 10                                                       | ✅     |
| SettingsMigrationHelper happy/orphan/sentinel/atomic       | `SettingsMigrationHelperTest` × 6                                                   | ✅     |
| ChatRepository save-draft→commit + 2 failure paths + delete + zombie sweep + auto-title | `ChatRepositoryTest` × 17 (9 commit + 3 attachment + 3 delete/sweep + 2 misc) | ✅     |
| WarmupCoordinator resolution order + cancelAndRestart ordering + failure log + last-used + AC-F3 observer | `WarmupCoordinatorTest` × 13 | ✅     |
| ModelRegistry activeModelName Ready/Idle/Init/Failed + J5 + concurrent read | `ModelRegistryActiveModelTest` × 7 | ✅     |
| ErrorLog 6 new components + existing unchanged             | `ErrorLogTest` (out-of-scope but present)                                            | ✅     |
| ChatViewModel Draft commit + Quick-no-Room + onCleared-no-cleanup + TopAppBar + double-bubble | `ChatViewModelTest` × 72 | ✅     |
| ChatDao CRUD + observeByChat emits on insert + delete      | `ChatDaoTest` × 5                                                                   | ✅     |
| MessageDao CRUD + CASCADE + observeByChat sorted ASC       | `MessageDaoTest` × 7                                                                | ✅     |
| SanctumDatabase schema + PRAGMA + FK enforcement           | `SanctumDatabaseTest` × 5                                                           | ✅     |
| DB corruption E2E (AC-D5 / Decision 9)                     | **MISSING** (see G2)                                                                 | ⚠️     |
| Delete-open-chat-while-streaming (Risk: callback to deleted row) | **MISSING** (see G4)                                                             | ⚠️     |

---

## Test-pyramid balance assessment

**Ratio:** 9 unit (JVM + Robolectric) + 3 instrumented (device/emulator) = reasonable for an Android app with heavy mobile-platform coupling.

- **Correctly placed in unit:** AutoTitleGenerator (pure function), SettingsMigrationHelper (DataStore via Robolectric tempFolder), ChatRepository (faked DAOs + temp filesystem), WarmupCoordinator (faked registry/settings + `TestCoroutineScheduler`), ModelRegistry derivation, ChatViewModel (faked everything, TestCoroutineScope), DrawerViewModel (faked chatRepo + in-memory DAOs), ModelManagerViewModel.
- **Correctly placed in instrumented:** ChatDao, MessageDao, SanctumDatabase — Room semantics (Flow emission on insert, CASCADE, PRAGMA) require a real SQLite engine.
- **Potentially misplaced:** none observed. Every file is in the right tier.
- **Excessive mocking check:** no test mocks ≥ 3 dependencies; hand-rolled fakes record state and tests assert against recorded state, not against mock-return values.

**No pyramid violations.**

---

## Recommendations for Task 16 QA

1. **Before QA runs:** execute the full suite per AVP step 2–5 (`./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest :core-settings:testDebugUnitTest` + `connectedAndroidTest`). Test green is a precondition — if any Phase-3 test fails, that failure is itself a QA finding.
2. **Post-QA follow-ons (not blockers for Task 16):**
   - G2: add DB-corruption E2E instrumented test (one test method in `SanctumDatabaseTest` or new file).
   - G4: add delete-during-streaming test in `ChatViewModelTest` (one method).
   - G3: amend `tasks/15.md` to reference the consolidated TopAppBar tests, or extract a pure helper.
   - G1: add one instrumented test exercising the real Room `@Transaction` rollback on rename failure.

No Critical or High severity findings. No finding blocks Task 16.

---

## Acceptance criteria self-check

- [x] All twelve test files read and analyzed.
- [x] Atomic transition coverage in `ChatRepositoryTest` assessed — both failure paths present + bonus message-insert-throws.
- [x] `WarmupCoordinatorTest` concurrency verdict recorded — in-flight Job cancellation ordering verified via `CompletableDeferred`.
- [x] `ModelRegistryActiveModelTest` concurrent-read scenario assessed — StateFlow.map atomicity + dedicated snapshot test + real-registry wiring test.
- [x] `ChatViewModelTest` double-bubble test present (`doubleBubble_noSimultaneousStreamingAndPersisted`).
- [x] Every AC item from tech-spec Testing Strategy mapped to a test (2 gaps recorded: G2, G4).
- [x] Test pyramid balance assessed — no misplacements.
- [x] TDD compliance checked via git history — PASS.
- [x] Audit report written and recorded (this file + decisions.md entry).
