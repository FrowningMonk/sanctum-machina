# Code Research: Phase 2 — UI (Sanctum Machina)

**Date:** 2026-04-15
**Feature folder:** `C:/AI-WORK/PhoneWrap/work/phase-2-ui`
**Goal of research:** снимок Phase-1 foundation и инвентаризация gallery-source для портирования multimodal + per-model settings + theming + thinking.

---

## 1. Snapshot of current `:app` (Phase 1)

### 1.1 Compose screens and NavHost

- **`app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`** — root NavHost. Два destination'а:
  - `model_manager` → `ModelManagerScreen(onLoad = { navigate("chat/{modelName}") })`
  - `chat/{modelName}` → `ChatScreen(modelName, onBack)` (nav-arg `modelName` через `NavType.StringType`, `Uri.encode`/`decode`).
  - Start destination: `model_manager`.
  - **Нет** SettingsScreen, AboutScreen, InferenceSettingsScreen — всё это Phase 2 добавит.

- **`app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt`** — список моделей (LazyColumn из `ModelCard`), TopAppBar без actions, нет entry в Settings. Карточка содержит Button Download/Cancel/Load/Retry и LinearProgressIndicator.

- **`app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`** — `LoadingContent` / `FailedContent` / `ReadyContent`. `ReadyContent` = Scaffold с TopAppBar (title = modelName, action = Refresh/Reset), MessageList (LazyColumn из `MessageBubble`), `ChatInputRow` (OutlinedTextField + IconButton Send/Stop). Bubble: RoundedCornerShape(12.dp), разные цвета для USER/ASSISTANT. Есть footer для TTFT. **Нет** thinking-канала, **нет** attachments, **нет** камеры/микрофона.

- **ViewModels:**
  - `ChatViewModel` (`ui/chat/ChatViewModel.kt`) — `@HiltViewModel`, читает nav-arg, вызывает `registry.initialize/getModel/resetConversation/cleanup`, `helper.runInference(images = emptyList(), audioClips = emptyList(), extraContext = null)`. Single-active-engine: в `onCleared()` запускает detached `CoroutineScope(SupervisorJob() + Dispatchers.IO)` для `registry.cleanup(modelName)`.
  - `ModelManagerViewModel` (`ui/modelmanager/ModelManagerViewModel.kt`) — `@HiltViewModel`, просто переформатирует `registry.models` в UI и обрабатывает кнопки download/cancel/load (emit NavEvent.OpenChat).

- **Message model:** `ui/chat/Message.kt` — `enum MessageRole { USER, ASSISTANT }`, `data class Message(role, text, streaming, interrupted, footer?)`. Plain string content, нет attachments-поля, нет thinking-поля.

### 1.2 Existing UI components

Только базовый Material 3:
- `Scaffold`, `TopAppBar`, `Button`, `OutlinedTextField`, `IconButton`, `Text`, `Card`, `LazyColumn`, `LinearProgressIndicator`, `CircularProgressIndicator`, `RoundedCornerShape(12.dp)`.
- Иконки из `material-icons-extended`: `Icons.Default.{Error,Refresh,Stop}`, `Icons.AutoMirrored.Filled.Send`.
- Нет переиспользуемых composable'ов в `:app/ui/common/` — директории вообще не существует.

### 1.3 Theming

- **`app/src/main/kotlin/app/sanctum/machina/ui/theme/Theme.kt`** (24 строки) — `SanctumTheme(content)`. Логика:
  ```kotlin
  val darkTheme = isSystemInDarkTheme()
  val colorScheme = if (SDK >= S) {
      if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
  } else {
      if (darkTheme) darkColorScheme() else lightColorScheme()
  }
  MaterialTheme(colorScheme = colorScheme, content = content)
  ```
- **Нет** `Color.kt`, **нет** `Type.kt`, **нет** `ThemeSettings.kt`. Нет custom-цветов, нет custom-шрифтов, нет brass/copper акцента. Никакого override вручную — только system `isSystemInDarkTheme()`.
- Base style `app/src/main/res/values/themes.xml`: `<style name="Theme.Sanctum" parent="Theme.Material3.DayNight.NoActionBar" />`. Тема-обёртка для активити.

### 1.4 Strings

- **`app/src/main/res/values/strings.xml`** — полностью русский интерфейс. Ключи:
  - `app_name` = **"Sanctum"** (нужно переименовать → "Sanctum Machina").
  - ModelManager: `model_manager_title`, `model_status_*`, `model_size_gb_format`, `model_download_progress_format`, `model_error_prefix`.
  - Buttons: `btn_download`, `btn_cancel`, `btn_load`, `btn_retry`, `btn_send`, `btn_stop`, `btn_reset`, `btn_back`.
  - Chat: `chat_loading_model`, `chat_load_failed_title`, `chat_input_placeholder`, `chat_message_interrupted_suffix`, `ttft_footer_format`.
  - Все строки в UI-коде через `stringResource(R.string.xxx)` — грепом не нашёл hardcoded литералов в composable'ах. Чистая локализация.

### 1.5 Иконка приложения

- **`app/src/main/AndroidManifest.xml` строка 17:** `android:icon="@android:drawable/sym_def_app_icon"` — системная дефолт-иконка. Нет кастомной иконки.
- Нет `mipmap/ic_launcher*`. Нет `res/drawable/`.

### 1.6 Application / MainActivity

- **`SanctumApplication.kt`** — `@HiltAndroidApp`, в `onCreate()` проставляет `DefaultDownloadRepository.mainActivityFqn`. Больше ничего (нет Firebase, нет DataStore).
- **`MainActivity.kt`** — `@AndroidEntryPoint ComponentActivity`. Единственная логика: запрос `POST_NOTIFICATIONS` на TIRAMISU+. `setContent { SanctumTheme { SanctumApp() } }`. Нет UiModeManager, нет splash screen, нет intent handling.

### 1.7 AndroidManifest (`:app`)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```
- `android:allowBackup="false"`, `android:fullBackupContent="false"`, `android:usesCleartextTraffic="false"`.
- `uses-native-library libOpenCL.so`, `libvndksupport.so` (оба `required="false"`).
- `WorkManager SystemForegroundService` с `foregroundServiceType="dataSync"`.
- **Нет:** CAMERA, RECORD_AUDIO, READ_MEDIA_IMAGES/AUDIO, FileProvider.

### 1.8 Модульная структура / сборка

- **`app/build.gradle.kts`:** `compileSdk = 35`, `minSdk = 31`, `targetSdk = 35`, `applicationId = "app.sanctum.machina"`, `versionCode = 1`, `versionName = "0.1.0"`. `BuildConfig.MAIN_ACTIVITY_CLASS_NAME = "app.sanctum.machina.MainActivity"`. Зависимости: `:core-runtime`, compose-bom, material3, material-icons-extended, navigation-compose, hilt (+ ksp).
- **Plugins активные:** `android.application`, `kotlin.android`, `kotlin.compose`, `hilt.application`, `ksp`. **НЕ активны:** `kotlinx-serialization`, `protobuf`.
- Все объявлены в `gradle/libs.versions.toml` (`compose-bom=2026.03.00`, `kotlin=2.2.0`, `agp=8.8.2`, `hilt=2.57.1`, `litertlm=0.10.0`).

---

## 2. Snapshot of current `:core-runtime` (Phase 1)

### 2.1 Runtime API — `LlmModelHelper.runInference` (Contents API)

- **`core-runtime/src/main/kotlin/app/sanctum/machina/core/runtime/LlmModelHelper.kt`** — interface c методами:
  ```kotlin
  fun initialize(context, model, supportImage, supportAudio, onDone, systemInstruction: Contents? = null,
                 tools = listOf(), enableConversationConstrainedDecoding = false, coroutineScope = null)
  fun resetConversation(model, supportImage = false, supportAudio = false,
                        systemInstruction: Contents? = null, tools = listOf(), ...)
  fun cleanUp(model, onDone)
  fun runInference(model, input: String, resultListener, cleanUpListener, onError,
                   images: List<Bitmap> = listOf(), audioClips: List<ByteArray> = listOf(),
                   coroutineScope = null, extraContext: Map<String,String>? = null)
  fun stopResponse(model)
  ```
- `ResultListener` = `(partialResult, done, partialThinkingResult: String?) -> Unit` — **thinking-канал уже прокинут через API**, просто `ChatViewModel` его игнорирует (передаёт в `resultListener`, но `updateLastAssistant` в нём ничего не делает с `_, _, _`).

- **`core-runtime/.../inference/LlmChatModelHelper.kt`** — singleton object. `runInference` уже собирает `mutableListOf<Content>` из `Content.ImageBytes(image.toPngByteArray())` + `Content.AudioBytes(audioClip)` + `Content.Text(input)`, затем `conversation.sendMessageAsync(Contents.of(contents), MessageCallback, extraContext ?: emptyMap())`. Thinking: `message.channels["thought"]` прокидывается как третий аргумент в `resultListener`.
- **Вывод:** core-runtime Contents API multimodal-ready. Вся работа Phase 2 с multimodal — на UI-стороне (сбор Bitmap/ByteArray из камеры/галереи/MediaRecorder и передача в `helper.runInference`).

### 2.2 ModelRegistry / ModelEntry / ModelInitStatus

- **`core-runtime/.../registry/ModelRegistry.kt`** — interface, центральный координатор. `models: StateFlow<List<ModelEntry>>`, `refreshAllowlist(): Result<Unit>`, `download(model): Flow<ModelDownloadStatus>`, `cancelDownload(modelName)`, `delete(modelName)`, `initialize(modelName): Result<Unit>`, `cleanup(modelName)`, `resetConversation(modelName, systemPrompt: String? = null)`, `getModel(modelName): Model?`.
- **`DefaultModelRegistry`** — `@Singleton`. Единый `lifecycleMutex: Mutex` + `SupervisorJob` scope. `initialize`: flip Initializing → try GPU → если `err1` не пусто → cleanUp + forced CPU → повтор. `cleanup`: stale-instance guard по `===`. `resetConversation` берёт `String? systemPrompt`, оборачивает в `Contents.of(listOf(Content.Text(it)))`.
- **`ModelInitStatus`** — sealed: `Idle | Initializing | Ready | Failed(message)`.
- **`ModelEntry`** — `data class(model: Model, downloadStatus, initStatus)`. Wrapper вокруг `Model`, не рефакторинг.

### 2.3 Allowlist + ModelDefinition

- **`core-runtime/src/main/assets/model_allowlist.json`** — 2 модели: Gemma-4-E2B-it (2.58 GB), Gemma-4-E4B-it (3.65 GB). У обеих `llmSupportImage=true`, `llmSupportAudio=true`, `llmSupportThinking=true`, `taskTypes=["llm_chat","llm_prompt_lab","llm_agent_chat","llm_ask_image","llm_ask_audio"]`. `defaultConfig` несёт `topK, topP, temperature, maxContextLength=32000, maxTokens=4000, accelerators="gpu,cpu", visionAccelerator="gpu"`.
  - **Проблема:** поля `llmSupportImage/Audio/Thinking` присутствуют в JSON, но `AllowedModel` их не парсит! См. `data/ModelAllowlist.kt` — `toModel()` не пробрасывает эти флаги в `Model`. Надо проверить, учитывается ли это в LlmChatModelHelper (см. §6.2 ниже).

- **`core-runtime/.../registry/AllowlistLoader.kt`** — `@Singleton`. `load(): Result<List<Model>>`. Schema-guard: `MODEL_ID_REGEX=^litert-community/[A-Za-z0-9._-]+$`, `MODEL_FILE_REGEX=^[A-Za-z0-9._-]+$`, `COMMIT_HASH_REGEX=^[a-f0-9]{40}$`, size in `1..10 GB`.

- **`core-runtime/.../data/ModelAllowlist.kt` — `AllowedModel.toModel()`** создаёт `Model` с вычислением HuggingFace URL `https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true`, парсит accelerators, формирует `configs = createLlmChatConfigs(...)` для LLM-моделей (`maxTokens, topK, topP, temperature, accelerator`).

- **`core-runtime/.../data/Config.kt`** — `ConfigKey`, `ConfigKeys.*` (уже есть `MAX_TOKENS, TOPK, TOPP, TEMPERATURE, ACCELERATOR, VISION_ACCELERATOR, ENABLE_THINKING, SUPPORT_IMAGE, SUPPORT_AUDIO, THEME` и др.). `Config` иерархия: `LabelConfig | NumberSliderConfig | BooleanSwitchConfig | SegmentedButtonConfig | BottomSheetSelectorConfig`. Функция `createLlmChatConfigs(defaultMaxToken, defaultMaxContextLength, defaultTopK, defaultTopP, defaultTemperature, accelerators, supportThinking)` уже готова к использованию.

- **`core-runtime/.../data/Model.kt`** — `data class Model` из Gallery, 40+ полей. Mutable поля: `var instance: Any?`, `var configValues: Map<String,Any>`, `var prevConfigValues: Map<String,Any>`. Config read: `getIntConfigValue/getFloatConfigValue/getBooleanConfigValue/getStringConfigValue(key, defaultValue)` — читают из `configValues` map с конверсией через `convertValueToTargetType`.
  - **D6 `Model.instance: Any?` анти-паттерн** отложен в Phase 2 (см. tech-spec.md Phase 1 строки 324-325).

### 2.4 Hilt module

- **`core-runtime/.../di/CoreRuntimeModule.kt`** — `@Module @InstallIn(SingletonComponent)`. Provides: `DownloadRepository` (`DefaultDownloadRepository(context)`), `LlmModelHelper` (`LlmChatModelHelper` singleton object), `ModelRegistry` (→ `DefaultModelRegistry`). Нет DataStore provides — ни Proto, ни Preferences.
- В `:app` Hilt-модуля нет вообще. Вся DI pipe — через `:core-runtime`.

### 2.5 Existing unit tests

- **`core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/AllowlistLoaderTest.kt`** — JUnit 4 (`org.junit.Test`, `org.junit.Assert.*`). Framework: plain JUnit4 (D8: MockK и JUnit 5 отложены в Phase 2). 7 тестов:
  - `loadFromFixture_returnsListOfTwoModels`
  - `loadFromFixture_allModelsHaveRequiredFields`
  - `fixtureMatchesProductionAsset` — сравнивает байты prod `src/main/assets/model_allowlist.json` и `src/test/resources/model_allowlist_fixture.json`
  - `parse_rejectsEmptyAllowlist`
  - `parse_rejectsDisallowedModelIdPrefix`
  - `parse_rejectsModelFilePathTraversal`
  - `parse_rejectsMalformedCommitHash`
  - `parse_rejectsOversizedModel`
- **Fixture:** `src/test/resources/model_allowlist_fixture.json` — побайтовая копия prod.
- **Ничего больше не покрыто** — нет тестов для `DefaultModelRegistry`, `DownloadRepository`, `LlmChatModelHelper`, `DownloadWorker`, `ErrorLog`. Нет UI-тестов. Нет инструментального тестового таргета.

### 2.6 Другие классы `:core-runtime`

- `data/DownloadRepository.kt` + `DefaultDownloadRepository` — DownloadManager/WorkManager callback-based API с `mainActivityFqn` параметризацией (T6).
- `data/Consts.kt` — `DEFAULT_MAX_TOKEN=1024`, `DEFAULT_TOPK=64`, `DEFAULT_TOPP=0.95f`, `DEFAULT_TEMPERATURE=1.0f`, `DEFAULT_ACCELERATORS=[GPU]`, `DEFAULT_VISION_ACCELERATOR=GPU`, `MAX_IMAGE_COUNT=10`, `MAX_AUDIO_CLIP_COUNT=1`, `MAX_AUDIO_CLIP_DURATION_SEC=30`, `SAMPLE_RATE=16000`. Phase 2 не трогает.
- `worker/DownloadWorker.kt`, `common/Utils.kt` (только `cleanUpMediapipeTaskErrorMessage`), `log/ErrorLog.kt`.
- **`core-runtime/src/main/AndroidManifest.xml`** — только `POST_NOTIFICATIONS` + WorkManager service merge.

---

## 3. Gallery source reference — что портировать

### 3.1 Multimodal input (фото + аудио)

#### 3.1.1 Image pipeline

- **`gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/common/Utils.kt`** — помощники:
  - `decodeSampledBitmapFromUri(context, uri, reqWidth, reqHeight): Bitmap?` (строки 260-285) — BitmapFactory с `inJustDecodeBounds` + inSampleSize для downscale из content Uri (галерея) или file Uri.
  - `rotateBitmap(bitmap, orientation): Bitmap` (строки 287-307) — apply ExifInterface orientation (90/180/270/flip).
  - `calculateInSampleSize(options, reqWidth, reqHeight)` (private, строки 309+).

- **`gallery-source/.../ui/common/chat/MessageInputText.kt`** (1109 строк) — единый большой composable `MessageInputText(task, modelManagerViewModel, curMessage, isResettingSession, inProgress, imageCount, audioClipMessageCount, modelInitializing, ... onSendMessage, onPickedImagesChanged, onPickedAudioClipsChanged, showImagePicker, showAudioPicker, ...)`. Включает:
  - Камера (через `androidx.camera:camera-core/camera2/lifecycle/view` + `ImageCapture`).
  - Photo picker (`ActivityResultContracts.PickMultipleVisualMedia()`).
  - WAV picker.
  - Audio recorder bottom sheet (`rememberModalBottomSheetState`).
  - Permission launchers (CAMERA, RECORD_AUDIO).
  - Thumbnail preview strip с closer кнопкой.
  - SensorObserver для device-orientation (используется для камеры).
  - Prompt templates, history, skills picker — **это выбросить**, не нужно в Phase 2.

- **`gallery-source/.../ui/common/LiveCameraView.kt`** (219 строк) — обёртка над CameraX PreviewView + контролы (flash/front camera flip). Используется в camera capture bottom sheet из `MessageInputText`.

- **Камера-зависимости:** `androidx.camera:camera-core:1.4.2`, `camera-camera2`, `camera-lifecycle`, `camera-view`. Нужно добавить в `libs.versions.toml`.

#### 3.1.2 Audio pipeline

- **`gallery-source/.../ui/common/chat/AudioRecorderPanel.kt`** (291 строк) — composable `AudioRecorderPanel(task, onAmplitudeChanged, onSendAudioClip: (ByteArray) -> Unit, onClose)`. Использует **`AudioRecord` (низкоуровневый)**, а не `MediaRecorder`! CHANNEL_IN_MONO, PCM_16BIT, sampleRate=16000. Bg-поток через `Dispatchers.IO`. Автостоп по `MAX_AUDIO_CLIP_DURATION_SEC=30`. DisposableEffect для `recorder.release()`. **ВАЖНО:** Gallery НЕ пишет WAV-файл — сразу возвращает raw PCM `ByteArray` и передаёт в `helper.runInference(audioClips = listOf(bytes))`. litertlm ест raw PCM через `Content.AudioBytes`.

- **`gallery-source/.../common/Utils.kt`** аудио-хелперы:
  - `convertWavToMonoWithMaxSeconds(context, stereoUri, maxSeconds=30): AudioClip?` (строки 107-192) — для audio-file-picker сценария (если пользователь импортирует WAV): парсит WAV-header, нормализует до 16-bit, resample до 16000 Hz, stereo→mono, обрезает до N секунд.
  - `calculatePeakAmplitude(buffer, bytesRead): Int` (строки 244-258) — для визуализации уровня.
  - `convert8BitTo16Bit`, `resample` (private helpers).
- **`gallery-source/.../common/Types.kt` строка 32:** `class AudioClip(val audioData: ByteArray, val sampleRate: Int)` — простой контейнер.
- **`gallery-source/.../ui/common/chat/AudioPlaybackPanel.kt`** — playback ранее записанного клипа через `AudioTrack`.
- **`gallery-source/.../ui/common/chat/MessageBodyAudioClip.kt`** — render audio-клипа в сообщении.

**User-spec говорит "аудио через MediaRecorder"**, но gallery-source использует `AudioRecord`. **Оба пути рабочие для litertlm — обоим нужно отдавать PCM raw bytes.** Проще портировать готовый `AudioRecorderPanel.kt` (AudioRecord). Если продукт-требование именно MediaRecorder — надо уточнить (см. §6.6 риски).

#### 3.1.3 Contents builder

- Уже портирован в `core-runtime/.../inference/LlmChatModelHelper.kt` строки 255-267:
  ```kotlin
  val contents = mutableListOf<Content>()
  for (image in images) contents.add(Content.ImageBytes(image.toPngByteArray()))
  for (audioClip in audioClips) contents.add(Content.AudioBytes(audioClip))
  if (input.trim().isNotEmpty()) contents.add(Content.Text(input))
  conversation.sendMessageAsync(Contents.of(contents), callback, extraContext ?: emptyMap())
  ```
- **Дополнительно портировать не нужно.** Только собрать `List<Bitmap>` и `List<ByteArray>` на UI-стороне и пробросить в `helper.runInference(...)`.

### 3.2 Per-model inference settings

- **Gallery хранит per-model settings в `Model.configValues: Map<String, Any>`** (in-memory), а не в DataStore. Настройки загружаются из allowlist (`createLlmChatConfigs` → `Model.preProcess()` заполняет `configValues` из defaults). Изменения в `ConfigDialog` (`ui/common/ConfigDialog.kt`) пишутся в тот же `configValues` map + trigger реинициализацию engine. **Нет persistence per-model** — при перезапуске всё сбрасывается к allowlist defaults.

- **Что есть в DataStore у Gallery (`proto/settings.proto`):**
  - `Theme theme` (THEME_UNSPECIFIED/LIGHT/DARK/AUTO).
  - `AccessTokenData` (deprecated на уровне Settings, перенесён в UserData).
  - `repeated string text_input_history`.
  - `repeated ImportedModel imported_model { string file_name, int64 file_size, LlmConfig llm_config }` — вот здесь `LlmConfig { compatible_accelerators, default_max_tokens, default_topk, default_topp, default_temperature, support_image, support_audio, support_thinking, ... }` хранится **только для импортированных моделей**, не для allowlisted.
  - Флаги Tos, promo, benchmark help.
- **Вывод:** Gallery не решает задачу Phase 2 D2 "per-model inference settings persistent по modelId". Придётся **спроектировать свой proto-message**, типа:
  ```proto
  message PerModelSettings {
    int32 max_tokens = 1;
    int32 top_k = 2;
    float top_p = 3;
    float temperature = 4;
    bool enable_thinking = 5;
    string accelerator = 6;  // "GPU"/"CPU"/"NPU"
    string system_prompt_default = 7;
  }
  message AppSettings {
    Theme theme = 1;
    map<string, PerModelSettings> per_model = 2;  // key = modelId/modelName
  }
  ```
  и читать/писать через `DataStore<AppSettings>` + `DataStoreRepository` (аналог Gallery `DefaultDataStoreRepository`).

### 3.3 SettingsScreen / ConfigDialog

- **`gallery-source/.../ui/home/SettingsDialog.kt`** (358 строк) — dialog-style. Секции:
  - Theme switcher: `MultiChoiceSegmentedButtonRow` с 3 опциями (`THEME_AUTO, THEME_LIGHT, THEME_DARK`). При смене: обновляет `ThemeSettings.themeOverride.value`, `modelManagerViewModel.saveThemeOverride(theme)` (→ DataStore), **и дополнительно** вызывает `UiModeManager.setApplicationNightMode(...)` чтобы другие Activity тоже подхватили тему.
  - HF Token management — **выбросить, Phase 2 откладывает HuggingFace OAuth**.
  - Third-party licenses (OssLicensesMenuActivity) — опционально портировать в AboutScreen.
  - ToS — выбросить.
- Для Sanctum это должен стать отдельный **SettingsScreen** (NavHost destination), а не dialog — user-spec говорит "SettingsScreen" + "AboutScreen". Навигация: MainModelManager top-bar icon → settings → entry в Model manager + entry в About.

- **`gallery-source/.../ui/common/ConfigDialog.kt`** (не читал полностью, видел в списке) — универсальный dialog, рендерит список `List<Config>` из `Model.configs` с редакторами по `ConfigEditorType` (`LABEL, NUMBER_SLIDER, BOOLEAN_SWITCH, SEGMENTED_BUTTON, BOTTOMSHEET_SELECTOR`). Это core pattern для **per-model InferenceSettings**. Портировать этот dialog + подстроить под свою InferenceSettingsScreen.

### 3.4 Thinking channel (ризонинг)

- **`gallery-source/.../ui/common/chat/MessageBodyThinking.kt`** (103 строки) — готовый composable. Collapsible:
  - Row с текстом `stringResource(R.string.show_thinking)` + `Icons.Filled.ArrowDropDown/Up`, `Modifier.clickable { isExpanded = !isExpanded }`.
  - `if (inProgress) isExpanded = true` — автоматически раскрывается, пока стрим идёт.
  - `AnimatedVisibility(expandVertically/shrinkVertically)` с левой вертикальной линией (drawBehind с `outlineVariant`), padding, `MarkdownText(thinkingText, smallFontSize=true, textColor=onSurfaceVariant)`.
- **Зависимость:** `MarkdownText` composable из `ui/common/MarkdownText.kt` — использует `com.halilibo.compose-richtext:richtext-commonmark:1.0.0-alpha02` + `richtext-ui-material3`. Можно портировать или заменить простым `Text(text)` если Markdown не нужен в Phase 2.

- **Интеграция:**
  - `Message` data class должен получить новое поле `thinkingText: String? = null`.
  - `ChatViewModel.send(...) → resultListener` сейчас игнорирует 3-й аргумент (`partialThinkingResult: String?`). Надо накопить его аналогично `sb` для основного текста: `if (thinking != null) thinkingSb.append(thinking); updateLastAssistant { it.copy(thinkingText = thinkingSb.toString()) }`.
  - `MessageBubble` добавляет `MessageBodyThinking(thinkingText, inProgress = message.streaming)` **до** основного `Text(message.text)`.
  - `Model.llmSupportThinking` + `ConfigKeys.ENABLE_THINKING` — фильтр показа (если модель не поддерживает или пользователь выключил — не показывать и не накапливать).

### 3.5 Theme switching

- **`gallery-source/.../ui/theme/Theme.kt`** (350 строк) — `GalleryTheme(content)`:
  ```kotlin
  val themeOverride = ThemeSettings.themeOverride  // mutableStateOf<Theme>
  val darkTheme = (isSystemInDarkTheme() || themeOverride.value == THEME_DARK) && themeOverride.value != THEME_LIGHT
  val colorScheme = if (darkTheme) darkScheme else lightScheme
  val customColorsPalette = if (darkTheme) darkCustomColors else lightCustomColors
  CompositionLocalProvider(LocalCustomColors provides customColorsPalette) {
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
  }
  // + StatusBarColorController, window.isNavigationBarContrastEnforced = false
  ```
  - **`ThemeSettings.kt`** — глобальный singleton с `mutableStateOf<Theme>(THEME_AUTO)`. Читается при старте (`MainActivity.onCreate`): `themeOverride.value = dataStoreRepository.readTheme()`.
  - **`Color.kt`** — полные Material 3 light/dark schemes (primary через surfaceContainerHighest).
  - **`Type.kt`** — `Typography` на базе **Nunito** (`R.font.nunito_regular, ...bold` — 8 ассетов в `res/font/`). **Портировать шрифт не нужно** (user-spec говорит "три размера шрифта" — можно обойтись default Roboto).
  - **`CustomColors` data class + `LocalCustomColors` staticCompositionLocalOf** — расширение colorScheme дополнительными цветами (userBubbleBgColor, agentBubbleBgColor, successColor, recordButtonBgColor и т.д.). **Это паттерн для brass/copper акцента Sanctum** — ввести свой `SanctumCustomColors` с `accentCopper`, `accentBrass`, bubble-цветами.

### 3.6 AboutScreen

- В Gallery нет отдельного AboutScreen — есть только SettingsDialog с секцией Third-party licenses. user-spec Phase 2 говорит "заглушка, полный манифест в Phase 5" — поэтому это просто новый NavHost destination с `Text("About Sanctum Machina")` + версия приложения из `BuildConfig.VERSION_NAME`. Никакого портирования.

---

## 4. DataStore (Proto) setup

### 4.1 Текущее состояние — НИЧЕГО не подключено

Проверил `gradle/libs.versions.toml`: нет упоминаний `datastore`, `protobuf`, `protobuf-javalite`. В `app/build.gradle.kts` и `core-runtime/build.gradle.kts` нет `implementation(libs.androidx.datastore)`, нет `plugins { protobuf }`, нет `protobuf {}` блока. В `:app/src/main/` нет директории `proto/`. Hilt-модуля в `:app` нет — DataStore просто некуда подключить без нового модуля.

### 4.2 Что Gallery использует

- **`libs.versions.toml` у Gallery:**
  ```toml
  dataStore = "1.1.7"
  protobuf = "0.9.5"           # plugin version
  protobufJavaLite = "4.26.1"  # runtime

  [libraries]
  androidx-datastore = { group = "androidx.datastore", name = "datastore", version.ref = "dataStore" }
  protobuf-javalite = { group = "com.google.protobuf", name = "protobuf-javalite", version.ref = "protobufJavaLite" }
  [plugins]
  protobuf = { id = "com.google.protobuf", version.ref = "protobuf" }
  ```
- **`app/build.gradle.kts`:**
  ```kotlin
  plugins { alias(libs.plugins.protobuf) ... }
  dependencies {
    implementation(libs.androidx.datastore)
    implementation(libs.protobuf.javalite)
  }
  protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.26.1" }
    generateProtoTasks { all().forEach { it.plugins { create("java") { option("lite") } } } }
  }
  ```
- **`.proto` файлы:** в `app/src/main/proto/settings.proto`, `benchmark.proto`, `skill.proto`. Работает **встроенный source set** `src/main/proto` — protobuf-gradle-plugin автоматически подхватывает.

### 4.3 Что нужно добавить в Sanctum (Phase 2 Task)

- **`libs.versions.toml`** (в `[versions]`): `dataStore = "1.1.7"`, `protobuf = "0.9.5"`, `protobufJavaLite = "4.26.1"`. В `[libraries]`: `androidx-datastore`, `protobuf-javalite`. В `[plugins]`: `protobuf`.
- **`build.gradle.kts` (root)**: добавить `alias(libs.plugins.protobuf) apply false`.
- **`:app/build.gradle.kts`** (или отдельный модуль — см. ниже решение): подключить plugin + deps + `protobuf { protoc ... }` блок.
- **Открытый вопрос (архитектурное решение для tech-spec):** **где** держать DataStore — в `:app` или в новом `:core-settings` модуле? Плюс за отдельный модуль — чистая граница, :core-runtime может читать DataStore через DI. Минус — overhead. Gallery держит в app.
- **Пример `.proto`** — уже эскиз в §3.2 (AppSettings + PerModelSettings + Theme enum).
- **Serializer:** полный аналог `SettingsSerializer.kt` (строки 26-38):
  ```kotlin
  object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue = AppSettings.getDefaultInstance()
    override suspend fun readFrom(input) = try { AppSettings.parseFrom(input) }
                                           catch (e: InvalidProtocolBufferException) { throw CorruptionException(...) }
    override suspend fun writeTo(t, output) = t.writeTo(output)
  }
  ```
- **Hilt:** новый модуль `AppModule` (или в `:core-runtime/CoreRuntimeModule`) с `@Provides @Singleton fun provideAppSettingsDataStore(@ApplicationContext ctx, serializer): DataStore<AppSettings> = DataStoreFactory.create(serializer, produceFile = { ctx.dataStoreFile("app_settings.pb") })`.

### 4.4 Proto plugin версия и совместимость

- AGP 8.8.2 + Kotlin 2.2.0 + KSP 2.3.6 — проверить `protobuf-gradle-plugin 0.9.5`; issue-tracker на GitHub показывает compatibility до AGP 8.x ok. Если не поедет — есть `0.9.4` и `0.9.5+`. Не ожидаю проблем.

---

## 5. Permissions для multimodal

### 5.1 Уже есть

В `:app/AndroidManifest.xml`: `INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, WAKE_LOCK`.
В `:core-runtime/AndroidManifest.xml`: `POST_NOTIFICATIONS`.

### 5.2 Нужно добавить

| Permission | Зачем | Runtime? |
|---|---|---|
| `android.permission.CAMERA` | CameraX (live preview + ImageCapture) | да, runtime |
| `android.permission.RECORD_AUDIO` | AudioRecord/MediaRecorder | да, runtime |
| `android.permission.READ_MEDIA_IMAGES` | Photo Picker fallback (API 33+) | да, runtime |
| `android.permission.READ_MEDIA_AUDIO` | WAV-импорт (API 33+), если будет | да, runtime |
| `<uses-feature android:name="android.hardware.camera" android:required="false" />` | не блокировать установку на планшеты без камеры | N/A |

**НЕ НУЖНО:**
- `WRITE_EXTERNAL_STORAGE` — attachments в `filesDir/quick/` (app-private), write не требуется.
- `READ_EXTERNAL_STORAGE` — Android 13+ использует ограниченный доступ; `PickVisualMediaRequest` не требует permission вообще (с `minSdk=31` и `targetSdk=35`).
- `FileProvider` — нужен только если отдавать файлы наружу или запускать Camera intent с `EXTRA_OUTPUT`. При использовании `CameraX ImageCapture.takePicture` → сразу получаем `Bitmap` без FileProvider. Но если все-таки надо сохранять preview jpeg на диск → добавить провайдер как в Gallery (строки 104-112 Gallery manifest) + `res/xml/file_paths.xml`.
- AICore BIND_SERVICE, Firebase, GSF — **выбрасываем**.

### 5.3 Runtime permission паттерн

Gallery (`MessageInputText.kt` строки 236-258):
```kotlin
val takePicturePermissionLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.RequestPermission()
) { granted -> if (granted) { showCameraCaptureBottomSheet = true } }
// Check + request:
if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PERMISSION_GRANTED) {
    showCameraCaptureBottomSheet = true
} else {
    takePicturePermissionLauncher.launch(Manifest.permission.CAMERA)
}
```
Аналогично для RECORD_AUDIO. Для PickVisualMediaRequest permission не нужен (Android scoped storage). Это стандартный паттерн из Compose — переиспользовать.

### 5.4 Почему minSdk=31 помогает

`PickMultipleVisualMedia` (Android Photo Picker) работает на API 31+ нативно (через Google Play update на старых девайсах). Granular media permissions (READ_MEDIA_IMAGES/AUDIO) только для API 33+; на 31-32 fallback — Photo Picker работает без permissions. Можно упростить: только CAMERA + RECORD_AUDIO + READ_MEDIA_{IMAGES,AUDIO} с `android:maxSdkVersion="..."` и т.п. — или вообще не просить READ_MEDIA_*, полагаясь только на Photo Picker.

---

## 6. Риски и подводные камни

### 6.1 Gallery-классы, которые **НЕ** тащить

- `Analytics.kt`, `FcmMessagingService.kt` — Firebase, не нужно.
- `runtime/aicore/AICoreModelHelper.kt` — AICore (Phase 1 D1 отверг).
- `customtasks/**` — все demo-флоу: `agentchat/`, `tinygarden/`, `mobileactions/`, `examplecustomtask/`. Не нужно.
- `ui/benchmark/**` — BenchmarkScreen/BenchmarkViewModel/Results. Не нужно в Phase 2.
- `ui/common/tos/**` — ToS dialogs. Не нужно.
- `SkillAllowlist.kt`, `BenchmarkResultsSerializer`, `CutoutsSerializer`, `SkillsSerializer`, `UserDataSerializer` — связаны с другими proto-сообщениями, выбросить вместе с benchmark/skill/cutout/user-data proto.
- `SettingsDialog.kt` HF-Token блок — Phase 2 откладывает OAuth.
- `ModelImportDialog.kt` — импорт локальных моделей (D6 инфра — Phase 2+).
- `ChatPanel.kt`, `ChatView.kt`, `ChatViewModel.kt`, `MessageBubbleShape.kt`, `MessageSender.kt` — в Gallery это большой монолит tightly-coupled с Task/DataStore/ModelManager. **Не портировать как есть** — переписать compact под нашу архитектуру, используя только кусочки (MessageBodyThinking, MessageInputText-идеи, AudioRecorderPanel).

### 6.2 Room-specific код Gallery — есть ли связность?

- Gallery **не использует Room**. Вся persistence — через Proto DataStore (`settings.proto` и пять сопутствующих). Код multimodal в `MessageInputText`/`AudioRecorderPanel`/`ChatViewModel` **не знает про Room** — завязан на:
  - `ModelManagerViewModel.uiState` (StateFlow) — selectedModel, task, image/audio limits.
  - `DataStoreRepository.saveTextInputHistory()` — опциональная фича, можно отбросить.
  - `Task` объект (из `data/Tasks.kt`) — передаётся в AudioRecorderPanel для цвета иконки. **Избавиться:** заменить на прямой параметр `buttonColor: Color`.
- **Вывод:** изолирование multimodal от Gallery-specific инфры — тривиально. Убрать аргумент `task: Task`, `modelManagerViewModel`, заменить на наши типы.

### 6.3 Потенциальные регрессии при портировании multimodal

- **Память.** `MessageInputText` хранит `pickedImages: List<Bitmap>` в state. 10 full-res фото = быстрая OOM. Надо жёстко downscale через `decodeSampledBitmapFromUri(reqWidth=1024, reqHeight=1024)` до state. user-spec: `MAX_IMAGE_COUNT=10` (уже в Consts.kt).
- **Lifecycle recorder.** `AudioRecord.release()` в DisposableEffect — если пользователь пройдёт back до `onSendAudioClip`, буфер дропнется. Это ок. Но если ViewModel переживает recomposition, recorder не должен держаться в VM — только в composable state. В gallery-source именно так.
- **extraContext != null** в `LlmChatModelHelper.sendMessageAsync(...)` — сейчас мы передаём `null → emptyMap()`. Gallery использует `extraContext` для передачи имени пользователя и контекстных полей в skill-чаты. Нам не нужно, просто оставить null.
- **Allowlist поля `llmSupportImage/Audio/Thinking`** не парсятся в `AllowedModel.toModel()` (см. §2.3). Это **существующий баг Phase 1**: в JSON есть, в Model не долетает. Phase 2 должен:
  1. Добавить поля в `AllowedModelConfig` (или в `AllowedModel` root — в JSON они на корне).
  2. Пробросить в `Model.llmSupportImage/Audio/Thinking` в `toModel()`.
  3. Обновить fixture.json и `fixtureMatchesProductionAsset` тест поедет (ожидаемо).
  - **Сейчас** `DefaultModelRegistry.initialize` передаёт `supportImage = model.llmSupportImage` (= false, default!) в `helper.initialize(...)`. Значит, **даже если UI соберёт Bitmap**, `Engine` инициализируется без vision backend и multimodal не полетит. **Это блокер для Phase 2** — исправить в первую очередь.
- **`Content.ImageBytes(PNG)` vs litertlm format.** Gallery использует `Bitmap.compress(PNG, 100, stream)` — PNG lossless, большой. litertlm может предпочитать JPEG или raw. Надо проверить в litertlm docs 0.10.0 — при ошибке переключить на JPEG quality=90.

### 6.4 Renaming "Sanctum" → "Sanctum Machina"

- `strings.xml` строка 2: `app_name = "Sanctum"`. Заменить на `"Sanctum Machina"`. **Никакого другого изменения не нужно** (`android:label="@string/app_name"` уже ссылается на ресурс). `applicationId`, `namespace`, package не трогать — это пользовательский label, не технический идентификатор.

### 6.5 Thinking-канал — зависимость от litertlm

- `LlmChatModelHelper.onMessage(message)` делает `message.channels["thought"]` — это новое поле в litertlm 0.10.0, не во всех версиях стабильно. Если `channels["thought"]` всегда null для текущей Gemma-4 .litertlm-модели — функциональность thinking будет мёртвой. **Надо при разработке проверить на реальной модели через Logcat.** В Gallery Gemma-4 E2B/E4B заявлена `llmSupportThinking=true` в allowlist — значит, должно работать.

### 6.6 MediaRecorder vs AudioRecord (спор user-spec vs Gallery)

- user-spec Phase 2: "аудио через MediaRecorder".
- Gallery: `AudioRecord` (raw PCM, удобнее для litertlm).
- **MediaRecorder** умеет AMR/AAC/etc в файл — потребует конверсию в PCM 16 kHz mono перед отдачей litertlm. **AudioRecord** выдаёт PCM сразу.
- **Рекомендация:** портировать gallery-source `AudioRecorderPanel.kt` (AudioRecord). Если user-spec специально требовал MediaRecorder — нужно явное подтверждение в интервью (сейчас user-spec — blank template, заполняется этой же фичей).

### 6.7 Модульная граница — куда класть UI multimodal код

- Вариант A: всё в `:app/ui/chat/` — приятно, но если мы хотим InferenceSettings переиспользовать в будущем — придётся выносить.
- Вариант B: новый модуль `:core-settings` (DataStore) + `:core-media` (bitmap/audio helpers), UI Compose — в `:app`.
- Phase 1 строил "обёрточную" архитектуру с `:core-runtime`. Phase 2 скорее всего пойдёт тем же путём — выносить media-утилиты в `:core-runtime/common/` (уже есть), Compose-UI и DataStore hook — в `:app`. Новых модулей **не создавать** без необходимости. `patterns.md` должна это подтвердить.

### 6.8 Attachments lifecycle (эфемерность)

- user-spec: "attachments в `filesDir/quick/`, удаляются при выходе с ChatScreen или kill процесса".
- Bitmap напрямую в VM-state при `PickVisualMedia` — не пишется на диск. Но если CameraX ImageCapture пишет JPEG через `OutputFileOptions(File(filesDir/quick/IMG_...jpg))`, нужно cleanup:
  - `ChatViewModel.onCleared()` — удалить `filesDir/quick/**`.
  - `MainActivity.onCreate()` — стартовый cleanup (на случай kill без onCleared).
  - Или не писать на диск вообще, использовать `ImageCapture.takePicture(executor, OnImageCapturedCallback)` → `ImageProxy → Bitmap` в памяти. Gallery так делает.

### 6.9 Deep-links / activity recreation

- `android:configChanges="uiMode"` (Gallery) при смене темы — НЕ recreate Activity, а просто recompose. В `:app/AndroidManifest.xml` этого нет. Если добавим theme switch через UiModeManager — надо добавить, иначе Activity пересоздастся и NavBackStack сбросится. Или switch только через `ThemeSettings.themeOverride.value =` без UiModeManager (Compose перерисуется — этого достаточно для single-Activity приложения).

### 6.10 Тестовая инфраструктура

- В `:core-runtime/src/test/` пока один тест. Phase 2 добавит минимум:
  - `PerModelSettingsTest` — save/read round-trip через in-memory DataStore.
  - `ImagePreprocessorTest` — downscale/rotate helpers (JVM-only, без Android — если вынести через Robolectric или отделить чистую логику от `Bitmap`).
- **MockK / JUnit 5 / Robolectric** — не подключены (Phase 1 D8 отложил в Phase 2). Решить в tech-spec: вводим ли, или остаёмся на plain JUnit 4. Новые Android-зависимые тесты потребуют Robolectric (`org.robolectric:robolectric:4.12`) или instrumentation `androidTest`.

---

## Appendix A. Конкретные файлы Gallery для порта (cheat sheet)

| Цель в Sanctum | Исходник Gallery | Строк | Надо ли чистить |
|---|---|---|---|
| `ui/chat/ThinkingBlock.kt` (collapsible) | `ui/common/chat/MessageBodyThinking.kt` | 103 | Только пакет + заменить `MarkdownText` на `Text` либо добавить richtext dep |
| `ui/chat/AudioRecorderPanel.kt` | `ui/common/chat/AudioRecorderPanel.kt` | 291 | Убрать `task: Task` аргумент, `customColors.recordButtonBgColor` → fixed Color.Red |
| `ui/chat/MessageInputBar.kt` (новый compact) | `ui/common/chat/MessageInputText.kt` | 1109 | Переписать под нужды: убрать prompt-templates/history/skills/AICore, оставить image+audio+text |
| `ui/chat/LiveCameraSheet.kt` | `ui/common/LiveCameraView.kt` | 219 | Удалить task-coupling, упростить |
| `core-runtime/common/MediaUtils.kt` | `common/Utils.kt` функции `decodeSampledBitmapFromUri`, `rotateBitmap`, `convertWavToMonoWithMaxSeconds`, `calculatePeakAmplitude` | ~150 | Скопировать 4 функции + private helpers `convert8BitTo16Bit`, `resample`, `calculateInSampleSize` |
| `core-runtime/common/AudioClip.kt` | `common/Types.kt:32` `class AudioClip(audioData, sampleRate)` | 2 | Just copy |
| `ui/theme/Color.kt` (M3 schemes + custom) | `ui/theme/Color.kt`, `Theme.kt` (`CustomColors`, `LocalCustomColors`, `lightCustomColors/darkCustomColors`) | 350 | Заменить цвета на Sanctum copper/brass, выбросить Gallery-specific keys (taskBgColors, promoBanner...), оставить bubble+success+warning+error |
| `ui/theme/Theme.kt` | `ui/theme/Theme.kt` | 350 | Переписать `GalleryTheme` → `SanctumTheme` с `ThemeSettings.themeOverride` |
| `ui/theme/ThemeSettings.kt` | `ui/theme/ThemeSettings.kt` | 24 | Скопировать 1:1 |
| `ui/settings/SettingsScreen.kt` | `ui/home/SettingsDialog.kt` (только theme + entry в ModelManager + entry About) | 358 | Выбросить HF-token, Tos, oss-licenses (или оставить простой list-item) |
| `ui/settings/InferenceSettingsScreen.kt` | `ui/common/ConfigDialog.kt` + `data/Config.kt` рендеры | ~200 | Переиспользовать `List<Config>` из `Model.configs`, писать через DataStore `PerModelSettings` по `modelId` |
| `app/.../proto/app_settings.proto` | `app/src/main/proto/settings.proto` (структура, не содержимое) | — | Написать свой с `PerModelSettings` map |
| `app/.../AppSettingsSerializer.kt` | `SettingsSerializer.kt` | 38 | Скопировать pattern, заменить `Settings` → `AppSettings` |
| `app/.../di/AppModule.kt` (новый) | `di/AppModule.kt` | 187 | Взять только DataStore Hilt-провайдеры, остальное не нужно |
| `app/.../data/DataStoreRepository.kt` | `data/DataStoreRepository.kt` | 437 | Compact interface: `readTheme/saveTheme`, `readPerModelSettings(modelId)/savePerModelSettings(modelId, ...)`. Выбросить text_input_history, secrets, imported_model, cutouts, benchmark, skills, promo, tos. |

## Appendix B. Открытые вопросы для интервью Цикл 2

1. **MediaRecorder vs AudioRecord** — подтвердить user-spec. Рекомендация: AudioRecord (как в Gallery).
2. **Per-model settings key** — `modelName` (человекочитаемое из allowlist) или `modelId` (`litert-community/...`)? Первое проще, второе — устойчивее к переименованию в allowlist.
3. **DataStore модуль** — в `:app` или отдельный `:core-settings`? Рекомендация: `:app` для Phase 2, выносить только если Phase 3 Room-интеграция потребует.
4. **MarkdownText для thinking** — тянем `richtext-commonmark` (~300 KB) или обойдёмся `Text`? Рекомендация: начать с `Text`, Markdown добавить позже.
5. **Fix allowlist `llmSupport*` parsing** — делать в Phase 2 или выделять pre-Phase-2 hotfix? Рекомендация: в Phase 2 первой Task (блокер для multimodal).
6. **Cleanup `filesDir/quick/`** — использовать `ImageCapture OnImageCapturedCallback` (in-memory Bitmap) и вообще не трогать диск, либо писать tmp-JPEG + cleanup в `ChatViewModel.onCleared()` + `MainActivity.onCreate()`? Рекомендация: in-memory, проще.
7. **Theme transition на уже запущенной Activity** — `UiModeManager.setApplicationNightMode` (как Gallery) + `configChanges="uiMode"` в манифесте, или чисто Compose через `ThemeSettings.themeOverride`? Рекомендация: чисто Compose для single-Activity приложения.
