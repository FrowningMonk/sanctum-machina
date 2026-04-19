# Code Research: phase-3-history

> Read-only investigation of Phase 2 (`v0.2-ui`) code to inform Phase 3 planning.
> Organised per the main-agent prompt (A – J). Every line reference is absolute
> to the repo under `C:\AI-WORK\PhoneWrap\`.

---

## A) Engine / model lifecycle

**Summary:** The engine is already effectively a process-wide singleton today —
`DefaultModelRegistry` is `@Singleton` with `@InstallIn(SingletonComponent::class)`.
What is NOT singleton is the *decision of when to initialise it* — that currently
lives inside `ChatViewModel.init`, which means the expensive
`LlmChatModelHelper.initialize` (5–30 s) runs the first time the user opens a
chat, blocking the `ChatScreen` on `ChatUiState.Loading`. Phase 3 warmup simply
needs to move the first `registry.initialize(modelName)` out of
`ChatViewModel.init` and into something that fires on `SanctumApplication.onCreate`
(or an application-scoped coordinator).

### A1. `DefaultModelRegistry` — already singleton

- `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt:74` — `@Singleton class DefaultModelRegistry @Inject constructor(...)`
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/di/CoreRuntimeModule.kt:17-32` — installed in `SingletonComponent`; provider binds interface to implementation:
  ```kotlin
  @Provides @Singleton fun provideModelRegistry(impl: DefaultModelRegistry): ModelRegistry = impl
  @Provides @Singleton fun provideLlmModelHelper(): LlmModelHelper = LlmChatModelHelper   // object singleton
  ```
- Private scope inside registry: `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` (`DefaultModelRegistry.kt:85`). Used only for `refreshAllowlist + scanLocalFiles + resumePartialDownloads` at construction (lines 91–103) and for the `download()` → `launchIn(scope)` bridge.
- Lifecycle mutex: `private val lifecycleMutex = Mutex()` (line 86) — already serialises init/cleanup/resetConversation/delete. Single-active-engine contract is explicit (`ModelRegistry.kt:53-60`).

**Implication for Phase 3:** No Hilt scope change needed. `DefaultModelRegistry`
is already the right place for the engine warmup. Adding a new `initializeDefault()`
entry point or wiring `SanctumApplication` to push `registry.initialize(defaultModelName)`
on startup will not break any existing consumer.

### A2. `LlmChatModelHelper` and `ModelRuntimeHandle`

- `core-runtime/src/main/kotlin/app/sanctum/machina/core/inference/LlmChatModelHelper.kt:56` — **`object LlmChatModelHelper : LlmModelHelper`** — Kotlin `object`, so functionally a process-singleton already; the `@Singleton @Provides` in CoreRuntimeModule just mirrors that at the Hilt level.
- There is NO `ModelRuntimeHandle` type yet. The native engine state lives in `LlmModelInstance(val engine, var conversation)` (line 54) and is stored **on the `Model` itself** as `model.instance: Any?` (`Model.kt:85`). `patterns.md` explicitly flags this as an anti-pattern but it has not been refactored yet.
- Per-model `CleanUpListener` map: `private val cleanUpListeners: MutableMap<String, CleanUpListener> = mutableMapOf()` (`LlmChatModelHelper.kt:57`) — registered in `runInference` (line 250), fired in `cleanUp` (line 219). **Not thread-safe** — relies on the registry's `lifecycleMutex` serialising all access. Phase 3 warmup path must continue to enter through the mutex.

### A3. `initialize` / `cleanup` / `resetConversation` call sites

All registry-level lifecycle calls funnel through `ChatViewModel` today:

| Call | File | Line | Trigger |
|---|---|---|---|
| `registry.initialize(modelName)` — first call | `ChatViewModel.kt` | 102 | `init {}` of `ChatViewModel` (runs when the NavBackStackEntry for `chat/{modelName}` is first visited) |
| `registry.initialize(modelName)` — heavy reinit | `ChatViewModel.kt` | 398 | `applyHeavySetting()` after user toggles `accelerator` via settings sheet |
| `registry.cleanup(modelName)` — heavy reinit prep | `ChatViewModel.kt` | 397 | same flow (teardown before reinit) |
| `registry.cleanup(modelName)` — teardown | `ChatViewModel.kt` | 535 | `onCleared()` — fires when user leaves the chat destination and the NavBackStackEntry is destroyed |
| `registry.resetConversation(modelName, systemPrompt)` — manual reset | `ChatViewModel.kt` | 239 | `resetConversation()` / `reset()` — TopAppBar `Icons.Outlined.Refresh` |
| `registry.resetConversation(modelName, systemPrompt)` — semi-light apply | `ChatViewModel.kt` | 360 | `applySystemPromptAndReset()` when `system_prompt_default` or `enable_thinking` changes |

Inside the registry itself, `awaitInitialize` (`DefaultModelRegistry.kt:267-281`) bridges the callback-based `LlmModelHelper.initialize` to `suspendCancellableCoroutine`; `initialize()` (line 170) does unconditional GPU→CPU fallback. `cleanup()` (line 211) and `resetConversation()` (line 221) both hold `lifecycleMutex` and dispatch onto `Dispatchers.Default`.

**Implication for Phase 3:**
- Warmup has exactly one new caller surface: application start. Every other site stays put.
- `onCleared()` at `ChatViewModel.kt:531` tears down the engine when the user leaves the chat. In Phase 3 with Application-scope ownership of the engine, **this must be removed or gated** — otherwise opening a chat, navigating back to the drawer, and opening a second chat on the SAME model would re-initialise unnecessarily (defeats the point of warmup).
- Cross-model reopen must enter `registry.cleanup(oldModelName) → registry.initialize(newModelName)` atomically. The `lifecycleMutex` already guarantees serialisation; the UX confirmation dialog goes in front of it.

### A4. `ChatViewModel` registry acquisition

- `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt:52-63` — `@HiltViewModel class ChatViewModel @Inject constructor(savedStateHandle, registry, helper, errorLog, @ApplicationContext context, imageDecoder, settingsRepository) : ViewModel()`. Registry is constructor-injected by the Hilt-viewmodel extension, so it already resolves the singleton.
- `savedStateHandle.get<String>(NAV_ARG_MODEL_NAME)` → `modelName` (line 65). Required; error if missing.
- `hiltViewModel()` is called at `ChatScreen.kt:61` and `ModelManagerScreen.kt:43`.

### A5. `SanctumApplication.onCreate`

Full file — `app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt:1-13`:
```kotlin
@HiltAndroidApp
class SanctumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DefaultDownloadRepository.mainActivityFqn = "app.sanctum.machina.MainActivity"
    }
}
```
- The only startup-time wiring today is the `MainActivity` FQN string for the download-notification PendingIntent bridge (`DownloadRepository.kt:81`).
- No warmup hook exists. The architecture doc (`architecture.md:102`) already predicts: *"Phase 3+: data/ (Room repos), di/ (app-level Hilt modules)"*.

**Implication for Phase 3:** `SanctumApplication.onCreate` is the hook for:
1. Kicking off engine warmup (inject `ModelRegistry` via Hilt `EntryPoint`, launch on a scope, select the default model per the resolution rule in the user prompt).
2. Purging `filesDir/quick/` from any previous session (quick-chat incognito guarantee).

Note: `EarlyEntryPoint` pattern is needed because `Application.onCreate` runs before Hilt's usual `@Inject` is viable on the Application class. `@HiltAndroidApp` + an `@EntryPoint` interface retrieved via `EntryPointAccessors.fromApplication(this, ...)` is the idiom.

### A6. Current model-switch flow: ModelManagerScreen → ChatScreen → first send

1. `ModelManagerScreen.kt:43` — `onLoad(modelName)` callback posted via `NavEvent.OpenChat(modelName)` in `ModelManagerViewModel` (line 43).
2. `SanctumApp.kt:20-24` — `navController.navigate("chat/${Uri.encode(modelName)}")`.
3. `SanctumApp.kt:26-36` — `chat/{modelName}` destination instantiates `ChatScreen` with the decoded name.
4. `ChatScreen.kt:61` — `viewModel: ChatViewModel = hiltViewModel()` — Hilt creates the VM keyed to the `NavBackStackEntry` (per-navigation-destination scope).
5. `ChatViewModel.init` (line 97-115) — launches `applyEffectiveConfigToModel()` → `registry.initialize(modelName)` → on success, refresh caps + flip to `Ready(isGenerating=false)`.
6. UI stays in `ChatUiState.Loading` until step 5's fold runs (see `ChatScreen.kt:79-81` — `LoadingContent`).
7. First `send(text)` runs synchronously from `ChatViewModel.kt:117`. Gated on `ChatUiState.Ready && !isGenerating && !reinitInProgress`. Actual engine call is `helper.runInference` (line 175).

**Phase 3 warmup trajectory:** steps 4–5 today happen on NavBackStackEntry creation. With optimistic-UI loading the VM can flip to `Ready(isGenerating=false)` **while** the engine is still warming: UI disables `Send` but shows the bubble list. Then when the warmup Job completes, `Send` enables. Requires a new `ChatUiState.ReadyWarmingUp` (or a boolean `engineReady: Boolean`) and a callback from registry into the VM when initialisation is done.

---

## B) ChatViewModel + ChatScreen state

### B1. `ChatViewModel` state inventory

All reactive state is in `ChatViewModel.kt`:
- `_uiState: MutableStateFlow<ChatUiState>` (line 69) — sealed `Loading | Ready(isGenerating) | Failed(rawCause)` (lines 35-39).
- `_messages: MutableStateFlow<List<Message>>` (line 72) — the entire chat transcript. **Lost on process death.** Architecture doc confirms (`architecture.md:195`): *"Phase 2 reality: … chats, messages, attachments all live in `ChatViewModel.StateFlow` and are discarded on process death."*
- `_attachments: MutableStateFlow<List<Attachment>>` (line 75) — staged-but-not-sent attachments (thumbnail strip).
- `_modelCaps: MutableStateFlow<ModelCapabilities>` (line 78) — derived post-init from `model.llmSupport{Image,Audio,Thinking}`.
- `_reinitInProgress: MutableStateFlow<Boolean>` (line 86) — toggles `ReinitProgressDialog`.
- `_snackbar: MutableSharedFlow<Int>` (line 94) — `@StringRes` id events, `replay=0`, `extraBufferCapacity=8`.

Per-`send()` local state (not fields, but critical):
- `sb: StringBuilder` (line 163) — accumulates streaming text.
- `thinkingSb: StringBuilder` (line 164) — accumulates streaming thinking-channel, gated once on `model.llmSupportThinking && ENABLE_THINKING` at line 141.

`onCleared()` (line 531-536) tears down the engine on VM disposal.

### B2. ViewModel scope

- `@HiltViewModel` + `hiltViewModel()` in Compose → scoped to the **`NavBackStackEntry`** (per-destination). Rotation uses `SavedStateHandle`, which only recovers `modelName` (line 65) — messages are NOT in `SavedStateHandle`.
- Process death: VM is recreated from scratch. No persistence. All messages gone.
- Navigating away from chat (`popBackStack()`) destroys the back-stack entry → VM `onCleared()` → engine torn down.

### B3. "Current open chat" identification

**There is no chat id today.** The session is keyed by `modelName` alone (`ChatViewModel.kt:65`, `SanctumApp.kt:22`). The nav argument `{modelName}` is the session identity. Opening a second `ChatScreen` with the same `modelName` is impossible today because Navigation Compose treats identical routes as the same destination.

**Implication for Phase 3:** Need to introduce a chat-id concept (Room `chats.id`). Nav route becomes `chat/{chatId}` (or `chat/{chatId}?kind=quick|persistent`). The `modelName` is derived from `chat.model_id` inside the VM.

### B4. Send / Stop / Reset gating

- `send(text)` — `ChatViewModel.kt:117-220`. Gates:
  - Early return if `normalized.isEmpty() && pending.isEmpty()` (line 120).
  - Early return if `state !is ChatUiState.Ready || state.isGenerating` (line 122).
  - Early return if `_reinitInProgress.value` (line 123).
  - `error("Model not initialized")` if registry has no `Ready` model (line 124).
- `stop()` — line 222-227. Calls `helper.stopResponse(model)`, marks last assistant message `interrupted = true`, flips back to `Ready(isGenerating=false)`.
- `resetConversation()` — line 235-243. Calls `registry.resetConversation(modelName, systemPrompt = effective)`, clears `_messages` and `_attachments`.

UI enables the Send button via `MultimodalInputState.isGenerating` + `hasAttachments || text.isNotBlank()` — see `MultimodalInputBar.kt` lines 33-52 and `ChatScreen.kt:221-228`.

### B5. Attachment storage

- `_attachments: StateFlow<List<Attachment>>` in `ChatViewModel.kt:75-76`.
- `Attachment` sealed class — `Attachment.kt:23-53`. Two subclasses:
  - `Image(val bitmap: Bitmap, override val id: Long)` — holds a `Bitmap` in heap (downscaled to ~1024 px edge).
  - `Audio(val pcm: ByteArray, val durationMs: Long, override val id: Long)` — raw PCM bytes in heap.
- Snapshot-on-dispatch pattern (AC-26, D28, `ChatViewModel.kt:126-158`):
  ```kotlin
  val images = pending.filterIsInstance<Attachment.Image>().map { it.bitmap }
  val audioClips = pending.filterIsInstance<Attachment.Audio>().map { pcmToWav(it.pcm, SAMPLE_RATE) }
  _messages.update { current -> current + Message(USER, text, attachments = pending) + Message(ASSISTANT, "", streaming = true, ...) }
  _attachments.value = emptyList()
  ```
- Attachments embed into the USER `Message` at dispatch (`Message.kt:24`, line 11-24 KDoc) so history bubbles render thumbnails.
- `addImages(uris)` — `ChatViewModel.kt:431-471`. Decodes via `ImageDecoder` (`Dispatchers.IO`), clips to `MAX_IMAGES=10`, handles TOCTOU race inside `_attachments.update {}`.
- `addImageBitmap(bitmap)` — line 479-486. Defensive downscale to 1024 px. Used by CameraBottomSheet.
- `addAudio(pcm, durationMs)` — line 502-507. Enforces `MAX_AUDIO_CLIPS=1`.
- `removeAttachment(idx)` — line 488-493.

### B6. Last-used model

**ChatViewModel does NOT know about "last-used model".** There is no field, no persistence hook. The current session's model is `ChatViewModel.modelName` (immutable), nothing more.

No `last_used_model_id` key exists in `PerModelSettings` or `AppSettings` (`core-settings/src/main/proto/app_settings.proto:1-21`). Phase 3 needs to add this — either a new top-level field `optional string last_used_model_id = 2` in `AppSettings`, or a separate proto field for default-model selection.

---

## C) Navigation structure

File: `app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt:14-41`.

Routes:
- `"model_manager"` — start destination (`startDestination = "model_manager"`, line 17).
- `"chat/{modelName}"` — with `navArgument("modelName") { type = NavType.StringType }`, `Uri.decode` applied in the composable (line 30-31).
- `"about"` — `AboutScreen`, back via `popBackStack()`.

No drawer, no sidebar, no bottom navigation today. Back from `ChatScreen` = `navController.popBackStack()` wired via `onBack = { navController.popBackStack() }` at line 34. The TopAppBar back arrow is `ChatScreen.kt:234-240`.

**Implication for Phase 3:**
- Introducing `ModalNavigationDrawer` at the top of `SanctumApp()` wraps the NavHost. The drawer becomes a sibling of the NavHost, not a new destination.
- Main screen ("New quick chat" hub) needs either (a) a new start destination `"home"` replacing `model_manager`, with `model_manager` moving behind a drawer entry, or (b) reusing `model_manager` as the hub and adding a new "quick chat" composable. The latter is a smaller delta.
- Chat route changes from `chat/{modelName}` to `chat/{chatId}` (see B3).
- Cross-model confirmation dialog must fire **before** `navController.navigate(...)` — the drawer click handler owns this decision.

---

## D) Attachment storage — current and migration targets

### D1. `decodeSampledBitmapFromUri`

- Defined: `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MediaUtils.kt:28-50` — two-pass decode, downsamples to fit `reqWidth × reqHeight`, returns `Bitmap?`.
- Invoked at exactly one site:
  - `app/src/main/kotlin/app/sanctum/machina/ui/chat/ImageDecoder.kt:36` — inside `DefaultImageDecoder.decode(uri)`, `withContext(Dispatchers.IO)`.
- `ImageDecoder` is `@Binds @Singleton` in `ImageDecoderModule` (`ImageDecoder.kt:40-46`).
- `TARGET_EDGE = 1024` (line 30).

### D2. Raw PCM lifetime

- Captured in `AudioRecorderBottomSheet` via `AudioRecord` — produces `ByteArray` on `Dispatchers.IO`.
- Stored in `Attachment.Audio.pcm: ByteArray` on the VM heap (`Attachment.kt:32-46`).
- Lives only inside `_attachments` until `send()` dispatches it; after dispatch it is copied (by reference) into `Message.attachments` in `_messages` (`ChatViewModel.kt:144-148`).
- **Lifetime today:** same as `_messages` — lost on process death.

### D3. `pcmToWav`

- Defined: `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MediaUtils.kt:126-157`. Pure function, wraps raw PCM in RIFF/WAVE header.
- Called from: `ChatViewModel.kt:133`:
  ```kotlin
  val audioClips = pending.filterIsInstance<Attachment.Audio>().map { pcmToWav(it.pcm, SAMPLE_RATE) }
  ```
- Only invoked at the litertlm boundary (in `send()`); the `Attachment.Audio` itself keeps headerless PCM for compactness — decisions.md Task 9 explicitly documents this as a Phase-3 Room compactness choice.

### D4. File-write code in :app

**Today there is none for chat attachments.** Nothing in `:app` writes media to `filesDir`. The only `filesDir` writers in the whole codebase are:
- `ErrorLog.kt:77` → `filesDir/logs/errors.log` (on-device error log, with rotation).
- `CoreSettingsModule.kt:40` → `filesDir/datastore/app_settings.pb` (DataStore).
- Model downloads go to `context.getExternalFilesDir(null)` via `Model.getPath()` (`Model.kt:105-132`) — external, not `filesDir`.

`CameraBottomSheet.kt` (first 80 lines read) captures via `ImageCapture.takePicture` → `ImageProxy` → `Bitmap` → `ChatViewModel.addImageBitmap(bitmap)`. No file is ever written to disk; the bitmap lives entirely in memory.

`AudioRecorderBottomSheet.kt` (first 80 lines read) writes PCM into a `ByteArrayOutputStream` on IO thread and passes the resulting `ByteArray` to `ChatViewModel.addAudio(pcm, durationMs)`. Again, no file on disk.

**Implication for Phase 3:** The "move attachments to files in filesDir/attachments/{chat_id}/ (persistent) and filesDir/quick/ (ephemeral)" migration is **entirely net-new write code**. The capture surfaces (`CameraBottomSheet`, `AudioRecorderBottomSheet`, Photo Picker via `ImageDecoder`) stay in-memory; the new I/O happens at the `ChatViewModel.send()` boundary (persistent chats → write, then store path on Message) and on purge (quick chats → delete directory on session end). There is no existing filesystem shape to preserve.

---

## E) Hilt DI modules and scoping

### E1. Modules and bindings

| Module | File | Bindings |
|---|---|---|
| `CoreRuntimeModule` | `core-runtime/src/main/kotlin/app/sanctum/machina/core/di/CoreRuntimeModule.kt:17-32` | `@Singleton DownloadRepository` (DefaultDownloadRepository), `@Singleton LlmModelHelper` (LlmChatModelHelper object), `@Singleton ModelRegistry` (DefaultModelRegistry) |
| `CoreSettingsModule` | `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/di/CoreSettingsModule.kt:21-43` | `@Binds @Singleton AppSettingsRepository` (DefaultAppSettingsRepository), `@Provides @Singleton DataStore<AppSettings>` |
| `ImageDecoderModule` | `app/src/main/kotlin/app/sanctum/machina/ui/chat/ImageDecoder.kt:40-46` | `@Binds @Singleton ImageDecoder` (DefaultImageDecoder) |

All modules `@InstallIn(SingletonComponent::class)`.

Plus non-module singletons created by direct class annotation:
- `DefaultModelRegistry` — `@Singleton` at class level (`DefaultModelRegistry.kt:74`).
- `AllowlistLoader` — `@Singleton` (`AllowlistLoader.kt:26`).
- `ErrorLog` — `@Singleton` (`ErrorLog.kt:64`).
- `DefaultAppSettingsRepository` — `@Singleton` (`DefaultAppSettingsRepository.kt:17`).
- `DefaultImageDecoder` — `@Inject constructor` without class-level `@Singleton`; `@Singleton` is on the `@Binds` method (typical pattern).

ViewModels:
- `ModelManagerViewModel` — `@HiltViewModel` (`ModelManagerViewModel.kt:20`).
- `ChatViewModel` — `@HiltViewModel` (`ChatViewModel.kt:52`).

Application-level:
- `SanctumApplication` — `@HiltAndroidApp`.
- `MainActivity` — `@AndroidEntryPoint`.

### E2. Existing `@Inject` consumers of `ModelRegistry`

Exact call sites:
- `DefaultModelRegistry.kt:76-82` — constructor injects `DownloadRepository`, `LlmModelHelper`, `AllowlistLoader`, `ErrorLog`, `@ApplicationContext Context`.
- `ModelManagerViewModel.kt:22-23` — constructor injects `ModelRegistry`.
- `ChatViewModel.kt:54-63` — constructor injects `ModelRegistry` (plus others).

**Moving `DefaultModelRegistry` to `@Singleton` is a no-op — it already is.** The real Phase 3 change is not a scope change but adding a new caller: `SanctumApplication` needs to drive the *first* `initialize` call instead of waiting for `ChatViewModel.init`.

### E3. `SanctumApplication` Hilt setup

- `app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt:7` — `@HiltAndroidApp`. Standard.
- No Hilt modules declared inside `:app` currently (`ImageDecoderModule` lives in `ui/chat/`). Architecture doc predicts `app/di/` folder for Phase 3+.

**Implication for Phase 3:** New `:app/di/AppModule.kt` for Room `@Database`, DAOs, and chat coordinator bindings. Also `@EntryPoint` interface exposed to `SanctumApplication.onCreate` for retrieving `ModelRegistry` + `AppSettingsRepository` (to pick the default model) + a new `ChatRepository` (for quick-chat filesDir purge).

---

## F) Per-model settings — coordination with Room

### F1. Current override key

- `observePerModelSettings(modelId: String): Flow<PerModelSettings?>` (`AppSettingsRepository.kt:7`).
- Proto schema: `map<string, PerModelSettings> per_model_overrides = 1` (`app_settings.proto:19`). Key is a string.
- **Actual key passed in code:** `ChatViewModel.kt:262, 280, 316, 338, 355, 393` — all use `modelName` (the `Model.name` string, e.g., `"Gemma-4-E4B-it-litert-lm"`).
- `AllowedModel.modelId` is a distinct field (`ModelAllowlist.kt:37`) — the HuggingFace repo identifier like `"litert-community/gemma-4-E4B-it-litert-lm"`. `Model.name` is derived from the allowlist `name` field (`ModelAllowlist.kt:77`).
- `Model` class does NOT expose `modelId` — only `name`, `displayName`, `normalizedName`, `version` (`Model.kt:51-94`). The Phase 2 code base physically cannot key by `modelId` today without changes upstream.

**Documented drift:** Phase 2 `decisions.md` Task 11 (Deviations) and Task 12 audit (NB-1) explicitly flag this. Quote from decisions.md Task 11:
> **Per-model settings keyed by `Model.name`, not `Model.modelId`.** Tech-spec D3 specifies `Model.modelId` for rename-stability, but `Model` doesn't expose `modelId` (only `AllowedModel` does). Phase-2 allowlist names are stable; Phase 3 can migrate when Room schema lands.

**Implication for Phase 3:** When introducing Room `chats.model_id`, the app should plumb `modelId` through `Model` and migrate `PerModelSettings` storage keys from `Model.name` to `Model.modelId` (proto schema stays the same; only the string values change). Room `chats.model_id` stores the stable HF id; `PerModelSettings` key aligns. This is a data-migration task — a one-off rewrite of the DataStore file at first boot under the new version.

### F2. `AppSettingsRepository`

- Interface — `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/AppSettingsRepository.kt:1-11`. Three methods:
  ```kotlin
  fun observePerModelSettings(modelId: String): Flow<PerModelSettings?>
  suspend fun savePerModelSettings(modelId: String, settings: PerModelSettings)
  suspend fun resetPerModelSettings(modelId: String)
  ```
- Impl — `DefaultAppSettingsRepository.kt:17-58`. Wraps `DataStore<AppSettings>` with `IOException` catch + `errorLog.e("settings-io", ...)`.

### F3. Adding `default_model_id`

Trivial proto change in `core-settings/src/main/proto/app_settings.proto:18-20`:
```proto
message AppSettings {
  map<string, PerModelSettings> per_model_overrides = 1;
  optional string default_model_id = 2;
  optional string last_used_model_id = 3;
}
```
Adds two getters/setters on the generated class (no wire breakage because proto3 optional). Repository grows two new methods; no existing consumer breaks because `AppSettings.getDefaultInstance()` returns empty strings for both.

---

## G) Model discovery context

### G1. On-disk model location

- `Model.getPath(context, fileName = downloadFileName): String` (`Model.kt:105-132`). Resolution order: `imported` → external files dir + fileName; `localModelFilePathOverride` → absolute override; `localFileRelativeDirPathOverride` → external files dir + relDir + fileName; default → external files dir + `normalizedName` + `version` + (optional `unzipDir`) + fileName.
- All paths go through `context.getExternalFilesDir(null)` — **external**, not `filesDir`. Models are not app-private in the strict sense (app-specific external storage, no other app can read but user file managers can).

### G2. `models_meta` tracking

**Not implemented as a DataStore entity today.** The `models_meta` table described in `architecture.md:222-227` is a Phase-3 concept. Today:
- Allowlist parsing produces `List<AllowedModel>` → `List<Model>` (`AllowlistLoader.load()`).
- `DefaultModelRegistry._models: MutableStateFlow<List<ModelEntry>>` holds the in-memory list with download + init status.
- Only user overrides (the 7-field `PerModelSettings`) are persisted; everything else is recomputed from the bundled asset at each app start.

### G3. Intent / URI flows in Model Manager

`ModelManagerScreen.kt` — read in full above. No Intent handling. Only buttons: Download / Cancel / Retry / Load (which emits `NavEvent.OpenChat`). No "Import model from URI" flow in Phase 2.

### G4. `DownloadWorker` + `DefaultDownloadRepository`

`DownloadRepository.kt:137-210` — `observerWorkerProgress` uses `workManager.getWorkInfoByIdLiveData(workerId).observeForever { ... }`. The `observeForever` leak is a known inherited Gallery issue documented in task logs (see `DefaultModelRegistry.kt:131-135` comment).

---

## H) Room readiness

### H1. Dependencies

`gradle/libs.versions.toml` (read in full) — **Room is NOT declared.** No `roomRuntime`, no `roomCompiler`, no `roomKtx`. The catalog currently has:
- `datastore = 1.1.7`, `protobuf = 4.28.3` — Proto DataStore.
- `androidx.navigation-compose = 2.8.9`.
- No persistence SQL dependency at all.

### H2. Database open code

`SanctumApplication.onCreate` (line 9-13) is empty beyond the MainActivity FQN string. No database bootstrap. No `Room.databaseBuilder(...)` anywhere.

### H3. `app/schemas/` folder

- `Glob app/schemas/*` → **No files found.** The folder does not exist yet.
- `.gitignore:33-35` already has the Room-aware rule:
  ```
  # Room — locally generated schema snapshots (shared schemas live in VCS)
  app/schemas/*.local.json
  ```
- This means canonical schema JSONs produced by the Room compiler (e.g., `app/schemas/app.sanctum.machina.data.SanctumDatabase/1.json`) **are intended to be tracked in git** — only `*.local.json` variants are ignored. Good.

### H4. Room-testing

No `androidx.room:room-testing` in the catalog; needs to be added in Phase 3. `:app/src/androidTest/` directory does not exist yet (only `:app/src/test/` for unit tests).

---

## I) ErrorLog component whitelist

Full enum — `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt:32-43`:
```kotlin
internal val ALLOWED_COMPONENTS: Set<String> = setOf(
  // Phase 1
  "download",
  "inference-init",
  "inference",
  "inference-cleanup",
  // Phase 2 (D27)
  "settings-io",
  "camera",
  "audio",
  "attachment-decode",
)
```

Runtime enforcement: `require(component in ALLOWED_COMPONENTS) { ... }` at line 70. Unknown → `IllegalArgumentException`.

**Phase 3 components to add** (driven by architecture.md:27 plus spec scope):
- `"history-read"` — Room query / DAO failure when loading chats/messages.
- `"history-write"` — Room insert/update/delete failure.
- `"attachment-save"` — writing an attachment file into `filesDir/attachments/{chatId}/` or `filesDir/quick/`.
- `"attachment-read"` — reading a stored attachment from disk (paired with `attachment-decode` for in-memory decode).
- Possibly `"engine-warmup"` — dedicated tag for application-level warmup failures, distinct from `"inference-init"` which stays for per-chat init attempts.

Adding each requires: (a) editing `ALLOWED_COMPONENTS` in ErrorLog.kt, (b) updating `patterns.md` under "ErrorLog component strings", (c) a unit test in `ErrorLogTest` (see `core-runtime/src/test/kotlin/app/sanctum/machina/core/log/ErrorLogTest.kt`). The pattern is mechanical.

---

## J) Risks and hazards

### J1. Concurrency on `ChatViewModel._messages`

Today `_messages` is a `MutableStateFlow<List<Message>>` written only from `viewModelScope` (Main dispatcher in prod; Test dispatcher in tests). `helper.runInference` dispatches its callbacks back onto `viewModelScope` for VM state mutations via `updateLastAssistant { ... }`. No backpressure because everything is a `MutableStateFlow` with `update {}`.

**Phase 3 risk:** Moving to `Flow<List<Message>> = dao.observeByChat(chatId)` means:
- Every DB write re-emits the full list → potentially many hundreds of rows re-allocated per emission.
- `LazyColumn(items(messages))` re-diffs on every emission.
- Streaming assistant text generates tens of emissions per second. Solution: keep the in-memory streaming StateFlow *in parallel* with the Room-sourced flow — Room stores only committed messages (USER done + ASSISTANT done-on-stream-complete), while the in-progress assistant bubble stays in-memory until `done = true` (matches today's streaming pattern). Combine the two flows with a `combine { persistedList + inProgressMessage ?: emptyList }`.

### J2. "Exactly one chat at a time" assumptions

Several spots assume a single-chat session:
- `ChatViewModel.modelName` is immutable per VM instance (`ChatViewModel.kt:65`).
- `ChatViewModel.onCleared()` (line 531) always calls `registry.cleanup(modelName)` — works today because leaving the chat destroys the VM. In Phase 3 with Application-scope engine, this tears down warmup. **Must be removed or made conditional.**
- Navigation route `chat/{modelName}` (`SanctumApp.kt:27`) — re-navigating to the same `modelName` re-uses the destination. Needs to change to `chat/{chatId}` so distinct chats on the same model open distinct destinations.
- `LlmChatModelHelper.cleanUpListeners: MutableMap<String, CleanUpListener>` (line 57) is keyed by `model.name` — one listener per model at any time. Compatible with "one engine at a time" but the map structure suggests multi-model awareness it does not actually have.

### J3. Rotation / process-death assumptions that break with Room

Today the VM loses messages on:
- Process death (documented).
- Rotation — the VM survives, but `onCleared` fires if the back-stack entry is destroyed in the process.

In Phase 3 with persistent chats:
- Messages MUST survive process death. That is the whole point.
- Rotation: rotation keeps the VM (`SavedStateHandle.get(NAV_ARG_CHAT_ID)` equivalent), so no additional care needed beyond reading from Room on init.
- Quick chats: MUST NOT survive process death. The `QuickChatSession` (not yet modelled; see architecture.md § Quick chats) is application-scoped in-memory; the purge-on-start hook in `SanctumApplication` cleans `filesDir/quick/` on every app launch. Rotation inside a quick chat must not trigger purge — only full process death / explicit "leave session" actions should purge.

### J4. Foreign-key concerns at chat deletion

- `architecture.md:252` — `messages.chat_id → chats.id ON DELETE CASCADE`.
- A chat deletion with an active stream is possible if user deletes a chat from the drawer while the assistant is still writing. Today there is no such path (no drawer, no delete). In Phase 3:
  - Need to stop the stream (`helper.stopResponse(model)`) before the DELETE executes.
  - Need to handle the case where the user deletes the chat that is *currently open*: nav should pop back to home after delete; if the deleted chat was generating, show a toast "Generation cancelled, chat deleted".
  - Cascade delete with thousands of messages is fine in SQLite; the real risk is the ASSISTANT's in-progress message update landing AFTER the DELETE — the `onMessage` callback in `LlmChatModelHelper.kt:257-263` fires on an engine-internal thread. Stop-before-delete + `_messages.lastOrNull()?.interrupted == true` short-circuit (already present at `ChatViewModel.kt:181`) mitigates.
- Orphaned attachment files: if Room deletes a chat row but `filesDir/attachments/{chatId}/` is not cleaned, files leak. Must pair DELETE with `File(filesDir, "attachments/$chatId").deleteRecursively()`.

### J5. `model.instance` race

The `var instance: Any?` on `Model` (`Model.kt:85`) is the anti-pattern called out in `patterns.md:30`. In Phase 2 this is serialised by `lifecycleMutex`. In Phase 3 with warmup on application start AND cross-model reopen coordinated via a confirmation dialog, there is additional pressure on this invariant. The `releaseEngine` stale-instance guard (`DefaultModelRegistry.kt:246-265`) is defensive but mutex-holding callers are the norm.

### J6. `QuickChatSession` must not leak a chatId into Room

- `architecture.md:135` — explicit rule. Needs a type-level separation: e.g., `sealed class ChatIdentity { data class Persistent(val id: Long); object Quick }` so `MessageDao` signatures can only accept `Persistent.id`. Phase 3 review checkpoint.

### J7. Warmup cancellation vs user-initiated chat open

If warmup is in flight on the default model when the user taps a drawer entry for a *different* model:
- The warmup Job must be cancellable and `lifecycleMutex` must not deadlock.
- `DefaultModelRegistry.initialize(modelName)` already holds `lifecycleMutex.withLock { ... }` across the whole init. Starting a second `initialize` on a different model while the first is running will **suspend on the mutex**, not cancel the first. The UX that "cross-model open shows confirmation before opening" actually helps — the confirmation gives the warmup time to either complete or be explicitly torn down before the second call is issued.
- Still need: an API like `registry.cancelInitialize(modelName)` or simply let the existing init finish (fast path since engine ends up Ready anyway, and the follow-up `cleanup` then tears it down).

### J8. Optimistic-UI loading: state-machine additions

`ChatUiState.Ready(val isGenerating: Boolean)` (line 37) is too coarse for "chat opens while engine warming". Options:
- Add `ChatUiState.Ready(isGenerating, isEngineReady)` or a separate flag. Simpler: keep the sealed class narrow and add a parallel `engineReady: StateFlow<Boolean>` that `MultimodalInputBar` reads to grey out Send.
- The engine-ready signal should come from `ModelRegistry` — e.g., a `StateFlow<Map<ModelName, ModelInitStatus>>` derived from `models`. `ModelInitStatus.Ready | Initializing | Idle | Failed` already exists (`DefaultModelRegistry.kt:188, 201, 206, 216`).

---

## Phase 3 Implementation Hazards — top risks

1. **`ChatViewModel.onCleared()` tears down the engine on every back-navigation.** Currently at `ChatViewModel.kt:531-536`. With Application-scope warmup, this kills the warm engine the moment the user enters then exits their first chat. **Must be gated** on "the VM is truly the last consumer" (a refcount, or owning the engine at the Application layer and having the VM only *observe* it). High impact; low technical effort once decided.

2. **`Model.name` vs `Model.modelId` split.** Known drift from Phase 2 decisions.md Task 11/12. Room's `chats.model_id` is specified as the stable HF id (`architecture.md:208`). `PerModelSettings` today is keyed by `Model.name`. Phase 3 must migrate both (plumb `modelId` onto `Model`, rewrite DataStore keys on first boot). A migration utility + test, plus plumbing changes across `DefaultModelRegistry`, `ChatViewModel`, `AppSettingsRepository` call sites. Medium effort, high blast-radius.

3. **Room emissions vs streaming assistant text.** `Flow<List<Message>>` re-emits the full list on every insert; streaming tokens appear at 10–50 Hz. Naive: one DB write per token, app becomes I/O-bound. Solution (described in J1): parallel in-memory streaming state + DB write only on `done = true`. Needs a careful composite Flow that renders in-progress ASSISTANT above the persisted list.

4. **Attachment file lifecycle.** Net-new code. `filesDir/attachments/{chatId}/` needs: write-on-send (PNG compress + file write, off-Main because `Bitmap.compress(PNG, 100, ...)` takes 200–600 ms per 1024² image — the same perf fix already applied to `LlmChatModelHelper.runInference` at `:297` confirms the pattern), atomic rename for crash-safety (temp-file → rename to final path), `deleteRecursively` on chat delete, `filesDir/quick/` purge on `SanctumApplication.onCreate`. Room `messages.image_path` stores the relative path. No existing helper — all new. Four new `ErrorLog` components need to be added (`history-read`, `history-write`, `attachment-save`, `attachment-read` or similar).

5. **Navigation redesign ripple.** Route `chat/{modelName}` → `chat/{chatId}` (+ optional `{kind}`). Every caller: `SanctumApp.kt:21-22` (drawer click handler new), `ChatViewModel.kt:65` (reads chatId instead of modelName, derives modelName from `chat.model_id` DAO read), `ChatViewModel.NAV_ARG_MODEL_NAME` → `NAV_ARG_CHAT_ID`. `ModelManagerScreen`'s `onLoad(modelName)` behaviour changes — "Load" now must: (a) create a new quick-chat ID or reopen last persistent chat for that model, (b) prompt if cross-model conflict, (c) navigate.

6. **Cross-model reopen confirmation BEFORE open.** The confirmation is a pre-navigation side effect, not a destination side effect. It lives in the drawer click handler (or home-screen handler) and in `ModelManagerScreen.onLoad`. Both need to consult "what model is currently warmed up" — a new read accessor on `ModelRegistry`, e.g., `activeModelName(): String?`. Not hard, but requires UI-flow thinking: confirm dialog over which screen? During which Nav transition?

7. **Warmup failure + optimistic UI.** If warmup fails (e.g., GPU+CPU both fail from `DefaultModelRegistry.kt:207`), the user is already inside an optimistically-opened ChatScreen with no engine. `ChatUiState.Failed(cause)` path exists (line 81 of ChatScreen.kt) but today fires from `ChatViewModel.init`. In Phase 3, the VM needs to observe the Registry's engine-ready state and transition to `Failed` if registry fails, while still showing persisted message history. This is the first time "I can read the chat but can't reply" becomes a user-visible state.

8. **Default-model resolution order without a persisted settings-default.** The three-step rule "settings-configured default → last-used fallback → route to Model Manager" needs (a) a new `default_model_id` field in `AppSettings` (trivial proto addition — F3 above), (b) a new `last_used_model_id` field or derive it from the newest chat's `model_id` in Room (Room-derived is cheaper). Add Model Manager route-guard: if no model is downloaded, `SanctumApplication` warmup skips, and the home hub's "New quick chat" button becomes "Download a model first" that navigates to `model_manager`.

---

## Appendix: quick reference of key file paths

- Entry point / nav: `C:\AI-WORK\PhoneWrap\app\src\main\kotlin\app\sanctum\machina\ui\SanctumApp.kt`
- App shell: `C:\AI-WORK\PhoneWrap\app\src\main\kotlin\app\sanctum\machina\SanctumApplication.kt`
- Chat VM (Phase 3 rework epicentre): `C:\AI-WORK\PhoneWrap\app\src\main\kotlin\app\sanctum\machina\ui\chat\ChatViewModel.kt`
- Chat Screen: `C:\AI-WORK\PhoneWrap\app\src\main\kotlin\app\sanctum\machina\ui\chat\ChatScreen.kt`
- Engine registry: `C:\AI-WORK\PhoneWrap\core-runtime\src\main\kotlin\app\sanctum\machina\core\registry\DefaultModelRegistry.kt`
- Engine wrapper: `C:\AI-WORK\PhoneWrap\core-runtime\src\main\kotlin\app\sanctum\machina\core\inference\LlmChatModelHelper.kt`
- Engine interface: `C:\AI-WORK\PhoneWrap\core-runtime\src\main\kotlin\app\sanctum\machina\core\runtime\LlmModelHelper.kt`
- Settings repo: `C:\AI-WORK\PhoneWrap\core-settings\src\main\kotlin\app\sanctum\machina\core\settings\DefaultAppSettingsRepository.kt`
- Proto schema: `C:\AI-WORK\PhoneWrap\core-settings\src\main\proto\app_settings.proto`
- ErrorLog: `C:\AI-WORK\PhoneWrap\core-runtime\src\main\kotlin\app\sanctum\machina\core\log\ErrorLog.kt`
- Attachment types: `C:\AI-WORK\PhoneWrap\app\src\main\kotlin\app\sanctum\machina\ui\chat\Attachment.kt`
- Media helpers (decode, pcmToWav): `C:\AI-WORK\PhoneWrap\core-runtime\src\main\kotlin\app\sanctum\machina\core\common\MediaUtils.kt`
- Version catalog: `C:\AI-WORK\PhoneWrap\gradle\libs.versions.toml`
- App Gradle config: `C:\AI-WORK\PhoneWrap\app\build.gradle.kts`
- Manifest: `C:\AI-WORK\PhoneWrap\app\src\main\AndroidManifest.xml`
- .gitignore (Room rule): `C:\AI-WORK\PhoneWrap\.gitignore`
