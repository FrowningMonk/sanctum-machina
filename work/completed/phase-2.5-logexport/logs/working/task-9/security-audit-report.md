# Security Audit Report — phase-2.5-logexport (Task 9)

**Date:** 2026-04-19
**Auditor:** security-auditor (Task 9, Audit Wave)
**Scope:** all code files created/modified in Tasks 1–7 of `phase-2.5-logexport`. The auditor is the review — no external JSON reviewers.
**Standard:** OWASP Top 10 (2021).

---

## Summary

**Verdict: PASS.** No Critical / High / Medium findings. No Low findings.

The three-path log-export feature (`CrashHandler` + `CrashReportActivity` in `:crash` + `LogExportManager` + SAF-driven writes) is architected around small, well-contained attack surfaces and makes deliberate security-positive choices (no FileProvider, no persistable URI grants, fixed-argv `ProcessBuilder`, non-exported activity, `FLAG_SECURE` on the crash window). Two residual risks (Decision 11 "unfiltered export" and the native-SIGSEGV fallback) are explicitly accepted in `user-spec.md § Ограничения` / `user-spec.md § Риски` and carry forward as documented trade-offs, **not** as blind spots. One informational observation (per-process manifest permissions in Android) is noted below.

Task 11 (Pre-deploy QA) is **not blocked** by security findings. No new fix-task is required before deploy.

---

## OWASP Top 10 Evaluation

### A01 — Broken Access Control

**Verdict: PASS.**

- `CrashReportActivity` declared with `android:exported="false"` at `AndroidManifest.xml:50`. No `<intent-filter>` ⇒ no implicit export. No external package can `startActivity` this component.
- `android:taskAffinity=""` at `AndroidManifest.xml:52` isolates the recovery UI into its own task, preventing cross-task escalation from the main task.
- `android:excludeFromRecents="true"` at `AndroidManifest.xml:51` keeps the crash screen out of the OS Recents snapshot collector.
- `FLAG_SECURE` applied in `CrashReportActivity.onCreate` (`CrashReportActivity.kt:85`) blocks screen capture / screen-record / Recents thumbnail collection by third-party accessibility tools.
- `CrashHandler.kt:63–67` builds the intent with `setClassName(context.packageName, CRASH_REPORT_ACTIVITY_FQN)` and uses `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` — the target is pinned to this app's package and class; no user input influences either argument.
- SAF URI access: `ActivityResultContracts.CreateDocument("text/plain")` returns a user-picked, write-scoped `content://` URI granted to this app only for the duration of the activity result. No `takePersistableUriPermission` call anywhere in the feature (verified by `grep` — 0 hits). No `openInputStream` call on the exported URI anywhere in `logexport/` or `crash/` (verified by `grep` — 0 hits).
- `filesDir/logs/` is per-app internal storage. No code in the feature uses `externalCacheDir` / `externalFilesDir`.

### A02 — Cryptographic Failures

**Verdict: N/A, confirmed clean.**

- No cryptography, no password storage, no TLS endpoints introduced by this feature. `AndroidManifest.xml:25` keeps `android:usesCleartextTraffic="false"` — unchanged.
- `DeviceInfoCollector.buildHeader()` emits no random material, no nonces, no tokens, no session identifiers.

### A03 — Injection

**Verdict: PASS.**

- The only subprocess invocation in the feature is `DefaultCommandRunner.run(argv, timeoutMs)` at `LogcatReader.kt:85`: `ProcessBuilder(argv).redirectErrorStream(true).start()`.
- `argv` is constructed at `LogcatReader.kt:31–38` as a fixed six-element list:
  1. `"logcat"` (literal)
  2. `"-d"` (literal)
  3. `"-v"` (literal)
  4. `"threadtime"` (literal)
  5. `"--pid=${Process.myPid()}"` — the interpolated value is an `Int` returned by the Android SDK. An `Int` cannot contain shell metacharacters.
  6. `"*:E"` (literal)
- No shell wrapping (`sh -c`, `bash -c`) anywhere — verified by `grep "Runtime.getRuntime|sh -c|ProcessBuilder(\"/bin"` → 0 matches.
- `Runtime.getRuntime().exec` is not used anywhere in the feature.
- `LogcatReaderTest` enforces the argv shape on every commit (tech-spec Testing Strategy: "argv-shape assertion — arg 4 matches `^--pid=\d+$`, arg 5 `"*:E"` — no shell wrapping").
- SQL / ORM / XSS / template-injection surfaces: N/A (no database, no webview, no HTML templating in this feature).

### A04 — Insecure Design

**Verdict: PASS.**

- Decisions 1–12 in `tech-spec.md` enumerate the architectural choices. Each is implemented in code (cross-checked by Task 8 Code Audit).
- Security-positive design choices:
  - Decision 2 — **no FileProvider, no `FLAG_GRANT_READ_URI_PERMISSION`, no share-intent**. The export path terminates at a SAF `CreateDocument` URI that the user selects; no third-party app is inserted into the data flow.
  - Decision 5 — `CrashReportActivity` deliberately opts out of Hilt to avoid DI-graph initialization as a second failure surface during crash recovery.
  - Decision 6 — filesystem-only banner state avoids DataStore coroutine / mutex work in a process (`:crash`) where the JVM may be unstable.
  - Decision 10 — `CrashHandler` writes `crash.log` directly via `File.writeText`, not through the `suspend`-based `ErrorLog`. Avoids the coroutine dispatcher being unavailable mid-crash.
- No new attack surfaces added vs. the base Android model: no exported activities, no exported services, no content providers, no broadcast receivers.

### A05 — Security Misconfiguration

**Verdict: PASS** (one informational observation below).

- `:crash` process permissions: **no** `<uses-permission android:process=":crash" … />` tag anywhere. Android does not support per-process permission filtering in the manifest — both the main process and `:crash` inherit the application-level `<uses-permission>` set (`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `CAMERA`, `RECORD_AUDIO`). `CrashReportActivity` uses none of them — its surface is SAF write-only — so the inheritance does not enable new behaviour. **Not a finding; observation only.**
- `android:allowBackup="false"` at `AndroidManifest.xml:23` — unchanged; prevents `adb backup` exfiltration of `filesDir/logs/`.
- `android:dataExtractionRules="@xml/data_extraction_rules"` at `AndroidManifest.xml:24` — unchanged; governs Android 12+ device-to-device transfer and cloud backup.
- `android:theme="@style/Theme.Sanctum"` at `AndroidManifest.xml:53` — resolves to the existing app theme; no dynamic theme lookup.
- `android:process=":crash"` appears exactly once in the manifest (`AndroidManifest.xml:49`) — verified by `grep`.
- No `FileProvider` declared (Decision 2) — one less misconfigurable surface.
- `CrashReportActivity` does not call any of the inherited permission-gated APIs (`CAMERA`, `RECORD_AUDIO`, `INTERNET`) — its entire surface is SAF + file I/O + Toast.

### A06 — Vulnerable and Outdated Components

**Verdict: PASS (out of Task 9 scope).**

- `tech-spec.md § Dependencies` states "No new external dependencies". Confirmed by Task 3 decisions.md verification: `git diff main -- gradle/libs.versions.toml app/build.gradle.kts` returned empty.
- CVE analysis of pre-existing dependencies (Hilt 2.57.1, Compose BOM 2026.03.00, activity-compose 1.10.1, Robolectric 4.12) is out of scope for a feature audit and is tracked separately per standard project-knowledge practice.

### A07 — Identification and Authentication Failures

**Verdict: N/A.** The feature contains no login, no session, no authentication surface. No user identity is established, stored, or verified.

### A08 — Software and Data Integrity Failures

**Verdict: PASS** (two accepted residual risks — see "Accepted Residual Risks" section).

Data-flow analysis of the exported `.txt`:

| Section | Producer | Content | PII / secrets risk |
|---------|----------|---------|--------------------|
| Header | `DeviceInfoCollector.buildHeader()` | `applicationId`, `versionName`+`versionCode`+`debug`, `manufacturer`/`model`/`Android release`/`API level`, `totalMem`/`availMem`, `activeModelId` (currently `null` → `"none"`), `downloadedModels` (currently empty → `"(none)"`) | No IMEI, no `ANDROID_ID`, no BSSID, no MAC, no install-ID, no per-user UID paths. Verified by `grep "ANDROID_ID\|getDeviceId\|getImei\|Settings.Secure"` in `logexport/` → 0 matches. `manufacturer`/`model` are device-class identifiers, not per-device identifiers. **Clean.** |
| `crash.log` | `CrashHandler.buildCrashRecord` — timestamp, thread name, exception type, message, stack trace | Thread name and exception message are sanitized for control whitespace (`CrashHandler.kt:95–96`). Stack trace is passed verbatim. Head-truncated to 100 KB. | Third-party library messages and stack frames go verbatim — **accepted residual risk, Decision 11.** |
| `errors.log` / `errors.log.1` | pre-existing `ErrorLog` (not modified by this feature) | `ERROR [component] description :: CauseType: cause.message` — description ≤ 500 chars, cause message ≤ 200 chars, both sanitized for `[\n\r\t]` | Narrow theoretical exposure: cause-message truncation could include URL fragments or HTTP auth strings if the underlying HTTP/download client embedded them in exception messages. No such pattern observed in current code paths (no HTTP header names appear in any `.message` construction under `core-runtime/.../registry/`). **Covered by Decision 11 residual-risk acceptance.** |
| `logcat` | `LogcatReader.read` via `logcat -d -v threadtime --pid=<ownpid> *:E` | Own-process ERROR-level lines. May include SELinux AVC denials, ART/JNI native-abort frames, third-party SDK error spew with whatever hardware identifiers they chose to log. Tail-truncated to 100 KB. | **Accepted residual risk, Decision 11.** |

Write-path integrity:
- `LogExportManager.writeTo(uri, content)` at `LogExportManager.kt:99–103` uses `openOutputStream(uri)` wrapped in `.use { }`. Stream is closed even on exception. `null`-return from `openOutputStream` is converted to `IOException("openOutputStream returned null")` so the single catch-`IOException` block in the caller covers both failure modes (user-spec AC "Ошибка записи … показывает Toast … кнопки остаются кликабельными").
- `buildExport` is entirely in-memory on `Dispatchers.IO`. Section caps (Decision 7) keep peak heap bounded: header ≤ ~1 KB, `crash.log` ≤ 100 KB, `errors.log` / `errors.log.1` ≤ 2 MB each (enforced by `ErrorLog`'s own rotator), `logcat` ≤ 100 KB — total ≤ ~4.3 MB.
- **No deserialization of untrusted data.** `LogExportManager` only reads from three local files it trusts (they are written by code in this codebase) and from a subprocess whose stdout is treated as opaque UTF-8 bytes.

### A09 — Security Logging and Monitoring Failures

**Verdict: PASS.**

- Bootstrap-loop guard: `CrashHandler.uncaughtException` at `CrashHandler.kt:48–74` wraps the entire handler body in `try { ... } catch (outer: Throwable)`. The catch emits a single `android.util.Log.e(TAG, "handler failed", outer)` breadcrumb (non-`suspend`, lands in logcat — captured by the next About-path export) and then calls `killer.kill(Process.myPid())`. No retry, no re-entry. Directly implements user-spec Risks row 1 ("Bootstrap loop").
- `CrashHandlerTest.handlerInternalFailure_killsOnce` (per tech-spec Testing Strategy) enforces that the killer is called exactly once on inner failure.
- SAF IOException path:
  - `CrashReportActivity.handleSafResult` at `CrashReportActivity.kt:105–116` catches `IOException`, shows Toast `"Не удалось сохранить лог"`, and **does not delete `crash.log`** — the file is preserved so the user can retry from the restart banner (Flow B) or from About (Flow C).
  - `AboutViewModel.buildAndWrite` at `AboutScreen.kt:84–90` catches `IOException` and returns `Result.failure`; caller shows Snackbar.
  - `ModelManagerViewModel.saveLogAndClearCrash` at `ModelManagerViewModel.kt:77–84` catches `IOException`, does **not** call `CrashState.clear()` on failure — `crash.log` and the banner stay for retry.
- User-visible feedback: every failure branch surfaces a Toast or Snackbar. No silent swallowing on the write path.

### A10 — Server-Side Request Forgery

**Verdict: N/A.** Mobile-only feature; no server endpoint issued by Phase 2.5 code. No URL parsing on user-controlled input. The SAF URI is a local `content://` scheme, not a network URL.

---

## Accepted Residual Risks

### RR-1 — Decision 11: unfiltered export contents

**Status:** **Accepted residual risk**, not a finding.

**Scope:** `LogExportManager` does not redact, sanitize, or hash content from `crash.log`, `errors.log`, `errors.log.1`, or `logcat`. Stack traces, SELinux contexts, `manufacturer`/`model` identifiers, third-party SDK error strings may all appear verbatim in the exported `.txt`.

**Why accepted:**
- `user-spec.md § Ограничения` — "Приватность: на фазе закрытого тестирования фильтрация содержимого не применяется — приоритет у полноты диагностики. В Phase 5 вопрос пересматривается перед публичным релизом (отмечено в `NOTES.md` backlog)."
- `tech-spec.md § Decision 11` — documents rationale: diagnosis completeness outranks privacy on the closed-alpha tester-to-developer channel; user controls the destination via SAF, so the file never leaves the device without an explicit user action; a redaction pipeline adds regex surface that can mis-redact, defeating the purpose of a full stack trace.

**Status gate for Phase 5:** tech-spec.md Decision 11 and user-spec.md Ограничения both explicitly punt sanitization to pre-public-release. A redaction pipeline should be designed before the first public APK.

### RR-2 — Native SIGSEGV bypasses Kotlin handler

**Status:** **Accepted residual risk**, not a finding.

**Scope:** Native crashes (e.g., SIGSEGV inside `litertlm`) bypass `Thread.setDefaultUncaughtExceptionHandler` entirely. Neither `crash.log` nor `CrashReportActivity` runs. The user sees the Android "app stopped" dialog.

**Why accepted:**
- `user-spec.md § Риски` row 3: "Native SIGSEGV из litertlm обходит Kotlin-handler. Митигация: принимаем как известное ограничение. Товарищ увидит системный диалог Android 'Приложение остановлено'. Если у ART-runtime хватило времени что-то записать — оно может подхватиться при проактивном экспорте из `AboutScreen` (logcat)."
- Mitigation path: proactive About-export picks up ART-runtime death lines from logcat on the next launch (Flow C).
- Alternative (bundle a native signal handler such as breakpad) rejected as out of scope for Phase 2.5 closed alpha.

### Observation (informational, not a residual risk)

**Per-process permissions are not filtered by Android.** The `:crash` process inherits all `<uses-permission>` declarations from the manifest (`INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`, `RECORD_AUDIO`, etc.). `CrashReportActivity`'s actual code surface uses only file I/O and SAF, so the inheritance has zero attack impact today. This is Android platform behaviour, not a code defect. Worth noting only as a future-proofing observation: if a later change ever adds network or sensor code under `app/.../crash/`, it would implicitly have full permission access. Decision 2 (no FileProvider, no share-intent) already constrains the data-exit channel to user-mediated SAF.

---

## Findings

**None.**

No Critical, High, Medium, or Low findings identified. All nine axes explicitly evaluated (A02 / A06 / A07 / A10 marked N/A with reason; A01 / A03 / A04 / A05 / A08 / A09 marked PASS with evidence).

---

## Recommendations

None for Phase 2.5. Recovery-path security posture is solid.

For Phase 5 (out of Task 9 scope, tracked in `NOTES.md` backlog):

1. Wrap the 7-tap dev-gesture in `if (BuildConfig.DEBUG)` before the first public APK — Decision 12 and user-spec.md Ограничения both flag this. Task 9 verified that `grep "BuildConfig.DEBUG"` in `AboutScreen.kt` returns 0 matches today — intentional per Decision 12, needs inversion before public release.
2. Design a redaction pipeline for exported `.txt` content — RR-1 above, Decision 11.
3. Consider a native signal handler (breakpad or equivalent) if native crashes from `litertlm` become a frequent diagnostic gap — RR-2.

None of these blocks Phase 2.5 Final Wave (Task 11 Pre-deploy QA).

---

## Structural Checks (appendix)

Verified by direct `grep`:

| Check | Expected | Actual | Verdict |
|-------|----------|--------|---------|
| `android:exported="true"` in `AndroidManifest.xml` | only on `MainActivity` (`LAUNCHER`) | line 40 only; `CrashReportActivity` at 50 is `"false"`, `SystemForegroundService` at 58 is `"false"` | PASS |
| `openInputStream` under `logexport/` or `crash/` | 0 | 0 | PASS |
| `takePersistableUriPermission` anywhere in `app/src/main/` | 0 | 0 | PASS |
| `Runtime.getRuntime().exec` anywhere in feature | 0 | 0 | PASS |
| `ProcessBuilder(...)` calls | exactly one, in `LogcatReader` with fixed argv | `LogcatReader.kt:85` only, argv built at `LogcatReader.kt:31–38` with 5 literals + `Process.myPid()` Int | PASS |
| `BuildConfig.DEBUG` in `AboutScreen.kt` | 0 (Decision 12, accepted) | 0 | PASS (deliberate) |
| `android:process=":crash"` in manifest | exactly 1 | `AndroidManifest.xml:49` | PASS |
| `FLAG_SECURE` on `CrashReportActivity` | present | `CrashReportActivity.kt:85` | PASS |
| `ErrorLog.e` calls under `app/.../crash/` | 0 (Decision 10) | 0 | PASS |
