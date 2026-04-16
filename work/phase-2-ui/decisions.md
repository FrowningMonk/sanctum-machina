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
