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

## Task 3: Порт media utils (`MediaUtils`, `AudioClip`, `calculatePeakAmplitude`) + ErrorLog whitelist/bounding

**Status:** Done
**Commit:** 855f43f (impl 53aa3aa + review-round-1 fix 855f43f)
**Agent:** main agent
**Summary:** Ported gallery media helpers to `:core-runtime/common/` — `MediaUtils.kt` (`decodeSampledBitmapFromUri`, `rotateBitmap`, pure `calculateInSampleSize`, `calculatePeakAmplitude`) and `AudioClip.kt` (plain class, D5). Extended `ErrorLog.kt` with runtime whitelist enforcement (unknown component → `IllegalArgumentException`) covering the four new Phase-2 components per D27, and bound cause-chain messages to 200 chars per TAC-15 (description stays at 500). D20 pure-JVM seam realised in code: primitive-only `calculateInSampleSize` and `calculatePeakAmplitude` live in `MediaUtilsPureTest` (no Robolectric, 1000× faster).
**Deviations:** (1) `calculatePeakAmplitude` returns `Float` (not Gallery's `Int`) and `bytesRead` defaults to `buffer.size` — sanctioned by the task edge-case note and driven by TDD anchor names (`0.0f`, `correct Float value`); documented in KDoc. (2) Added `androidx.exifinterface 1.4.1` (needed by `rotateBitmap` for `ORIENTATION_*` constants) and `androidx.test.core 1.6.1` testImplementation (for `ApplicationProvider` in Robolectric tests) — not in the task's "Files to modify" list. (3) Skipped the checked-in `test-image.jpg` resource: tests synthesise a 2048×2048 bitmap at runtime via `Bitmap.createBitmap` + `compress(JPEG)` to cacheDir and clean up in `@After` — self-contained, no binary in git. (4) Pixel-motion assertions for dimension-preserving rotations (180 / flipH / flipV) cannot run under Robolectric — `Bitmap.createBitmap(src, x, y, w, h, matrix, filter)` doesn't rasterise transformed pixels into the shadow-backed bitmap (`getPixel` returns 0). Fallback litmus: `assertNotSame(src, out)` catches the identity-matrix regression via Android's full-region/identity short-circuit; pixel correctness deferred to device smoke (AC-13, AC-18). (5) Robolectric was already added to `libs.versions.toml` and `core-runtime/build.gradle.kts` by Task 4 — no further changes needed there.

**Reviews:**

*Round 1:*
- code-reviewer: 0 critical / 0 major / 7 minor (approved_with_suggestions) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- test-reviewer: 0 critical / 2 major / 3 minor (needs_improvement) → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: approved, 2 minor residual (non-blocking) → [logs/working/task-3/code-reviewer-2.json](logs/working/task-3/code-reviewer-2.json)
- test-reviewer: passed, 2 minor residual (non-blocking) → [logs/working/task-3/test-reviewer-2.json](logs/working/task-3/test-reviewer-2.json)

Fixes applied in round 1 (commit 855f43f): `ALLOWED_COMPONENTS` → `internal`; JPEG-fixture rationale KDoc on `writeJpegTempFile`; trimmed implementation-internals comment in `decodeSampledBitmapFromUri_missingFile_returnsNull`; `@After` cleanup in `ErrorLogTest`; split pure-JVM tests to new `MediaUtilsPureTest.kt` (D20 seam); added `rotateBitmap` tests for `FLIP_VERTICAL`, `TRANSPOSE`, `TRANSVERSE`; strengthened rotation litmus with `assertNotSame`; added `descriptionTruncation_cappedAt500`; switched `AudioClipTest` to `assertSame`. Deferred with rationale: shared `TEST_SDK` constant (YAGNI at two call sites); `openStream` → `ErrorLog("attachment-decode")` wiring (task 7/8 integration, pure `:core-runtime` function can't inject `ErrorLog`); log-rotation test (fixture cost non-trivial, not on TDD anchor list, covered by manual smoke); optional `androidTest` for pixel-level rotation (tech-spec D20 rejected instrumentation for this phase).

**Verification:**
- `./gradlew :core-runtime:testDebugUnitTest --tests '*.MediaUtilsTest' --tests '*.MediaUtilsPureTest' --tests '*.AudioClipTest' --tests '*.ErrorLogTest'` → 31 passed, 0 failures (11 + 9 + 3 + 8)
- `./gradlew :core-runtime:test` → BUILD SUCCESSFUL (debug + release; 55 tests total, 0 failures including pre-existing 16 `AllowlistLoaderTest` + 8 `MultimodalContentsBuilderTest`)
- Whitelist-enforcement regression sweep: all 10 existing `errorLog.e(...)` callsites (`DefaultModelRegistry`, `ChatViewModel`, `DefaultAppSettingsRepository`) use whitelisted tags — zero hits outside the allowed set

---

## Task 5: strings.xml, AndroidManifest permissions + privacy hardening, dependencies

**Status:** Done
**Commit:** d411631 (impl 36379a2 + review-round-1 fix d411631)
**Agent:** main agent
**Summary:** Renamed `app_name` → "Sanctum Machina" (D18) and added all Phase-2 strings (7 settings labels, apply/default buttons, permission errors, ризонинг show/hide, attachment ensemble, camera/audio sheets, heavy-change dialog, reinit progress, systemPrompt-applied snackbar, About title/version) per ux-guidelines tone. Manifest: CAMERA + RECORD_AUDIO + matching `<uses-feature ... required="false">` for both camera and microphone (D11), `allowBackup="false"` retained, new `dataExtractionRules="@xml/data_extraction_rules"` with explicit exclude-root for both `<cloud-backup>` and `<device-transfer>` (D26). Version catalog + `app/build.gradle.kts` add CameraX 1.4.2 (core/camera2/lifecycle/view) and compose-richtext 1.0.0-alpha02 (commonmark + ui-material3, group `com.halilibo.compose-richtext` per D8); `lifecycle-runtime-compose` already present from Phase 1.
**Deviations:** (1) Added `<uses-feature android.hardware.microphone required="false">` — not in task's "What to do" but applied during round-1 fix for Play Store install parity with the explicitly-required camera uses-feature; same `required="false"` semantics. (2) Removed pre-existing `android:fullBackupContent="false"` from `<application>` — redundant on minSdk=31 once `allowBackup=false` + `dataExtractionRules` cover both backup channels; flagged by code-reviewer + security-auditor. (3) About `description` string deferred — D17 places About copy in `assets/about.md`, so only `about_title` + `about_version_format` are in `strings.xml`. (4) "Document" attachment label from task bullet skipped — Phase 2 supports only photo + audio attachments per user-spec; YAGNI.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions, 0 critical / 0 major / 5 minor → [logs/working/task-5/code-reviewer-1.json](logs/working/task-5/code-reviewer-1.json)
- security-auditor: approved_with_suggestions, 0 critical / 0 major / 3 minor → [logs/working/task-5/security-auditor-1.json](logs/working/task-5/security-auditor-1.json)
- infrastructure-reviewer: approved_with_suggestions, 0 critical / 0 major / 6 minor → [logs/working/task-5/infrastructure-reviewer-1.json](logs/working/task-5/infrastructure-reviewer-1.json)

Fixes applied in round 1 (commit d411631): added `microphone` uses-feature; dropped redundant `fullBackupContent`. Round 2 not run — all three reviewers verdict was `approved_with_suggestions` (not blocking), the two applied fixes are mechanical 3-line manifest deltas with smoke re-verification (assembleDebug + aapt re-dump confirmed both changes), and remaining residual suggestions are non-blocking and documented as deferred:
- compose-richtext alpha02 → Gradle dependency verification pin (security-auditor; alpha-stability hardening, deferred to a Phase-2 closing infra-task or Phase 3)
- `INTERNET` permission breadth + NetworkSecurityConfig (security-auditor; pre-existing, follow-up task)
- timer-format plural variants `%1$d с` (code-reviewer; MVP-acceptable for in-progress timer)
- `heavy_change_dialog_body` "5–30 секунд" literal (code-reviewer; intentional copy of D12 spec text)
- `<exclude domain="root" path="."/>` non-idiomatic vs `<exclude domain="root"/>` (infrastructure-reviewer; tech-spec D26 + TAC-14 grep require the explicit `path="."` form — kept per spec)
- implementation/ksp ordering cosmetic (infrastructure-reviewer; conformant either way)

**Verification:**
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (post-fix re-build also SUCCESS, 5s incremental)
- `aapt dump permissions app-debug.apk` → contains both `android.permission.CAMERA` and `android.permission.RECORD_AUDIO` (TAC-9)
- `aapt dump xmltree app-debug.apk AndroidManifest.xml` → `android:allowBackup=(type 0x12)0x0` (TAC-10), `android:dataExtractionRules=@0x7f110000` reference, two `uses-feature` blocks both `required=(type 0x12)0x0`, `fullBackupContent` absent
- `aapt dump xmltree app-debug.apk res/xml/data_extraction_rules.xml` → `<cloud-backup>` and `<device-transfer>` each contain `<exclude domain="root" path="."/>` (TAC-14)
- User-verify (launcher label "Sanctum Machina" on Honor 200) — pending physical device install; APK staged at `app/build/outputs/apk/debug/app-debug.apk`

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
