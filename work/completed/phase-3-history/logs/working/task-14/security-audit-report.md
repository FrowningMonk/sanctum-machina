---
task: 14
type: security-audit
status: final
auditor: security-auditor
date: 2026-04-22
---

# Phase 3 — Security Audit Report

## Executive summary

Phase-3 code (chat history, engine warmup, DataStore migration, crash-state singleton) **passes** the seven focus areas defined by the tech spec. No Critical or High findings. Two low-severity observations: one data-integrity edge case in `SettingsMigrationHelper` (re-migration drops overrides when the DataStore read throws an IOException) and one bounding concern around inference-error logging that relies on `ErrorLog`'s truncation rather than caller-side limits. Privacy-manifest regression checks and `SafeMarkdown` usage are all green.

## Findings table

| # | Severity | Area | File:line | Description |
|---|----------|------|-----------|-------------|
| F1 | Low | Migration atomicity | `app/src/main/kotlin/app/sanctum/machina/data/SettingsMigrationHelper.kt:40` (+ `core-settings/.../DefaultAppSettingsRepository.kt:116-122`) | `isSettingsMigrated()` returns `false` on `IOException`. A transient read failure during the sentinel check will re-run `migrateIfNeeded()` on already-migrated data; because the remap table is keyed by `Model.name` but the current keys are `Model.modelId`, every entry is treated as an orphan and dropped. Data integrity, not confidentiality. |
| F2 | Info | PII-in-logs hardening | `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt:1192`, `:1275` | `errorLog.e("inference", safeMsg)` forwards the engine's `onError` string without a caller-side length cap. `ErrorLog` sanitises & truncates at 500 chars, so practical exposure is bounded; but the caller does not guarantee the payload is free of inputs reflected back by the native engine. |

No Critical / High / Medium findings.

## Per-focus-area verdicts

### 1. File-path injection — PASS

- `chatId` is typed `Long` end-to-end (`ChatEntity.id`, `MessageEntity.chatId`, nav arg `NAV_ARG_CHAT_ID: Long`, `DefaultChatRepository.commitDraftChat(modelId, ...): Long`, `deleteChat(chatId: Long, ...)`, `savePersistentAttachment(chatId: Long, ...)`). No code path accepts a `String` chatId.
- Path construction uses `File(filesDir, "$ATTACHMENTS_DIR/$id/...")` and `File(filesDir, "attachments/$chatId")` against numeric ids (`DefaultChatRepository.kt:141-142`, `:228`, `:330`, `:358`).
- Every public repository method with a `File` argument invokes `requireInsideAttachmentsRoot(candidate, filesDir)` (`DefaultChatRepository.kt:290-298`) which canonicalises both paths and requires `candidate.canonicalPath.startsWith(attachmentsRoot + File.separator)`. This is applied to `writeAttachmentStaging`, `deleteStagedAttachment`, `pruneStagingDir`, `savePersistentAttachment`, and `commitDraftChat`. A misconfigured `filesDir` or a poisoned staging dir cannot escape the attachments tree.
- `ChatViewModel.resolveInsideAttachmentsRoot` (`ChatViewModel.kt:1474-1483`) applies the same canonical-path containment guard on Room-stored `image_path` / `audio_path` reads, defending against a poisoned DB row.
- The staging-dir suffix (`attachments/.staging-${UUID.randomUUID()}` at `ChatViewModel.kt:676-679`, and the `.staging-{uuid}` prefix matched in `StartupHousekeeper.kt:18`) is generated from `UUID.randomUUID()` — not user input — and is therefore not attacker-controllable.

### 2. SQL injection surface — PASS

- All DAO queries use Room `@Query` with `:param` binding (`ChatDao.kt:19-26`, `MessageDao.kt:13-52`). No `@RawQuery`, no `rawQuery`, no `execSQL` with interpolation anywhere in Phase-3 code.
- The only literal `execSQL` call is `db.execSQL("PRAGMA foreign_keys = ON")` in `SanctumDatabase.kt:30` — a compile-time constant, no user data.
- Grep verification: `rawQuery|execSQL|RawQuery` across `app/src/main/kotlin/app/sanctum/machina/` yields one match only (the PRAGMA line above).

### 3. Privacy guarantee — `quick/` purge — PASS

- `StartupHousekeeper.purgeQuickDir` (`StartupHousekeeper.kt:68-78`) invokes the `deleter` seam, which calls `dir.deleteRecursively()` and throws `IOException` on a `false` return value (`StartupHousekeeper.kt:46-50`). Non-empty directories are fully removed, matching tech-spec requirement `(a) deleteRecursively() is called`.
- Failure is caught and routed to `errorLog.e("attachment-save", "quick/ purge failed", cause)` (`StartupHousekeeper.kt:76`) — satisfies requirement `(b) wrapped in try/catch with ErrorLog.e on failure`.
- Missing `quick/` is a no-op early return (`:70`), which is the correct behaviour for the common case.
- No Phase-3 code writes quick-chat content outside `filesDir/quick/`. Quick-chat attachments live in memory (`ChatViewModel` Quick mode does not call `stageDraftAttachmentIfNeeded`, gated by `identity !is ChatIdentity.Draft` at `ChatViewModel.kt:610` and `:654`). Persistent-chat attachments go exclusively to `filesDir/attachments/{chatId}/` via `savePersistentAttachment` (`DefaultChatRepository.kt:223-245`). Verified by inspection of all `payloadWriter` / `writeBytes` / `outputStream` call-sites in the Phase-3 tree.

### 4. PII in logs — PASS (with one Info)

- Audited every `errorLog.e` callsite added or touched in Phase 3. No call embeds message text, user prompt, image/audio content, or chat payload.
- The `description` arguments use safe identifiers: chatIds (integer), modelIds (HF repo string — public, stable), storage filenames (`img_{uuid}.png` / `audio_{uuid}.wav`), attachment-ids (integer), absolute file paths under app-private storage, orphan DataStore keys (bounded by the Phase-1/2 allowlist — `Model.name`, a code-controlled storage filename, not user text).
- `ErrorLog.e` sanitises (`[\n\r\t]` → space, `ErrorLog.kt:21`, `:109-110`) and truncates description / cause message to 500 / 200 chars (`:14-15`, `:102-106`). Bounds are enforced.
- `SettingsMigrationHelper.kt:59-62` logs the orphan old-key; this key is `Model.name` from the Phase-1/2 allowlist universe (e.g., `gemma-4-E2B-it-litert-lm.task`), not user-entered.
- `ChatViewModel.kt:1420-1460` logs attachment paths on read failure. Paths are derived from `filesDir/attachments/{chatId}/{uuid-filename}` — no chat content.
- **F2 (Info).** `ChatViewModel.kt:1192` and `:1275` forward the engine's `onError` string as-is (`errorLog.e("inference", safeMsg)`). The string source is the native LiteRT-LM error callback, which typically emits short mechanical strings ("context window exceeded", "init failed on CPU"), but the caller does not cap the payload; it leans on `ErrorLog`'s 500-char truncation. Harden by slicing at the callsite (`safeMsg.take(200)`) or by defining an `ErrorLog.i` convention for non-exceptional inference telemetry.

### 5. Attachment decode security — PASS

- Image decode (`ChatViewModel.kt:1417-1424`, `:1439-1454`) is wrapped in `runCatching`; `BitmapFactory.decodeFile` returning `null` is handled (`:1447-1452`) and surfaced as "attachment unavailable" placeholder; decode exceptions are logged under `attachment-read`.
- Audio decode (`ChatViewModel.kt:1427-1434`, `:1456-1466`) follows the same `runCatching` + placeholder pattern. `wavToPcm` failures propagate to the caller's `runCatching` and are logged.
- `resolveInsideAttachmentsRoot` (`ChatViewModel.kt:1474-1483`) canonicalises the path and throws `SecurityException` if the resolved file is not a descendant of `filesDir/attachments/`. This blocks a poisoned Room row from pointing at an arbitrary app-private file (e.g., `logs/errors.log` or `datastore/settings.pb`).
- All reads come from `filesDir` — no external `Uri` dereferencing at persistent-decode time.

### 6. Cross-process crash state — PASS

- `AppCorruptionState` (`AppCorruptionState.kt:17-23`) is a Hilt `@Singleton` with a single `@Volatile Boolean` field. No persistence layer. No IPC surface (no `@Parcelize`, no Intent bundling, no Binder exposure).
- `AndroidManifest.xml` (full file read) declares zero `<receiver>`, `<provider>`, and only two `<activity>` entries: `MainActivity` (exported LAUNCHER, no sensitive args) and `.crash.CrashReportActivity` (`exported="false"`, `android:process=":crash"`). The lone `<service>` is `androidx.work.impl.foreground.SystemForegroundService` with `exported="false"`. `AppCorruptionState` is unreachable from other apps.
- Corrupt DB backup (`AppModule.kt:61, :70-81`): `context.getDatabasePath(SanctumDatabase.DATABASE_NAME)` resolves to `/data/data/app.sanctum.machina/databases/sanctum.db`. The rename target is `parent/sanctum.db.corrupt_{ts}` — same directory, inheriting the app-private (0700) permissions created by the OS for the databases dir. No `MODE_WORLD_READABLE`, no `openFileOutput` with weak mode. Sidecar journal/WAL/SHM files are explicitly deleted to prevent a stale `-wal` resurrecting corrupt state on the fresh build (`AppModule.kt:77-79`, `:85-88`).
- There is no code path that reads `corruptionOccurred` outside the app process or exposes it over an Intent.

### 7. DataStore migration atomicity — PASS (+ Low F1)

- Sentinel gate at `SettingsMigrationHelper.kt:40`: `if (appSettings.isSettingsMigrated()) return` runs before any work.
- The rekey, orphan-key drop, and sentinel flip all live inside a single `dataStore.updateData { ... }` block (`:53-72`). DataStore's atomicity contract guarantees a crash mid-transform leaves either the pre-image or the post-image; there is no manual partial-merge step.
- Orphan keys are logged with the safe `oldKey` (`Model.name`, code-controlled — see §4) before being dropped (`:59-63`).
- **F1 (Low).** `isSettingsMigrated()` returns `false` on any `IOException` (`DefaultAppSettingsRepository.kt:116-122`). If a transient read error occurs on the sentinel check but the subsequent `updateData` succeeds, migration will re-run against already-migrated modelId-keyed data. Because `nameToModelId[modelId]` is always `null` (the map is keyed by `name`), every existing override is treated as an orphan and dropped, silently clearing the user's per-model settings. **Fix:** inside the `updateData` transform, re-check `current.settingsKeysMigrated` and early-return the unchanged builder if already migrated — makes the migration block itself idempotent and immune to sentinel-read failure.
  - Alternatively: mark the outer `isSettingsMigrated()` to propagate `IOException` (or return `true` on `IOException` to fail-safe — existing data is preserved, migration simply doesn't run this boot).

## OWASP Mobile Top 10 sweep

- **M1 Improper Credential Usage — N/A.** No credentials stored. PhoneWrap is a public-model on-device LLM app; no HF token flow exists in the Phase-3 codebase (grep `hf[_-]?token|HF_TOKEN|accessToken` returned no source matches). The tech-spec's "HF token stored via androidx.security.crypto" question does not apply to this phase.
- **M2 Inadequate Supply Chain Security — out of scope for this audit** (dependency pinning, reproducible builds handled in CI task, not Phase-3 surface).
- **M3 Insecure Authentication/Authorization — N/A** (no multi-user model, no remote service).
- **M4 Insufficient Input/Output Validation — PASS.** See §1 (path containment), §5 (decode hardening), §2 (parameterised SQL). Compose `Text(row.title)` in `DrawerContent.kt:373` renders chat titles as plain text — no markdown/HTML interpretation, no XSS analogue.
- **M5 Insecure Communication — PASS.** `AndroidManifest.xml:25` sets `android:usesCleartextTraffic="false"`. No HTTP clients introduced in Phase 3. No new network surface.
- **M6 Inadequate Privacy Controls — PASS.** Quick-chat purge (§3), manifest `allowBackup="false"` + `dataExtractionRules` (see regression check), DataStore files under `filesDir`. `DeviceInfoCollector` exports only model ids (HF public strings), device memory, Android version — no chat content, no PII.
- **M7 Insufficient Binary Protections — not audited** (out of scope; ProGuard/R8 rules not Phase-3 concern).
- **M8 Security Misconfiguration — PASS.**
  - Only one component is `exported="true"` (MainActivity, required for LAUNCHER intent).
  - `CrashReportActivity` is `exported="false"` + `taskAffinity=""` + `:crash` process — properly isolated.
  - `SystemForegroundService` is `exported="false"`.
  - No `android:debuggable` overrides found.
- **M9 Insecure Data Storage — PASS.** All writes routed through `filesDir` (`ChatRepository.*` uses `context.filesDir`, `ErrorLog` writes to `filesDir/logs/`, DataStore `app_settings.pb` is in app-private storage by default, Room DB under `/data/data/app/databases/`). No `MODE_WORLD_READABLE` / `MODE_WORLD_WRITEABLE`, no external-storage writes. `allowBackup=false` prevents adb backup extraction.
- **M10 Insufficient Cryptography — N/A** (no secrets, no encryption in scope for Phase 3).

## SafeMarkdown regression check — PASS

- Grep `RichText|Markdown\(` across `app/src/main/kotlin/.../ui/` returns matches only inside `SafeMarkdown.kt` (the sanitised wrapper) plus one consumer each at `MessageBubble.kt:91`, `ThinkingBlock.kt:112`, and `AboutScreen.kt:160` — all call `SafeMarkdown(text = …)`, not the raw `RichText` / `Markdown` composables.
- New Phase-3 UI surfaces (`HomeScreen.kt`, `DrawerContent.kt`) render text via `Text(...)` only. Chat titles (`row.title`, `DrawerContent.kt:373`), chat subtitles, and home-hub labels are string-resource or plain-string renders — no markdown interpretation. No `SafeMarkdown` or `RichText` import in either file — confirmed safe.

## Privacy-manifest regression — PASS

From `app/src/main/AndroidManifest.xml` (head read):

- `android:allowBackup="false"` (line 23) — unchanged from Phase 2.
- `android:dataExtractionRules="@xml/data_extraction_rules"` (line 24) — unchanged.
- `android:usesCleartextTraffic="false"` (line 25) — unchanged.

No regression. No newly-exported components. No new permissions beyond Phase 2 (`INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `CAMERA`, `RECORD_AUDIO` — all pre-existing).

## Recommendations

1. **F1 (Low) — harden `SettingsMigrationHelper` against sentinel-read failure.** Fold the sentinel check *inside* the `updateData` transform, so the migration block early-returns when `current.settingsKeysMigrated` is already `true`. Single-line diff; removes the theoretical data-loss path after transient DataStore IO errors.

2. **F2 (Info) — cap `inference` onError description at the callsite.** `ChatViewModel.kt:1192, :1275`: replace `errorLog.e("inference", safeMsg)` with `errorLog.e("inference", safeMsg.take(200))`. Belt-and-braces over `ErrorLog`'s existing 500-char truncation; makes PII-exposure invariant a local property of each caller rather than a global property of the logger.

3. **No action required** on focus areas 1, 2, 3, 5, 6, 7 — implementations meet or exceed the tech-spec's stated security invariants.

## Coverage statement

All files listed in task 14's "Full file list to audit" section were read or grep-inspected. High-risk files were line-by-line audited: `DefaultChatRepository.kt`, `ChatDao.kt`, `MessageDao.kt`, `SanctumApplication.kt`, `SanctumDatabase.kt`, `StartupHousekeeper.kt`, `SettingsMigrationHelper.kt`, `AppCorruptionState.kt`, `AppModule.kt` (DI + corruption handler), `WarmupCoordinator.kt`, `ChatViewModel.kt` (staging + decode + error-log surfaces), `DefaultAppSettingsRepository.kt`, `ErrorLog.kt` (whitelist + bounds), `DeviceInfoCollector.kt`, `AndroidManifest.xml`. UI tree grep-audited for `RichText|Markdown\(` usage. Data-layer grep-audited for `rawQuery|execSQL|RawQuery` and `Log\.i|Log\.d|Log\.w|Log\.v`.
