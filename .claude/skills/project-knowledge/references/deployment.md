# Deployment & Operations

## Purpose
Deployment process, infrastructure, and production operations for AI agents.

---

## Deployment Platform

**Platform:** Android device (direct APK installation). No Google Play, no F-Droid, no third-party store.

**Type:** Native Android application (`.apk`).

**Why this platform:** Sanctum Machina is an on-device tool. The "deploy target" is the user's phone itself — there is no server component to deploy. The APK is built locally and transferred to the device as a file. This is intentional and aligned with the manifesto: no store means no store policy constraints, no account requirements for users, no telemetry injection during distribution.

---

## Access Information

**Device access (primary test device):** Honor 200, Android 14+, 12GB RAM, NPU present. Transfer mechanism for APK delivery:
- Cable: `adb install -r app-debug.apk` from developer machine
- Wireless: Android Studio wireless debugging, or file transfer via any sync tool the user already uses (Telegram saved messages, `scp` to device, etc.)

**No server access.** There is no server.

**Credentials location:** N/A — no deployment credentials exist. The only credentials involved anywhere in the project are the HuggingFace OAuth token, and that lives only on the end-user device in encrypted DataStore.

---

## Environment Variables

**No environment variables** are consumed by the application at runtime. All configuration is:
- Compile-time, in `gradle/libs.versions.toml` and `build.gradle.kts` (library versions, build flags).
- Runtime, in-app via Proto DataStore (user preferences, theme, selected model) and Room (projects, chats, messages).

No `.env` file exists in this project. `.env` is in `.gitignore` preemptively in case one is added during development for local tool integration, but no `.env.example` is shipped.

---

## Deployment Triggers

**Phase 1-4 (development / private testing):** Manual. Developer machine runs `./gradlew :app:assembleDebug`, the resulting APK in `app/build/outputs/apk/debug/app-debug.apk` is transferred to the test device and installed. No CI, no automation — a solo developer working locally.

**Phase 5 (first release):** GitHub Releases. After the repo goes public, releases are published manually as GitHub Releases with an attached signed APK. A release keystore is generated once at the start of Phase 5 and kept out of git (see "Release Keystore" below). Tagging the commit as `v1.0-release` (see `patterns.md`) is the release trigger.

**No staging environment.** The developer's phone is the testbed. If Phase 5 introduces a pre-release track, this document will be updated.

---

## Pre-Deploy Checklist

For Phase 1-4 (debug installs to test device), pre-push gate (see `patterns.md`) already runs `:core-runtime` unit tests. Additional checks only when they add value:

- [ ] Room DAO instrumentation tests on the test device, if schema changed: `./gradlew :app:connectedAndroidTest`.
- [ ] If the signing key changed since the previous install, uninstall the previous APK first.

For Phase 5 (first release):

- [ ] All of the above.
- [ ] Manual full smoke test: clean install, download an E2B model, quick chat, multimodal chat with a photo, history survives app restart, project creation.
- [ ] Release APK signed with the release keystore (see "Release Keystore" below).
- [ ] Git tag `v1.0-release` pushed.
- [ ] GitHub Release created with the APK attached, release notes, and attribution to Google AI Edge Gallery + Gemma + LiteRT-LM + dependencies.

---

## Rollback Procedure

**Device rollback:** Uninstall current APK, install a previous APK from disk (developer retains prior builds during a phase).

**Repository rollback:** See `patterns.md § Git Workflow` for branch and tag structure. Typical commands: `git checkout main && git branch -D phase/N-name` (undo in-progress phase), `git checkout v0.X-name` (revert to a prior released phase), `git checkout master` (nuclear reset to the initial commit).

**Schema downgrade caveat:** Room schema versions are strictly monotonic; **never re-release with a lower schema version**. Crossing a schema boundary on downgrade forces the user to clear app data.

**Approximate time:** ~1 minute for device rollback, ~5 seconds for git rollback.

---

## Environments

**Production:** Developer's Honor 200 (see Access Information).

**Staging:** None.

**Preview:** None.

---

## Monitoring & Observability

### Logging

Format, levels, and component taxonomy are defined in `patterns.md § Project-Specific Code Patterns / Error logging conventions`. Operational details only:

- **Location:** `context.filesDir/logs/errors.log`; rotated copy `errors.log.1`.
- **Access:** Never uploaded. Exportable via in-app "Export log" action that invokes the system share sheet; entirely user-initiated.

### Error Tracking

None. See `project.md § Out of Scope`. The on-device log is the only record.

### Health Checks

No server, no health endpoint. At app start `SanctumApplication.onCreate` runs two sanity checks: (a) Room database opens, (b) last-used model's file still exists at its stored path; if deleted externally, mark it not-downloaded in `models_meta`. Failures log at ERROR level; the app continues running.

### Metrics

None.

### Alerts

None.

---

## Release Keystore (Phase 5 only)

Not needed for Phase 1-4 (debug keystore auto-generated by Android Studio at `~/.android/debug.keystore` is sufficient while uninstalling-and-reinstalling between developer builds is acceptable).

For Phase 5 first public release:
- Generated once with `keytool -genkey -v -keystore sanctum-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias sanctum`.
- Kept in `C:\AI-WORK\PhoneWrap\release-keys\` (outside git; this path is in `.gitignore`).
- Backed up to at least one external medium (encrypted USB stick or cold storage). **Losing this key means losing the ability to ship updates that install over the existing Sanctum Machina on any user's device** — Android requires signing continuity.
- Keystore password and key password stored in a password manager, not in git, not in any project file.
