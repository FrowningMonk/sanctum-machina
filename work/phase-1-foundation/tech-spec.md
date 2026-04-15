---
created: 2026-04-15
status: approved
branch: phase/1-foundation
size: L
---

# Tech Spec: phase-1-foundation

## Solution

Собираем валидационный спайк Sanctum Machina — двухмодульный Gradle-проект (`:app` + `:core-runtime`), куда хирургически переносим ядро инференса и скачивания моделей из Google AI Edge Gallery, поверх него пишем минимальный Compose-UI «список моделей → чат». Цель — доказать на Honor 200, что Gemma-4-E2B-it после извлечения из Gallery работает не медленнее, чем в оригинальном Gallery (AC-14: TTFT ≤ 1.5× Gallery на том же промпте).

Архитектура намеренно упрощена: никакого Room, никакого Proto DataStore, никакого OAuth, никакого Firebase. State живёт в `StateFlow` в памяти и в файлах на диске. Gallery-код копируется с точечными патчами (~11 строк удаления HF-токена, ~3 вызова Firebase analytics, 1 хардкод `MainActivity` через `BuildConfig`). Единственный non-copy-paste рефакторинг — замена anti-pattern `Model.instance: Any?` на `ModelDefinition` + `ModelRuntimeHandle` (D6). Единственный автотест — парсер bundled allowlist (D8).

Phase 1 UI намеренно throwaway: ~250 строк Compose, сырой Material 3 без тем, русские строки по `ux-guidelines.md`. В Phase 2 будет переписан под дизайн-направление Pocket LLM. Имена сервисов (`ModelRegistry`, `DefaultModelRegistry`) и раскладка Hilt-модулей тоже зарезервированы под рефакторинг в Phase 2 — Phase 1 не финальная архитектура, а проверка работоспособности.

## Architecture

### What we're building/modifying

**Новые модули Gradle:**

- **`:core-runtime`** (Android library module, namespace `app.sanctum.machina.core`) — UI-free ядро: копия inference + download из Gallery с патчами, плюс собственные `ModelRegistry` и `ErrorLog`. Граница модуля: ноль импортов `androidx.compose.*` и `androidx.activity.*` (верифицируется grep'ом в QA).
- **`:app`** (Android application module, namespace `app.sanctum.machina`, applicationId `app.sanctum.machina`) — Compose-оболочка: `SanctumApplication` + `MainActivity` + два экрана (`ModelManagerScreen`, `ChatScreen`) + навигация + `AndroidManifest.xml`.

**Компоненты внутри `:core-runtime`:**

- **`data/Model.kt`, `ConfigValue.kt`, `Config.kt`, `Consts.kt`, `Types.kt`, `ModelAllowlist.kt`** — копии из Gallery с патчами (см. Decisions D3, D6).
- **`data/DownloadRepository.kt`** — копия из Gallery, патчи: удалить HF-токен-plumbing (lines 123–125 + import 39 + ctor param 83), удалить 3 вызова `firebaseAnalytics.logEvent(...)`, заменить `lifecycleProvider.isAppInForeground` (line 276) на `ProcessLifecycleOwner` check.
- **`worker/DownloadWorker.kt`** — копия из Gallery, патчи: удалить HF-токен (lines 32, 105, 134–137), заменить `Class.forName("com.google.ai.edge.gallery.MainActivity")` (line 342) на `BuildConfig.MAIN_ACTIVITY_CLASS_NAME` константу.
- **`runtime/LlmModelHelper.kt`** — копия as-is (interface).
- **`inference/LlmChatModelHelper.kt`** — перенос из `ui/llmchat/LlmChatModelHelper.kt`, копия as-is (Kotlin `object`, 309 LoC). Содержит готовый `resetConversation` API (research §13) и `initialize` с catch-all вокруг `Engine()` + `engine.initialize()` + `createConversation()` (research §14).
- **`registry/ModelRegistry.kt`, `registry/DefaultModelRegistry.kt`** — **новый код** (~120 LoC). Hilt `@Singleton`. Экспонирует list/download/init/cleanup/resetConversation/getInstance. Владеет `Mutex` на lifecycle + stale-instance guard (research §12.3). GPU→CPU fallback внутри `initialize()` без парсинга текста ошибки (research §14).
- **`registry/AllowlistLoader.kt`** — **новый код** (~30 LoC). Читает `assets/model_allowlist.json`, парсит через Gson, возвращает `List<Model>`. Покрыт unit-тестом (D8, user-spec Тестирование).
- **`log/ErrorLog.kt`** — **новый код** (~60 LoC). Hilt `@Singleton`. Пишет в `context.filesDir/logs/errors.log`, ротация при >2 МБ (переименование в `errors.log.1`). Формат по `patterns.md`: `ERROR [component] short description :: cause`. Компоненты Phase 1: `download`, `inference-init`, `inference`, `inference-cleanup` (D11).
- **`di/CoreRuntimeModule.kt`** — **новый код**. `@Module @InstallIn(SingletonComponent::class)`. Provides `DownloadRepository`, `LlmModelHelper`, `ModelRegistry`, `ErrorLog`.
- **`assets/model_allowlist.json`** — форк `gallery-source/model_allowlists/1_0_11.json`, обрезанный до 2 моделей: Gemma-4-E2B-it + Gemma-4-E4B-it.

**Компоненты внутри `:app`:**

- **`SanctumApplication.kt`** — `@HiltAndroidApp class SanctumApplication : Application()`. Никакой Firebase init, никакой DataStore.
- **`MainActivity.kt`** — `@AndroidEntryPoint`, `setContent { SanctumApp() }`, runtime-запрос `POST_NOTIFICATIONS` на SDK 33+.
- **`ui/SanctumApp.kt`** — `NavHost` с двумя route'ами: `model_manager` (start) и `chat/{modelName}`.
- **`ui/modelmanager/ModelManagerScreen.kt` + `ModelManagerViewModel.kt`** — `@HiltViewModel`. Отображает `registry.models`, обрабатывает Download/Cancel/Load.
- **`ui/chat/ChatScreen.kt` + `ChatViewModel.kt`** — `@HiltViewModel`. Владеет `List<Message>` в `StateFlow`. Streaming через `registry.getInstance(modelName)` + `LlmChatModelHelper.runInference`. Считает TTFT через `System.currentTimeMillis()`. Reset через `registry.resetConversation(modelName)`. Stop через `LlmChatModelHelper` cancel path.
- **`AndroidManifest.xml`** — permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK`. `uses-native-library` для `libOpenCL.so` (не required), `libvndksupport.so` (не required). `<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge"/>`.
- **`res/values/strings.xml`** — русские строки по `ux-guidelines.md` (тон нейтрально-минималистичный, без эмодзи, без «пожалуйста»).
- **`BuildConfig.MAIN_ACTIVITY_CLASS_NAME`** — через `buildConfigField("String", "MAIN_ACTIVITY_CLASS_NAME", "\"app.sanctum.machina.MainActivity\"")` в `:app/build.gradle.kts`. Считывается из `:core-runtime`'s `DownloadWorker`.

**Gradle-инфраструктура:**

- `settings.gradle.kts` — `rootProject.name = "PhoneWrap"`, `include(":app", ":core-runtime")`, pluginManagement с google/mavenCentral/gradlePluginPortal.
- `build.gradle.kts` (root) — плагины `apply false`: `android.application`, `android.library`, `kotlin.android`, `kotlin.compose`, `hilt.application`, `ksp`.
- `gradle/libs.versions.toml` — каталог пиннингованных версий (список в Dependencies).
- `:app/build.gradle.kts` — `compileSdk=35`, `minSdk=31`, `targetSdk=35`, `jvmTarget="11"`, `buildFeatures { compose=true; buildConfig=true }`, `kotlinOptions.freeCompilerArgs += "-Xcontext-receivers"`.
- `:core-runtime/build.gradle.kts` — android-library, то же compileSdk/minSdk/jvmTarget, `buildFeatures { buildConfig=false }` (BuildConfig только в `:app`, класс считывается через dependency).
- `.gitignore` — стандартный Android (`.idea/`, `*.iml`, `build/`, `local.properties`) + `gallery-source/` + `.env`, `*.keystore`, `*.jks`, `release-keys/` (по `patterns.md`).

### How it works

**Download flow:**

1. На старте `SanctumApplication.onCreate()` → Hilt создаёт синглтоны, включая `DefaultModelRegistry`. В его `init{}` запускается `scanLocalFiles()`: для каждого `Model` из allowlist считается `Model.getPath(ctx)` и проверяется `File.exists()` → заполняется `StateFlow<List<ModelEntry>>` c статусами NOT_DOWNLOADED / SUCCEEDED. Вызывается `resumePartialDownloads()` (re-enqueue любых `*.gallerytmp` в IN_PROGRESS).
2. User тапает «Скачать» → `ModelManagerViewModel.onDownload(model)` → `registry.download(model)` → делегация в `DownloadRepository.downloadModel(model)` → WorkManager enqueue `DownloadWorker` с тегом `"download_${model.name}"`.
3. `DownloadWorker` работает как foreground service с notification (системное требование Android). HTTP GET с `Range: bytes=N-` если есть `*.gallerytmp`. Прогресс публикуется через `setProgress(Data)` каждые 200 мс.
4. `DownloadRepository.observerWorkerProgress` мониторит WorkInfo → пишет в `model.downloadStatus` → UI обновляется через `registry.models` StateFlow.
5. По завершении: `outputTmpFile.renameTo(originalFile)` → статус `SUCCEEDED`. При ошибке → статус `FAILED` + `errorMessage`, `ErrorLog.e("download", ...)`.

**Load / inference flow:**

1. User тапает «Загрузить» на SUCCEEDED-модели → `ModelManagerViewModel.onLoad(model)` → navigation на `chat/{modelName}`.
2. `ChatScreen` показывает «Загрузка модели…» → в `ChatViewModel.init` вызывается `registry.initialize(modelName)`. Внутри — `lifecycleMutex.withLock`: (a) попытка c accelerator из allowlist (GPU); (b) при ошибке — `cleanUp` + патч `configValues[ACCELERATOR] = CPU` + вторая попытка. Любая ошибка → `ErrorLog.e("inference-init", ...)` + surfacing в UI через `Result.failure`.
3. User отправляет промпт → `ChatViewModel.send(text)`:
   - `startMs = System.currentTimeMillis()`
   - `registry.getInstance(modelName)` → `LlmModelInstance` (хранится на `Model.instance`)
   - `LlmChatModelHelper.runInference(model, input = text /* String, Contents собирается внутри */, resultListener, cleanUpListener, onError, images = emptyList(), audioClips = emptyList(), coroutineScope = viewModelScope)`.
   - `resultListener` имеет тип `(partialResult: String, done: Boolean, partialThinkingResult: String?) -> Unit`. Первый непустой `partialResult` → `firstTokenMs = System.currentTimeMillis()` → `TTFT = firstTokenMs - startMs`. Накопление текста в `StateFlow<String>`. На `done = true` → `totalMs = System.currentTimeMillis() - startMs`, формируется футер `"TTFT {TTFT}ms · {totalMs/1000.0}s total"`. Ошибки приходят отдельно через `onError: (message: String) -> Unit` (Gallery-сигнатура) — помечают сообщение как interrupted или показывают inline-ошибку.
4. Stop → `LlmChatModelHelper` cancel path (`conversation.cancelProcess()`), partial текст помечается суффиксом «(прервано)».
5. Reset → `registry.resetConversation(modelName, systemPrompt=null)` → `LlmChatModelHelper.resetConversation(model)` → закрывает `instance.conversation` и мутирует его на новый (создан через `engine.createConversation`); сам `model.instance` и `engine` не пересоздаются (research §13).
6. Back из `ChatScreen` → `ChatViewModel.onCleared` → `registry.cleanup(modelName)` под Mutex → `LlmChatModelHelper.cleanUp(model)` → engine + conversation closed → native память освобождена.

**Offline:** после SUCCEEDED файл лежит в `externalFilesDir`. Inference пути не требуют сети — AC-13 выполняется как побочный эффект архитектуры (`:core-runtime` не делает сетевых вызовов во время inference).

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|----------------|-----------|----------------|
| `LlmModelHelper` (Kotlin `object` `LlmChatModelHelper`) | `CoreRuntimeModule.provideLlmModelHelper` | `DefaultModelRegistry` | 1 (singleton by design) |
| `DownloadRepository` | `CoreRuntimeModule.provideDownloadRepository` | `DefaultModelRegistry` | 1 (@Singleton) |
| `ModelRegistry` | `CoreRuntimeModule.provideModelRegistry` | `ModelManagerViewModel`, `ChatViewModel` | 1 (@Singleton) |
| `ErrorLog` | `CoreRuntimeModule.provideErrorLog` | `DefaultModelRegistry`, `DefaultDownloadRepository` (через injection) | 1 (@Singleton) |
| `LlmModelInstance` (native `Engine` + `Conversation`) | `LlmChatModelHelper.initialize` держит на `Model.instance` | `runInference`, `resetConversation`, `cleanUp` | 0 или 1 за раз (single-active-model через `lifecycleMutex`) |
| `WorkManager` | Android system | `DefaultDownloadRepository` | 1 (application singleton, предоставлен Android) |

Heavy native resource = `LlmModelInstance` (engine = 2–5 ГБ native памяти в зависимости от модели). Защищён `Mutex` в `DefaultModelRegistry` + stale-instance guard при cleanup (сравнение by reference перед вызовом `close()`).

## Decisions

Все D1–D12 уже зафиксированы в user-spec § «Технические решения» — не дублирую, привязка явная. Ниже — дополнительные tech-level решения, которые не попали в user-spec.

### Decision T1: Package layout — `app.sanctum.machina.*`
**Decision:** `:app` namespace + applicationId = `app.sanctum.machina`; `:core-runtime` namespace = `app.sanctum.machina.core`. Файлы внутри: `app.sanctum.machina.core.data.*`, `...core.runtime.*`, `...core.inference.*`, `...core.registry.*`, `...core.log.*`, `...core.di.*`, `...core.worker.*`.
**Rationale:** Фиксировано в `architecture.md § Project Structure` и в user-spec § Ограничения («Пакет: app.sanctum.machina»). Префикс `app.` вместо `com.` — по осознанному решению в `architecture.md` (нет домена sanctum.com).
**Alternatives considered:** `com.phonewrap.*` — фигурирует в code-research §9.5 как placeholder, отверг: противоречит зафиксированным источникам (architecture.md + user-spec Ограничения).
**Anchors:** user-spec Ограничения (п. про пакет), `architecture.md § Project Structure`.

### Decision T2: Библиотечные версии — берём из architecture.md (authoritative), не из Gallery
**Decision:** `compose-bom = 2026.03.00`, `hilt = 2.57.1`, `kotlin = 2.2.0`, `agp = 8.8.2`, `ksp = 2.3.6`, `litertlm = 0.10.0`, `work-runtime = 2.10.0`, `gson = 2.12.1`, `core-ktx = 1.15.0`, `lifecycle = 2.8.7`, `activity-compose = 1.10.1`, `navigation = 2.8.9`, `material-icon-extended = 1.7.8`, `hilt-navigation = 1.3.0`, `kotlinx-serialization-json = 1.7.3`.
**Rationale:** `architecture.md § Tech Stack` — source of truth для версий. Differences vs Gallery: compose-bom `2026.03.00` (не `2026.02.00`), hilt `2.57.1` (не `2.57.2`). Architecture.md зафиксирован раньше tech-spec и является контрактом проекта.
**Alternatives considered:** 1-в-1 версии Gallery — отверг: architecture.md уже утверждена пользователем; её значения имеют приоритет.
**Anchors:** user-spec Ограничения (`compose-bom:2026.03.00`), `architecture.md § Tech Stack`.

### Decision T3: Hilt через KSP вместо kapt
**Decision:** `ksp(libs.hilt.android.compiler)` вместо `kapt(...)`. Hilt 2.48+ поддерживает KSP.
**Rationale:** `architecture.md § Tech Stack`: «Hilt 2.57.1 via KSP ... KSP is ~2× faster than KAPT at build time». Gallery использует kapt — мы осознанно отходим.
**Alternatives considered:** kapt — отверг: architecture.md явно мандатит KSP.
**Anchors:** `architecture.md § Tech Stack` (Hilt bullet).

### Decision T4: `DownloadWorker` — plain `CoroutineWorker`, без `@HiltWorker`
**Decision:** Не добавлять `androidx.hilt:hilt-work`, не делать `@HiltWorker`. `DownloadWorker` остаётся с сигнатурой `class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(...)`. Все параметры передаются через `WorkManager Data bundle` (как в Gallery).
**Rationale:** В Gallery нет `@HiltWorker` (grep 0 matches, research §10.4). Добавление `hilt-work` для одного Worker — лишняя сложность без выгоды в Phase 1.
**Alternatives considered:** `@HiltWorker` + `HiltWorkerFactory` — отверг: не нужна DI внутри Worker (все зависимости — `Context` и `inputData`).
**Anchors:** `[TECHNICAL]` — чисто реализационный выбор, следует Gallery-pattern.

### Decision T5: Замена `AppLifecycleProvider` на `ProcessLifecycleOwner`
**Decision:** Файл `GalleryLifecycleProvider.kt` и интерфейс `AppLifecycleProvider` не копируем. В `DefaultDownloadRepository.sendNotification` строка `if (lifecycleProvider.isAppInForeground) return` заменяется на `if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return`. Dep: `androidx.lifecycle:lifecycle-process = 2.8.7`.
**Rationale:** 1 call-site всего (research §10.3), вывод `:app` из конструктора `:core-runtime` классов. `lifecycle-process` добавляется отдельной строкой в `libs.versions.toml` (без BOM — lifecycle-libraries разделены), версия совпадает с `lifecycle-runtime-ktx` = 2.8.7 по соглашению.
**Alternatives considered:** (a) Всегда показывать notification — минус: двойной cue пользователю в foreground. (b) `isForeground: () -> Boolean` injection — минус: добавляет параметр конструктора, который Phase 2 всё равно выбросит.
**Anchors:** `[TECHNICAL]` — реализация D2 (точный список копируемых файлов).

### Decision T6: FQN `MainActivity` через companion + Data bundle + package-prefix guard
**Decision:** В `:core-runtime/.../data/DefaultDownloadRepository.kt` добавить `companion object { @Volatile var mainActivityFqn: String? = null }`. В `:app/SanctumApplication.onCreate()` (первая же строка после `super.onCreate()`) вызвать `DefaultDownloadRepository.mainActivityFqn = "app.sanctum.machina.MainActivity"`. В `DefaultDownloadRepository.sendNotification` путь, где формируется Intent для notification'а, — читаем `mainActivityFqn ?: return` (если не установлено — не шлём notification). В WorkManager `Data bundle` кладём строку по ключу `KEY_MAIN_ACTIVITY_FQN`. В `:core-runtime/worker/DownloadWorker.kt:342` заменить `Class.forName("com.google.ai.edge.gallery.MainActivity")` на:
```kotlin
val fqn = inputData.getString(KEY_MAIN_ACTIVITY_FQN)
  ?: return@... // no-op: notification уже не создавалась
require(fqn.startsWith("app.sanctum.machina.")) { "Unexpected main activity FQN: $fqn" }
Class.forName(fqn)
```
**Rationale:** User-spec D3(c) требует «`BuildConfig`-константу или инъекцию класса». Companion object с установкой из `SanctumApplication.onCreate()` — минимально инвазивный вариант: ноль Hilt-модулей, ноль конструкторных параметров у `DefaultDownloadRepository`, ноль BuildConfig-полей. Package-prefix guard (`require(fqn.startsWith("app.sanctum.machina."))`) закрывает класс-loading anti-pattern (security-auditor low-finding): строка в Data bundle изолирована per-app хранилищем WorkManager, но защитный guard превращает теоретический gadget в жёсткий fail.
**Alternatives considered:**
- (a) `:core-runtime/BuildConfig` с hardcoded FQN — отверг: hardcode `:app`-имени в `:core-runtime` строже ломает модульную границу, плюс требует `buildConfig=true` в `:core-runtime`.
- (b) Hilt-модуль `AppMainActivityModule` с `@Provides Class<*>` + constructor injection — отверг (**ранее выбранный, заменён по решению validation-round 1**): 3-слойная DI-цепочка (Hilt-модуль → ctor param → Data bundle serialization) overengineered для одной строки, completeness-validator и security-auditor отметили это.
- (c) `BuildConfig` в `:app` + `ApplicationInfo.className` чтение — отверг: Android API `context.packageManager.getLaunchIntentForPackage` даёт это из коробки, но возвращает `Intent`, не `Class` — не подходит для хардкода в Gallery `Class.forName`.
**Anchors:** user-spec Risks R1, Decisions D3(c).

### Decision T7: Хранение allowlist — `assets/model_allowlist.json` (НЕ runtime fetch)
**Decision:** Файл `:core-runtime/src/main/assets/model_allowlist.json`. Содержит 2 модели (Gemma-4-E2B-it + Gemma-4-E4B-it), обрезанная копия `gallery-source/model_allowlists/1_0_11.json`. `AllowlistLoader` парсит через `context.assets.open("model_allowlist.json")` + Gson.
**Rationale:** Прямая реализация user-spec D4. Gallery fetch-механизм (из `ModelManagerViewModel`) не портируется.
**Alternatives considered:** (a) Зашить модели как Kotlin-литерал — отверг: теряется совместимость со схемой Gallery, юнит-тест парсера становится тавтологией. (b) Runtime fetch — отверг: user-spec D4 явно запрещает.
**Anchors:** user-spec Decisions D4, D8.

### Decision T8: Accelerator UI не показываем — захардкожен GPU + CPU fallback
**Decision:** `ModelManagerScreen` и `ChatScreen` не содержат UI выбора accelerator. `DefaultModelRegistry.initialize` делает первой попыткой accelerator из `Model.defaultConfig` (в allowlist для обеих моделей `accelerators: "gpu,cpu"` → первый токен = GPU). При ошибке — unconditional `cleanUp` + патч `configValues[ConfigKeys.ACCELERATOR.label] = Accelerator.CPU.label` + вторая попытка. Без парсинга текста ошибки (research §14).
**Rationale:** user-spec Risks R2 (Honor 200 не имеет LiteRT NPU-backend) + user-spec Ограничения («backend=GPU»). Unconditional fallback проще и надёжнее, чем парсинг native-error-string из `cleanUpMediapipeTaskErrorMessage`.
**Alternatives considered:** (a) Парсить error для признаков GPU-проблемы ("OpenCL", "GL", "delegate") — отверг: хрупко, версионно-зависимо. (b) UI с выбором accelerator — отверг: вне scope Phase 1.
**Anchors:** user-spec Risks R2, Ограничения (accelerator=GPU).

### Decision T9: `Mutex` в `DefaultModelRegistry` вместо флагов `model.initializing`
**Decision:** `DefaultModelRegistry` владеет `private val lifecycleMutex = Mutex()`. Все операции жизненного цикла (`initialize`, `cleanup`, `resetConversation`, `delete`) идут через `lifecycleMutex.withLock { ... }`. Stale-instance guard сохраняется: перед `cleanUp` сравниваем `currentInstance === model.instance`.
**Rationale:** user-spec Risks R3 мандатит «`ModelRegistry` обязан вызывать `stopResponse()` перед `cleanUp()`... реализация — решение tech-spec (Mutex/AtomicBoolean)». Gallery использует пару флагов `initializing` + `cleanUpAfterInit` (Model.kt:267, 269) — этот антипаттерн не портируется.
**Alternatives considered:** (a) `AtomicBoolean` — отверг: не умеет блокировать `reset` пока идёт `initialize`; нужна явная очередь. (b) Один `Channel` с последовательной обработкой команд — отверг: оверинжиниринг для Phase 1 (одна активная модель). (c) Флаги Gallery — отверг: user-spec D6 мандатит уход от anti-pattern `Model.instance: Any?` и связанной bookkeeping-логики.
**Anchors:** user-spec Risks R3, Decisions D6.

### Decision T10: Stop inference — через `LlmChatModelHelper.stopResponse(model)`
**Decision:** В `ChatScreen` при нажатии Stop — `ChatViewModel.stop()` → `LlmChatModelHelper.stopResponse(model)` (готовый публичный метод в копируемом файле, `ui/llmchat/LlmChatModelHelper.kt:238-241`, вызывает `instance.conversation.cancelProcess()`). Gallery `LlmChatViewModel.stopResponse` (`ui/llmchat/LlmChatViewModel.kt:238`) использует этот же helper.
**Rationale:** user-spec Основной сценарий п. 11 и AC-11 требуют остановку за ~500 мс. Gallery использует нативный `Conversation.cancelProcess()` через `LlmChatModelHelper.stopResponse` — берём as-is (research §4 mode 2; skeptic confirmed method exists).
**Alternatives considered:** Отменять coroutine scope целиком — отверг: оставляет native conversation в подвисшем состоянии, риск native crash (research §4 mode 5).
**Anchors:** user-spec AC-11, Risks R3.

### Decision T11: TTFT/total без tok/s — формат футера `"TTFT {N}ms · {X.X}s total"`
**Decision:** Формат ровно как указано, обе метрики через `System.currentTimeMillis()`. Никакого tok/s, никакого tokenizer'а.
**Rationale:** user-spec D9 явно отвергает tok/s.
**Alternatives considered:** `chars/4 tok/s` — отверг: user-spec D9 «если цифра хоть сколько-то неточная — не показываем».
**Anchors:** user-spec AC-8, Decisions D9.

## Data Models

Phase 1 не использует БД (user-spec D5). В памяти:

**`ModelEntry`** (in `:core-runtime/registry`):
```kotlin
data class ModelEntry(
  val model: Model,                      // исходный Model из allowlist
  val downloadStatus: ModelDownloadStatus, // NOT_DOWNLOADED / IN_PROGRESS(bytes) / SUCCEEDED / FAILED(msg)
  val initStatus: ModelInitStatus,        // IDLE / INITIALIZING / READY / FAILED(msg)
)
```

**`ModelDownloadStatus`** — исходный тип из Gallery: enum `ModelDownloadStatusType` (`Model.kt:353-360`) + `data class ModelDownloadStatus(status, totalBytes, receivedBytes, errorMessage, bytesPerSecond, remainingMs)` (`Model.kt:362-369`). Из 6 состояний enum'а используем 4 в UI Phase 1: NOT_DOWNLOADED, IN_PROGRESS, SUCCEEDED, FAILED. UNZIPPING и PARTIALLY_DOWNLOADED не показываем отдельно (Phase 1 bundled-JSON, нет zip; resume уходит в IN_PROGRESS сразу через Range header).

**`ModelInitStatus`** — новый sealed class в `:core-runtime/registry`:
```kotlin
sealed class ModelInitStatus {
  object Idle : ModelInitStatus()
  object Initializing : ModelInitStatus()
  object Ready : ModelInitStatus()
  data class Failed(val message: String) : ModelInitStatus()
}
```

**`Message`** (in `:app/ui/chat`):
```kotlin
data class Message(
  val id: Long,           // простой counter
  val role: Role,          // USER / ASSISTANT
  val text: String,        // user text / assistant full text
  val streaming: Boolean,  // true пока стрим идёт
  val interrupted: Boolean = false,  // true после Stop
  val footer: String? = null,  // "TTFT 340ms · 4.2s total"
)
enum class Role { USER, ASSISTANT }
```

**Allowlist JSON schema** — совместимо с Gallery `AllowedModel` (research §5). Для Phase 1 используются поля: `name`, `modelId`, `modelFile`, `commitHash`, `description`, `sizeInBytes`, `defaultConfig {topK, topP, temperature, accelerators, maxContextLength, maxTokens}`, `taskTypes`. Остальные поля (`llmSupportImage`, `llmSupportAudio`, `bestForTaskTypes`, `socToModelFiles`, и т.д.) игнорируются парсером.

## Dependencies

### New packages (впервые появляются в проекте)

- `com.google.ai.edge.litertlm:litertlm-android:0.10.0` — inference engine (ядро).
- `androidx.work:work-runtime-ktx:2.10.0` — WorkManager для foreground download.
- `com.google.code.gson:gson:2.12.1` — парсер allowlist JSON (Gallery использует Gson через `@SerializedName`).
- `com.google.dagger:hilt-android:2.57.1` + `hilt-android-compiler:2.57.1` (через KSP).
- `androidx.hilt:hilt-navigation-compose:1.3.0` — `hiltViewModel()` в Compose navigation.
- `androidx.lifecycle:lifecycle-process:2.8.7` — `ProcessLifecycleOwner` (замена `AppLifecycleProvider`).
- `androidx.core:core-ktx:1.15.0`, `androidx.lifecycle:lifecycle-runtime-ktx:2.8.7`, `androidx.activity:activity-compose:1.10.1`.
- `androidx.compose:compose-bom:2026.03.00` (platform) + `ui`, `ui-graphics`, `ui-tooling-preview`, `material3`, `material-icons-extended:1.7.8`, `navigation-compose:2.8.9`.
- `org.jetbrains.kotlin:kotlin-reflect:2.2.21` — присутствует в Gallery `libs.versions.toml` и тянется предположительно как транзитивная зависимость Hilt/KSP tooling; пиннинг для совместимости с Kotlin 2.2.0. На этапе Task 2 первая успешная сборка подтвердит необходимость; если ни один `implementation` / `runtimeOnly` путь её не требует — убрать в последующей итерации.
- **Testing** (`:core-runtime/src/test/`): `junit:junit:4.13.2`, `com.google.code.gson:gson:2.12.1` (уже в impl — используется в тесте тоже). MockK и JUnit 5 **не добавляются** (user-spec D8: plain JUnit4, инфра Phase 2).

**Gradle plugins:**
- `com.android.application:8.8.2`
- `com.android.library:8.8.2`
- `org.jetbrains.kotlin.android:2.2.0`
- `org.jetbrains.kotlin.plugin.compose:2.2.0` (встроенный Compose Compiler через Kotlin 2.2)
- `com.google.dagger.hilt.android:2.57.1`
- `com.google.devtools.ksp:2.3.6` (выравнивание с Decision T2; совместим с Kotlin 2.2.0 — Gallery использует эту же версию)

### Explicitly NOT included (enforced)

По `architecture.md § Key Dependencies — Explicitly NOT included`: **никаких** `google-services`, `firebase-*`, `mlkit-genai-prompt`, `play-services-oss-licenses`. Дополнительно Phase 1 исключает: `net.openid:appauth`, `androidx.datastore`, `protobuf-javalite`, `androidx.camera:*`, `com.halilibo:compose-richtext*`, `androidx.security.crypto`, `androidx.room:*` (→ Phase 3), `com.squareup.moshi:*`, `androidx.test:*` + `room-testing` (→ Phase 3), `io.mockk:mockk` (→ Phase 2). QA верифицирует grep'ом.

### Using existing (from project)

Проект новый — переиспользовать нечего. Копируемые из `gallery-source/` Kotlin-файлы — см. Architecture § «What we're building/modifying».

## Testing Strategy

**Feature size:** L — но user-spec D8 явно делает исключение из стандартной практики для Phase 1. Основание: спайк + throwaway-архитектура. См. user-spec § «Тестирование».

### Unit tests (`:core-runtime/src/test/`)

- **`AllowlistLoaderTest`** (единственный unit-тест Phase 1) — plain JUnit4 + Gson:
  - Scenario 1: `loadFromAsset(assetFixture)` возвращает `Result.success(list)` где `list.size == 2`.
  - Scenario 2: Для каждого Model из распарсенного списка непустые `name`, `modelId`, `modelFile`, `commitHash`, `sizeInBytes > 0`, `defaultConfig != null`, `defaultConfig.topK != null`, `defaultConfig.temperature != null`.
  - Scenario 3 (опциональный): битый JSON (обрезанный) → `Result.failure`, сообщение содержит парсер-исключение.
  - Fixture: `:core-runtime/src/test/resources/model_allowlist_fixture.json` = копия финального `assets/model_allowlist.json`. Читается через `javaClass.getResourceAsStream("/model_allowlist_fixture.json")`.

### Integration tests

None — Room отсутствует (нечего тестировать на уровне DAO). `androidx.test` + `room-testing` инфраструктура — Phase 3.

### E2E tests

None — Compose UI-тесты вне scope проекта в принципе (`patterns.md § Test Infrastructure`). E2E с реальным LiteRT-LM на CI не выполнимо (нет модели в CI).

### Manual on-device verification

Главный канал проверки Phase 1 — ручные шаги 1–15 + back-to-back сравнение с Gallery из user-spec § «Как проверить / Пользователь проверяет» + § «Главный критерий Phase 1». Выполняется пользователем на Honor 200 после установки debug-APK.

### Agent-verifiable automated checks

Выполняются в `pre-deploy-qa` задаче Final Wave:

- `./gradlew build` — BUILD SUCCESSFUL, ноль ERROR в lint.
- `./gradlew :core-runtime:test` — проходит `AllowlistLoaderTest`.
- Greps из user-spec § «Агент проверяет» (проверка модульной границы, отсутствие Firebase/AICore/AppAuth, формат лога — `Log.e` only в `app/src/main/`, `core-runtime/src/main/`).
- `jq '.models | length' core-runtime/src/main/assets/model_allowlist.json` == 2.

## Agent Verification Plan

**Source:** user-spec § «Как проверить».

### Verification approach

Проверка Phase 1 делится на три уровня:

1. **Агент-автоматически (в `pre-deploy-qa` Final Wave):** gradle build, unit test, grep'ы модульной границы, grep запретных зависимостей, проверка allowlist JSON. См. user-spec § «Как проверить / Агент проверяет» — таблица с 11 пунктами + TAC-12…TAC-16 из tech-spec.
2. **Per-task smoke checks (Verify-smoke в каждой задаче):** локальные проверки агентом после каждой имплементации — `./gradlew :module:compileDebugKotlin`, grep'ы специфичных для задачи паттернов, `jq` на ассеты. Детали — в полях Verify-smoke каждой задачи.
3. **Пользователь-вручную на Honor 200:** 15 шагов + back-to-back с Gallery. Полный чеклист — user-spec § «Пользователь проверяет». Итоговый гейт Phase 1 — AC-14 (TTFT ≤ 1.5× Gallery).

**AC-14 protocol (уточнение из User-Spec Deviations):**

1. На Honor 200 в Gallery загрузить Gemma-4-E2B-it, ввести выбранный промпт средней длины (ответ ~200-500 токенов). 3 раза подряд отправить промпт, секундомером (телефон/часы) замерить время от тапа Send до появления первого символа ответа на экране. Между прогонами — Reset conversation в Gallery (чтобы каждый прогон был cold-prefill, а не cache hit). Три значения: `G1, G2, G3`. Gallery baseline = `median(G1, G2, G3)`.
2. На том же Honor 200 в Phase 1 APK — тот же промпт 3 раза (Reset между прогонами). В футере каждого ответа читаем TTFT. Три значения: `S1, S2, S3`. Phase 1 TTFT = `median(S1, S2, S3)`.
3. Записать все 6 замеров + медианы в QA-отчёт (Task 14). Gate: `median(S) / median(G) ≤ 1.5`. Если > 1.5 — Phase 1 провалена, стоп + анализ.
4. Субъективный чек («примерно одинаково») остаётся как дополнительный — если ощущение «Gallery заметно быстрее» при прошедшем numeric gate → user разбирает вручную перед закрытием фазы.

Post-deploy verification (MCP, live environment) не применима: Phase 1 деплоится ручной установкой APK на Honor 200, MCP-инструментов для взаимодействия с Android-устройством в проекте нет. Live-проверку делает пользователь.

### Tools required

- **Gradle 8.x** (через wrapper `./gradlew`) — сборка + unit-тест.
- **bash / git-bash** — для grep'ов из user-spec «Агент проверяет» (Windows окружение: git-bash или WSL; альтернатива — ripgrep через Grep tool).
- **jq** (или эквивалент: `python -c "import json; ..."`) — для проверки `core-runtime/src/main/assets/model_allowlist.json`.
- **adb** (опционально) — только для пользователя, не для агента (передача APK на устройство).
- **Telegram MCP / Playwright MCP** — **не используются** (нет UI для автоматизации, нет bot-интеграции).

## Risks

Все основные риски зафиксированы в user-spec R1–R10. Ниже — дополнительные tech-level риски, не покрытые user-spec.

| Risk | Mitigation |
|------|-----------|
| **T-R1.** KSP 2.2.0-compatible Hilt-compiler версия может не существовать на мейвене на момент реализации — Hilt 2.57.1 + KSP 2.3.6 + Kotlin 2.2.0 сочетание нужно подтвердить. | В Task 2 (Gradle setup) первый `./gradlew build` верифицирует совместимость. Fallback — подбираем ближайшую совместимую KSP или обновляем Hilt до 2.57.2. Любое изменение версий → коммит в `libs.versions.toml`, упоминается в decisions.md при имплементации. |
| **T-R2.** `kotlin-reflect` присутствует в Gallery dependencies, но фактически Gallery-код не использует kotlin.reflect API (verified skeptic: grep `kotlin.reflect` в `gallery-source/Android/src` → 0 совпадений). Пиннинг оставлен для соответствия Gallery и потенциальных транзитивных Hilt/KSP требований. | Task 2 — первая успешная сборка; если ни одна зависимость её не требует — удалить в Phase 2 как cleanup. Не блокер Phase 1. |
| **T-R3.** `Model.getPath(context)` использует `getExternalFilesDir(null)` — на Android 11+ scoped storage может менять семантику для package-private app data. Путь из user-spec `/sdcard/Android/data/app.sanctum.machina/files/...` должен совпасть. | В Task 6 после `scanLocalFiles()` логируем через `ErrorLog` фактический путь первого ModelEntry (INFO-level невозможен — только ERROR). Верификация пути — пункт 4 user-spec § «Пользователь проверяет». |
| **T-R4.** Несовместимость Compose BOM 2026.03.00 и material-icons-extended 1.7.8 — BOM может hoist'ить material3 иконки. | В Task 8 (ModelManagerScreen) первый `./gradlew :app:compileDebugKotlin` выловит version conflict. Fallback — убрать явный `material-icons-extended`, использовать только Material3 icons через BOM (минус — меньше иконок, для Phase 1 хватает). |
| **T-R5.** Navigation Compose + type-safe routes требовали бы `kotlinx-serialization-json` + плагин `kotlin-serialization`. Phase 1 намеренно упрощает до string-route `chat/{modelName}` без serializable args — плагин и dep исключены из Dependencies. | Один параметр modelName, без спецсимволов — URL-encoding не нужен. Trade-off зафиксирован в User-Spec Deviations как extension/clarification. |

## User-Spec Deviations

- **D6 отложен в Phase 2.** → `[PENDING USER APPROVAL]` → **APPROVED by user 2026-04-15**.
  **User-spec говорит:** D6 — «Рефакторим анти-паттерн `Model.instance: Any?` в `ModelDefinition` (immutable) + `ModelRuntimeHandle` (mutable) — это единственный non-copy-paste рефакторинг в Phase 1. Причина: протащить анти-паттерн дальше Phase 1 заблокирует архитектуру Phase 2 (`patterns.md`).»
  **Tech-spec делает:** Оставляет Gallery-класс `Model` с `Model.instance: Any?` нетронутым. Вводит `ModelEntry` (Phase 1 view-тип над Gallery `Model`) + `Mutex` + stale-instance guard (`currentInstance === model.instance`) для защиты от race-условий жизненного цикла. Изменения Gallery-кода ограничены патчами из D3 (HF-токен, Firebase, MainActivity).
  **Почему:** Реализация D6 as-written требует переписать API `DownloadRepository` и `DownloadWorker` — они принимают Gallery `Model` по всему контракту, `Model.instance` мутируется в `LlmChatModelHelper.initialize/cleanUp/resetConversation`. Ввод `ModelRuntimeHandle` как отдельного типа означает либо полностью обернуть `LlmChatModelHelper` новым фасадом (и потерять возможность as-is копирования файла — user-spec D2), либо модифицировать Gallery-код за пределами патчей D3. Оба варианта противоречат спайк-логике Phase 1 и заставляют перекраивать копируемое ядро больше, чем user-spec D2 заявляет («тащим... только ядро», «точный список — code-research § 2»). User-spec D8, R8 явно допускают переделку архитектуры `:core-runtime` в Phase 2. D6 переносится туда вместе с основной архитектурной работой.
  **Mitigation в Phase 1:** stale-instance guard + Mutex-сериализация lifecycle-операций закрывают класс ошибок, ради которого D6 был написан (race между initialize и cleanup, native SIGSEGV — user-spec R3). Анти-паттерн не «протаскивается» в новый код — он остаётся только внутри копируемого ядра Gallery, над которым надстроен `DefaultModelRegistry`.
- **AC-14 — уточнение протокола измерения Gallery baseline (не расхождение, уточнение).** User-spec: «секундомер, одного прогона достаточно». Tech-spec ужесточает до: **3 прогона одного и того же промпта в Gallery → медиана TTFT = baseline**. То же самое для Phase 1 APK (3 прогона → медиана). Сравнение 1.5× применяется к медианам, а не к единичным замерам. Результат: 6 замеров вручную вместо 2. → `[PENDING USER APPROVAL]` → **APPROVED by user 2026-04-15** (мотивация: закрыть 15-40% шум человеческой реакции, выявленный completeness-validator, без похода в полноценную автоматизацию измерения). Протокол фиксируется в Agent Verification Plan и TAC-ниже.
- **Extension (T-R5, не в user-spec):** Не добавляем `kotlinx-serialization-json` и плагин `kotlin-serialization`. User-spec Ограничения перечисляет пакеты для `:app` включая navigation-compose, но про serialization-плагин молчит. Tech-spec намеренно упрощает навигацию до string-route `chat/{modelName}` без type-safe args. → **Not a deviation — clarification**, user-spec не закрывал вопрос. Отмечаю для прозрачности.
- **Extension:** Tech-spec добавляет `ErrorLog`-компонент в `:core-runtime` (не только концепцию из user-spec D11/AC-16). User-spec D11 описывает что и куда писать, tech-spec фиксирует конкретный класс `ErrorLog` с Hilt-singleton, ротацией и 4 компонентами. → **Not a deviation — refinement** того, что user-spec оставил на tech-spec («остальные компоненты — в последующих фазах»).
- **Technical-only: T1–T11** — зафиксированы выше, каждое решение привязано либо к user-spec decision (D1–D12), либо к architecture.md / user-spec Ограничения, либо помечено `[TECHNICAL]` (T4 — выбор plain CoroutineWorker).

Всё остальное в tech-spec — прямая реализация user-spec.

## Acceptance Criteria

Дополняют пользовательские AC-1 … AC-16 из user-spec. Технические приёмочные критерии:

- [ ] **TAC-1.** `./gradlew build` → `BUILD SUCCESSFUL`, ноль ERROR в `lint-results-*.html`.
- [ ] **TAC-2.** `./gradlew :core-runtime:test` → `BUILD SUCCESSFUL`, ровно 1 тест пройден (`AllowlistLoaderTest`).
- [ ] **TAC-3.** Модуль `:core-runtime` не импортирует Compose / Activity / ViewModel (grep'ы из user-spec § «Агент проверяет» — 0 совпадений для `androidx.compose`, `androidx.activity`, `ViewModel` в `core-runtime/src/main/`).
- [ ] **TAC-4.** В проекте **нет** `firebase`, `mlkit-genai`, `aicore`, `AppAuth` (grep 0 совпадений в `app/src/`, `core-runtime/src/`, `build.gradle.kts`, `gradle/libs.versions.toml`).
- [ ] **TAC-5.** В production коде (`app/src/main/`, `core-runtime/src/main/`) используется только `Log.e` — grep `Log\.(i|w|d)\(` возвращает 0 совпадений.
- [ ] **TAC-6.** `core-runtime/src/main/assets/model_allowlist.json` валидный JSON, `.models | length == 2`, каждый элемент имеет непустые `name`, `modelId`, `modelFile`, `commitHash`, `sizeInBytes`.
- [ ] **TAC-7.** `AndroidManifest.xml` содержит все permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, `WAKE_LOCK` (grep ≥ 6 совпадений).
- [ ] **TAC-8.** `AndroidManifest.xml` содержит `<service android:name="androidx.work.impl.foreground.SystemForegroundService" ...>` с `foregroundServiceType="dataSync"`.
- [ ] **TAC-9.** `ErrorLog` записывает ошибки в `context.filesDir/logs/errors.log` в формате `ERROR [component] description :: cause` (по одной строке на событие, без переносов). Верифицируется кодом `ErrorLog` + его вызовы используют корректный формат (grep `ErrorLog.e\(` → все вызовы передают component из white-list {`download`, `inference-init`, `inference`, `inference-cleanup`}).
- [ ] **TAC-10.** `BuildConfig.MAIN_ACTIVITY_CLASS_NAME == "app.sanctum.machina.MainActivity"` — grep в `app/build.gradle.kts` подтверждает `buildConfigField`.
- [ ] **TAC-11.** Пакет приложения (`applicationId`) + `namespace` в `:app` = `app.sanctum.machina`; `namespace` в `:core-runtime` = `app.sanctum.machina.core`.
- [ ] **TAC-12.** `AndroidManifest.xml <application>` содержит атрибуты `android:allowBackup="false"`, `android:fullBackupContent="false"`, `android:usesCleartextTraffic="false"`. Grep по каждому — 1+ совпадение.
- [ ] **TAC-13.** Нет residual HF-token кода в копируемых Gallery-файлах. `grep -rEi "(hfToken|HF_TOKEN|Authorization|bearerToken|oauth|appauth|accessToken|refreshToken)" core-runtime/src/main/ app/src/main/` → 0 совпадений. (Комплементарно к TAC-4, ловит пропущенные при патче D3 ссылки.)
- [ ] **TAC-14.** FQN `MainActivity` плумбинг в двух точках: `grep "app.sanctum.machina.MainActivity" app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt` ≥ 1 совпадение (там где установка companion'а); `grep "startsWith(\"app.sanctum.machina.\")" core-runtime/src/main/kotlin/app/sanctum/machina/core/worker/DownloadWorker.kt` = 1 совпадение (package-prefix guard).
- [ ] **TAC-15.** `AllowlistLoader` валидирует каждую запись: грубый схема-guard — `modelId` начинается с `litert-community/`, итоговый download URL — с `https://huggingface.co/`, `sizeInBytes in 1..10_737_418_240L` (до 10 ГБ). Любой fail → `Result.failure`, весь load ломается (not per-entry skip). Проверяется кодом + `AllowlistLoaderTest` расширенным сценарием.
- [ ] **TAC-16.** Gallery baseline протокол: в QA-отчёте (Task 14) зафиксированы 3 TTFT-замера Gallery на одном промпте + медиана; аналогично 3 замера Phase 1 APK + медиана; отношение `median(Phase 1) / median(Gallery) ≤ 1.5`.

## Implementation Tasks

### Wave 0 (инфраструктура — последовательно, до всего остального)

#### Task 1: Git init + .gitignore
- **Description:** Инициализировать git-репозиторий в корне `C:\AI-WORK\PhoneWrap\`, создать `.gitignore` по списку из `patterns.md § .gitignore essentials` (включая `gallery-source/`, `.idea/`, `build/`, `*.keystore`, `.env`). Сделать первый коммит с tag `master` (immutable zero-state marker по `patterns.md § Git Workflow`). Создать ветку `phase/1-foundation` от `master`.
- **Skill:** infrastructure-setup
- **Reviewers:** code-reviewer, security-auditor, infrastructure-reviewer
- **Verify-smoke:** `git log --oneline` → 1 commit; `git tag` содержит `master`; `git branch` содержит `phase/1-foundation`; `git status` → clean.
- **Files to modify:** `.gitignore`, `.gitattributes` (CRLF для Windows)
- **Files to read:** `.claude/skills/project-knowledge/references/patterns.md` (секции Git Workflow, .gitignore)

#### Task 2: Gradle infrastructure — root + libs catalog + двумодульная структура
- **Description:** Создать `settings.gradle.kts` с `rootProject.name = "PhoneWrap"` и `include(":app", ":core-runtime")`. Создать `build.gradle.kts` (root) с плагинами `apply false`. Создать `gradle/libs.versions.toml` с пиннингованным каталогом версий по Dependencies секции. Создать пустые `:app/build.gradle.kts` и `:core-runtime/build.gradle.kts` с минимальной конфигурацией (namespace, compileSdk=35, minSdk=31, jvmTarget=11, plugins). В `:app/build.gradle.kts` объявить `buildConfigField("String", "MAIN_ACTIVITY_CLASS_NAME", "\"app.sanctum.machina.MainActivity\"")`. Gradle wrapper 8.x. Первая успешная сборка пустых модулей.
- **Skill:** infrastructure-setup
- **Reviewers:** code-reviewer, security-auditor, infrastructure-reviewer
- **Verify-smoke:** `./gradlew help` → `BUILD SUCCESSFUL`; `./gradlew projects` → список `:app`, `:core-runtime`; `./gradlew :app:tasks` содержит `assembleDebug`.
- **Files to modify:** `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `core-runtime/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`
- **Files to read:** `gallery-source/Android/src/settings.gradle.kts`, `gallery-source/Android/src/build.gradle.kts`, `gallery-source/Android/src/app/build.gradle.kts`, `gallery-source/Android/src/gradle/libs.versions.toml`, `.claude/skills/project-knowledge/references/architecture.md`, `work/phase-1-foundation/code-research.md § 9`

### Wave 1 (зависит от Task 2, задачи 3/4/5 параллельны — работают в разных директориях)

#### Task 3: Перенос + патч Gallery-ядра в `:core-runtime`
- **Description:** Извлечь из `gallery-source/` runtime/download-ядро в `:core-runtime` и применить все патчи, описанные в Decisions D3 (HF-token + Firebase) + T5 (ProcessLifecycleOwner вместо AppLifecycleProvider) + T6 (FQN MainActivity через companion + Data bundle + package-prefix guard). Точный список файлов и построчные координаты патчей — в `code-research.md § 1, 2, 11`. Смена package root на `app.sanctum.machina.core.*`. После патча `:core-runtime` компилируется независимо, без ссылок на `:app` или Gallery-пакеты.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:**
  - `./gradlew :core-runtime:compileDebugKotlin` → `BUILD SUCCESSFUL`
  - `grep -rEi "(firebase|accessToken|ModelDownloadAccessToken|com.google.ai.edge.gallery|hfToken|HF_TOKEN|Authorization|bearerToken|oauth|appauth|refreshToken)" core-runtime/src/` → 0 (TAC-4 + TAC-13)
  - `grep -rE "androidx.compose|androidx.activity" core-runtime/src/` → 0 (модульная граница, TAC-3)
  - `grep 'startsWith("app.sanctum.machina.")' core-runtime/src/main/kotlin/app/sanctum/machina/core/worker/DownloadWorker.kt` = 1 (T6 guard)
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/{Model,Config,ConfigValue,Consts,Types,ModelAllowlist,DownloadRepository,TaskIds}.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/worker/DownloadWorker.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/runtime/LlmModelHelper.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/inference/LlmChatModelHelper.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/Utils.kt`
- **Files to read:** `gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/data/*.kt`, `gallery-source/.../worker/DownloadWorker.kt`, `gallery-source/.../runtime/LlmModelHelper.kt`, `gallery-source/.../ui/llmchat/LlmChatModelHelper.kt`, `work/phase-1-foundation/code-research.md § 1, 2, 11`, Decisions D3, T5, T6

#### Task 4: `AllowlistLoader` + bundled JSON + единственный Phase 1 unit-тест
- **Description:** Создать `core-runtime/src/main/assets/model_allowlist.json` (форк `1_0_11.json` обрезанный до 2 моделей: Gemma-4-E2B-it + Gemma-4-E4B-it — Decision T7) + Hilt-injectable `AllowlistLoader` со schema guard по TAC-15 + единственный Phase 1 unit-тест на парсер (D8 gate, обоснование в user-spec § Тестирование). Ассершены теста включают проверку `defaultConfig.accelerators` с `"gpu"` первым токеном (защита Decision T8).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `jq '.models | length' core-runtime/src/main/assets/model_allowlist.json` → `2`; `./gradlew :core-runtime:test` → `BUILD SUCCESSFUL` + 1 passed test.
- **Files to modify:** `core-runtime/src/main/assets/model_allowlist.json`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/AllowlistLoader.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/AllowlistLoaderTest.kt`, `core-runtime/src/test/resources/model_allowlist_fixture.json`
- **Files to read:** `gallery-source/model_allowlists/1_0_11.json`, `work/phase-1-foundation/code-research.md § 5`, user-spec D8, Decisions T7, T8, TAC-15

#### Task 5: `ErrorLog` — on-device ERROR-only writer
- **Description:** Реализовать Hilt-singleton `ErrorLog` пишущий в `context.filesDir/logs/errors.log` согласно формату `patterns.md § Error logging conventions` + ротация 2 МБ + sanitization (strip newlines, truncate до 500 chars). Whitelist компонентов Phase 1: `download`, `inference-init`, `inference`, `inference-cleanup` (по user-spec D11).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :core-runtime:compileDebugKotlin` → `BUILD SUCCESSFUL`; `grep -E "2 \* 1024 \* 1024|MAX_LOG_BYTES" core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt` ≥ 1.
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/di/CoreRuntimeModule.kt`
- **Files to read:** `.claude/skills/project-knowledge/references/patterns.md § Error logging conventions`, user-spec D11, AC-16

### Wave 2 (зависит от Wave 1 — 3, 4, 5)

#### Task 6: `DefaultModelRegistry` — lifecycle координатор
- **Description:** Реализовать `ModelRegistry` (interface) + `DefaultModelRegistry` (Hilt `@Singleton`) с операциями list/download/init/cleanup/resetConversation/getInstance. Жизненный цикл движка защищён `Mutex` + stale-instance guard (Decision T9), init с GPU→CPU fallback (Decision T8). На старте — scan local files + resumePartialDownloads. Все ошибки идут в `ErrorLog` с соответствующим component.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:**
  - `./gradlew :core-runtime:compileDebugKotlin` → `BUILD SUCCESSFUL`
  - `grep "lifecycleMutex.withLock" core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt` ≥ 4 (initialize, cleanup, resetConversation, delete)
  - `grep "currentInstance === model.instance" core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt` ≥ 1 (stale-instance guard)
  - `grep "KEY_MAIN_ACTIVITY_FQN" core-runtime/src/main/kotlin/app/sanctum/machina/core/data/DownloadRepository.kt` ≥ 1 (FQN плумбинг по T6)
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/{ModelRegistry,DefaultModelRegistry,ModelEntry,ModelInitStatus}.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/di/CoreRuntimeModule.kt`
- **Files to read:** `gallery-source/.../ui/modelmanager/ModelManagerViewModel.kt`, `work/phase-1-foundation/code-research.md § 10, 12, 13, 14`, Decisions T5, T6, T8, T9

### Wave 3 (зависит от Wave 2; задачи 7/8/9 параллельны — разные файлы)

#### Task 7: `:app` bootstrap — `SanctumApplication`, манифест, MainActivity-stub, strings.xml (весь Phase 1)
- **Description:** Поднять `:app` модуль: `@HiltAndroidApp SanctumApplication` (устанавливает FQN для T6 в `onCreate`), `MainActivity` (`@AndroidEntryPoint`, request POST_NOTIFICATIONS на SDK 33+, `setContent` со stub-заглушкой до Wave 4), голая Material 3 тема, `AndroidManifest.xml` с permissions TAC-7 + hardening-атрибутами TAC-12 + WorkManager SystemForegroundService. **`strings.xml` создаётся в этой задаче как единственном месте**: содержит все ключи, которые понадобятся Task 8 и Task 9 (имена экранов, кнопки, статусы моделей, empty states, error messages, чат-плейсхолдеры). Тон по `ux-guidelines.md`. Задачи 8 и 9 только читают strings.xml, не модифицируют.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:**
  - `./gradlew :app:compileDebugKotlin` → `BUILD SUCCESSFUL`
  - `grep -E "(INTERNET|FOREGROUND_SERVICE|POST_NOTIFICATIONS|WAKE_LOCK)" app/src/main/AndroidManifest.xml` ≥ 6 (TAC-7)
  - `grep -E 'android:allowBackup="false"|android:usesCleartextTraffic="false"|android:fullBackupContent="false"' app/src/main/AndroidManifest.xml` ≥ 3 (TAC-12)
  - `grep "app.sanctum.machina.MainActivity" app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt` ≥ 1 (TAC-14)
- **Files to modify:** `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt`, `app/src/main/kotlin/app/sanctum/machina/MainActivity.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/theme/Theme.kt`, `app/src/main/res/values/strings.xml`
- **Files to read:** `gallery-source/Android/src/app/src/main/AndroidManifest.xml`, `work/phase-1-foundation/code-research.md § 6`, `.claude/skills/project-knowledge/references/ux-guidelines.md`, Decisions T6, TAC-7, TAC-12, TAC-14

#### Task 8: `ModelManagerScreen` + `ModelManagerViewModel`
- **Description:** Экран списка моделей поверх `registry.models`. Карточка: имя + размер + статус (Не скачано / прогресс / Скачано / Ошибка). Действия: Скачать / Отмена / Загрузить (navigation event до Task 10). Строки берутся из готового `strings.xml` (создан в Task 7) — задача не модифицирует strings.xml.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** на Honor 200: список 2 моделей, Download начинает прогресс, Cancel возвращает в NOT_DOWNLOADED. См. user-spec § Пользователь проверяет шаги 3, 4, 6.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/{ModelManagerScreen,ModelManagerViewModel}.kt`
- **Files to read:** `core-runtime/.../registry/ModelRegistry.kt`, `app/src/main/res/values/strings.xml` (из Task 7), `.claude/skills/project-knowledge/references/ux-guidelines.md`

#### Task 9: `ChatScreen` + `ChatViewModel`
- **Description:** Чат-экран поверх `registry` для активной модели. Поведение: загрузка engine → streaming диалог → Stop/Reset/Back (все механики в Decisions T10, T11). Покрывает init-failure UI для AC-15 — текст ошибки из `cleanUpMediapipeTaskErrorMessage` + кнопка «Назад». Строки — из готового `strings.xml` (Task 7).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** на Honor 200 после download E2B: Load → ChatScreen ≤30с, send «Hello» → stream + TTFT footer, multi-turn, Stop ≤500мс, Reset без повторного Load, corrupt-file (`adb push` мусор) → читабельное сообщение + «Назад». См. user-spec § Пользователь проверяет шаги 7-11, 15.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/{ChatScreen,ChatViewModel,Message}.kt`
- **Files to read:** `core-runtime/.../inference/LlmChatModelHelper.kt`, `gallery-source/.../ui/llmchat/LlmChatViewModel.kt` (only as pattern reference — не копируется), `work/phase-1-foundation/code-research.md § 4, 7, 13`, `app/src/main/res/values/strings.xml` (Task 7), Decisions T10, T11

### Wave 4 (зависит от Wave 3 — 7, 8, 9)

#### Task 10: Navigation + финальная wiring MainActivity
- **Description:** Создать `SanctumApp.kt` с `NavHost` (routes `model_manager` / `chat/{modelName}`) и обновить `MainActivity.setContent` на `{ SanctumTheme { SanctumApp() } }` (Task 7 оставил stub). NavHost связывает готовые экраны из Task 8 и Task 9.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-user:** полный сценарий user-spec § Основной сценарий пп. 1-10.
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`, `app/src/main/kotlin/app/sanctum/machina/MainActivity.kt`
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt` (Task 8), `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatScreen.kt` (Task 9)

### Audit Wave

#### Task 11: Code Audit
- **Description:** Full-feature code quality audit. Прочитать все source-файлы созданные/модифицированные в Phase 1 (`:app/src/main/` + `:core-runtime/src/main/` + `:core-runtime/src/test/` + все `build.gradle.kts`). Проверить холистически: соответствие module boundary (`:core-runtime` без Compose/Activity), единый паттерн error-handling через `ErrorLog`, отсутствие анти-паттерна `Model.instance: Any?` в нашем новом коде, корректность shared-resources (LlmModelInstance под Mutex, single-active-model), соответствие D1–D12 из user-spec и T1–T11 из tech-spec. Записать audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 12: Security Audit
- **Description:** Full-feature security audit. Прочитать все source-файлы Phase 1. Анализ OWASP Top 10 применительно к mobile/Android: (a) нет secrets в коде (HF-токен не используем), (b) runtime permissions запрашиваются корректно (POST_NOTIFICATIONS), (c) нет SQL-инъекций (нет SQL), (d) нет insecure HTTP (все download'ы с HuggingFace — HTTPS через существующий Gallery-код), (e) нет log-injection (ErrorLog экранирует переводы строк), (f) WorkManager Data bundle не содержит секретов (только modelName/url/fileName). Отдельно проверить, что все извлечённые Gallery-файлы действительно лишены HF-token-plumbing. Записать audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 13: Test Audit
- **Description:** Full-feature test quality audit. Прочитать единственный Phase 1 тест (`AllowlistLoaderTest.kt`). Проверить: meaningful assertions (не просто «не упало»), покрытие критичного пути (парсинг реальной копии prod-ассета), валидность fixture (должна совпадать с prod). Оценить — соответствует ли user-spec D8 обоснованию (асимметричный payoff, единственное место гарантированно-исполняемого кода на старте). Записать audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 14: Pre-deploy QA
- **Description:** Acceptance testing. Запустить `./gradlew build` (полная сборка + lint) и `./gradlew :core-runtime:test` (unit-тест парсера). Проверить все TAC-1 … TAC-16 через grep'ы и jq (полный список — user-spec § «Агент проверяет» + tech-spec Acceptance Criteria). Проверить что AC-1 выполняется (APK собирается). Для user-verification AC (AC-2 … AC-16) — подготовить APK в `app/build/outputs/apk/debug/app-debug.apk` и предоставить инструкцию пользователю: скопировать на Honor 200, выполнить чеклист user-spec § «Пользователь проверяет» (шаги 1–15), затем back-to-back сравнение с Gallery по AC-14 protocol из Agent Verification Plan (3 прогона Gallery + 3 прогона Phase 1, медианы, гейт `median(S)/median(G) ≤ 1.5`). Записать QA-отчёт в `work/phase-1-foundation/logs/qa-report.json` со всеми 6 замерами + медианами + финальным verdict.
- **Skill:** pre-deploy-qa
- **Reviewers:** none

<!-- Deploy и Post-deploy-verification пропущены:
     - Deploy: Phase 1 не имеет CI/CD (manual APK install по deployment.md).
     - Post-deploy: нет MCP-инструментов для Android-устройства; live-verification делает пользователь вручную (см. user-spec § «Пользователь проверяет»). -->
