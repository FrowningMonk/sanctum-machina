# Phase 3.5 Diagnostics — Security Audit

**Branch:** `phase/3.5-diagnostics`
**Auditor:** main agent (security-auditor skill)
**Date:** 2026-04-28
**Scope:** Tasks 1–8 final state — files listed in tech-spec § Implementation Tasks → "Files to modify".

## 1. Summary

**Verdict: PASS — no security findings (zero critical / high / medium / low).** Three `info`-level
observations recorded as future hardening notes; none block deploy.

The fan-out of new attack surface in Phase 3.5 is small: one extra integer field (`minDeviceMemoryInGb`)
flowing through an already-hardened JSON parser, one Gradle exec call (`git describe`) gated on argv-list
form with no shell, one logcat-filter widening from `*:E` to `*:W`, one new singleton holding three
non-PII scalars + one model id, and one new Compose screen wrapping an existing SAF launcher and
existing `LogExportManager`. None of these introduce a new trust boundary; everything new is either
read from the bundled (signed-APK) asset, captured from system services, or sourced from the same
internal allowlist that Phase 1–3 already validated.

**Cross-reference with prior per-task auditor passes:**

| Task | Per-task security-auditor | This pass |
|------|---------------------------|-----------|
| 1 (allowlist parser) | approved, 0 findings | Re-confirmed |
| 2 (gradle git-describe) | approved, 0 findings | Re-confirmed |
| 4 (InitDiagnostics seam) | approved, 0 findings | Re-confirmed |
| 5 (ModelManager gate UI) | approved, 0 findings | Re-confirmed |
| 6 (registry wire-up) | approved, 0 findings | Re-confirmed |
| 7 (DeviceInfoCollector) | 3 minor (defense-in-depth) | All 3 closed by polish-pass `6b00f5c` (`flattenForLogLine`) |
| 8 (DiagnosticsScreen) | 1 info | Re-confirmed (info, not load-bearing) |

No regressions introduced between per-task passes and final integrated state.

## 2. Scope (files read for this audit)

**Task 1 — allowlist:**
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/AllowlistLoader.kt`
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/ModelAllowlist.kt`
- `core-runtime/src/main/assets/model_allowlist.json`

**Task 2 — gradle git-describe:**
- `app/build.gradle.kts`
- `build-logic/build.gradle.kts`
- `build-logic/src/main/kotlin/app/sanctum/machina/build/GitVersionPlugin.kt`
- `build-logic/src/main/kotlin/app/sanctum/machina/build/GitVersionParse.kt`

**Task 3 — logcat:**
- `app/src/main/kotlin/app/sanctum/machina/logexport/LogcatReader.kt`

**Task 4 — diagnostics seam:**
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/InitDiagnostics.kt`
- `app/src/main/kotlin/app/sanctum/machina/diagnostics/InitSnapshot.kt`
- `app/src/main/kotlin/app/sanctum/machina/diagnostics/DiagnosticsState.kt`
- `app/src/main/kotlin/app/sanctum/machina/diagnostics/di/DiagnosticsModule.kt`

**Task 5 — Model Manager gate:**
- `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerViewModel.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt`

**Task 6 — registry wire-up:**
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`

**Task 7 — DeviceInfoCollector header expansion:**
- `app/src/main/kotlin/app/sanctum/machina/logexport/DeviceInfoCollector.kt`

**Task 8 — Diagnostics screen + Drawer pin + AboutScreen refactor:**
- `app/src/main/kotlin/app/sanctum/machina/ui/diagnostics/DiagnosticsScreen.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/diagnostics/DiagnosticsViewModel.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/drawer/DrawerContent.kt`
- `app/src/main/kotlin/app/sanctum/machina/ui/about/AboutScreen.kt`

Approach: white-box code review against OWASP Top 10 (2021) in the Android-on-device-LLM context.
No dynamic scanners; no network / device test runs.

## 3. OWASP Top 10 (2021) verdicts

### A01:2021 — Broken Access Control — **n/a**
No multi-user model; no server-side auth; no role separation. SAF export is a system-mediated user
gesture (`CreateDocument` returns a content URI scoped to the picked location — `DiagnosticsScreen.kt:66`,
`ModelManagerScreen.kt:98`). The app cannot write outside the SAF-granted URI; the OS enforces this.

### A02:2021 — Cryptographic Failures — **n/a**
No new cryptography in Phase 3.5. No new data persisted at rest (`InitSnapshot` lives in-process
in an `AtomicReference`, never serialized to disk). The exported `.txt` is plain text by design (user-facing
diagnostic file) — not a confidentiality boundary; user explicitly chooses where to save.

### A03:2021 — Injection — **ok**
Three potential injection surfaces, all closed:
1. **`git describe` exec** (`GitVersionPlugin.kt:24-27`) — argv-list via `commandLine("git", "describe", ...)`.
   No user-controlled input enters the command; arguments are static literals. No shell interpretation.
2. **Logcat exec** (`LogcatReader.kt:31-38`) — argv-list via `ProcessBuilder(argv)`. The only variable
   element is `Process.myPid()` (an integer from the OS); no user-controlled string flows in.
3. **Allowlist JSON parse** (`AllowlistLoader.kt:65-98`) — Gson deserialization of a bundled asset
   (read-only, signed in APK). `MODEL_ID_REGEX`, `MODEL_FILE_REGEX`, `COMMIT_HASH_REGEX`, and
   explicit `PATH_TRAVERSAL` substring checks block any path-traversal abuse if the bundled asset
   were ever swapped (defense-in-depth; the asset is signed in production).

`flattenForLogLine` (`DeviceInfoCollector.kt:140-142`) collapses `\r\n\t` in the snapshot's `modelName`
before interpolation into the export header — defense-in-depth against log-line forgery even though
`modelName` is allowlist-controlled today (Decision 6 / `DefaultModelRegistry.initialize` reads
`ModelEntry.model.name`, regex-validated upstream).

### A04:2021 — Insecure Design — **ok**
The two design decisions worth checking:
- **Pre-flight gate at Download time** (Decision 1) — refuses to download a model the device cannot
  host. Closes a class of native-OOM crashes at the source instead of papering over them downstream.
  Fail-loud: missing/null `minDeviceMemoryInGb` in the allowlist makes the parser drop the record
  and log to `errors.log`'s `download` component (Decision 5 / `AllowlistLoader.kt:85-92`) — preferred
  to silent fail-open.
- **`InitDiagnostics` interface seam** (Decision 6) — `:core-runtime` cannot import `:app`; the seam
  preserves the module boundary that matches `architecture.md § Module graph`. No concern leakage.

### A05:2021 — Security Misconfiguration — **ok**
- `app/build.gradle.kts:21-22` consumes `gitVersion` extension with explicit fallback
  `"v0.3.5-diagnostics-fallback"` — never crashes the build on missing `.git/`.
- Gradle exec uses `isIgnoreExitValue = true` (`GitVersionPlugin.kt:26`) so a non-zero
  `git describe` doesn't fail the build; the `gitVersionParse` pure-function returns `null` for
  any non-zero exit, and `app/build.gradle.kts:21` substitutes the fallback.
- `LogcatReader` translates `IOException` / `SecurityException` from `ProcessBuilder.start()` into
  `[logcat unavailable: ...]` placeholder — never throws into the export pipeline
  (`LogcatReader.kt:84-90`).
- Hilt module `DiagnosticsModule` correctly scoped: `@InstallIn(SingletonComponent::class)` plus
  `@Singleton` on the impl class — single instance per process.

### A06:2021 — Vulnerable and Outdated Components — **ok**
No new dependencies added in Phase 3.5 (tech-spec § Dependencies → "New packages: None" —
re-confirmed against `app/build.gradle.kts` and `build-logic/build.gradle.kts`). Only stdlib JDK
(`AtomicReference`, `Runtime`, `Debug`, `OffsetDateTime`), Android SDK (`ActivityManager`, `Process`),
and Hilt/AGP/Compose already present. No `npm audit`-equivalent applies — no `package.json`.

### A07:2021 — Identification and Authentication Failures — **n/a**
Local-only Android app. No accounts, no sessions, no tokens. The 7-tap dev-crash gesture in
`AboutScreen` is a developer convenience, not an authentication boundary; throwing a `RuntimeException`
on tap is a deterministic crash, not an exploitable privileged action.

### A08:2021 — Software and Data Integrity Failures — **ok**
- **Allowlist asset** is bundled in the APK (`core-runtime/src/main/assets/model_allowlist.json`),
  signed via the standard Android APK signature. Tampering requires re-signing the APK; out of
  scope (vendor-key compromise).
- **No deserialization of untrusted data.** Gson reads only the bundled allowlist; `InitSnapshot`
  is never serialized; SAF-export writes plain text only — never reads back.
- **`git describe` exec** is build-time only; the resulting `versionName` is baked into
  `BuildConfig` at compile and immutable at runtime.

### A09:2021 — Security Logging and Monitoring Failures — **ok**
- Allowlist rejection (Decision 5) writes to `ErrorLog.e("download", …)` —
  `AllowlistLoader.kt:51-54`. Component `"download"` is already in `ErrorLog.ALLOWED_COMPONENTS`;
  whitelist not extended (per user-spec constraint).
- Init-attempt failure logs to `ErrorLog.e("inference-init", …)` —
  `DefaultModelRegistry.kt:263, 278`. Both whitelisted components.
- The exported `.txt` header is the runtime "audit trail" for the user — see § 4.4 for the
  PII/secrets check.
- The pre-flight gate firing is **deliberately not** logged to `ErrorLog` (user-spec constraint:
  preventive UX, not an incident). Confirmed in `ModelManagerViewModel.onDownload`: short-circuit
  `if (!gateAllowsDownload(...)) return` without any log call (`ModelManagerViewModel.kt:108`).

### A10:2021 — Server-Side Request Forgery — **n/a**
On-device app. No server, no user-controlled URL forwarding. Hugging Face download URLs are
constructed from allowlist entries (`ModelAllowlist.kt:51-52`) and then constrained by the
parser to start with `https://huggingface.co/` (`AllowlistLoader.kt:93-95`). User input never
flows into a URL.

## 4. Targeted checks (7 risks listed in task spec)

### 4.1 Input validation in allowlist parser (Decision 5) — **ok**

`AllowlistLoader.parse()` (`AllowlistLoader.kt:65-98`) applies:
- Empty-list rejection (`require(!models.isNullOrEmpty())`).
- `MODEL_ID_REGEX = ^litert-community/[A-Za-z0-9._-]+$` + explicit `..` substring block on `modelId`.
- `MODEL_FILE_REGEX = ^[A-Za-z0-9._-]+$` + explicit `..` substring block on `modelFile`.
- `COMMIT_HASH_REGEX = ^[a-f0-9]{40}$` on `commitHash`.
- `sizeInBytes in 1..10_737_418_240L` (1 byte to 10 GB).
- **New (Phase 3.5):** `require(minMemory != null)` and `require(minMemory in 1..64)`.
- Final URL `startsWith("https://huggingface.co/")` check.

The new range `1..64` is correct: blocks `0` / negative (would be silent fail-open via
`gateAllowsDownload(totalBytes, 0)` returning true on every device) and absurd-large values
(`>=65` would universally block, hiding the model with no signal).

ReDoS check on `MODEL_ID_REGEX` / `MODEL_FILE_REGEX` / `COMMIT_HASH_REGEX`: all three are linear
character-class regexes with anchored start/end, no nested quantifiers, no backreferences — not
susceptible to catastrophic backtracking. Inputs are bounded by `sizeInBytes` of the allowlist
asset (≤10 KB in practice, hard ceiling enforced by APK assembly).

Aggregate fail-fast strategy (one bad record → whole batch rejected, registry stays empty) is
documented in `AllowlistLoader.kt:60-64` and is the correct posture: a partially-trusted allowlist
is more dangerous than an empty one (silent fail-open on a forgotten threshold).

`ErrorLog.e` from `load()` is in `withContext(Dispatchers.IO)` — suspending and bounded by
`ErrorLog`'s own 500-char clamp. No unbounded growth path.

### 4.2 Path traversal through `model_allowlist.json` — **ok**

Three model fields flow into filesystem paths via `Model.getPath(context)`:

| Field | Validation in parser | Path component |
|-------|---------------------|----------------|
| `modelId` | `MODEL_ID_REGEX`, no `..` | URL only (not path) |
| `modelFile` | `MODEL_FILE_REGEX`, no `..` | filename component of `getPath` |
| `commitHash` | 40-hex regex | `version` fallback → path component |
| `version` | none in parser | `version` field (path component when non-empty) |

`Model.getPath()` (`Model.kt:110-137`) joins these against `context.getExternalFilesDir(null)`
(app-private external storage) using `File.separator`. The path stays inside the app sandbox even
if a component were attacker-controlled, because `getExternalFilesDir` is OS-rooted.

**Pre-existing observation, not a Phase 3.5 finding:** the `version` field on `AllowedModel`
(`ModelAllowlist.kt:43`) is not regex-validated by the parser. It defaults to `commitHash`
(40-hex, validated) when blank, but a JSON entry with an explicit non-blank `version` would
be used verbatim as a path segment. This pre-dates Phase 3.5 and is not in scope; the bundled
asset (signed in APK) does not exercise this path.

### 4.3 Command injection in `gitVersionName()` (Decision 10) — **ok**

`GitVersionPlugin.kt:24-27`:
```kotlin
project.providers.exec {
    commandLine("git", "describe", "--tags", "--always", "--dirty=-dev")
    isIgnoreExitValue = true
}
```
- `commandLine(vararg)` is argv-list, not a shell string. No `/bin/sh -c`, no `cmd.exe /c`,
  no metacharacter interpretation.
- All four arguments are compile-time constants from this same file — no project property,
  env var, or user-controlled value flows in.
- `gitVersionParse(stdout, exitCode)` (`GitVersionParse.kt:9-13`) is a pure function. It calls
  no exec, no `Runtime`, no `ProcessBuilder`. It only `trim()`s stdout and returns it or null.
  The trimmed output ends up in `BuildConfig.VERSION_NAME` — a string baked into compiled
  resources at build time, immutable at runtime.

### 4.4 Sensitive data in exported `.txt` (`DeviceInfoCollector.buildHeader`) — **ok**

The Phase 3.5 additions to the header are:
- `memory: total=…, available=…, threshold=…, lowMemory=…` — system-wide RAM scalars.
- `process: java=…, native=…, totalPss=…` — our own process's heap counts (in GB after
  formatter conversion). Per-field `n/a` if the source threw.
- `last init: …` — one of four variants. Fields used: `freeRamBytes` (Long), `atEpochMs`
  (rendered as `HH:mm` only — date stripped), `modelName` (allowlist-controlled — `Gemma-4-E2B-it`
  / `Gemma-4-E4B-it`), `outcome` (Ok / Failed / InProgress).

**PII check:** none of these fields contain user names, account IDs, email, IMEI, MAC,
geolocation, or any free-form user content. `freeRamBytes` and process-memory counts are device
state, not user state. The `atEpochMs` is rendered as `HH:mm` with no date — the export's
top-level `exported:` line carries the full ISO timestamp anyway, but that is pre-Phase-3.5 and
out of scope.

**Path leakage check:** the header contains no filesystem paths. `getPath()` outputs are
`/data/user/0/<package>/...` (sandbox-internal) or `getExternalFilesDir`-rooted, neither of
which appears in the new rows. The `applicationId` line is the package name (`app.sanctum.machina`) —
public knowledge, not a secret.

**Token / API-key check:** none. There are no API tokens, HF tokens, auth headers, or session
identifiers anywhere in the codebase Phase 3.5 touches.

**Log-forging defense-in-depth:** `flattenForLogLine` collapses `\r\n\t` in `modelName` before
interpolation (`DeviceInfoCollector.kt:140-142`). Even though `modelName` is allowlist-controlled
today, this closes the line-injection vector if a future allowlist entry slipped a control
character in.

### 4.5 Thread-safety of `DiagnosticsState` (Decision 7) — **ok**

`DiagnosticsState.kt:23-35`:
```kotlin
private val ref = AtomicReference<InitSnapshot?>(null)
override fun onInitStart(...) { ref.set(InitSnapshot(..., InProgress)) }
override fun onInitEnd(success: Boolean) {
    ref.updateAndGet { current ->
        current?.copy(outcome = if (success) Outcome.Ok else Outcome.Failed)
    }
}
open fun lastInitSnapshot(): InitSnapshot? = ref.get()
```

Confirmed:
- `onInitEnd` uses `updateAndGet`, **not** `set` — CAS-correct read-modify-write. A future second
  writer cannot lose an outcome update under a concurrent `onInitStart`.
- Single-writer invariant in production (only `DefaultModelRegistry.initialize` under
  `lifecycleMutex.withLock` — `DefaultModelRegistry.kt:203, 245-280`) eliminates write-write race
  by construction; `AtomicReference` adds the visibility guarantee between writer
  (`Dispatchers.Default`) and readers on Main (`DiagnosticsViewModel.snapshot()` /
  `DeviceInfoCollector.formatLastInit`).
- `data class.copy(outcome=…)` is a pure function operating on an immutable record — no shared
  mutable state inside a snapshot.
- Atomic-snapshot contract validated by `concurrentWriterReaderNeverSeesMixedState` test
  (10 000 iterations, polish-pass added `observedNonNull` guard so the test cannot become
  vacuous — Task 4 decisions.md polish-pass `ed79677`).

### 4.6 `:crash`-process degradation (AC-H4) — **ok**

`AndroidDeviceInfoProvider` has three constructors (`DeviceInfoCollector.kt:205-221`):
- Primary `private constructor(context, entriesProvider, lastInitSnapshotProvider)` — both upstream
  thunks injected.
- `@Inject` secondary — main process — forwards `{ registry.models.value }` and
  `{ state.lastInitSnapshot() }`.
- **`(Context)` secondary — `:crash` process** — forwards `{ emptyList() }` and **`{ null }`**.

Confirmed: in `:crash`, `lastInitSnapshot()` returns `null` → `formatLastInit(null)` returns
`"пока не было"` (`DeviceInfoCollector.kt:123`). The five other process-memory getters
(`thresholdMemoryBytes`, `isLowMemory`, `processJavaHeapBytes`, `processNativeHeapBytes`,
`processTotalPssBytes`) read live system APIs that don't depend on the Hilt graph and so render
normally even without the singleton. Per-field `runCatching → NA_SENTINEL → "n/a"` keeps the
`process:` row whole even if a single Debug API throws on a hostile OEM.

No NPE / process-crash path: `lastInitSnapshotProvider` is a function, never null; even if
`DiagnosticsState` were missing entirely (it isn't bound in `:crash`), the `:crash` ctor never
references it.

### 4.7 `MemoryInfo.availMem` privilege check (Decision 9) — **ok**

`DefaultModelRegistry.kt:242-245`:
```kotlin
val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
val memInfo = ActivityManager.MemoryInfo()
activityManager.getMemoryInfo(memInfo)
initDiagnostics.onInitStart(modelName, memInfo.availMem, System.currentTimeMillis())
```

`ActivityManager.getMemoryInfo()` — synchronous JNI hop into the system service, **does not**
require a runtime permission, **does not** elevate privilege, and is documented thread-safe.
The reading thread is `Dispatchers.Default` under `lifecycleMutex` — no UI freeze risk.
Read-only, no side effects. Same posture as the existing `DeviceInfoProvider` consumers.

The `@ApplicationContext` reference is the same one already injected into `DefaultModelRegistry`
(`DefaultModelRegistry.kt:106`) for `Model.getPath(context)` calls — no new DI seam, no new
permission grant, no new IPC binding.

## 5. Findings

**None.** No critical / high / medium / low security findings.

The pre-flight gate, `InitDiagnostics` seam, allowlist-parser hardening, gradle git-describe,
logcat widening, header expansion, and SAF-export migration close at the same trust boundaries the
prior phases established. The new attack surface is bounded by:
- Bundled, signed allowlist asset (no network ingestion).
- Argv-list `ProcessBuilder` / `providers.exec` (no shell).
- `AtomicReference` snapshot of three scalars + one allowlist-controlled model id.
- SAF system-mediated user gesture for the only write to outside-sandbox storage.

Two prior per-task auditor recommendations are already merged into the audited code:
- Polish-pass `6b00f5c` (Task 7) added `flattenForLogLine` (closes log-forging defense-in-depth).
- Polish-pass `ed79677` (Task 4) tightened the `concurrentWriterReaderNeverSeesMixedState` test
  (closes the vacuous-test risk).

## 6. Recommendations (info-level, future hardening — not blocking)

These are observations for future phases, **not** Phase 3.5 deploy blockers:

- **info-1 — `version` field unvalidated.** `AllowedModel.version` (`ModelAllowlist.kt:43`) has no
  regex-check in `AllowlistLoader.parse()`. It defaults to `commitHash` (validated) when blank,
  but a JSON entry with a non-blank `version` would be used verbatim as a path segment via
  `Model.getPath()`. Pre-Phase-3.5; bundled asset doesn't exercise it; recommend adding
  `^[A-Za-z0-9._-]+$` regex-check in a future allowlist-touching task for symmetry with
  `modelFile` / `commitHash`.
- **info-2 — `runCatching(Throwable)` on async boundaries.** Three process-memory getters
  (`DeviceInfoCollector.kt:249-261`) use `runCatching` which catches `CancellationException`. Today
  `buildHeader()` is sync, so the issue is moot; if a future change makes any of these getters
  suspend, `runCatching` would swallow cancellation. Track when `DeviceInfoProvider` ever grows
  a `suspend` getter.
- **info-3 — `version` and `BuildConfig` mirror.** `provider.versionName()` reads
  `PackageInfo.versionName` via `PackageManager` (`DeviceInfoCollector.kt:225`); the AboutScreen
  footer reads `BuildConfig.VERSION_NAME` (`AboutScreen.kt:125`). Both ultimately come from the
  same Gradle `versionName` set in `app/build.gradle.kts:21`, so they cannot drift, but two
  source-of-truth lookups in the codebase invite future drift if one is replaced. Cosmetic.

## 7. References

- tech-spec § Decisions 5, 7, 9, 10 — `work/phase-3.5-diagnostics/tech-spec.md`
- tech-spec § Risks table — same file
- decisions.md Tasks 1–8 — `work/phase-3.5-diagnostics/decisions.md`
- Per-task security-auditor JSON reports —
  `work/phase-3.5-diagnostics/logs/working/task-{1,2,4,5,6,7,8}/security-auditor-1.json`
- code-audit (Task 9) — `work/phase-3.5-diagnostics/logs/audit/code-audit.md`
