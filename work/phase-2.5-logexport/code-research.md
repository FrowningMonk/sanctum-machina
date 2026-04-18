# Code Research: phase-2.5-logexport

Created: 2026-04-18
Scope: Log export + crash reporting hotfix between Phase 2 and Phase 3.

---

## 1. ErrorLog Integration

### 1.1 The `ErrorLog` class

`C:/AI-WORK/PhoneWrap/core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`

- `@Singleton class ErrorLog @Inject constructor(@ApplicationContext context: Context)` (lines 64-65).
- Public API: `suspend fun e(component: String, description: String, cause: Throwable? = null)` (line 69). Suspend — routes IO through `Dispatchers.IO` under a `Mutex` (lines 67, 73-74).
- Whitelist `ALLOWED_COMPONENTS` (lines 32-43): `download`, `inference-init`, `inference`, `inference-cleanup`, `settings-io`, `camera`, `audio`, `attachment-decode`. Unknown component → `IllegalArgumentException` at call site (line 70-72).
- Format (line 49): `ERROR [component] description :: CauseType: cause.message`. Truncation: description 500 chars, cause message 200 chars, control whitespace `[\n\r\t]` → space.
- Storage path: `${context.filesDir}/logs/errors.log` (constants `LOG_DIR = "logs"`, `LOG_FILE = "errors.log"`, `ROTATED_FILE = "errors.log.1"`, lines 17-19). Rotation on >2 MB (`MAX_LOG_BYTES`, line 16): rename to `errors.log.1`, overwriting any previous rotated copy.
- IO errors in `e()` are intentionally swallowed (lines 85-89) — "Logger must never fail its caller."

### 1.2 Every call site of `ErrorLog.e(...)` / `errorLog.e(...)` in production sources

| File (under `src/main/`) | Line | Component | Context |
|---|---|---|---|
| `core-runtime/.../core/registry/DefaultModelRegistry.kt` | 100 | `download` | `"startup scan failed"` (init block runCatching) |
| `core-runtime/.../core/registry/DefaultModelRegistry.kt` | 108 | `download` | `"allowlist load failed"` (refreshAllowlist failure) |
| `core-runtime/.../core/registry/DefaultModelRegistry.kt` | 193 | `inference-init` | GPU init failure (`"GPU init failed: $err1"`, no cause) |
| `core-runtime/.../core/registry/DefaultModelRegistry.kt` | 205 | `inference-init` | CPU fallback init failure (`"CPU init failed: $err2"`, no cause) |
| `core-runtime/.../core/registry/DefaultModelRegistry.kt` | 298 | `download` | per-entry startup scan failure (`"scan ${entry.model.name} failed"`) |
| `core-runtime/.../core/registry/DefaultModelRegistry.kt` | 316 | `download` | `"resume ${entry.model.name} failed"` (resume of WorkManager download) |
| `core-settings/.../core/settings/DefaultAppSettingsRepository.kt` | 26 | `settings-io` | `.catch` on DataStore.data IOException |
| `core-settings/.../core/settings/DefaultAppSettingsRepository.kt` | 45 | `settings-io` | save IOException |
| `core-settings/.../core/settings/DefaultAppSettingsRepository.kt` | 55 | `settings-io` | reset IOException |
| `app/.../ui/chat/ChatViewModel.kt` | 211 | `inference` | `helper.runInference` onError (mid-stream) |
| `app/.../ui/chat/ChatViewModel.kt` | 412 | `inference-init` | `applyHeavySetting` reinit failure, with cause |
| `app/.../ui/chat/ChatViewModel.kt` | 467 | `attachment-decode` | per-URI decode failure from `addImages` (loop) |
| `app/.../ui/chat/ChatViewModel.kt` | 516 | `camera` | `reportCameraError(description, cause)` from `CameraBottomSheet` |
| `app/.../ui/chat/ChatViewModel.kt` | 527 | `audio` | `reportAudioError(description, cause)` from `AudioRecorderBottomSheet` |

`ImageDecoder.kt` only references `ErrorLog` in a KDoc comment (line 23) — no actual call.

Important: `ErrorLog.e` is `suspend`. Every call site above wraps it in a coroutine scope — `viewModelScope.launch { errorLog.e(...) }` or is already inside a `suspend` function. Any new caller (e.g. from a UI dialog) must do the same.

### 1.3 `filesDir` usage pattern

Only two production consumers touch `context.filesDir`:

- `ErrorLog.kt:77` — `File(context.filesDir, LOG_DIR).apply { mkdirs() }`.
- `core-settings/.../core/settings/di/CoreSettingsModule.kt:40` — `File(context.filesDir, DATASTORE_FILE).also { it.parentFile?.mkdirs() }` where `DATASTORE_FILE = "datastore/app_settings.pb"` (line 19).

Pattern to reuse: accept `@ApplicationContext context: Context` via Hilt constructor injection, derive `File(context.filesDir, subdir)`, `mkdirs()` defensively. The log folder layout `LogExportManager` should read is therefore:

```
<filesDir>/logs/errors.log        # live
<filesDir>/logs/errors.log.1      # optional rotated predecessor
<filesDir>/logs/crash.log         # NEW — the uncaught-exception handler writes here
```

No existing reader abstraction; `LogExportManager` will be the first reader.

---

## 2. About Screen

`C:/AI-WORK/PhoneWrap/app/src/main/kotlin/app/sanctum/machina/ui/about/AboutScreen.kt` — 99 lines, single composable `AboutScreen(onBack: () -> Unit)`.

- **TopAppBar:** `Scaffold { topBar = TopAppBar(title = Text(stringResource(R.string.about_title)), navigationIcon = IconButton(onClick = onBack) { Icon(ArrowBack, ...) }) }` (lines 52-65). No `actions = { ... }` yet — the "Сохранить лог" button can either land in `actions` (icon/text) or as an item inside the scrollable column.
- **Content layout:** `Column(verticalScroll, spacedBy(16.dp)) { SafeMarkdown(text = markdown); HorizontalDivider(); AboutFooter() }` (lines 67-78).
- **Asset loading:** `produceState` + `withContext(Dispatchers.IO) { context.assets.open("about.md").bufferedReader().use { it.readText() } }`, with `R.string.about_load_failed` fallback on `IOException` (lines 42-50). Hardcoded asset name — D17 path-traversal note in the comment (line 33).
- **Version rendering (`AboutFooter`, lines 82-99):**
  - `BuildConfig.VERSION_NAME` (line 84) — this is the 7-tap host candidate.
  - Rendered as `Text(stringResource(R.string.about_version_format, versionName), style = MaterialTheme.typography.bodySmall, color = onSurfaceVariant)` (lines 88-92).
  - Fallback `R.string.about_version_unknown` when blank (line 86).
  - Below it: `R.string.about_attribution` text (line 94).
- **`about.md`** (`C:/AI-WORK/PhoneWrap/app/src/main/assets/about.md`) — 28 lines of Markdown: title, "Что умеет", "Приватность" (claims no telemetry, backup off), "Атрибуция" (Gallery / Gemma / LiteRT-LM), "Обратная связь".
- **Navigation to AboutScreen** — in `SanctumApp.kt:37-39`: `composable("about") { AboutScreen(onBack = { navController.popBackStack() }) }`. Entry point: `ModelManagerScreen` TopAppBar `IconButton(onClick = onAbout)` with `Icons.Outlined.Info` (`ModelManagerScreen.kt:59-66`).

### 2.1 Relevant `strings.xml` keys already present

`C:/AI-WORK/PhoneWrap/app/src/main/res/values/strings.xml` lines 96-100: `about_title`, `about_version_format`, `about_version_unknown`, `about_attribution`, `about_load_failed`. `btn_close` at line 25. **Missing and to be added** for phase-2.5: strings for "Сохранить лог", "Лог сохранён", "Не удалось сохранить лог", "Приложение аварийно завершилось", crash-report body, dev-mode toast, restart-banner text.

---

## 3. Start Screen + NavHost

`C:/AI-WORK/PhoneWrap/app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt` — 41 lines, single composable `SanctumApp()`.

```
NavHost(navController, startDestination = "model_manager") {
    composable("model_manager") { ModelManagerScreen(onLoad, onAbout) }
    composable("chat/{modelName}", ...) { ChatScreen(modelName, onBack) }
    composable("about") { AboutScreen(onBack) }
}
```

- **First screen at launch** — `ModelManagerScreen` (`C:/AI-WORK/PhoneWrap/app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt`). Phase 1/2 legacy — list of allowlist models with Download / Load cards. **This is the single logical host for the restart-banner** — it is the first composable the user sees after every cold start. The banner can be inserted either:
  1. inside the `Scaffold` `topBar` slot below the existing `TopAppBar`, or
  2. as a `LazyColumn` header item above model cards, or
  3. as a `SnackbarHost` on `ModelManagerScreen` (ModelManagerScreen currently has no `SnackbarHost`).
- `ModelManagerScreen.kt` itself has no persistent-state hosting yet; the banner should be driven by a Hilt-injected service that reads a flag in `filesDir` set by the crash process on the previous run and cleared when the banner is dismissed / "Сохранить лог" is tapped.

---

## 4. `SanctumApplication` and Lifecycle

`C:/AI-WORK/PhoneWrap/app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt` — 13 lines.

```kotlin
@HiltAndroidApp
class SanctumApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DefaultDownloadRepository.mainActivityFqn = "app.sanctum.machina.MainActivity"
    }
}
```

- Places to wire `Thread.setDefaultUncaughtExceptionHandler` — immediately after `super.onCreate()` **before** the `mainActivityFqn` assignment (so that a crash inside that assignment is still captured). Must also be installed when the `:crash` process boots (Application.onCreate runs in every process that has the application-class name; guard by `ActivityManager.getRunningAppProcesses()` or by checking `Application.getProcessName()` / `ActivityThread.currentProcessName()` to avoid installing recursive handlers inside the crash process).
- Hilt graph construction itself can throw — but `@HiltAndroidApp` installs the component in `attachBaseContext` / super.onCreate before our override runs. Risk: Hilt's own generated code can throw `IllegalStateException` if a missing binding surfaces at runtime — but only when the first `@AndroidEntryPoint` wakes up (MainActivity), not in `onCreate`.
- `DefaultDownloadRepository.mainActivityFqn` is a public `lateinit` / mutable-static field (documented in `patterns.md` as "Cross-module FQN plumbing via companion fields"). Any exception thrown here would never get caught by our handler — it must be installed on the line before.

### 4.1 `MainActivity`

`C:/AI-WORK/PhoneWrap/app/src/main/kotlin/app/sanctum/machina/MainActivity.kt` — 40 lines.

- `@AndroidEntryPoint class MainActivity : ComponentActivity()`.
- `onCreate`: optional POST_NOTIFICATIONS request on SDK 33+ (lines 24-32), then `setContent { SanctumTheme { SanctumApp() } }` (lines 34-38).
- No Intent handling, no deep links — so adding a dev-gesture or banner visibility does not conflict with anything. `onCreate` is where the restart-banner signal can be read from the injected service and passed into `SanctumApp()` as a parameter or collected from a Hilt-provided `StateFlow`.

---

## 5. Manifest State

### 5.1 `app/src/main/AndroidManifest.xml` (53 lines)

- Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `CAMERA`, `RECORD_AUDIO`.
- No logcat permission needed (`READ_LOGS` is system-only since API 16 for non-system apps; `logcat --pid=ourpid` works without it — see §12).
- `<application>` attrs: `android:name=".SanctumApplication"`, `allowBackup="false"`, `dataExtractionRules="@xml/data_extraction_rules"`, `usesCleartextTraffic="false"`, plus icon/label/theme (lines 21-29).
- Activities currently declared: only `.MainActivity` as LAUNCHER (lines 38-45).
- Services: WorkManager's `SystemForegroundService` with `tools:node="merge"` (lines 47-51).
- **Insertion point for `<activity android:name=".CrashReportActivity" android:process=":crash" ... />`:** inside `<application>`, next to the `<activity android:name=".MainActivity">` block. Must set `android:exported="false"`, pick a theme that does not require Hilt (see §8), and ideally `taskAffinity=""` + `excludeFromRecents="true"` so the crash dialog does not pollute the recents stack. No intent-filter — it is launched by explicit `Intent(context, CrashReportActivity::class.java)`.

### 5.2 `core-runtime/src/main/AndroidManifest.xml` (13 lines)

- Declares `POST_NOTIFICATIONS` and a merged `SystemForegroundService` for library-lint hygiene only (documented pattern in `patterns.md § Library-module manifest hygiene`). Nothing to change here for phase-2.5.

---

## 6. `app/build.gradle.kts` and Dependencies

`C:/AI-WORK/PhoneWrap/app/build.gradle.kts` — 82 lines.

- `applicationId = "app.sanctum.machina"`, `namespace = "app.sanctum.machina"` (lines 10, 14).
- `minSdk = 31` (Android 12), `targetSdk = 35`, `compileSdk = 35`. versionCode = 1, versionName = "0.1.0" (lines 15-18).
- **BuildConfig fields** available (lines 20-24): `BuildConfig.MAIN_ACTIVITY_CLASS_NAME = "app.sanctum.machina.MainActivity"`, plus Gradle's auto-generated `BuildConfig.VERSION_NAME` / `BuildConfig.VERSION_CODE` / `BuildConfig.APPLICATION_ID` / `BuildConfig.DEBUG`. `buildFeatures { buildConfig = true }` (line 29) so BuildConfig is enabled. `versionCode` + `DEBUG` can be surfaced directly in the log header without adding new fields.
- **Dependencies relevant to the feature:**
  - `libs.androidx.compose.material3` via `platform(libs.androidx.compose.bom)` — composeBom = `2026.03.00` (libs.versions.toml line 7). Material 3 at this BOM version **does not** ship a dedicated `Banner` composable (Material 3 still omits Banner as of 2025+). Implement the restart-banner as a `Card` with a `Row { Icon, Text, TextButton, IconButton }` — or reuse `Snackbar` with an indefinite duration.
  - `libs.androidx.compose.material.icons.extended` — icons for download / close / error available.
  - `libs.hilt.android` 2.57.1, `libs.hilt.navigation.compose` 1.3.0 — standard Hilt stack.
- **Test deps** (lines 77-81): `libs.junit` 4.13.2, `libs.robolectric` 4.12, `libs.androidx.test.core` 1.6.1, `libs.kotlinx.coroutines.test` 1.10.2, `libs.litertlm`. `testOptions.unitTests.isIncludeAndroidResources = true` (line 43) — enables Robolectric to resolve `R.string.*`.
- **Kotlin / JVM target:** `VERSION_11`, `-Xcontext-receivers` (lines 32-40). Nothing in phase-2.5 needs jvm11+ APIs; straight `File.readLines` / `File.appendText` works.

---

## 7. Compose UI Patterns Already Present

### 7.1 Two-button confirm/dismiss dialog — `HeavyChangeDialog.kt` (37 lines)

`app/src/main/kotlin/app/sanctum/machina/ui/chat/HeavyChangeDialog.kt`:

```
AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(R.string.heavy_change_dialog_title) },
    text = { Text(R.string.heavy_change_dialog_body) },
    confirmButton = { TextButton(onClick = onConfirm) { Text(R.string.btn_apply) } },
    dismissButton  = { TextButton(onClick = onDismiss) { Text(R.string.btn_cancel) } },
)
```

This is the template the CrashReportActivity's two-button layout can imitate, though CrashReportActivity is a full-screen `ComponentActivity` so it uses `Scaffold` + `Button`s, not `AlertDialog`.

### 7.2 Non-cancellable progress dialog — `ReinitProgressDialog.kt` (42 lines)

Uses `DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)` — pattern usable if a "Saving log…" progress indicator is wanted while the SAF copy runs.

### 7.3 Snackbar pattern — `ChatScreen.kt`

Lines 30-32, 68-73, 258, 289-324. The canonical pattern:

```
val snackbarHostState = remember { SnackbarHostState() }
val resources = LocalResources.current
LaunchedEffect(viewModel, resources) {
    viewModel.snackbar.collect { stringRes -> snackbarHostState.showSnackbar(resources.getString(stringRes)) }
}
...
Scaffold(snackbarHost = { SnackbarHost(hostState = snackbarHostState) }, ...)
```

ViewModel side (`ChatViewModel.kt:94`): `private val _snackbar = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = 8); val snackbar: SharedFlow<Int> = _snackbar.asSharedFlow()`. `_snackbar.tryEmit(R.string.xxx)` — non-suspending. Snackbar with action (for "Открыть настройки" style, `ChatScreen.kt:289-294`): `showSnackbar(message, actionLabel, SnackbarDuration.Long)` returns `SnackbarResult.ActionPerformed` / `Dismissed`.

No existing "Banner"-style component. Create one in phase-2.5 as a simple `Card` + `Row { Icon, Text, TextButton }`.

### 7.4 Navigation

`androidx.navigation:navigation-compose` 2.8.9 + `androidx.hilt:hilt-navigation-compose` 1.3.0. Routes are plain strings (`"model_manager"`, `"about"`, `"chat/{modelName}"` with a `navArgument(NavType.StringType)`). NavHost is declared in the single top-level composable `SanctumApp`.

### 7.5 SAF launcher pattern (reference)

`CameraBottomSheet.kt:11,127` and `MultimodalInputBar.kt:4,83` use `rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> ... }` / `RequestPermission`. **No existing use of `ActivityResultContracts.CreateDocument`** — phase-2.5 introduces it. Inside `AboutScreen`/`CrashReportActivity` it would look like:

```
val createDocLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.CreateDocument("text/plain")
) { uri: Uri? -> if (uri != null) viewModel.writeLogTo(uri) }
```

`createDocLauncher.launch("sanctum-log-${yyyyMMdd-HHmm}.txt")` — the argument is the suggested filename.

---

## 8. Hilt Scopes

- `@HiltAndroidApp` on `SanctumApplication` (line 7) — single entry. Hilt creates one `SingletonComponent` per OS-level process.
- `@AndroidEntryPoint` on `MainActivity` (line 15).
- `@HiltViewModel` on `ChatViewModel` (line 52) and `ModelManagerViewModel`.
- Hilt modules currently in the project:
  - `CoreRuntimeModule` (`core-runtime/.../core/di/CoreRuntimeModule.kt`) — `DownloadRepository`, `LlmModelHelper`, `ModelRegistry`.
  - `CoreSettingsModule` (`core-settings/.../di/CoreSettingsModule.kt`) — `AppSettingsRepository` + `DataStore<AppSettings>`.
  - `ImageDecoderModule` (`app/.../ui/chat/ImageDecoder.kt:40-46`) — inline `@Module abstract class` in the same file.

### 8.1 The `:crash` process subtlety

Android creates a **separate process** for every component that declares `android:process=":name"`. That process has its own JVM, its own `Application.onCreate` call, and its own Hilt `SingletonComponent` — fresh `ErrorLog`, fresh `DataStore`, fresh everything. Consequences:

- `SanctumApplication.onCreate` runs again in the `:crash` process. The uncaught-exception handler installed there would re-trigger the crash flow recursively if another crash occurred. Guard: check the process name early in `onCreate` (e.g. `Application.getProcessName()` on API 28+ — we are min 31 so unconditional use is fine) and **skip** the heavy initialisation (DownloadRepository companion field, uncaught-exception handler) in the `:crash` process. Or: install a minimal handler that only kills the process without writing crash.log recursion.
- `CrashReportActivity` in `:crash` can still be `@AndroidEntryPoint` — its own fresh Hilt graph. But: injected singletons are **not shared** with the main process (e.g. `ErrorLog` in `:crash` would point to the same `filesDir` but would be a different instance). That is fine for reads; for `LogExportManager` it is fine since the filesystem is the shared medium.
- Simpler alternative: `CrashReportActivity` can skip Hilt entirely — it only needs `Context` + raw `File` access + SAF. Gallery-source has nothing to reference (grep confirmed no `android:process` and no crash reporting in `gallery-source/`). Given the activity does SAF + two buttons + read one text file, **not using Hilt in `:crash` is the simpler path**.

---

## 9. Test Infrastructure

### 9.1 What exists

```
C:/AI-WORK/PhoneWrap/app/src/test/kotlin/app/sanctum/machina/ui/chat/
  AudioRecorderBottomSheetTest.kt
  CameraBottomSheetTest.kt
  ChatViewModelTest.kt
  EffectiveConfigTest.kt
  SafeUriHandlerTest.kt

C:/AI-WORK/PhoneWrap/core-runtime/src/test/kotlin/app/sanctum/machina/core/
  common/{AudioClipTest.kt, MediaUtilsPureTest.kt, MediaUtilsTest.kt, MultimodalContentsBuilderTest.kt}
  log/ErrorLogTest.kt
  registry/{AllowlistLoaderTest.kt, SystemInstructionTest.kt}

C:/AI-WORK/PhoneWrap/core-settings/src/test/kotlin/app/sanctum/machina/core/settings/
  AppSettingsRepositoryTest.kt
```

- No `androidTest` / instrumentation suites — explicitly deferred (`CameraBottomSheetTest.kt:24-27` KDoc). Compose-ui-test is not on the classpath.
- No Robolectric setup for `:app` activities beyond bottom-sheet helpers.

### 9.2 Robolectric pattern (canonical example — `ErrorLogTest.kt`, 119 lines)

```
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ErrorLogTest {
  private lateinit var context: Context
  @Before fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    errorLog = ErrorLog(context)
    logFile = File(context.filesDir, "logs/errors.log")
    logFile.parentFile?.deleteRecursively()
  }
  @After fun tearDown() { logFile.parentFile?.deleteRecursively() }
  @Test fun knownComponents_accepted() = runTest { ... errorLog.e("download", "ok") ... }
}
```

Uses `kotlinx.coroutines.test.runTest` for suspend `errorLog.e`. Configured SDK 33 — below the app's min (31) but above SDK target gaps Robolectric tracks. Phase-2.5 tests for `LogExportManager` can mirror this: seed `errors.log`, `errors.log.1`, `crash.log` under `context.filesDir/logs/`, call `manager.buildExport()` (returns String or writes to a caller-provided `OutputStream`), assert the composed text.

### 9.3 Representative signatures

- `ErrorLogTest.knownComponents_accepted()` — iterates the whitelist set.
- `ChatViewModelTest` / `SafeUriHandlerTest` — use `org.junit.Test` + standard JUnit 4. `runTest` for suspend paths. Mocks via hand-rolled fakes (no MockK dependency in `libs.versions.toml`).

### 9.4 What is NOT covered

- No UncaughtExceptionHandler tests in the repo (new surface).
- No SAF `CreateDocument` tests (new surface).
- No `Runtime.exec` / logcat tests (new surface).

---

## 10. Gallery-Source for Crash Reporting / Log Export

Checked `C:/AI-WORK/PhoneWrap/gallery-source/Android/src/` for any salvageable code:

- `Grep UncaughtExceptionHandler|crash|Crash|ACTION_CREATE_DOCUMENT` → hits only in `strings.xml` ("оценка" strings) and in `ConfigDialog.kt` (unrelated — "crash" used in an agent-skill doc string), and in `skills/featured/mood-music/scripts/index.html` (web asset). **No crash reporter, no log exporter.**
- `Grep android:process` → **no matches**. Gallery does not use isolated-process activities.

Conclusion: nothing to port. The feature is greenfield relative to the reference.

---

## 11. Previous Phase User-Specs (formatting reference)

- `C:/AI-WORK/PhoneWrap/work/completed/phase-1-foundation/user-spec.md` — size L. Russian prose, detailed "Как должно работать" with numbered happy-path scenarios, then validation, then out-of-scope "сценарии, которые должны работать".
- `C:/AI-WORK/PhoneWrap/work/completed/phase-2-ui/user-spec.md` — size L. Sub-cases `US-1..US-7` (US = User Story), each with its own happy-path numbering. Acceptance criteria split "Обязательные" / "Желательные".
- `C:/AI-WORK/PhoneWrap/work/phase-3-history/user-spec.md` — freshest: marked `size: S`, draft, empty template sections. Treat the phase-3 file as the current live skeleton format; phase-2.5 user-spec should follow the same YAML front-matter (`created`, `status`, `type`, `size`) and the same section layout (`Что делаем` / `Зачем` / `Как должно работать` / `Критерии приёмки` / `Ограничения` / `Риски` / `Технические решения` / `Тестирование` / `Как проверить`).

Feature size per interview — **M**. Several components, one new activity, new manager, UI touches in two screens, new manifest declarations.

---

## 12. SAF / logcat Risk Check

### 12.1 SAF `ACTION_CREATE_DOCUMENT`

- `minSdk = 31` (`app/build.gradle.kts:15`). `Intent.ACTION_CREATE_DOCUMENT` was added in **API 19 (KitKat)**. `ActivityResultContracts.CreateDocument(mimeType: String)` is part of `androidx.activity` and is min-SDK-safe. **Zero compatibility risk.**
- MIME type `"text/plain"` — universally accepted by Files/Drive/LocalStorage providers.
- User cancellation returns `null` — must be null-checked.
- `ContentResolver.openOutputStream(uri)` needs `try { } finally { stream.close() }` or `.use { }`.

### 12.2 `Runtime.exec("logcat --pid=<ourpid> *:E")`

- **No permission required** when the process is reading its own pid — `READ_LOGS` is needed only for cross-process log access. Android 16+ did not change this.
- `Process.myPid()` gives the integer pid.
- `ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${Process.myPid()}", "*:E").redirectErrorStream(true).start()` — `-d` dumps then exits (no tail follow). Capture stdout line-by-line, timeout 2-3 s.
- Known pitfalls:
  - Some vendors (Honor/MIUI in paranoid mode) return an empty stream even for own-pid; handle gracefully — `LogExportManager` should emit `"(logcat unavailable)"` instead of failing.
  - On the `:crash` process the pid is **different** from the main process. Logcat of `:crash` pid will be empty (no prior activity). The main-process crash handler must read the main-process pid logcat **before** starting the `:crash` activity; the `LogExportManager` running inside `CrashReportActivity` can only replay what the handler pre-wrote to `crash.log`. This is aligned with the spec: the crash handler writes `crash.log` synchronously, then starts `CrashReportActivity`.
  - From `AboutScreen`'s "Сохранить лог" button (in the main process, live) `--pid=myPid` works normally.
- No additional permissions, no manifest entries, no network. Pure read of own-process signal.

### 12.3 Security / privacy notes consistent with existing patterns

- `about.md` claims "нет телеметрии" / data stays on device. SAF keeps that claim intact — the user picks the destination; the app never uploads.
- `ErrorLog` already sanitises cause.message (200 chars, control ws collapsed). `LogExportManager` should not re-sanitise; it just concatenates. But `crash.log` written by the uncaught-exception handler **will** contain a full stacktrace. Consider mimicking the `ErrorLog` bounding (take the first N frames / N bytes) before writing it, to avoid SELinux/context leakage in the exported file — and document the trade-off in tech-spec.
- `logcat --pid=ourpid` may contain third-party library (litertlm, CameraX, WorkManager) error messages — those can carry device identifiers. For a tester-to-developer channel this is acceptable; for public distribution it would not be.

---

## 13. Constraints & Infrastructure Summary

- **Build:** AGP 8.13.2, Kotlin 2.2.0, KSP 2.3.6, Hilt 2.57.1, Compose BOM 2026.03.00, navigation 2.8.9. Pre-push: `:core-runtime` unit tests (`patterns.md § Security & Quality Gates`). Pre-commit: `gitleaks`.
- **Languages / comments:** user-facing UI strings — Russian (`strings.xml`); code and KDoc — English (per `CLAUDE.md` project instruction).
- **CI/CD:** not configured (mobile app, manual `./gradlew :app:assembleDebug` + `adb install -r`, see `NOTES.md:28`).
- **Env vars:** none. No `.env`, no secrets. HuggingFace OAuth removed in Phase 1 (`NOTES.md:42`).
- **Process model:** today single-process. Adding `android:process=":crash"` is the first split and needs manifest + application-onCreate guards (§4, §8.1).
- **Backup / privacy constraints** (already enforced): `android:allowBackup="false"`, `android:dataExtractionRules="@xml/data_extraction_rules"` (excludes cloud-backup and device-transfer). Exported `.txt` files go outside app sandbox only via the user's SAF choice — compliant.

---

## 14. External Libraries — no new ones required

Everything needed for phase-2.5 is already on the classpath:

- `androidx.activity` (activity-compose 1.10.1) → `ActivityResultContracts.CreateDocument`.
- `androidx.compose.material3` (via BOM 2026.03.00) → `Card`, `Button`, `TextButton`, `Scaffold`, `AlertDialog`, `SnackbarHost`. No Banner composable — build one from `Card` + `Row`.
- `androidx.compose.material.icons.extended` 1.7.8 → icons for download/close/error.
- Kotlin stdlib → `Runtime.getRuntime().exec` / `ProcessBuilder`, `File.appendText`, `java.io.ByteArrayOutputStream`, `java.text.SimpleDateFormat`.
- `android.os.Build` + `android.content.pm.PackageInfo` for device info header.

No WebFetch / WebSearch research required — all APIs used here are stable and long-established.

---

## 15. Suggested File Layout for phase-2.5

(Recommendation based on existing module boundaries — not prescriptive.)

```
app/src/main/kotlin/app/sanctum/machina/
  SanctumApplication.kt                     # MODIFY — install UncaughtExceptionHandler
  crash/
    CrashReportActivity.kt                  # NEW — @Composable, Scaffold, two buttons
    CrashHandler.kt                         # NEW — writes crash.log, spawns :crash activity
    CrashState.kt                           # NEW — injected, exposes "prev-crash?" flag
  logexport/
    LogExportManager.kt                     # NEW — @Singleton, builds the .txt blob
  ui/about/AboutScreen.kt                   # MODIFY — "Сохранить лог" button + 7-tap gesture
  ui/modelmanager/ModelManagerScreen.kt     # MODIFY — restart-banner host
app/src/main/AndroidManifest.xml            # MODIFY — <activity android:process=":crash">
app/src/main/res/values/strings.xml         # MODIFY — new strings
```

- `LogExportManager` lives in `:app` (not `:core-runtime`) because it reads `errors.log` + `crash.log` + logcat + runs SAF — UI-boundary concerns. No cross-module leakage rule violation.
- `CrashHandler` can live in `:app` since `SanctumApplication` is there.
- All new production code in Kotlin with English KDoc, matching existing style.

---

## 16. Open Gaps to Resolve During Tech-Spec

1. Does the restart-banner auto-dismiss after one display, or persist until "Сохранить лог" / "Закрыть" is tapped? (affects `CrashState` persistence — flag file in `filesDir/logs/last-crash.flag` vs DataStore entry).
2. Should `LogExportManager` include `errors.log.1` if it is present, or only `errors.log`? (spec says `(+.1)` — include).
3. Dev-gesture 7-tap — should the resulting test-crash happen on the main thread (`throw RuntimeException()` directly in the onClick) or on a background thread (which would bypass Compose's error-boundary but still be caught by `setDefaultUncaughtExceptionHandler` because that handler is thread-global)? Main-thread crash gives the full end-to-end path — recommended.
4. `crash.log` — single latest crash only (overwrite), or append? Spec suggests single `.log`. Keep single-record semantics: overwrite on each crash.
5. Truncation of logcat and crash stacktrace — align with `ErrorLog` precedent (MAX_LOG_BYTES = 2 MB, cause message 200 chars). A log-export `.txt` with 2 MB + logcat + crash is borderline email-attachable; consider per-section caps documented in tech-spec.
