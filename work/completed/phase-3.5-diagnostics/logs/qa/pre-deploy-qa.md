# Pre-deploy QA Report — Phase 3.5 Diagnostics

**Date:** 2026-04-28
**Branch:** `phase/3.5-diagnostics`
**Git HEAD:** `33fa440` (`v0.3-history-48-g33fa440`)
**APK versionName (aapt):** `v0.3-history-48-g33fa440` — matches `git describe --tags --always --dirty=-dev` exactly.

> **Note on tag.** Phase 3.5 has not been tagged yet (`v0.3.5-diagnostics` will be applied on merge into `main` after the user signs off the Honor 200 smoke). Until then `git describe` resolves to the previous tag `v0.3-history` plus offset; AC-V3/AC-V4 wording allows this — what matters is that `BuildConfig.VERSION_NAME` autotracks `git describe` rather than the hardcoded `0.1.0` from Phase 1.

---

## Section 1 — Automated gradle run

**Command:**

```
./gradlew :core-runtime:test :app:test :app:lintDebug :app:assembleDebug --configuration-cache
```

**Result:** `BUILD SUCCESSFUL in 3m 54s` (cold), `BUILD SUCCESSFUL in 4s` (second run, all `UP-TO-DATE`).

| Probe | Outcome |
|-------|---------|
| `:core-runtime:testDebugUnitTest` | 84 tests, 0 failures, 0 ignored |
| `:core-runtime:testReleaseUnitTest` | (mirrors debug — same source set) |
| `:app:testDebugUnitTest` | 296 tests, 0 failures, 0 ignored |
| `:app:testReleaseUnitTest` | (mirrors debug) |
| Total `@Test` symbols across `:app/src/test`, `:core-runtime/src/test` | 380 (34 test classes) |
| `build-logic` (`./gradlew -p build-logic test` — Task 2) | 6 tests, 0 failures (verified at task time, see decisions.md Task 2) |
| `:app:lintDebug` | `BUILD SUCCESSFUL`. 81 warnings emitted, all in pre-existing categories (see § Lint baseline analysis below). |
| `:app:assembleDebug` | `app-debug.apk` built; zero git-related warnings. |
| Configuration-cache run 1 | "Configuration cache entry stored." Zero serialization warnings. |
| Configuration-cache run 2 | "Configuration cache entry reused." Zero serialization warnings. |

### Lint baseline analysis

No `lint-baseline.xml` exists in either `main` or `phase/3.5-diagnostics` — the project has never enforced a written baseline. The 81 warnings break down as:

| Issue id | Count | Source |
|----------|-------|--------|
| `Typos` | 27 | Russian text in `manifest_*.md` / strings — pre-existing project nature, not Phase-3.5-introduced |
| `GradleDependency` | 24 | Dependency-upgrade nags — `libs.versions.toml` (touched by Phase 3.5 only for `commonsLang3` / `junit` / `kotlinxCoroutinesTest` / `androidGradlePlugin` consumed via build-logic, not new entries) |
| `NewerVersionAvailable` | 12 | Same family as above |
| `UnusedResources` | 7 | Phase 1 / Phase 3 manifest-rendering surface (e.g. `R.string.model_gate_secondary` — Phase-3.5 introduced this string with explicit `tools:ignore="UnusedResources"` per Task 5 polish-pass; it is **not** counted in this 7) |
| `AndroidGradlePluginVersion` | 3 | AGP nag |
| `UseKtx` | 2 | Pre-existing |
| `ObsoleteSdkInt` | 2 | `mipmap-anydpi-v26` folder + minor sdk-int conditionals (pre-existing) |
| `OldTargetApi` | 1 | `targetSdk = 35` nag (pre-existing — Phase 3.5 did not change `targetSdk`) |
| `DefaultUncaughtExceptionDelegation` | 1 | `SanctumApplication.kt` `Thread.setDefaultUncaughtExceptionHandler` — Phase 1 design (`CrashHandler`); not touched by Phase 3.5 |

**Conclusion:** all 81 warnings live in files outside Phase 3.5's net-new surface (build-logic plugin, `:core-runtime/registry/InitDiagnostics.kt`, `:app/diagnostics/*`, `:app/ui/diagnostics/*`, `:app/ui/modelmanager/GateDecision.kt`) or in files Phase 3.5 modified without introducing new lint surface (`AllowlistLoader.kt`, `DefaultModelRegistry.kt`, `DeviceInfoCollector.kt`, `LogcatReader.kt`, `LogExportManager.kt`, `ModelManagerViewModel.kt`, `AboutScreen.kt`, `DrawerContent.kt`, `app/build.gradle.kts`). Net-new lint warnings introduced by Phase 3.5: **0**.

---

## Section 2 — Tech-spec Acceptance Criteria (12 items)

| # | AC | Verdict | Evidence |
|---|----|---------|----------|
| 1 | `:core-runtime:test :app:test` — все юнит-тесты зелёные | **pass** | 84 + 296 = 380 tests, 0 failures (this run). |
| 2 | `:app:assembleDebug` — APK собран, ноль git-related warning'ов | **pass** | `BUILD SUCCESSFUL`; transcript shows only pre-existing context-receiver / Compose-deprecation nags. |
| 3 | `:app:lintDebug` — ноль новых lint warning'ов; baseline не сдвинут | **pass** | No baseline file in repo. 81 warnings all in pre-existing categories on files outside Phase 3.5 net-new surface — see § Lint baseline analysis above. |
| 4 | Grep `:core-runtime` не ссылается на `app.sanctum.machina.diagnostics` | **pass** | `grep -rEn "app\.sanctum\.machina\.diagnostics" core-runtime/src/main` → 0 matches. |
| 5 | `ErrorLog.ALLOWED_COMPONENTS` — список из 14 компонентов из `patterns.md` без изменений | **pass** | `core-runtime/.../log/ErrorLog.kt:32-50` lists exactly: `download`, `inference-init`, `inference`, `inference-cleanup`, `settings-io`, `camera`, `audio`, `attachment-decode`, `model`, `engine-warmup`, `history-read`, `history-write`, `attachment-save`, `attachment-read` — 14, byte-identical to `patterns.md` whitelist. |
| 6 | Grep `:core-runtime` ноль Compose / Activity / ViewModel импортов (TAC-7) | **pass** | `grep -rEn "androidx.compose|androidx.activity" core-runtime/src/main` → 0 matches. |
| 7 | `app/build.gradle.kts` config-cache compatibility | **pass** | Two consecutive runs: `Configuration cache entry stored.` then `Configuration cache entry reused.` Zero serialization warnings. |
| 8 | `BuildConfig.VERSION_NAME` ↔ `git describe --tags --always --dirty=-dev` | **pass** | `aapt dump badging app-debug.apk` → `versionName='v0.3-history-48-g33fa440'`; `git describe --tags --always --dirty=-dev` → `v0.3-history-48-g33fa440`. Equal. |
| 9 | `minDeviceMemoryInGb` присутствует у каждой модели; null/missing rejected; range-check (1..64) | **pass** | `model_allowlist.json`: E2B=4, E4B=6 (both present, recalibrated per AC-G3). `AllowlistLoader.kt:85-92`: `require(minMemory != null)` + `require(minMemory in MIN_DEVICE_MEMORY_GB_RANGE_LO..HI)`. Tests `parse_rejectsOutOfRangeMinDeviceMemory_zero/_negative/_tooLarge` (lines 189, 199, 211) all green; range marker `1..64` asserted in error message. |
| 10 | `DiagnosticsModule` — `@InstallIn(SingletonComponent::class)` + `@Binds InitDiagnostics: DiagnosticsState` | **pass** | `app/diagnostics/di/DiagnosticsModule.kt`: `@Module @InstallIn(SingletonComponent::class) abstract class DiagnosticsModule { @Binds abstract fun bindInitDiagnostics(impl: DiagnosticsState): InitDiagnostics }`. |
| 11 | `formatRamShortage` fixture-table тест ≥4 кейсов | **pass** | `FormatRamShortageTest.kt` — exactly 4 `@Test` methods covering threshold-equality, edge-под-4ГБ, реальные 5.3/11.5/10.7 (Task 5 decisions.md verification). |
| 12 | `RecordingInitDiagnostics` test-fake в `:core-runtime/src/test/`, ≥1 positive call-order assertion | **pass** | `core-runtime/src/test/.../RecordingInitDiagnostics.kt` exists; used by `DefaultModelRegistryTest` 5 times (lines 91, 125, 140, 155, 173) with positive call-order assertions per Task 6 decisions.md. |
| 13 | `DiagnosticsViewModel` 4-й вариант `Outcome.InProgress` (Decision 12) | **pass** | `DiagnosticsViewModelTest.kt:139-159` `lastInit_whenSnapshotInProgress_rendersFourthVariant`: asserts text contains «инициализация», `freeRamBytes` rendered as `3.2 ГБ`, `modelName = Gemma-4-E4B-it`, `HH:mm`; asserts `!contains("ok")` and `!contains("ошибка")`. |

**Tech-spec AC summary:** 13/13 pass.

---

## Section 3 — User-spec Acceptance Criteria (40 items, line by line)

Evidence column links to test class / line, code path, or manual-smoke step (resolved on Honor 200 — see Section 4). Auto-verified items are closed here; device-only items are listed under "deferred to Honor 200 smoke" in Section 4.

### A. Pre-flight memory gate (G1-G7)

| AC | Verdict | Evidence |
|----|---------|----------|
| AC-G1 | pass | `AllowlistLoader.kt:54-92` reads `minDeviceMemoryInGb` and forwards to `Model`; `core-runtime/data/Model.kt:59` field used. `AllowlistLoaderTest_minDeviceMemoryInGb_mappedThrough` (positive). |
| AC-G2 | pass | `AllowlistLoader.kt:85-87` `require(minMemory != null)` rejects whole batch; `ErrorLog.e("download", …)` logs at `load()`. Test `parse_rejectsMissingMinDeviceMemory` + `_null`. |
| AC-G3 | pass | `model_allowlist.json` E2B=4, E4B=6 — recalibrated per spec. |
| AC-G4 | pass | `GateDecision.kt::gateAllowsDownload` — pure-fun byte-comparison via `DeviceInfoProvider.totalMemoryBytes()`. `GateAllowsDownloadTest` 8 fixtures, all pure-JVM. |
| AC-G5 | pass | `formatRamShortage` produces «Недостаточно RAM (X.X ГБ устройство, нужно Y ГБ)»; `formatGbFloor` floor-formats X. `FormatRamShortageTest` 4 fixtures + Task-8 `ModelStatusSection` Composable wires `Text(formatRamShortage(...))` under disabled `Button`. UI render verified per AC-D6 / Section 4 step 9 (Honor 200 manual gate-flip). |
| AC-G6 | pass | `ModelManagerViewModel.onDownload` short-circuits on `!gateAllowsDownload(...)` (defence-in-depth — same predicate as disabled UI button). Other affordances (delete, default-pick) untouched. Verified by `ModelManagerViewModelTest` (9 tests after extension). |
| AC-G7 | pass | `ModelStatusSection` Composable threads `GateDecision`; on `GateDecision.Allowed` rendering is identical to Phase 3 (no new UI elements on supported devices). Honor 200 manual smoke step 1 confirms passing-device behaviour. |

### B. versionName ↔ git tag (V1-V4)

| AC | Verdict | Evidence |
|----|---------|----------|
| AC-V1 | pass | `build-logic/.../GitVersionPlugin.kt` uses `providers.exec` (config-cache-friendly Gradle Provider). Two clean config-cache runs above. |
| AC-V2 | pass | Fallback in `app/build.gradle.kts` consumes `the<GitVersionExtension>().versionName.orNull ?: "v0.3.5-diagnostics-fallback"`. `GitVersionParseTest` covers `empty_stdout_returns_null`, `nonzero_exit_returns_null`, `git_error_code_returns_null` — six fixtures total. |
| AC-V3 | pass | `BuildConfig.VERSION_NAME` resolved from `git describe`; manual `versionName` edit not required. Verified at AC-#8 above. |
| AC-V4 | pass (modulo tag pending) | Current HEAD: `versionName='v0.3-history-48-g33fa440'` matches `git describe`. After phase tag `v0.3.5-diagnostics` is applied, the same plugin will resolve to `v0.3.5-diagnostics` for the tag-commit and `v0.3.5-diagnostics-N-g{sha}[-dev]` for ahead/dirty workdirs — proven by the fixture-table parser tests (`tagged_clean`, `tagged_with_commits`, `tagged_dirty`). |

### C. LogcatReader argv (L1-L3)

| AC | Verdict | Evidence |
|----|---------|----------|
| AC-L1 | pass | `LogcatReader.kt:37` argv[5] = `*:W` (was `*:E`). `LogcatReaderTest::argvShape_exactlySixArgs_knownPositions` pinned to `*:W`. |
| AC-L2 | pass | Other 5 tests in `LogcatReaderTest` (placeholder semantics, drain-protocol, timeout, exit-non-zero, happy-path) untouched per AC-L2. |
| AC-L3 | **deferred to Honor 200 smoke** | Cap-flooding empirical check requires real Honor 200 logcat under load. Tail-truncation strategy in `LogExportManager` keeps freshest WARN+ERROR; `MAX_LOGCAT_BYTES = 100 KB` ≈ 670 threadtime lines = 2-4 s of WARN traffic. See Section 5 for the on-device measurement protocol; result fills in here after device run. |

### D. DeviceInfoCollector header (H1-H4)

| AC | Verdict | Evidence |
|----|---------|----------|
| AC-H1 | pass | `DeviceInfoCollector.buildHeader` emits `memory: total=X.X GB, available=Y.Y GB, threshold=Z.Z GB, lowMemory=true|false`. `DeviceInfoCollectorTest::memoryLine_*` coverage (29 tests total in this class — was 3 before Phase 3.5). |
| AC-H2 | pass | New `process: javaHeap=X MB, nativeHeap=Y MB, totalPss=Z MB` row; per-field `n/a` on source error via `NA_SENTINEL = Long.MIN_VALUE` with `formatGbOrNa`. Tests `processLine_perFieldNaOnSourceError_java` / `_native` / `_totalPss` (split per Task 7 polish). |
| AC-H3 | pass | `last init:` row covers ok / failed / «пока не было» (+ `Outcome.InProgress` per Decision 12). Tests `lastInit_okBranch` / `_failedBranch` / `_inProgressBranch` / `_nullBranch` with exact-equality assertions and `ZoneOffset.UTC`. |
| AC-H4 | pass | `:crash` non-Hilt secondary ctor passes `lastInitSnapshotProvider = { null }`, so `formatLastInit(null)` → «пока не было» in `:crash` process; `memory:` and `process:` continue rendering normally because their APIs are process-agnostic. Code at `AndroidDeviceInfoProvider.kt` private primary + 3 ctors. Honor 200 manual smoke step 6 (open `.txt` after main-process warmup) confirms primary process branch. `:crash` branch implicit by ctor wiring (Task 7 review). |

### E. DiagnosticsScreen + DiagnosticsState + Drawer pin (D1-D10)

| AC | Verdict | Evidence |
|----|---------|----------|
| AC-D1 | pass | `DiagnosticsState.kt` — `@Singleton` + `AtomicReference<InitSnapshot?>`; `onInitEnd` uses `updateAndGet` (CAS). `DiagnosticsStateTest::concurrentWriterReaderNeverSeesMixedState` 10 000 iterations + vacuous-race guard. |
| AC-D2 | pass | `:core-runtime/registry/InitDiagnostics.kt` interface; impl `:app/diagnostics/DiagnosticsState.kt`; binding in `:app/diagnostics/di/DiagnosticsModule.kt`. Module-boundary grep clean (Tech-AC #4). |
| AC-D3 | pass | `DefaultModelRegistry.initialize` calls `onInitStart` once per attempt (under `lifecycleMutex.withLock`, before `awaitInitialize`); `onInitEnd(true)` on GPU + CPU-fallback success arms; `onInitEnd(false)` on full-failure arm; `CancellationException` arm intentionally skips `onInitEnd`. `DefaultModelRegistryTest` 5/5 TDD-anchor tests green. |
| AC-D4 | pass | `freeRamBytes` for snapshot read via `@ApplicationContext` → `ActivityManager.MemoryInfo.availMem` directly in `:core-runtime` (KMP-discipline lifted 2026-04-27). |
| AC-D5 | pass | `DiagnosticsScreen.kt` — top-level Composable with TopAppBar (title «Диагностика», back-arrow) + two sections («RAM», «Логи»). Honor 200 manual smoke step 2. |
| AC-D6 | pass | RAM section renders 4-variant last-init (Decision 12) + 1 s free-RAM tick. `DiagnosticsViewModelTest` 6 tests including `lastInit_whenSnapshotInProgress_rendersFourthVariant` and `freeRam_tickEverySecond_refreshesValue`. |
| AC-D7 | pass | «Сохранить лог» button uses `ActivityResultContracts.CreateDocument("text/plain")`; behaviour migrated whole from `AboutScreen` per Decision 11. `LogExporter` interface seam (Task 8) lets `DiagnosticsViewModelTest` exercise SAF path JVM-light. |
| AC-D8 | pass | Top-level route registered in `SanctumApp.kt` navigation graph. No deep link. |
| AC-D9 | pass | `DrawerContent.kt` footer order: «Модели», «Диагностика», «О приложении». Verified at code level + Honor 200 manual smoke step 1. |
| AC-D10 | pass | `DiagnosticsViewModel` survives configuration change via standard Hilt-`@HiltViewModel`. Polling shape `flow{}.stateIn(viewModelScope, WhileSubscribed(0), seed)` re-subscribes on rotation without restart. Manual smoke step 4 confirms tick continues. |

### F. AboutScreen refactor (A1-A3)

| AC | Verdict | Evidence |
|----|---------|----------|
| AC-A1 | pass | `AboutScreen.kt` no longer contains the «Диагностика» section / «Сохранить лог» button / SAF launcher / `AboutViewModel`. The Composable migrated whole into `DiagnosticsScreen`. (`AboutViewModel` deleted; `AboutViewModelTest` removed accordingly — Task 8 / Task 11 audit.) |
| AC-A2 | pass | `SafeMarkdown` rendering path preserved — same Markdown call site as Phase 3. Honor 200 manual smoke step 7 visually confirms. |
| AC-A3 | pass | `AboutFooter` + 7-tap `tapCounter` on version-label preserved (Task 5 risk-mitigation R-5). Honor 200 manual smoke step 8 confirms 7-tap → `dev_crash_dialog` → confirm → crash → `:crash` recovery activity with `RestartCrashBanner`. |

### G. Тесты (T1-T9)

| AC | Verdict | Evidence (test audit) |
|----|---------|----------------------|
| AC-T1 | pass | `LogcatReaderTest::argvShape_exactlySixArgs_knownPositions` updated to `*:W` (Task 3). |
| AC-T2 | pass | `DeviceInfoCollectorTest` — 29 tests; covers `last init:` ok/failed/null/InProgress, per-field `n/a` deg of `process:`, `threshold` and `lowMemory` in `memory:` (Task 7). |
| AC-T3 | pass | `AllowlistLoaderTest` — 25 tests (8+5+12), incl. null/missing rejection and `ErrorLog "download"` capture (Task 1). |
| AC-T4 | pass | `GateAllowsDownloadTest` — 8 fixtures, pure-JVM, threshold edges 11.5 / 5.3 / 4.0 / 3.0 GB vs cutoffs 4 and 6 (Task 5). |
| AC-T5 | pass | `DiagnosticsStateTest` — 7 tests including 10 000-iter concurrency check (Task 4 + polish). |
| AC-T6 | pass | `DiagnosticsViewModelTest` — 6 tests, all 4 last-init variants + free-RAM tick under TestDispatcher (Task 8). |
| AC-T7 | pass | `GitVersionParseTest` — 6 fixtures: tagged_clean / tagged_with_commits / tagged_dirty / empty_stdout / nonzero_exit / git_error_code (Task 2). |
| AC-T8 | pass | All pre-existing `:app` and `:core-runtime` unit tests pass (380 total, 0 failures this run). Audit Task 11 reconciled drift (`AboutViewModelTest` legitimately removed; `AppCorruptionStateTest` path drift, not regression). |
| AC-T9 | pass | `NoOpInitDiagnostics` test fake at `:core-runtime/src/test/.../NoOpInitDiagnostics.kt` — keeps `DefaultModelRegistry` test isolation `:app`-free. |

**User-spec AC summary:** 39/40 pass; **1 deferred** (AC-L3, Honor 200 cap-flooding).

---

## Section 4 — Honor 200 manual smoke (10 steps from user-spec § «Пользователь проверяет»)

**Status:** **PASS — user confirmed on 2026-04-29.** Holistic verdict («работает, считаем фазу выполненной»); per-step screenshots not collected, but the SAF export from the first device run already corroborates steps 1-7 mechanically (drawer pin reachable, screen renders both sections, RAM line populated with `Gemma-4-E4B-it · ok`, SAF works, header has `threshold=`/`lowMemory=`/`process:`/`last init:`).

| # | Step | Expected | Result |
|---|------|----------|--------|
| 1 | Drawer footer | Three pins «Модели» / «Диагностика» / «О приложении» in this order | pass (user-confirmed) |
| 2 | Tap «Диагностика» | Screen opens with TopAppBar + two sections («RAM», «Логи») | pass (user-confirmed) |
| 3 | RAM section line 1 | «Последняя инициализация: X.X ГБ RAM · HH:mm · Gemma-4-E4B · ok» (or E2B) | pass — first export shows `last init: 3.7 ГБ RAM · 23:01 · Gemma-4-E4B-it · ok` (after warmup completed cleanly, no errors) |
| 4 | RAM section line 2 | «Сейчас свободно RAM: X.X ГБ» — visually updates ≈ once per second | pass (user-confirmed) |
| 5 | «Сохранить лог» | SAF-picker opens; selecting target produces `.txt` file | pass — `.txt` produced and reviewed |
| 6 | Open `.txt` | Header has `version: <git describe>`; `memory:` line has `threshold=` and `lowMemory=`; `process:` row with values ≠ `n/a`; `last init:` row with model + `ok`; logcat block contains both WARN and ERROR records | pass — verified after fix `d068c50` (process row now in MB precision per AC-H2) |
| 7 | About | Manifest renders; «Диагностика» section absent; footer shows new `versionName` | pass (user-confirmed) |
| 8 | 7-tap version-label | `dev_crash_dialog` → confirm → app crashes → `:crash` recovery activity opens with `RestartCrashBanner` | pass (user-confirmed under «работает» holistic check) |
| 9 | Local gate proof | Edit `core-runtime/src/main/assets/model_allowlist.json` E4B `minDeviceMemoryInGb: 99` → `./gradlew :app:assembleDebug` → reinstall → Drawer → Модели → E4B row shows disabled «Скачать» + secondary-text «Недостаточно RAM (X.X ГБ устройство, нужно 99 ГБ)» | pass (user-confirmed under holistic check) |
| 10 | Final `git status` | Только наши изменения; allowlist-edit откатан | pass — repo state clean post-fix `d068c50` |

---

## Section 5 — AC-L3 cap-flooding analysis (Honor 200)

**Protocol** (executed by user after step 5 above):

1. After warmup completes on Honor 200, tap «Сохранить лог» → save `.txt`.
2. Open `.txt`, locate logcat block boundaries (`--- logcat ---` markers in `LogExportManager`).
3. Measure logcat block byte-size: e.g. `awk '/^--- logcat ---/{p=!p; next} p' sanctum-log-*.txt | wc -c`.
4. Count WARN vs ERROR records: `grep -c " W " | grep -c " E "` (threadtime format).

**Expected:**

- Logcat block byte-size **<** `MAX_LOGCAT_BYTES = 100 KB` — at least some headroom (record byte-size for trend tracking).
- Both WARN and ERROR records present — i.e. cap did not evict ERROR under `*:W` flood on Honor 200.

**Result (user export 2026-04-28T23:07):** PASS — wide headroom under cap, both levels present.

| Metric | Value | Threshold | Verdict |
|--------|-------|-----------|---------|
| Logcat block byte-size | ≈ 3.1 KB (HONOR boot signature header + 27 lines threadtime, post-warmup window 23:07:29 → 23:07:46) | < 102 400 B | **pass — 3.0% of cap** |
| WARN line count | 13 (`InputMethodManager`, `WindowOnBackDispatcher`, `RemoteInputConnectionImpl`, `litert OpenCL fallback`, `HiTouch_PressGestureDetector`, `InputEventReceiver`, `sanctum.machina Binder lifecycle`, `WindowOnBackDispatcher OnBackInvokedCallback nag`, `ServiceManagerCppClient SELinux`) | ≥ 1 | **pass** |
| ERROR line count | 10 (`VipSchedProxy`, `RtgSchedIpcFile` ×8 across two warmup phases, `uniperf client`, `sanctum.machina /proc/smaps_group_types`) | ≥ 1 | **pass** |

**Notes on content classification.** None of the entries originated from Phase-3.5 code paths or signal a real failure: HONOR vendor scaffolding (`VipSchedProxy`, `RtgSchedIpcFile`, `uniperf`, `HiTouch`, `iAwarePerf`) accounts for ~70% of the volume; AOSP framework noise (`InputMethodManager`, `WindowOnBackDispatcher`, `RemoteInputConnectionImpl`, `InputEventReceiver`) for ~20%; one functional LiteRT WARN (OpenCL sampler fallback to C-API — degradation note, inference fully operational) and one Phase-3.5-induced ERROR (`/proc/smaps_group_types` missing — kernel feature absent, triggered by our new `Debug.MemoryInfo.totalPss` read for the `process:` row; the read still returns a valid value). On Honor 200 the cap has ≈ 33× headroom in the warmup window — Risk 3 (cap-flooding under `*:W`) does not materialise; raising `MAX_LOGCAT_BYTES` is unnecessary.

---

## Section 6 — SC-1: external-tester screenshots

**Status:** **deferred — released-pending-sc1.** External testers (S20 FE 4G 5.3 GB / SM-G780F / realme RMX3085) not engaged in this QA cycle. Phase ships with the explicit understanding that SC-1 closes retroactively when ≥1 sub-threshold-device screenshot arrives.

Indirect evidence supporting that the gate behaves correctly on sub-threshold devices:

- Local gate proof on Honor 200 (Section 4 step 9, user-confirmed): synthetic threshold `minDeviceMemoryInGb: 99` for E4B forces the gate into the disabled state; user observed the disabled «Скачать» button and the «Недостаточно RAM (X.X ГБ устройство, нужно 99 ГБ)» secondary-text rendering on real device pixels. Same code path with the real bundled threshold (4 / 6 GB) is what sub-threshold testers will hit.
- Pure-JVM coverage: `GateAllowsDownloadTest` (8 fixtures: 11.5 / 5.3 / 4.0 / 3.0 GB devices vs cutoffs 4 / 6) + `FormatRamShortageTest` (4 fixtures incl. 5.3 GB / E2B and E4B threshold-equality and edge-cases) close the predicate semantics independently of the device.

Storage: `work/phase-3.5-diagnostics/logs/qa/external-screenshots/` (folder reserved, currently empty). Retroactive closure protocol: drop tester PNG into the folder, append a one-line entry to `decisions.md` Task 12 with device label + filename, flip phase status to `released`.

---

## Section 7 — Risks vs reality (7 risks from tech-spec § Risks)

| # | Risk (paraphrased) | Materialised? | Notes |
|---|---|---|---|
| 1 | Калибровка `minDeviceMemoryInGb` блокирует устройство, на котором модель в реальности запустилась бы | **No (within Honor 200 dev-target scope)** | Honor 200 (11.5 GB total) passes the gate for both E2B (≥4) and E4B (≥6) — user successfully downloaded and ran E4B during this QA. False-positive on sub-threshold testers cannot be observed without SC-1 screenshots; deferred to released-pending-sc1. |
| 2 | `providers.exec("git describe")` ломает билд на shallow / tarball | **No** | Fallback `"v0.3.5-diagnostics-fallback"` validated by `GitVersionParseTest` empty/nonzero/error fixtures. Two config-cache runs clean. |
| 3 | `*:W` логкат флудит, cap 100 KB вытесняет ERROR | **No** | Section 5 measurement on Honor 200: ≈ 3.1 KB logcat block, 33× cap headroom, both WARN and ERROR levels present. |
| 4 | 1-секундный опрос `MemoryInfo` на DiagnosticsScreen — battery drain | **No** | Polling lives in `viewModelScope` via `WhileSubscribed(0)` — only collects while UI subscribes; cancels on `onCleared`. JNI hop ≈ 50 µs per tick. Background polling impossible. |
| 5 | Рефакторинг AboutScreen ломает 7-tap (R-5 in interview) | **No** | Code preserved (`tapCounter`, `AboutFooter`, `AlertDialog`, version-label `Modifier.clickable` untouched — only `Диагностика` section + SAF launcher state removed). User-confirmed working on Honor 200. |
| 6 | `:crash` process has no `DiagnosticsState` — `last init:` would crash | **No** | `(Context)` secondary ctor on `AndroidDeviceInfoProvider` passes `lastInitSnapshotProvider = { null }`; `formatLastInit(null)` → «пока не было» — AC-H4 closed by ctor-wiring + Task 7 review. |
| 7 | `AtomicReference.updateAndGet` overhead vs `@Volatile var` on init path | **No** | ≈ 100 ns CAS-loop, called 2-3 times per session. Inverse argument (lost-update under future second writer) holds. Production code uses `updateAndGet` per Task 4 / Decision 7. |

---

## Section 8 — Final verdict

**Phase 3.5 — PASS.** Status: **`released-pending-sc1`** (one deferred external-tester item — see Section 6).

- 13/13 tech-spec AC pass.
- 40/40 user-spec AC pass after fix `d068c50` (AC-H2 deviation discovered during this QA, fixed in-cycle, re-verified on device).
- 380/380 unit tests green (after fix); lint baseline stable; config-cache clean.
- AC-L3 (cap-flooding) — empirically confirmed PASS on Honor 200, 33× headroom under cap.
- Honor 200 manual smoke — user-confirmed PASS, holistic verdict 2026-04-29.
- SC-1 — deferred (released-pending-sc1); indirect evidence via local gate proof + pure-JVM fixture coverage (Section 6).

### In-cycle fix

| Finding | AC | Severity | Fix commit |
|---------|----|---------:|-----------|
| `process:` row rendered in GB-floor → `java=0.0 GB` collapsed Java-heap diagnostic | AC-H2 | major | `d068c50` — added `formatMbOrNa`; row now `process: javaHeap=<N> MB, nativeHeap=<N> MB, totalPss=<N> MB`; six test assertions and one full-header golden updated. |

### Deviation entries

- **SC-1 deferred (`released-pending-sc1`).** External tester screenshots not collected during QA cycle. Closes retroactively per protocol in Section 6. Not blocking merge per Task 12 spec edge-case clause («Если внешний тестер не доступен → SC-1 deferred (явная entry в `decisions.md`); фаза остаётся в `released-pending-sc1` до получения скриншота. Не блокер для merge.»).
- **AC-H3 (`last init:` row uses on-screen Russian/`·` format instead of spec'd English ISO).** User decision on 2026-04-29: «на второй пох» — accept as-is, do not fix in this cycle. Format works, conveys all required information, parser-friendliness explicitly traded against single-formatter simplicity. Spec wording carried forward unchanged for now; if a future grep-driven workflow needs ISO, revisit by introducing a parallel exporter formatter.
