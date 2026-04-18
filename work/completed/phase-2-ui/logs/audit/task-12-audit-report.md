# Phase 2 Code Audit — Task 12

**Auditor:** code-reviewer
**Date:** 2026-04-18
**Scope:** Phase 2 Tasks 1–11, all Kotlin/Compose/XML files
**Phase-2 HEAD:** 9b435f2
**Phase-1 baseline:** 31bd1d0

## Executive Summary

The Phase 2 code base cleanly meets the seven axes of the Task 12 audit. Module boundary
(TAC-7, TAC-8), lifecycle hygiene for CameraX and AudioRecord, markdown link-scheme
safety, shared-resource ownership, and the autoscroll performance gate are all in place
with explicit test or grep coverage. Two non-blocking observations (a `systemPromptDefault`
persistence-key deviation already documented in `decisions.md`, and a minor type-safety
nit in `classifyApplyLevel`) are Phase-3 follow-ups. No blocking findings; verdict
**approved** for Phase-2 closure.

Findings by severity: **critical 0 / high 0 / medium 0 / low 2**.

## Findings by Axis

### 1. Module boundary (TAC-7, TAC-8)

**Status:** pass.

**Evidence:** All four smoke greps return empty for `:core-runtime` and `:core-settings`:

- `import androidx.compose` — 0 matches in both modules.
- `import android.app.Activity` — 0 matches in both modules.
- `import androidx.activity` — 0 matches in both modules.
- `class … : ViewModel(` / `extends Activity` — 0 matches in both modules.

`core-runtime/build.gradle.kts` and `core-settings/build.gradle.kts` carry no
`androidx.compose` / `androidx.activity` / `androidx.lifecycle.viewmodel` dependencies.
`core-settings` manifest at `core-settings\src\main\AndroidManifest.xml:1` is a self-closing
`<manifest>` with no `<application>` attributes — preserves `:app`'s `allowBackup`/
`dataExtractionRules` under manifest merge (R7, patterns.md § library-module hygiene).

The three `ViewModel` string hits in `core-runtime` are doc-comments only
(`LlmChatModelHelper.kt:278`, `ModelRegistry.kt:11,77`), not imports.

**Findings:** none.

### 2. Lifecycle hygiene

**Status:** pass.

**CameraX (`app\src\main\kotlin\app\sanctum\machina\ui\chat\CameraBottomSheet.kt`):**

- `bindToLifecycle` at `CameraBottomSheet.kt:205`, preceded by defensive `unbindAll` at
  `:204` inside `LaunchedEffect(lifecycleOwner)`.
- `DisposableEffect(Unit) { onDispose { cameraProvider?.unbindAll(); executor.shutdown() } }`
  at `:219–224` — guaranteed teardown on composition drop.
- `LaunchedEffect` is keyed on `lifecycleOwner` (`:201`) so a NavHost-driven owner swap
  does not orphan the bound use cases (R8).
- Capture callback marshals through `scope.launch { … }` (`:272, 276, 280`); `scope` is
  the sheet's `rememberCoroutineScope` — a swipe-dismiss mid-capture cancels it, so
  `onCapture`/`onError` delivered post-cancellation become silent no-ops (no phantom
  attachment).
- Capture-decode failures close `ImageProxy` (`:268, 275`) before re-dispatching the
  error — prevents the native buffer leak.

**AudioRecord (`app\src\main\kotlin\app\sanctum\machina\ui\chat\AudioRecorderBottomSheet.kt`):**

- Constructor at `:330`; `state` check at `:337` with eager `release()` at `:338` when
  init fails.
- `DisposableEffect(Unit) { onDispose { job?.cancel(); recorder?.stop(); recorder.release() } }`
  at `:230–241`.
- `LifecycleEventEffect(Lifecycle.Event.ON_PAUSE)` at `:243` — flips the idempotency
  latch `completed.set(true)` BEFORE starting the dismiss animation, then synchronously
  `recorder.stop()`. AC-19 regression closed: the IO loop's pending 30-sec `finish()`
  becomes a no-op and the buffer is genuinely dropped during the ~300 ms sheet-hide
  animation.
- Terminal path `finish()` at `:350–365` gated by `AtomicBoolean.compareAndSet` — single
  save guarantee across Stop-button / auto-stop / onPause races.
- `RecorderHandle` fields marked `@Volatile` (`:315, 317`) — safe cross-thread reads
  between Main callbacks and the IO recording coroutine.
- Permission path guards unprivileged construction: `buildRecorder` is only called after
  `hasPermission == true` (`:155`); the `@SuppressLint("MissingPermission")` at `:328`
  is justified.

Grep summary of native-resource call sites (matches only in the two sheets and
`MediaUtils.kt` header writer — no orphan constructors elsewhere):

```
CameraBottomSheet.kt:204  provider.unbindAll()
CameraBottomSheet.kt:205  provider.bindToLifecycle(
CameraBottomSheet.kt:221  cameraProvider?.unbindAll()
AudioRecorderBottomSheet.kt:237  r.release()
AudioRecorderBottomSheet.kt:330  val recorder = AudioRecord(
AudioRecorderBottomSheet.kt:338  recorder.release()
```

**Findings:** none.

### 3. Autoscroll performance

**Status:** pass.

**Evidence:** `ChatScreen.MessageList` at `ChatScreen.kt:348–383`:

```kotlin
val lastTextLen = messages.lastOrNull()?.text?.length ?: 0
val lastThinkingLen = messages.lastOrNull()?.thinkingText?.length ?: 0
LaunchedEffect(messages.size, lastTextLen, lastThinkingLen) {
    if (messages.isNotEmpty()) {
        listState.animateScrollToItem(
            index = messages.lastIndex,
            scrollOffset = Int.MAX_VALUE / 2,
        )
    }
}
```

- Single consolidated `LaunchedEffect` keyed on `(size, lastTextLen, lastThinkingLen)` —
  matches the Task 11 decisions.md post-smoke plan.
- Keys read `Message` fields directly — no `snapshotFlow`, no `derivedStateOf`, no
  additional `listState` snapshot reads during composition.
- `scrollOffset = Int.MAX_VALUE / 2` anchors the bottom of the last item, solves the
  clipping regression that plain `animateScrollToItem(lastIndex)` produced on long
  streams (commit 944b3b3).
- Re-scroll fires per-token by design; the `LazyListState.animateScrollToItem` helper
  is internally debounced against in-flight scroll animations, so rapid key changes
  do not queue up competing animations.
- Grep: `animateScrollToItem` appears only at this single call site in `:app`; no
  duplicated autoscroll surfaces.

**Findings:** none.

### 4. Permission flow consistency

**Status:** pass.

Both sheets follow the same permission pattern:

| Step | `CameraBottomSheet.kt` | `AudioRecorderBottomSheet.kt` |
|------|------------------------|-------------------------------|
| `ContextCompat.checkSelfPermission` seed | `:110–115` | `:114–119` |
| `rememberLauncherForActivityResult(RequestPermission())` | `:127–139` | `:131–143` |
| `shouldShowRequestPermissionRationale` permanent check | `:134–136` | `:138–140` |
| Pure permanent-denial helper | `isCameraDenialPermanent` at `:339` | `isAudioDenialPermanent` at `:386` |
| Denial → `onPermissionDenied(permanent)` + dismiss | `:136–138` | `:140–142` |

`ChatScreen.ReadyContent` renders the identical snackbar contract for both:

- Non-permanent denial → short snackbar with the domain-specific message
  (`permission_camera_denied` / `permission_audio_denied`).
- Permanent denial → long snackbar with action label
  `R.string.permission_open_settings` that, on `ActionPerformed`, runs the shared
  `context.openAppSettings()` deeplink.

The deeplink implementation at `ChatScreen.kt:340–346` is wrapped in `runCatching` to
swallow `ActivityNotFoundException` on locked-down OEMs — both flows share this single
helper, so behaviour cannot drift.

**Findings:** none.

### 5. Markdown safety

**Status:** pass.

**Evidence:** Grep for `Markdown(` and `RichText {` returns four hits across `:app`:

```
AboutScreen.kt:75              SafeMarkdown(text = markdown)
ThinkingBlock.kt:112           SafeMarkdown(
MessageBubble.kt:91            SafeMarkdown(text = display)
SafeMarkdown.kt:46             fun SafeMarkdown(
SafeMarkdown.kt:82             Markdown(content = text)
```

The only call-site of the raw `com.halilibo.richtext.commonmark.Markdown` / `RichText`
is inside `SafeMarkdown` itself at `SafeMarkdown.kt:67–83`. `SafeMarkdown` wraps the
content tree in `CompositionLocalProvider(LocalUriHandler provides SafeUriHandler(context))`
at `:58`, so every rendered link goes through the scheme-whitelist at `:30–42`:

- `ALLOWED_SCHEMES = setOf("http", "https")` — lower-cased per RFC 3986 §3.1.
- Non-whitelisted schemes return silently without `startActivity`.
- Malformed URIs caught by `runCatching { Uri.parse(uri) }` at `:34`.
- `context.startActivity` itself wrapped in `runCatching` at `:41` — stray
  `ActivityNotFoundException` from degenerate intents cannot crash the chat.

User-authored text in `MessageBubble.kt:85–90` renders via plain `Text` (no markdown
parse), eliminating client-side injection of markdown into the user's own bubbles.

`SafeUriHandlerTest` covers 14 cases (allowed + blocked schemes + empty/malformed),
TAC-13 green.

**Findings:** none.

### 6. Cross-component duplicate init

**Status:** pass.

**Engine / `ModelRegistry.initialize` call sites** (grep):

- `ChatViewModel.kt:102` — initial load inside `init { viewModelScope.launch { … } }`.
- `ChatViewModel.kt:398` — heavy-reinit inside `applyHeavySetting` after `registry.cleanup`.

Both sites hit the same `DefaultModelRegistry` singleton; `:170` guards the work under
a shared `lifecycleMutex.withLock { … }` and calls `releaseEngine(model)` before
`awaitInitialize` to idempotently tear down any prior native instance. No secondary
caller (no fragment, no extra ViewModel, no `ModelManagerViewModel` invocation) —
grep confirms.

**DataStore singleton:**

- `CoreSettingsModule.provideAppSettingsDataStore` at `CoreSettingsModule.kt:32–43` is
  `@Provides @Singleton`, path `context.filesDir/datastore/app_settings.pb`.
- `DefaultAppSettingsRepository` at `DefaultAppSettingsRepository.kt:17–20` is
  `@Singleton` with injected `DataStore<AppSettings>` — single consumer of the
  DataStore handle, matches tech-spec shared-resources table.
- `DefaultAppSettingsRepository` catches `IOException` on observe/save/reset (R13) and
  logs via whitelisted `"settings-io"` component — correct.

**Hilt / `ErrorLog`:** `@Singleton` at `ErrorLog.kt:65`, no alternative constructor call
sites.

**Findings:** none.

### 7. Shared resources compliance

**Status:** pass.

Tech-spec § Shared resources table vs actual code:

| Resource | Tech-spec owner | Tech-spec consumers | Actual owner | Actual consumers | Verdict |
|----------|-----------------|---------------------|--------------|------------------|---------|
| `DataStore<AppSettings>` | `CoreSettingsModule` (Hilt singleton) | `DefaultAppSettingsRepository` | `CoreSettingsModule.kt:32` | `DefaultAppSettingsRepository.kt:17` | match |
| `AppSettingsRepository` | `CoreSettingsModule` | `ChatViewModel`, `InferenceSettingsBottomSheet` | `@Binds` at `CoreSettingsModule.kt:26` | `ChatViewModel.kt:62`, `InferenceSettingsBottomSheet.kt:69` | match |
| litertlm engine | `DefaultModelRegistry` | `ChatViewModel` | `DefaultModelRegistry.kt:74` | `ChatViewModel.kt:57` | match (single-active via `lifecycleMutex`) |
| CameraX `ProcessCameraProvider` | `CameraBottomSheet` | sheet only | `CameraBottomSheet.kt:203` | sheet only, bound/unbound in `DisposableEffect` | match |
| `AudioRecord` | `AudioRecorderBottomSheet` | sheet only | `AudioRecorderBottomSheet.kt:330` | sheet only, released in `onDispose` + `ON_PAUSE` | match |
| `ErrorLog` (Phase 1 residual) | `:core-runtime` (Hilt `@Singleton`) | VMs, repo, registry | `ErrorLog.kt:65` | `ChatViewModel`, `DefaultAppSettingsRepository`, `DefaultModelRegistry` | match, `ALLOWED_COMPONENTS` whitelist at `:32–43` extended per D27 |

All `errorLog.e(...)` call sites use whitelisted components
(`"download"`, `"inference-init"`, `"inference"`, `"inference-cleanup"`, `"settings-io"`,
`"camera"`, `"audio"`, `"attachment-decode"`).

**Findings:** none.

## Blocking findings

None.

## Non-blocking observations

### NB-1. Persistence key deviation from tech-spec D3 (low)

**Files:** `app\src\main\kotlin\app\sanctum\machina\ui\chat\ChatViewModel.kt:262, 280, 316, 338, 355, 393, 587`.

**Observation:** tech-spec D3 mandates `map<string, PerModelSettings>` keyed by
`Model.modelId`; `DefaultAppSettingsRepository.savePerModelSettings(modelId, …)` takes
that contract verbatim. The call-site in `ChatViewModel` passes `modelName` as the key
(`savePerModelSettings(modelName, settings)` etc.). For Phase 2 the two are functionally
interchangeable (allowlist names stable), but renaming a model in the allowlist would
orphan persisted overrides.

**Status:** already documented in `decisions.md` Task 11 under Deviations —
*"Per-model settings keyed by `Model.name`, not `Model.modelId`. Phase-2 allowlist names
are stable; Phase 3 can migrate when Room schema lands."* Not a regression, deferred
intentionally. Call out here so Phase 3 owner picks it up alongside the Room migration.

**Recommendation:** expose `Model.modelId` via `ModelRegistry` (or propagate through
`ModelEntry`) in Phase 3 and switch the VM to pass `modelId` — one-line fix at the
call site once the upstream field is reachable.

### NB-2. `classifyApplyLevel` compares proto-typed and Kotlin-typed values (low)

**File:** `app\src\main\kotlin\app\sanctum\machina\ui\chat\ChatViewModel.kt:546–566`.

**Observation:** `classifyApplyLevel(current, target)` checks equality of the values
pulled from `model.configValues` (Phase-1 typed as `Any`, populated from `LabelConfig`
`defaultValue` / `String` allowlist overrides) against the values returned by
`EffectiveConfig.merge`, which explicitly produces Kotlin `Int` / `Float` / `Boolean` /
`String` per proto field. When no override exists, the `current` side may carry the
allowlist's `Int` literal from `createLlmChatConfigs` — fine — but if a field happens to
be written as a boxed number through a non-canonical path, `Int(40) != java.lang.Integer(40)`
could falsely report "heavy changed" and route through `HeavyChangeDialog` on a no-op
change. Today all writers go through `EffectiveConfig.merge` so the comparison is
type-stable; code-reviewer Round-1 marked this "harmless, convertValueToTargetType
normalises at read time" (see decisions.md Task 11 M4).

**Status:** latent. Not exercised by current writers.

**Recommendation:** on the Phase 3 refactor that touches `configValues`, route the
comparison through the same type-narrowing helpers (`getIntConfigValue` / `getFloatConfigValue`)
as `LlmChatModelHelper.initialize` uses, instead of raw `Map<String, Any>` equality.

## Smoke grep results

### §Verification Steps — Smoke 1 (module boundary)

`:core-runtime` and `:core-settings` — `import androidx.compose` / `import android.app.Activity` / `import androidx.activity` / `ViewModel` class declarations:

```
(all four greps return: No matches found)
```

### §Verification Steps — Smoke 2 (AudioRecord lifecycle)

```
AudioRecorderBottomSheet.kt:330  val recorder = AudioRecord(
AudioRecorderBottomSheet.kt:338  recorder.release()      # init-failure branch
AudioRecorderBottomSheet.kt:237  r.release()             # DisposableEffect.onDispose
```

Single constructor, two `release()` paths — both cover every exit (init fail, normal
teardown, `ON_PAUSE`, cancellation — the `DisposableEffect.onDispose` is the guaranteed
catch-all).

### §Verification Steps — Smoke 3 (CameraX bindToLifecycle)

```
CameraBottomSheet.kt:204  provider.unbindAll()
CameraBottomSheet.kt:205  provider.bindToLifecycle(
CameraBottomSheet.kt:221  cameraProvider?.unbindAll()   # DisposableEffect.onDispose
```

Single `bindToLifecycle`, matching `unbindAll` immediately before the bind (defensive)
and unconditionally in `onDispose` — lifecycle-balanced.
