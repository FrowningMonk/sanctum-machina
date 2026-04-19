---
created: 2026-04-18
status: approved
branch: phase/2.5-logexport
size: M
---

# Tech Spec: Phase 2.5 — экспорт диагностического лога

## Solution

Three-path log export on top of the existing `filesDir/logs/` folder:

1. **Crash capture.** `CrashHandler` installs once in `SanctumApplication.onCreate` via `Thread.setDefaultUncaughtExceptionHandler`, guarded by `Application.getProcessName()` so it runs only in the main process. On crash it synchronously writes `filesDir/logs/crash.log` (overwrite, head-truncated to 100 KB), deletes `crash.log.dismissed` if present, launches `CrashReportActivity` in a separate OS process (`android:process=":crash"`), then kills the main process. A `try { … } catch(Throwable)` wraps the handler body and short-circuits to `Process.killProcess` on any internal failure, preventing a bootstrap loop.

2. **Export assembly.** `LogExportManager` (`@Singleton` under `:app/logexport/`) builds the export `.txt` as a single in-memory `String` on `Dispatchers.IO`. Sources are a device-info header, `crash.log`, `errors.log`, `errors.log.1`, and an own-pid `logcat -d -v threadtime --pid=<mypid> *:E` dump. Each missing/empty source renders a `[empty]` placeholder. The caller writes the assembled string to a SAF-picked `content://` URI.

3. **UI surfaces.** (a) `CrashReportActivity` — standalone Compose `ComponentActivity` (no Hilt, runs in `:crash`), two buttons, SAF via `ActivityResultContracts.CreateDocument("text/plain")`. (b) `AboutScreen` — new "Диагностика" section with one button plus a 7-tap dev-gesture on the version line that dispatches a `RuntimeException` on the main thread. (c) `ModelManagerScreen` — new restart-banner, visibility driven by a `StateFlow<Boolean>` from Hilt-injected `CrashState` that watches `crash.log` + `crash.log.dismissed`.

File-state semantics are filesystem-driven (no DataStore), because the `:crash` process and the main process only reliably share disk. No new external dependencies. No server component. No telemetry.

## Architecture

### What we're building/modifying

- **NEW `app/.../crash/CrashHandler.kt`** — installs `Thread.setDefaultUncaughtExceptionHandler`; synchronously writes `crash.log`, deletes `.dismissed`, spawns `CrashReportActivity` with `FLAG_ACTIVITY_NEW_TASK`, then `Process.killProcess(Process.myPid())`. Takes a `Killer` interface seam to allow unit-testing without actually killing the JVM.
- **NEW `app/.../crash/CrashReportActivity.kt`** — `ComponentActivity` (NOT `@AndroidEntryPoint`); `setContent { … }` two-button Compose screen; SAF launcher with in-flight guard flag.
- **NEW `app/.../crash/CrashState.kt`** — `@Singleton`, Hilt-injected. Exposes `val hasUnresolvedCrash: StateFlow<Boolean>`, `fun refresh()`, `fun markDismissed()`, `fun clear()`. State = `crash.log` exists ∧ `¬crash.log.dismissed` exists.
- **NEW `app/.../crash/RestartCrashBanner.kt`** — Compose composable. `Card { Row { Icon, Text, TextButton, IconButton } }`. Exposes `onSaveClick` / `onDismissClick` callbacks. The hosting screen owns an in-flight guard flag (`remember { mutableStateOf(false) }`) passed to the banner so repeated "Сохранить лог" taps before the SAF dialog returns are ignored.
- **NEW `app/.../logexport/LogExportManager.kt`** — `@Singleton`. `suspend fun buildExport(source: ExportSource): String` (enum `About` / `CrashReport`); `suspend fun writeTo(uri: Uri, content: String)` — uses `ContentResolver.openOutputStream(uri)` inside `.use`; a null return is treated as `IOException("openOutputStream returned null")` so the caller's catch handles the single error path. Section-aware truncation per Decision 7.
- **NEW `app/.../logexport/DeviceInfoCollector.kt`** — produces the `.txt` header. Takes `PackageInfo`, `ActivityManager`, `Build` constants via an interface so tests can stub.
- **NEW `app/.../logexport/LogcatReader.kt`** — spawns `ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "*:E").redirectErrorStream(true)` and reads stdout to completion on a worker thread concurrently with `waitFor(2, SECONDS)` — required to avoid pipe-buffer deadlock when the child produces more than the OS pipe buffer (typically 64 KB) before the timeout fires. On timeout the subprocess is `destroy()`-ed. Returns the captured bytes (tail-truncated by caller) or a `[logcat unavailable: <reason>]` placeholder. A `CommandRunner` interface seam makes the test double deterministic.
- **NEW `app/.../logexport/TapCounter.kt`** — pure 7-tap detector, no Android deps. Takes a `nowNanos: () -> Long` for testability.
- **MODIFY `app/.../SanctumApplication.kt`** — process-name guard; install `CrashHandler` before the existing `DefaultDownloadRepository.mainActivityFqn = …` assignment (so a crash in that line is still captured).
- **MODIFY `app/.../ui/about/AboutScreen.kt`** — append "Диагностика" section; wrap version text with `TapCounter` gesture + confirm dialog.
- **MODIFY `app/.../ui/modelmanager/ModelManagerScreen.kt`** — insert `RestartCrashBanner` above the model list; wire SAF launcher, `CrashState.clear()`, `CrashState.markDismissed()`, `SnackbarHost`.
- **MODIFY `app/src/main/AndroidManifest.xml`** — add `<activity android:name=".crash.CrashReportActivity" android:process=":crash" android:exported="false" android:excludeFromRecents="true" android:taskAffinity="" android:theme="@style/Theme.Sanctum" />`.
- **MODIFY `app/src/main/res/values/strings.xml`** — ~12 new Russian keys.

### How it works

**Flow A — Crash → CrashReportActivity → SAF (US-A):**
1. A thread in the main process throws an uncaught exception.
2. JVM invokes `CrashHandler.uncaughtException(t, e)`.
3. Handler, wrapped in outer `try`: (a) deletes `crash.log.dismissed`; (b) writes stacktrace (head-truncated to 100 KB) to `filesDir/logs/crash.log`; (c) `startActivity(Intent(ctx, CrashReportActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK))`; (d) `Process.killProcess(Process.myPid())` via the injected `Killer` seam.
4. Any `Throwable` inside the handler → outer `catch` emits a single `android.util.Log.e("CrashHandler", "handler failed", t)` breadcrumb (non-suspend, lands in logcat — captured by later About-export) → `Process.killProcess` without retrying any step.
5. OS spawns `:crash` process → `SanctumApplication.onCreate` runs again but the process-name guard skips handler installation → `CrashReportActivity` launches.
6. User taps "Сохранить лог": an in-flight guard flag (`var launching: Boolean`) flips to `true` and the SAF `CreateDocument` launcher fires with suggested name `sanctum-log-YYYYMMDD-HHmm.txt`. Further taps are ignored while `launching == true`.
7. On URI result (the guard flag is cleared in a `finally` in the result callback regardless of branch):
   - `uri != null` → `LogExportManager.buildExport(CrashReport)` → `writeTo(uri, content)`. On success: delete `crash.log`, Toast "Лог сохранён", `finish()`. On `IOException` or null-returning `openOutputStream`: Toast "Не удалось сохранить лог", keep `crash.log`, buttons re-enabled via guard clear.
   - `uri == null` (user cancelled) → guard clears, buttons re-enabled, no Toast.
8. "Закрыть" → `finish()`. `crash.log` stays → next cold-start shows banner.

**Flow B — Restart banner (US-B):**
1. Cold-start: `ModelManagerScreen` `LaunchedEffect(Unit)` calls `crashState.refresh()`.
2. `refresh()` emits `true` iff `crash.log` exists ∧ `crash.log.dismissed` does not.
3. Banner renders above model list.
4. "Сохранить лог": in-flight guard flag on the banner flips to `true` → shared SAF launcher → `LogExportManager.buildExport(About)` → `writeTo(uri)`. On URI result, guard is cleared in `finally`; outcomes:
   - Success → `crashState.clear()` (deletes both files, emits `false`, banner disappears) + Snackbar "Лог сохранён".
   - `IOException` / null URI cancel → Snackbar "Не удалось сохранить лог" on IOException (silent on cancel); `crashState.clear()` NOT called; `crash.log` and banner stay so the user can retry.
5. ✕ → `crashState.markDismissed()` (touches `.dismissed`, emits `false`).
6. A later crash → handler deletes `.dismissed` in step 3a → banner reappears on the next cold-start.

**Flow C — Proactive export from About (US-C):**
1. User opens "О программе" → taps "Сохранить лог" in "Диагностика".
2. SAF launcher fires; `uri` returned.
3. `LogExportManager.buildExport(About)` → logcat section populated by `LogcatReader` (2-s timeout, tail-truncated to 100 KB).
4. `writeTo(uri)` → Snackbar "Лог сохранён". `crash.log` and `.dismissed` untouched.

**Dev-gesture (same screen):** `TapCounter` behind `Modifier.clickable` on the version `Text`. 7 taps with gaps ≤ 2 s each → `AlertDialog` "Спровоцировать тест-краш?". Confirm → `throw RuntimeException("test crash from About")` directly from the dialog's main-thread onClick → propagates into `CrashHandler` via Flow A.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|-----------------|-----------|----------------|
| `filesDir/logs/crash.log` (file) | `CrashHandler` (writer) | `CrashReportActivity`, `CrashState`, `LogExportManager` (readers) | 0–1 on disk |
| `filesDir/logs/crash.log.dismissed` (flag) | `CrashState.markDismissed` (writer); `CrashHandler` (deleter) | `CrashState` (reader) | 0–1 on disk |
| `LogExportManager` (main proc) | `LogExportModule` (Hilt @Singleton) | `AboutScreen`, `ModelManagerScreen` | 1 per process (main-process Hilt graph) |
| `CrashState` (main proc) | `LogExportModule` (Hilt @Singleton) | `ModelManagerScreen` | 1 per process |
| `LogExportManager` (crash proc) | `CrashReportActivity` (direct `new`, no Hilt) | `CrashReportActivity` itself | 1, local to the activity |

`:crash` and the main process never share live objects — only the filesystem. Decision 5 covers the deliberate non-Hilt construction in `:crash`.

## Decisions

### Decision 1: Single `.txt` export, not a zip
**Decision:** Assemble one UTF-8 text file with `=== section ===` dividers; no archiving.
**Rationale:** Supports US-C tester workflow ("видно глазами что именно пересылается"); removes `java.util.zip` + FileProvider + URI-permission plumbing. Section caps (Decision 7) keep total ≤ ~4.3 MB — messenger-attachable.
**Alternatives considered:** Zip per section — rejected (user-spec "Технические решения" first bullet explicitly prefers text).
**Anchors:** user-spec US-A/B/C, "Технические решения" bullet 1.

### Decision 2: SAF `CreateDocument`, not share-intent with FileProvider
**Decision:** Use `ActivityResultContracts.CreateDocument("text/plain")` with suggested filename `sanctum-log-YYYYMMDD-HHmm.txt`.
**Rationale:** User picks destination; no FileProvider registration, no `FLAG_GRANT_READ_URI_PERMISSION`, no third-party chooser; cancel path returns `null` URI cleanly.
**Alternatives considered:** Write to `filesDir/exports/` then fire share-intent with FileProvider — rejected (extra manifest `<provider>`, `file_paths.xml`, and a third-party app in the chain that may ignore the file).
**Anchors:** user-spec AC "Экран отчёта (CrashReportActivity)", "Технические решения" bullet 2.

### Decision 3: `CrashReportActivity` in `android:process=":crash"`
**Decision:** Declare the activity with `android:process=":crash"`, `exported="false"`, `excludeFromRecents="true"`, `taskAffinity=""`, use the existing `Theme.Sanctum` style from `app/src/main/res/values/themes.xml`.
**Rationale:** Survives `Process.killProcess(myPid)` on the main process — the well-known ACRA pattern. Without it we would have to either leave a corrupted JVM running or require the user to relaunch manually.
**Alternatives considered:** (a) don't kill main, show a Dialog — rejected, post-crash JVM state is undefined; (b) port ACRA as a library — rejected, external-dep overhead for ~200 lines of code we control.
**Anchors:** user-spec US-A step 2, Risks row 2.

### Decision 4: Install `UncaughtExceptionHandler` only in the main process
**Decision:** In `SanctumApplication.onCreate`, guard with `if (getProcessName() == packageName) { installCrashHandler() }` before assigning `DefaultDownloadRepository.mainActivityFqn`.
**Rationale:** `Application.onCreate` runs in every OS process that uses the `<application android:name>`. Installing the same handler in `:crash` would recurse on a second crash. `minSdk = 31` > 28, so `Application.getProcessName()` is always available.
**Alternatives considered:** Install a minimal no-op handler in `:crash` — rejected (complexity with no benefit; the default Android handler already routes to the system "app stopped" dialog).
**Anchors:** user-spec Risks row 2, "Технические решения" bullet 4.

### Decision 5: `CrashReportActivity` opts out of Hilt
**Decision:** Plain `ComponentActivity`, NOT `@AndroidEntryPoint`. Instantiates `LogExportManager` directly via a non-Hilt constructor in `onCreate`.
**Rationale:** `:crash`'s Hilt graph is fresh and does not share singletons with the main process anyway. Any runtime binding failure in Hilt's generated code during recovery would turn the crash screen into a second crash. The activity's needs (Context, PackageInfo, file I/O, SAF) do not require DI.
**Alternatives considered:** `@AndroidEntryPoint` + `@Inject LogExportManager` — rejected (one more failure surface on the exact path we need to keep bulletproof).
**Anchors:** code-research §8.1; **[TECHNICAL]** — supports user-spec Risks row 1 ("Bootstrap loop").

### Decision 6: Filesystem-only banner state
**Decision:** Banner visibility = `crash.log` exists ∧ `¬crash.log.dismissed` exists. No DataStore, no SharedPreferences.
**Rationale:** `:crash` cannot safely touch DataStore (coroutines, mutex, async IO mid-crash). Filesystem is the one medium both processes already use for the log itself. Easier to inspect via `adb shell run-as … ls logs/` during bring-up.
**Alternatives considered:** DataStore boolean — rejected (two sources of truth, `:crash`-to-main sync risk).
**Anchors:** user-spec "Технические решения" bullet 6, US-B mechanic.

### Decision 7: Per-section size caps
**Decision:** `crash.log` ≤ 100 KB (head-truncated, preserves exception type + top frames); `logcat` ≤ 100 KB (tail-truncated, preserves most recent lines); `errors.log` ≤ 2 MB (already enforced by `ErrorLog` rotation); `errors.log.1` ≤ 2 MB (same); header ≤ ~1 KB. Truncation marker `[truncated at NNN KB]` appended when it fires.
**Rationale:** Keeps total `.txt` ≤ ~4.3 MB — attachable through any mobile messenger (user-spec "Размер .txt ≤ 4.3 МБ"). In-memory build stays bounded.
**Alternatives considered:** No caps — rejected (unbounded memory, messenger limits); per-file split — rejected per Decision 1.
**Anchors:** user-spec "Ограничения", AC "Общий размер `.txt` не превышает ~4.3 МБ".

### Decision 8: Logcat section differs per `ExportSource`
**Decision:** `LogExportManager.buildExport(source: ExportSource)` with `ExportSource.About` → live `LogcatReader` output; `ExportSource.CrashReport` → placeholder `[logcat available only via About export]`.
**Rationale:** From `:crash`, `Process.myPid()` is the fresh crash-process pid (empty log); the main-process pid is unreachable by that point. Stating the limitation in the file is clearer than silently producing `[empty]`.
**Alternatives considered:** Collect logcat in `CrashHandler` before killing main — rejected (handler must stay under ~10 ms to dodge ANR-cascade on top of the original crash; reading logcat spawns a child that can hang on OEM paranoid-mode).
**Anchors:** user-spec AC "Секция `logcat` содержит…", Risks row 3, "Технические решения" bullet 5.

### Decision 9: Test-crash throws on the main thread
**Decision:** `AlertDialog.confirmButton.onClick = { throw RuntimeException("test crash from About") }`. No `Handler.post`, no `Thread { … }`, no `launch`.
**Rationale:** Reproduces the exact crash path of a real bug in a UI callback. A background-thread throw would still be caught by the thread-global handler but bypasses the Compose/main-loop path that typical production crashes follow.
**Alternatives considered:** `Thread { throw … }.start()` — rejected (tests only half of the handler contract).
**Anchors:** user-spec "Технические решения" bullet 7, Dev-gesture description.

### Decision 10: Own writer for `crash.log`; `ErrorLog` whitelist untouched
**Decision:** `CrashHandler` writes `crash.log` directly via `File.writeText` with head-truncation. It does NOT call `ErrorLog.e(…)`. `ErrorLog.ALLOWED_COMPONENTS` is NOT extended with a `"crash"` value.
**Rationale:** `ErrorLog.e` is `suspend` and requires a coroutine scope/dispatcher — unsafe in an uncaught handler where the JVM is about to die. `ErrorLog`'s single-line format (`ERROR [component] description :: cause`) does not fit a multi-line stacktrace. Keeping crash.log as a separate channel preserves `ErrorLog`'s bounded, grep-friendly format for the rest of the codebase.
**Alternatives considered:** Extend `ALLOWED_COMPONENTS` and call `errorLog.e("crash", …)` — rejected (dispatcher not guaranteed live during uncaught, format mismatch, violates user-spec "Whitelist не расширяется").
**Anchors:** user-spec "Ограничения" "Whitelist `ErrorLog.ALLOWED_COMPONENTS` не расширяется", "Технические решения" last bullet.

### Decision 11: Exported `.txt` is unfiltered on Phase 2.5
**Decision:** `LogExportManager` does not redact, sanitize, or hash content from `crash.log`, `errors.log`, `errors.log.1`, or `logcat`. Stacktraces, SELinux contexts, hardware identifiers (manufacturer/model), and third-party library error messages may all appear verbatim in the exported `.txt`.
**Rationale:** Diagnosis completeness outranks privacy on the closed-alpha tester-to-developer channel. User controls the destination via SAF — file never leaves the device without an explicit user action. User-spec "Ограничения" explicitly accepts this trade-off for Phase 2.5.
**Alternatives considered:** Redaction pipeline for hardware ids / SELinux — rejected (adds regex surface that can mis-redact, and tester-to-dev channel does not need it); sanitization mirroring `ErrorLog`'s 200-char cause truncation — rejected (defeats the purpose of a full stacktrace for diagnosis).
**Anchors:** user-spec "Ограничения" "Приватность: на фазе закрытого тестирования фильтрация содержимого не применяется".

### Decision 12: Dev-gesture NOT wrapped in `BuildConfig.DEBUG` for Phase 2.5
**Decision:** The 7-tap gesture is active in both debug and release builds in Phase 2.5.
**Rationale:** User-spec "Ограничения" explicitly opts for this so the developer's own test APK and the tester's identical APK share behaviour. Phase 5 wraps it in `BuildConfig.DEBUG` before first public release — tracked in NOTES.md backlog.
**Alternatives considered:** Wrap now — rejected (user-spec decision).
**Anchors:** user-spec "Ограничения" "Dev-gesture доступен и в debug-, и в release-сборке на Phase 2.5".

## Data Models

File layout under `filesDir/logs/`:

```
filesDir/logs/
├── errors.log               # existing — ErrorLog live file (≤2 MB)
├── errors.log.1             # existing — ErrorLog rotated copy (≤2 MB)
├── crash.log                # NEW — latest uncaught exception (overwrite, ≤100 KB)
└── crash.log.dismissed      # NEW — zero-byte flag; presence = user tapped ✕
```

`crash.log` format (UTF-8):

```
=== Sanctum Machina crash record ===
timestamp: 2026-04-18T14:23:05.123+05:00
thread: <Thread.name>
ExceptionType: <fully.qualified.Type>
message: <one-line message, if any>

at ...
at ...
Caused by: ...
    at ...
[truncated at 100 KB]     # present iff truncation fired
```

Exported `.txt` top-to-bottom sections:

```
=== Sanctum Machina diagnostic log ===
exported: 2026-04-18T14:25:00+05:00
applicationId: app.sanctum.machina
version: 0.1.0 (1), debug=true
device: Honor / HONOR 200 / Android 14 (API 34)
memory: total=11.7 GB, available=6.2 GB
active model: litert-community/gemma-4-E2B-it-litert-lm
downloaded models:
  - litert-community/gemma-4-E2B-it-litert-lm (3.1 GB)

=== crash.log ===
<content or [empty]>

=== errors.log ===
<content or [empty]>

=== errors.log.1 ===
<content, or skipped entirely if file does not exist>

=== logcat ===
<content, or [logcat available only via About export], or [logcat unavailable: <reason>]>
```

No schemas, no DB changes, no DataStore changes.

## Dependencies

### New packages
None.

### Using existing (from project)
- `androidx.activity:activity-compose` (1.10.1) — `ActivityResultContracts.CreateDocument`, `rememberLauncherForActivityResult`.
- `androidx.compose.material3` (BOM 2026.03.00) — `Card`, `Scaffold`, `AlertDialog`, `TextButton`, `Snackbar`, `SnackbarHost`.
- `androidx.compose.material.icons.extended` (1.7.8) — `Icons.Outlined.Warning`, `Icons.Outlined.Close`, `Icons.Outlined.Download`.
- `androidx.hilt:hilt-android` (2.57.1) — `@Singleton` for `LogExportManager` and `CrashState` in the main-process graph.
- Android SDK: `Thread.setDefaultUncaughtExceptionHandler`, `Process.killProcess`, `Process.myPid`, `Application.getProcessName()` (API 28+, our minSdk is 31), `ContentResolver.openOutputStream`, `ProcessBuilder`, `ActivityManager.MemoryInfo`, `Build`.
- Test infra already on classpath: `org.robolectric:robolectric` 4.12, `androidx.test:core` 1.6.1, `kotlinx-coroutines-test` 1.10.2, `junit:junit` 4.13.2. `testOptions.unitTests.isIncludeAndroidResources = true` already enabled in `app/build.gradle.kts`.

## Testing Strategy

**Feature size:** M

### Unit tests

All new test classes under `app/src/test/kotlin/…`, mirroring the Robolectric pattern of `core-runtime/.../ErrorLogTest.kt`.

- **`LogExportManagerTest`** — header contains all required fields; `allSectionsPresent_orderedCorrectly` — seed all four files + stubbed logcat with distinctive markers, assert dividers appear in order `crash → errors → errors.1 → logcat` by string-index comparison; missing `errors.log` → `[empty]`; missing `crash.log` → `[empty]`; `ExportSource.CrashReport` → logcat placeholder `[logcat available only via About export]`; `ExportSource.About → logcat populated` with live stubbed `CommandRunner` output (positive path, complements CrashReport placeholder); **logcat tail-truncation** — huge input, assert the final characters match the input's tail AND a `[truncated: head ... bytes]`-style marker is prepended (directional anchor, not just length); **crash.log head-truncation** — huge input, assert the initial characters match the input's head AND a `[truncated at 100 KB]` marker appended; `errors.log.1` absent → section omitted entirely (no empty divider); `freshInstall_noLogsDir_succeeds` — no `logs/` folder yet, `buildExport(About)` returns header + all-`[empty]` sections without throwing; `writeTo_ioException_surfaces` — `ShadowContentResolver` forces IOException via `openOutputStream(uri)` throwing, caller distinguishes from success; `writeTo_nullOutputStream_surfacesAsIoException` — separate case where the shadow returns `null` from `openOutputStream`, asserts the wrapped `IOException("openOutputStream returned null")` reaches the caller (guards the `!!` refactor risk); `emptyDownloadedModels_rendersBlankList` — header when registry reports zero downloaded models shows `downloaded models:` followed by `(none)`, not a trailing colon with nothing.
- **`DeviceInfoCollectorTest`** — deterministic formatting from stubbed provider (manufacturer, model, Android version, RAM numbers, active model id / `none`, downloaded-model list ordering).
- **`CrashHandlerTest`** — uncaught exception writes `crash.log` before the injected `Killer` is invoked — asserted via a `RecordingKiller` fake that reads `filesDir/logs/crash.log` inside its `kill()` body and stashes the observed bytes; the test then asserts the stashed bytes contain the exception type (hand-rolled fake, no Mockito/MockK — consistent with `ErrorLogTest` style per code-research §9.3); pre-existing `.dismissed` deleted as part of the new crash write (US-B "новый краш сбрасывает скрытость" cross-component contract); `repeatedCrashes_overwriteNotAppend` — second invocation of the handler with a different exception leaves `crash.log` containing only the second stacktrace and not a concatenation (enforces user-spec "overwrite-семантика"); internal failure → killer called exactly once, no re-entry; stacktrace >100 KB → truncation marker present at end; `handlerShape_noSuspendCallsNoCoroutineImports` — reflection-level check that the handler class has no `Continuation` parameters on any declared method (enforces Decision 10 without reading source-file text, which is brittle).
- **`CrashStateTest`** — `crash.log` exists + no `.dismissed` → `hasUnresolvedCrash = true`; both exist → `false`; neither → `false`; `markDismissed()` flips flow to `false` and creates flag; `clear()` deletes both files; `refresh()` re-reads after external change (simulates the `ModelManagerScreen` lifecycle refresh); fresh-install (no `logs/` folder) → `false`, no exception; `dismissedThenNewCrash_reappears` — seed `.dismissed`, run `CrashHandler` on a fake exception, call `refresh()` → flow flips back to `true` (end-to-end US-B cross-component contract at the file-system boundary, complements `CrashHandlerTest`'s `.dismissed`-deletion assertion).
- **`TapCounterTest`** — pure JVM, no Robolectric. 7 taps ≤2 s apart → triggers on 7th; gap >2 s → counter resets to 1; gap exactly 2 s → within window (inclusive boundary); gap = 2 s + 1 ns → reset (exclusive above); <7 → does not trigger; trigger fires once per 7-tap cycle, not repeatedly on the 8th tap.
- **`LogcatReaderTest`** — stubs `ProcessBuilder` via a `CommandRunner` interface seam: empty output → `[logcat unavailable: empty]`; non-zero exit → `[logcat unavailable: exit=N]`; 2-s timeout → `[logcat unavailable: timeout]`; happy path → raw output returned verbatim (tail truncation is the caller's responsibility, covered in `LogExportManagerTest`); argv-shape assertion — the runner receives exactly six args; arg 0 `"logcat"`, args 1-3 `"-d"`, `"-v"`, `"threadtime"`, arg 4 matches `^--pid=\d+$`, arg 5 `"*:E"` — no shell wrapping (guards Decision 8 + security A03).
- **`SanctumApplicationTest`** (Robolectric) — `getProcessName() == packageName` path installs the handler; a stubbed process name `"${packageName}:crash"` skips installation. Keeps Decision 4 from regressing silently.

`core-runtime/.../ErrorLogTest.kt` is NOT modified — whitelist unchanged per Decision 10.

### Integration tests
None as `androidTest`-flavoured instrumentation (not configured in the project — see code-research §9.1). The SAF system dialog, the `:crash` process boundary, and the platform `Thread.setDefaultUncaughtExceptionHandler` dispatch all require instrumentation. These paths are covered by manual user verification per user-spec "Как проверить / Пользователь проверяет".

Three Robolectric-level composition checks are in scope on the existing unit classpath and are folded into the classes listed above (rather than a separate integration tier): (a) `CrashHandlerTest` exercises the handler → filesystem → Killer sequence end-to-end; (b) `LogExportManagerTest.freshInstall_noLogsDir_succeeds` exercises `buildExport(About)` against a real Robolectric-seeded filesystem from a clean state; (c) `LogExportManagerTest.writeTo_ioException_surfaces` stubs `ContentResolver` via Robolectric's `ShadowContentResolver` to force an `IOException` on the write path and asserts the caller can distinguish it.

### E2E tests
None. Size M + no instrumentation.

## Agent Verification Plan

**Source:** user-spec "Как проверить" section.

### Verification approach

The agent runs Gradle gates and static greps only. Runtime behaviour of the `:crash` process, SAF dialog, banner reappearance across cold-starts, and the 7-tap gesture requires a physical device (Honor 200) and is performed by the user. Per-task Verify-smoke / Verify-user fields below record the split.

Structural invariants the agent checks post-implementation:
- Exactly one `<activity android:process=":crash" android:name=".crash.CrashReportActivity" …>` in `AndroidManifest.xml`.
- `SanctumApplication.onCreate` installs the handler inside a process-name guard, before the `DefaultDownloadRepository.mainActivityFqn` line.
- `ErrorLog.ALLOWED_COMPONENTS` in `core-runtime/.../log/ErrorLog.kt` unchanged.
- Zero Compose/Activity imports in `:core-runtime` and `:core-settings` (existing module-boundary rule).
- `app/src/main/kotlin/app/sanctum/machina/crash/` does NOT call `ErrorLog.e`.

### Tools required

`bash` for `./gradlew :app:test`, `./gradlew :core-runtime:test`, `./gradlew :core-settings:test`, `./gradlew :app:lintDebug`, `./gradlew :app:assembleDebug`. `grep` for structural invariants. No MCP (no web surface); no Playwright; no curl (no server); no Telegram MCP (APK transfer is manual per `deployment.md`).

## Risks

| Risk | Mitigation |
|------|------------|
| Bootstrap loop: `CrashHandler` itself throws during the recovery sequence | Outer `try { … } catch (Throwable) { Log.e(…); Process.killProcess(myPid) }` in `CrashHandler`. Unit test (`CrashHandlerTest.handlerInternalFailure_killsOnce`) covers this explicitly. |
| `CrashReportActivity` itself crashes during `onCreate` (theme resolution, missing string, manifest issue) | Cannot be caught by `CrashHandler` — it runs in a different process. Mitigations: activity opts out of Hilt (Decision 5), uses only strings added in Task 1 and the existing `Theme.Sanctum` (confirmed to exist in `themes.xml`), depends on no `DataStore` or coroutine scope, no dynamic resource lookups. If it still crashes, the user sees the Android "app stopped" dialog twice (once for the original crash, once for the activity). This failure mode is a documented residual limitation, not an eliminated one — the filesystem `crash.log` is still intact, so a relaunch shows the restart banner (Flow B). |
| `startActivity(CrashReportActivity)` silently fails (process limit, manifest merge issue at runtime) | Main process still dies via `Process.killProcess` — state on disk is consistent. Same residual banner-fallback as the row above. |
| Recursive handler installation in `:crash` process | Process-name guard in `SanctumApplication.onCreate` before any handler code (Decision 4). |
| Native SIGSEGV from litertlm bypasses Kotlin handler entirely | Documented limitation (user-spec Risks row 3). User falls back to the system "app stopped" dialog and to a proactive About-export on the next launch (logcat will retain ART/native death lines). |
| Logcat empty on OEM paranoid-mode (Honor/MIUI) | `LogcatReader` emits `[logcat unavailable: <reason>]`; the rest of the `.txt` saves regardless. Covered by `LogcatReaderTest`. |
| SAF write failure leaves inconsistent state | On `IOException`: Toast "Не удалось сохранить лог"; `crash.log` NOT deleted; buttons remain clickable. AC in user-spec + technical AC below. |
| User double-taps "Сохранить лог" before SAF opens | In-flight guard flag in both `CrashReportActivity` and `RestartCrashBanner` rejects the second tap until the dialog callback returns. |
| 7-tap gesture misfires during normal UI scroll | `Modifier.clickable` on the version `Text` only, fires on discrete taps; `TapCounter` resets after 2-s gap so fat-finger triple-taps cannot escalate. |
| Dev-gesture reaches release build in Phase 5 | User-spec "Ограничения" accepts it for Phase 2.5; Phase 5 backlog entry wraps in `BuildConfig.DEBUG` (NOTES.md). |
| `strings.xml` bloat | ~12 short keys, tone-compliant per `ux-guidelines.md`; reviewed by `code-reviewer` in Audit Wave. |
| Test APK installs over existing Phase 2 APK with different signing key | Deployment rollback path in `deployment.md § Rollback Procedure` (uninstall previous APK). Not a tech-spec-level concern. |

## User-Spec Deviations

None.

All deliberate implementation choices (internal module split into `CrashHandler` / `LogExportManager` / `CrashState` / `CrashReportActivity`, the `ExportSource` enum, the non-Hilt `CrashReportActivity`, the `Killer` / `CommandRunner` test seams, the flag-file filesystem state) either implement user-spec requirements directly or are marked **[TECHNICAL]** in the Decisions section (Decision 5). No feature is added that user-spec does not ask for; no feature from user-spec is dropped or altered.

## Acceptance Criteria

Technical criteria complementing user-spec "Критерии приёмки":

- [ ] `./gradlew :app:test` passes; all new tests (`LogExportManagerTest`, `DeviceInfoCollectorTest`, `CrashHandlerTest`, `CrashStateTest`, `TapCounterTest`, `LogcatReaderTest`) green.
- [ ] `./gradlew :core-runtime:test` passes (existing `ErrorLogTest` still green — whitelist unchanged).
- [ ] `./gradlew :core-settings:test` passes (no regressions in existing repository tests).
- [ ] `./gradlew :app:lintDebug` produces no new errors; `MissingPermission` / `MissingClass` / `UnusedResources` / `HardcodedText` unchanged.
- [ ] `./gradlew :app:assembleDebug` produces `app-debug.apk`; size increase over the current `main` APK ≤ ~200 KB.
- [ ] `grep -rEn "androidx.compose|androidx.activity" core-runtime/src/main core-settings/src/main` — zero hits (module-boundary rule, `patterns.md`).
- [ ] `grep -rEn "ErrorLog|errorLog" app/src/main/kotlin/app/sanctum/machina/crash/` — zero hits (Decision 10).
- [ ] `grep -n "android:process=\":crash\"" app/src/main/AndroidManifest.xml` — exactly one match.
- [ ] `grep -n "setDefaultUncaughtExceptionHandler" app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt` — exactly one match, inside the process-name guard block.
- [ ] Git-diff of `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt` — empty; `ALLOWED_COMPONENTS` unchanged.
- [ ] No new external dependencies in `gradle/libs.versions.toml` or `app/build.gradle.kts`.

## Implementation Tasks

### Wave 1 (independent)

#### Task 1: Add Russian strings for diagnostics surfaces
- **Description:** Add ~12 new keys to `app/src/main/res/values/strings.xml` for CrashReportActivity (title, body, "Сохранить лог", "Закрыть"), Toast/Snackbar ("Лог сохранён", "Не удалось сохранить лог"), restart banner ("Прошлый запуск завершился аварийно.", close contentDescription), About diagnostics section ("Диагностика", "Сохранить лог"), and the test-crash dialog ("Спровоцировать тест-краш?", "Да", "Отмена"). Tone per `ux-guidelines.md`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer
- **Files to modify:** `app/src/main/res/values/strings.xml`
- **Files to read:** `.claude/skills/project-knowledge/references/ux-guidelines.md`, `work/phase-2.5-logexport/user-spec.md`

#### Task 2: CrashHandler + CrashState file primitives
- **Description:** Implement `CrashHandler` (uncaught handler: write `crash.log` head-truncated ≤100 KB, delete `.dismissed`, start `CrashReportActivity` via explicit Intent, then `Process.killProcess` via injected `Killer` seam; outer try/catch short-circuits on internal failure). Implement `CrashState` (Hilt `@Singleton`: `hasUnresolvedCrash: StateFlow<Boolean>`, `refresh`, `markDismissed`, `clear` — backed by `crash.log` + `crash.log.dismissed` filesystem state). Unit tests mirror `ErrorLogTest.kt` Robolectric setup.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :app:test --tests "*CrashHandlerTest*" --tests "*CrashStateTest*"` → all green.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/crash/CrashHandler.kt`, `app/src/main/kotlin/app/sanctum/machina/crash/CrashState.kt`, `app/src/main/kotlin/app/sanctum/machina/crash/Killer.kt`, `app/src/test/kotlin/app/sanctum/machina/crash/CrashHandlerTest.kt`, `app/src/test/kotlin/app/sanctum/machina/crash/CrashStateTest.kt`
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/log/ErrorLogTest.kt`, `work/phase-2.5-logexport/user-spec.md`, `work/phase-2.5-logexport/code-research.md`

#### Task 3: LogExportManager + DeviceInfoCollector + LogcatReader + TapCounter
- **Description:** Implement `LogExportManager.buildExport(source: ExportSource): String` assembling header + `crash.log` + `errors.log` + `errors.log.1` + `logcat` sections with per-section caps from Decision 7, and `writeTo(uri: Uri, content: String)` using `ContentResolver.openOutputStream(uri).use`. Add `DeviceInfoCollector` (header), `LogcatReader` (own-pid `logcat -d -v threadtime *:E` with 2-s timeout, `CommandRunner` interface for tests), and pure-JVM `TapCounter`. Covers Decisions 1, 7, 8.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :app:test --tests "*LogExportManagerTest*" --tests "*DeviceInfoCollectorTest*" --tests "*LogcatReaderTest*" --tests "*TapCounterTest*"` → all green.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/logexport/LogExportManager.kt`, `app/src/main/kotlin/app/sanctum/machina/logexport/DeviceInfoCollector.kt`, `app/src/main/kotlin/app/sanctum/machina/logexport/LogcatReader.kt`, `app/src/main/kotlin/app/sanctum/machina/logexport/TapCounter.kt`, `app/src/test/kotlin/app/sanctum/machina/logexport/LogExportManagerTest.kt`, `app/src/test/kotlin/app/sanctum/machina/logexport/DeviceInfoCollectorTest.kt`, `app/src/test/kotlin/app/sanctum/machina/logexport/LogcatReaderTest.kt`, `app/src/test/kotlin/app/sanctum/machina/logexport/TapCounterTest.kt`
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/log/ErrorLogTest.kt`, `app/build.gradle.kts`

### Wave 2 (depends on Wave 1)

#### Task 4: CrashReportActivity (non-Hilt) + RestartCrashBanner composable
- **Description:** Implement `CrashReportActivity` as a plain `ComponentActivity` (Decision 5) with a two-button Compose screen, SAF `CreateDocument("text/plain")` launcher with suggested filename `sanctum-log-YYYYMMDD-HHmm.txt` and in-flight guard flag. On success: write via `LogExportManager.buildExport(CrashReport)` + `writeTo(uri)`, delete `crash.log`, Toast, `finish()`. On IOException/cancel: keep file, re-enable buttons. Also ship the reusable `RestartCrashBanner` composable (Card + icon + text + two buttons) consumed by Task 7.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** ask the user to install the APK after Task 5 lands and confirm: CrashReportActivity appears on a test crash, SAF opens with correct suggested name, successful save removes `crash.log` and dismisses the screen, cancel returns to the screen with buttons still clickable.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/crash/CrashReportActivity.kt`, `app/src/main/kotlin/app/sanctum/machina/crash/RestartCrashBanner.kt`
- **Files to read:** created files from Task 2 and Task 3; `app/src/main/kotlin/app/sanctum/machina/ui/theme/Theme.kt`; `app/src/main/kotlin/app/sanctum/machina/ui/chat/HeavyChangeDialog.kt` (two-button pattern); `app/src/main/res/values/strings.xml` (Task 1)

#### Task 5: SanctumApplication handler install + AndroidManifest activity
- **Description:** (a) Add `<activity android:name=".crash.CrashReportActivity" android:process=":crash" android:exported="false" android:excludeFromRecents="true" android:taskAffinity="" android:theme="@style/Theme.Sanctum" />` inside `<application>` in `AndroidManifest.xml`. (b) In `SanctumApplication.onCreate`, install `CrashHandler` inside a `getProcessName() == packageName` guard (Decision 4), before the existing `DefaultDownloadRepository.mainActivityFqn` assignment so the handler is live as early as possible. Add `SanctumApplicationTest` (Robolectric) covering both guard branches.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL; `./gradlew :app:test --tests "*SanctumApplicationTest*"` → green; `grep -n "android:process=\":crash\"" app/src/main/AndroidManifest.xml` → 1 match; `grep -n "getProcessName" app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt` → ≥1 match.
- **Files to modify:** `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt`, `app/src/test/kotlin/app/sanctum/machina/SanctumApplicationTest.kt`
- **Files to read:** created files from Task 2 and Task 4; `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/DefaultDownloadRepository.kt`

#### Task 6: AboutScreen — Diagnostics section + 7-tap dev-gesture
- **Description:** Append a "Диагностика" section under the existing SafeMarkdown content with one "Сохранить лог" button backed by a SAF launcher → `LogExportManager.buildExport(About)` → `writeTo(uri)` → Snackbar. Wrap the version `Text` in `AboutFooter` with a `Modifier.clickable` that feeds a `TapCounter`; on 7 taps within 2-s gaps, show `AlertDialog` "Спровоцировать тест-краш?"; on confirm, throw `RuntimeException("test crash from About")` directly in the onClick (Decision 9).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** on Honor 200, open "О программе" → "Диагностика" → "Сохранить лог" → SAF → open saved `.txt` → header + all sections present, logcat non-empty. Separately: 7-tap version → dialog → Да → app dies → CrashReportActivity appears.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/about/AboutScreen.kt`
- **Files to read:** created files from Task 3; `app/src/main/res/values/strings.xml`

#### Task 7: ModelManagerScreen — restart banner wiring
- **Description:** Insert `RestartCrashBanner` (from Task 4) above the model list when `CrashState.hasUnresolvedCrash` emits `true`; refresh via `LaunchedEffect(Unit)` on screen enter. Wire "Сохранить лог" to a shared SAF launcher → `LogExportManager.buildExport(About)` → `writeTo(uri)` → `CrashState.clear()` + Snackbar "Лог сохранён". Wire ✕ to `CrashState.markDismissed()`. Add a `SnackbarHost` to the screen (currently absent, per code-research §3).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** on Honor 200, force a crash via the dev-gesture → relaunch → banner visible → "Сохранить лог" saves, banner disappears, Snackbar shown. Force another crash → relaunch → ✕ → banner disappears and stays hidden on further cold-starts until a new crash occurs.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt`
- **Files to read:** created files from Task 2, Task 3, Task 4; `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt` (SnackbarHost pattern)

### Audit Wave

#### Task 8: Code Audit
- **Description:** Full-feature holistic code review of all files created/modified in Tasks 1–7. Focus on cross-component issues: shared file-state correctness between `CrashHandler` and `CrashState`, module-boundary rule compliance (`:core-*` stays UI-free), adherence to `patterns.md` (module hygiene, error-logging conventions, SafeMarkdown only for markdown — not used here but checked), architectural consistency with existing Compose patterns. Write report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 9: Security Audit
- **Description:** Full-feature security audit across all feature files against OWASP Top 10. Focus: A01 access control (`android:exported="false"` on CrashReportActivity), A03 injection (no shell-arg interpolation into `ProcessBuilder("logcat", …)` — args are fixed strings + `Process.myPid()` integer), A05 security misconfiguration (`:crash` process permissions and theme), A08 integrity (what actually lands in the exported `.txt`: SELinux-context / hardware-identifier leakage from logcat, full stacktraces in `crash.log`), A09 logging failures (handler inner-failure path, SAF IOException path). Write report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 10: Test Audit
- **Description:** Full-feature test quality audit across `CrashHandlerTest`, `CrashStateTest`, `LogExportManagerTest`, `DeviceInfoCollectorTest`, `LogcatReaderTest`, `TapCounterTest`, `SanctumApplicationTest`. Focus: behavioural coverage of user-spec AC (header fields, `[empty]` placeholders, section order, truncation direction, banner visibility logic, 7-tap counter boundaries, overwrite semantics, cross-component `.dismissed` reset), meaningful assertions beyond nullability, Robolectric consistency with `ErrorLogTest`, pyramid balance (size M → unit-only is correct; integration/E2E absence justified by code-research §9.1). Write report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 11: Pre-deploy QA
- **Description:** Run `./gradlew :app:test :core-runtime:test :core-settings:test`, `./gradlew :app:lintDebug`, `./gradlew :app:assembleDebug`. Verify every AC from user-spec "Критерии приёмки" and tech-spec "Acceptance Criteria" by reading code + test output. Run structural greps (`android:process=":crash"`, process-guard in SanctumApplication, `:core-*` UI-freedom, no `ErrorLog` under `crash/`). Hand off to user for on-device verification per user-spec "Как проверить / Пользователь проверяет".
- **Skill:** pre-deploy-qa
- **Reviewers:** none

<!-- No Deploy task: mobile project, manual APK transfer, no CI/CD (deployment.md). -->
<!-- No Post-deploy verification task: on-device checks are user-performed; no MCP-verifiable surface. -->
