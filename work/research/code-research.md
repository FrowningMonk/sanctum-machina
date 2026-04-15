# Code Research — Google AI Edge Gallery (референс для PhoneWrap)

Дата: 2026-04-14. Источник: `C:/AI-WORK/PhoneWrap/gallery-source/` (read-only). Версия: `versionCode = 23`, `versionName = 1.0.11` (Android only — исследовалась только `Android/src/`, iOS-часть в проекте отсутствует в данном чекауте).

**Масштаб:** 170 Kotlin-файлов, ~37 447 строк (все java/src/main/java). Тестов нет (директорий `test/`, `androidTest/` в репо не существует).

---

## 1. LiteRT-интеграция

### Gradle-зависимости

Файл: `Android/src/gradle/libs.versions.toml`

Ключевые библиотеки для инференса:
- `com.google.ai.edge.litertlm:litertlm-android` — **0.10.0** (помечено `#noinspection GradleDependency`). Это основной LLM-рантайм.
- `com.google.android.gms:play-services-tflite-java` / `tflite-gpu` / `tflite-support` — **16.4.0** (tflite через Google Play Services — используется где-то, но не в основном LLM-потоке).
- `com.google.mlkit:genai-prompt` — **1.0.0-beta2** (это **AICore**-путь — серверный on-device LLM от Google, использует отдельный системный сервис через `BIND_SERVICE` permission).

Gradle-файл: `Android/src/app/build.gradle.kts`. Kotlin 2.2.0, AGP 8.8.2, compileSdk 35, minSdk 31, targetSdk 35, JVM target 11.

### Абстракция движка

Есть чёткая абстракция. Интерфейс — `com.google.ai.edge.gallery.runtime.LlmModelHelper` (`runtime/LlmModelHelper.kt`, 122 строки):

```
interface LlmModelHelper {
  fun initialize(context, model, supportImage, supportAudio, onDone, systemInstruction: Contents?, tools: List<ToolProvider>, enableConversationConstrainedDecoding, coroutineScope)
  fun resetConversation(model, supportImage, supportAudio, systemInstruction, tools, enableConversationConstrainedDecoding)
  fun cleanUp(model, onDone)
  fun runInference(model, input, resultListener, cleanUpListener, onError, images, audioClips, coroutineScope, extraContext)
  fun stopResponse(model)
}

typealias ResultListener = (partialResult: String, done: Boolean, partialThinkingResult: String?) -> Unit
```

Две реализации:
1. **`LlmChatModelHelper`** (`ui/llmchat/LlmChatModelHelper.kt`, 309 строк) — обёртка над `com.google.ai.edge.litertlm` (LiteRT-LM). Основная.
2. **`AICoreModelHelper`** (`runtime/aicore/AICoreModelHelper.kt`, 378 строк) — обёртка над ML Kit GenAI Prompt (системный AICore).

Диспатч выбора: `runtime/ModelHelperExt.kt` — экстеншн-property `Model.runtimeHelper` возвращает одну из двух реализаций по полю `model.runtimeType` (`RuntimeType.LITERT_LM` или `RuntimeType.AICORE`).

Примечание: `LlmChatModelHelper` лежит в пакете `ui/llmchat/`, хотя логически это runtime-слой. Сам файл не содержит UI-зависимостей (Compose, ViewModel и т.п.) — только `android.content.Context`, `Bitmap`, `Log`, `litertlm.*`, `data.Model` и интерфейс `LlmModelHelper`. Расположение путает, но связей с UI нет.

### Модель инференса

`LlmChatModelHelper.initialize()`:
- Создаёт `EngineConfig` с `modelPath`, `backend` (CPU / GPU / NPU через `Backend.CPU()` / `Backend.GPU()` / `Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)`), отдельными `visionBackend` и `audioBackend`, `maxNumTokens`, `cacheDir`.
- Вызывает `Engine(engineConfig).initialize()`.
- Создаёт `Conversation` через `engine.createConversation(ConversationConfig(samplerConfig = SamplerConfig(topK, topP, temperature), systemInstruction, tools))`. Для NPU sampler = null.
- Хранит `LlmModelInstance(engine, conversation)` в `model.instance: Any?` (см. «Потенциальные проблемы»).

`runInference()`:
- Собирает `Contents.of(listOf(Content.ImageBytes, Content.AudioBytes, Content.Text))`.
- Вызывает `conversation.sendMessageAsync(contents, MessageCallback { onMessage, onDone, onError }, extraContext)`. **Streaming** — токены приходят в `onMessage`; thinking-канал отдельно: `message.channels["thought"]`.
- Отменой занимается `conversation.cancelProcess()` в `stopResponse()`.

`runInference` не suspend-функция — использует колбэки. Поток исполнения определяется самим litertlm (видимо, нативный внутренний поток), но `LlmChatViewModel.generateResponse` запускает вызов из `viewModelScope.launch(Dispatchers.Default)`.

### Загрузка и хранение моделей

Файлы:
- `data/Model.kt` — модель-класс, `getPath(context)` строит путь `context.getExternalFilesDir(null)/{normalizedName}/{version}/{downloadFileName}`. Поддержка `localFileRelativeDirPathOverride` и `localModelFilePathOverride` для ручного менеджмента файлов.
- `data/DownloadRepository.kt` (343 строки) — управление скачиванием.
- `worker/DownloadWorker.kt` (369 строк) — `CoroutineWorker` (WorkManager) с foreground-уведомлением, ForegroundServiceType `dataSync`. Поддерживает прогресс, unzip, extra data files, HuggingFace access tokens.
- `ui/modelmanager/ModelManagerViewModel.kt` (**1411 строк, самый большой**) — центральный хаб: список моделей, инициализация/очистка, аллоулист, аутентификация HF, кеш токенов.

Формат моделей: `.task` (LiteRT-LM tflite-based), `.tflite`, zip. Аллоулист загружается с GitHub: `https://raw.githubusercontent.com/google-ai-edge/gallery/refs/heads/main/model_allowlists`.

### Изолируемость рантайм-слоя от UI

**Рантайм-слой почти изолирован от UI.** Прямых Compose-зависимостей в `LlmModelHelper`, `LlmChatModelHelper`, `AICoreModelHelper`, `data/Model.kt` нет. Зависит только от:
- `android.content.Context` (для `getPath`, nativeLibraryDir, externalFilesDir).
- `android.graphics.Bitmap` (передаётся в inference).
- `data/Model.kt`, `data/Config.kt` (конфиг-параметры) — это чистые data-классы, без UI.
- `litertlm` (внешний SDK).

Однако:
- `LlmChatModelHelper` лежит физически в `ui/llmchat/` (не критично, но сбивает).
- Состояние инстанса хранится в `model.instance: Any?` — это мутабельное поле data-класса `Model`. То есть `Model` — одновременно дата-класс и runtime-handle. В «чистом ядре» это стоит переделать.
- `ModelManagerViewModel` (1411 строк) смешивает управление файлами/скачиванием моделей, аутентификацию HF, инициализацию движка, аллоулист и UI-state. Без него не получится переиспользовать runtime «чисто» — придётся вытащить из него сервисный слой.

### Размер «ядра» в LoC (грубая оценка)

Чистый рантайм-слой + модель данных:
- `runtime/LlmModelHelper.kt` (122) + `runtime/ModelHelperExt.kt` (30) + `ui/llmchat/LlmChatModelHelper.kt` (309) + `runtime/aicore/AICoreModelHelper.kt` (378) = **~840 строк**.
- `data/Model.kt` (376) + `data/Config.kt`, `data/ConfigValue.kt`, `data/Consts.kt`, `data/Tasks.kt` (161), `data/Categories.kt` — суммарно ~1000-1500 строк (не считая `DataStoreRepository`, `DownloadRepository`, `ModelAllowlist`).
- Утилиты: `common/Types.kt`, `common/Utils.kt`.

Итого ядро (inference + data-модели, без скачивания и UI): **~1500-2500 строк**, это ~5-7% кодбазы. Если включить скачивание (`DownloadRepository`, `DownloadWorker`) и часть `ModelManagerViewModel`, то **~4000-6000 строк** (~15% кодбазы).

Вся остальная ~85% — UI (Compose), аналитика, скиллы (`customtasks/agentchat/`, `customtasks/tinygarden/`, `customtasks/mobileactions/`), benchmark, onboarding/promo.

---

## 2. Архитектура и data layer

### Паттерн

**MVVM + Hilt DI + Jetpack Compose + Navigation Compose.**

- `GalleryApplication : Application` — `@HiltAndroidApp`, инициализирует Firebase, подгружает тему.
- `MainActivity : ComponentActivity` — `@AndroidEntryPoint`, один экран, держит `ModelManagerViewModel`.
- ViewModels наследуются от `androidx.lifecycle.ViewModel`, помечаются `@HiltViewModel` и инжектятся в Compose через `hiltViewModel()`.
- UI state — через `MutableStateFlow` → `.asStateFlow()` → `collectAsState()` в Compose.
- DI-модуль один: `di/AppModule.kt` (@InstallIn SingletonComponent). Feature-модули `@IntoSet` для `CustomTask` (см. ниже).

### Persistence

**Нет Room, нет SQLite, нет собственной БД.** Только Proto-DataStore.

DataStore-файлы (`data/DataStoreRepository.kt`, 436 строк; `di/AppModule.kt` — провайдеры):
- `settings.pb` (`proto/settings.proto` → `Settings`) — theme, text input history, imported models, TOS acceptance, viewed promo IDs, feature flags.
- `user_data.pb` (`UserData`) — access tokens, secrets (API-ключи и т.п.).
- `cutouts.pb` (`CutoutCollection`) — данные скрапбук-демо.
- `benchmark_results.pb` (`proto/benchmark.proto`) — сохранённые бенчмарки.
- `skills.pb` (`proto/skill.proto`) — агентские скиллы.

Также: `androidx.security.crypto` для шифрования secrets (`SharedPreferences` encrypted).

Структура типа «репозиторий над DataStore» + `DefaultDataStoreRepository` (impl). Все методы синхронные (`runBlocking` внутри) — помечено `TODO(b/423700720): Change to async (suspend) functions`.

**Чаты нигде не персистятся.** Сообщения хранятся только в `ChatUiState.messagesByModel: Map<String, MutableList<ChatMessage>>` в памяти `ChatViewModel` (`ui/common/chat/ChatViewModel.kt`). При закрытии ViewModel / переходе между моделями история теряется (кроме `text_input_history` — это просто список последних введённых строк для автокомплита).

### Навигация

`Navigation Compose`, единый `NavHost` в `ui/navigation/GalleryNavGraph.kt` (597 строк).

Маршруты:
- `ROUTE_HOMESCREEN = "homepage"` — стартовый экран, плитки задач (tiles).
- `ROUTE_MODEL_LIST = "model_list"` — список моделей для выбранной задачи.
- `ROUTE_MODEL = "route_model"` с параметрами `{taskId}/{modelName}` — экран задачи.
- `ROUTE_BENCHMARK = "benchmark"` с `{modelName}`.
- `ROUTE_MODEL_MANAGER = "model_manager"` — менеджер моделей.

Deep linking: `com.google.ai.edge.gallery://model/{taskId}/{modelName}` и `.../global_model_manager`.

### Зависимости от Google Services — риск для форка

**Firebase используется, но опционально.** `build.gradle.kts`:
```
alias(libs.plugins.google.services) apply false  // требует google-services.json
```
- `Firebase.analytics` — обёрнут в `runCatching {}` в `Analytics.kt`: если `google-services.json` нет, приложение работает, просто логирование молчит.
- `FirebaseMessaging` (FCM) — `FcmMessagingService.kt` для push-уведомлений.
- `play-services-oss-licenses` (attribution).
- AICore-путь требует permission `com.google.android.apps.aicore.service.BIND_SERVICE` — но это отдельный runtime-path, можно выключить в форке (убрать `mlkit-genai-prompt` и удалить `AICoreModelHelper`).

**Пронести в форк без Firebase реально**: просто не применять `google-services` плагин и убрать `firebase-*` + `mlkit-genai-prompt` из `build.gradle.kts`, удалить `Analytics.kt`, `FcmMessagingService.kt`, ветку `RuntimeType.AICORE`.

**Play Services TFLite** (`play-services-tflite-java` 16.4.0) — используется где-то, но основной LLM-путь через `litertlm` от Google. Требует наличия Play Services на устройстве (это может быть проблемой для не-Google-Android, но для Honor 200 нормально).

### Другие внешние зависимости

- `androidx.work:work-runtime-ktx` 2.10.0 — `DownloadWorker`.
- `androidx.datastore` 1.1.7 + `protobuf-javalite` 4.26.1 — персистентность настроек.
- `com.halilibo.compose-richtext` (richtext + commonmark) — markdown в чате.
- `net.openid:appauth` 0.11.1 — OAuth для HuggingFace.
- `androidx.camera:*` 1.4.2 — камера для Ask Image.
- `androidx.webkit` 1.14.0 — `GalleryWebView` для агентских скиллов (WebView исполняет JS из скиллов).
- `com.squareup.moshi` 1.15.2 + KSP codegen.
- `com.google.code.gson` 2.12.1.
- Hilt 2.57.2.

---

## 3. UI для AI-сценариев

### Сколько отдельных экранов, сколько общего кода

**Это не 4 отдельных экрана, а один `ChatView` + 4+ тонких обёртки, параметризованных флагами.**

Файл-узел: `ui/common/chat/ChatView.kt` (307 строк) — главный Composable, принимает Task, ViewModel, модель-менеджер, колбэки `onSendMessage`, `onRunAgainClicked`, `onResetSessionClicked`, флаги `showImagePicker`, `showAudioPicker`, `emptyStateComposable`, `sendMessageTrigger`, `composableBelowMessageList`, `allowEditingSystemPrompt`, `curSystemPrompt`, `onSystemPromptChanged`, и т.д.

`ChatView` рендерит:
- Pager по моделям (когда у Task несколько моделей — можно свайпать).
- `ChatPanel` (`ui/common/chat/ChatPanel.kt`, 619 строк) — сам список сообщений и `MessageInputText`.

Обёртки:
- **`LlmChatScreen`** (`ui/llmchat/LlmChatScreen.kt`) — `AI Chat`. Делает `ChatViewWrapper` с `showImagePicker=false, showAudioPicker=false`.
- **`LlmAskImageScreen`** — те же параметры, но `showImagePicker=true, showAudioPicker=false` + свой `emptyStateComposable` с текстом «загрузи фото».
- **`LlmAskAudioScreen`** — `showImagePicker=false, showAudioPicker=true` + свой emptyState.

Все три оборачивают один и тот же `ChatViewWrapper` → `ChatView`. Три ViewModel (`LlmChatViewModel`, `LlmAskImageViewModel`, `LlmAskAudioViewModel`) — все пустые, наследуются от общего `LlmChatViewModelBase` без добавления логики. `LlmChatViewModel.kt` содержит все три:
```
@HiltViewModel class LlmChatViewModel @Inject constructor() : LlmChatViewModelBase()
@HiltViewModel class LlmAskImageViewModel @Inject constructor() : LlmChatViewModelBase()
@HiltViewModel class LlmAskAudioViewModel @Inject constructor() : LlmChatViewModelBase()
```
Причина существования трёх VM — Hilt-скоупинг per-task (разные инстансы для разных задач), не функциональность.

**AgentChat (Agent Skills)** — `customtasks/agentchat/AgentChatScreen.kt` (586 строк) — использует **тот же** `ChatView` (через `ChatViewWrapper` косвенно — реально сам вызывает `LlmChatScreen` с дополнительными параметрами типа `onSkillClicked`, bottom sheets для управления скиллами, `composableBelowMessageList`, кастомный `onResetSessionClickedOverride`). То есть AgentChat = AI Chat + слой tools/skills сверху.

**PromptLab (Audio Scribe — это отдельный таск `LLM_PROMPT_LAB`)** — `ui/llmsingleturn/` — это **другой UI**: `VerticalSplitView` (сверху промпт-шаблоны, снизу ответ), **не** ChatView. Так что не все 4 сценария идентичны. «Аудио Скрайб» в README — это, видимо, `LLM_ASK_AUDIO` (обычный чат с аудио-вводом), а `LLM_PROMPT_LAB` — это Prompt Lab, он действительно отличается.

**Вывод:** реально идентичный UI используют 3 сценария — AI Chat, Ask Image, Ask Audio. Все параметризуются флагами `showImagePicker`/`showAudioPicker` и `emptyStateComposable`. Agent Skills — это тот же UI + дополнительный слой. Prompt Lab — отдельный UI.

### Связь UI с ViewModel и движком

- `ChatView` принимает абстрактную `ChatViewModel` (abstract base), работает через её методы `addMessage`, `clearAllMessages`, `uiState.collectAsState()`.
- `LlmChatViewModelBase.generateResponse()` вызывает `model.runtimeHelper.runInference(...)` — прямая ссылка на runtime.
- UI **не знает** о `litertlm` — связь через интерфейс.
- ViewModel знает о движке напрямую, но через интерфейс `LlmModelHelper`.

Чтобы заменить `ChatView` на свой UI — нужно:
1. Унаследовать `ChatViewModel` (или `LlmChatViewModelBase`) или написать свой, использующий `runtimeHelper`.
2. Реализовать свой Composable, принимающий `ChatUiState` + колбэки.

Это выполнимо. Связь Compose ↔ ViewModel идиоматична (state-hoisting через StateFlow).

### `CustomTask` API

`customtasks/common/CustomTask.kt` — в Gallery есть явное API для плагинных задач:
```kotlin
interface CustomTask {
  val task: Task
  fun initializeModelFn(context, coroutineScope, model, onDone)
  fun cleanUpModelFn(context, coroutineScope, model, onDone)
  @Composable fun MainScreen(data: Any)
}
```
Hilt-мультибиндинг через `@IntoSet` (см. `AgentChatTaskModule`). Это означает, что **в Gallery уже заложена механика добавления новых «экранов-задач» без модификации ядра** — логично, если PhoneWrap захочет оставить форк близким к upstream.

---

## 4. Извлекаемость ядра

### Что в «ядре без UI»

Чистый runtime + data-model:
- `runtime/` (LlmModelHelper, ModelHelperExt, aicore/AICoreModelHelper) — ~840 строк
- `data/Model.kt`, `data/Config.kt`, `data/ConfigValue.kt`, `data/Tasks.kt`, `data/Consts.kt`, `data/Types.kt`, `data/Categories.kt` — ~1500 строк
- `common/Utils.kt`, `common/Types.kt` — утилиты (~200-300 строк)

Управление файлами моделей (если нужно):
- `data/DataStoreRepository.kt` (только часть — imported models, access tokens) — ~200 строк чистой логики
- `data/DownloadRepository.kt` + `worker/DownloadWorker.kt` — ~700 строк
- Часть `ui/modelmanager/ModelManagerViewModel.kt` (инициализация/очистка/аллоулист) — ~500-800 строк; **нужно рефакторить** (1411 строк, смешанные ответственности).

Итого **чистое ядро: ~2500 строк**. **Ядро + model management: ~4500-5000 строк**.

### Внешние зависимости ядра

- `com.google.ai.edge.litertlm` (обязательно — сам движок)
- `androidx.work` (если нужен download)
- `androidx.datastore` + `protobuf-javalite` (persistence настроек)
- `com.google.mlkit.genai-prompt` (опционально — AICore)
- `com.google.android.gms:play-services-tflite-*` (возможно опционально)
- Hilt (архитектурная зависимость, можно пронести или заменить)
- `android.*` (Context, Bitmap) — ядро **не** pure-kotlin, привязано к Android.

### Проблемы извлечения

1. **`Model.instance: Any?`** — runtime-состояние хранится в data-классе. При выделении ядра в модуль стоит развести `ModelDefinition` (immutable config) и `ModelRuntimeHandle` (instance, state).
2. **`ModelManagerViewModel` (1411 строк)** — god-object: аллоулист, аутентификация HF, скачивание, инициализация движка, prev/next selection, AICore feature detection, text input history, theme. Его надо разбить на сервисы, иначе при форке постоянные мерж-конфликты с upstream.
3. **`LlmChatModelHelper` лежит в `ui/llmchat/`** — путаница в физическом расположении относительно логического слоя.
4. **Статическое состояние:**
   - `LlmChatModelHelper.cleanUpListeners: MutableMap<String, CleanUpListener>` — singleton state в object.
   - `AICoreModelHelper.cleanUpListeners` — то же.
   - `ExperimentalFlags.enableConversationConstrainedDecoding = true` — глобальный флаг litertlm, пишется перед `createConversation` и сбрасывается сразу после (race если одновременно инициализируются две модели).
   - `val firebaseAnalytics: FirebaseAnalytics?` — топ-левел global в `Analytics.kt`.
5. **Hard dependencies на `Context`** — `getPath()`, `nativeLibraryDir`, `getExternalFilesDir`. Это Android-специфично, но ожидаемо для Android-приложения.
6. **Моб. чат-сообщения (`ChatMessage`) содержат `Bitmap` и `ImageBitmap`** — UI-типы внутри data-классов. Для персистентности (см. PhoneWrap goals) это нужно сериализовать отдельно.

---

## 5. Риски форка

### Лицензия

**Apache License 2.0.** Файлы несут копирайт `Copyright 2025 Google LLC` / `Copyright 2026 Google LLC`. По Apache 2.0 форк разрешён, обязательства:
- Сохранить `LICENSE`-файл.
- Сохранить NOTICE, если будет (в текущем чекауте отсутствует).
- В каждом изменённом файле — отметить изменения (stated changes).
- Сохранить исходные копирайт-хедеры в неизменённых файлах.

Название «Google AI Edge Gallery» и логотипы — не брать (чтобы не было путаницы / trademark).

### Breaking changes при обновлении upstream

- `com.google.ai.edge.litertlm` — **0.10.0**, отмечено `#noinspection GradleDependency`. Это указывает на то, что Gradle считает версию подозрительной (возможно, неcтабильный API). Многое помечено `@OptIn(ExperimentalApi::class)` — API может менять сигнатуры. Высокий риск breaking change в minor-версиях.
- Проект **очень активный**: файлы с `Copyright 2026` указывают на регулярный апдейт. Code churn высокий.
- Промо-экраны (`PromoScreenGm4`, `PromoBannerGm4`) жёстко зашиты под конкретные релизы моделей (Gemma 4) — будут постоянно обновляться.

**Следить за upstream в одиночку** — реально для файлов ядра (рантайм, data-model — ~2500 строк, низкий churn), **не очень реально** для UI и customtasks (много изменений, часто cosmetic).

### Технический долг, странные решения

1. `ModelManagerViewModel` на 1411 строк — god-object.
2. `DataStoreRepository` — все методы синхронные (`runBlocking` в несуспенд-функциях). Явный TODO `b/423700720`.
3. `LlmChatModelHelper` в пакете `ui/llmchat/` (должен быть в `runtime/`).
4. `ExperimentalFlags.enableConversationConstrainedDecoding` — мутация глобального флага вокруг создания conversation (race condition при параллельной инициализации).
5. Инстанс модели хранится в `model.instance: Any?` — смешение data и runtime.
6. 3 дубля ViewModel (`LlmChatViewModel`, `LlmAskImageViewModel`, `LlmAskAudioViewModel`) с пустыми телами — для Hilt-скоупинга. Можно заменить на один VM + qualifier, но мелочь.
7. **Нет тестов** вообще. Ни unit, ни instrumentation. Для соло-разработчика это и плюс (нечего чинить), и минус (нет safety net при рефакторинге).
8. Файл `Model.kt` — data-класс с 25+ полями, большинство optional, с мутабельными полями (`var configs`, `var instance`, `var initializing`). Нарушает принципы immutability.
9. Русскоязычный разработчик будет разбирать комментарии на английском и строковые ресурсы под локализацию — в `res/values/` только английский (не проверял, но традиционно для Google-проектов).

### Размер кодбазы

- **170 Kotlin-файлов**, **37 447 строк** (без учёта XML, ресурсов).
- ~85% — UI, customtasks, ModelManagerViewModel, onboarding.
- ~5-7% — чистое ядро инференса и data-model.

---

## 6. Цели PhoneWrap — места для интеграции

### История чатов (persist)

**Где вписывать:**
- Новый persistence-слой. DataStore + Proto подойдёт, но если чатов много — лучше **Room** (SQLite): сейчас в проекте нет Room, зависимость `androidx.room:*` надо добавить. Альтернатива: расширить существующий `DataStoreRepository` новым proto-файлом `chat_history.pb` — проще встраивается в текущий стиль, но плохо масштабируется при больших объёмах.
- Модифицировать `ChatViewModel.ChatUiState`: сейчас `messagesByModel: Map<String, MutableList<ChatMessage>>` — только in-memory. Добавить persistent backing: при `addMessage` писать в репозиторий, при входе в экран — читать.
- `ChatMessage` содержит Bitmap и ImageBitmap — их надо сериализовать в файловую систему отдельно и хранить путь, либо сжимать в BLOB.
- Ключ хранения: сейчас только `model.name`. Для PhoneWrap надо ввести понятие **Chat** (id, timestamp, title, modelName, projectId) — новая сущность.

### Проекты с общим контекстом

**Где вписывать:**
- Новая сущность `Project { id, name, systemPrompt, modelPreference?, chatIds }`.
- `Task` и `Model` трогать не нужно — они описывают «тип задачи» и «движок», а не сессию.
- В Gallery уже есть `defaultSystemPrompt: String` на `Task` (`data/Tasks.kt`) — но это per-task-type. Для проекта — per-project.
- `allowEditingSystemPrompt` и `curSystemPrompt` уже пробрасываются через `LlmChatScreen`/`ChatViewWrapper` в `ChatView`. Значит, механизм «дать юзеру редактировать system prompt» уже частично встроен — осталось привязать к проекту и сохранять.
- При старте чата в проекте — брать `project.systemPrompt`, при `viewModel.resetSession(systemInstruction = Contents.of(listOf(Content.Text(systemPrompt))))` — litertlm уже это принимает.

### Разделение / унификация UI-сценариев

**Факт:** AI Chat + Ask Image + Ask Audio = один `ChatView` с флагами `showImagePicker`/`showAudioPicker`. Унификация **уже есть**. Код «трёх экранов» — это ~60 строк обёрток в `LlmChatScreen.kt`.

**Что действительно отличается:**
- Prompt Lab (`ui/llmsingleturn/`) — другой UI (split view).
- Agent Skills — тот же ChatView + управление скиллами сверху.
- Mobile Actions, Tiny Garden — отдельные экраны в `customtasks/`.

**Предложение для PhoneWrap:** убирать «лишние» сценарии не нужно — они и так на одном движке. Можно просто:
- Убрать из UI главного экрана плитки/карточки, которые не нужны (Mobile Actions, Tiny Garden, Prompt Lab, Benchmark) — правится в списке задач (`ModelManagerViewModel.getTasks()` и/или отключением Hilt-модулей для соответствующих `CustomTask`).
- Оставить один универсальный чат-экран, принимающий модальности (text / image / audio) через состояние проекта или чата.
- Текущая архитектура под это готова: единый `ChatView`, параметризованный флагами.

### Что менять не надо трогать ради целей PhoneWrap

- `runtime/` + `LlmChatModelHelper` — ядро LiteRT-интеграции, работает, изолировано.
- `data/Model.kt`, `data/Config.kt` — data-model.
- `DownloadWorker`, `DownloadRepository` — скачивание моделей работает.
- `ModelManagerViewModel` частично — но стоит рефакторить, когда форк стабилизируется.

---

## 7. Ключевые файлы (для быстрого возврата)

| Путь | Что |
|---|---|
| `Android/src/app/build.gradle.kts` | Gradle-конфиг, зависимости |
| `Android/src/gradle/libs.versions.toml` | Версии всех либ |
| `Android/src/app/src/main/AndroidManifest.xml` | Permissions, activities, FCM |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/GalleryApplication.kt` | Application class, Hilt |
| `Android/src/app/src/main/java/com/google/ai/edge/gallery/MainActivity.kt` | Единственная Activity |
| `.../runtime/LlmModelHelper.kt` | **Интерфейс runtime-движка** |
| `.../runtime/ModelHelperExt.kt` | Диспатч LiteRT vs AICore |
| `.../ui/llmchat/LlmChatModelHelper.kt` | **Обёртка над litertlm** (основной движок) |
| `.../runtime/aicore/AICoreModelHelper.kt` | Обёртка над ML Kit GenAI (AICore) |
| `.../data/Model.kt` | Data-класс модели, getPath() |
| `.../data/Tasks.kt` | Task, BuiltInTaskId |
| `.../data/DataStoreRepository.kt` | Persistence (Proto DataStore) |
| `.../data/DownloadRepository.kt` + `.../worker/DownloadWorker.kt` | Скачивание моделей |
| `.../ui/modelmanager/ModelManagerViewModel.kt` | **1411 строк, god-object** |
| `.../ui/llmchat/LlmChatViewModel.kt` | Базовый chat ViewModel (3 класса) |
| `.../ui/llmchat/LlmChatScreen.kt` | 3 экрана-обёртки (Chat, AskImage, AskAudio) → один `ChatViewWrapper` |
| `.../ui/common/chat/ChatView.kt` | **Универсальный чат-экран** (Composable) |
| `.../ui/common/chat/ChatViewModel.kt` | Базовый ChatViewModel, ChatUiState (in-memory!) |
| `.../ui/common/chat/ChatPanel.kt` | Рендер сообщений, MessageInputText |
| `.../ui/common/chat/ChatMessage.kt` | Типы сообщений |
| `.../ui/navigation/GalleryNavGraph.kt` | Compose NavGraph |
| `.../customtasks/common/CustomTask.kt` | Плагинное API задач |
| `.../customtasks/agentchat/AgentChatScreen.kt` + `AgentChatTaskModule.kt` | Agent Skills (Hilt @IntoSet) |
| `.../customtasks/tinygarden/`, `.../mobileactions/`, `.../examplecustomtask/` | Реализации CustomTask |
| `.../ui/llmsingleturn/LlmSingleTurnScreen.kt` | Prompt Lab (отдельный UI) |
| `.../di/AppModule.kt` | Hilt AppModule |
| `.../proto/settings.proto`, `benchmark.proto`, `skill.proto` | Protobuf-схемы |
| `.../Analytics.kt`, `.../FcmMessagingService.kt` | Firebase (опционально) |

---

## Сводка неуверенностей

- **iOS-часть** в чекауте отсутствует — не исследована. Если цель PhoneWrap — только Android, это не проблема.
- Не читал полностью `ModelManagerViewModel.kt` (1411 строк) — оценки god-object-дизайна сделаны по сигнатурам и общей структуре.
- Не смотрел детально рендеринг Compose (ChatPanel 619 строк) — если важна точность производительности/ререндеров, надо отдельный проход.
- Не проверял, реально ли используется `play-services-tflite-java` (основной путь — litertlm). Возможно, используется только в Benchmark-экране или для AICore.
- Не исследован `ProjectConfig.kt` (common/) — судя по имени, feature flags или config-константы. 3 файла common/* — ~200-300 строк.
- Тестов нет — не изучал фреймворк тестирования; в `build.gradle.kts` прописаны junit/espresso/ui-test, но нет файлов.

---

**Итоговая картина для решения о форке:**

- Ядро (рантайм + data) — ~2500 строк, чистое, изолированное от UI через интерфейс, низкий churn → легко поддерживать.
- UI-слой — ~30 000 строк, высокий churn в upstream, смешанные ответственности (особенно ModelManagerViewModel), но уже имеет механизм плагинных задач (`CustomTask`) и универсальный ChatView → можно строить PhoneWrap UX поверх, подменяя экраны.
- Persistence — только настройки, **чаты не сохраняются** (in-memory StateFlow). Для PhoneWrap нужно добавить новый слой (Room предпочтительнее для масштаба).
- Лицензия Apache 2.0 — без юридических препятствий, требует только сохранения атрибуции.
- Firebase, AICore, Mobile Actions, Tiny Garden — опциональны, можно отключить.
