# Test Audit — Phase 3.5 Diagnostics

## Summary

**PASS.** All nine AC-T criteria are covered by concrete tests with meaningful assertions. No Mockito/MockK imports anywhere in the test tree. All hand-rolled fakes follow project convention (`patterns.md`). Concurrency test for `DiagnosticsState` is implemented correctly per Decision 7 (raw `Thread` + `CountDownLatch`, 10 000 iterations, attempt-set assertion with vacuous-race guard). Fixture-table coverage for the three pure functions matches the boundary cases listed in tech-spec § Testing Strategy. Verdict: **GO** for Pre-deploy QA (Task 12).

## Scope

Test files added or modified in Phase 3.5 Tasks 1–8 (12 test classes + 2 fakes, 114 `@Test` methods total):

| File | @Test count | Bucket |
|------|-------------|--------|
| `core-runtime/src/test/.../AllowlistLoaderTest.kt` | 25 | Robolectric |
| `core-runtime/src/test/.../DefaultModelRegistryTest.kt` | 5 | Robolectric |
| `core-runtime/src/test/.../ModelRegistryActiveModelTest.kt` | 7 | Robolectric |
| `core-runtime/src/test/.../NoOpInitDiagnostics.kt` | — (test fake) | — |
| `core-runtime/src/test/.../RecordingInitDiagnostics.kt` | — (test fake) | — |
| `app/src/test/.../diagnostics/DiagnosticsStateTest.kt` | 7 | pure-JVM |
| `app/src/test/.../logexport/DeviceInfoCollectorTest.kt` | 14 | pure-JVM |
| `app/src/test/.../logexport/LogExportManagerTest.kt` | 15 | Robolectric |
| `app/src/test/.../logexport/LogcatReaderTest.kt` | 6 | Robolectric |
| `app/src/test/.../ui/diagnostics/DiagnosticsViewModelTest.kt` | 8 | Robolectric |
| `app/src/test/.../ui/modelmanager/FormatRamShortageTest.kt` | 4 | pure-JVM |
| `app/src/test/.../ui/modelmanager/GateAllowsDownloadTest.kt` | 8 | pure-JVM |
| `app/src/test/.../ui/modelmanager/ModelManagerViewModelTest.kt` | 9 | Robolectric |
| `build-logic/src/test/.../build/GitVersionParseTest.kt` | 6 | pure-JVM |

## AC-T Coverage

- AC-T1: pass — `LogcatReaderTest::argvShape_exactlySixArgs_knownPositions` (`LogcatReaderTest.kt:84`) asserts `argv[5] == "*:W"` at line 102, plus argv-shape invariants for OWASP A03 / Decision 8.
- AC-T2: pass — `DeviceInfoCollectorTest` covers all new header fields. `headerFormatting_deterministicFromStub` (line 20) fixes the full deterministic blob; `memoryLine_containsThresholdAndLowMemory` (line 93) checks `threshold=` + `lowMemory=`; `processLine_rendersThreeFields` (line 110) plus three `_perFieldNaOnSourceError_*` (lines 122/138/151) cover per-field `n/a` degradation via the `Long.MIN_VALUE` sentinel; `lastInit_*` (lines 165/176/186/196/212/232) covers all four render branches plus newline-flatten guard plus AC-H4 `:crash` degradation.
- AC-T3: pass — `AllowlistLoaderTest::parse_rejectsNullMinDeviceMemory` (line 162), `parse_rejectsMissingMinDeviceMemory` (line 175), `parse_rejectsOutOfRangeMinDeviceMemory_zero|_negative|_tooLarge` (lines 189/199/211), `parse_acceptsValidMinDeviceMemory` (line 221), `parse_acceptsBoundaryMinDeviceMemory_one|_sixtyFour` (lines 230/236), and `load_logsRejectionToErrorLog` (line 242). The last test drives the full load → parse → ErrorLog path with component `"download"` and verifies the file's content.
- AC-T4: pass — `GateAllowsDownloadTest` (8 tests). Boundary set: 11.5/6=true, 5.3/6=false, 5.3/4=true, exact 4.0/4=true, 3.99/4=false, null=false, 3.0/4=false, 11.5/4=true. Exactly matches tech-spec § Testing Strategy.
- AC-T5: pass — `DiagnosticsStateTest` (7 tests). Six single-thread branches (`initialStateIsNull`, `onInitStartProducesInProgressSnapshot`, `onInitEndTrueProducesOkAndPreservesFields`, `onInitEndFalseProducesFailedAndPreservesFields`, `onInitEndWithoutStartIsNoop`, `secondOnInitStartReplacesPreviousAttempt`) plus `concurrentWriterReaderNeverSeesMixedState` (line 86). Concurrency test details below.
- AC-T6: pass — `DiagnosticsViewModelTest`. Four `lastInitText` variants (Ok / Failed / null / InProgress) at lines 92/112/129/138; the InProgress test asserts negative substrings (no `ok`, no `ошибка`). `freeRam_tickEverySecond_refreshesValue` (line 167) verifies 3 additional reads after `advanceTimeBy(3_000)` via call-counting fake. `whenCollectorCancels_pollingStops` (line 186) verifies `WhileSubscribed(0)` semantics. `buildAndWrite_success_returnsResultSuccess` / `_ioException_returnsResultFailure` (lines 210/221) cover the migrated About logic.
- AC-T7: pass — `GitVersionParseTest` (6 tests, lines 9–43). Cases: tagged_clean, tagged_with_commits (`-3-gabc1234`), tagged_dirty (`-dev`), empty stdout → null, exit=1 → null, exit=128 → null. All 6 boundary cases from tech-spec.
- AC-T8: pass — see Existing-tests-still-passing list below.
- AC-T9: pass — `core-runtime/src/test/.../NoOpInitDiagnostics.kt` (hand-rolled, both methods Unit) used by `ModelRegistryActiveModelTest::defaultModelRegistry_activeModelName_wiredToMutationsOfUnderlyingModelsFlow` (line 217). `RecordingInitDiagnostics` (29 lines) is the positive-assertion fake used by `DefaultModelRegistryTest`.

## Mock-framework scan

- `import org.mockito` matches: **<empty>** (whole repo, all source sets).
- `import io.mockk` matches: **<empty>** (whole repo, all source sets).

Verified by ripgrep across `**/*.kt` over the entire workspace — zero hits. Hand-rolled fakes are the exclusive convention (`patterns.md`).

## Test pyramid compliance

- **Pure-JVM tests:** 5 files / 39 `@Test` methods.
  - `DiagnosticsStateTest` (raw `Thread`+`CountDownLatch`, no Android types).
  - `DeviceInfoCollectorTest` (hand-rolled `StubDeviceInfoProvider`, `java.time.ZoneOffset.UTC`, no Android types).
  - `FormatRamShortageTest`.
  - `GateAllowsDownloadTest`.
  - `GitVersionParseTest` (in `:build-logic`).
- **Robolectric tests:** 7 files / 75 `@Test` methods. Justifications below.
- **Instrumented tests:** 0. Confirmed — no `androidx.test.ext.junit.runners.AndroidJUnit4` instrumentation runner anywhere in the new set.

### Robolectric justifications (per file)

- `LogcatReaderTest.kt` — `LogcatReader.read()` calls `android.os.Process.myPid()` while building argv; that returns `-1` outside Android runtime and would skew the regex assertion. Justified.
- `LogExportManagerTest.kt` — uses `ApplicationProvider.getApplicationContext<Context>()`, `Context.filesDir`, real `File` IO under app-private storage, `Uri.parse`. All three are platform classes without JVM stand-ins. Justified.
- `DiagnosticsViewModelTest.kt` — needs `android.net.Uri.parse` for `buildAndWrite_*` (no JVM impl, `unitTests.returnDefaultValues = false` is project default). Class-level docstring explicitly justifies the choice over splitting into two test classes. Justified.
- `ModelManagerViewModelTest.kt` — constructs real `CrashState` and `LogExportManager` from `ApplicationProvider`. Constructor is `class CrashState(@ApplicationContext context)` and exercising the real ctor matches production wiring. Justified.
- `AllowlistLoaderTest.kt` — needs `Context` (for `ErrorLog`) and `context.filesDir` (for the `errors.log` write-path verification in `load_logsRejectionToErrorLog`). Justified.
- `DefaultModelRegistryTest.kt` — needs `ActivityManager.MemoryInfo` and `Robolectric.shadowOf(am).setMemoryInfo(...)` to inject a known `availMem` so the assertion `expectedAvailMem == 7_654_321_000L` is not a tautology against Robolectric's default 0L. The shadow is mandatory — `availMem` cannot be set without a Robolectric shadow. Justified.
- `ModelRegistryActiveModelTest.kt` — six derivation tests run pure-logic but pay only the per-class Robolectric init cost (negligible vs the wiring test which needs real `Context`). Class docstring explicitly weighs splitting vs single-class cost. Justified by amortisation.

No Robolectric usage is unjustified. No test was promoted to Robolectric where a hand-rolled fake would have sufficed (e.g. `DeviceInfoCollectorTest` deliberately stays pure-JVM through the `StubDeviceInfoProvider` seam).

## Concurrency test (DiagnosticsStateTest)

- **Iterations:** **10 000** (`DiagnosticsStateTest.kt:88`, constant `iterations = 10_000`). Meets the ≥10 000 threshold.
- **Threading primitives:** `java.lang.Thread` + `java.util.concurrent.CountDownLatch` + `java.util.concurrent.atomic.AtomicReference` (for failure capture) + `AtomicInteger` (for vacuous-race detection). No jcstress / lincheck / kotlinx-coroutines-test. Per Decision 7 + tech-spec. **OK.**
- **Attempt-set assertion:** Reader uses an explicit `when (snap.modelName)` (line 117) that admits exactly:
  - `(modelA, FREE_RAM_A, AT_A, InProgress|Ok)` (lines 118–120),
  - `(modelB, FREE_RAM_B, AT_B, InProgress|Failed)` (lines 121–123),
  - `null` (early return at line 115).
  Any other tuple flips `valid` to `false` and re-throws via the captured `AssertionError`. The forbidden tuples `(modelA, Failed)` and `(modelB, Ok)` are not in the admitted set, so they fail the test. Field cross-checks (`freeRamBytes == FREE_RAM_A`, `atEpochMs == AT_A`) are present, locking that the `data class copy` produced a fresh object rather than mutating in place. **OK.**
- **Vacuous-race guard:** `observedNonNull.get() == 0` triggers `fail("reader observed only null — race window was not exercised, test is vacuous")` (line 148). Defends against scheduling that could let the reader skip every write (e.g. a JIT optimisation or hot-path reorder that never lets the reader observe a non-null snapshot). Strong addition over what tech-spec required.

Concurrency test is correctly implemented per Decision 7 with a vacuous-race guard added beyond spec.

## Fixture-table coverage

- **`formatRamShortage`:** OK. 4 cases mapped to tech-spec § Testing Strategy:
  - `5_694_498_816L / 6` → `"Недостаточно RAM (5.3 ГБ устройство, нужно 6 ГБ)"` (line 19).
  - `11_500_000_000L / 16` → `"… (10.7 ГБ …, нужно 16 ГБ)"` (line 26).
  - `5_368_709_120L / 6` (5×1024³) → `"5.0 ГБ"` (line 33) — locks the `5 ГБ` regression.
  - `4_294_967_295L / 4` (4 GB − 1 byte) → `"3.9 ГБ"` (line 40) — locks floor-not-round-up.
  Note: tech-spec mentions a `5.3/16` case in the Task 11 brief but the production fixtures (and the actual UI scenario) use `5.3/6`. Test correctly reflects the production scenario; brief had a minor inconsistency.
- **`gateAllowsDownload`:** OK. 8 cases:
  - `11.5GB/6 → true` (line 21), `5.3GB/6 → false` (line 27), `5.3GB/4 → true` (line 33), `exact 4GB/4 → true` (line 39), `(4GB−1 byte)/4 → false` (line 45), `null minGb → false` (line 51), `3GB/4 → false` (line 57), `11.5GB/4 → true` (line 63). Boundary inclusive (`>=`) verified twice (exact equality / just-below). All 6 cases from tech-spec plus 2 AC-T4 anchors.
- **`gitVersionParse`:** OK. 6 cases:
  - `"v0.3.5-diagnostics\n", 0` → trimmed tag.
  - `"v0.3.5-diagnostics-3-gabc1234\n", 0` → ahead-of-tag.
  - `"v0.3.5-diagnostics-dev\n", 0` → dirty `-dev` suffix.
  - `"", 0` → null.
  - `"anything", 1` → null.
  - `"anything", 128` → null.
  Matches the 6 cases in tech-spec § Testing Strategy. Test resides in `:build-logic` (decision per Task 2 placement option), not in `:app`.

## Trivial-assert scan

- `assertEquals(x, x)` regex `assertEquals\(\s*([^,]+)\s*,\s*\1\s*\)`: **<empty>**.
- `assertTrue(true)` / `assertFalse(false)`: **<empty>**.
- `@Test` methods without an `assert*` / `fail(` / `assertThat`: **<empty>** in the new set (manually scanned all 114 method bodies during file reads above).

No trivial assertions found.

## Test isolation

- **`@Before/@After` cleanup:** `LogExportManagerTest` (`logsDir.deleteRecursively()` in both `@Before` and `@After`, lines 40/45), `AllowlistLoaderTest` (`errorLogFile.parentFile?.deleteRecursively()` in both `@Before` and `@After`, lines 34/39). No file IO leaks between tests.
- **No mutable static state:** Searched for `companion object` with `var`. The only `companion object`s in the new tests are `private companion object` blocks holding `const val` constants (`DiagnosticsStateTest:153`, `DeviceInfoCollectorTest:325`, `DiagnosticsViewModelTest:240`) — read-only, safe.
- **No shared writable fixtures:** Each test instantiates its own `DiagnosticsState`, `StubDeviceInfoProvider`, `FakeAppSettingsRepository`, etc. The Robolectric runner gives each `@Test` a fresh class instance per JUnit 4 contract, so `private lateinit var` fields are re-initialised in `@Before`. Confirmed.
- **`Dispatchers.setMain` is paired with `Dispatchers.resetMain`:** `DiagnosticsViewModelTest:79`, `ModelManagerViewModelTest:76`. Correct teardown.

No isolation issues.

## Existing-tests-still-passing list

- **`ModelManagerViewModelTest`** — adapted: VM constructor receives the new `DeviceInfoProvider` parameter (`ModelManagerViewModelTest.kt:81`). `FakeDeviceInfoProvider` (lines 301–324) is a hand-rolled stub that errors loudly on every accessor except `totalMemoryBytes()` — defending against silent stub drift. Pre-existing assertions (`setDefaultModel_*`, `defaultModelId_*`, `onLoad_*`) untouched. **OK.**
- **`AboutViewModelTest`** — class no longer exists in the tree (`grep "class AboutViewModel"` returns 0 hits across `**/*.kt`). The `buildAndWrite` logic was migrated to `DiagnosticsViewModel`; corresponding test methods (`buildAndWrite_success_returnsResultSuccess` / `_ioException_returnsResultFailure`) live in `DiagnosticsViewModelTest`. This matches the `tech-spec § Existing tests to verify still passing` note: «если `buildAndWrite` мигрирует целиком, соответствующий тест переносится в `DiagnosticsViewModelTest`». **OK — consistent with tech-spec.**
- **`LogExportManagerTest.headerContainsRequiredFields`** — present (`LogExportManagerTest.kt:51`), extended with `process:` and `last init:` substrings (lines 62–63). Existing substring assertions for `applicationId:`, `version:`, `device:`, `memory:`, `active model:`, `downloaded models:` retained. **OK.**
- **`WarmupCoordinatorTest`** — present at `app/src/test/.../engine/WarmupCoordinatorTest.kt`, not touched by the phase diff. The `InitDiagnostics` interface is wired inside `DefaultModelRegistry`; `ModelRegistry` interface (which Warmup uses) is unchanged. **OK.**
- **`AllowlistLoaderTest.fixtureMatchesProductionAsset`** — present (`AllowlistLoaderTest.kt:87`). Byte-exact comparison between `src/main/assets/model_allowlist.json` and `src/test/resources/model_allowlist_fixture.json`. After Task 1's recalibration both files were updated in lockstep, so the test continues to catch drift. **OK.**
- **`AppCorruptionStateTest`** — present at `app/src/test/.../engine/AppCorruptionStateTest.kt` (path differs from tech-spec listing of `crash/AppCorruptionStateTest`, but file exists; minor location detail, not a regression). Not touched by the phase diff. **OK.**

All six items from tech-spec § Existing tests to verify still passing are accounted for.

## Findings

**None.** No CRITICAL / MAJOR / MINOR findings.

Notes (not findings, do not block deploy):

- The Task 11 brief lists a `5.3/16` formatRamShortage case, but the production fixtures and the test correctly use `5.3/6` (the actual sub-threshold scenario for E4B). The `5.3/16` figure in the brief appears to be a transcription artefact from `decisions.md` Task 5 wording. No test impact — the test file covers the boundaries that matter.
- `DiagnosticsStateTest::concurrentWriterReaderNeverSeesMixedState` adds a vacuous-race guard (`observedNonNull == 0 → fail`) beyond what tech-spec required. This is positive deviation — it stops the test from passing if the JIT optimised away the race window.

## Verdict

**GO** for Pre-deploy QA (Task 12).

All nine AC-T criteria covered with concrete, asserting tests; zero Mockito/MockK; concurrency contract pinned via the Decision 7 primitives at the spec'd iteration count with a vacuous-race guard; fixture-table coverage matches tech-spec boundary tables; hand-rolled fakes follow `patterns.md`; test isolation verified; existing-tests-still-passing list reconciled (including AboutViewModelTest's documented migration into DiagnosticsViewModelTest).
