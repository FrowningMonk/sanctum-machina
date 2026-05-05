# Decisions Log: phase-3.6-bugfix

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Task 1: ErrorLog refactor — shared `write(level, ...)` helper + `i()` / `w()` + whitelist `"inference-reset"`

**Status:** Done
**Commit:** 34032ea
**Agent:** main agent
**Summary:** Extracted `e()`'s body into a private `suspend fun write(level, component, description, cause)` that owns whitelist enforcement, `sanitize()`, length-bounding, mutex, append, and rotation; `e/i/w` are now thin public wrappers. `Level` enum (private, in-file) drives the format prefix via `level.name`. `ALLOWED_COMPONENTS` grew 14→15 with `"inference-reset"` for Phase 3.6 reset diagnostics. Closed-whitelist invariant preserved for all three levels (`require()` runs before any I/O).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 2 minor informational nits (no fix required) → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: approve, Decision 5 drift vector structurally eliminated → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- test-reviewer: approve, all 4 prescribed tests substantive and mutation-resistant → [logs/working/task-1/test-reviewer-1.json](logs/working/task-1/test-reviewer-1.json)

**Verification:**
- `./gradlew :core-runtime:testDebugUnitTest --tests ErrorLogTest` → green (13 tests: 9 existing + 4 new).
- `./gradlew :core-runtime:testDebugUnitTest` (full module suite) → green (no regression in `DefaultModelRegistryTest` etc.).
- `./gradlew :core-runtime:lintDebug` → green; no new warnings in `ErrorLog.kt` (2 pre-existing warnings in `build.gradle.kts` and `Consts.kt`, unrelated).
- `./gradlew :core-runtime:assembleDebug` → AAR built.
- Module boundary: `grep -rE "androidx.compose|androidx.activity" core-runtime/src/main` → 0 hits.

---

## Task 2: `ResetReason` enum + `ModelRegistry.resetConversation` signature + `DefaultModelRegistry` logging

**Status:** Done
**Commit:** 8f46a38 (impl `b8b763b` + review-fix `8f46a38`)
**Agent:** main agent
**Summary:** Added `ResetReason` enum (six values) in `:core-runtime/.../core/registry/`, extended `ModelRegistry.resetConversation` with mandatory `reason: ResetReason` (no default — Decision 1), and replaced the silent non-Ready skip in `DefaultModelRegistry` with `errorLog.w("inference-reset", "skipped reason=<NAME> status=<STATUS>")`; success path now emits `errorLog.i("inference-reset", "reason=<NAME>")`. All description text routes through Task 1's shared `write(level, …)` pipeline (sanitize + 500-char bound) — no parallel formatter (Decision 5). Mutex discipline preserved on every path. `:app` build is intentionally red until Task 3 wires reasons through `ChatViewModel`.
**Deviations:** Test 6 (`resetConversation_isSerializedByLifecycleMutex`) replaces the literal `recorded == [1, 2]` hint from the TDD anchor with a peak-concurrency `AtomicInteger` check (`peak == 1`); `AtomicInteger.incrementAndGet` is itself atomic so the original assertion would pass even without the mutex — peak-concurrency directly pins serialisation. `Thread.sleep(50)` instead of `delay(50)` because `LlmModelHelper.resetConversation` is non-suspend.

**Reviews:**

*Round 1:*
- code-reviewer: approve, 0 blocker / 0 major / 2 minor (KDoc nudges) / 3 info → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: approve, 0 blocker / 0 major / 0 minor, 2 info notes only → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: approve, 0 blocker / 0 major / 5 strengthening nits — three applied (canonical Idle-line format pin; Failed-arm asserts cause text survives; throw-arm asserts no WARN line) → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

**Verification:**
- `./gradlew :core-runtime:testDebugUnitTest --tests DefaultModelRegistryTest` → 12 tests pass (5 existing + 7 new).
- `./gradlew :core-runtime:testDebugUnitTest` (full module) → all suites green; no regressions in `ErrorLogTest`, `AllowlistLoaderTest`, `ModelRegistryActiveModelTest`, etc.
- `./gradlew :core-runtime:compileDebugKotlin` → UP-TO-DATE.
- Module boundary: `Get-ChildItem core-runtime/src/main -Recurse -Include *.kt | Select-String "androidx\.(compose|activity)"` → 0 hits.
- Enum completeness: `Select-String ResetReason.kt -Pattern "CHAT_SWITCH|DRAFT_COMMIT|LIGHT_OVERRIDE|SYSTEM_PROMPT|HEAVY|USER"` → 6 hits.
- No-default check: `Select-String ModelRegistry.kt -Pattern "reason: ResetReason\s*="` → 0 hits (Decision 1).
