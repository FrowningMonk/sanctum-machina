---
created: 2026-04-16
status: draft
branch: phase/2-ui
size: L
---

# Tech Spec: phase-2-ui (Sanctum Machina)

## Solution

Phase 2 превращает Phase-1 foundation (два примитивных экрана) в полноценную мультимодальную чат-оболочку. Подход — «обёртка»: ядро `:core-runtime` трогаем минимально (починка парсинга allowlist + портирование media-утилит), основная новая работа распределяется между `:app` (Compose UI, ChatViewModel, runtime-permissions) и новым gradle-модулем `:core-settings` (Proto DataStore с per-model overrides). Multimodal Contents API, thinking-канал в ResultListener и lifecycle engine (single-active, cleanup/initialize через `DefaultModelRegistry`) уже готовы из Phase 1 — в Phase 2 мы их только используем. Эффективные inference-настройки = allowlist-defaults ∪ per-model overrides из DataStore, ключ — `Model.modelId`. Переключение «тяжёлых» полей (`accelerator`, initially `enableThinking`) триггерит модальный progress-dialog + `cleanup + initialize` engine; «лёгкие» (`temperature, topK, topP, maxTokens, systemPromptDefault`) применяются со следующего пользовательского хода без reinit. Attachments (Bitmap из Photo Picker/CameraX, ByteArray из AudioRecord) живут исключительно in-memory в `ChatViewModel.StateFlow<List<Attachment>>` — на диск не пишем.

## Architecture

### What we're building/modifying

- **`:core-runtime` (минимальные изменения)**
  - Починка `AllowedModel.toModel()` — проброс `llmSupportImage/Audio/Thinking` из JSON в `Model` (блокер multimodal: без этого `DefaultModelRegistry.initialize` передаёт `supportImage=false` в engine).
  - `common/MediaUtils.kt` — порт `decodeSampledBitmapFromUri`, `rotateBitmap`, `calculateInSampleSize` из `gallery-source/common/Utils.kt`.
  - `common/AudioClip.kt` — порт контейнера `data class AudioClip(audioData: ByteArray, sampleRate: Int)` из gallery.
  - `common/MultimodalContentsBuilder.kt` — вынесенная pure-функция сборки `List<Content>` из `(text, List<Bitmap>, List<ByteArray>)`; используется из `LlmChatModelHelper.runInference` (рефакторинг inline-кода phase 1).
  - **Никакой реархитектуры ядра**: Contents API, thinking-канал через `ResultListener(partialResult, done, partialThinkingResult)`, lifecycle engine — уже готовы.

- **`:core-settings` (новый gradle-модуль)**
  - `src/main/proto/app_settings.proto` — схема `AppSettings { map<string, PerModelSettings> per_model_overrides }`.
  - `AppSettingsSerializer` — DataStore serializer над `AppSettings`.
  - `AppSettingsRepository` (interface) + `DefaultAppSettingsRepository` (implementation over `DataStore<AppSettings>`) — публичный API для чтения/записи overrides по `modelId`.
  - `di/CoreSettingsModule.kt` — Hilt-модуль с `@Provides @Singleton DataStore<AppSettings>` + `@Binds AppSettingsRepository`.
  - `AndroidManifest.xml` — library-hygiene (namespace, без permissions — DataStore их не требует).

- **`:app` (расширение)**
  - Новая директория `ui/chat/`: `MultimodalInputBar.kt`, `Attachment.kt`, `ThumbnailStrip.kt`, `CameraBottomSheet.kt`, `AudioRecorderBottomSheet.kt`, `ThinkingBlock.kt`, `InferenceSettingsBottomSheet.kt`, `HeavyChangeDialog.kt`, `ReinitProgressDialog.kt`, `EffectiveConfig.kt`.
  - Новая директория `ui/about/`: `AboutScreen.kt`.
  - Расширения existing: `ChatScreen.kt` (TopAppBar actions, host для sheets, autoscroll), `ChatViewModel.kt` (attachments state, thinking accumulation, override-aware config, heavy-change reinit), `Message.kt` (поле `thinkingText`), `SanctumApp.kt` (destination `about`), `ModelManagerScreen.kt` (TopAppBar action «О приложении»).
  - `strings.xml` — `app_name = "Sanctum Machina"` + новые строки.
  - `assets/about.md` — редактируемый манифест (markdown).
  - `AndroidManifest.xml` — `CAMERA`, `RECORD_AUDIO`, `<uses-feature camera required="false">`.

### How it works

**Inference flow:** user тапает камеру / галерею / микрофон → собираются Bitmap/ByteArray в `ChatViewModel.attachments: StateFlow<List<Attachment>>` → `ThumbnailStrip` отображает миниатюры → user печатает текст + Send → `ChatViewModel.send()` разделяет attachments на `List<Bitmap>` и `List<ByteArray>`, вызывает `helper.runInference(input, images, audioClips)` → `LlmChatModelHelper` через `MultimodalContentsBuilder` формирует `List<Content>` (`ImageBytes(PNG)` + `AudioBytes(PCM)` + `Text`) → litertlm стримит `partialResult` и `partialThinkingResult` через `ResultListener` → `ChatViewModel` накапливает в `Message.text` и `Message.thinkingText` (отдельные StringBuilder-ы) → Compose перерисовывает; `LaunchedEffect(messages.size, last.text.length)` запускает `listState.animateScrollToItem` → после `done=true` сообщение финализируется (`streaming=false`), attachments очищаются.

**Settings flow:** открытие ⚙ в TopAppBar → `InferenceSettingsBottomSheet` читает `repository.observePerModelSettings(model.modelId)` и allowlist-defaults, показывает `effectiveConfig(defaults, overrides)` → user правит поле → «Применить»:
- **Лёгкое поле** (`temperature, topK, topP, maxTokens, systemPromptDefault`) → `repository.savePerModelSettings(modelId, merged)`; `ChatViewModel` на следующем `send()` применяет новые values через `model.configValues` (in-memory mutable map от Phase 1). Активный стрим не прерывается.
- **Тяжёлое поле** (`accelerator`, initially `enableThinking`) → `HeavyChangeDialog` (warning) → confirm → `ReinitProgressDialog` (неотменяемый) → `stopResponse` (если идёт стрим) + `registry.cleanup(modelName)` + обновить `model.configValues` + `registry.initialize(modelName)` → dialog dismiss. История чата в UI сохраняется; контекст внутри engine сбрасывается.

«Default» → `repository.resetPerModelSettings(modelId)` (удаляет запись из map) → sheet показывает allowlist-defaults; если среди удалённых override'ов было тяжёлое поле с ненулевой разницей — применяется через тот же reinit-flow.

**Thinking flow:** при `model.llmSupportThinking && effectiveConfig[ENABLE_THINKING]=true` — `partialThinkingResult` из `ResultListener` копится в `ChatViewModel.thinkingSb`, пишется в `Message.thinkingText` через `_messages.update { ... }`. `MessageBubble` при непустом `thinkingText` рендерит `ThinkingBlock(thinkingText, inProgress = message.streaming)` над основным текстом (collapsible, auto-expand пока `inProgress`). При `llmSupportThinking=false` или `enableThinking=false` — накопление пропускается, UI блока нет.

**Lifecycle audio:** `AudioRecorderBottomSheet` использует `DisposableEffect { onDispose { recorder.release() } }` + `LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { if (recording) { release(); dismissSheet() } }` — при входящем звонке, сворачивании, блокировке экрана recorder освобождается, буфер дропается, attachment не добавляется (AC-19).

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `DataStore<AppSettings>` | `CoreSettingsModule` (Hilt `@Singleton`) | `DefaultAppSettingsRepository` | 1 (singleton; file `context.filesDir/datastore/app_settings.pb`) |
| `AppSettingsRepository` | `CoreSettingsModule` | `ChatViewModel`, `InferenceSettingsBottomSheet` | 1 (singleton) |
| litertlm engine via `ModelRegistry` (from Phase 1) | `DefaultModelRegistry` | `ChatViewModel` | 0 or 1 (single-active; reinit при смене модели или heavy setting) |
| CameraX `ProcessCameraProvider` | `CameraBottomSheet` composable | only the sheet | 1 per open sheet; bound/unbound в `DisposableEffect` |
| `AudioRecord` | `AudioRecorderBottomSheet` composable | only the sheet | 1 per open sheet; `release()` в `onDispose` + `ON_PAUSE` |

## Decisions

### D1. Модуль `:core-settings` — всё proto+DataStore внутри него
**Decision:** Новый gradle-модуль `:core-settings`. Proto-схема, generated-классы, serializer, repository, Hilt-модуль — всё внутри. `:app` и `:core-runtime` зависят от `:core-settings` и получают только `AppSettingsRepository` через DI, не знают про proto напрямую.
**Rationale:** `:core-runtime` остаётся без зависимости от `:app` (patterns.md § module boundary). Build-isolation: изменения в proto не триггерят полную пересборку `:app`. KMP-путь: DataStore + protobuf-javalite кроссплатформенны.
**Alternatives considered:**
- Proto+DataStore в `:app` (как Gallery) — отверг, нарушает D2 user-spec (`:core-runtime` не сможет читать через DI).
- Proto в `:app`, DataStore-обёртка в `:core-settings` — отверг, странная разнесённость, generated-классы застрянут в `:app`.
- Supports: D2 user-spec.

### D2. Путь proto-файла — внутри `:core-settings`, не `:app`
**Decision:** `core-settings/src/main/proto/app_settings.proto`. Protobuf gradle-плагин подключён только в `:core-settings/build.gradle.kts`.
**Rationale:** Следствие D1 — плагин там, где proto. Уточнение с пользователем Phase 3 clarification.
**Alternatives considered:** путь `app/src/main/proto/` из текста AC-3 — опечатка (case-след от Gallery).
- Supports: AC-3 (уточнение пути, не деviation по смыслу).

### D3. Ключ per-model overrides — `Model.modelId`
**Decision:** `map<string, PerModelSettings>` key = `Model.modelId` (например, `litert-community/gemma-4-E2B-it-litert-lm`).
**Rationale:** Устойчив к ручному переименованию `modelName` в allowlist.
**Alternatives considered:** `modelName` — проще, но теряется при переименовании.
- Supports: D3 user-spec, AC-3.

### D4. `PerModelSettings` — все поля `optional` (proto3 explicit optional)
**Decision:** Каждое поле `PerModelSettings` помечено `optional`. Отсутствующее поле = «нет override, использовать allowlist-default».
**Rationale:** Позволяет хранить только реально изменённые поля; семантика «Default» (удаление записи из map) однозначна; корректно мерджится в `effectiveConfig(defaults, overrides)`.
**Alternatives considered:** required + sentinel (`max_tokens = -1 означает default`) — sentinels хрупки, мешают валидации диапазонов.
- Supports: AC-4, AC-21.

### D5. AudioRecord (не MediaRecorder), raw PCM 16 kHz mono
**Decision:** `AudioRecord(CHANNEL_IN_MONO, ENCODING_PCM_16BIT, sampleRate=16000)` → raw PCM `ByteArray` → `helper.runInference(audioClips = listOf(bytes))`.
**Rationale:** litertlm ест raw PCM напрямую, MediaRecorder потребовал бы AAC→PCM конверсию. Gallery делает так же.
**Alternatives considered:** MediaRecorder — отверг, лишний слой.
- Supports: D7 user-spec.

### D6. Attachments — in-memory (Bitmap + ByteArray в VM StateFlow)
**Decision:** `ChatViewModel.attachments: StateFlow<List<Attachment>>`, где `Attachment = sealed class { Image(Bitmap), Audio(ByteArray, Long) }`. На диск не пишем. CameraX через `ImageCapture.takePicture(executor, OnImageCapturedCallback)` возвращает `ImageProxy.toBitmap()` в память.
**Rationale:** Упрощает lifecycle, нет cleanup-кода, нет FileProvider. Rotation survive через VM. OOM-риск снижен downscale до 1024×1024.
**Alternatives considered:** запись в `filesDir/quick/` + cleanup — отверг, лишняя сложность для эфемерной задачи.
- Supports: D1 user-spec.

### D7. Photo Picker — `ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)`
**Decision:** Использовать Android Photo Picker. Permission не требуется. При выборе >10 UI-контракт сам обрезает; нашим кодом ловим `result.size > 10` как defensive check и показываем snackbar.
**Rationale:** Работает на API 31+ нативно (через Google Play update на старых девайсах), не требует READ_MEDIA_IMAGES.
**Alternatives considered:** `ACTION_PICK` + READ_MEDIA_IMAGES — отверг, лишний permission request.
- Supports: D10 user-spec, AC-10.

### D8. compose-richtext-commonmark для markdown + thinking + about
**Decision:** Зависимости `com.halilibo.richtext:richtext-commonmark:1.0.0-alpha02` + `richtext-ui-material3:1.0.0-alpha02`. Используется в `MessageBubble` (assistant text), `ThinkingBlock` (thinking text), `AboutScreen` (assets/about.md).
**Rationale:** Gallery использует эту же версию; стабилизирована на alpha02 > года. Поддерживает code-blocks, bold, lists — то что нужно для ответа LLM и для манифеста.
**Alternatives considered:** plain `Text` — теряется форматирование ответов; свой markdown-парсер — YAGNI.
**Risk mitigation:** R3 — при несовместимости с Compose BOM 2026.03.00 fallback на plain `Text` до Phase 3 (функция thinking деградирует визуально, не ломает сборку).
- Supports: D9 user-spec, AC-6, AC-7.

### D9. Thinking — поле `Message.thinkingText`, collapsible UI-блок над основным текстом
**Decision:** `Message` data class получает `thinkingText: String? = null`. `ChatViewModel.resultListener` накапливает 3-й аргумент (`partialThinkingResult: String?`) в отдельный `StringBuilder`, пишет в `Message.thinkingText` при каждом onPartial. `ThinkingBlock` composable (порт `MessageBodyThinking.kt`) — collapsible, левая вертикальная линия (drawBehind с `outlineVariant`), приглушённый текст, auto-expand при `inProgress=true`.
**Rationale:** Thinking-канал уже прокинут API в Phase 1, `ChatViewModel` просто игнорирует 3-й аргумент. Минимально инвазивное расширение.
**Alternatives considered:** отдельное сообщение-пузырь в ленте — отверг, нарушает chat-структуру и осложняет finalization.
- Supports: AC-7, AC-14, AC-18.

### D10. Автоскролл — `LazyColumn` + `LaunchedEffect(size, lastLength)`
**Decision:** `LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length)` внутри ChatScreen → `listState.animateScrollToItem(messages.lastIndex)`. Триггерится при добавлении нового сообщения и при росте длины последнего (стрим).
**Rationale:** Стандартный Compose-паттерн; `animateScrollToItem` учитывает уже-видимость (не «прыгает», если пользователь сам прокрутил вверх — `LazyListState` не форсит).
**Alternatives considered:** nested-scroll с кастомным controller — overkill.
- Supports: AC-8.

### D11. Runtime permissions — standard Compose pattern per-permission
**Decision:** `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` на каждое (CAMERA, RECORD_AUDIO). Check через `ContextCompat.checkSelfPermission`. При `denied` — snackbar («Разрешите доступ к камере» / «Разрешите доступ к микрофону»). При denied-perma (`shouldShowRequestPermissionRationale=false` после первого отказа) — snackbar со ссылкой в системные настройки (`Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)`). Photo Picker permission не требует.
**Rationale:** Стандартный Android-паттерн; Gallery так делает.
- Supports: AC-15.

### D12. Heavy setting reinit — модальный `AlertDialog` + неотменяемый `ReinitProgressDialog`
**Decision:** При подтверждении тяжёлого изменения — `HeavyChangeDialog` с пояснением «~5–30 сек, контекст текущего чата будет сброшен», кнопки «Применить»/«Отмена». После confirm — `ReinitProgressDialog` (модальный, без кнопки отмены, `CircularProgressIndicator` + текст «Переинициализация модели…») на время `registry.cleanup` + `registry.initialize`.
**Rationale:** Операция короткая; cancel в середине reinit может оставить engine в inconsistent state. Блокирующий UI предотвращает случайный Send в промежутке (crash при send на невалидный engine).
**Alternatives considered:** неблокирующий inline overlay — отверг, риск пользовательской ошибки.
- Supports: AC-4, AC-21.

### D13. AC-20 — микрофон disabled при вложенном audio (без snackbar)
**Decision:** Если `attachments` содержит элемент типа `Audio` — `IconButton` микрофона `enabled = false`, `contentDescription = "Максимум один аудио-клип на сообщение"`. Snackbar не показываем.
**Rationale:** ux-guidelines «whitespace over ornament, минимализм»; visual affordance (серая иконка) достаточен.
**Alternatives considered:** snackbar на тап — отверг, лишний noise.
- Supports: AC-20.

### D14. Audio lifecycle — `DisposableEffect.onDispose` + `LifecycleEventEffect(ON_PAUSE)`, без `TelephonyCallback`
**Decision:** `DisposableEffect(recording) { onDispose { recorder?.release() } }` + `LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { if (recording) { release(); dismissSheet() } }`. `READ_PHONE_STATE` не запрашиваем.
**Rationale:** `ON_PAUSE` срабатывает при входящем звонке, сворачивании приложения, блокировке экрана — покрывает все кейсы AC-19. `TelephonyCallback` требует `READ_PHONE_STATE` (лишний permission-запрос, в user-spec Constraints явно сказано «AudioRecord без дополнительных permission»).
**Alternatives considered:** `TelephonyCallback.onCallStateChanged` — отверг, лишний permission.
- Supports: AC-19.

### D15. Начальная классификация «лёгкие» vs «тяжёлые» поля
**Decision:** Initial:
- **Лёгкие** (применяются на следующий `send`, без reinit): `temperature, topK, topP, maxTokens, systemPromptDefault`.
- **Тяжёлые** (требуют `cleanup + initialize`): `accelerator, enableThinking`.

В ходе реализации Task 10 — ручной smoke на Honor 200: проверить, применяется ли `enableThinking` на лету через `resetConversation` с новым `Contents`/config. Если да — перенести в «лёгкие», зафиксировать результат в `decisions.md` (и в финальной `tech-spec.md` при next iteration).
**Rationale:** Гипотеза из D6 user-spec; финализация — по факту.
**Alternatives considered:** всё через reinit — безопасно, UX страдает. Всё лёгкое — риск crash engine на сменах backend.
- Supports: AC-4, AC-21.

### D16. `effectiveConfig(defaults, overrides)` — pure function, testable
**Decision:** `object EffectiveConfig { fun merge(defaults: Map<String, Any>, overrides: PerModelSettings?): Map<String, Any> }` — копирует `defaults` и поверх накладывает non-null (hasField) поля из `overrides`. Pure, без сайд-эффектов. Вызывается из `ChatViewModel.applyEffectiveConfig()` перед `helper.runInference` и из `InferenceSettingsBottomSheet` при отображении текущих значений.
**Rationale:** Единая семантика merge; легко покрывается `EffectiveConfigTest`.
- Supports: AC-4, AC-17, AC-21.

### D17. AboutScreen — compose-richtext + `assets/about.md`
**Decision:** `AboutScreen` читает `context.assets.open("about.md").bufferedReader().use { it.readText() }` в `LaunchedEffect(Unit)` (кэш внутри state), рендерит через `RichText { Markdown(text) }`. Футер — `Text(BuildConfig.VERSION_NAME)` + статичная атрибуция к Google AI Edge Gallery / Gemma / LiteRT-LM (из strings.xml). Destination `"about"` в NavHost, вход — кнопка на `ModelManagerScreen` TopAppBar.
**Rationale:** Пользователь правит `about.md` напрямую, rebuild APK — обновлённый текст. Никакого отдельного editor-экрана.
**Alternatives considered:** hardcoded composable — отверг, плохо для редактирования манифеста.
- Supports: D5, D11 user-spec, AC-5, AC-6, US-7.

### D18. `app_name` изменение — только `strings.xml`
**Decision:** `res/values/strings.xml` → `<string name="app_name">Sanctum Machina</string>`. `applicationId`, `namespace`, package `app.sanctum.machina` не трогаем.
**Rationale:** launcher-label берётся из `@string/app_name`; технические идентификаторы ломают upgrade-path для уже установленного APK.
- Supports: D12 user-spec, AC-2.

### D19. Тема — только `isSystemInDarkTheme()` (без override)
**Decision:** Оставляем логику Phase 1: `isSystemInDarkTheme()` + `dynamicColorScheme` на API 31+. Переключатель в Phase 2 не добавляется. Proto3 `AppSettings` позволит расширить полем `Theme theme` без миграции позже.
**Rationale:** YAGNI. user-spec Constraints явно откладывает theme switcher.
- Supports: D6 user-spec.

### D20. Unit-тесты `:core-runtime` с Bitmap — через Robolectric
**Decision:** Добавить `org.robolectric:robolectric:4.12` как `testImplementation` в `:core-runtime`. `MediaUtilsTest` и `MultimodalContentsBuilderTest` размечены `@RunWith(RobolectricTestRunner::class)` — позволяет создать `Bitmap` в JVM-тестах. JUnit 4 сохраняется (D8 Phase 1 откладывает миграцию на JUnit 5). MockK не вводим.
**Rationale:** `Bitmap` в pure JVM не инстанцируется. Альтернатива — выносить pure math в interface и мокировать `Bitmap` — лишняя абстракция.
**Alternatives considered:** instrumentation тесты (`androidTest`) — отверг, solo dev не будет гонять по эмулятору регулярно.
- Supports: AC-17.

### D21. Lifecycle engine reinit — через `ChatViewModel.viewModelScope` + `ModelRegistry`
**Decision:** Heavy-change reinit полностью инкапсулирован в `ChatViewModel.applyHeavySetting(key, value)`:
1. `_reinitInProgress.value = true` (триггерит `ReinitProgressDialog`).
2. `if (streaming) helper.stopResponse(model)` — прервать активный стрим (AC-21 note).
3. Обновить `model.configValues[key] = value`.
4. `registry.cleanup(modelName)` — suspend.
5. `registry.initialize(modelName)` — suspend.
6. `_reinitInProgress.value = false`.

При crash в step 4/5 — catch в `ViewModelScope` exception handler → показ ошибки через snackbar, dialog dismiss, engine-state переводится в `Failed` (UI уйдёт в `FailedContent`, как в Phase 1).
**Rationale:** `ModelRegistry` API уже содержит `cleanup` + `initialize` как suspend — reinit — последовательность двух вызовов, не новый концепт.
- Supports: AC-4, AC-21.

### D22. `[TECHNICAL]` — рефакторинг сборки `Contents` в отдельный helper
**Decision:** `MultimodalContentsBuilder` вынесен в `:core-runtime/common/` как testable pure-функция. `LlmChatModelHelper.runInference` вызывает его вместо inline-кода Phase 1 (строки 255-267).
**Rationale:** Сейчас сборка `List<Content>` inline в `LlmChatModelHelper`, не тестируется. Вынос — необходимое условие для `MultimodalContentsBuilderTest` из AC-17. `[TECHNICAL]` — не выводится из конкретного пользовательского требования, но обеспечивает покрытие AC-17.
- `[TECHNICAL]` justification: minimal refactor (одно вынесение), не меняет поведение, testability.

## Data Models

### Proto schema — `core-settings/src/main/proto/app_settings.proto`

```proto
syntax = "proto3";

package app.sanctum.machina.settings;

option java_package = "app.sanctum.machina.core.settings.proto";
option java_multiple_files = true;

message PerModelSettings {
  optional int32 max_tokens = 1;
  optional int32 top_k = 2;
  optional float top_p = 3;
  optional float temperature = 4;
  optional bool enable_thinking = 5;
  optional string accelerator = 6;              // "GPU" | "CPU"
  optional string system_prompt_default = 7;
}

message AppSettings {
  map<string, PerModelSettings> per_model_overrides = 1;  // key = Model.modelId
}
```

### `:core-settings` public API

```kotlin
interface AppSettingsRepository {
  fun observePerModelSettings(modelId: String): Flow<PerModelSettings?>
  suspend fun savePerModelSettings(modelId: String, settings: PerModelSettings)
  suspend fun resetPerModelSettings(modelId: String)
}
```

### `:app` — Attachment sealed hierarchy

```kotlin
sealed class Attachment {
  data class Image(val bitmap: Bitmap) : Attachment()
  data class Audio(val pcm: ByteArray, val durationMs: Long) : Attachment()
}
```

### `:app` — Message extension

`ui/chat/Message.kt`:
```kotlin
data class Message(
  val role: MessageRole,
  val text: String,
  val streaming: Boolean = false,
  val interrupted: Boolean = false,
  val footer: String? = null,
  val thinkingText: String? = null,   // NEW
)
```

## Dependencies

### New packages

- `com.google.protobuf` (gradle plugin, 0.9.5) — codegen для proto. Подключается в `core-settings/build.gradle.kts`.
- `com.google.protobuf:protobuf-javalite` (4.26.1) — runtime для generated-классов.
- `androidx.datastore:datastore` (1.1.7) — Proto DataStore.
- `androidx.camera:camera-core` / `camera-camera2` / `camera-lifecycle` / `camera-view` (1.4.2) — CameraX.
- `com.halilibo.richtext:richtext-commonmark` (1.0.0-alpha02) + `richtext-ui-material3` (1.0.0-alpha02) — markdown-рендеринг.
- `androidx.lifecycle:lifecycle-runtime-compose` (из существующего BOM) — `LifecycleEventEffect`.
- `org.robolectric:robolectric` (4.12) — **только** `testImplementation` в `:core-runtime`.

### Using existing (from project)

- `:core-runtime` — `ModelRegistry`, `LlmChatModelHelper`, `Contents API`, `ResultListener` с thinking (Phase 1).
- `Config.kt`, `ConfigKey`, `ConfigKeys.*`, `createLlmChatConfigs` — готовые defaults / ключи.
- Hilt 2.57.1 + KSP — infrastructure Phase 1.
- Material 3 BOM 2026.03.00, `material-icons-extended`, `navigation-compose` — Phase 1.
- `androidx.activity.compose` — `rememberLauncherForActivityResult`.

## Testing Strategy

**Feature size:** L

### Unit tests

**:core-settings:**
- `AppSettingsRepositoryTest` (in-memory DataStore через `DataStoreFactory.create(scope, produceFile = { File(tmp, "test.pb") })`):
  - `save → observe` возвращает то же значение (round-trip).
  - `save для modelId-A, save для modelId-B` — observe каждого независимо.
  - `reset(modelId)` — observe возвращает null, map не содержит key.
  - merge: сохраняем частичный `PerModelSettings` (только temperature), читаем — остальные поля unset (optional hasField=false).
  - concurrent writes: последовательная консистентность (две подряд save — читается последняя).

**:core-runtime (расширение):**
- `AllowlistLoaderTest` — новые кейсы:
  - `llmSupportImage=true/false` (и комбинации для Audio/Thinking) корректно парсятся.
  - Отсутствие поля в JSON → `false` (default).
  - `fixtureMatchesProductionAsset` остаётся зелёным после обновления fixture.
- `EffectiveConfigTest` (в `:app` или `:core-runtime` по решению реализации):
  - `overrides=null` → defaults as-is.
  - частичные overrides → merged (unset поля остаются defaults).
  - boolean override `enable_thinking=false` при `defaults[ENABLE_THINKING]=true` — effective=false.
- `MediaUtilsTest` (Robolectric, `@RunWith(RobolectricTestRunner::class)`):
  - `decodeSampledBitmapFromUri` на test PNG 2048×2048, `reqWidth=1024, reqHeight=1024` → bitmap ≤ 1024 по каждой оси.
  - `rotateBitmap` с ExifInterface orientations 90/180/270/normal → pixel comparison на контрольном сэмпле.
  - `calculateInSampleSize` boundary: exact fit, 2x, 4x, 8x.
- `AudioClipTest`:
  - round-trip `AudioClip(byteArrayOf(), 16000)` — пустой `audioData`, sampleRate сохраняется.
  - нечётный `audioData.size` (non-aligned 16-bit) — не падает, хранит as-is (формат-agnostic контейнер).
- `MultimodalContentsBuilderTest`:
  - `(text, listOf(bitmap), listOf(bytes))` → `Content.ImageBytes + Content.AudioBytes + Content.Text` (3 items, в этом порядке).
  - пустой `text` — `Content.Text` не добавляется (phase-1 behavior сохранён).
  - пустые images/audio, только text → `Content.Text` один.

### Integration tests

**None.** Phase 2 не вводит инфраструктуры, требующей integration-тестов. litertlm native в JVM не воспроизводим (явное exclusion в user-spec «Тестирование» и подтверждено architecture.md § testing).

### E2E tests

**None.** Compose UI-тесты для solo dev на фазе частых UI-изменений неоправданы (явное exclusion в user-spec). Multimodal end-to-end — manual smoke на Honor 200 через AC-13, AC-14, US-1..US-7.

## Agent Verification Plan

**Source:** user-spec «Как проверить» section.

### Verification approach

Агент запускает:
- `./gradlew build` — `BUILD SUCCESSFUL`.
- `./gradlew :core-runtime:test` — все прежние + новые зелёные.
- `./gradlew :core-settings:test` — `AppSettingsRepositoryTest` зелёный.
- `./gradlew lintDebug` — нет Error-level issues (особенно `MissingPermission`, `MissingClass` для `:core-settings`).
- `./gradlew :app:assembleDebug` — APK собран.
- `aapt dump permissions app/build/outputs/apk/debug/app-debug.apk` — содержит CAMERA, RECORD_AUDIO.

Per-task smoke checks — в `Verify-smoke` каждого Task.
**Post-deploy verification — N/A.** Мобильное приложение, distribution = manual APK transfer на Honor 200. Агент не может установить APK и взаимодействовать с UI на физическом устройстве. Все end-to-end проверки (US-1..US-7, AC-13, AC-14, AC-16) — в «Verify-user» соответствующих tasks и в финальном AC-22 (user approval).

### Tools required

`./gradlew` CLI, bash, `aapt` (входит в Android SDK build-tools). **MCP-инструменты (Playwright, Telegram) не применимы** — нативное Android-приложение на физическом устройстве пользователя.

## Risks

| Risk | Mitigation |
|------|-----------|
| R1. AC-1 fix: fixture обновлён, prod JSON — нет (или наоборот) — `fixtureMatchesProductionAsset` падает | Обновить fixture **и** prod asset одновременно в Task 1; добавить explicit тест на JSON→Model mapping |
| R2. Heavy reinit (`accelerator`/`enableThinking`) litertlm 0.10.0 может крашнуть engine | `HeavyChangeDialog` + `ReinitProgressDialog`; при crash — exception handler в VM переводит engine в `Failed`, UI → `FailedContent`; manual smoke на Honor 200 финализирует классификацию D15 |
| R3. compose-richtext alpha02 несовместим с Compose BOM 2026.03.00 | Verify-smoke на Task 5 (сборка + render snapshot в preview); fallback на plain `Text` (R3 user-spec) |
| R4. Thinking-канал `message.channels["thought"]` для Gemma-4 приходит null в litertlm 0.10.0 | Manual smoke на Honor 200 (AC-14); при проблеме — помечаем AC-14 как manual-verify-only, код готов |
| R5. OOM при 10 фото full-res на 8GB RAM | Downscale 1024×1024 в `decodeSampledBitmapFromUri` + `MAX_IMAGE_COUNT=10` (Consts.kt, Phase 1); Honor 200 (12GB) — основное устройство теста |
| R6. Proto plugin 0.9.5 + AGP 8.8.2 incompatibility | Проверка на первом `./gradlew :core-settings:build` (Task 2); fallback 0.9.4 или 0.9.6+ |
| R7. `:core-settings` library-manifest hygiene — lint `MissingPermission` / `MissingClass` false-positive | Воспроизвести pattern `:core-runtime/src/main/AndroidManifest.xml` (patterns.md § library-module manifest hygiene); `core-runtime` `work-runtime-ktx` exposed as `api` paradigm — для `:core-settings` актуально только если будут classes из `:app`/`:core-runtime` (нет — repository-only) |
| R8. CameraX lifecycle коллапс при rotation экрана | `LocalLifecycleOwner.current` + bind/unbind в `DisposableEffect(cameraProvider)`; тестируется вручную (rotation на Honor 200) |
| R9. AudioRecord отклоняется на MIUI/HarmonyOS из-за restricted mic access | Honor 200 (Android 14+, MagicOS) — основной target; сообщение об ошибке в snackbar через `AudioRecord.getState() != STATE_INITIALIZED` check; не блокер |
| R10. `optional` в proto3 javalite 4.26.1 поддержан (нужна версия ≥ 3.15) | javalite 4.26.1 поддерживает `optional` (since 3.15 explicitly); verify — first build Task 2 показывает generated `hasMaxTokens()` / `getMaxTokens()` methods |
| R11. Attachments теряются при process death (VM re-creation) | user-spec явно допускает эфемерность (D1 user-spec: «attachments переживают rotation через VM», но process death — допустимая потеря); документируем, не закрываем |
| R12. HeavyChangeDialog во время стрима — `stopResponse` + reinit may race with in-flight callback | VM вызывает `stopResponse` перед `cleanup`; `ResultListener` после cleanup игнорирует late-arriving callbacks (stale-instance guard Phase 1 D3 уже есть в DefaultModelRegistry) |

## User-Spec Deviations

### 1. Путь proto-файла — `:core-settings`, не `:app`
- **AC-3:** user-spec текст: «`app/src/main/proto/app_settings.proto`». Tech-spec: `core-settings/src/main/proto/app_settings.proto`. Причина: чистая изоляция, protobuf-плагин подключён только в `:core-settings`. Подтверждено с пользователем в Phase 3 clarification. → **Не требует approval** (разрешено в ходе планирования; зафиксировано как D1+D2).

### 2. Новая test-зависимость Robolectric 4.12
- **AC-17:** user-spec перечисляет `MediaUtilsTest` как JVM unit-тест, но Robolectric не упомянут явно в Constraints/Dependencies. Tech-spec добавляет `org.robolectric:robolectric:4.12` в `:core-runtime/testImplementation` для возможности инстанцировать `Bitmap` в JVM. → **[PENDING USER APPROVAL]**

### 3. `MultimodalContentsBuilder` рефакторинг — вынос из `LlmChatModelHelper`
- **Не в user-spec:** user-spec Constraint § `:core-runtime` разрешает «портирование media-утилит в `:core-runtime/common/`» и «новые unit-тесты». Вынос inline-логики сборки `List<Content>` из `LlmChatModelHelper.runInference` в pure helper `MultimodalContentsBuilder` — минимальный рефакторинг Phase-1 кода, необходимый для покрытия `MultimodalContentsBuilderTest` из AC-17 (без выноса тестировать inline-код невозможно). → **[PENDING USER APPROVAL]**

### 4. Классификация `enableThinking` как heavy initially
- **AC-21:** user-spec: «тяжёлое, возможно перенести в лёгкие по итогу теста». Tech-spec D15 классифицирует как heavy initially; финализация после ручного smoke на Honor 200 в Task 10, результат фиксируется в `decisions.md` по завершении задачи. → **Прямое исполнение user-spec**, не deviation.

## Acceptance Criteria

Технические критерии приёмки (дополняют пользовательские AC-1..AC-25 из user-spec):

- [ ] **TAC-1.** `./gradlew build` завершается `BUILD SUCCESSFUL`, без `warning-as-error`.
- [ ] **TAC-2.** `./gradlew :core-runtime:test` — все тесты зелёные (прежние + новые из AC-17).
- [ ] **TAC-3.** `./gradlew :core-settings:test` — `AppSettingsRepositoryTest` зелёный.
- [ ] **TAC-4.** `./gradlew lintDebug` — нет Error-level issues; `:core-settings` проходит без `MissingPermission` / `MissingClass`.
- [ ] **TAC-5.** `fixtureMatchesProductionAsset` в `AllowlistLoaderTest` зелёный после Task 1 (fixture синхронизирован с prod).
- [ ] **TAC-6.** `rg -l "androidx.compose|androidx.activity" core-runtime/src/main` → пустой (module boundary `:core-runtime` ↔ UI).
- [ ] **TAC-7.** `rg -l "androidx.compose|androidx.activity" core-settings/src/main` → пустой (module boundary `:core-settings` ↔ UI).
- [ ] **TAC-8.** APK `app/build/outputs/apk/debug/app-debug.apk` собран, `aapt dump permissions` содержит `CAMERA` и `RECORD_AUDIO`.
- [ ] **TAC-9.** В `app/src/main/kotlin/**/*.kt` нет hardcoded Russian-литералов в Compose: `rg -n "Text\(\"[А-Яа-я]" app/src/main` → пустой; все строки через `stringResource(R.string.xxx)`.
- [ ] **TAC-10.** `app_settings.proto` компилируется, generated-классы `AppSettings`, `PerModelSettings` доступны в `:core-settings`; `hasMaxTokens() / getMaxTokens()`-методы присутствуют (explicit optional proto3).

## Implementation Tasks

### Wave 1 (инфра, блокер multimodal — параллельно)

#### Task 1: AC-1 — починить парсинг allowlist `llmSupport*` полей
- **Description:** В `AllowedModel.toModel()` проброс `llmSupportImage/Audio/Thinking` из JSON в `Model`. Расширить `AllowlistLoaderTest` кейсами. Синхронизировать fixture с prod. Блокер — без этого multimodal в engine не стартует.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `./gradlew :core-runtime:test --tests AllowlistLoaderTest` → все зелёные, включая новые кейсы для 3 полей.
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/ModelAllowlist.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/AllowlistLoaderTest.kt`, `core-runtime/src/test/resources/model_allowlist_fixture.json`
- **Files to read:** `core-runtime/src/main/assets/model_allowlist.json`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Model.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt` (использование `model.llmSupportImage` в `initialize`)

#### Task 2: Создать gradle-модуль `:core-settings` с Proto DataStore
- **Description:** Новый модуль `:core-settings` с `build.gradle.kts` (protobuf 0.9.5 plugin, datastore 1.1.7, hilt), `AndroidManifest.xml` (library hygiene), proto-схема `app_settings.proto`, `AppSettingsSerializer`, `AppSettingsRepository` interface + `DefaultAppSettingsRepository`, Hilt `CoreSettingsModule`. Зарегистрировать в `settings.gradle.kts`, добавить зависимость в `:app`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `./gradlew :core-settings:build` → SUCCESS; `ls core-settings/build/generated/source/proto/main/java/app/sanctum/machina/core/settings/proto/` содержит `AppSettings.java`, `PerModelSettings.java`; `./gradlew :core-settings:test --tests AppSettingsRepositoryTest` → зелёный.
- **Files to modify:** `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts` (root, `alias(libs.plugins.protobuf) apply false`), `app/build.gradle.kts` (+ `implementation(project(":core-settings"))`)
- **Files to create:** `core-settings/build.gradle.kts`, `core-settings/src/main/AndroidManifest.xml`, `core-settings/src/main/proto/app_settings.proto`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/AppSettingsSerializer.kt`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/AppSettingsRepository.kt`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/DefaultAppSettingsRepository.kt`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/di/CoreSettingsModule.kt`, `core-settings/src/test/kotlin/app/sanctum/machina/core/settings/AppSettingsRepositoryTest.kt`
- **Files to read:** `core-runtime/build.gradle.kts`, `core-runtime/src/main/AndroidManifest.xml`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/di/CoreRuntimeModule.kt`, `gallery-source/.../proto/settings.proto`, `gallery-source/.../data/SettingsSerializer.kt`

#### Task 3: Порт media-утилит и AudioClip в `:core-runtime/common/`
- **Description:** Скопировать из `gallery-source/common/Utils.kt` функции `decodeSampledBitmapFromUri`, `rotateBitmap`, `calculateInSampleSize` в `MediaUtils.kt`. Скопировать `AudioClip` из `Types.kt`. Unit-тесты через Robolectric (новый testImplementation dep).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `./gradlew :core-runtime:test --tests MediaUtilsTest --tests AudioClipTest` → зелёные.
- **Files to modify:** `gradle/libs.versions.toml` (+ robolectric), `core-runtime/build.gradle.kts` (testImplementation)
- **Files to create:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MediaUtils.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/AudioClip.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/common/MediaUtilsTest.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/common/AudioClipTest.kt`, `core-runtime/src/test/resources/test-image.jpg`
- **Files to read:** `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/common/Utils.kt` (lines 260-310), `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/common/Types.kt`

#### Task 4: `MultimodalContentsBuilder` + рефактор `LlmChatModelHelper.runInference`
- **Description:** Вынести inline-сборку `List<Content>` из `LlmChatModelHelper.runInference` (Phase 1) в pure-функцию `MultimodalContentsBuilder.build(text, images, audio)` в `:core-runtime/common/`. Unit-тест `MultimodalContentsBuilderTest`. [TECHNICAL] — testability (D22).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `./gradlew :core-runtime:test --tests MultimodalContentsBuilderTest` → зелёный; `./gradlew :core-runtime:test` — прежние тесты не сломались.
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/inference/LlmChatModelHelper.kt`
- **Files to create:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MultimodalContentsBuilder.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/common/MultimodalContentsBuilderTest.kt`
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/inference/LlmChatModelHelper.kt` (строки 255-267)

### Wave 2 (ресурсы + разрешения — параллельно, после Wave 1)

#### Task 5: App naming, strings, manifest permissions, CameraX+richtext deps
- **Description:** `strings.xml` → `app_name = "Sanctum Machina"` + новые строки Phase 2 (7 полей settings, кнопки, permissions errors, thinking label, attachment labels, about, heavy-change dialog). `AndroidManifest.xml` в `:app` — `CAMERA`, `RECORD_AUDIO`, `<uses-feature camera required="false">`. `libs.versions.toml` + `app/build.gradle.kts` — CameraX 1.4.2 (4 артефакта), richtext-commonmark 1.0.0-alpha02 + richtext-ui-material3, lifecycle-runtime-compose.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor
- **Verify-smoke:** `./gradlew :app:assembleDebug` → SUCCESS; `aapt dump permissions app-debug.apk` показывает CAMERA + RECORD_AUDIO.
- **Verify-user:** установить APK на Honor 200 — launcher-label «Sanctum Machina» (AC-2).
- **Files to modify:** `app/src/main/res/values/strings.xml`, `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`
- **Files to read:** `.claude/skills/project-knowledge/references/ux-guidelines.md` (tone), `.claude/skills/project-knowledge/references/patterns.md` (manifest hygiene)

### Wave 3 (UI, после Wave 2 — tasks внутри параллельны по зависимостям)

#### Task 6: AboutScreen + navigation entry + ModelManagerScreen TopAppBar action
- **Description:** `AboutScreen.kt` — scrollable, читает `assets/about.md` через `context.assets`, рендерит через `RichText { Markdown(text) }`, футер с `BuildConfig.VERSION_NAME` + атрибуция. Destination `"about"` в NavHost. На `ModelManagerScreen` TopAppBar — IconButton «О приложении» (icon-only, Material Icons `Info`), `onClick = { navController.navigate("about") }`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `./gradlew :app:assembleDebug` → SUCCESS.
- **Verify-user:** APK → ModelManager → тап «О приложении» → открывается AboutScreen с текстом из `about.md` и версией в футере (AC-5, AC-6, US-7).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/about/AboutScreen.kt`, `app/src/main/assets/about.md`
- **Files to read:** `gallery-source/.../ui/common/MarkdownText.kt` (richtext usage pattern)

#### Task 7: MultimodalInputBar + Attachment state + Photo Picker + ThumbnailStrip
- **Description:** `MultimodalInputBar` — замена Phase-1 `ChatInputRow`: `OutlinedTextField` + 3 `IconButton` (camera/gallery/mic, условно видимые по `model.llmSupportImage/Audio`) + Send. Photo Picker через `ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)`. `ThumbnailStrip` — `LazyRow` миниатюр с ✕. `ChatViewModel.attachments: StateFlow<List<Attachment>>`, методы `addImages(List<Uri>)` (через `decodeSampledBitmapFromUri(1024, 1024)`) / `removeAttachment(idx)`. Send disabled при пустом тексте и пустом attachments. При >10 фото — первые 10 + snackbar.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** APK → ChatScreen → тап галерея → Photo Picker → выбор 3 фото → 3 миниатюры → ✕ убирает → Send enabled только если text или attachments (AC-9, AC-10, AC-18).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/res/values/strings.xml`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/MultimodalInputBar.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/Attachment.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ThumbnailStrip.kt`
- **Files to read:** `gallery-source/.../ui/common/chat/MessageInputText.kt` (photo picker paths)

#### Task 8: CameraBottomSheet (CameraX)
- **Description:** Bottom sheet с `CameraX PreviewView`, кнопки «Снять» / «Закрыть». `ImageCapture.takePicture(executor, OnImageCapturedCallback)` → `ImageProxy.toBitmap()` → `rotateBitmap(exifOrientation)` → `viewModel.addImage(bitmap)` → dismiss. Permission-check CAMERA перед открытием, `rememberLauncherForActivityResult(RequestPermission)`, snackbar на отказ. Lifecycle bind/unbind в `DisposableEffect`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** APK → ChatScreen → тап камеры → (первый раз) запрос CAMERA → bottom sheet с live preview → «Снять» → миниатюра появляется (AC-11, AC-15).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`, `app/src/main/res/values/strings.xml`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/CameraBottomSheet.kt`
- **Files to read:** `gallery-source/.../ui/common/LiveCameraView.kt`, `gallery-source/.../ui/common/chat/MessageInputText.kt` (camera sheet integration)

#### Task 9: AudioRecorderBottomSheet (AudioRecord, lifecycle-aware)
- **Description:** Bottom sheet с таймером до 30 сек. `AudioRecord(CHANNEL_IN_MONO, ENCODING_PCM_16BIT, 16000)` в bg-потоке через `Dispatchers.IO`, автостоп на 30. «Остановить» → `ByteArray` в `viewModel.addAudio(pcm, durationMs)`. `DisposableEffect + LifecycleEventEffect(ON_PAUSE)` — прерывание записи при уходе в фон/звонке (AC-19). Permission-check RECORD_AUDIO. Иконка микрофона disabled при уже-вложенном audio (D13, AC-20).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** APK → ChatScreen → тап микрофона → запрос RECORD_AUDIO → sheet с таймером → говорю 5с → «Остановить» → миниатюра с длительностью; тап при вложенном audio → disabled; входящий звонок → sheet закрывается, attachment не добавляется (AC-12, AC-15, AC-19, AC-20).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/res/values/strings.xml`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/AudioRecorderBottomSheet.kt`
- **Files to read:** `gallery-source/.../ui/common/chat/AudioRecorderPanel.kt`

#### Task 10: ThinkingBlock + MessageBubble markdown + ChatViewModel thinking accumulation
- **Description:** `ThinkingBlock.kt` — collapsible, левая вертикальная линия через `drawBehind(outlineVariant)`, приглушённый текст, `RichText { Markdown(thinkingText) }`, auto-expand при `inProgress=true`. `MessageBubble` — при `message.thinkingText != null && model.llmSupportThinking` рендерит `ThinkingBlock` над основным `RichText { Markdown(message.text) }`. `Message` data class + `thinkingText`. `ChatViewModel` — отдельный `StringBuilder` для thinking в `resultListener`; пропускает накопление если `!model.llmSupportThinking || !effectiveConfig[ENABLE_THINKING]`. Ручной smoke на Honor 200 — проверить, можно ли `enableThinking` применять на лету (D15), зафиксировать в `decisions.md`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-user:** APK → ChatScreen с моделью `llmSupportThinking=true` и `enableThinking=true` (настройка из Task 11) → текстовый вопрос «подумай и ответь» → над ответом collapsible блок «Показать ризонинг» (AC-7, AC-14, AC-18).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/MessageBubble.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/Message.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/res/values/strings.xml`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ThinkingBlock.kt`
- **Files to read:** `gallery-source/.../ui/common/chat/MessageBodyThinking.kt`, `gallery-source/.../ui/common/MarkdownText.kt`

#### Task 11: EffectiveConfig + InferenceSettingsBottomSheet + HeavyChangeDialog + ReinitProgressDialog + autoscroll + ChatScreen final integration
- **Description:** `EffectiveConfig.merge(defaults, overrides)` — pure-функция слияния. `InferenceSettingsBottomSheet` внутри ChatScreen: 7 полей (условно `enableThinking` при `llmSupportThinking`). Читает `repository.observePerModelSettings(modelId)`, показывает effective. «Применить» — лёгкие: `save` + `viewModel.applyLightOverrides()`; тяжёлые: `HeavyChangeDialog` → `ReinitProgressDialog` → `viewModel.applyHeavySetting(key, value)` (stopResponse → cleanup → update configValues → initialize). «Default» — `reset` + возможный reinit при тяжёлом-override. `ChatScreen` TopAppBar: Settings ⚙, Reset ↻, Back. Autoscroll — `LaunchedEffect(messages.size, lastLength)` → `animateScrollToItem`. Финальная интеграция всех Wave-3 composables.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `./gradlew :app:test --tests EffectiveConfigTest` → зелёный; `./gradlew :app:assembleDebug` → SUCCESS.
- **Verify-user:** APK → ChatScreen → ⚙ → bottom sheet → temperature 1.0→0.2 → «Применить» → следующий ответ менее разнообразный; accelerator GPU→CPU → HeavyChangeDialog → confirm → ReinitProgressDialog → чат продолжает работу; «Default» → overrides сброшены; автоскролл следит за стримом (AC-4, AC-8, AC-18, AC-21).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/res/values/strings.xml`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/EffectiveConfig.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/InferenceSettingsBottomSheet.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/HeavyChangeDialog.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ReinitProgressDialog.kt`, `app/src/test/kotlin/app/sanctum/machina/ui/chat/EffectiveConfigTest.kt`
- **Files to read:** `gallery-source/.../ui/common/ConfigDialog.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Config.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`

### Audit Wave

#### Task 12: Code Audit
- **Description:** Full-feature code quality audit. Прочитать все source-файлы Phase 2 (из decisions.md + Files to modify/create). Holistic review: module-boundary (`:core-runtime` и `:core-settings` без Compose/Activity — grep check), lifecycle-hygiene (CameraX bind/unbind, AudioRecord release), autoscroll performance, permission-flow consistency, cross-component duplicate init, shared resources compliance. Report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 13: Security Audit
- **Description:** Full-feature security audit. Прочитать все source-файлы Phase 2. OWASP Top 10: permission misuse (CAMERA/RECORD_AUDIO — requested on-demand, не при старте); attachment handling (in-memory OOM vectors через 10 фото × 1024²; content URI validation в `decodeSampledBitmapFromUri`); DataStore file permissions (private filesDir); Intent handling в AboutScreen; secrets hygiene (нет hardcoded tokens). Report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 14: Test Audit
- **Description:** Full-feature test quality audit. Прочитать все test-файлы. Coverage, meaningful assertions, Robolectric hygiene (test-image fixture, orientation coverage), test pyramid balance (7+ unit-тестов добавляются в Phase 2), `fixtureMatchesProductionAsset` integrity. Report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 15: Pre-deploy QA
- **Description:** Acceptance testing. Запустить: `./gradlew build`, `:core-runtime:test`, `:core-settings:test`, `:app:test`, `lintDebug`, `:app:assembleDebug`. Verify TAC-1..TAC-10 и user-spec AC-1..AC-22 (в части автоматически проверяемого). AC-2, AC-13, AC-14, AC-16, US-1..US-7 — требуют user verification на Honor 200, помечаются в отчёте как deferred-to-user. AC-23..AC-25 (желательные) — отмечаются реализовано/deferred без блокировки. Финальный гейт AC-22 — user approval на устройстве.
- **Skill:** pre-deploy-qa
- **Reviewers:** none

**Deploy — N/A.** Distribution мобильного APK — ручная передача на Honor 200 (architecture.md + deployment.md).
**Post-deploy verification — N/A.** MCP-инструменты (Playwright, Telegram) не применимы к нативному Android-приложению на физическом устройстве. Verification целиком в Verify-user пунктах Tasks 6–11 и AC-22 user approval.
