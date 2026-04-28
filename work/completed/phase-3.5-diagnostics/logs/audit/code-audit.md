# Code Audit — phase-3.5-diagnostics

Result: pass
Date: 2026-04-28
Auditor: main agent (Task 9 — Audit Wave)
Scope: Tasks 1–8 (final state on branch `phase/3.5-diagnostics`, HEAD `0b7940e`)

## Cross-component checks

1. **Audit report produced — pass**
   - File: `work/phase-3.5-diagnostics/logs/audit/code-audit.md`. Header carries explicit `Result: pass`.

2. **Interface seam — `:core-runtime` does not import `:app` diagnostics packages — pass**
   - Command: `grep -rEn "app\.sanctum\.machina\.diagnostics" core-runtime/src/main`
   - Result: 0 matches. Module boundary intact (Decision 6).
   - The `InitDiagnostics` interface in `core-runtime/src/main/.../registry/InitDiagnostics.kt:13` mentions `DiagnosticsState` only in its KDoc — KDoc text is not an import, so the boundary is not crossed.

3. **No Compose / Activity in `:core-runtime` — pass**
   - Command: `grep -rEn "androidx.compose|androidx.activity" core-runtime/src/main`
   - Result: 0 matches. TAC-7 regression gate clean.

4. **`ErrorLog.ALLOWED_COMPONENTS` whitelist unchanged (14 components) — pass**
   - Source of truth: `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt:32-50`.
   - Set verbatim: `download`, `inference-init`, `inference`, `inference-cleanup`, `settings-io`, `camera`, `audio`, `attachment-decode`, `model`, `engine-warmup`, `history-read`, `history-write`, `attachment-save`, `attachment-read` — exactly 14, identical to `patterns.md` § ErrorLog whitelist.
   - All Phase 3.5 `errorLog.e(...)` calls re-use existing whitelist values:
     - `core-runtime/.../registry/AllowlistLoader.kt:52` — `"download"` (Task 1, parser rejection log).
     - `core-runtime/.../registry/DefaultModelRegistry.kt:263, :278` — `"inference-init"` (Task 6 init failure logs, pre-existing site).

5. **Shared resources ownership matches Architecture table — pass**
   - **`DiagnosticsState` (`@Singleton`)** — single owner: `app/src/main/.../diagnostics/di/DiagnosticsModule.kt:21-22` (`@Binds`). The implementation `app/src/main/.../diagnostics/DiagnosticsState.kt:21` carries `@Singleton` + `@Inject constructor()`. No other `@Provides` / `@Binds` / non-test instantiations. Production has exactly one instance; test sources construct freely (`DiagnosticsStateTest`, `FakeDiagnosticsState`) which is correct — those are out-of-graph fakes.
   - **`DeviceInfoProvider` (`AndroidDeviceInfoProvider`)** — main-process owner: `LogExportModule.kt:25` `@Binds`. Secondary non-Hilt ctor for `:crash`: `DeviceInfoCollector.kt:217-221`. `LogExportManager.kt:88` calls the secondary ctor in the `:crash` recovery path. Three-ctor structure exactly as Decision 10 / tech-spec § Shared resources prescribe. Consumers — `DeviceInfoCollector` (constructor inject), `ModelManagerViewModel.kt:42`, `DiagnosticsViewModel.kt:50` — all declare the dependency through constructor injection, no dynamic lookups.
   - **`ActivityManager`** — system service. Two production lookup sites, both in resource-owning classes:
     - `DefaultModelRegistry.kt:242` — snapshot site (`@ApplicationContext` per Decision 9).
     - `DeviceInfoCollector.kt:289` — central `memoryInfo()` helper inside `AndroidDeviceInfoProvider`.
     No Hilt wrappers, matches the table's "system service lookup, no Hilt wrapper" entry. Both consumers (`AndroidDeviceInfoProvider`, `DefaultModelRegistry`) are explicitly enumerated in the table.

6. **No duplicate heavy-resource init — pass**
   - `(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)` — appears in production at exactly two sites (above), both legitimate per Decision 9 + Shared resources table. Test sources have one occurrence in `core-runtime/src/test/.../DefaultModelRegistryTest.kt:87` for `ShadowActivityManager` seeding — out-of-graph by design.
   - `DiagnosticsState` — production instances: 0 direct `new`/ctor calls; only the Hilt-injected singleton.
   - `DeviceInfoProvider` / `AndroidDeviceInfoProvider` — production: Hilt graph (1 instance per process) + non-Hilt secondary in `:crash` (1 instance in that process). Matches the "1 per process" cell.

## Findings

Findings: none (no blocker / major).

Three minor observations are recorded in **Inconsistencies between tasks** + **Notes** below, all explicitly anticipated and accepted in `decisions.md` or upstream task reviews; none rise to a finding.

## Inconsistencies between tasks

- **Two distinct read paths for `lastInitSnapshot`** — `DeviceInfoCollector.buildHeader` reads `provider.lastInitSnapshot()` (through the `DeviceInfoProvider` interface), while `DiagnosticsViewModel.snapshot()` reads `diagnosticsState.lastInitSnapshot()` directly on the impl class. Both ultimately hit the same `AtomicReference`, but the API surface is not symmetric across the two readers. Acknowledged in `decisions.md` Task 8 Deviations: "Reader-side seam interface symmetrical to the writer side (`InitDiagnostics`) was considered and rejected — `open class` keeps the test substitution cost at one keyword without inventing an interface for a single consumer." Severity: none (deliberate trade-off, costs are correct as evaluated).

- **Two `private const val LOG_TAG_DOWNLOAD = "download"`** — declared in `AllowlistLoader.kt:57` and `DefaultModelRegistry.kt:44`. Same value, same package family, two separate file-private constants. Could be hoisted into `core-runtime/log/`, but doing so would couple unrelated call sites for negligible win. Severity: none (each constant is local-scope, idiomatic for the file; refactor cost ≥ refactor benefit).

## Notes

- `buildHeader` invokes `memoryInfo()` four times (`totalMemoryBytes` / `availableMemoryBytes` / `thresholdMemoryBytes` / `isLowMemory`), each constructing a fresh `ActivityManager.MemoryInfo` and calling `am.getMemoryInfo()`. ≈ 4 JNI hops per export call. The pattern is pre-existing (the first two calls predate Phase 3.5; Task 7 added the `threshold` / `lowMemory` calls and the 5 `Debug.MemoryInfo` getters). Task 7 code-reviewer flagged the same observation as "pre-existing, non-regression" and the team chose not to expand scope. Cost ≈ 200 µs on a single, user-initiated export — negligible. No action needed.

- The two production formatters `formatGbFloor` (Russian `ГБ`, integer-floor, in `ui/modelmanager/GateDecision.kt`) and `formatGb` (English `%.1f GB`, private to `DeviceInfoCollector.kt`) are intentionally distinct — surface in different contexts (UI gate / diagnostics screen vs. raw `.txt` export header). The KDoc on `formatLastInit` (`DeviceInfoCollector.kt:99-117`) documents the distinction explicitly. No silent divergence, no shared format that could drift.

- Test fakes for `InitDiagnostics` are consistent across the suite: `RecordingInitDiagnostics` (positive call-order assertions, 5 uses in `DefaultModelRegistryTest`) and `NoOpInitDiagnostics` (isolation, 1 use in `ModelRegistryActiveModelTest`). No inline-anonymous `: InitDiagnostics by NoOp...` style fakes — convention from `decisions.md` Task 4 holds.

- Drawer order — `Модели → Диагностика → О приложении` — verified at `DrawerContent.kt:201, :214, :227`. Matches Task 8 Description and Decision 11.

- `AboutViewModel.kt` is gone from `app/src/main/.../ui/about/` (only `AboutScreen.kt` remains) — confirms Decision 11 ("AboutViewModel.buildAndWrite removed; whole VM if it empties out"). `AboutScreen.kt` keeps `SafeMarkdown`, `AboutFooter`, `tapCounter`, version-tap → `AlertDialog`, dev-crash throw — full 7-tap regression surface preserved.

- `DefaultModelRegistry.initialize` correctly implements Decision 8: `onInitStart` after `releaseEngine` and before the first `awaitInitialize` (line 245); single `onInitEnd(true)` on either GPU or CPU success arm (lines 258 / 274); `onInitEnd(false)` on the failure arm after `errorLog.e` (line 280); cancellation arm intentionally skips `onInitEnd` so `Outcome.InProgress` survives until the next attempt (lines 282-288).
