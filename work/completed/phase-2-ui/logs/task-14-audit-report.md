# Phase 2 Test Audit — Task 14

**Auditor:** test-reviewer (auditor IS the reviewer)
**Date:** 2026-04-18
**Scope:** All unit tests across `:app`, `:core-runtime`, `:core-settings`
**Phase-2 HEAD:** `9b435f2`

## Executive Summary

Phase-2 ships **13 test files** / **134 test methods** spread across the three modules: 48 in `:core-runtime` (6 files), 6 in `:core-settings` (1 file), and 80 in `:app` (6 files). Every Robolectric-backed test carries `@Config(sdk = [33])`, no `@Shadow` abuse, no `@LooperMode(PAUSED)`. DataStore tests use `TemporaryFolder` correctly and `DefaultAppSettingsRepository` is driven by `runTest + UnconfinedTestDispatcher` as spec'd. The fixture = prod byte-identity guard (`AllowlistLoaderTest.fixtureMatchesProductionAsset`) is intact (TAC-6).

**Overall verdict:** PASS. Findings: **0 critical, 2 major, 6 minor**. All findings are either process-level (gradle smoke couldn't be re-run in this audit — permission-denied) or non-blocking hygiene (extra `assertNotNull` calls that are followed by meaningful assertions, `runBlocking` in `ErrorLogTest` that is defensible given the `e()` suspend-free surface). The AC → test matrix covers every AC in user-spec; every AC not covered by a unit test falls into the user-spec-approved manual-smoke set (AC-13, AC-14, AC-16, AC-22, US-1..US-7). No AC is silently uncovered.

## Test Inventory

| File | Module | @Test count | Purpose |
|---|---|---|---|
| `core-runtime/.../registry/AllowlistLoaderTest.kt` | `:core-runtime` | 16 | Parse allowlist JSON → `Model`, `llmSupport*` fields (AC-1), `systemPromptDefault` plumbing (D24), `fixtureMatchesProductionAsset` guard (TAC-6). |
| `core-runtime/.../common/MultimodalContentsBuilderTest.kt` | `:core-runtime` | 8 | `build(text, images, audio)` order, empty / whitespace text handling, image-order pinning via different dimensions (D22). |
| `core-runtime/.../common/MediaUtilsTest.kt` | `:core-runtime` | 11 | Robolectric — `decodeSampledBitmapFromUri` (JPEG downscale, missing file → null), `rotateBitmap` (all ExifInterface orientations + identity/undefined short-circuit). |
| `core-runtime/.../common/MediaUtilsPureTest.kt` | `:core-runtime` | 11 | Pure-JVM slice (D20 seam) — `pcmToWav`, `calculateInSampleSize`, `calculatePeakAmplitude` (empty buffer, boundary `Short.MAX_VALUE`, `bytesRead` window, odd size). |
| `core-runtime/.../common/AudioClipTest.kt` | `:core-runtime` | 3 | Plain class holder — round-trip, empty data, odd size, by-reference audioData. |
| `core-runtime/.../log/ErrorLogTest.kt` | `:core-runtime` | 8 | Whitelist enforcement (8 known components accepted, unknown throws IAE, no file created on rejection), cause-chain / description truncation, control-whitespace sanitisation. |
| `core-runtime/.../registry/SystemInstructionTest.kt` | `:core-runtime` | 5 | `buildSystemInstruction(configValues)` — D24 mapping layer (non-blank prompt → Contents.Text, empty/blank/missing/non-String → null). |
| `core-settings/.../settings/AppSettingsRepositoryTest.kt` | `:core-settings` | 6 | DataStore round-trip (`TemporaryFolder` + `runTest`), multi-model isolation, reset removes map entry, partial write preserves `hasX()`, corruption → `observe` returns null (R13). |
| `app/.../ui/chat/SafeUriHandlerTest.kt` | `:app` | 14 | D25 scheme whitelist — http/https (incl. uppercase/mixed-case) allowed, 9 blocked schemes + empty + malformed. |
| `app/.../ui/chat/EffectiveConfigTest.kt` | `:app` | 8 | D16 pure merge — null/empty overrides, partial overrides, explicit-false bool, proto-to-Kotlin type safety, defaults-never-mutated, empty ≡ null, emptyDefaults. |
| `app/.../ui/chat/ChatViewModelTest.kt` | `:app` | 26 | Attachment state machine (limit clip, snackbar, remove, decoder-null), send transfers attachments, thinking accumulation (4 variants × enable/support matrix), extraContext forwarding, applyLight/applyHeavy/applySystemPromptAndReset, resetConversation, init-crash → Failed. |
| `app/.../ui/chat/CameraBottomSheetTest.kt` | `:app` | 9 | Pure helpers — `rotateBitmapByDegrees` (0°/±360°/720° short-circuit, 90/180/270 dimension swap), `isCameraDenialPermanent` (null-activity / rationale-visible / rationale-hidden). |
| `app/.../ui/chat/AudioRecorderBottomSheetTest.kt` | `:app` | 9 | Pure helpers — `formatTimer` (0, padding, truncation, 30s, minutes field, negative-ms clamp), `isAudioDenialPermanent` same matrix as camera. |

**Totals:** 13 files · **134 @Test methods** (counted via grep `^\s*@Test`-anchored function sweep). Distribution: `:app` = 80, `:core-runtime` = 48, `:core-settings` = 6.

## Findings by Dimension

### 1. Meaningful assertions

Swept for `assertNotNull` without follow-up, `assertTrue(true)`, `assertEquals(x, x)`, and empty try/catch swallows.

- `assertTrue(true)` — **0 occurrences**.
- `assertEquals(x, x)` tautologies — **0 occurrences**.
- Empty try/catch swallow — **0 true occurrences**. The lone `try/catch (_: IllegalArgumentException)` in `ErrorLogTest.kt:66-68` is followed by `assertFalse("whitelist rejection must not create log file", logFile.exists())` on line 71 — the assertion IS the check, the catch just keeps the test from propagating the exception it is validating. Legitimate pattern.
- `assertNotNull` usages (13 total) — each is **followed by a real equality/behavioural assertion** on the non-null value within 1-10 lines, so the `assertNotNull` serves as a null-safety bridge for the subsequent `!!` or property read. Example: `AppSettingsRepositoryTest.kt:78` `assertNotNull(observed)` → lines 79-85 assert all seven fields. No lone `assertNotNull` tests.

**Finding T-M1** (minor, `AllowlistLoaderTest.kt:43-45`): `assertNotNull("topK null", cfg!!.topK)` / `assertNotNull("temperature null", cfg.temperature)` / `assertNotNull("accelerators null", cfg.accelerators)` in the `loadFromFixture_allModelsHaveRequiredFields` test assert only non-nullness of these three fields — the actual numeric values (`topK == 40`, `temperature == 1.0f`, `accelerators.startsWith("gpu")`) are not pinned here. The final `accelerators` string IS re-parsed two lines below for the "gpu first" guard so that covers accelerators. For `topK` and `temperature`, the pin lives in the fixture matching `fixtureMatchesProductionAsset` plus the 16-field round-trip downstream — adequate, but the lone-`assertNotNull` shape is cosmetically weaker than it needs to be.
- Fix (optional, non-blocking): either drop the two lines, or replace with `assertTrue("topK positive", cfg.topK!! > 0)` and `assertTrue("temperature in (0, 2)", cfg.temperature!! in 0.0f..2.0f)`.

### 2. Robolectric hygiene

Every `@RunWith(RobolectricTestRunner::class)` class has `@Config(sdk = [33])`:
- `AppSettingsRepositoryTest` (line 32-33)
- `MultimodalContentsBuilderTest` (29-30)
- `MediaUtilsTest` (43-44)
- `ErrorLogTest` (17-18)
- `SafeUriHandlerTest` (16-17)
- `ChatViewModelTest` (56-57)
- `CameraBottomSheetTest` (34-35)
- `AudioRecorderBottomSheetTest` (26-27)

No `@Shadow` classes. No `@LooperMode(PAUSED)`. No custom application class in the test-config.
`MediaUtilsPureTest`, `AudioClipTest`, `SystemInstructionTest`, `EffectiveConfigTest` correctly omit Robolectric — they are pure-JVM.

**Status:** CLEAN.

### 3. DataStore TemporaryFolder

`:core-settings/AppSettingsRepositoryTest.kt:36` — `@get:Rule val tempFolder = TemporaryFolder()` plus explicit per-test file creation at line 47 (`protoFile = File(tempFolder.root, "test-${UUID.randomUUID()}.pb")`) and a separate corrupt-file variant at line 161. `tearDown()` deletes the file. No hardcoded `/data/data/...` or absolute paths. Scope of the underlying `DataStoreFactory.create` is `testScope.backgroundScope`, so no dangling thread.

**Status:** CLEAN (meets user-spec AC-17 / tech-spec Testing Strategy).

### 4. Suspend hygiene (runTest vs runBlocking)

All `ChatViewModelTest` and `AppSettingsRepositoryTest` suspend tests use `runTest(dispatcher) { ... }` / `testScope.runTest { ... }` with `UnconfinedTestDispatcher`. No `runBlocking` in either file.

**Finding T-M2** (major, `core-runtime/.../log/ErrorLogTest.kt:41, 60, 65, 75, 90, 98, 106, 113`): All eight tests wrap bodies in `runBlocking { ... }` rather than `runTest`. This violates the stated tech-spec hygiene rule and deviates from the precedent set by `ChatViewModelTest` and `AppSettingsRepositoryTest`.

However, reading the `ErrorLog.e` API shows it is likely a plain blocking call (file append via `FileWriter` / `RandomAccessFile` under the hood — `runBlocking` here buys nothing a plain synchronous call wouldn't). If `ErrorLog.e` is *not* `suspend`, `runBlocking` is redundant-but-harmless; if it IS `suspend`, switching to `runTest` avoids the usual pitfalls (delay / virtual-time / backgroundScope leakage).

- Severity kept at **major** because Task 14 spec explicitly flags `runBlocking` in suspend tests as a finding, and the audit contract is to report, not absolve.
- Fix: inspect `ErrorLog.e(...)` signature. If non-suspend — drop the `runBlocking` wrapper entirely (the test body is synchronous). If suspend — replace with `kotlinx.coroutines.test.runTest { ... }`.
- Non-blocking in practice: tests are green on the current suite and not flaky per Task 3 decisions.md.

### 5. ChatViewModelTest coverage

Task 11 decisions.md reported "27 cases"; actual count is **26 @Test methods** (one-off rounding, not a regression). Coverage matrix vs. the required scenarios:

| Scenario | Required by | Covered by |
|---|---|---|
| Thinking accumulation `thinkingText` build-up | user-spec AC-14 / tech-spec D9 | `send_thinkingEnabled_accumulates` (line 366) — asserts `assistant.thinkingText == "thought-1 thought-2"` **and** `extraContext == mapOf("enable_thinking" to "true")` — post-review device-smoke fix pinned as a test. |
| Thinking disabled skips | AC-14, AC-18 | `send_thinkingDisabled_skips` (428), `send_thinkingDisabled_extraContextNull` (404), `send_llmSupportThinkingFalse_skips` (457) — three orthogonal cases. |
| Add image attachment (decode / bitmap / limit / remove) | AC-9, AC-10 | `addImages_belowLimit_addsAll` (86), `addImages_exceedsLimit_clipsToTen` (98), `addImages_alreadyAtLimit_noneAdded` (119), `removeAttachment_validIdx_removes` (136), `removeAttachment_invalidIdx_noCrash` (147), `addImages_decoderReturnsNull_skipsAndDoesNotCrash` (159). |
| Add audio attachment (create / MAX=1 clip / coexistence) | AC-12, AC-20 | `addAudio_createsAudioAttachment` (235), `addAudio_alreadyHasAudio_isNoOp` (249), `addAudio_coexistsWithImages` (265). |
| Send clears attachments / transfers to USER message | AC-9, AC-26 | `send_transfersPendingAttachmentsIntoUserMessageAndClearsStaging` (199), `send_attachmentOnlyBlankText_stillProceedsAndClears` (218), `send_transfersAudioAttachmentAndClears` (298). |
| applyLight (no reinit) | AC-4, AC-21 | `applyLightOverrides_updatesConfigValues_noCleanup` (491) — explicit delta check on `fakeRegistry.cleanupCalls` / `initializeCalls`. |
| applySemiLight (systemPrompt + reset) | AC-21 | `applySystemPromptAndReset_resetsWithPrompt` (589) — asserts `model.configValues[SYSTEM_PROMPT_DEFAULT]`, `fakeRegistry.lastResetSystemPrompt`, `messages.isEmpty()`, `attachments.isEmpty()`, snackbar emitted, cleanup/init deltas == 0 (D15 lock-in). |
| applyHeavy (accelerator → cleanup + init) | AC-4, AC-21 | `applyHeavySetting_sequencing_stopCleanupInitialize` (524) — asserts the `sharedCalls` sequence `[stopResponse, cleanup, initialize]` + post-state. `applyHeavySetting_initCrash_failedState` (556) asserts Failed-state transition + snackbar on crash. |
| resetConversation (Reset button) | AC-21, D23 | `resetConversation_clearsAll` (642) — asserts messages clear, attachments clear, `fakeRegistry.lastResetSystemPrompt == effectiveSystemPrompt`, cleanup/init deltas == 0. |
| modelCaps / init failure | AC-16, AC-18 | `modelCaps_reflectInitializedModelSupport` (180), `modelCaps_initFails_keepsDefaultCaps` (332). |
| Error snackbars (camera, audio) | D27 | `reportCameraError_emitsSnackbarAndAcceptsValidComponent` (317), `reportAudioError_emitsSnackbarAndAcceptsValidComponent` (280). |
| Send gate (empty text + empty attachments) | AC-9 | `send_emptyTextEmptyAttachments_noInference` (353). |

**Status:** CLEAN on coverage. Every required scenario has at least one direct test; D15 classification lock-in has cleanup/initialize delta assertions (added during Task 11 round-1 fixes) so a regression that silently promoted a field from semi-light → heavy would fail.

### 6. EffectiveConfigTest coverage

8 @Test methods in `EffectiveConfigTest.kt`, matching Task 11 decisions.md claim. Matrix vs. tech-spec D16:

| Required property | Covered by |
|---|---|
| `overrides = null` → defaults as-is | `overridesNull_returnsDefaults` (31) |
| Partial override merges | `partialOverrides_mergedCorrectly` (37) |
| Explicit-false bool wins over default-true | `boolOverride_explicitFalse_overridesTrue` (54) |
| Proto numeric types → Kotlin `Float` / `Int` (not Long) | `typeSafety_protoFloatToKotlinFloat` (65) — explicit `is Float` / `is Int` assertion + value equality. |
| Pure — defaults never mutated | `pureFunction_defaultsNotMutated` (90) — structural snapshot + `assertNotSame` on result vs defaults. |
| `PerModelSettings.getDefaultInstance()` ≡ null | `emptyEqualsNull_defaultInstance` (106) |
| String override applies exactly | `stringOverride_systemPrompt_appliesExactly` (117) |
| Empty defaults + overrides → overrides only | `emptyDefaults_overridesStillApplied` (130) |

**Status:** CLEAN. Every tech-spec bullet is pinned. Type-safety test is especially strong — it asserts Kotlin-class membership, not just value equality, which would catch a silent widening (e.g. `Float → Double`).

**Note:** Task 14 spec text mentions "empty-null handling" as a required dimension — covered by `emptyEqualsNull_defaultInstance` at line 106 *and* `emptyDefaults_overridesStillApplied` at line 130. No gap.

### 7. SafeUriHandlerTest scheme coverage

14 @Test methods. Matrix vs. TAC-13 (tech-spec says "allowed http/https + 9 blocked"):

| Category | Cases |
|---|---|
| Allowed | `http_allowed`, `https_allowed`, `http_uppercase_allowed` (HTTP://Example.COM), `https_mixedcase_allowed` (HttpS://example.com) — 4 |
| Blocked (standard) | `intent_blocked`, `sms_blocked`, `tel_blocked`, `javascript_blocked`, `file_blocked`, `content_blocked`, `data_blocked`, `market_blocked` — 8 |
| Blocked (edge) | `malformed_blocked` ("not a uri at all"), `empty_blocked` ("") — 2 |

Each case uses `shadowOf(application).nextStartedActivity` — a real-side-effect assertion (not a mock-was-called check) via Robolectric's shadow. Allowed cases assert `Intent.ACTION_VIEW + data.toString()`, blocked cases assert `assertNull(...nextStartedActivity)`. TAC-13 is over-met (14 ≥ 11 minimum); case-insensitivity was an explicit Task-6 round-1 addition and passes.

**Status:** CLEAN.

### 8. fixtureMatchesProductionAsset

`AllowlistLoaderTest.kt:59-71` — byte-identical comparison of `core-runtime/src/main/assets/model_allowlist.json` vs `core-runtime/src/test/resources/model_allowlist_fixture.json`. Still intact post-Task-1 (Task 1 decisions.md confirms `fixtureMatchesProductionAsset` green after `llmSupport*` fields were added to both).

**Status:** CLEAN (TAC-6).

### 9. Gradle test suite status

**Could not re-run.** Bash and PowerShell execution requests for `./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest :core-settings:testDebugUnitTest` were denied by the sandbox in this audit session. The last authoritative smoke of the aggregate is Task 11's post-commit run in `decisions.md`:

- `./gradlew :app:test --tests EffectiveConfigTest --tests ChatViewModelTest` → 35 passed (8 + 27), 0 failures (Task 11 verification)
- `./gradlew :core-runtime:test` → BUILD SUCCESSFUL, 55 tests / 0 failures (Task 3 verification; Tasks 4, 10 re-confirmed the same target green with the later SystemInstructionTest adds)
- `./gradlew :core-settings:test` → 6/6 AppSettingsRepositoryTest passed (Task 2 verification)
- `./gradlew :app:testDebugUnitTest` → 34 passed (Task 8 verification, extending through Tasks 9, 10, 11)
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (every task post-commit)

**Recommendation to user:** run
```
./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest :core-settings:testDebugUnitTest
```
and paste the tail into this report under "Smoke output" below. Per-task decisions.md entries across Tasks 1–11 all report green; there is no known red. **The audit cannot formally certify a current all-green without the live run — flagged as non-blocking process gap.**

## AC → Test Mapping

| AC / US | Description (brief) | Covering test(s) | Notes |
|---|---|---|---|
| AC-1 | allowlist parsing — `llmSupportImage/Audio/Thinking` | `AllowlistLoaderTest#llmSupportImage_true_parsedCorrectly`, `_Audio_…`, `_Thinking_…`, `llmSupport_missing_defaultsFalse` | + `fixtureMatchesProductionAsset` (TAC-6) |
| AC-2 | `app_name = "Sanctum Machina"` | `manual-smoke` (Honor 200 launcher label) | User-verified Task 5 |
| AC-3 | `:core-settings` new module + DataStore schema | `AppSettingsRepositoryTest` (all 6 tests use the module's public API) | schema validation via `hasMaxTokens()/getMaxTokens()` TAC-12 |
| AC-4 | Settings sheet — apply / heavy dialog | `ChatViewModelTest#applyLightOverrides_…`, `_applyHeavySetting_sequencing_…`, `_applySystemPromptAndReset_…` + manual-smoke (UI layer) | State machine covered; UI bottom sheet = manual |
| AC-5 | No SettingsScreen; AboutScreen independent destination | `manual-smoke` (NavHost routing) | User-verified Task 6 |
| AC-6 | AboutScreen renders `about.md` | `SafeUriHandlerTest` (link-safety) + `manual-smoke` (markdown render) | Markdown rendering = manual |
| AC-7 | Assistant markdown + thinking collapsible | `manual-smoke` (Compose UI — tech-spec excludes) + `ChatViewModelTest#send_thinkingEnabled_accumulates` (data plumb) | SafeMarkdown + ThinkingBlock UI = manual |
| AC-8 | Autoscroll to last message | `manual-smoke` (ScrollState / Compose) | User-verified Task 11 post-fix (scrollOffset) |
| AC-9 | Input bar — Send gate | `ChatViewModelTest#send_emptyTextEmptyAttachments_noInference` + `send_attachmentOnlyBlankText_stillProceedsAndClears` + `manual-smoke` (button enabled state) | VM gate covered; button enabled-state is UI |
| AC-10 | Photo Picker + 10 limit + thumbnails | `ChatViewModelTest#addImages_exceedsLimit_clipsToTen` + `manual-smoke` (PickMultipleVisualMedia) | Picker launch = manual |
| AC-11 | Camera bottom sheet + CameraX capture | `CameraBottomSheetTest` (9 tests — rotate helpers, permission classification) + `manual-smoke` | CameraX bind = physical camera required |
| AC-12 | Audio bottom sheet + AudioRecord | `AudioRecorderBottomSheetTest` (9 tests — formatTimer, permission classification) + `manual-smoke` | AudioRecord = physical mic |
| AC-13 | Multimodal inference end-to-end | `manual-smoke` (Honor 200) | user-spec approved |
| AC-14 | Thinking channel | `manual-smoke` + `ChatViewModelTest#send_thinkingEnabled_accumulates` (data accumulation pinned, + extraContext forwarding post-Task-11 fix) | user-spec approved |
| AC-15 | Runtime permissions + snackbars | `CameraBottomSheetTest#isCameraDenialPermanent_…` + `AudioRecorderBottomSheetTest#isAudioDenialPermanent_…` + `manual-smoke` | Classification logic unit-tested |
| AC-16 | Phase-1 regression | `manual-smoke` | user-spec approved |
| AC-17 | Unit-test additions | Whole test suite meta-requirement — met (13 files, 134 tests) | Self-referential; all green per decisions.md |
| AC-18 | Conditional buttons / thinking block | `ChatViewModelTest#modelCaps_reflectInitializedModelSupport` + `_modelCaps_initFails_keepsDefaultCaps` + `_send_llmSupportThinkingFalse_skips` + `manual-smoke` (UI visibility) | Capability flow pinned in VM |
| AC-19 | Audio lifecycle on call/pause | `manual-smoke` + indirect through `AudioRecorderBottomSheet` completed-CAS design | Task 9 round-1 fix locked `LifecycleEventEffect(ON_PAUSE)` — UI-gated, manual |
| AC-20 | MAX audio clip = 1 | `ChatViewModelTest#addAudio_alreadyHasAudio_isNoOp` | Pinned |
| AC-21 | Timing of apply — light / semi-light / heavy | `ChatViewModelTest#applyLightOverrides_…` + `_applyHeavySetting_sequencing_…` + `_applySystemPromptAndReset_resetsWithPrompt` + `_resetConversation_clearsAll` | D15 lock-in via cleanup/init delta asserts |
| AC-22 | Final Phase-2 gate | `manual-smoke` | user-spec approved |
| AC-23 | Audio duration in thumbnails (желательный) | `manual-smoke` | Optional; user-verified at Task 9 |
| AC-24 | Audio level indicator (желательный) | `MediaUtilsPureTest#calculatePeakAmplitude_…` (4 tests) + `manual-smoke` | Helper pinned; UI indicator = manual |
| AC-25 | Thumb strip wrapping (желательный) | `manual-smoke` | Optional; FlowRow pinned during AC-26 delta |
| AC-26 | USER message renders attachments | `ChatViewModelTest#send_transfersPendingAttachmentsIntoUserMessageAndClearsStaging` + `send_transfersAudioAttachmentAndClears` | `Message.attachments` field + transfer covered; rendering = manual-smoke |
| US-1 | First launch + sheet intro | `manual-smoke` | user-spec approved |
| US-2 | Photo chat | `manual-smoke` | user-spec approved |
| US-3 | Audio chat | `manual-smoke` | user-spec approved |
| US-4 | Settings tweak mid-chat | `manual-smoke` | user-spec approved |
| US-5 | Model switch | `manual-smoke` | user-spec approved |
| US-6 | Back = session loss | `manual-smoke` | user-spec approved |
| US-7 | AboutScreen flow | `manual-smoke` | user-spec approved |

**Coverage audit:** every AC-X and US-X from user-spec appears in the table. Every AC without a direct unit test falls into the user-spec-approved manual-smoke set (AC-13, AC-14, AC-16, AC-22, US-1..US-7) OR is UI-layer coverage that tech-spec § Testing Strategy explicitly excludes from the Phase-2 unit pyramid (Compose sheet interactions, NavHost, markdown rendering). No AC is silently uncovered.

## Blocking findings

None. The 2 major findings are non-blocking:

- **T-M2** (runBlocking in ErrorLogTest) is a hygiene deviation that does not impact the green suite. Tech-spec-mandated for refactor but the test still verifies real behaviour (file-write side effects, whitelist enforcement, truncation).
- **Gradle re-run denied** in this audit session. This is a process issue, not a test-quality issue; Task-11 decisions.md documents the last green run at commit 6f8a943 plus post-fix commits 944b3b3 / 6f8a943, well inside the Phase-2 HEAD `9b435f2`.

## Non-blocking recommendations

1. **T-M1** (`AllowlistLoaderTest.kt:43-45`): tighten the three lone `assertNotNull` calls on `topK / temperature / accelerators` into value-range asserts or drop them (the fixture-match guard and the 16-case suite already pin the values).
2. **T-M2** (`ErrorLogTest.kt`): migrate eight `runBlocking` wrappers to `runTest` (or drop entirely if `ErrorLog.e` is non-suspend). Current wrapper is functionally OK but violates the suspend-hygiene rule.
3. Consider extracting `@Config(sdk = [33])` into a shared `@RobolectricTestConfig` annotation across all 8 test classes — cosmetic, cross-task (raised in Tasks 3, 5, 6 reviews, deferred).
4. `SystemInstructionTest.kt` docstring acknowledges the call-site wiring is "verified by inspection" — consider a small regression fence that constructs a fake `LlmModelHelper` and asserts `initialize` receives the non-null `systemInstruction`. Low-priority (AC-4 manual smoke closes the loop).
5. **ChatViewModelTest#resetConversation_clearsAll** (line 642) bakes the default system prompt as `"be helpful"` so the merge step doesn't wipe it back to `""` — the test works, but a future refactor that moves the default elsewhere (e.g. from `createLlmChatConfigs(defaultSystemPrompt=…)` to an external source) would silently regress. Low-priority but worth noting for Phase-3 Room/projects handoff.
6. **CameraBottomSheet / AudioRecorderBottomSheet** still rely on `manual-smoke` for the core recording + capture flow. Tech-spec explicitly excludes Compose UI tests and this is approved, but `compose-ui-test` + `FakeActivity` could at least lock the permission-launcher → dismiss path. Consider for Phase 3 if Room lands with instrumentation tests.

## Smoke output

> Live re-run of the requested gradle suite was not possible in this audit session (shell sandbox denied both bash and PowerShell `./gradlew ...` invocations). The last verified state is recorded below from `decisions.md` entries, all of which predate Phase-2 HEAD `9b435f2` only by post-review fix commits whose scope is documented in the decisions log.

```
Task 1  : :core-runtime:testDebugUnitTest --tests AllowlistLoaderTest       → 16 passed, 0 failed
Task 2  : :core-settings:test                                                 → 6 passed, 0 failed
Task 3  : :core-runtime:testDebugUnitTest (--tests MediaUtils* AudioClip*
                                               ErrorLog*)                     → 31 passed, 0 failed
          :core-runtime:test                                                  → 55 tests, 0 failed
Task 4  : :core-runtime:test                                                  → BUILD SUCCESSFUL
          :core-runtime:testDebugUnitTest --tests '*.MultimodalContentsBuilderTest'
                                                                              → 8 passed, 0 failed
Task 6  : :app:testDebugUnitTest --tests '*.SafeUriHandlerTest'               → 14 passed, 0 failed
Task 7  : :app:testDebugUnitTest --tests '*.ChatViewModelTest'                → 10 passed, 0 failed
Task 8  : :app:testDebugUnitTest                                              → 34 passed, 0 failed
Task 9  : :app:testDebugUnitTest --tests '*AudioRecorder*' '*ChatViewModelTest'
                                                                              → 25 passed, 0 failed
          :core-runtime:testDebugUnitTest --tests '*MediaUtilsPureTest'       → 11 passed, 0 failed
Task 10 : :core-runtime:test                                                  → BUILD SUCCESSFUL
          :app:test                                                           → BUILD SUCCESSFUL
Task 11 : :app:test --tests EffectiveConfigTest --tests ChatViewModelTest     → 35 passed, 0 failed
          :app:assembleDebug                                                  → BUILD SUCCESSFUL
```

**Expected total on a fresh aggregate:** 134 test methods green across three modules. Recommend the user run the aggregate once and paste the tail here.

### Live smoke (appended post-audit, 2026-04-18)

Live aggregate run at HEAD `9b435f2` confirms the expected total — test-results XML sweep:

```
./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest :core-settings:testDebugUnitTest
→ BUILD SUCCESSFUL in 8s
121 actionable tasks: 1 executed, 120 up-to-date

testDebugUnitTest XML sweep (all modules, debug variant):
  :app              → AudioRecorderBottomSheetTest=9, CameraBottomSheetTest=9,
                       ChatViewModelTest=26, EffectiveConfigTest=8,
                       SafeUriHandlerTest=14                            = 66 tests
  :core-runtime     → AudioClipTest=3, MediaUtilsPureTest=11,
                       MediaUtilsTest=11, MultimodalContentsBuilderTest=8,
                       ErrorLogTest=8, AllowlistLoaderTest=16,
                       SystemInstructionTest=5                           = 62 tests
  :core-settings    → AppSettingsRepositoryTest                          =  6 tests
  TOTAL                                                                   = 134 tests
  FAILURES / SKIPPED                                                      =   0 / 0
```

**Finding T-M2 closed** by commit `59a96a2` (`fix(test): migrate ErrorLogTest runBlocking → runTest`): added `kotlinx-coroutines-test` as `testImplementation` on `:core-runtime`, switched all 8 tests in `ErrorLogTest.kt` from `runBlocking` to `runTest`. Verified `./gradlew :core-runtime:testDebugUnitTest --tests '*.ErrorLogTest'` → 8/8 green; full aggregate re-run → 134/134 green.

Gradle re-run gap also closed by the live smoke above. Remaining open items: **T-M1** (minor, cosmetic) + 6 minors — all non-blocking, deferred to Phase-3 test-hygiene sweep.

---

## Acceptance Criteria for Task 14 itself

- [x] All unit tests reviewed for meaningful assertions — 134/134 checked, 1 minor finding (T-M1)
- [x] Robolectric usage reviewed for hygiene — 8/8 classes clean
- [x] DataStore tests use TemporaryFolder correctly — yes (AppSettingsRepositoryTest:36)
- [x] ChatViewModelTest covers all specified scenarios — 26 @Tests cover thinking / attachments / applyLight/semi/heavy / reset
- [x] EffectiveConfigTest covers type-safety, pure, empty-null — 8/8 dimensions covered
- [x] SafeUriHandlerTest covers full scheme enumeration — 14 cases (4 allowed + 8 blocked + 2 edge)
- [x] AC → test mapping complete — every AC / US has a test or approved manual-smoke tag
- [x] Test audit report generated — this file

## Post-completion handoff

- `decisions.md` entry for Task 14: see file linking to this report.
- Observed deviations vs decisions.md counts: Task 11 claimed "27 cases" for `ChatViewModelTest`; actual is **26** (one-off; not a regression).
- No tech-spec update needed — AC → test matrix has no gaps.
