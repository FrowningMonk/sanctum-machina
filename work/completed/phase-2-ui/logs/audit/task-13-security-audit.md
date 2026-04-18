# Phase 2 Security Audit — Task 13

**Auditor:** security-auditor
**Date:** 2026-04-18
**Standards:** OWASP Top 10 (mobile) + OWASP MASVS v2
**Phase-2 HEAD:** 9b435f2

## Executive Summary

The Phase 2 codebase was audited against the eight scoped areas plus the mandatory OWASP/MASVS sweep. Result: **0 critical, 0 high, 2 medium (both non-blocking), 3 low**. No findings block the merge to `main`. Phase 2 maintains the "no secrets, no server auth, no external auth token" posture established in Phase 1: zero hardcoded credentials, zero auth headers anywhere in `:app`, `:core-runtime`, or `:core-settings` main sources. The permission model is strictly on-demand (per-sheet launchers), privacy hardening in the manifest is intact (`allowBackup=false` + explicit `data_extraction_rules.xml` exclude-root), and `SafeUriHandler` correctly enforces an `http`/`https`-only allow-list verified against 13 test cases covering every dangerous scheme from the task brief.

Two medium-severity items are carried forward as deferred hardening (known gaps, documented in Phase-1 and Phase-2 decisions, no new risk introduced by Phase 2). Three low-severity items are stylistic/defensive improvements for Phase 3 or later.

## Findings by Area

### 1. Permission model

**Status:** PASS.

**Evidence:**
- `app/src/main/AndroidManifest.xml:5–12` — eight `<uses-permission>` entries; the dangerous pair is `CAMERA` (line 11) and `RECORD_AUDIO` (line 12). `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`, and `WRITE_EXTERNAL_STORAGE` are absent (grep verified — zero hits on the three patterns across all manifests).
- `CameraBottomSheet.kt:141–145` — `LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }` fires **only on the first composition of the Camera sheet**, which itself is only mounted when the user taps the camera icon in `MultimodalInputBar`. There is no `checkSelfPermission` or `requestPermissions` call in `SanctumApplication` or `MainActivity` (grep verified).
- `AudioRecorderBottomSheet.kt:145–149` — symmetric on-demand pattern for `RECORD_AUDIO`; only requested when the Audio sheet first composes.
- `MultimodalInputBar.kt:114–126` — Photo Picker is invoked via `ActivityResultContracts.PickVisualMedia` which requires **no permission** on API 31+, matching tech-spec D10 and user-spec R6.
- Both permission launchers use `ActivityResultContracts.RequestPermission()`, not manual `ActivityCompat.requestPermissions(...)`, so the Android system manages the prompt flow including "don't ask again" semantics. `isCameraDenialPermanent` / `isAudioDenialPermanent` are pure helpers that treat a null Activity + cleared rationale as permanent — the pragmatic read post-prompt.

**Findings:** None.

### 2. Privacy hardening

**Status:** PASS.

**Evidence:**
- `app/src/main/AndroidManifest.xml:23–25`:
  - `android:allowBackup="false"` (line 23)
  - `android:dataExtractionRules="@xml/data_extraction_rules"` (line 24)
  - `android:usesCleartextTraffic="false"` (line 25) — **bonus hardening not in task brief**; defense-in-depth for the HF download channel.
- `app/src/main/res/xml/data_extraction_rules.xml:3–8` — both `<cloud-backup>` and `<device-transfer>` contain `<exclude domain="root" path="."/>` per TAC-14. `.` path form is intentional (tech-spec D26 requires the literal form for lint/regex verification).
- `app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml:35–46` — merged manifest from the build output confirms the library-module manifests (`core-runtime`, `core-settings`) did NOT undo these attributes during merge. `android:fullBackupContent` is absent (intentionally removed in Task 5 round-1 as redundant on minSdk=31).
- `core-settings/src/main/AndroidManifest.xml` is self-closing (no `<application>` block), which is the correct library-manifest pattern for not overriding app-level flags.

**Findings:** None.

### 3. Attachment OOM vectors

**Status:** PASS.

**Evidence:**
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MediaUtils.kt:28–50` — `decodeSampledBitmapFromUri` performs the two-pass `BitmapFactory.Options.inSampleSize` dance. First pass (`inJustDecodeBounds=true`) at lines 34–36 populates `outWidth/outHeight` without allocating pixel memory; second pass at lines 48–49 honours the computed sample size. `calculateInSampleSize` at lines 84–89 is the pure rounding variant.
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Consts.kt:50` — `MAX_IMAGE_COUNT = 10`; `MAX_AUDIO_CLIP_DURATION_SEC = 30` (line 62); `SAMPLE_RATE = 16000` (line 65). Paired with `CHANNEL_IN_MONO` + `ENCODING_PCM_16BIT` in `AudioRecorderBottomSheet.kt:66–67`, the audio cap is **30 × 16000 × 2 = 960 KB** per clip — within the "hundreds of KB" budget the task anchors on.
- `app/src/main/kotlin/app/sanctum/machina/ui/chat/ImageDecoder.kt:30–38` — `TARGET_EDGE = 1024`; decoding runs inside `withContext(Dispatchers.IO)`.
- `ChatViewModel.addImages` (ChatViewModel.kt:431–471) clips inside the `_attachments.update { }` block (TOCTOU-safe) and emits `attachment_max_images_reached` snackbar on overflow.
- `ChatViewModel.addImageBitmap` (ChatViewModel.kt:479–486) defensively re-downscales to 1024 even for CameraX captures (R5).
- **PNG compression runs off-Main.** `LlmChatModelHelper.kt:284,297` — `MultimodalContentsBuilder.build(...)` is dispatched via `coroutineScope.launch(Dispatchers.Default) { dispatchPrep() }`. This is the Task-7 perf fix that closed the 5.9-sec / 355-frame freeze; the invariant has not regressed in subsequent commits.
- `AudioRecorderBottomSheet.kt:187–228` — recording runs on `Dispatchers.IO`, the `ByteArrayOutputStream` has a single writer, `DisposableEffect.onDispose` releases the native `AudioRecord`.

**Findings:** None.

### 4. Content-URI handling

**Status:** PASS (with one LOW defensive note, see 4.1 below).

**Evidence:**
- The attachment happy-path (`Photo Picker → DefaultImageDecoder.decode → decodeSampledBitmapFromUri → openStream`) produces content URIs only. Content URIs are opaque authority + id handles; they cannot be crafted to traverse filesystem paths because the `ContentResolver` resolves authority to the provider and the id is opaque to the caller.
- `MediaUtils.kt:177–188` — `openStream` uses `contentResolver.openInputStream(uri)` for the content-scheme branch (line 182). The result is treated as a nullable `InputStream`; `IOException` and `SecurityException` are both caught and collapsed to `null`. No `File(uri.path)` for content URIs.

**#4.1 — LOW (defensive, non-blocking).** `MediaUtils.openStream` at `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MediaUtils.kt:179–180` DOES support a `file://` / null-scheme branch: `if (uri.scheme == null || uri.scheme == "file") uri.path?.let { FileInputStream(it) }`. In Phase 2 the only production callers (`DefaultImageDecoder` driven by Photo Picker, `ChatViewModel.addImageBitmap` driven by CameraX) cannot produce a `file://` or null-scheme URI, so this branch is **not reachable from user input today**. However, any future caller that forwards an externally-supplied URI into `decodeSampledBitmapFromUri` without scheme gating would hand an attacker a direct `FileInputStream(uri.path)` on the application's process — and Android's default `StrictMode` will not stop it. **OWASP MASVS v2 MSTG-STORAGE-2 / MSTG-PLATFORM-3.** Recommendation: either (a) drop the `file://` branch when porting to Phase 3 (no known caller needs it), or (b) require the caller to declare `allowFileScheme: Boolean = false` and default it off, as a tripwire.

### 5. DataStore permissions

**Status:** PASS.

**Evidence:**
- `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/di/CoreSettingsModule.kt:19,40` — `DATASTORE_FILE = "datastore/app_settings.pb"`, `produceFile = { File(context.filesDir, DATASTORE_FILE) }`. `context.filesDir` is the internal app-private directory (`/data/data/<pkg>/files`, mode 0700 by default on Android since the framework does the chown/chmod for us).
- Grep across the codebase: zero hits on `MODE_WORLD_READABLE`, `MODE_WORLD_WRITEABLE`, or `MODE_APPEND`. `getExternalFilesDir` is used only in `:core-runtime` for **model downloads** (not settings), consistent with Phase-1 design; Phase 2 did not introduce any new external-storage usage.
- `data_extraction_rules.xml` excludes `domain="root"` for both cloud-backup and device-transfer, so the DataStore file is NOT uploaded to Google Drive even if a future phase re-enables auto-backup.

**Findings:** None.

### 6. Intent handlers

**Status:** PASS.

**Evidence:**
- Phase-2 code issues exactly **three** outgoing Intents:
  1. `ChatScreen.kt:340–346` — `ACTION_APPLICATION_DETAILS_SETTINGS` wrapped in `runCatching` (permanent-denial → "Open settings" snackbar flow).
  2. `SafeMarkdown.kt:38–41` — `ACTION_VIEW` for allow-listed `http`/`https` schemes only (see §7).
  3. `DownloadRepository.kt:238` / `DownloadWorker.kt:330` — internal `Intent(context, activityClass)` to launch `MainActivity` as the notification content intent (Phase-1 code; not exported, no extras).
- `app/src/main/AndroidManifest.xml:38–51` — the only `exported="true"` component is `MainActivity` (LAUNCHER category, line 40). `WorkManager`'s `SystemForegroundService` is `exported="false"` (line 50).
- Merged-manifest review (app/build/intermediates/merged_manifests/.../AndroidManifest.xml) — the only additional `exported="true"` components come from AndroidX internals (`SystemJobService`, `DiagnosticsReceiver`, `ProfileInstallReceiver`) and each is protected by a system-signature permission (`BIND_JOB_SERVICE`, `android.permission.DUMP`). No Phase-2 code added a new exported activity/service/receiver/provider.

**Findings:** None.

### 7. SafeUriHandler scheme coverage

**Status:** PASS.

**Implementation:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/SafeMarkdown.kt:30–43`.

**Allow-list (enumerated):**
```
ALLOWED_SCHEMES = setOf("http", "https")
```
Scheme is normalized via `parsed.scheme?.lowercase()` (RFC 3986 §3.1 — case-insensitive), and `if (scheme !in ALLOWED_SCHEMES) return` silently drops everything else. The subsequent `startActivity` is wrapped in `runCatching`, so a handler-missing `ActivityNotFoundException` for http/https doesn't crash the app either.

**Dangerous-scheme verification matrix:**

| Scheme                | Asserted blocked in test                       | Status        |
|-----------------------|------------------------------------------------|---------------|
| `intent://`           | `SafeUriHandlerTest.intent_blocked`            | **COVERED**   |
| `javascript:`         | `SafeUriHandlerTest.javascript_blocked`        | **COVERED**   |
| `file://`             | `SafeUriHandlerTest.file_blocked` (uses `/etc/passwd`) | **COVERED** |
| `content://`          | `SafeUriHandlerTest.content_blocked`           | **COVERED**   |
| `sms:`                | `SafeUriHandlerTest.sms_blocked`               | **COVERED**   |
| `tel:`                | `SafeUriHandlerTest.tel_blocked`               | **COVERED**   |
| `market:`             | `SafeUriHandlerTest.market_blocked`            | **COVERED**   |
| `data:text/html,...`  | `SafeUriHandlerTest.data_blocked`              | **COVERED**   |
| `mailto:`             | NOT directly tested (blocked by allow-list semantics) | **GAP**    |
| custom deeplinks (e.g. `myapp://`) | NOT directly tested (blocked by allow-list semantics) | **GAP** |
| Empty string          | `SafeUriHandlerTest.empty_blocked`             | **COVERED**   |
| Malformed             | `SafeUriHandlerTest.malformed_blocked`         | **COVERED**   |
| HTTP/HTTPS uppercase  | `SafeUriHandlerTest.{http_uppercase_allowed, https_mixedcase_allowed}` | **COVERED** |

Total tests: 13 — all PASS (see Task 6 decisions.md verification block).

**Sub-findings:**

**#7.1 — LOW (test coverage completeness, non-blocking).** The allow-list is additive/whitelist, so `mailto:` and arbitrary custom deeplink schemes (e.g. `myapp://`, `fb://`, `whatsapp://`) are already blocked by the `scheme !in ALLOWED_SCHEMES` check. However, the test suite does not have a dedicated `mailto_blocked` or `custom_deeplink_blocked` assertion, so regressions from a future "let's also allow X" change would not be caught. Recommendation: add two one-liner tests mirroring `sms_blocked` — `handler.openUri("mailto:x@y.com")` and `handler.openUri("myapp://deeplink")`. Zero code changes needed in production.

### 8. Hardcoded secrets scan

**Status:** PASS.

**Patterns scanned** (case-insensitive across `**/*.{kt,kts,java,properties,xml,gradle,yml,yaml,json,md}`):
- `api[_-]?key`, `apikey`, `secret[_-]?key`, `auth[_-]?token`, `password\s*=\s*"..."`, `bearer\s`, `authorization:\s*...`
- `BEGIN (RSA |EC )?PRIVATE KEY`
- `AIza[0-9A-Za-z_-]{35}` (Google API key pattern)
- `hf_[a-zA-Z0-9]{20,}` (Hugging Face token pattern)
- `AKIA[0-9A-Z]{16}` (AWS access key pattern)
- `ghp_[A-Za-z0-9]{30,}` (GitHub PAT pattern)
- `sk_live_`, `sk_test_` (Stripe key patterns)
- `hfToken`, `HF_TOKEN`, `accessToken`, `bearerToken`, `refreshToken`, `huggingface.*token`

**Hits reviewed:**
- `core-runtime/src/test/kotlin/.../AllowlistLoaderTest.kt:48` — `val firstToken = cfg.accelerators!!.split(",")[0].trim().lowercase()` — identifier name `firstToken` is a local variable in a test; no credential.
- `app/src/test/kotlin/.../SafeUriHandlerTest.kt:80` — string literal `file:///etc/passwd` used as an input to assert SafeUriHandler blocks it. Not a credential.
- `core-runtime/src/main/kotlin/.../Consts.kt:42`, `ModelAllowlist.kt:68,89` — `DEFAULT_MAX_TOKEN = 1024`, `defaultMaxToken = maxToken`, `llmMaxToken = maxToken`. "Token" in the LLM-inference sense (output budget), not an auth artifact.
- `huggingface.co` references in `AllowlistLoader.kt:17` and `ModelAllowlist.kt:50–51` — **public unauthenticated** download URLs; no `Authorization` header is attached at any call site (grep on `Authorization|Bearer ` in `:app`/`:core-runtime`/`:core-settings` main sources returns zero hits).

**Gitignore coverage** (`.gitignore` at repo root): `.env`, `.env.*`, `*.keystore`, `*.jks`, `*.p12`, `*.pfx`, `*.key`, `*.pem`, `credentials.json`, `secrets/`, `release-keys/`, `keystore.properties`, `signing.properties`, `local.properties`. `git ls-files | grep` for these patterns returns zero matches — no secret-bearing file has ever been committed.

**`local.properties` inspection** (gitignored, inspected locally): contains only `sdk.dir=...` — no tokens, no API keys.

**Findings:** None.

## OWASP Top 10 (mobile) + MASVS v2 sweep (carry-over verdicts)

| # | Category | Phase-2 verdict |
|---|---|---|
| M1 / MSTG-ARCH | Improper Platform Usage | PASS — minSdk=31, scoped storage, Photo Picker, no legacy permission patterns. |
| M2 / MSTG-STORAGE | Insecure Data Storage | PASS — DataStore in `filesDir` (0700), backup excluded. See §4.1 for a future tripwire. |
| M3 / MSTG-NETWORK | Insecure Communication | PASS — `usesCleartextTraffic="false"`, only HTTPS outbound to huggingface.co. |
| M4 / MSTG-AUTH | Insecure Authentication | N/A — single-user on-device app, no server auth. |
| M5 / MSTG-CRYPTO | Insufficient Cryptography | N/A — no cryptographic operations in Phase-2 code paths. |
| M6 / MSTG-AUTH-13 | Insecure Authorization | N/A — no authorization boundary (see M4). |
| M7 / MSTG-CODE | Client Code Quality | PASS — no deserialization from untrusted input; `Uri.parse` + allow-list; bitmap decode hardened. |
| M8 / MSTG-RESILIENCE | Code Tampering | Out of scope for Phase 2 (anti-tamper reserved for later phase; not required by user-spec). |
| M9 / MSTG-REVERSE | Reverse Engineering | Out of scope (minified release build flags not part of Phase 2). |
| M10 / MSTG-PLATFORM | Extraneous Functionality | PASS — no debug endpoints, no hidden commands; `BuildConfig.DEBUG` only gates logcat verbosity. |

## Blocking findings

**None.**

## Non-blocking recommendations

| ID | Severity | Summary | Where |
|----|----------|---------|-------|
| #4.1 | LOW | `MediaUtils.openStream` still has a `file://` / null-scheme → `FileInputStream(uri.path)` branch. Not reachable from today's callers, but a tripwire against future misuse is worth ~5 lines. | `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MediaUtils.kt:179–180` |
| #7.1 | LOW | Add explicit `mailto_blocked` and `custom_deeplink_blocked` tests to `SafeUriHandlerTest` — guards against a future well-intentioned "let's allow mailto" commit. | `app/src/test/kotlin/app/sanctum/machina/ui/chat/SafeUriHandlerTest.kt` |
| Carry-over from Phase 1 / Task 5 | MEDIUM (deferred) | `INTERNET` permission is declared broadly. NetworkSecurityConfig that pins `huggingface.co` (the sole outbound host today) would narrow the blast radius of a supply-chain compromise. Already flagged by Task-5 security-auditor; documented deferral. | `AndroidManifest.xml` + `res/xml/network_security_config.xml` (new) |
| Carry-over from Phase 1 / Task 5 | MEDIUM (deferred) | Gradle dependency verification (`gradle/verification-metadata.xml`) is not configured. Adding pins for `compose-richtext 1.0.0-alpha02` and other alpha/beta dependencies would harden the build supply chain. Already flagged by Task-5 security-auditor. | `gradle/verification-metadata.xml` (new) |
| Carry-over from Task 10 | LOW (deferred) | No hard cap on `systemPromptDefault` string length when it flows from `AllowedModelConfig.defaultConfig` into `Model.configValues`. Task 11 added a 4096-char clamp at the Apply site (security-auditor minor-2, fixed). No regression, but equivalent clamp at load time would be defence-in-depth for Phase 3 when user-provided overrides land. | `core-runtime/.../AllowlistLoader.kt`, `ChatViewModel.applyOverrides` |

## Smoke verification

**Manifest grep (decisive):**

- `allowBackup="false"` → hit at `app/src/main/AndroidManifest.xml:23`.
- `dataExtractionRules="@xml/data_extraction_rules"` → hit at `app/src/main/AndroidManifest.xml:24`.
- `usesCleartextTraffic="false"` → hit at `app/src/main/AndroidManifest.xml:25` (bonus).
- Merged manifest (`app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml:37,39,46`) preserves all three attributes.
- Grep on `READ_MEDIA_IMAGES|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE` across all `AndroidManifest.xml` files → **zero hits** in `:app` and library manifests (matches in `work/**` are documentation only).
- `data_extraction_rules.xml` grep on `exclude domain="root" path="."` → hit at lines 4 and 7 (both `<cloud-backup>` and `<device-transfer>`).

**SafeUriHandler allow-list enumeration:**

```kotlin
// app/src/main/kotlin/app/sanctum/machina/ui/chat/SafeMarkdown.kt:30
private val ALLOWED_SCHEMES = setOf("http", "https")
```

Blocked (by the whitelist's default-deny semantics): `intent`, `javascript`, `file`, `content`, `sms`, `tel`, `market`, `data`, `mailto`, and any custom deeplink scheme. Case-insensitive scheme-matching per RFC 3986 §3.1 (`parsed.scheme?.lowercase()`).

Test coverage: 13 `SafeUriHandlerTest` cases, all pass (see Task 6 decisions.md).

**Secrets grep output (summary):**

```
# Pattern 1: api_key|apikey|secret|auth_token|password=|bearer|authorization
#   → 3 hits: firstToken (test local var), /etc/passwd (test input string),
#     *MAX_TOKEN / maxToken (LLM output-budget constants). 0 real credentials.

# Pattern 2: AIza[0-9A-Za-z_-]{35}
#   → 0 hits.

# Pattern 3: BEGIN (RSA|EC)? PRIVATE KEY
#   → 0 hits.

# Pattern 4: hf_[a-zA-Z0-9]{20,} | AKIA[0-9A-Z]{16} | ghp_[A-Za-z0-9]{30,} | sk_live_ | sk_test_
#   → 0 hits in production source; only hits are in skill-template docs (AKIA1234567890EXAMPLE as a sample).

# Pattern 5: huggingface.co | Authorization | Bearer
#   → huggingface.co present as unauthenticated download URL (AllowlistLoader.kt:17,
#     ModelAllowlist.kt:50–51). Zero Authorization/Bearer hits in :app/:core-runtime/:core-settings
#     main sources.
```

**Git-tracked secret-bearing files check:**

```
git ls-files | grep -i -E "(local.properties|secrets|credentials|keystore|\.key$|\.pem$)"
  → 0 hits.
```

**`local.properties` content (local, gitignored):**

```
sdk.dir=C\:\\Users\\Vladimir\\AppData\\Local\\Android\\Sdk
```

No secrets.

---

**End of audit.**
