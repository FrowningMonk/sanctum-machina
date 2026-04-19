# Code Audit Report — Phase 2.5 «Экспорт диагностического лога»

**Date:** 2026-04-19
**Skill:** `code-reviewing` (11 dimensions)
**Scope:** Tasks 1–7 (Wave 1 + Wave 2) — feature-wide holistic review
**Branch:** `phase/2.5-logexport`
**Auditor:** Claude Opus 4.7 (do-task)

## Files read

Production code:
- `app/src/main/kotlin/app/sanctum/machina/crash/CrashHandler.kt`
- `app/src/main/kotlin/app/sanctum/machina/crash/CrashState.kt`
- `app/src/main/kotlin/app/sanctum/machina/crash/Killer.kt`
- `app/src/main/kotlin/app/sanctum/machina/crash/CrashReportActivity.kt`
- `app/src/main/kotlin/app/sanctum/machina/crash/RestartCrashBanner.kt`
- `app/src/main/kotlin/app/sanctum/machina/logexport/LogExportManager.kt`
- `app/src/main/kotlin/app/sanctum/machina/logexport/DeviceInfoCollector.kt`
- `app/src/main/kotlin/app/sanctum/machina/logexport/LogcatReader.kt`
- `app/src/main/kotlin/app/sanctum/machina/logexport/TapCounter.kt`
- `app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/about/AboutScreen.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerViewModel.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/strings.xml`
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt` (control read — unchanged)

References (compared against, not audited):
- `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt` — SnackbarHost pattern.
- `app/src/main/kotlin/app/sanctum/machina/ui/chat/HeavyChangeDialog.kt` — two-button AlertDialog pattern.
- `app/src/main/kotlin/app/sanctum/machina/ui/theme/Theme.kt` — `SanctumTheme` definition.

Specs / references:
- `work/phase-2.5-logexport/user-spec.md`
- `work/phase-2.5-logexport/tech-spec.md`
- `.claude/skills/project-knowledge/references/patterns.md` (Module boundary, Error logging conventions, SafeMarkdown).

Tests were not audited (that's Task 10 / `test-master`). Security was scanned only for obvious regressions (full OWASP sweep is Task 9 / `security-auditor`).

---

## Structural checks

| # | Check | Expected | Actual | Verdict |
|---|-------|----------|--------|---------|
| 1 | `androidx.compose\|androidx.activity` in `core-runtime/src/main` and `core-settings/src/main` | 0 matches | 0 matches | **PASS** |
| 2 | `ErrorLog\|errorLog` in `app/src/main/kotlin/app/sanctum/machina/crash/` | 0 matches | 0 matches | **PASS** |
| 3 | `android:process=":crash"` in `AndroidManifest.xml` | exactly 1 | 1 match at line 49 | **PASS** |
| 4 | `setDefaultUncaughtExceptionHandler` in `SanctumApplication.kt` | exactly 1, inside `getProcessName() == packageName` guard | 1 match at line 24; guard at lines 17–19 wraps `installCrashHandler()` call | **PASS** |
| 5 | `@AndroidEntryPoint\|@Inject` in `CrashReportActivity.kt` | 0 matches | 0 annotations; the KDoc at line 45 mentions `@AndroidEntryPoint` only as prose explaining Decision 5 | **PASS** |
| 6 | `git diff main -- core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt` | empty | empty (also `git log main..HEAD -- …ErrorLog.kt` empty) | **PASS** |
| 7 | `\bLog\.(i\|w\|d)\(` across `app/.../crash/` and `app/.../logexport/` | 0 matches | 0 matches | **PASS** |
| 8 | `\bLog\.` in `app/.../crash/` | ≤1 `Log.e` breadcrumb | exactly one: `CrashHandler.kt:71` `Log.e(TAG, "handler failed", outer)` — matches tech-spec Flow A step 4 / Decision 10 | **PASS** |
| 9 | `SafeMarkdown\|Markdown\(` in `app/.../ui/about/` | 1 `SafeMarkdown` usage, 0 raw `Markdown(` | 1 SafeMarkdown at `AboutScreen.kt:160`, 0 raw Markdown | **PASS** |

All nine structural invariants pass.

---

## Cross-component review

### 1. Shared file-state between `CrashHandler` (writer) and `CrashState` (reader)

**PASS.**

Three pieces of shared state live on disk under `filesDir/logs/`: `crash.log` and `crash.log.dismissed` (both strings duplicated across three files). Checked:

- **Path semantics.** All three touch-points resolve the directory as `File(context.filesDir, "logs")`:
  - Writer: `CrashHandler.kt:50` `File(context.filesDir, LOG_DIR)`.
  - Main-process reader: `CrashState.kt:33` `File(context.filesDir, LOG_DIR)`.
  - `:crash`-process reader (`CrashReportActivity.kt:110`): `File(filesDir, "$LOGS_DIR/$CRASH_LOG")` — `filesDir` on a `ComponentActivity` resolves to the same per-app private dir as `Context.filesDir` (Android OS contract; the `:crash` process is the same app/uid so `filesDir` points at the same physical directory).
  - Export assembly: `LogExportManager.kt:78` `File(context.filesDir, LOG_DIR)`.
  - No cache dir, no external storage — all four call sites agree.

- **Overwrite semantics.** `CrashHandler` writes via `file.writeText(content, UTF_8)` (through the `crashLogWriter` lambda default) — a destination file is truncated before write. Second crash overwrites the first, matching the user-spec "overwrite-семантика" invariant. `LogExportManager` only ever reads; it cannot append.

- **`.dismissed` lifecycle.**
  - `CrashHandler.kt:51-54` — on every new crash, `.dismissed` is deleted *before* `crash.log` is written. This implements Flow B "новый краш сбрасывает скрытость". The order is correct: deleting `.dismissed` first means even if the subsequent `crash.log` write fails, the banner-suppression flag is already gone (fail-open: user will see the banner, not miss it).
  - `CrashState.markDismissed()` creates the flag via `createNewFile()` (zero-byte). Directory is `mkdirs()`-ed first for the freshly-installed case where `logs/` doesn't yet exist.
  - `CrashState.clear()` deletes both files, then `refresh()`. The two `.delete()` calls are not wrapped in any atomic primitive, but the user-spec wording "атомарно-enough для пользовательского восприятия" accepts a two-syscall window. `refresh()` re-reads truth from disk, so even a partial delete self-heals on the next refresh.

- **Truncation.** Head-truncate to 100 KB is owned solely by `CrashHandler` (`MAX_CRASH_BYTES = 100 * 1024`, `headTruncate()`). `CrashState` only checks `.exists()`. `LogExportManager` independently applies its own 100-KB head-truncation when reading `crash.log` (`LogExportManager.kt:105-113`). This is *belt-and-suspenders* — the file on disk is already bounded, so the reader's re-truncation is a no-op. Not a bug, just redundancy.

- **Truncation-marker format drift.** Worth noting: `CrashHandler` uses `"\n[truncated at 100 KB]\n"` (trailing newline), while `LogExportManager` uses `"\n[truncated at 100 KB]"` (no trailing newline). In practice `LogExportManager`'s path is dead — its reader only fires on a file >100 KB, which `CrashHandler`'s head-truncation cannot produce. But the two literals drift. See **finding L-2**.

### 2. Module boundary `:core-runtime` / `:core-settings` — UI-free

**PASS.** Structural check #1 returns zero hits. No new imports in either `core-*` module. The feature lives entirely under `:app`.

### 3. `patterns.md` compliance

- **Module hygiene (per check #1)** — **PASS**.
- **Error-logging conventions** (check #2, #7, #8) — **PASS**. The sole `android.util.Log.e("CrashHandler", ...)` at `CrashHandler.kt:71` is the single allowed breadcrumb inside the outer `catch (Throwable)` (Decision 10, Flow A step 4). `ErrorLog.ALLOWED_COMPONENTS` unchanged (check #6).
- **SafeMarkdown** (check #9) — **PASS**. `AboutScreen` still renders about-page markdown through `SafeMarkdown` (line 160); the new "Диагностика" section renders plain Compose `Text` (no markdown) for its title and uses `Button` for its action. Nothing introduced raw `Markdown(...)`.
- **Error-log format** (check #7) — **PASS**. No `Log.i`, `Log.d`, or `Log.w` anywhere in the feature. No `println` either.

### 4. Architectural consistency with existing Compose patterns

**PASS with one minor style observation.**

- **SnackbarHost on `ModelManagerScreen`.** New `Scaffold { snackbarHost = { SnackbarHost(state) } }` at `ModelManagerScreen.kt:103-117`, with `rememberCoroutineScope()` at line 79 and `snackbarHostState.showSnackbar(...)` at lines 94-95. This mirrors `ChatScreen.kt:68, 72, 195, 258` exactly. `AboutScreen` follows the same shape (line 114, 115, 127, 128, 150). Both Snackbar surfaces are new to this feature (ModelManager had none; About had none) and both reuse the established pattern.

- **Two-button pattern.**
  - `CrashReportActivity`: `TextButton` + `TextButton` at `CrashReportActivity.kt:146, 149` — matches `HeavyChangeDialog.kt:27, 32`.
  - `RestartCrashBanner`: `TextButton("Сохранить лог")` + `IconButton(Icons.Outlined.Close)`. Not two `TextButton`s, but this is a Row-in-Card banner, not an `AlertDialog`. The user-spec explicitly asks for "иконка ✕" here, and `IconButton` is the Material 3 idiom for a dismissable row. Pattern-consistent with the Material 3 banner recipe, distinct from the dialog recipe.
  - `AboutScreen` dev-crash `AlertDialog`: `confirmButton { TextButton ... }` + `dismissButton { TextButton ... }` — shape identical to `HeavyChangeDialog`. No hand-rolled `Dialog { Card {} }`.

- **`CrashReportActivity` theming.** Uses `SanctumTheme` (line 89), `MaterialTheme.typography.headlineSmall` / `bodyMedium` (lines 138, 142), `MaterialTheme.colorScheme.onSurfaceVariant` (line 143). No hardcoded hex, no hardcoded typography. Despite being non-Hilt (Decision 5), the Compose UI follows the rest-of-app convention.

- **Style observation (not a finding).** `AboutScreen` uses filled `Button` for "Сохранить лог" (line 169) while `CrashReportActivity` uses `TextButton` for the same text (line 146). This is intentional per Material 3 emphasis hierarchy: About has one diagnostic CTA among informational content → prominent `Button` is correct; CrashReport has two equal-weight actions → `TextButton` pair is correct. The difference is pattern-driven, not inconsistent.

### 5. Decisions 5 and 10

**PASS.**

- **Decision 5** — `CrashReportActivity.kt:55` is `class CrashReportActivity : ComponentActivity()` with no `@AndroidEntryPoint`. `LogExportManager` is instantiated at `CrashReportActivity.kt:86` via the non-Hilt secondary constructor `LogExportManager(applicationContext)`, which (at `LogExportManager.kt:71-75`) constructs `DeviceInfoCollector(AndroidDeviceInfoProvider(context))` and `LogcatReader(DefaultCommandRunner())` — the whole graph is manual, no Hilt. Grep #5 confirms zero Hilt annotations in the file. The file's KDoc at line 45 explicitly explains the deliberate opt-out.

- **Decision 10** — `CrashHandler` writes `crash.log` directly via the `crashLogWriter` lambda (`CrashHandler.kt:45, 60`), default `{ file, content -> file.writeText(content, Charsets.UTF_8) }`. No `ErrorLog.e` anywhere in `crash/` (grep #2). `ErrorLog.ALLOWED_COMPONENTS` in `core-runtime` unchanged (grep #6, git log check).

---

## 11-dimension holistic review

### 1. Architectural patterns

- Clear layering: `CrashHandler` (writer, no DI) / `CrashState` (Hilt `@Singleton`, filesystem-backed observable) / `LogExportManager` (Hilt `@Singleton` with non-Hilt secondary constructor) / `CrashReportActivity` (non-Hilt ComponentActivity) / `RestartCrashBanner` (stateless composable). Ownership and lifetimes are explicit.
- Two-construction-path pattern on `LogExportManager` is justified by Decision 5: main process uses Hilt, `:crash` uses the secondary constructor. The secondary constructor wires the same collaborators via `AndroidDeviceInfoProvider(context)` and `DefaultCommandRunner()` — no logic divergence between paths.
- Process boundary is respected: nothing in `:crash` reaches into main-process state; communication is filesystem-only (Decision 6).
- **Verdict:** no findings.

### 2. Separation of concerns

- `CrashHandler` does one thing (capture and recover). `CrashState` does one thing (expose `crash.log` existence as a flow). `LogExportManager` does one thing (assemble + write). `DeviceInfoCollector` / `LogcatReader` / `TapCounter` each own one concern. File sizes are all moderate (largest is `LogcatReader.kt` at 128 lines).
- `AboutViewModel` and `ModelManagerViewModel` each contain a narrow `suspend fun` (`buildAndWrite`, `saveLogAndClearCrash`) that wraps the same `LogExportManager` pair. Kept inside their host screen files — reasonable; splitting into separate files would add boilerplate without reuse.
- **Verdict:** no findings.

### 3. Code readability & maintainability

- Naming: `ExportSource.About` / `ExportSource.CrashReport`, `hasUnresolvedCrash`, `markDismissed`, `RestartCrashBanner`, `TapCounter` — all unambiguous, no cryptic abbreviations.
- Magic-number elimination is consistent: `MAX_CRASH_BYTES`, `MAX_LOGCAT_BYTES`, `LOGCAT_TIMEOUT_MS`, `TIMESTAMP_PATTERN`, `EMPTY_PLACEHOLDER`, `CRASH_REPORT_LOGCAT_PLACEHOLDER`. No hardcoded `100 * 1024` or `2_000L` in call sites.
- Comments explain *why*, not *what*: `CrashHandler.kt:32-41` docs the bootstrap-loop rationale; `ModelManagerScreen.kt:74-76` explains why `LaunchedEffect` re-reads filesystem truth despite the singleton; `AboutScreen.kt:188-192` explains the `showDialog = false` ordering before `throw`.
- One stale TODO — see **finding L-1**.
- **Verdict:** one LOW finding (stale TODO).

### 4. Error handling & logging

- `CrashHandler`: outer `try { ... } catch (Throwable) { Log.e(...); killer.kill(...) }` — does the right thing (breadcrumb + kill, no retry, no rethrow). The breadcrumb uses `android.util.Log.e` because `ErrorLog.e` is suspend and would require a coroutine scope inside a dying JVM (rationale in Decision 10). Killer is invoked in both success and failure paths (`CrashHandler.kt:69, 72`) — no path leaves the main process alive.
- `LogExportManager.writeTo`: null-returning `openOutputStream` is translated into `IOException("openOutputStream returned null")` (line 101). Any thrown `IOException` from `.write()` propagates. Caller (all three of `CrashReportActivity`, `AboutViewModel`, `ModelManagerViewModel`) catches only `IOException` — keeps the net tight, doesn't swallow `CancellationException` or other Throwables.
- `LogcatReader.DefaultCommandRunner`: `IOException` and `SecurityException` from `ProcessBuilder.start()` are swallowed into a null-exitCode result. `LogcatReader.read()` then classifies: timed out → `[logcat unavailable: timeout]`; null exit → `[logcat unavailable: unknown]`; non-zero exit → `[logcat unavailable: exit=N]`; empty stdout → `[logcat unavailable: empty]`. No exception reaches the export pipeline — correct: logcat failure must never abort the `.txt` build.
- Doc-vs-code drift on `DefaultCommandRunner` — see **finding L-2**.
- SAF cancel path (`uri == null`): the UI-layer Result wrappers (`AboutViewModel.buildAndWrite`, `ModelManagerViewModel.saveLogAndClearCrash`) never see null; the callbacks at `AboutScreen.kt:125-128` and `ModelManagerScreen.kt:92-95` gate on `if (uri != null)` so cancel is a no-op by design. `CrashReportActivity.kt:105-116` mirrors this with an early `if (uri == null) return`. All three surfaces clear `launching` in `finally` regardless of branch.
- **Verdict:** one LOW finding (doc drift).

### 5. Type safety

- Kotlin codebase, strict. `ExportSource` is an `enum class`, not a loose string. `LogcatResult.exitCode` is `Int?` with a documented contract ("`null` exactly when destroyed due to timeout or failed to start"). `DeviceInfoProvider.activeModelId(): String?` — nullability explicit and handled at the collector (`DeviceInfoCollector.kt:50` `... ?: "none"`).
- No `Any!!`, no `as Any`. Only `!!`-like construct is the null-output-stream branch, which is explicitly wrapped into an IOException rather than `!!`-ed.
- `@Suppress("DEPRECATION")` at `DeviceInfoCollector.kt:107` is localised and necessary for the `versionCode` / `longVersionCode` compatibility (even though our minSdk is 31, the suppression keeps the code compiling clean when `versionCode` is read on <P without a branch).
  - Minor observation: since minSdk is 31, the `Build.VERSION.SDK_INT >= Build.VERSION_CODES.P` branch is always taken and the `else` is dead code. Not worth calling out as a finding — the branch is self-documenting and compiles identically.
- **Verdict:** no findings.

### 6. Testing coverage

Out of scope for Code Audit (Task 10 covers test quality). Verification that the production-side interface seams are reachable:

- `Killer` seam — wired in `CrashHandler` constructor (default `Killer.Default`); `RecordingKiller`-style fake is possible. ✓
- `CommandRunner` seam — wired in `LogcatReader` constructor (default `DefaultCommandRunner`); fake is trivial. ✓
- `DeviceInfoProvider` interface — wired in `DeviceInfoCollector` constructor (default none — the Hilt binding path uses `AndroidDeviceInfoProvider`). ✓
- `LogExportManager.openOutputStreamForTest` — `internal` lambda var, tests can swap without Robolectric shadow gymnastics. ✓
- `TapCounter.nowNanos` — lambda, enables virtual-time testing without `System.nanoTime()` mocking. ✓

All seams are real — no seam that production code bypasses.

**Verdict:** no findings (in scope).

### 7. Dependencies

No new entries in `gradle/libs.versions.toml` or `app/build.gradle.kts` (tech-spec AC). All new code uses: Android SDK, Kotlin stdlib, AndroidX Compose Material 3 (already on classpath), AndroidX activity-compose, Hilt (already present), Material icons extended (already present).

- **Verdict:** no findings.

### 8. Security

Beyond the scope of this audit (Task 9 / `security-auditor` handles OWASP). Sanity-check pass:

- `LogcatReader` uses a fixed argv list (`"logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "*:E"`) passed to `ProcessBuilder(List<String>)`. No shell, no `bash -c`, no string interpolation beyond the integer own-pid. No command injection surface.
- `CrashReportActivity` declared `android:exported="false"` (manifest line 50). External apps cannot start it.
- `CrashReportActivity.onCreate` sets `FLAG_SECURE` on the window (line 85). This keeps the crash stacktrace out of Android Recents thumbnails and OEM screen-capture collectors — *unrequested* positive-security addition. Worth flagging as a good decision.
- `FileProvider` is not used (Decision 2 → SAF instead). No `grantUriPermission`, no `<provider>` in the manifest.
- SAF URIs are consumed via `ContentResolver.openOutputStream(uri)` — the user has already chosen the destination, no TOCTOU concern inside the `.use` block.
- Decision 11 (unfiltered content) is enforced by the *absence* of any redaction code — no half-finished regex scrubber that could mis-redact. Confirmed by reading `LogExportManager.renderCrashLog/renderPlainFile/renderLogcat`: the only transform applied is size truncation.
- **Verdict:** no findings in the holistic-audit scope. Full OWASP sweep is Task 9.

### 9. Performance

- `LogcatReader.DefaultCommandRunner` reads stdout **concurrently** with `waitFor` — at `LogcatReader.kt:93-109` a daemon drainer thread pipes the child's stdout into a `ByteArrayOutputStream` while `waitFor(timeoutMs, MILLISECONDS)` runs on the calling thread. This is the correct fix for the pipe-buffer deadlock noted in tech-spec Architecture: a single-threaded `waitFor`-then-read would block forever if the child's output exceeds the 64 KB OS pipe buffer before the timeout. The drainer joins with a 500 ms grace after the timeout/finish.
- `LogExportManager.buildExport` runs on `Dispatchers.IO`. `writeTo` also on `Dispatchers.IO`. Both are `suspend` — callers invoke from `viewModelScope` / `lifecycleScope` and do not block the main thread.
- In-memory build: the full `.txt` is materialised as a `String`. Total size is bounded by Decision 7 (~4.3 MB). Allocation is one-shot via `buildString`, which pre-sizes reasonably and grows via doubling. Acceptable for the advertised size envelope.
- `TapCounter` is O(1) per tap. `CrashState.refresh()` is two `File.exists()` syscalls — bounded regardless of directory contents.
- **Verdict:** no findings.

### 10. Cross-file consistency

- `CrashHandler` calls `setClassName(context.packageName, CRASH_REPORT_ACTIVITY_FQN)` where the FQN is the string constant `"app.sanctum.machina.crash.CrashReportActivity"`. The manifest declares `<activity android:name=".crash.CrashReportActivity" ... android:process=":crash" />` at `AndroidManifest.xml:47-53`. Package prefix `app.sanctum.machina` (from `AndroidManifest.xml` implicit package via applicationId) + `.crash.CrashReportActivity` = the same FQN. ✓
- `RestartCrashBanner(launching, onSaveClick, onDismissClick, modifier)` — signature at `RestartCrashBanner.kt:39-44` matches the call site at `ModelManagerScreen.kt:121-129`. ✓
- `LogExportManager.buildExport(ExportSource)` and `writeTo(Uri, String)` — callers at `CrashReportActivity.kt:108-109`, `AboutViewModel.kt:85-86`, `ModelManagerViewModel.kt:78-79` all match. ✓
- `CrashState.hasUnresolvedCrash`, `refresh()`, `markDismissed()`, `clear()` — exposed via `ModelManagerViewModel` (`.hasUnresolvedCrash` at VM line 44, `refreshCrashState` line 66, `dismissCrashBanner` line 69, `.clear()` inside `saveLogAndClearCrash` line 80). All reach the composable at `ModelManagerScreen.kt:63, 76, 128, 93`. ✓
- `TapCounter(nowNanos: () -> Long, maxGapNanos: Long = 2e9, threshold: Int = 7)` — `AboutScreen.kt:118` instantiates with the `System.nanoTime()` provider and default threshold/gap. ✓
- `SanctumTheme(content)` — called from `CrashReportActivity.kt:89`. ✓
- `ExportSource.About` / `ExportSource.CrashReport` — both enum values used (`About` at three call sites, `CrashReport` at `CrashReportActivity.kt:108`). ✓
- `R.string.*` resources — spot-checked `crash_report_title`, `crash_report_body`, `log_export_save_button`, `log_export_success_toast`, `log_export_error_toast`, `crash_banner_body`, `crash_banner_dismiss_description`, `about_diagnostics_title`, `dev_crash_dialog_title`, `dev_crash_dialog_confirm`, `btn_close`, `btn_cancel`, `btn_back`, `about_version_format`, `about_version_unknown`, `about_attribution`. All present in `strings.xml`. ✓
- **Verdict:** no findings.

### 11. Resource management

- `CrashHandler` has no persistent resources — one `File.writeText` per invocation (opens + closes stream synchronously).
- `CrashState` holds three `File` references; `File` is a pure-JVM path handle, no kernel resource.
- `LogExportManager.writeTo` uses `stream.use { ... }` — deterministic close on the SAF output stream even on exception.
- `LogcatReader.DefaultCommandRunner` uses `process.inputStream.use { ... }` inside the drainer thread. On timeout, `process.destroy()` is called and `drainer.join(500)` waits for the drainer to finish cleanly. `drainer` is a daemon, so even if `join` times out (pathological case), it will not block JVM exit.
- `LogExportManager` is a `@Singleton` in the main-process Hilt graph → one instance per process. The `:crash` process creates its own via the secondary constructor in `CrashReportActivity.onCreate` → one instance for the Activity's lifetime. No duplicated heavy resources.
- `DeviceInfoCollector` / `LogcatReader` / `TapCounter` — lightweight objects, no pooling concern.
- **Verdict:** no findings.

---

## Edge cases checked

- **Fresh install, no `logs/` dir.** `CrashHandler.kt:50` calls `.mkdirs()`. `CrashState.kt:33-35` constructs the `File` objects without touching disk; `refresh()` only calls `.exists()` which is false for missing paths (no NPE). `LogExportManager` opens files with `.exists() || .length() == 0L` guard — no throw. `CrashState.markDismissed()` calls `logsDir.mkdirs()` before `createNewFile()`. ✓
- **Double-tap on "Сохранить лог".** In all three surfaces (`CrashReportActivity`, `AboutScreen`, `ModelManagerScreen`) the guard is checked/set before launching SAF and cleared in `finally` inside the callback. The SAF launcher is `registerForActivityResult`/`rememberLauncherForActivityResult` — the platform serialises the callback. ✓
- **User cancels SAF.** `uri == null` branch: all three surfaces skip work, clear the guard, show no snackbar. User-spec AC matches. ✓
- **`CrashReportActivity` started from Application context.** `CrashHandler.context` is `applicationContext` (via `SanctumApplication.kt:24`). Starting an activity from non-Activity context requires `FLAG_ACTIVITY_NEW_TASK` — set at `CrashHandler.kt:65` alongside `FLAG_ACTIVITY_CLEAR_TASK`. ✓
- **Recursive handler install.** Process-name guard at `SanctumApplication.kt:17-19` skips install in `:crash`. `CrashReportActivity` itself never calls `setDefaultUncaughtExceptionHandler`. ✓
- **`android:taskAffinity=""` vs missing.** Manifest line 52 sets `android:taskAffinity=""` (empty-string), not absent. Correct per tech-spec Decision 3. ✓
- **Handler error after process killed.** `killer.kill` is called once per invocation (success path line 69, failure path line 72). The outer catch does not re-enter: if `killer.kill` itself threw, there is no further catch and the JVM's default handler would take over. In production this is effectively unreachable — `Process.killProcess` does not throw. ✓
- **`repeated crashes` overwrite.** `File.writeText` truncates; `CrashHandlerTest.repeatedCrashes_overwriteNotAppend` is the covering test (per tech-spec Testing Strategy). Production code is consistent with overwrite. ✓
- **`CrashState.clear()` and a concurrent `markDismissed()`.** Both called only from the UI thread via the ViewModel / composable callbacks. No locking needed, matches the class-level KDoc ("single-threaded (UI thread)"). ✓

---

## Findings summary

| Severity | File | Line | Issue | Recommendation |
|----------|------|------|-------|----------------|
| LOW | `app/.../crash/CrashHandler.kt` | 62 | Stale TODO: `"TODO(Task 4): switch to CrashReportActivity::class.java once the class lands."` Task 4 has landed; the activity class now exists. But replacing `setClassName(pkg, FQN)` with `Intent(ctx, CrashReportActivity::class.java)` would force class-loading of `CrashReportActivity` in the **main** process (which never renders it), polluting the main-process classpath with the `:crash`-only UI. The indirection is deliberate and should be kept. | Remove the TODO and replace it with a one-line explanation: "// Indirect class-name lookup avoids loading CrashReportActivity bytecode in the main process; the class exists only for the :crash process." |
| LOW | `app/.../logexport/LogcatReader.kt` | 76–79 | Doc comment on `DefaultCommandRunner` claims the `IOException` path "translated into an empty-stdout, non-error result so the caller renders `[logcat unavailable: empty]`". Actual classifier in `LogcatReader.read()` lines 40–46 returns `[logcat unavailable: unknown]` for this case, because `result.exitCode == null` is checked **before** `result.stdout.isEmpty()`. No behavioural impact (both placeholders look the same to the tester), but the doc drifts from the code. | Correct the KDoc to match: "... translated into a null-exitCode result so the caller renders `[logcat unavailable: unknown]`." Alternatively, re-order the classifier in `read()` to check stdout emptiness first — but that would obscure exit-code failures, so fixing the doc is preferable. |
| LOW | `app/.../logexport/LogExportManager.kt` | 22 vs `CrashHandler.kt` 17 | Truncation-marker string literal drifts: `LogExportManager.CRASH_TRUNC_MARKER = "\n[truncated at 100 KB]"` vs `CrashHandler.TRUNC_MARKER = "\n[truncated at 100 KB]\n"` (trailing newline). The `LogExportManager` branch is unreachable in practice (writer already bounds the file to 100 KB, so reader re-truncation is dead code), but the inconsistency would surface if a future change removed writer-side truncation. | Extract both to a single `const val CRASH_TRUNCATION_MARKER` in a shared location under `:app/crash/` or `:app/logexport/`, or — cheaper — simply align the string literals. Not urgent; dead-code path today. |

**Positive observations** (not findings, noted for the record):

- `CrashReportActivity.onCreate` sets `FLAG_SECURE` on the window (`CrashReportActivity.kt:85`) — keeps crash stacktraces out of Android Recents thumbnails and OEM screen-capture collectors. Not in the tech-spec; welcome addition.
- `LogExportManager.openOutputStreamForTest` is an `internal var` seam (line 67) — a clean way to make SAF write paths testable without Robolectric `ShadowContentResolver` gymnastics, while keeping the public surface of the class unchanged.
- `CrashHandler.crashLogWriter` is `internal val` (line 45) — narrowly-scoped test seam, invisible to non-test consumers.
- KDoc coverage on public API is consistent and explanatory: every new class carries at least a one-paragraph KDoc that cites the relevant tech-spec Decision numbers. This makes the cross-reference back to the spec trivial for future maintainers.
- No `!!` operator in the feature code (spot-checked). All nullability is explicit.

**11-dimension review: no CRITICAL, no HIGH, no MEDIUM findings.** Three LOW findings, all doc/style drift.

---

## Overall verdict

**PASS with minor findings.**

The Phase 2.5 log-export feature implements every user-spec and tech-spec requirement verified in scope, respects every structural invariant (module boundary, error-logging conventions, process guard, manifest declaration, non-Hilt opt-out, whitelist untouched), and correctly handles the cross-component file-state contract between writer (`CrashHandler` in main), Hilt reader (`CrashState` in main), direct reader (`CrashReportActivity` in `:crash`), and exporter (`LogExportManager` in both). Compose-pattern consistency with `ChatScreen` and `HeavyChangeDialog` is achieved without pixel-perfect mimicry — the new surfaces adopt the right Material 3 idioms for their context.

The three LOW findings are all minor polish (stale TODO, doc drift, literal duplication). None block pre-deploy QA (Task 11). They can be folded into a small follow-up commit or deferred to a later housekeeping pass.

No CRITICAL, HIGH, or MEDIUM issues. Ready to hand off to Task 9 (security audit) and Task 10 (test audit) in the Audit Wave.
