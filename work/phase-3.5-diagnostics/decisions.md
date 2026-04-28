# Decisions Log: phase-3.5-diagnostics

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Task 1: Allowlist mapping + recalibration + parser rejection

**Status:** Done
**Commit:** 91e097a (impl) + 056cd8f (review polish)
**Agent:** main agent
**Summary:** Wired `minDeviceMemoryInGb` end-to-end (`AllowedModel` → `Model`), recalibrated bundled allowlist (E2B 8 → 4, E4B 12 → 6 — Decision 4), and added fail-loud parser checks (non-null + range `1..64`) with `ErrorLog.e("download", …)` rejection logging on `load()`. Strategy choice: **aggregate fail-fast** in `parse()` — one bad record drops the whole batch, so the registry never loads a partially-trusted allowlist; documented in companion KDoc above `parse()`. Test seam `AllowlistLoader.loadFromStream(stream)` (`@VisibleForTesting internal`) drives the load → reject → log path under Robolectric without overriding bundled assets.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 3 nits → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: approved → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- test-reviewer: approved, 4 nits → [logs/working/task-1/test-reviewer-1.json](logs/working/task-1/test-reviewer-1.json)

Polish-pass commit (056cd8f) addressed the two highest-value nits: dropped the duplicate "model X rejected:" prefix from parser messages so the wrapped ErrorLog line reads cleanly; loosened the test assertion from `[download]` to `download` (decoupled from ErrorLog's bracket format) and added modelId substring checks to the `_negative` / `_tooLarge` range tests for parity with `_zero`. Remaining nits (KDoc wording, IO-error logging, `1..64` constant extraction) left as author-discretion.

**Verification:**
- `./gradlew :core-runtime:testDebugUnitTest --tests "app.sanctum.machina.core.registry.AllowlistLoaderTest"` → 25/25 passed (8 existing positive + 5 existing negative + 12 new TDD anchor tests).
- `./gradlew assembleDebug` → BUILD SUCCESSFUL (full project compiles after `AllowlistLoader` ctor change + `ModelRegistryActiveModelTest:214` update).
- User-facing smoke deferred to Task 5 (Model Manager gate UI) per task spec — no UI surface delivered by this task.

## Task 2: Gradle git-describe versionName via convention plugin

**Status:** Done
**Commit:** 730bed3 (impl) + 0a55790 (review polish)
**Agent:** main agent
**Summary:** Wired `versionName` to `git describe --tags --always --dirty=-dev` via a `phonewrap.git-version` convention plugin in a new `build-logic/` included build (Decision 10). Split into `providers.exec` (config-cache-friendly Gradle Provider) and pure-fun `gitVersionParse(stdout, exitCode): String?` (testable JUnit seam, 6 fixtures from TDD anchor). `app/build.gradle.kts` consumes via `the<GitVersionExtension>().versionName.orNull ?: "v0.3.5-diagnostics-fallback"`.
**Deviations:** Added `!**/src/**` to `.gitignore` — `**/build/` was eating the new `app.sanctum.machina.build` package path; surgical fix prevents the same trap for any future Kotlin package containing a `build` segment. Kept `Provider<String>` (with null = absent) instead of the `abstract class GitVersionExtension { abstract val versionName: Property<String> }` Gradle 8.x idiom — the orNull-on-Provider pattern is short, the KDoc documents the null contract, and Property<String?> isn't natively supported.

**Reviews:**

*Round 1:*
- code-reviewer: approved with 5 minor suggestions → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: approved, zero findings → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: passed, 3 documentary nits → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

Polish-pass commit (0a55790) addressed two highest-value suggestions: (1) plugin's silent `catch` now logs at `info` so unexpected failures (Gradle API drift) are distinguishable from expected non-git environments; (2) build-logic JUnit dep migrated to `gradle/libs.versions.toml` via included-build `versionCatalogs` declaration, per tech-spec § Files line 89. Stylistic suggestions on `abstract class` idiom, narrower `!**/src/main|test/**`, and `.zip(...)` chaining left as-is (rationale above).

**Verification:**
- `./gradlew -p build-logic test` → 6/6 fixtures green (tagged_clean / tagged_with_commits / tagged_dirty / empty_stdout_returns_null / nonzero_exit_returns_null / git_error_code_returns_null).
- `./gradlew :app:assembleDebug --configuration-cache` → BUILD SUCCESSFUL, "Configuration cache entry stored." on first run, "Configuration cache entry reused." on second; zero serialization warnings; zero git-related warnings (all warnings are pre-existing Kotlin context-receiver / Compose deprecations).
- `aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep versionName` → `v0.3-history-25-g730bed3-dev`, matches `git describe --tags --always --dirty=-dev` exactly.
- Fallback path covered by unit fixtures `empty_stdout_returns_null` + `nonzero_exit_returns_null`; live `.git/`-rename smoke skipped per task spec to keep working tree clean.
- User-facing surface (7-tap About-screen `BuildConfig.VERSION_NAME`) deferred to Pre-deploy QA Task 12.

## Task 3: LogcatReader argv `*:E` → `*:W`

**Status:** Done
**Commit:** 0323fcb
**Agent:** main agent
**Summary:** One-token widen of the logcat filter from ERROR to WARN in `LogcatReader.kt:37` to capture pre-mortem WARN entries (AC-L1). Pinning unit test `argvShape_exactlySixArgs_knownPositions` updated synchronously to `assertEquals("*:W", argv[5])`; remaining five tests (placeholder semantics, happy-path) untouched per AC-L2. `LogExportManager.MAX_LOGCAT_BYTES = 100 KB` left untouched — tail-truncation continues to preserve the freshest hits, mitigating the `*:W`-flood risk on noisy OEMs (AC-L3 deferred to Pre-deploy QA Task 12).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 1 nit (stale "at ERROR level" KDoc on `LogcatReader.kt:12` — task explicitly forbade other edits) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- test-reviewer: approved, 1 nit (same stale KDoc) → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "*LogcatReaderTest*"` → 6/6 passed (1 updated argv-pin + 5 unchanged: empty / non-zero exit / null-exit-no-timeout / timeout / happy-path).
- Red→green confirmed: test-first edit produced `org.junit.ComparisonFailure at LogcatReaderTest.kt:102` before the production token flip; turned green immediately after.
- User-facing verification of WARN content + 100 KB cap behavior under flood deferred to Pre-deploy QA Task 12 on Honor 200 per task spec.

## Task 4: InitDiagnostics seam + DiagnosticsState + Hilt module

**Status:** Done
**Commit:** 692e7e1 (impl) + ed79677 (review polish)
**Agent:** main agent
**Summary:** Created the write-only `InitDiagnostics` interface in `:core-runtime/registry/` (Decision 6 — same `interface-в-:core-runtime + @Binds-в-:app` shape used by `LogExportModule`), with `DiagnosticsState` (`@Singleton`, `AtomicReference<InitSnapshot?>`) implementing it in `:app/diagnostics/` and `DiagnosticsModule` doing the `@Binds` (Decision 7 — CAS-correct read-modify-write on `onInitEnd`, visibility between writer-Default and reader-Main). `NoOpInitDiagnostics` test fake lives in `:core-runtime/test/` so `DefaultModelRegistryTest` (Task 6) can isolate without pulling `:app`. No consumers wired in this task — pure seam.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 4 minor doc/style suggestions → [logs/working/task-4/code-reviewer-1.json](logs/working/task-4/code-reviewer-1.json)
- security-auditor: approved, zero findings → [logs/working/task-4/security-auditor-1.json](logs/working/task-4/security-auditor-1.json)
- test-reviewer: passed, 2 minor recommendations → [logs/working/task-4/test-reviewer-1.json](logs/working/task-4/test-reviewer-1.json)

Polish-pass commit (ed79677) picked up the two highest-signal suggestions: (1) `InitDiagnostics` KDoc now documents the two-event protocol (one start, one end; CPU fallback shares the original start) so future drift toward N-progress events has to argue against the contract; (2) `concurrentWriterReaderNeverSeesMixedState` got a KDoc clarifying it guards visibility + snapshot atomicity (CAS lost-update is structural to `AtomicReference` per Decision 7, single-writer race is excluded by `lifecycleMutex`) plus an `observedNonNull` counter that fails the test if scheduling left the reader seeing only `null` — guards against the test silently becoming vacuous. Remaining nits (writer-side error propagation symmetry, `val` getter for `lastInitSnapshot`, symmetric `onInitEnd` overwrite test) left as-is — non-load-bearing in current production scope.

**Verification:**
- `./gradlew :core-runtime:test :app:test` → BUILD SUCCESSFUL; `DiagnosticsStateTest` 7/7 green (initialStateIsNull, onInitStartProducesInProgressSnapshot, onInitEndTrueProducesOkAndPreservesFields, onInitEndFalseProducesFailedAndPreservesFields, onInitEndWithoutStartIsNoop, secondOnInitStartReplacesPreviousAttempt, concurrentWriterReaderNeverSeesMixedState — 10 000 iterations, observedNonNull > 0 after polish-pass).
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL — confirms Hilt graph validation: KSP found `@Binds bindInitDiagnostics`, `DiagnosticsState` resolves as `InitDiagnostics` in `SingletonComponent`.
- `grep -rEn "app\.sanctum\.machina\.diagnostics" core-runtime/src/main` → zero matches (module boundary held).
- `grep -rEn "androidx.compose|androidx.activity" core-runtime/src/main` → zero matches (TAC-7 regression gate clean).
- User-facing verification deferred to Task 8 (DiagnosticsScreen) and Pre-deploy QA Task 12 (Honor 200 device smoke) per task spec — no UI surface delivered by this seam-only task.

## Task 5: Pre-flight gate logic + Model Manager UI

**Status:** Done
**Commit:** 3016bb3 (impl) + 7542c2d (review polish)
**Agent:** main agent
**Summary:** Slice 1 of Phase 3.5: top-level pure-funs `gateAllowsDownload` / `formatRamShortage` + `internal formatGbFloor` (cross-file-reused by Task 8) live in a new `GateDecision.kt` together with the `GateDecision` / `ModelRowState` data classes. `ModelManagerViewModel` now `@Inject`s `DeviceInfoProvider` (resolved by the existing `LogExportModule` `@Binds` — no DI changes), reads `totalMemoryBytes()` once at property-init, and exposes `rows: StateFlow<List<ModelRowState>>` via `Eagerly` `stateIn` over `registry.models.map(::toRowState)`. `onDownload` short-circuits before `registry.download(...).launchIn(viewModelScope)` when `!gateAllowsDownload(...)`, matching the same predicate the disabled UI button uses (defence-in-depth, AC). The Composable chain (`ModelList` → `ModelCard` → `ModelStatusSection`) threads `GateDecision`; `NOT_DOWNLOADED` / `PARTIALLY_DOWNLOADED` renders disabled-`Button` + `Text(formatRamShortage(...))` on sub-threshold devices and stays identical on passing ones. `R.string.model_gate_secondary` is added as a localization-reserve only — Phase 3.5 UI reads from `formatRamShortage` (visible-text source-of-truth), so the resource carries `tools:ignore="UnusedResources"`.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, 3 minor → [logs/working/task-5/code-reviewer-1.json](logs/working/task-5/code-reviewer-1.json)
- security-auditor: approved, zero findings → [logs/working/task-5/security-auditor-1.json](logs/working/task-5/security-auditor-1.json)
- test-reviewer: passed, 2 minor → [logs/working/task-5/test-reviewer-1.json](logs/working/task-5/test-reviewer-1.json)

Polish-pass commit (7542c2d) picked up the highest-signal items: dropped the now-dead `@Deprecated val models` alias (zero callers — only `WarmupCoordinator` uses `registry.models` directly); deduped the row-state mapping into a private `toRowState(entry)` helper instead of repeating the `GateDecision`-build block in both `.map` and `initialValue`; added `tools:ignore="UnusedResources"` on the localization-reserve string; tightened the two `assertTrue(actual.contains(...))` cases in `FormatRamShortageTest` to full `assertEquals` and the `row_above_threshold_has_gate_allowed` assertion to compare the full `GateDecision`. Skipped suggestions: none load-bearing.

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "*GateAllowsDownloadTest*" --tests "*FormatRamShortageTest*" --tests "*ModelManagerViewModelTest*"` → 21/21 green (8 + 4 + 9; legacy 5 VM tests still pass with the new `DeviceInfoProvider` parameter).
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL — Hilt graph resolves `DeviceInfoProvider` through the existing `AndroidDeviceInfoProvider` `@Binds`; KSP runs clean.
- Red→green confirmed: `:app:compileDebugUnitTestKotlin` failed with 33 unresolved references (`gateAllowsDownload`, `vm.rows`, `gate`, new ctor signature) before implementation; turned green after.
- User-facing smoke (sub-threshold path with `model_allowlist.json` E4B `minDeviceMemoryInGb = 99` override on Honor 200) deferred to Pre-deploy QA Task 12 per the Honor-200 device-matrix policy and the project-memory note "Verify UI chain before device smoke".

## Task 6: Wire InitDiagnostics into DefaultModelRegistry.initialize

**Status:** Done
**Commit:** 207c30b (impl) + 1c92b48 (review polish)
**Agent:** main agent
**Summary:** Plumbed the `InitDiagnostics` seam from Task 4 into `DefaultModelRegistry.initialize`. Added `@Inject`-injected `initDiagnostics: InitDiagnostics` parameter; under `lifecycleMutex.withLock` — after `releaseEngine(model)` and before the first `awaitInitialize` — the registry reads `availMem` via the already-injected `@ApplicationContext` (Decision 9, no new DI seam) and emits exactly one `onInitStart(modelName, freeRamBytes, atEpochMs)` per init attempt. `onInitEnd(true)` fires on the GPU and CPU-fallback success arms — one snapshot per attempt, never two (Decision 8); `onInitEnd(false)` fires on the full-failure arm after `errorLog.e` and `updateEntry { Failed }`. The `CancellationException` arm intentionally skips `onInitEnd` so the snapshot stays in `Outcome.InProgress` until the next `onInitStart` replaces it. Added `RecordingInitDiagnostics` test fake (positive call-order assertions) and `DefaultModelRegistryTest` covering all five TDD-anchor cases, plus a one-line ctor update in `ModelRegistryActiveModelTest` to match the new signature.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved, zero findings → [logs/working/task-6/code-reviewer-1.json](logs/working/task-6/code-reviewer-1.json)
- security-auditor: approved, zero findings → [logs/working/task-6/security-auditor-1.json](logs/working/task-6/security-auditor-1.json)
- test-reviewer: passed, two minor advisory tightenings → [logs/working/task-6/test-reviewer-1.json](logs/working/task-6/test-reviewer-1.json)

Polish-pass commit (1c92b48) addressed both test-reviewer suggestions: pinned `freeRamBytes` to a known non-zero value via `ShadowActivityManager.setMemoryInfo` (the prior equality-with-fresh-read assertion would also have passed against a hardcoded `0L` since Robolectric's default `availMem` is 0), and tightened the `atEpochMs` window from a 60-second band relative to `now` to brackets captured immediately around the `registry.initialize()` call.

**Verification:**
- `./gradlew :core-runtime:testDebugUnitTest --tests "*DefaultModelRegistryTest*"` → BUILD SUCCESSFUL; 5/5 TDD-anchor tests green (start placement + availMem + atEpochMs window, GPU success arm, CPU-fallback success arm == one end(true), full-failure arm == one end(false), cancellation == zero ends).
- `./gradlew :core-runtime:testDebugUnitTest` → BUILD SUCCESSFUL — full `:core-runtime` suite, no regressions in pre-existing tests (`ModelRegistryActiveModelTest` updated minimally with `initDiagnostics = NoOpInitDiagnostics()`).
- `./gradlew :core-runtime:compileDebugKotlin :app:compileDebugKotlin` → BUILD SUCCESSFUL — confirms Hilt graph resolves the new `InitDiagnostics` constructor parameter through the Task-4 `@Binds DiagnosticsState` in `DiagnosticsModule`.
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL — `:app` tests unaffected by the registry-side plumbing change.
- End-to-end visibility of the snapshot in the `.txt` export header (Task 7) and on the diagnostics screen (Task 8) deferred to Task 12 (Honor 200 device smoke) per the task spec — UI chain not yet built.

## Task 7: DeviceInfoCollector header expansion

**Status:** Done
**Commit:** 119c04a (impl) + 6b00f5c (review polish)
**Agent:** main agent
**Summary:** Solution slice 4 of Phase 3.5: extended `DeviceInfoProvider` with 6 methods (`thresholdMemoryBytes`, `isLowMemory`, `processJavaHeapBytes`, `processNativeHeapBytes`, `processTotalPssBytes`, `lastInitSnapshot`) and refactored `AndroidDeviceInfoProvider` into a 3-ctor structure — private primary takes both `entriesProvider` and a new `lastInitSnapshotProvider` thunk; `@Inject` secondary forwards `DiagnosticsState`; non-Hilt `(Context)` secondary forwards `{ null }` so AC-H4 holds in `:crash`. `buildHeader` now emits an extended `memory:` line plus new `process:` and `last init:` rows between `memory:` and `active model:`. Per-field `n/a` mechanism uses `NA_SENTINEL = Long.MIN_VALUE` returned via `runCatching` from the three process getters; `formatGbOrNa` renders the sentinel as `n/a` while sibling fields stay normal. `last init:` row uses a top-level `internal fun formatLastInit(snapshot, zone)` covering all four AC-D6 + Decision 12 branches (null / Ok / Failed / InProgress) — Russian `ГБ` floor-formatted via cross-package `formatGbFloor` from `ui/modelmanager/GateDecision.kt`, `HH:mm` via `OffsetDateTime.ofInstant` with explicit zone for testability.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions, 5 minor nits → [logs/working/task-7/code-reviewer-1.json](logs/working/task-7/code-reviewer-1.json)
- security-auditor: approved, 3 minor (defense-in-depth recommendations) → [logs/working/task-7/security-auditor-1.json](logs/working/task-7/security-auditor-1.json)
- test-reviewer: passed, 2 minor → [logs/working/task-7/test-reviewer-1.json](logs/working/task-7/test-reviewer-1.json)

Polish-pass commit (6b00f5c) addressed the highest-signal items: (1) defense-in-depth `flattenForLogLine` helper that collapses newline/tab in `modelName` to single space — closes the log-forging gap flagged in security M-1, locked by `lastInit_modelNameWithLineBreaks_isFlattened`; (2) split `processLine_perFieldNaOnSourceError` into `_java`/`_native`/`_totalPss` so a wiring bug that swaps the sentinel routing between getters is caught; (3) tightened `lastInit_okBranch`/`_failedBranch`/`_inProgressBranch` from `HH:mm` regex + substring into exact-equality assertions on the helper output with `ZoneOffset.UTC` — removes system-zone fragility and shape-only weak check; (4) replaced misleading `Decision 10` KDoc reference on the `:crash` secondary ctor with an `architecture.md § Non-Hilt construction in :crash` pointer; (5) imported `InitSnapshot` in `ModelManagerViewModelTest` instead of leaving the fully-qualified type. Remaining nits left as-is: KDoc branch ordering (cosmetic), the four `memoryInfo()` invocations per `buildHeader` (pre-existing, non-regression), `runCatching(Throwable)` future-proofing concern around `CancellationException` (`buildHeader` is sync today; out of scope).

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "*DeviceInfoCollectorTest*" --tests "*LogExportManagerTest*"` → BUILD SUCCESSFUL; 29 tests in `DeviceInfoCollectorTest` (was 3) + 14 in `LogExportManagerTest`, all green.
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL — full `:app` suite compiles and passes after interface extension; `ModelManagerViewModelTest`'s `FakeDeviceInfoProvider` extended with 6 loud-`error("not used")` overrides per the discover-and-extend-stubs protocol.
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL — Hilt graph resolves the new `DiagnosticsState` parameter on the `@Inject`-ctor through Task 4's `@Binds` in `DiagnosticsModule`.
- Red→green confirmed: `:app:compileDebugUnitTestKotlin` failed with 14 "overrides nothing" errors before implementation; turned green after.
- End-to-end visibility of the new `memory:`/`process:`/`last init:` rows in the exported `.txt` deferred to Task 12 (Honor 200 device smoke) per the task spec — UI surface (DiagnosticsScreen export entry-point) lands in Task 8.

## Task 8: DiagnosticsScreen + ViewModel + Drawer pin + AboutScreen refactor

**Status:** Done
**Commit:** a9499fe (impl) + follow-up (unused-import cleanup)
**Agent:** main agent
**Summary:** Delivered the Phase-3.5 user-facing surface — `DiagnosticsScreen` (RAM section with 4-variant last-init rendering per Decision 12 and 1 s free-RAM tick, Logs section with SAF `.txt` export migrated whole from `AboutScreen` per Decision 11), drawer pin (third item between «Модели» and «О приложении» with `Icons.Outlined.MonitorHeart`), and `AboutScreen` refactor (diagnostics section + SAF launcher + `AboutViewModel` removed; `SafeMarkdown`, `AboutFooter`, 7-tap dev-crash dialog preserved). Polling shape is `flow {}.stateIn(viewModelScope, WhileSubscribed(0), seed)` — flow only runs while Compose collects, seed avoids placeholder frame. Pulled a thin `LogExporter` interface seam over `LogExportManager` so the VM test stays JVM-light and stops needing a `Context` for the export collaborator.
**Deviations:**
- `freeRamText` is built inline (`"Свободно: ${formatGbFloor(b)} ГБ"`) instead of a `diagnostics_free_ram_format` string resource — task spec step 8 listed the resource as desirable; the app is Russian-only (`Locale.ROOT`, no fallback per `architecture.md`) so the resource adds an indirection without any localisation payoff. Adding it later is a one-line change if the project ever localises.
- Reused existing `R.string.log_export_save_button` instead of new `diagnostics_button_save_log` and reused `R.string.log_export_success_toast` / `_error_toast` for snackbars — the strings already say «Сохранить лог» / «Лог сохранён» / «Не удалось сохранить лог»; cloning them under a `diagnostics_*` key would duplicate copy without any change in behaviour, and the task hint explicitly invited reuse of the existing snackbar resources.
- TDD-anchor test `onCleared_cancelsPolling` was renamed to `whenCollectorCancels_pollingStops` to match the refactored polling shape (`WhileSubscribed(0)` reacts to subscriber cancel; `viewModelScope` cancellation is a backstop). Same intent, more accurate name. Backstop path is exercised in production by Hilt's `ViewModel.clear` and is implicitly covered because `WhileSubscribed` is wired through `viewModelScope`.
- `DiagnosticsViewModelTest` runs under `RobolectricTestRunner` (Robolectric scope is minimum viable: only the two `buildAndWrite_*` tests need it for `Uri.parse`, and splitting into two classes was rejected because four pure-JVM-eligible tests don't justify the duplication and the Robolectric init is paid once per class).
- `DiagnosticsState` opened (was final) so the test can substitute `lastInitSnapshot()`. Reader-side seam interface symmetrical to the writer side (`InitDiagnostics`) was considered and rejected — `open class` keeps the test substitution cost at one keyword without inventing an interface for a single consumer.
- VM took the polling-shape rewrite mid-flight: an earlier `viewModelScope.launch { while(isActive) delay(1_000); ... }` design hung `runTest`'s end-of-block `advanceUntilIdle` forever (verified via `jstack` — the test worker spent infinite time in `delay(1_000)`); switching to `flow.stateIn(WhileSubscribed(0))` fixed it deterministically.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions, 0 critical / 0 major / 8 minor → [logs/working/task-8/code-reviewer-1.json](logs/working/task-8/code-reviewer-1.json)
- security-auditor: approved, 1 low (info) → [logs/working/task-8/security-auditor-1.json](logs/working/task-8/security-auditor-1.json)
- test-reviewer: passed, 0 critical / 0 major / 5 minor → [logs/working/task-8/test-reviewer-1.json](logs/working/task-8/test-reviewer-1.json)

Round 2 not run — all findings were minor / nit; only the unused `kotlinx.coroutines.flow.collect` import was removed inline. Other suggestions (extract `freeRamText` to string resource, tighten `text.contains("ok")` to `endsWith(" · ok")`, pin HH:mm zone to UTC for tests, document the asymmetry between Diagnostics VM (LogExporter) and Home / ModelManager VMs (concrete LogExportManager)) deferred as author discretion / future-cleanup tracking.

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "*DiagnosticsViewModelTest*"` → BUILD SUCCESSFUL in 28 s, 6/6 green.
- `./gradlew :app:testDebugUnitTest :app:assembleDebug` → BUILD SUCCESSFUL in 21 s (full app test suite green, debug APK assembles).
- Honor 200 device smoke deferred to Task 12 per project memory «Verify UI chain before device smoke» — UI cycle (drawer → screen → SAF → snackbar → back) and AboutScreen 7-tap regression go into the bundled pre-deploy QA matrix there.

## Task 9: Code Audit

**Status:** Done
**Commit:** (this commit)
**Agent:** main agent
**Summary:** Single-pass cross-component audit of Tasks 1-8 final state on `phase/3.5-diagnostics`. Verdict: **pass** — all 6 cross-component smoke checks green, zero blocker / major / minor findings. Three minor observations (dual-API readers of `lastInitSnapshot`, two private `LOG_TAG_DOWNLOAD` constants, 4× `memoryInfo()` invocations in `buildHeader`) are recorded as deliberate trade-offs already accepted in upstream task decisions; none rise to a finding. Final Wave (Task 12) is unblocked. Full report: [logs/audit/code-audit.md](logs/audit/code-audit.md).
**Deviations:** None.

**Reviews:**

Audit Wave — auditor is the review. Per-task reviewers absent by design (`reviewers: []`).

**Verification:**
- `grep -rEn "app\.sanctum\.machina\.diagnostics" core-runtime/src/main` → 0 matches.
- `grep -rEn "androidx.compose|androidx.activity" core-runtime/src/main` → 0 matches.
- `ErrorLog.ALLOWED_COMPONENTS` (`core-runtime/.../log/ErrorLog.kt:32-50`) — 14 components verbatim, identical to `patterns.md` whitelist.
- Shared resources ownership (`DiagnosticsState`, `DeviceInfoProvider`, `ActivityManager`) — matches Architecture § Shared resources table.
- No duplicate heavy-resource init — production has exactly the prescribed singletons / system-service lookup sites.

## Task 11: Test Audit

**Status:** Done
**Commit:** (this commit)
**Agent:** main agent
**Summary:** White-box test-quality audit of Tasks 1-8 final state on `phase/3.5-diagnostics`. Verdict: **PASS — GO for Pre-deploy QA**. All nine AC-T criteria (AC-T1..AC-T9) covered with concrete, asserting tests across 12 test classes and 114 `@Test` methods (+ 2 hand-rolled `InitDiagnostics` fakes). Mock-framework scan: zero `org.mockito` / `io.mockk` imports anywhere in the repo. Pyramid: 5 pure-JVM files / 39 tests; 7 Robolectric files / 75 tests with per-file justifications (Android `Process.myPid` / `ActivityManager` shadow / `Uri.parse` / `Context`); 0 instrumented. Concurrency test in `DiagnosticsStateTest::concurrentWriterReaderNeverSeesMixedState` uses raw `Thread`+`CountDownLatch` at 10 000 iterations with the spec'd attempt-set assertion plus a vacuous-race guard (positive deviation over spec). Fixture-table coverage for `formatRamShortage`, `gateAllowsDownload`, `gitVersionParse` matches tech-spec § Testing Strategy. Existing-tests-still-passing list reconciled: `AboutViewModelTest` no longer exists because `buildAndWrite` migrated into `DiagnosticsViewModel` per tech-spec note; `AppCorruptionStateTest` lives at `app/engine/` (path drift from tech-spec listing, not a regression). Zero CRITICAL/MAJOR/MINOR findings. Full report: [logs/audit/test-audit.md](logs/audit/test-audit.md).
**Deviations:** None.

**Reviews:**

Audit Wave — auditor is the review. Per-task reviewers absent by design (`reviewers: []`).

**Verification:**
- `test -f work/phase-3.5-diagnostics/logs/audit/test-audit.md` → report exists.
- `grep -c "^- AC-T[1-9]:" …/test-audit.md` → 9 (matches the smoke-required count).
- `grep -E "^## Mock-framework scan" -A 4 …/test-audit.md` → section present with explicit `<empty>` lines for both Mockito and MockK scans.

## Task 10: Security Audit

**Status:** Done
**Commit:** (this commit)
**Agent:** main agent
**Summary:** Single-pass security audit of Tasks 1-8 final state on `phase/3.5-diagnostics` against OWASP Top 10 (2021). Verdict: **pass** — zero critical / high / medium / low findings. All 10 OWASP categories covered (A01-A10) with explicit verdicts (5× ok, 5× n/a — n/a's are server/auth/crypto categories that don't apply to a local Android-on-device-LLM app). All 7 targeted checks (input validation, path traversal, command injection, sensitive data in `.txt`, `DiagnosticsState` thread-safety, `:crash` degradation, `MemoryInfo.availMem` privilege) closed as ok. Three info-level future-hardening recommendations recorded (`version`-field regex, `runCatching(Throwable)` on async boundaries, `versionName` mirror) — none blocking deploy. Final Wave (Task 12, Pre-deploy QA) is unblocked from a security perspective. Full report: [logs/audit/security-audit.md](logs/audit/security-audit.md).
**Deviations:** None.

**Reviews:**

Audit Wave — auditor is the review. Per-task reviewers absent by design (`reviewers: []`).

**Verification:**
- `work/phase-3.5-diagnostics/logs/audit/security-audit.md` exists.
- Report contains OWASP Top 10 section with all A01-A10 verdicts.
- Report contains targeted checks 4.1-4.7 with explicit per-check verdict.
- Findings section explicitly states "no security findings"; reasoning enumerates checked surfaces (bundled signed allowlist, argv-list ProcessBuilder, AtomicReference snapshot scalars, SAF system-mediated user gesture).
- Cross-reference matrix vs. per-task auditor passes confirms no regression between per-task and integrated-state audits.

<!-- Entries are added by agents as tasks are completed.

Format is strict — use only these sections, do not add others.
Do not include: file lists, findings tables, JSON reports, step-by-step logs.
Review details — in JSON files via links. QA report — in logs/working/.

## Task N: [title]

**Status:** Done
**Commit:** abc1234
**Agent:** [teammate name or "main agent"]
**Summary:** 1-3 sentences: what was done, key decisions. Not a file list.
**Deviations:** None / Deviated from spec: [reason], did [what].

**Reviews:**

*Round 1:*
- code-reviewer: 2 findings → [logs/working/task-N/code-reviewer-1.json]
- security-auditor: OK → [logs/working/task-N/security-auditor-1.json]

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-N/code-reviewer-2.json]

**Verification:**
- `npm test` → 42 passed
- Manual check → OK

-->
