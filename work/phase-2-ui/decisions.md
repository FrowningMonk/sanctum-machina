# Decisions Log: phase-2-ui

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Task 1: AC-1 — allowlist parsing fix + systemPromptDefault plumbing

**Status:** Done
**Commit:** 31bd1d0 (impl 87e5a61 + review-round-1 fix 31bd1d0)
**Agent:** main agent
**Summary:** Routed `llmSupportImage/Audio/Thinking` from allowlist JSON into `AllowedModel` → `Model`, and wired `defaultConfig.systemPromptDefault` through `AllowedModelConfig` → new `ConfigKeys.SYSTEM_PROMPT_DEFAULT` via `createLlmChatConfigs(defaultSystemPrompt=...)` emitting a `LabelConfig` so `Model.configValues` carries the default. Downstream engine wiring (D24 step 4) stays scoped to task 10.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: OK → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: OK → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- test-reviewer: 2 low → [logs/working/task-1/test-reviewer-1.json](logs/working/task-1/test-reviewer-1.json)

*Round 2 (after fixes):*
- test-reviewer: OK → [logs/working/task-1/test-reviewer-2.json](logs/working/task-1/test-reviewer-2.json)

**Verification:**
- `./gradlew :core-runtime:testDebugUnitTest --tests AllowlistLoaderTest` → 16 passed, 0 failures
- `fixtureMatchesProductionAsset` (TAC-6) → green (fixture and prod byte-identical, both already carry `llmSupport*` fields)

---

## Task 4: `MultimodalContentsBuilder` + рефакторинг `LlmChatModelHelper.runInference`

**Status:** Done
**Commit:** a5e46bd (impl 28a3a1b + review-round-1 c82c6bb + review-round-2 a5e46bd)
**Agent:** main agent
**Summary:** Moved inline `List<Content>` assembly (images → audio → text) from `LlmChatModelHelper.runInference` into pure `MultimodalContentsBuilder.build` in `:core-runtime/common/` per D22; the private `Bitmap.toPngByteArray` extension moved with it. Behavior unchanged: same order, same whitespace guard (now via `isNotBlank()`). Added Robolectric 4.12 as `testImplementation` in `:core-runtime` per D20 and enabled `testOptions.unitTests.isIncludeAndroidResources`.
**Deviations:** Added `robolectric` to `gradle/libs.versions.toml` and `core-runtime/build.gradle.kts` — scoped for Task 3 in Wave 2 but required here because `MultimodalContentsBuilderTest` (Wave 1) needs Bitmap. Task 3 can skip its Robolectric-add step. Smoke command in task file uses `:core-runtime:test --tests MultimodalContentsBuilderTest`, but `:core-runtime:test` is an aggregate task that does not accept `--tests`; used `:core-runtime:testDebugUnitTest --tests '*.MultimodalContentsBuilderTest'` for targeted verification and the aggregate `:core-runtime:test` for the full-suite smoke.

**Reviews:**

*Round 1:*
- code-reviewer: 3 minor → [logs/working/task-4/code-reviewer-1.json](logs/working/task-4/code-reviewer-1.json)
- test-reviewer: 2 major + 3 minor → [logs/working/task-4/test-reviewer-1.json](logs/working/task-4/test-reviewer-1.json)

*Round 2 (after fixes):*
- test-reviewer: passed, 1 residual minor (fixed in commit a5e46bd) → [logs/working/task-4/test-reviewer-2.json](logs/working/task-4/test-reviewer-2.json)

**Verification:**
- `./gradlew :core-runtime:test` → SUCCESS (debug + release variants; 8 `MultimodalContentsBuilderTest` + 16 `AllowlistLoaderTest` per variant, 0 failures)
- `./gradlew :core-runtime:testDebugUnitTest --tests '*.MultimodalContentsBuilderTest'` → 8 passed, 0 failures

---

## Task 2: Создать `:core-settings` + version catalog entries

**Status:** Done
**Commit:** 127b381 (impl 5de04cd + review-round-1 fix 127b381)
**Agent:** main agent
**Summary:** Added new gradle module `:core-settings` with Proto DataStore per-model overrides keyed by `Model.modelId` (D1, D3). Schema matches tech-spec § Data Models (7 explicit-optional fields on `PerModelSettings`, `AppSettings.per_model_overrides` map, java_package `app.sanctum.machina.core.settings.proto`). `DefaultAppSettingsRepository` catches `IOException` (covers `CorruptionException` since it extends IOException) on observe/save/reset and logs via `ErrorLog.e("settings-io", ...)` (R13, D27). Hilt `CoreSettingsModule` provides `@Singleton DataStore<AppSettings>` at `context.filesDir/datastore/app_settings.pb` and binds the repository. Library manifest is self-closing (no `<application>` attrs) to preserve `:app`'s backup flags at merge time. `AppSettingsRepositoryTest` — 6 tests per TDD anchor.
**Deviations:** Added `kotlinx-coroutines-test 1.10.2` and `androidx-test-core 1.6.1` to `libs.versions.toml` as `testImplementation`-only — not listed in task's "Files to modify" but required for `runTest(UnconfinedTestDispatcher())` + `ApplicationProvider.getApplicationContext()` in `AppSettingsRepositoryTest`. Task file's smoke-step path (`build/generated/source/proto/main/java/...`) corrected in-place to actual protobuf-plugin 0.9.5 output (`build/generated/sources/proto/debug/java/...`, per-variant, plural "sources") — documented drift.

**Reviews:**

*Round 1:*
- code-reviewer: 1 critical + 1 major + 4 minor → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: 1 critical + 2 minor → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: 2 major + 2 minor → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: passed, 0 new → [logs/working/task-2/code-reviewer-2.json](logs/working/task-2/code-reviewer-2.json)
- security-auditor: passed, 0 new → [logs/working/task-2/security-auditor-2.json](logs/working/task-2/security-auditor-2.json)
- test-reviewer: passed, 0 new → [logs/working/task-2/test-reviewer-2.json](logs/working/task-2/test-reviewer-2.json)

Fixes applied in round 1 (commit 127b381): protobuf 4.26.1 → 4.28.3 (security-auditor critical — CVE-2024-7254 stack-overflow in proto-javalite < 4.27.2); explicit `parentFile?.mkdirs()` in Hilt DataStore `produceFile` (code-reviewer minor); task.md smoke path correction (code-reviewer minor). Deferred with documented rationale: code-reviewer "Hilt library-plugin variant" (false positive — `com.google.dagger.hilt.android` is the single unified plugin for app+library); code-reviewer "settings-io whitelist" (intentional per D27 — Task 3 extends `ErrorLog.kt` whitelist); security-auditor "CorruptionException not caught" (false positive — extends IOException); test-reviewer "ErrorLog.e not asserted" and "real ErrorLog side effect" (would require making `ErrorLog` open or interface-based — out of scope for task 2; deferred to task 3 which owns ErrorLog extension).

**Verification:**
- `./gradlew :core-settings:test` → BUILD SUCCESSFUL (debug + release variants; 6/6 `AppSettingsRepositoryTest` passed, 0 failures)
- `./gradlew :core-settings:build` → BUILD SUCCESSFUL
- `ls core-settings/build/generated/sources/proto/debug/java/app/sanctum/machina/core/settings/proto/` → contains `AppSettings.java`, `PerModelSettings.java`, `AppSettingsOrBuilder.java`, `PerModelSettingsOrBuilder.java`, `AppSettingsOuterClass.java`
- `grep -n "hasMaxTokens\|getMaxTokens\|hasEnableThinking\|hasAccelerator\|hasSystemPromptDefault" PerModelSettings.java` → all methods generated (TAC-12)

---

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
