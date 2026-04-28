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
