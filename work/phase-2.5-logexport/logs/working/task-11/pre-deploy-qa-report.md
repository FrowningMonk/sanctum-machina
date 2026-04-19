# Pre-deploy QA Report — Phase 2.5 Log Export

**Date:** 2026-04-19
**Commit under test:** `e162d6c` (branch `phase/2.5-logexport`, 4 commits ahead of `main@4c0b8b5`)
**Verdict:** **READY for hand-off** — all automated gates green, all structural invariants hold, zero blockers. 25/25 user-spec ACs and 11/11 tech-spec ACs resolve to PASS (14 user-spec items carry an on-device observation pass that is covered by the user hand-off checklist below; none are structural FAIL).

---

## 1. Summary

| Dimension | Status |
|---|---|
| Gradle `:app:test` / `:core-runtime:test` / `:core-settings:test` | PASS (113 + 62 + 6 = 181 tests, 0 failures) |
| Gradle `:app:lintDebug` | PASS (0 errors, 46 pre-existing warnings; no new `MissingPermission` / `MissingClass` / `UnusedResources` / `HardcodedText` on Phase 2.5 files) |
| Gradle `:app:assembleDebug` | PASS (`app-debug.apk` built) |
| APK size delta vs `main` | +148.6 KB (within ≤ ~200 KB budget) |
| Structural greps (7) | PASS (all match expected results) |
| User-spec AC (25) | PASS — 11 structurally verified end-to-end, 14 structurally verified with on-device observation handed off for US-A/B/C integration run |
| Tech-spec AC (11) | PASS |
| Blockers | 0 |
| Open questions | 0 |

Upstream audits (Tasks 8 / 9 / 10) already concluded zero Critical / High / Medium / Major findings and their low-severity nits are documented drift — none re-opened here.

---

## 2. Automated Gates

### 2.1 `./gradlew :app:test`

```
BUILD SUCCESSFUL in 2s
166 actionable tasks: 166 up-to-date
```

Per-class results for the 7 Phase 2.5 test suites (from `app/build/test-results/testDebugUnitTest/`):

| Suite | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|
| `app.sanctum.machina.crash.CrashHandlerTest` | 7 | 0 | 0 | 0 |
| `app.sanctum.machina.crash.CrashStateTest` | 8 | 0 | 0 | 0 |
| `app.sanctum.machina.logexport.LogExportManagerTest` | 15 | 0 | 0 | 0 |
| `app.sanctum.machina.logexport.DeviceInfoCollectorTest` | 3 | 0 | 0 | 0 |
| `app.sanctum.machina.logexport.LogcatReaderTest` | 6 | 0 | 0 | 0 |
| `app.sanctum.machina.logexport.TapCounterTest` | 6 | 0 | 0 | 0 |
| `app.sanctum.machina.SanctumApplicationTest` | 2 | 0 | 0 | 0 |
| **Phase 2.5 total** | **47** | **0** | **0** | **0** |
| **`:app` module total** (incl. pre-existing) | **113** | **0** | **0** | **0** |

Verdict: **PASS**.

### 2.2 `./gradlew :core-runtime:test`

```
BUILD SUCCESSFUL in 1s
74 actionable tasks: 74 up-to-date
```

Aggregate: `tests=62 failures=0 errors=0 skipped=0`.
`ErrorLogTest` alone: `tests=8 failures=0 errors=0 skipped=0` (from its `<testsuite>` header) — confirms the whitelist regression gate is green. See also AC mapping §5 ("git-diff of `ErrorLog.kt` empty").

Verdict: **PASS**.

### 2.3 `./gradlew :core-settings:test`

```
BUILD SUCCESSFUL in 1s
130 actionable tasks: 130 up-to-date
```

Aggregate: `tests=6 failures=0 errors=0 skipped=0` (from `AppSettingsRepositoryTest`).

Verdict: **PASS** (no settings-repo regressions introduced by Phase 2.5; no files under `core-settings` were touched on this branch).

### 2.4 `./gradlew :app:lintDebug`

```
BUILD SUCCESSFUL in 20s
121 actionable tasks: 7 executed, 114 up-to-date
```

Counted from `app/build/reports/lint-results-debug.xml`:
- **Errors: 0**
- Warnings: 46 (breakdown: 18 `GradleDependency`, 12 `NewerVersionAvailable`, 6 `UnusedResources`, 3 `ObsoleteSdkInt`, 3 `AndroidGradlePluginVersion`, 2 `UseKtx`, 1 `OldTargetApi`, 1 `DefaultUncaughtExceptionDelegation`).

Relevance to the AC ("no new errors" on `MissingPermission` / `MissingClass` / `UnusedResources` / `HardcodedText`):
- `MissingPermission`, `MissingClass`, `HardcodedText`: 0 occurrences of any severity.
- `UnusedResources`: 6 warnings, all pre-existing keys (`btn_reset`, `settings_accelerator_gpu`, `settings_accelerator_cpu`, `attachment_audio_label`, `attachment_image_decode_failed`, `audio_record_start`). Grep-filter confirms zero Phase 2.5 string keys (`crash_*`, `log_export_*`, `about_diagnostics_*`, `dev_crash_*`) land in this list — all 10 new keys are wired in.
- `DefaultUncaughtExceptionDelegation` (new, but a warning not an error): informational — lint recommends delegating to the previous handler; by design we do not (Decision 4 + `CrashHandler` outer try/catch). Documented here, not treated as a regression.

Verdict: **PASS** (no new errors; no `UnusedResources` attributable to Phase 2.5 strings; warning delta is intentional per design).

### 2.5 `./gradlew :app:assembleDebug`

```
BUILD SUCCESSFUL in 7s
93 actionable tasks: 3 executed, 90 up-to-date
```

APK: `app/build/outputs/apk/debug/app-debug.apk` — **122,605,261 bytes (116.92 MiB)**.

Verdict: **PASS**.

---

## 3. APK Size Delta

Baseline measured by checking out `main@4c0b8b5` in a git worktree (`/tmp/pw-main`), copying `local.properties` over, and running `./gradlew :app:assembleDebug` against an identical toolchain.

| Branch | APK bytes | MiB |
|---|---:|---:|
| `main@4c0b8b5` (baseline) | 122,453,051 | 116.78 |
| `phase/2.5-logexport@e162d6c` | 122,605,261 | 116.92 |
| **Delta** | **+152,210** | **+0.145 (≈ 148.6 KB)** |

Budget: ≤ ~200 KB (user-spec "Агент проверяет" / tech-spec AC).
Result: **PASS** (74% of budget consumed).

The delta matches expectations: ~10 new Russian strings (~1 KB in `strings.xml`), ~1.3 KB of new byte-code per new Kotlin class across 11 net new `.kt` files in `app/crash/` and `app/logexport/`, plus Hilt-generated glue for `LogExportManager` / `CrashState` / `AboutViewModel` / `ModelManagerViewModel`-injection expansion. Zero new entries in `libs.versions.toml` or `app/build.gradle.kts` (confirmed via `git diff main`).

---

## 4. Structural Greps

All invariants from task spec §3 + tech-spec "Acceptance Criteria".

| # | Command | Expected | Actual | Match |
|---|---|---|---|---|
| 1 | `grep -rEn "androidx.compose\|androidx.activity" core-runtime/src/main core-settings/src/main` | 0 hits | 0 hits in both directories | ✅ |
| 2 | `grep -rEn "ErrorLog\|errorLog" app/src/main/kotlin/app/sanctum/machina/crash/` | 0 hits | 0 hits | ✅ |
| 3 | `grep -n 'android:process=":crash"' app/src/main/AndroidManifest.xml` | exactly 1 | 1 hit at line 49 | ✅ |
| 4 | `grep -n "setDefaultUncaughtExceptionHandler" app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt` (with `-B 3 -A 1`) | exactly 1, inside `getProcessName() == packageName` guard | 1 hit at line 24, inside `installCrashHandler()`, which is called only from the guard block at lines 17–19 | ✅ |
| 5 | `git diff main -- core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt` | empty | empty (`wc -l = 0`) | ✅ |
| 6 | `grep -rEn '(androidx\.hilt\|@AndroidEntryPoint)' app/src/main/kotlin/app/sanctum/machina/crash/CrashReportActivity.kt` | 0 hits (no annotation, no Hilt import) | 1 hit at line 45, but it is a KDoc sentence ("*Intentionally a plain `ComponentActivity` — NOT `@AndroidEntryPoint`*") — **not** an annotation or import. Annotation scan (`grep -n '^\s*@AndroidEntryPoint'`) → 0 hits; import scan (`grep -n '^import.*androidx\.hilt'`) → 0 hits. | ✅ (intent preserved) |
| 7 | `git diff main -- gradle/libs.versions.toml app/build.gradle.kts` | empty | empty (`wc -l = 0`) | ✅ |

Plus one additional invariant lifted from tech-spec Verification Plan and confirmed by inspection of `SanctumApplication.kt`:

- The handler install line (`Thread.setDefaultUncaughtExceptionHandler(...)` at line 24) runs *before* the `DefaultDownloadRepository.mainActivityFqn = …` assignment at line 20 — wait, actually line 20 **runs after** line 17–19 (the guard+install). Let me restate: source order is `if (getProcessName() == packageName) installCrashHandler()` → `DefaultDownloadRepository.mainActivityFqn = …`. The guard-install block is lines 17–19, the assignment is line 20. ✅ (correct order: handler armed first, so a `ClassLoader` failure on line 20 is captured).

Verdict: **all structural invariants PASS**.

---

## 5. Acceptance Criteria Coverage

Legend: **PASS** = verified end-to-end by tests + code reading, no runtime observation required; **PASS\*** = file / code logic verified by tests or code reading, **visual observation on a physical device handed off for final integration run** (US-A/B/C in §6); **FAIL** / **DEFERRED** not used — none encountered.

### 5.1 User-spec "Критерии приёмки"

#### Поведение при краше

| # | AC | Status | Evidence |
|---|---|---|---|
| U1 | Запись о краше сохраняется на диск до смерти процесса | PASS | `CrashHandler.uncaughtException` writes `crash.log` at line 60 before invoking `killer.kill` at line 69. `CrashHandlerTest` uses a `RecordingKiller` that reads `crash.log` inside its `kill()` body and observes a non-empty file (captures the ordering invariant). |
| U2 | Экран отчёта переживает смерть главного процесса | PASS\* | `CrashReportActivity` declared `android:process=":crash"` (`AndroidManifest.xml:49`) — Android OS guarantees a separate process lifecycle. Decision 3 documents the pattern. Cross-process survival is observable only on a real device; hand-off US-A covers it. |
| U3 | Сбой внутри обработчика → один краш, не бесконечный цикл | PASS | Outer `try { … } catch (outer: Throwable)` at `CrashHandler:49-73` emits one `Log.e` breadcrumb then calls `killer.kill` exactly once. `CrashHandlerTest.handlerInternalFailure_killsOnce` asserts `killer.invocations == 1`. |
| U4 | Длинный стэктрейс → обрезка ≤100 КБ с пометкой | PASS | `CrashHandler.headTruncate` at lines 98-103 returns `head + "\n[truncated at 100 KB]\n"` when input exceeds `MAX_CRASH_BYTES`. `CrashHandlerTest.stacktraceOver100KB_truncationMarkerAtEnd` seeds an oversized throwable and asserts both marker presence and size cap. |

#### Экран отчёта (CrashReportActivity)

| # | AC | Status | Evidence |
|---|---|---|---|
| U5 | Экран: заголовок, пояснение, две кнопки; все строки на русском через strings.xml | PASS | `CrashReportActivity.CrashReportScreen` (lines 122-155) composes `Text(R.string.crash_report_title)`, `Text(R.string.crash_report_body)`, `TextButton(R.string.log_export_save_button)`, `TextButton(R.string.btn_close)`. Strings present in `strings.xml:103-106` under `<!-- Crash report / diagnostics (Phase 2.5) -->`. Russian, no emoji, no "пожалуйста" — tone-compliant per ux-guidelines (verified in Task 1 review). |
| U6 | SAF с именем `sanctum-log-YYYYMMDD-HHmm.txt`, MIME `text/plain` | PASS\* | `CrashReportActivity:70-71` — `ActivityResultContracts.CreateDocument("text/plain")`; `CrashReportActivity:102` — `saveLogLauncher.launch("sanctum-log-${filenameTimestamp()}.txt")`; `filenameTimestamp()` formats `yyyyMMdd-HHmm` via `SimpleDateFormat` with `Locale.ROOT` (line 119). Task 6 user-verification observed the actual filename `sanctum-log-20260419-1156.txt` in SAF dialog. Hand-off US-A re-runs against fresh APK. |
| U7 | Успех: удаляет crash.log, Toast «Лог сохранён», закрывает экран | PASS\* | `CrashReportActivity.handleSafResult` (lines 105-116): `writeTo` → `File(filesDir, "logs/crash.log").delete()` → `Toast.makeText(applicationContext, R.string.log_export_success_toast, ...).show()` → `finish()`. `Toast.applicationContext` pattern from Task 4 security review (avoids activity leak). Delete + Toast + finish are visual; hand-off US-A confirms. |
| U8 | Отмена SAF → экран без сообщений, кнопки кликабельны | PASS\* | `CrashReportActivity.handleSafResult:105-106` — early return when `uri == null`, no Toast. `saveLogLauncher`'s `finally` (line 76-78) resets `launching = false` so buttons re-enable. Hand-off Negative-path covers. |
| U9 | IOException → Toast «Не удалось сохранить лог», crash.log не удаляется, кнопки кликабельны | PASS\* | `CrashReportActivity:113-115` `catch (_: IOException) { Toast(error) }`; `File.delete()` at line 110 runs only on success (after `writeTo` returns without throwing). `finally` at line 76-78 re-enables buttons regardless. `LogExportManagerTest.writeTo_ioException_surfaces` + `writeTo_nullOutputStream_surfacesAsIoException` verify the manager surfaces `IOException` correctly. Hand-off visually confirms Toast + kept file. |
| U10 | «Закрыть» → экран закрыт, crash.log на диске → баннер при следующем запуске | PASS | `CrashReportActivity:93` — `onCloseClick = ::finish`; no file deletion on Close path. At next cold-start `CrashState.refresh()` (called by `LaunchedEffect(Unit)` in `ModelManagerScreen:76`) reads `crash.log.exists() == true ∧ dismissedFlag.exists() == false` → banner renders. `CrashStateTest.dismissedThenNewCrash_reappears` covers the cross-component contract. |
| U11 | Повторное нажатие «Сохранить лог» до открытия диалога → второй диалог не открывается | PASS | `CrashReportActivity.onSaveClicked` (lines 99-103) checks `launching` guard; if `true`, returns. Guard set to `true` before `launch()`. Backed by `mutableStateOf` so Compose recomposes the button `enabled=!launching` atomically (line 146). Same pattern replicated in `ModelManagerScreen` and `AboutScreen`. |

#### Баннер после аварийного завершения

| # | AC | Status | Evidence |
|---|---|---|---|
| U12 | Баннер виден iff crash.log exists ∧ .dismissed отсутствует | PASS\* | `CrashState.refresh()` (lines 44-46) sets `_state.value = crashLog.exists() && !dismissedFlag.exists()`. `ModelManagerScreen:120` gates `RestartCrashBanner` by `if (hasUnresolvedCrash)`. `CrashStateTest` covers the 4-row truth table: (T,F)→true, (T,T)→false, (F,F)→false, (F,T)→false (equivalent to (F,F) by AND-short-circuit; nit F1 in Task 10). Cold-start visual: hand-off US-B. |
| U13 | Баннер «Сохранить лог» → SAF → crash.log удаляется, баннер исчезает, Snackbar | PASS\* | `ModelManagerScreen:87-101` wires the launcher to `viewModel.saveLogAndClearCrash(uri)` which (per `ModelManagerViewModel:77-80`) calls `logExportManager.writeTo` then `crashState.clear()` on success; `clear()` deletes both files and emits `false`. Snackbar shown via `snackbarHostState.showSnackbar(successMessage)` (line 94). Task 7 user-verified on Honor 200 (decisions.md). Hand-off US-B re-runs. |
| U14 | ✕ → баннер больше не виден до нового краша | PASS\* | `ModelManagerScreen:128` — `onDismissClick = viewModel::dismissCrashBanner` → `crashState.markDismissed()` (CrashState:48-52) creates the `.dismissed` flag and re-emits `false`. Flag survives process death (filesystem). Task 7 user-verified. Hand-off US-B re-runs multi-cycle. |
| U15 | Новый краш сбрасывает «скрытость» → баннер снова виден | PASS | `CrashHandler:51-54` unconditionally deletes `dismissedFlag` at the start of every crash-write. `CrashHandlerTest.preexistingDismissedFlag_deletedOnNewCrash` + `CrashStateTest.dismissedThenNewCrash_reappears` cover the end-to-end file-system cross-component contract. |

#### Раздел «Диагностика» в AboutScreen

| # | AC | Status | Evidence |
|---|---|---|---|
| U16 | «Диагностика» секция + одна кнопка «Сохранить лог» | PASS\* | `AboutScreen:165-178` renders `Text(R.string.about_diagnostics_title)` + `Button(R.string.log_export_save_button)`. Strings present in `strings.xml:110, 105`. Task 6 user-verified. |
| U17 | Кнопка доступна всегда, независимо от краша и скрытости баннера | PASS | No `CrashState` read anywhere in `AboutScreen`; button `enabled = !launching` only (lines 169-178). Structural: button surface is independent of crash state. |
| U18 | Нажатие → SAF → Snackbar; crash.log и `.dismissed` не меняются | PASS\* | `AboutScreen.saveLogLauncher` (lines 120-134) calls `viewModel.buildAndWrite(uri)` which wraps `ExportSource.About` through `LogExportManager` — **no file deletion, no CrashState mutation** on any branch. Snackbar shown via `snackbarHostState.showSnackbar`. Task 6 on-device verification observed `crash.log=[empty]` preserved. |
| U19 | 7-тап по строке версии → AlertDialog → Да бросает реальное исключение | PASS\* | `AboutScreen.AboutFooter:220-225` wraps version `Text` with `Modifier.clickable(indication = null) { onVersionTap() }`; `onVersionTap` in parent (line 163) = `if (tapCounter.tap()) showDialog = true`. `TapCounter.tap()` returns `true` on the 7th consecutive tap within 2s gaps (`TapCounterTest` covers all boundaries). `AlertDialog.confirmButton.onClick` (lines 188-195) calls `showDialog = false; throw RuntimeException("test crash from About")` directly on the UI thread (Decision 9). Task 6 user-verified on Honor 200 — stacktrace top frame was `AboutScreen.kt:194`, confirming the real UI-callback path (no Handler/Thread/launch frames). |

#### Содержимое экспортированного .txt

| # | AC | Status | Evidence |
|---|---|---|---|
| U20 | Технический хедер: все поля (дата, applicationId, version, device, memory, active model, downloaded models) | PASS (with nuance) | `DeviceInfoCollector.buildHeader` (lines 36-60) emits all required fields. `DeviceInfoCollectorTest.buildHeader_producesAllRequiredFields` asserts them line-by-line. **Nuance:** `AndroidDeviceInfoProvider.activeModelId()` / `downloadedModels()` are intentionally stubbed to `null` / `emptyList()` for Phase 2.5 (documented at `DeviceInfoCollector.kt:94-98, 127-132`, and in `decisions.md` Task 3). Header renders `active model: none` and `downloaded models:\n  (none)` — structurally correct, and the phase-gate text is acceptable for the closed-alpha channel per user-spec "Ограничения". Real registry wire-up tracked in NOTES.md backlog for Phase 3+. |
| U21 | Порядок секций: crash.log → errors.log → errors.log.1 → logcat | PASS | `LogExportManager.buildExport` (lines 77-97) appends sections in exactly this order; `errors.log.1` section is omitted (not empty-placeholdered) when the file doesn't exist (line 88-92). `LogExportManagerTest.allSectionsPresent_orderedCorrectly` seeds four distinct markers and compares `indexOf` positions to lock the order. |
| U22 | Отсутствующий/пустой errors.log или crash.log → `[empty]`, файл всё равно сохраняется | PASS | `LogExportManager.renderCrashLog:106` and `renderPlainFile:116` return `"[empty]"` when `!file.exists() || file.length() == 0L`. Covered by `LogExportManagerTest` (`missingErrorsLog_rendersEmpty`, `missingCrashLog_rendersEmpty`, `freshInstall_noLogsDir_succeeds`). |
| U23 | logcat: непустой из About, placeholder из CrashReport, placeholder при недоступности | PASS\* | `LogExportManager.renderLogcat` (lines 120-123): `ExportSource.CrashReport` → `"[logcat available only via About export]"`; `ExportSource.About` → `logcat.read()` (live). `LogcatReader.read` (lines 30-46) returns one of four placeholders (`timeout` / `unknown` / `exit=N` / `empty`) or raw output. `LogcatReaderTest` covers all four branches + argv shape (exactly 6 args, `^--pid=\d+$` pattern, no shell wrapping). Task 6 user-verified non-empty logcat on Honor 200 (HONOR ELI-NX9, Android 16). OEM paranoid-mode fallback will surface on hand-off US-C if present — placeholder guaranteed. |
| U24 | Общий размер `.txt` ≤ ~4.3 МБ | PASS (structural) | Section caps enforced: `crash.log` head-truncated to 100 KB (`LogExportManager:108-112` + marker); `logcat` tail-truncated to 100 KB with head-marker (`LogExportManager:125-131`); `errors.log` / `errors.log.1` already bounded to 2 MB each by `ErrorLog` rotation (unchanged; ErrorLog.kt diff empty); header ≤ ~1 KB (fixed-shape). Maximum: 100 + 2048 + 2048 + 100 + 1 = 4,297 KB ≈ 4.2 MB. `LogExportManagerTest` verifies both truncation directions (head/tail anchors) and marker presence. |

#### Инвариант на установке

| # | AC | Status | Evidence |
|---|---|---|---|
| U25 | Fresh install (нет logs/): экспорт из «О программе» работает | PASS | `LogExportManagerTest.freshInstall_noLogsDir_succeeds` runs `buildExport(About)` against an empty Robolectric filesystem and asserts: returns without throwing; output contains header; all four sections render `[empty]` or the appropriate placeholder. Robolectric seeds a clean `filesDir`. |

### 5.2 Tech-spec "Acceptance Criteria"

| # | AC | Status | Evidence |
|---|---|---|---|
| T1 | `:app:test` passes; all new tests green | PASS | §2.1 — 47 Phase 2.5 tests + 66 pre-existing = 113 tests, 0 failures. |
| T2 | `:core-runtime:test` passes; `ErrorLogTest` still green | PASS | §2.2 — 62 tests, 0 failures; `ErrorLogTest` alone 8/8. |
| T3 | `:core-settings:test` passes | PASS | §2.3 — 6 tests, 0 failures. |
| T4 | `:app:lintDebug` produces no new errors | PASS | §2.4 — 0 errors; no new `MissingPermission`/`MissingClass`/`UnusedResources`/`HardcodedText` attributable to Phase 2.5 files. |
| T5 | `:app:assembleDebug` builds APK; size increase ≤ ~200 KB | PASS | §2.5 + §3 — delta +148.6 KB. |
| T6 | core-runtime + core-settings: no Compose/Activity imports | PASS | §4 row 1. |
| T7 | `crash/` package: no `ErrorLog` references | PASS | §4 row 2. |
| T8 | Manifest: exactly one `android:process=":crash"` | PASS | §4 row 3. |
| T9 | SanctumApplication: exactly one `setDefaultUncaughtExceptionHandler`, inside the process-guard | PASS | §4 row 4. |
| T10 | `ErrorLog.kt` diff vs main: empty | PASS | §4 row 5. |
| T11 | No new deps in `libs.versions.toml` / `app/build.gradle.kts` | PASS | §4 row 7. |

### 5.3 Summary

Totals: **36 PASS, 0 FAIL, 0 true-DEFERRED (i.e., 0 items that cannot be verified at all before hand-off)**.

14 of the user-spec items carry a trailing visual/on-device observation marked **PASS\*** — each has its file-level or test-level evidence above, and is enumerated in §6 for the final fresh-install integration run on Honor 200. None of these are blockers; they are the conventional split for mobile QA where SAF dialogs, Toast/Snackbar rendering, cross-process Activity launch, and cold-start banner re-read are inherently device-side behaviours.

---

## 6. Hand-off Checklist for On-Device Verification (Honor 200, Android 16)

Install a fresh debug APK:

```
# If signing conflict with an older installed variant:
adb uninstall app.sanctum.machina

adb install app/build/outputs/apk/debug/app-debug.apk
```

Then run the four scenarios below. Each check references the user-spec AC it closes the loop on.

### Scenario 1 — US-C / Pipeline check (closes U16, U17, U18, U20, U21, U22, U23 logcat-non-empty branch)

- [ ] Open the app → tap the Info icon in the ModelManager top-bar → navigate to **«О программе»**.
- [ ] Scroll down past the manifest and AboutFooter → confirm **«Диагностика»** heading + one button **«Сохранить лог»**.
- [ ] Tap **«Сохранить лог»** → system SAF dialog appears with suggested filename `sanctum-log-YYYYMMDD-HHmm.txt` (MIME `text/plain`).
- [ ] Save into Downloads → Snackbar **«Лог сохранён»** at the bottom of the screen.
- [ ] Open the saved `.txt` in a file manager / text viewer. Verify:
  - [ ] Header starts with `=== Sanctum Machina diagnostic log ===`.
  - [ ] Header contains: `exported:`, `applicationId: app.sanctum.machina`, `version: … (…), debug=true`, `device: HONOR / <model> / Android …`, `memory: total=… GB, available=… GB`, `active model: none`, `downloaded models:\n  (none)` (Phase 2.5 stub — see U20 nuance).
  - [ ] Section `=== crash.log ===` contains `[empty]`.
  - [ ] Section `=== errors.log ===` contains either real log lines or `[empty]`.
  - [ ] Section `=== logcat ===` is **non-empty** (owns-pid ERROR output), **not** a placeholder.

### Scenario 2 — US-A / Crash path (closes U1, U2, U5, U6, U7, U19, U23 CrashReport-placeholder branch)

- [ ] In **«О программе»**, tap the version line **7 times** in quick succession (gaps ≤ 2 s).
- [ ] AlertDialog appears: **«Спровоцировать тест-краш?»** with **«Да»** / **«Отмена»**.
- [ ] Tap **«Да»** → the app dies → `CrashReportActivity` appears showing:
  - [ ] Title **«Приложение аварийно закрылось»**.
  - [ ] Body text about saving a technical log.
  - [ ] Two buttons: **«Сохранить лог»** and **«Закрыть»**.
- [ ] Tap **«Сохранить лог»** → SAF opens with suggested name `sanctum-log-YYYYMMDD-HHmm.txt`.
- [ ] Save into Downloads → Toast **«Лог сохранён»** → activity closes.
- [ ] Open the saved `.txt`. Verify:
  - [ ] Section `=== crash.log ===` contains a non-`[empty]` block starting with `=== Sanctum Machina crash record ===` and includes `ExceptionType: java.lang.RuntimeException`, `message: test crash from About`, followed by a stacktrace.
  - [ ] Section `=== logcat ===` is the placeholder **`[logcat available only via About export]`** (Decision 8).

### Scenario 3 — US-B / Restart banner (closes U10, U12, U13, U14, U15)

- [ ] Trigger another test-crash via the 7-tap gesture → **«Да»**.
- [ ] On `CrashReportActivity`, tap **«Закрыть»** (do **not** save).
- [ ] Relaunch the app from the launcher → `ModelManagerScreen` opens → **banner visible above the model list**: warning icon + text **«Прошлый запуск завершился аварийно.»** + **«Сохранить лог»** button + **✕** icon.
- [ ] Tap **«Сохранить лог»** in the banner → SAF → save → Snackbar **«Лог сохранён»** → banner disappears.
- [ ] Relaunch → banner stays hidden (no crash outstanding).
- [ ] Trigger another test-crash → **«Закрыть»** → relaunch → banner visible.
- [ ] Tap **✕** → banner disappears.
- [ ] Relaunch → banner still hidden (dismissed flag).
- [ ] Trigger another test-crash → relaunch → banner visible again (new crash cleared `.dismissed`).

### Scenario 4 — Negative paths (closes U8, U9 observationally)

- [ ] On `CrashReportActivity` (post-crash), tap **«Сохранить лог»** → in SAF tap system **Cancel/«Отмена»**. Screen stays, no Toast, button re-enabled and clickable.
- [ ] In **«О программе» → «Диагностика»**, tap **«Сохранить лог»** → in SAF tap system Cancel. Screen stays, no Snackbar, button re-enabled.
- [ ] Optional / best-effort: run out of disk space (fill internal storage close to full) or attempt to save into a read-only location — verify Toast **«Не удалось сохранить лог»** appears on CrashReportActivity and the previous crash log is still present for the next attempt.

---

## 7. Blockers / Open questions

**None.**

Known-and-accepted residual items (all tracked in tech-spec / user-spec / decisions.md and out of scope for Phase 2.5):
- `activeModelId` / `downloadedModels` stubbed to `null` / `emptyList` in `AndroidDeviceInfoProvider` — Phase 3+ registry wire-up (NOTES.md backlog).
- `DefaultUncaughtExceptionDelegation` lint warning on `CrashHandler` — intentional per Decision 4 + outer try/catch; not delegated to previous handler.
- Dev-gesture active in both debug and release builds on Phase 2.5 — per Decision 12 / user-spec "Ограничения"; Phase 5 wraps in `BuildConfig.DEBUG` (NOTES.md backlog).
- Unfiltered `.txt` contents (SELinux contexts, hardware ids, full stacktraces) — per Decision 11 / user-spec "Ограничения" for closed-alpha tester-to-developer channel.
- Native SIGSEGV (litertlm) — documented residual; falls back to system "app stopped" dialog, log lines may be captured in a later About-export.

**Upstream audit status:**
- Task 8 (code audit) — 3 LOW severity doc/style drift, not blocking (decisions.md entry).
- Task 9 (security audit) — 0 Critical / High / Medium / Low findings; 2 accepted residual risks (Decision 11, native SIGSEGV).
- Task 10 (test audit) — 0 Critical / Major / Minor findings; 3 optional nits. Approved.

**Verdict:** **READY — hand off to the user for the on-device integration run per §6.**
