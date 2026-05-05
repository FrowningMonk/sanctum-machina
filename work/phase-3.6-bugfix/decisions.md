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
