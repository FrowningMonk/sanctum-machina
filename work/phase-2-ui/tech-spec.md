---
created: 2026-04-16
status: approved
branch: phase/2-ui
size: L
---

# Tech Spec: phase-2-ui (Sanctum Machina)

## Solution

Phase 2 превращает Phase-1 foundation (два примитивных экрана) в полноценную мультимодальную чат-оболочку. Подход — «обёртка»: ядро `:core-runtime` трогаем минимально (починка парсинга allowlist, порт media-утилит, небольшое расширение Config + engine systemPrompt wiring), основная новая работа распределяется между `:app` (Compose UI, ChatViewModel, runtime-permissions) и новым gradle-модулем `:core-settings` (Proto DataStore с per-model overrides). Multimodal Contents API, thinking-канал через `ResultListener`, lifecycle engine (single-active, cleanup/initialize через `DefaultModelRegistry`) — уже готовы из Phase 1, в Phase 2 мы их только используем. Эффективные inference-настройки = allowlist-defaults ∪ per-model overrides из DataStore, ключ — `Model.modelId`. Переключение «тяжёлых» полей (`accelerator`, initially `enableThinking`) триггерит модальный progress-dialog + `cleanup + initialize` engine; «полу-лёгкое» поле `systemPromptDefault` применяется через `resetConversation(systemPrompt=...)` (не reinit, но и не on-the-fly) с явным UX-сигналом; «лёгкие» (`temperature, topK, topP, maxTokens`) — применяются со следующего пользовательского хода без ничего. Attachments (Bitmap из Photo Picker/CameraX, ByteArray из AudioRecord) живут исключительно in-memory в `ChatViewModel.StateFlow<List<Attachment>>`. Привативная гигиена: `android:allowBackup=false`, `dataExtractionRules` (exclude all), markdown link-scheme whitelist (http/https only).

## Architecture

### What we're building/modifying

- **`:core-runtime` (минимальные изменения, полный список)**
  - `data/ModelAllowlist.kt` — починка `AllowedModel.toModel()`: проброс `llmSupportImage/Audio/Thinking` из JSON в `Model` (блокер: без этого `DefaultModelRegistry.initialize` передаёт `supportImage=false` в engine).
  - `data/Config.kt` — новый `ConfigKeys.SYSTEM_PROMPT_DEFAULT` + расширение `createLlmChatConfigs(defaultSystemPrompt: String = "")` — эмитит default systemPrompt в `Model.configValues`.
  - `inference/LlmChatModelHelper.kt` — `resetConversation(systemInstruction: Contents? = null)` уже принимает systemInstruction (Phase 1 API); `initialize` проброс effective systemPrompt из `Model.configValues[SYSTEM_PROMPT_DEFAULT]` в `engine.createConversation(systemInstruction = ...)` при первой инициализации (сейчас передаётся null); рефактор inline-сборки `Contents` в вызов `MultimodalContentsBuilder`.
  - `common/MediaUtils.kt` — порт `decodeSampledBitmapFromUri`, `rotateBitmap`, `calculateInSampleSize`, **`calculatePeakAmplitude`** из `gallery-source/common/Utils.kt`.
  - `common/AudioClip.kt` — порт контейнера `class AudioClip(audioData: ByteArray, sampleRate: Int)` (plain class как в Gallery, без `data class`).
  - `common/MultimodalContentsBuilder.kt` — pure-функция сборки `List<Content>` из `(text, List<Bitmap>, List<ByteArray>)`.
  - **Никакой реархитектуры ядра**: Contents API, thinking-канал в `ResultListener`, lifecycle engine — готовы.
  - **Не портируем** `convertWavToMonoWithMaxSeconds` — он нужен только для сценария импорта WAV-файла из файлсистемы; Phase 2 использует AudioRecord → raw PCM напрямую, WAV-import flow нет (документировано в User-Spec Deviations).

- **`:core-settings` (новый gradle-модуль)**
  - `src/main/proto/app_settings.proto` — схема `AppSettings { map<string, PerModelSettings> per_model_overrides }`.
  - `AppSettingsSerializer` — DataStore serializer.
  - `AppSettingsRepository` (interface) + `DefaultAppSettingsRepository` — публичный API; обрабатывает `IOException` / `CorruptionException` (R13).
  - `di/CoreSettingsModule.kt` — Hilt: `@Provides @Singleton DataStore<AppSettings>`, `@Binds AppSettingsRepository`.
  - `AndroidManifest.xml` — library hygiene (namespace, без permissions; никаких `<application>`-атрибутов, чтобы не перезаписать `:app` при manifest-merge).

- **`:app` (расширение)**
  - Новая директория `ui/chat/`: `MultimodalInputBar.kt`, `Attachment.kt`, `ThumbnailStrip.kt`, `CameraBottomSheet.kt`, `AudioRecorderBottomSheet.kt`, `ThinkingBlock.kt`, `MessageBubble.kt` (**вынос** из `ChatScreen.kt` Phase-1 private composable в отдельный файл), `InferenceSettingsBottomSheet.kt`, `HeavyChangeDialog.kt`, `ReinitProgressDialog.kt`, `EffectiveConfig.kt`, `SafeMarkdown.kt` (scheme-whitelisted rich-text wrapper).
  - Новая `ui/about/AboutScreen.kt`.
  - Расширения existing: `ChatScreen.kt` (TopAppBar actions, host для sheets, autoscroll, MessageBubble extraction), `ChatViewModel.kt` (attachments state, thinking accumulation, override-aware config, heavy/medium-change handling, resetConversation), `Message.kt` (поле `thinkingText`), `SanctumApp.kt` (destination `about`), `ModelManagerScreen.kt` (TopAppBar action «О приложении»), `AndroidManifest.xml` (permissions + `android:allowBackup="false"` + `android:dataExtractionRules`).
  - `strings.xml` — `app_name = "Sanctum Machina"` + новые строки.
  - `assets/about.md` — редактируемый манифест (markdown).
  - `res/xml/data_extraction_rules.xml` — пустой allowlist (блок Google backup / transfer).

### How it works

**Inference flow:** user тапает камеру / галерею / микрофон → Bitmap/ByteArray накапливаются в `ChatViewModel.attachments: StateFlow<List<Attachment>>` → `ThumbnailStrip` отображает миниатюры → user печатает текст + Send → `ChatViewModel.send()` разделяет attachments на `List<Bitmap>` / `List<ByteArray>`, вызывает `helper.runInference(input, images, audioClips)` → `LlmChatModelHelper` через `MultimodalContentsBuilder` формирует `List<Content>` → litertlm стримит `partialResult` и `partialThinkingResult` через `ResultListener` → `ChatViewModel` накапливает в `Message.text` и `Message.thinkingText` → Compose перерисовывает; `LaunchedEffect(messages.size, lastLength)` → `animateScrollToItem` → после `done=true` сообщение финализируется, attachments очищаются.

**Settings flow:** открытие ⚙ в TopAppBar → `InferenceSettingsBottomSheet` читает `repository.observePerModelSettings(model.modelId)` и allowlist-defaults, показывает `EffectiveConfig.merge(defaults, overrides)` → user правит поле → «Применить»:
- **Лёгкое поле** (`temperature, topK, topP, maxTokens`) → `repository.savePerModelSettings(modelId, merged)`; `ChatViewModel` на следующем `send()` через `applyLightOverrides()` переназначает `model.configValues = effectiveConfigMap`. Стрим не прерывается.
- **Полу-лёгкое `systemPromptDefault`** → `repository.savePerModelSettings(...)` + `ChatViewModel.applySystemPromptAndReset()` вызывает `registry.resetConversation(modelName, systemPrompt = newEffectiveSystemPrompt)` — engine остаётся инициализированным, но контекст чата обнуляется. UX: тихая snackbar-уведомление «Системный промпт применён, контекст чата сброшен».
- **Тяжёлое поле** (`accelerator`, initially `enableThinking`) → `HeavyChangeDialog` (warning) → confirm → `ReinitProgressDialog` (неотменяемый) → `ChatViewModel.applyHeavySetting(key, value)`: `stopResponse` (при стриме) → `registry.cleanup` → reassign `model.configValues` → `registry.initialize` → dialog dismiss.

«Default» → `repository.resetPerModelSettings(modelId)` → эффективные values = allowlist defaults. Если среди сброшенных был тяжёлый override — применяется через тот же heavy-flow.

**Reset (↻) TopAppBar:** `ChatViewModel.resetConversation()` — очистка UI-messages списка + `registry.resetConversation(modelName, systemPrompt = effectiveConfig[SYSTEM_PROMPT_DEFAULT])`. Модель остаётся инициализированной; engine-контекст и UI-лента обнуляются вместе (D23).

**Thinking flow:** при `model.llmSupportThinking && effectiveConfig[ENABLE_THINKING]=true` — `partialThinkingResult` копится в `ChatViewModel.thinkingSb`, пишется в `Message.thinkingText`. `MessageBubble` при непустом `thinkingText` рендерит `ThinkingBlock(thinkingText, inProgress = message.streaming)` (collapsible, auto-expand пока inProgress). При `llmSupportThinking=false` или `enableThinking=false` — накопление пропускается.

**Lifecycle audio:** `AudioRecorderBottomSheet` использует `DisposableEffect { onDispose { recorder.release() } }` + `LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { if (recording) { release; dismiss } }` — покрывает входящий звонок, сворачивание, блокировку экрана (AC-19).

**Error flow (D27):** reinit engine crash → `ChatViewModel` ловит в `viewModelScope` exception handler → `ErrorLog.e("inference-init", ...)` + snackbar + engine-state `Failed` → UI в `FailedContent`. CameraX init fail → `ErrorLog.e("camera", ...)` + snackbar + sheet dismiss. AudioRecord init fail (`STATE_INITIALIZED != true`) → `ErrorLog.e("audio", ...)` + snackbar + sheet dismiss. DataStore IO → `ErrorLog.e("settings-io", ...)` + repository возвращает defaults (non-fatal).

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `DataStore<AppSettings>` | `CoreSettingsModule` (Hilt `@Singleton`) | `DefaultAppSettingsRepository` | 1 singleton; file `context.filesDir/datastore/app_settings.pb` |
| `AppSettingsRepository` | `CoreSettingsModule` | `ChatViewModel`, `InferenceSettingsBottomSheet` | 1 singleton |
| litertlm engine (Phase 1 `ModelRegistry`) | `DefaultModelRegistry` | `ChatViewModel` | 0 or 1 (single-active) |
| CameraX `ProcessCameraProvider` | `CameraBottomSheet` | only the sheet | 1 per sheet; bound/unbound в `DisposableEffect` |
| `AudioRecord` | `AudioRecorderBottomSheet` | only the sheet | 1 per sheet; `release()` в `onDispose` + `ON_PAUSE` |

## Decisions

### D1. Модуль `:core-settings` — proto+DataStore внутри него
**Decision:** Всё (схема, generated-классы, serializer, repository, Hilt) — внутри `:core-settings`. `:app` и `:core-runtime` зависят от `:core-settings`, получают только `AppSettingsRepository` через DI.
**Rationale:** `:core-runtime` остаётся UI-free (patterns.md § module boundary); build-isolation; KMP-путь через protobuf-javalite.
**Alternatives considered:**
- Proto+DataStore в `:app` — отверг: нарушает D2 user-spec.
- Proto в `:app`, DataStore-обёртка в `:core-settings` — отверг: странная разнесённость.
- Supports: D2 user-spec.

### D2. Путь proto-файла — `core-settings/src/main/proto/`
**Decision:** `core-settings/src/main/proto/app_settings.proto`. Protobuf gradle-плагин подключён только в `:core-settings/build.gradle.kts`.
**Rationale:** Следствие D1.
**Alternatives considered:** путь `app/src/main/proto/` из текста AC-3 — опечатка. Уточнено в Phase 3 clarification (2026-04-16).
- Supports: AC-3.

### D3. Ключ per-model overrides — `Model.modelId`
**Decision:** `map<string, PerModelSettings>` key = `Model.modelId`.
**Rationale:** Устойчив к переименованию `modelName`.
**Alternatives considered:** `modelName` — теряется при переименовании.
- Supports: D3 user-spec.

### D4. `PerModelSettings` — все поля `optional` (proto3 explicit optional)
**Decision:** Каждое поле помечено `optional`. Отсутствие = «использовать allowlist-default».
**Rationale:** Позволяет хранить только изменённые; семантика «Default» однозначна; корректно мерджится в `EffectiveConfig.merge`.
**Alternatives considered:** sentinel — хрупко, мешает валидации.
- Supports: AC-4, AC-21.

### D5. AudioRecord (не MediaRecorder), raw PCM 16 kHz mono
**Decision:** `AudioRecord(CHANNEL_IN_MONO, ENCODING_PCM_16BIT, 16000)` → `ByteArray` → `helper.runInference(audioClips=listOf(bytes))`.
**Rationale:** litertlm ест PCM, MediaRecorder потребовал бы AAC→PCM конверсию. Gallery так же.
**Alternatives considered:** MediaRecorder — отверг.
- Supports: D7 user-spec.

### D6. Attachments — in-memory (Bitmap + ByteArray в VM StateFlow)
**Decision:** `sealed class Attachment { Image(Bitmap), Audio(ByteArray, Long) }`. Не пишем на диск. CameraX через `OnImageCapturedCallback` → `ImageProxy.toBitmap()`.
**Rationale:** Упрощает lifecycle, нет cleanup, нет FileProvider; rotation survive через VM.
**Alternatives considered:** диск + cleanup — отверг.
- Supports: D1 user-spec.

### D7. Photo Picker — `ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)`
**Decision:** Android Photo Picker. Permission не требуется. При `result.size > 10` (defensive) — first 10 + snackbar.
**Rationale:** Работает на API 31+ нативно, не требует READ_MEDIA_IMAGES.
**Alternatives considered:** `ACTION_PICK` + permission — отверг.
- Supports: D10 user-spec, AC-10.

### D8. compose-richtext (commonmark + ui-material3) для markdown + thinking + about
**Decision:** Зависимости `com.halilibo.compose-richtext:richtext-commonmark:1.0.0-alpha02` + `com.halilibo.compose-richtext:richtext-ui-material3:1.0.0-alpha02`. Используются через wrapper `SafeMarkdown` (с scheme-whitelisted link-handler, D25) в `MessageBubble`, `ThinkingBlock`, `AboutScreen`.
**Rationale:** Gallery использует эту же версию, стабилизирована > года. Maven-группа — `com.halilibo.compose-richtext`, **не `com.halilibo.richtext`**.
**Alternatives considered:** plain `Text` — теряется markdown; свой парсер — YAGNI.
**Risk mitigation:** R3 — при несовместимости с Compose BOM 2026.03.00 fallback на plain `Text`.
- Supports: D9 user-spec, AC-6, AC-7.

### D9. Thinking — поле `Message.thinkingText`, collapsible UI-блок
**Decision:** `Message` получает `thinkingText: String?`. `ChatViewModel.resultListener` накапливает 3-й аргумент в отдельный StringBuilder. `ThinkingBlock` composable (порт `MessageBodyThinking.kt`) — collapsible, drawBehind(outlineVariant) левая линия, приглушённый текст, auto-expand при inProgress.
**Rationale:** Thinking-канал уже прокинут API в Phase 1, ChatViewModel игнорирует 3-й аргумент. Минимально инвазивное расширение.
**Alternatives considered:** отдельное сообщение-пузырь — отверг, нарушает chat-структуру.
- Supports: AC-7, AC-14, AC-18.

### D10. Автоскролл — `LazyColumn` + `LaunchedEffect(size, lastLength)`
**Decision:** `LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length)` → `listState.animateScrollToItem(messages.lastIndex)`.
**Rationale:** Стандартный паттерн, `animateScrollToItem` не прыгает при user-scroll.
- Supports: AC-8.

### D11. Runtime permissions — standard Compose pattern per-permission
**Decision:** `rememberLauncherForActivityResult(RequestPermission())` для CAMERA, RECORD_AUDIO. Check через `ContextCompat.checkSelfPermission`. Denied → snackbar. Denied-perma → snackbar со ссылкой в системные настройки (`Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).data = Uri.fromParts("package", packageName, null)`). Photo Picker permission не требует.
**Rationale:** Стандарт Android. Photo Picker — system trust, без permission.
- Supports: AC-15.

### D12. Heavy setting reinit — модальный `AlertDialog` + `ReinitProgressDialog`
**Decision:** `HeavyChangeDialog` («~5–30 сек, контекст сбросится», buttons «Применить»/«Отмена») → confirm → `ReinitProgressDialog` (неотменяемый, `CircularProgressIndicator` + «Переинициализация модели…») на время `cleanup` + `initialize`.
**Rationale:** Операция короткая, cancel может оставить engine inconsistent. Блокирующий UI предотвращает Send в промежутке.
**Alternatives considered:** inline overlay — отверг.
- Supports: AC-4, AC-21.

### D13. AC-20 — микрофон disabled при вложенном audio (без snackbar)
**Decision:** Если в `attachments` есть `Audio` — `IconButton` `enabled = false`, `contentDescription = "Максимум один аудио-клип на сообщение"`.
**Rationale:** ux-guidelines «whitespace over ornament», visual affordance.
- Supports: AC-20.

### D14. Audio lifecycle — `DisposableEffect.onDispose` + `LifecycleEventEffect(ON_PAUSE)`, без `TelephonyCallback`
**Decision:** `DisposableEffect(recording)` release + `LifecycleEventEffect(ON_PAUSE)` release+dismiss. `READ_PHONE_STATE` не запрашиваем.
**Rationale:** `ON_PAUSE` покрывает звонок/сворачивание/блокировку экрана. TelephonyCallback потребовал бы `READ_PHONE_STATE`.
- Supports: AC-19.

### D15. Классификация полей по способу применения (3 уровня, не 2)
**Decision:**
- **Лёгкие** (применяются на следующем `send`, без вызовов registry): `temperature, topK, topP, maxTokens`. `ChatViewModel.applyLightOverrides()` переназначает `model.configValues` перед следующим `runInference`.
- **Полу-лёгкое `systemPromptDefault`**: применяется через `registry.resetConversation(modelName, systemPrompt = new)` — engine не переинициализируется, но engine-контекст сбрасывается. UX: snackbar «Системный промпт применён, контекст чата сброшен». Чат-лента в UI **очищается** (D23 Reset-behaviour).
- **Тяжёлые** (требуют `cleanup + initialize`): `accelerator, enableThinking`. `HeavyChangeDialog` + `ReinitProgressDialog`.

Manual smoke в Task 10 на Honor 200 — проверить, поддерживает ли litertlm 0.10.0 смену `enableThinking` через `resetConversation` с обновлённым config. Если да — перенести в полу-лёгкое; результат — в `decisions.md`.
**Rationale:** user-spec AC-21 явно говорит про `systemPromptDefault` = «next `resetConversation`»; это не «на следующий send», но и не reinit.
**Alternatives considered:** всё лёгкое / всё heavy — неправильно с точки зрения API litertlm.
- Supports: AC-4, AC-21.

### D16. `EffectiveConfig.merge` — pure function, type-safe
**Decision:** `object EffectiveConfig { fun merge(defaults: Map<String, Any>, overrides: PerModelSettings?): Map<String, Any> }` — создаёт **новую** Map, копирует defaults, накладывает `hasField`-true поля из overrides. Явная type-conversion (proto-javalite `Int` → Kotlin `Int`, `Float` → `Float`, `Boolean`, `String`). Pure: `defaults` не мутирует. Покрывается `EffectiveConfigTest`.
**Rationale:** Единая семантика merge; type-safety избавляет от `ClassCastException` в runtime.
- Supports: AC-4, AC-17, AC-21.

### D17. AboutScreen — `SafeMarkdown` + `assets/about.md`
**Decision:** `AboutScreen` читает `context.assets.open("about.md").bufferedReader().use { it.readText() }` в `LaunchedEffect(Unit)`, рендерит через `SafeMarkdown(text)`. Футер: `BuildConfig.VERSION_NAME` + статичная атрибуция.
**Rationale:** Пользователь правит `.md` напрямую, rebuild — новый текст.
**Constraint:** asset-имя **hardcoded** `"about.md"` (не из state / user-input) — защита от path traversal (security-auditor minor).
- Supports: D5, D11 user-spec, AC-5, AC-6, US-7.

### D18. `app_name` — только `strings.xml`
**Decision:** `<string name="app_name">Sanctum Machina</string>`. `applicationId`, `namespace`, package — не трогаем.
**Rationale:** launcher-label из `@string/app_name`; технические идентификаторы ломают upgrade path.
- Supports: D12 user-spec, AC-2.

### D19. Тема — только `isSystemInDarkTheme()` (без override)
**Decision:** Логика Phase 1 сохраняется. Переключатель в Phase 2 не добавляется. Proto3 AppSettings schema допускает расширение полем `Theme theme` без миграции позже.
**Rationale:** YAGNI; user-spec Constraints явно откладывает.
- Supports: D6 user-spec.

### D20. `:core-runtime` Bitmap-тесты — через Robolectric 4.12
**Decision:** `org.robolectric:robolectric:4.12` как `testImplementation` в `:core-runtime`. `MediaUtilsTest` и `MultimodalContentsBuilderTest` помечаются `@RunWith(RobolectricTestRunner::class)`. JUnit 4 сохраняется (Phase 1 D8 откладывает JUnit 5).
**Rationale:** `Bitmap` в pure JVM не инстанцируется. Альтернатива — seam (extract `calculateInSampleSize` как pure, Robolectric только для decode/rotate) — применена: `calculateInSampleSize` покрывается plain JVM, остальное — Robolectric. Компромисс между чистотой и удобством.
**Alternatives considered:** instrumentation `androidTest` — отверг, solo dev не гоняет regularly.
- Supports: AC-17.

### D21. Lifecycle engine reinit — через `ChatViewModel.viewModelScope` + reassignment `configValues`
**Decision:** Heavy reinit инкапсулирован в `ChatViewModel.applyHeavySetting(key, value)`:
1. `_reinitInProgress.value = true` (триггерит `ReinitProgressDialog`).
2. `if (streaming) helper.stopResponse(model)`.
3. `model.configValues = model.configValues + (key to value)` — **reassignment**, не мутация (type `var Map<String, Any>`).
4. `registry.cleanup(modelName)` — suspend.
5. `registry.initialize(modelName)` — suspend.
6. `_reinitInProgress.value = false`.

При `CancellationException` / любого другого throwable в step 4/5 — `catch` в exception-handler → `ErrorLog.e("inference-init", "...", cause)` (D27) → snackbar → dialog dismiss → engine-state в `Failed` (UI → `FailedContent`).
**Rationale:** `Model.configValues` объявлено в Gallery как `var configValues: Map<String, Any>` (re-assignable). ModelRegistry API already suspend.
- Supports: AC-4, AC-21.

### D22. [TECHNICAL] Рефакторинг сборки `Contents` в отдельный helper
**Decision:** `MultimodalContentsBuilder.build(text, images, audio)` — вынесена pure-функция в `:core-runtime/common/`. `LlmChatModelHelper.runInference` делегирует. Test `MultimodalContentsBuilderTest`.
**Rationale:** Inline-код не тестируется без mock litertlm. Вынос — минимальное условие для AC-17 `MultimodalContentsBuilderTest`. `[TECHNICAL]` — обеспечивает покрытие AC-17.

### D23. Reset (↻) TopAppBar — clear UI messages + `registry.resetConversation`
**Decision:** `ChatViewModel.resetConversation()`:
1. `_messages.value = emptyList()` — очистка UI-ленты.
2. `_attachments.value = emptyList()` — сброс неотправленных attachments.
3. `thinkingSb.clear()`.
4. `registry.resetConversation(modelName, systemPrompt = effectiveConfig[SYSTEM_PROMPT_DEFAULT] as? String)` — engine-контекст обнуляется, модель остаётся инициализированной.

Та же процедура используется при применении `systemPromptDefault` (D15 полу-лёгкое).
**Rationale:** Phase 1 TopAppBar уже имеет Reset action; Phase 2 уточняет семантику и пробрасывает systemPrompt. User-spec AC-21 явно указывает `resetConversation(systemPrompt = ...)` как триггер для systemPromptDefault.
- Supports: AC-21 (triggers), completeness F-04.

### D24. `systemPromptDefault` plumbing — новый `ConfigKey` + `createLlmChatConfigs` + `DefaultModelRegistry.awaitInitialize` wiring
**Decision:** Добавить в `:core-runtime`:
1. `ConfigKeys.SYSTEM_PROMPT_DEFAULT = ConfigKey(id = "system_prompt_default", label = "Default system prompt")` — **2-arg `ConfigKey`** (verified: `data class ConfigKey(val id: String, val label: String)`). В Phase 1 ключа нет — создаётся.
2. `createLlmChatConfigs(..., defaultSystemPrompt: String = "")` — новый параметр в конце сигнатуры; существующий `accelerators: List<Accelerator>` сохраняется. Эмитит `LabelConfig(key = ConfigKeys.SYSTEM_PROMPT_DEFAULT, defaultValue = defaultSystemPrompt)` (попадает в `Model.configValues` при `preProcess`).
3. `AllowedModel.toModel()` — парсит `defaultConfig.systemPromptDefault` (optional string, default `""`) из allowlist JSON, передаёт в `createLlmChatConfigs`.
4. **`DefaultModelRegistry.awaitInitialize(model)`** — читает `model.configValues[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label] as? String`, оборачивает непустое значение в `Contents.of(listOf(Content.Text(it)))`, передаёт как `systemInstruction` в `llmModelHelper.initialize(...)`. **Важно:** Phase-1 `LlmChatModelHelper.initialize` уже принимает и форвардит `systemInstruction: Contents?` (grep: строки 65, 136 `LlmChatModelHelper.kt`); единственный реальный caller-null — `awaitInitialize` (строки 248–261 `DefaultModelRegistry.kt`).
5. `DefaultModelRegistry.resetConversation(modelName, systemPrompt)` — уже работает корректно (Phase 1, строки 202–211); `ChatViewModel.resetConversation` передаёт effective systemPrompt.

**Rationale:** Без этого `systemPromptDefault` существует только в DataStore, до engine не доезжает (completeness F-03). user-spec AC-4 требует, чтобы настройка применялась.
**Alternatives considered:** пропустить systemPromptDefault в Phase 2 — отверг, user-spec AC-4 явно его перечисляет.
- Supports: AC-4, AC-21, completeness F-03.

### D25. Markdown link-handler — scheme-whitelist (http/https only)
**Decision:** `SafeMarkdown` (wrapper над `RichText { Markdown(text) }`) перехватывает link-clicks через `LocalUriHandler` с проверкой scheme:
```kotlin
CompositionLocalProvider(LocalUriHandler provides SafeUriHandler(context)) {
  RichText { Markdown(text) }
}

class SafeUriHandler(private val context: Context) : UriHandler {
  override fun openUri(uri: String) {
    val parsed = Uri.parse(uri)
    if (parsed.scheme in setOf("http", "https")) {
      context.startActivity(Intent(Intent.ACTION_VIEW, parsed))
    } else {
      // silently ignore — blocked scheme is expected UX, not an error (no ErrorLog entry)
    }
  }
}
```
**Rationale:** LLM-output рендерится через markdown — `[text](intent://...)`, `[text](sms:...)`, `[text](tel:...)` могут запустить нежелательные intent'ы. Whitelist только http/https — достаточно для ссылок, блокирует остальное.
**Alternatives considered:** отключить link-handling целиком — отверг, теряем полезные ссылки в ответах.
- Supports: Security A04 (Insecure Design), markdown-output safety.

### D26. Privacy hardening — `allowBackup=false`, `dataExtractionRules`
**Decision:**
1. `<application android:allowBackup="false" ... />` — Google auto-backup отключён (DataStore-настройки и Phase-3 Room не передаются через cloud).
2. `<application android:dataExtractionRules="@xml/data_extraction_rules" ... />` (Android 12+) — `res/xml/data_extraction_rules.xml` с явным exclude-all: `<data-extraction-rules><cloud-backup><exclude domain="root" path="."/></cloud-backup><device-transfer><exclude domain="root" path="."/></device-transfer></data-extraction-rules>` — блокирует Google cloud-backup и device-to-device transfer полностью (пустой root по умолчанию на API 31+ допускает всё).

**`FLAG_SECURE` не добавляем** — пользователю нужна возможность делать скриншоты. Ответственность за утечку чат-контента через overview/screenshots лежит на пользователе.

**Rationale:** Sanctum Machina — local-LLM privacy app (project.md § Out of Scope: «no cloud sync»). По умолчанию backup включён — DataStore-настройки и (в Phase 3) Room-история тихо уезжают в Google Drive. `allowBackup=false` + `dataExtractionRules` закрывают этот канал. Стоимость — 1 атрибут + 1 XML-файл. Должно быть в Phase 2 — в Phase 3 (Room) упущение станет дорогостоящим.
- Supports: Security (A04 Insecure Design, M2 Inadequate Privacy Controls), project.md «no cloud sync».

### D27. Phase 2 error handling → `ErrorLog.e` integration
**Decision:** Новые failure paths → `ErrorLog.e(component, description, cause?)` (patterns.md § ErrorLog whitelist). Необходимо расширить whitelist в `patterns.md` + в `ErrorLog.kt`:
- `"inference-init"` (уже есть) — D21 reinit crash.
- `"settings-io"` (новый) — DataStore IOException / CorruptionException.
- `"camera"` (новый) — CameraX provider/useCase bind fail.
- `"audio"` (новый) — `AudioRecord.state != STATE_INITIALIZED`.
- `"attachment-decode"` (новый) — `decodeSampledBitmapFromUri` returns null / throws (битое фото из picker).

Каждый новый component добавляется в constant-set в `ErrorLog.kt` (closed whitelist).
**Cause-chain bounding:** `ErrorLog.e(component, desc, cause)` пишет `cause.javaClass.simpleName + ": " + (cause.message?.take(200) ?: "")` — избегаем дампа полного stacktrace с SELinux context / hardware identifiers. Convention документируется в `patterns.md § ErrorLog` одновременно с whitelist расширением.
**Rationale:** patterns.md требует, чтобы component был в whitelist. Phase 2 вводит новые failure modes; без явного добавления — дырки в логе.
- Supports: R13, completeness F-05, security round-2 minor.

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

### `:core-runtime` Config extension

`data/Config.kt` — `ConfigKey` — 2-arg data class (`data class ConfigKey(val id: String, val label: String)`):
```kotlin
object ConfigKeys {
  // existing: MAX_TOKENS, TOPK, TOPP, TEMPERATURE, ENABLE_THINKING, ACCELERATOR, SUPPORT_THINKING, ... (verified in Phase 1)
  val SYSTEM_PROMPT_DEFAULT = ConfigKey(id = "system_prompt_default", label = "Default system prompt")   // NEW
}

fun createLlmChatConfigs(
  defaultMaxToken: Int = DEFAULT_MAX_TOKEN,
  defaultMaxContextLength: Int? = null,
  defaultTopK: Int = DEFAULT_TOPK,
  defaultTopP: Float = DEFAULT_TOPP,
  defaultTemperature: Float = DEFAULT_TEMPERATURE,
  accelerators: List<Accelerator> = DEFAULT_ACCELERATORS,
  supportThinking: Boolean = false,
  defaultSystemPrompt: String = "",   // NEW — emits LabelConfig(SYSTEM_PROMPT_DEFAULT, defaultValue = defaultSystemPrompt)
): List<Config> = buildList {
  // existing entries preserved unchanged
  add(LabelConfig(key = ConfigKeys.SYSTEM_PROMPT_DEFAULT, defaultValue = defaultSystemPrompt))   // NEW
}
```

### `:core-runtime` engine systemPrompt wiring — `DefaultModelRegistry.awaitInitialize`

**Location of fix:** `LlmChatModelHelper.initialize` уже принимает и форвардит `systemInstruction: Contents?` в `engine` — Phase-1 API корректен. Реальный caller-null — `DefaultModelRegistry.awaitInitialize`, которая сегодня не передаёт systemInstruction. Правка — там:

```kotlin
// DefaultModelRegistry.awaitInitialize — NEW systemInstruction plumbing
private suspend fun awaitInitialize(model: Model): String =
  suspendCancellableCoroutine { cont ->
    val systemPrompt = (model.configValues[ConfigKeys.SYSTEM_PROMPT_DEFAULT.label] as? String)
      ?.takeIf { it.isNotBlank() }
    val systemInstruction: Contents? = systemPrompt?.let { Contents.of(listOf(Content.Text(it))) }
    llmModelHelper.initialize(
      context = context,
      model = model,
      supportImage = model.llmSupportImage,
      supportAudio = model.llmSupportAudio,
      systemInstruction = systemInstruction,   // NEW — was effectively null (default)
      onDone = { err -> if (cont.isActive) cont.resume(err) },
    )
    // existing invokeOnCancellation preserved
  }
```

На `resetConversation` — уже работает корректно (`DefaultModelRegistry.resetConversation(modelName, systemPrompt)` уже строит `Contents` и передаёт в `llmModelHelper.resetConversation(entry.model, systemInstruction = contents)` — Phase 1).

### `:app` — Attachment sealed hierarchy

```kotlin
sealed class Attachment {
  data class Image(val bitmap: Bitmap) : Attachment()
  data class Audio(val pcm: ByteArray, val durationMs: Long) : Attachment()
}
```

### `:app` — Message extension

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

- `com.google.protobuf` (gradle plugin, 0.9.5) — подключается в `core-settings/build.gradle.kts`.
- `com.google.protobuf:protobuf-javalite` (4.26.1) — runtime.
- `androidx.datastore:datastore` (1.1.7) — Proto DataStore.
- `androidx.camera:camera-core` / `camera-camera2` / `camera-lifecycle` / `camera-view` (1.4.2) — CameraX.
- `com.halilibo.compose-richtext:richtext-commonmark` (1.0.0-alpha02) + `com.halilibo.compose-richtext:richtext-ui-material3` (1.0.0-alpha02) — markdown. **Maven-группа — `com.halilibo.compose-richtext`**, не `com.halilibo.richtext`.
- `androidx.lifecycle:lifecycle-runtime-compose` — для `LifecycleEventEffect`.
- `org.robolectric:robolectric` (4.12) — `testImplementation` в `:core-runtime`.

### Using existing (from project)

- `:core-runtime` — `ModelRegistry`, `LlmChatModelHelper`, Contents API, `ResultListener` с thinking (Phase 1).
- `Config.kt`, `ConfigKey`, `ConfigKeys.*`, `createLlmChatConfigs` — defaults / ключи.
- `ErrorLog` (Phase 1) — расширить component whitelist (D27).
- Hilt 2.57.1 + KSP, Material 3 BOM 2026.03.00, icons-extended, navigation-compose, activity-compose — Phase 1.

## Testing Strategy

**Feature size:** L. Unit-only pyramid — обосновано: litertlm native не воспроизводим в JVM, E2E-Compose-тесты для solo dev на фазе частых UI-изменений неоправданы. Замещение E2E — manual smoke на Honor 200 с чек-листом US-1..US-7 (AC-22 финальный гейт).

### Unit tests

**:core-settings:**
- `AppSettingsRepositoryTest` (`@get:Rule TemporaryFolder` + `runTest(UnconfinedTestDispatcher())` + per-test fresh `DataStore<AppSettings>` через `DataStoreFactory.create(scope, produceFile = { File(tempFolder.root, "test-${uuid}.pb") })`):
  - `save → observe` round-trip (single modelId).
  - `save modelId-A, save modelId-B` → observe каждого независимо.
  - `reset(modelId)` → observe null, map не содержит key.
  - merge optional: save только `temperature` → read: `hasTemperature()=true`, `hasTopK()=false`.
  - concurrent: внутри `runTest` — две `savePerModelSettings` подряд → observe возвращает последнюю (sequential-consistency).
  - IOException injection: mock corrupt file → repository возвращает default и логирует (D27 — `ErrorLog.e("settings-io", ...)`) — но test просто проверяет, что `observe` не падает.

**:core-runtime:**
- `AllowlistLoaderTest` (расширение):
  - `llmSupportImage/Audio/Thinking = true/false` (комбинации) → корректно парсятся в `Model`.
  - Отсутствие поля в JSON → false.
  - `fixtureMatchesProductionAsset` зелёный после обновления fixture + prod.
  - Новое поле `defaultConfig.systemPromptDefault` (optional string, default "") парсится в `Model.configValues[SYSTEM_PROMPT_DEFAULT]`.
- `MediaUtilsTest` (Robolectric):
  - `decodeSampledBitmapFromUri(uri, 1024, 1024)` на тестовой PNG 2048×2048 → bitmap ≤1024 по каждой оси.
  - `rotateBitmap` с ExifInterface orientations 0/90/180/270 → pixel comparison на контрольном сэмпле.
  - `calculateInSampleSize` boundary: exact fit, 2x, 4x, 8x (JVM-pure, не Robolectric — извлечено как pure function, D20).
  - `calculatePeakAmplitude` — empty buffer → 0; buffer со значениями [-1000, 500, 2000, -300] → 2000; boundary `Short.MAX_VALUE`.
- `AudioClipTest`:
  - Построение `AudioClip(byteArrayOf(), 16000)` — пустой `audioData`, sampleRate сохраняется.
  - Нечётный `audioData.size` — не падает.
- `MultimodalContentsBuilderTest`:
  - `(text, listOf(bitmap), listOf(bytes))` → `listOf(Content.ImageBytes, Content.AudioBytes, Content.Text)` в этом порядке.
  - Пустой `text` → `Content.Text` не добавляется.
  - `(text, emptyList, emptyList)` → один `Content.Text`.

**:app:**
- `SafeUriHandlerTest` (`ui/chat/SafeUriHandler`, TAC-13):
  - **Allowed:** `http://example.com`, `https://example.com/path?q=1` — `Intent.ACTION_VIEW` вызывается.
  - **Blocked (silent):** `intent://anything`, `sms:+1234`, `tel:911`, `javascript:alert(1)`, `file:///etc/passwd`, `content://foo/bar`, `data:text/html,<script>`, `market://details?id=x`, `""`, malformed `"not a uri at all"`.
  - Test-fake Context: counts `startActivity` calls; blocked schemes → 0 calls; allowed → 1 call.
- `ErrorLogTest` (новый, `:core-runtime/src/test/`):
  - `e("inference-init", ...)`, `e("settings-io", ...)`, `e("camera", ...)`, `e("audio", ...)`, `e("attachment-decode", ...)`, `e("download", ...)`, `e("inference", ...)`, `e("inference-cleanup", ...)` — принимаются без exception.
  - `e("unknown-component", ...)` — `IllegalArgumentException` (whitelist enforcement).
  - Cause-chain bounding: `e("inference", "failed", RuntimeException("x".repeat(500)))` → log line содержит `"RuntimeException: " + "x".repeat(200)`, не больше.
- `EffectiveConfigTest`:
  - `overrides=null` → defaults as-is.
  - Частичный `overrides` (только temperature) → defaults ∪ temperature override (остальные остаются defaults).
  - `overrides.enableThinking=false` при `defaults[ENABLE_THINKING]=true` → effective=false (explicit bool override).
  - **Type safety:** `overrides.temperature` (proto Float) → Kotlin `Float` в returned Map; `overrides.max_tokens` (proto Int) → Kotlin `Int` (не Long).
  - **Pure:** defaults-map не мутирует после `merge` (structural equality до/после).
  - **Empty ≡ null:** `merge(d, PerModelSettings.getDefaultInstance())` == `merge(d, null)` == defaults.
- `ChatViewModelTest` (plain JUnit + `runTest`, MockK не требуется — используется fake `ModelRegistry`, fake `AppSettingsRepository`, fake `LlmModelHelper` — простые interface-реализации):
  - `addImage(uri)` → attachments содержит Image с downscaled bitmap.
  - `addImage` × 11 → первые 10 добавлены, snackbar-flag выставлен, 11-ое отброшено.
  - `removeAttachment(idx)` → attachment исчезает.
  - `send(text)` при пустом text + пустых attachments → `helper.runInference` не вызывается.
  - `send(text)` при `enableThinking=true`, `llmSupportThinking=true` → при emit `partialThinkingResult` в listener → `Message.thinkingText` накапливается.
  - `send(text)` при `enableThinking=false` → thinkingText не накапливается даже при emit.
  - `send(text)` при `llmSupportThinking=false` → thinkingText не накапливается.
  - `applyLightOverrides()` → `model.configValues` обновлён, `registry.cleanup` НЕ вызывается.
  - `applyHeavySetting(ACCELERATOR, "CPU")` → последовательность: `stopResponse`, `configValues`-reassign, `cleanup`, `initialize`, `_reinitInProgress` true→false.
  - `applyHeavySetting` при crash в initialize → exception caught, engine в Failed state, `_reinitInProgress`=false, ErrorLog.e вызван.
  - `applySystemPromptAndReset(newPrompt)` → `resetConversation(systemPrompt = newPrompt)`, messages cleared, snackbar emitted.
  - `resetConversation()` (Reset button) → messages cleared, attachments cleared, `registry.resetConversation(modelName, systemPrompt = effective)`.

### Integration tests

**None.** Phase 2 не вводит инфраструктуры, требующей integration-тестов. litertlm native в JVM не воспроизводим (exclusion user-spec + architecture.md).

### E2E tests

**None.** user-spec exclusion. Substitute — manual smoke на Honor 200 по чек-листу US-1..US-7 (AC-22). Manual checklist:
- US-1: первый запуск, TopAppBar "О приложении", bottom sheet настроек с 7 полями.
- US-2: 3 фото + текст → ответ.
- US-3: 5-сек аудио → ответ.
- US-4: температура 1.0 → 0.2, следующий ответ менее разнообразный.
- US-5: switch model → engine cleanup + init.
- US-6: back → сессия уничтожена, новый ChatScreen пустой.
- US-7: AboutScreen markdown рендерится, версия в футере.
- Edge cases (user-spec): отказ permission, 15 фото, 30+ сек аудио, back во время стрима, звонок во время записи, микрофон при вложенном audio.

## Agent Verification Plan

**Source:** user-spec «Как проверить».

### Verification approach

Агент запускает:
- `./gradlew build` — `BUILD SUCCESSFUL`.
- `./gradlew :core-runtime:test` — все прежние + новые зелёные.
- `./gradlew :core-settings:test` — `AppSettingsRepositoryTest` зелёный.
- `./gradlew :app:test` — `EffectiveConfigTest` + `ChatViewModelTest` зелёные.
- `./gradlew lintDebug` — нет Error-level issues; `:core-settings` без `MissingPermission`/`MissingClass`.
- `./gradlew :app:assembleDebug` — APK собран.
- `aapt dump permissions app-debug.apk` содержит CAMERA, RECORD_AUDIO.
- `aapt dump xmltree app-debug.apk AndroidManifest.xml | grep allowBackup` — `android:allowBackup=false`.

Per-task smoke — в `Verify-smoke` каждого Task. **Post-deploy verification — N/A.** Мобильное приложение на физическом устройстве; агент не может установить APK и взаимодействовать с UI. US-1..US-7, AC-13, AC-14, AC-16 — user verification на Honor 200 (AC-22 финальный гейт).

### Tools required

`./gradlew` CLI, bash, `aapt` (Android SDK build-tools). **MCP-инструменты (Playwright, Telegram) не применимы** — нативное Android-приложение.

## Risks

| Risk | Mitigation |
|------|-----------|
| R1. AC-1 fix: fixture vs prod desync → `fixtureMatchesProductionAsset` падает | Обновить fixture+prod синхронно в Task 1; добавить explicit тест JSON→Model mapping |
| R2. Heavy reinit (`accelerator`/`enableThinking`) crash engine в litertlm 0.10.0 | `HeavyChangeDialog` + `ReinitProgressDialog`; exception handler → engine `Failed`; manual smoke финализирует D15 classification |
| R3. compose-richtext alpha02 несовместим с Compose BOM 2026.03.00 | Verify-smoke в Task 5 (сборка + compose preview); fallback на plain `Text` |
| R4. Thinking-канал null для Gemma-4 в litertlm 0.10.0 | Manual smoke (AC-14); при проблеме AC-14 → manual-verify-only, код готов |
| R5. OOM при 10 фото на 8GB RAM | Downscale 1024×1024 + `MAX_IMAGE_COUNT=10`; Honor 200 (12GB) — основной target |
| R6. Proto plugin 0.9.5 + AGP 8.8.2 incompatibility | Verify на first `:core-settings:build` (Task 2); fallback 0.9.4 или 0.9.6+ |
| R7. `:core-settings` library-manifest — lint false-positive | Воспроизвести pattern `:core-runtime/AndroidManifest.xml`; manifest не должен override `<application>`-атрибуты (иначе сбрасывает `:app`'s `allowBackup`) |
| R8. CameraX lifecycle на rotation | `LocalLifecycleOwner.current` + bind/unbind в `DisposableEffect`; manual rotation-тест на Honor 200 |
| R9. AudioRecord отклоняется на MIUI/HarmonyOS | Honor 200 — main target; `AudioRecord.state != STATE_INITIALIZED` → snackbar + `ErrorLog.e("audio", ...)`; не блокер |
| R10. Proto3 `optional` в javalite 4.26.1 (нужна ≥ 3.15) | Поддерживается since 3.15; verify — generated `hasMaxTokens()/getMaxTokens()` появляются после first build Task 2 |
| R11. Attachments теряются при process death (VM re-creation) | user-spec D1 допускает эфемерность; rotation survives через VM (UI-state); process-death — допустимая потеря, документируем |
| R12. HeavyChangeDialog race с in-flight callback | VM: `stopResponse` → `cleanup` → reinit; `ResultListener` после `cleanup` — stale-instance guard Phase 1 D3 в `DefaultModelRegistry` блокирует late callbacks |
| R13. DataStore IOException / CorruptionException — потеря overrides, crash читателя | `DefaultAppSettingsRepository` ловит `IOException`/`CorruptionException` в `observePerModelSettings.catch { emit(null) }` и в `save*`/`reset*` — swallow+log `ErrorLog.e("settings-io", cause)`; UI fallback на allowlist-defaults; пользователь теряет overrides только для того modelId (не весь файл) |
| R14. Markdown link injection из LLM-output | `SafeMarkdown` wrapper (D25) — `SafeUriHandler` с scheme-whitelist (http/https); non-whitelisted schemes silently ignored + optional log |
| R15. (removed — FLAG_SECURE не добавляется, user decision: скриншоты важнее) | — |

## User-Spec Deviations

### 1. Путь proto-файла — `:core-settings`, не `:app`
- **AC-3:** user-spec текст: «`app/src/main/proto/app_settings.proto`». Tech-spec: `core-settings/src/main/proto/app_settings.proto`. Причина: чистая изоляция (D1+D2). **Разрешено в ходе Phase 3 clarification 2026-04-16** (зафиксировано в user-чате: «всё в core-settings, наружу только AppSettingsRepository»). → **Не требует approval.**

### 2. Новая test-зависимость Robolectric 4.12
- **AC-17:** user-spec перечисляет `MediaUtilsTest` без указания тест-фреймворка. Tech-spec добавляет `org.robolectric:robolectric:4.12` в `:core-runtime/testImplementation` — Bitmap в pure JVM не инстанцируется. `calculateInSampleSize` выделён как pure (без Robolectric) — компромисс D20. → **Approved** (user: «лишним не будет, раз они дешёвые»).

### 3. `MultimodalContentsBuilder` рефакторинг `LlmChatModelHelper`
- **Не в user-spec:** Вынос inline-сборки `List<Content>` в pure helper. Без этого `MultimodalContentsBuilderTest` (AC-17) тестирует litertlm-зависимый код. Минимальный рефакторинг, без изменения поведения. → **Approved.**

### 4. `systemPromptDefault` plumbing — новый `ConfigKey` + `createLlmChatConfigs` + engine systemInstruction wiring
- **AC-4, AC-21:** user-spec требует применения `systemPromptDefault`. Phase-1 API: `LlmChatModelHelper.initialize` передаёт `systemInstruction = null` — значение из Model.configValues не доезжает. Tech-spec D24 вводит `ConfigKey.SYSTEM_PROMPT_DEFAULT`, расширяет `createLlmChatConfigs` параметром `defaultSystemPrompt`, парсит `defaultConfig.systemPromptDefault` из allowlist JSON, пробрасывает в `engine.createConversation(systemInstruction=...)`. → **Approved** (технически необходимо для AC-4).

### 5. Не портируем `convertWavToMonoWithMaxSeconds`
- **D11 user-spec:** перечисляет функцию в списке «media helpers для `:core-runtime/common/`». Tech-spec не портирует. Причина: функция нужна только для сценария **импорта WAV-файла из файловой системы**; Phase 2 использует AudioRecord → raw PCM напрямую (D5, D7 user-spec). WAV-import flow в Phase 2 нет. Если появится в будущем (Phase 3+) — портируется отдельной задачей. → **Approved** (архитектурно оправдано, подтверждено в clarification).

### 6. `calculatePeakAmplitude` добавлен в Task 3 (порт)
- **D11 user-spec:** перечисляет функцию. Tech-spec v1 silently dropped, v2 добавляет в Task 3 для закрытия AC-24 (желательный — индикатор уровня звука при записи). Не deviation от user-spec, восстановление полного порта по D11. → **Не требует approval** (возвращение к user-spec).

### 7. Reset (↻) TopAppBar семантика
- **AC-21:** user-spec упоминает Reset в контексте триггера для systemPrompt-reset и tip'a в описании. Tech-spec D23 фиксирует: Reset = clear UI messages + clear attachments + `registry.resetConversation(modelName, systemPrompt = effectiveSystemPromptDefault)`. Подтверждено пользователем в Phase 3 clarification. → **Не требует approval** (уточнение Phase-1 behavior + явная пробросил systemPrompt).

### 8. `systemPromptDefault` классификация как «полу-лёгкое» поле
- **AC-21:** user-spec перечисляет «лёгкие» (temperature, topK, topP, maxTokens, systemPromptDefault) и «тяжёлые» (accelerator, enableThinking). Tech-spec D15 вводит **3 уровня** — выделяет `systemPromptDefault` как «полу-лёгкое» (применяется через `resetConversation`, сбрасывает engine-context, очищает UI-ленту). user-spec AC-21 явно говорит «применяется при следующей переинициализации engine через `resetConversation(systemPrompt = ...)`» — tech-spec этот контракт соблюдает, но отделяет от остальных «лёгких» явной UX-семантикой. → **Не требует approval** (следование user-spec AC-21 text, с более точной классификацией).

### 9. Privacy hardening — `allowBackup=false`, `dataExtractionRules` (без FLAG_SECURE)
- **Не в user-spec:** D26 добавляет два privacy-умолчания (backup + transfer exclusion). `FLAG_SECURE` не добавляется — user decision: скриншоты нужны, ответственность за утечку через overview/screenshots лежит на пользователе. `allowBackup=false` + `dataExtractionRules` блокируют Google-backup (DataStore, Phase-3 Room). project.md манифест «no cloud sync». → **Approved** (FLAG_SECURE dropped per user decision; allowBackup approved).

### 10. Markdown link-scheme whitelist (`SafeUriHandler`)
- **Не в user-spec:** D25 добавляет scheme-whitelist для link-handler markdown. user-spec явно не требует, но LLM-output рендерится markdown, и `[text](intent://...)` — валидный attack vector. → **Approved.**

### 11. `ErrorLog` component whitelist расширение
- **Не в user-spec:** D27 добавляет `"settings-io"`, `"camera"`, `"audio"`, `"attachment-decode"` в closed whitelist `patterns.md § ErrorLog`. user-spec явно не требует, но patterns.md Phase 1 рассматривает whitelist как closed — без расширения логировать Phase-2-failure-paths негде. → **Approved** (требует правки `patterns.md`).

## Acceptance Criteria

Технические критерии приёмки (дополняют AC-1..AC-25 user-spec):

- [ ] **TAC-1.** `./gradlew build` — `BUILD SUCCESSFUL`.
- [ ] **TAC-2.** `./gradlew :core-runtime:test` — все тесты зелёные (прежние + новые AC-17).
- [x] **TAC-3.** `./gradlew :core-settings:test` — `AppSettingsRepositoryTest` зелёный.
- [ ] **TAC-4.** `./gradlew :app:test` — `EffectiveConfigTest` + `ChatViewModelTest` зелёные.
- [ ] **TAC-5.** `./gradlew lintDebug` — нет Error-level issues.
- [ ] **TAC-6.** `fixtureMatchesProductionAsset` зелёный после Task 1.
- [ ] **TAC-7.** `rg -l "androidx.compose|androidx.activity" core-runtime/src/main` → пусто.
- [x] **TAC-8.** `rg -l "androidx.compose|androidx.activity" core-settings/src/main` → пусто.
- [ ] **TAC-9.** APK собран, `aapt dump permissions` содержит CAMERA + RECORD_AUDIO.
- [ ] **TAC-10.** `aapt dump xmltree app-debug.apk AndroidManifest.xml | grep allowBackup` → `allowBackup=false`.
- [ ] **TAC-11.** `rg -n "Text\(\"[А-Яа-я]" app/src/main` → пусто (все строки через `stringResource`).
- [x] **TAC-12.** Generated `AppSettings`, `PerModelSettings` в `:core-settings/build/generated/...`, `hasMaxTokens()`/`getMaxTokens()` методы присутствуют.
- [ ] **TAC-13.** `SafeUriHandlerTest` покрывает allowed (http/https) + 9 blocked cases (intent, sms, tel, javascript, file, content, data, market, malformed).
- [ ] **TAC-14.** `aapt dump xmltree app-debug.apk res/xml/data_extraction_rules.xml` показывает `<cloud-backup>` и `<device-transfer>` с `<exclude domain="root" path="."/>` (backup/transfer блокированы полностью).
- [ ] **TAC-15.** `ErrorLogTest` покрывает whitelist-enforcement (unknown component → IllegalArgumentException) + cause-chain bounding (message обрезается на 200 chars).

## Implementation Tasks

> Зависимости между волнами строгие: Wave N+1 начинается после Wave N. Внутри волны — `parallel:` или `sequential:` отмечено явно. При `sequential:` задачи идут по порядку Task-N.

### Wave 1 — инфраструктура без UI (parallel)

Задачи 1 и 4 правят разные файлы в `:core-runtime` — параллельны.

#### Task 1: AC-1 — починить парсинг allowlist `llmSupport*` + `defaultConfig.systemPromptDefault`
- **Description:** `AllowedModel.toModel()` — проброс `llmSupportImage/Audio/Thinking` из JSON в `Model`. Добавить парсинг `defaultConfig.systemPromptDefault` (optional string, default `""`) в `createLlmChatConfigs(..., defaultSystemPrompt = ...)`. Расширить `AllowlistLoaderTest`, синхронизировать fixture + prod.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :core-runtime:test --tests AllowlistLoaderTest` — зелёные, включая новые кейсы.
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/ModelAllowlist.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/AllowlistLoaderTest.kt`, `core-runtime/src/test/resources/model_allowlist_fixture.json`, `core-runtime/src/main/assets/model_allowlist.json` (fixture/prod sync)
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Model.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`

#### Task 4: `MultimodalContentsBuilder` + рефакторинг `LlmChatModelHelper.runInference`
- **Description:** Вынести inline-сборку `List<Content>` из `LlmChatModelHelper.runInference` (функция обрабатывает images/audio перед `sendMessageAsync`) в pure `MultimodalContentsBuilder.build`. Unit-тест. [TECHNICAL] по D22.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `./gradlew :core-runtime:test --tests MultimodalContentsBuilderTest` — зелёный; прежние тесты не сломались.
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/inference/LlmChatModelHelper.kt`
- **Files to create:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MultimodalContentsBuilder.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/common/MultimodalContentsBuilderTest.kt`
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/inference/LlmChatModelHelper.kt` (функция `runInference`, inline-сборка перед `sendMessageAsync`)

### Wave 2 — модуль `:core-settings` + media utils (sequential, общий `libs.versions.toml`)

Задачи 2 и 3 обе правят `gradle/libs.versions.toml`; выполнение — по порядку.

#### Task 2: Создать `:core-settings` + version catalog entries
- **Description:** Новый модуль `:core-settings`: `build.gradle.kts` (protobuf 0.9.5 plugin, datastore 1.1.7, hilt), `AndroidManifest.xml` (library hygiene — без `<application>`-атрибутов), `app_settings.proto`, `AppSettingsSerializer`, `AppSettingsRepository` interface + `DefaultAppSettingsRepository` (с обработкой `IOException`/`CorruptionException` → `ErrorLog.e("settings-io", ...)`, R13), Hilt `CoreSettingsModule`. В `libs.versions.toml` — protobuf, datastore, protobuf-javalite. Зарегистрировать в `settings.gradle.kts`, зависимость из `:app`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :core-settings:build` — SUCCESS; `ls core-settings/build/generated/source/proto/main/java/app/sanctum/machina/core/settings/proto/` содержит `AppSettings.java`, `PerModelSettings.java` с `hasMaxTokens()`/`getMaxTokens()` методами; `./gradlew :core-settings:test --tests AppSettingsRepositoryTest` — зелёный.
- **Files to modify:** `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts` (root, `alias(libs.plugins.protobuf) apply false`), `app/build.gradle.kts`
- **Files to create:** `core-settings/build.gradle.kts`, `core-settings/src/main/AndroidManifest.xml`, `core-settings/src/main/proto/app_settings.proto`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/AppSettingsSerializer.kt`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/AppSettingsRepository.kt`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/DefaultAppSettingsRepository.kt`, `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/di/CoreSettingsModule.kt`, `core-settings/src/test/kotlin/app/sanctum/machina/core/settings/AppSettingsRepositoryTest.kt`
- **Files to read:** `core-runtime/build.gradle.kts`, `core-runtime/src/main/AndroidManifest.xml`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/di/CoreRuntimeModule.kt`, `gallery-source/Android/src/app/src/main/proto/settings.proto`, `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/data/SettingsSerializer.kt`

#### Task 3: Порт media utils (`MediaUtils`, `AudioClip`, `calculatePeakAmplitude`) в `:core-runtime/common/`
- **Description:** Скопировать в `MediaUtils.kt` функции `decodeSampledBitmapFromUri`, `rotateBitmap`, `calculateInSampleSize` (extract как pure — для JVM-тестов без Robolectric), `calculatePeakAmplitude` из gallery `common/Utils.kt`. `AudioClip.kt` — `class AudioClip(audioData: ByteArray, sampleRate: Int)` (plain, не `data class`). Расширить `ErrorLog.kt` component whitelist: `"attachment-decode"`, `"audio"`, `"camera"`, `"settings-io"` (D27). В `libs.versions.toml` — robolectric 4.12. Unit-тесты.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Verify-smoke:** `./gradlew :core-runtime:test --tests MediaUtilsTest --tests AudioClipTest` — зелёные.
- **Files to modify:** `gradle/libs.versions.toml`, `core-runtime/build.gradle.kts`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`
- **Files to create:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MediaUtils.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/AudioClip.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/common/MediaUtilsTest.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/common/AudioClipTest.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/log/ErrorLogTest.kt`, `core-runtime/src/test/resources/test-image.jpg`
- **Files to read:** `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/common/Utils.kt` (функции `decodeSampledBitmapFromUri`, `rotateBitmap`, `calculateInSampleSize`, `calculatePeakAmplitude`), `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/common/Types.kt` (`AudioClip`)

### Wave 3 — ресурсы и privacy hardening (single task)

#### Task 5: strings.xml, AndroidManifest permissions + privacy hardening, dependencies, theme
- **Description:** `strings.xml` → `app_name = "Sanctum Machina"` + новые Phase-2 строки (7 settings-полей, кнопки «Применить»/«Default», permission errors, thinking label, attachment labels, about, heavy-change dialog, reinit progress, snackbar «Системный промпт применён»). `AndroidManifest.xml` в `:app` — CAMERA, RECORD_AUDIO, `<uses-feature camera required="false">`, `android:allowBackup="false"`, `android:dataExtractionRules="@xml/data_extraction_rules"`. `res/xml/data_extraction_rules.xml` с explicit exclude-all. `libs.versions.toml` + `app/build.gradle.kts` — CameraX 1.4.2 (camera-core/camera2/lifecycle/view), `com.halilibo.compose-richtext:richtext-commonmark:1.0.0-alpha02`, `com.halilibo.compose-richtext:richtext-ui-material3:1.0.0-alpha02`, `androidx.lifecycle:lifecycle-runtime-compose`. D26, D25, D11.
- **Skill:** infrastructure-setup
- **Reviewers:** code-reviewer, security-auditor, infrastructure-reviewer
- **Verify-smoke:** `./gradlew :app:assembleDebug` → SUCCESS; `aapt dump permissions app-debug.apk` показывает CAMERA + RECORD_AUDIO; `aapt dump xmltree app-debug.apk AndroidManifest.xml | grep allowBackup` — `false`.
- **Verify-user:** APK на Honor 200 → launcher-label «Sanctum Machina» (AC-2).
- **Files to modify:** `app/src/main/res/values/strings.xml`, `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`
- **Files to create:** `app/src/main/res/xml/data_extraction_rules.xml`
- **Files to read:** `.claude/skills/project-knowledge/references/ux-guidelines.md`, `.claude/skills/project-knowledge/references/patterns.md` (library-manifest hygiene)

### Wave 4 — UI (sequential из-за общих файлов `ChatScreen.kt` / `ChatViewModel.kt` / `strings.xml`)

Исключение: Task 6 не трогает ChatScreen/ViewModel — может быть запущена параллельно Tasks 7–11 (если исполняется разными агентами). По умолчанию — sequential по порядку.

#### Task 6: AboutScreen + navigation entry + ModelManagerScreen TopAppBar action + `SafeMarkdown`
- **Description:** `SafeMarkdown.kt` — wrapper над `RichText { Markdown(text) }` с `SafeUriHandler` (scheme-whitelist http/https, D25). `AboutScreen.kt` — scrollable, читает `context.assets.open("about.md")`, рендерит через `SafeMarkdown`, футер = `BuildConfig.VERSION_NAME` + атрибуция. Destination `"about"` в NavHost. На `ModelManagerScreen` TopAppBar — `IconButton(Icons.Default.Info)` → `navController.navigate("about")`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :app:assembleDebug` → SUCCESS; unit-тест `SafeUriHandlerTest` (TAC-13) — intent://, sms:, tel: игнорируются.
- **Verify-user:** APK → ModelManager → тап «О приложении» → AboutScreen с текстом из `about.md` и версией в футере (AC-5, AC-6, US-7).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/about/AboutScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/SafeMarkdown.kt`, `app/src/main/assets/about.md`, `app/src/test/kotlin/app/sanctum/machina/ui/chat/SafeUriHandlerTest.kt`
- **Files to read:** `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/MarkdownText.kt`

#### Task 7: `Attachment` + `MultimodalInputBar` + `ThumbnailStrip` + Photo Picker + ChatViewModel attachments state
- **Description:** `Attachment` sealed hierarchy в `:app`. `MultimodalInputBar` — `OutlinedTextField` + 3 `IconButton` (condenonal по `model.llmSupportImage/Audio`) + Send. Photo Picker `ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)`. `ThumbnailStrip` — `LazyRow` миниатюр с ✕. `ChatViewModel`: `attachments: StateFlow<List<Attachment>>`, `addImages(List<Uri>)` через `decodeSampledBitmapFromUri(1024, 1024)`; >10 — первые 10 + snackbar; `removeAttachment(idx)`. Send disabled при пустом тексте и пустом attachments. ChatScreen интегрирует `MultimodalInputBar` и `ThumbnailStrip`.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** APK → ChatScreen → Photo Picker → 3 фото → миниатюры → ✕ → Send; 15 фото → первые 10 + snackbar (AC-9, AC-10, AC-18).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/res/values/strings.xml`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/Attachment.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/MultimodalInputBar.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ThumbnailStrip.kt`
- **Files to read:** `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/MessageInputText.kt` (Photo Picker integration)

#### Task 8: `CameraBottomSheet` (CameraX)
- **Description:** Bottom sheet с CameraX `PreviewView`, кнопки «Снять»/«Закрыть». `ImageCapture.takePicture(executor, OnImageCapturedCallback)` → `ImageProxy.toBitmap()` → `rotateBitmap(exifOrientation)` → `viewModel.addImage(bitmap)` → dismiss. Permission-check CAMERA, snackbar на отказ (D11). `DisposableEffect` bind/unbind provider. На CameraX-init fail → `ErrorLog.e("camera", ...)` + snackbar + dismiss.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** APK → ChatScreen → камера → (первый раз) запрос CAMERA → preview → «Снять» → миниатюра (AC-11, AC-15).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`, `app/src/main/res/values/strings.xml`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/CameraBottomSheet.kt`
- **Files to read:** `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/LiveCameraView.kt`

#### Task 9: `AudioRecorderBottomSheet` (AudioRecord, lifecycle-aware)
- **Description:** Bottom sheet с таймером до 30 сек. `AudioRecord(CHANNEL_IN_MONO, ENCODING_PCM_16BIT, 16000)` в `Dispatchers.IO`, автостоп на 30. «Остановить» → `ByteArray` в `viewModel.addAudio(pcm, durationMs)`. `DisposableEffect + LifecycleEventEffect(ON_PAUSE)` — прерывание при уходе в фон/звонке (D14, AC-19). Permission-check RECORD_AUDIO. Микрофон disabled при вложенном audio (D13, AC-20). При `STATE_INITIALIZED != true` → `ErrorLog.e("audio", ...)` + snackbar + dismiss.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** APK → ChatScreen → микрофон → permission → таймер → «Остановить» → миниатюра; вложенное audio → disabled; звонок → dismiss (AC-12, AC-15, AC-19, AC-20).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/res/values/strings.xml`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/AudioRecorderBottomSheet.kt`
- **Files to read:** `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/AudioRecorderPanel.kt`

#### Task 10: `MessageBubble` extraction + `ThinkingBlock` + markdown rendering + ChatViewModel thinking accumulation + `SYSTEM_PROMPT_DEFAULT` ConfigKey + engine systemInstruction wiring
- **Description:** Вынос `MessageBubble` из приватного composable `ChatScreen.kt` в отдельный файл `MessageBubble.kt`. `ThinkingBlock.kt` — collapsible, левая линия `drawBehind(outlineVariant)`, приглушённый текст, `SafeMarkdown(thinkingText)`, auto-expand при inProgress. `MessageBubble` — при `thinkingText != null && model.llmSupportThinking` → `ThinkingBlock` над основным `SafeMarkdown(text)`. `Message` + `thinkingText: String?`. `ChatViewModel` — отдельный `StringBuilder` для thinking в `resultListener`, skip при `!model.llmSupportThinking || !effectiveConfig[ENABLE_THINKING]`. В `:core-runtime`: `ConfigKeys.SYSTEM_PROMPT_DEFAULT` (verify — если уже есть в Phase 1, noop), `createLlmChatConfigs(..., defaultSystemPrompt = "")` эмитит default, `LlmChatModelHelper.initialize` — проброс `model.configValues[SYSTEM_PROMPT_DEFAULT]` в `engine.createConversation(systemInstruction = ...)` (D24). Ручной smoke на Honor 200 — проверить применение `enableThinking` через `resetConversation` (если on-the-fly работает — перенести в полу-лёгкое D15, зафиксировать в `decisions.md`).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** APK → ChatScreen с `llmSupportThinking=true` + `enableThinking=true` → вопрос «подумай и ответь» → над ответом collapsible блок «Показать ризонинг» (AC-7, AC-14, AC-18). После установки systemPrompt и перехода в чат — engine отвечает в указанном стиле (AC-4 systemPromptDefault end-to-end).
- **Verify-smoke:** `./gradlew :core-runtime:test` — прежние тесты (Allowlist + MediaUtils + AudioClip + MultimodalContentsBuilder) остаются зелёными; `./gradlew :app:assembleDebug` → SUCCESS.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/Message.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/res/values/strings.xml`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Config.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt` (systemInstruction plumbing в `awaitInitialize`), `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/ModelAllowlist.kt` (прокинуть `defaultSystemPrompt` в `createLlmChatConfigs`)
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/MessageBubble.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ThinkingBlock.kt`
- **Files to read:** `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/chat/MessageBodyThinking.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt` (initialize flow)

#### Task 11: `EffectiveConfig` + `InferenceSettingsBottomSheet` + `HeavyChangeDialog` + `ReinitProgressDialog` + autoscroll + ChatViewModel state machines + ChatScreen final integration + `ChatViewModelTest`
- **Description:** `EffectiveConfig.merge(defaults, overrides)` — pure, type-safe (D16). `InferenceSettingsBottomSheet` — 7 полей (условно `enableThinking` при `llmSupportThinking`). Читает `repository.observePerModelSettings(modelId)`, показывает effective. «Применить»: лёгкие → `save` + `viewModel.applyLightOverrides()`; `systemPromptDefault` → `save` + `viewModel.applySystemPromptAndReset()` + snackbar; тяжёлые → `HeavyChangeDialog` → `ReinitProgressDialog` → `viewModel.applyHeavySetting`. «Default» → `reset` + возможный reinit при тяжёлом-override. ChatScreen TopAppBar: Settings ⚙, Reset ↻, Back. `resetConversation()` (D23) — clear messages/attachments + `registry.resetConversation(..., systemPrompt = effective)`. Autoscroll `LaunchedEffect(size, lastLength) → animateScrollToItem`. `ChatViewModelTest` — все сценарии из Testing Strategy (thinking accumulation, attachments, applyLightOverrides, applyHeavySetting sequencing + error path, applySystemPromptAndReset, resetConversation).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :app:test --tests EffectiveConfigTest --tests ChatViewModelTest` → зелёные; `./gradlew :app:assembleDebug` → SUCCESS.
- **Verify-user:** APK → ChatScreen → ⚙ → temp 1.0→0.2 → «Применить» → ответ менее разнообразный; accelerator GPU→CPU → HeavyChangeDialog → confirm → ReinitProgressDialog → продолжение; systemPrompt change → snackbar «Системный промпт применён, контекст чата сброшен» + лента очищена; «Default» → overrides сброшены; Reset ↻ → лента очищена + engine reset; autoscroll следит за стримом (AC-4, AC-8, AC-18, AC-21).
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt`, `app/src/main/res/values/strings.xml`
- **Files to create:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/EffectiveConfig.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/InferenceSettingsBottomSheet.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/HeavyChangeDialog.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/chat/ReinitProgressDialog.kt`, `app/src/test/kotlin/app/sanctum/machina/ui/chat/EffectiveConfigTest.kt`, `app/src/test/kotlin/app/sanctum/machina/ui/chat/ChatViewModelTest.kt`
- **Files to read:** `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/ui/common/ConfigDialog.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Config.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`

### Audit Wave (parallel)

#### Task 12: Code Audit
- **Description:** Full-feature code quality audit. Прочитать все source-файлы Phase 2. Holistic review: module-boundary (`:core-runtime` и `:core-settings` без Compose/Activity — grep), lifecycle-hygiene (CameraX bind/unbind, AudioRecord release), autoscroll performance, permission-flow consistency, markdown-safety (`SafeMarkdown`/`SafeUriHandler`), cross-component duplicate init, shared resources compliance. Report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 13: Security Audit
- **Description:** Full-feature security audit. OWASP Top 10 / OWASP MASVS. Permission misuse (on-demand, не при старте); `allowBackup=false` + `dataExtractionRules` активны; attachment in-memory OOM vectors; content-URI handling в `decodeSampledBitmapFromUri`; DataStore private-file permissions; Intent handling (`ACTION_APPLICATION_DETAILS_SETTINGS`); `SafeUriHandler` — blocked-scheme enumeration (intent, sms, tel, custom); secrets hygiene (нет hardcoded tokens). Report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 14: Test Audit
- **Description:** Full-feature test audit. Coverage, meaningful assertions, Robolectric hygiene, `TemporaryFolder` для DataStore, `runTest` для suspend, `ChatViewModelTest` сценарии полные (thinking, attachments, apply*, reset), `EffectiveConfigTest` type-safety/pure/empty-null, `SafeUriHandlerTest` scheme-enumeration, `fixtureMatchesProductionAsset` integrity, AC→test mapping. Pass-criteria: каждый AC user-spec маппится на ≥1 unit-тест или явно помечен manual-smoke (AC-13, AC-14, AC-16, AC-22, US-1..US-7). Report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 15: Pre-deploy QA
- **Description:** Acceptance testing. Запустить: `./gradlew build`, `:core-runtime:test`, `:core-settings:test`, `:app:test`, `lintDebug`, `:app:assembleDebug`. Verify TAC-1..TAC-15. Сформировать `work/phase-2-ui/manual-smoke.md` — чек-лист пользователя по US-1..US-7 + edge cases с expected-outcomes: «тап галерея → Photo Picker → выбор 3 фото → 3 миниатюры над input bar», «15 фото → первые 10 + snackbar", «тап ⚙ → bottom sheet с 7 полями», «temperature 1.0→0.2 → заметно менее разнообразный следующий ответ», «accelerator GPU→CPU → HeavyChangeDialog → confirm → ReinitProgressDialog 5–30 сек → чат продолжается», «back во время стрима → стрим останавливается», «входящий звонок во время записи → sheet закрывается, attachment не добавляется», «установить APK → отключить wi-fi → приложение работает offline». Маппинг user-spec AC-1..AC-22 на автоматически проверяемое / user-verify-only. AC-2, AC-13, AC-14, AC-16, US-1..US-7 — deferred to user on Honor 200. AC-23 автоматически через `Attachment.Audio.durationMs`; AC-24 — manual-smoke (визуальная проверка индикатора); AC-25 (multi-line thumbnail) — implement/defer. Финальный гейт AC-22 — user approval.
- **Files to create:** `work/phase-2-ui/manual-smoke.md`
- **Skill:** pre-deploy-qa
- **Reviewers:** none

**Deploy — N/A** (mobile APK, ручная передача на Honor 200 — deployment.md).
**Post-deploy verification — N/A** (нет MCP для inspection Android UI на физическом устройстве).
