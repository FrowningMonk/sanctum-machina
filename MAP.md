# MAP.md

> Карта верхнего уровня проекта PhoneWrap. Живой рабочий документ для навигации и уборки. Не публичная документация.

## 1. Кэши и артефакты сборки

> Скип. Наводить порядок бессмысленно.

| Папка | Что это | В git |
|---|---|---|
| `.git/` | Git-внутренности | — |
| `.gradle/` | Кеш Gradle | нет (gitignore) |
| `.idea/` | Конфиг Android Studio / IntelliJ | нет (gitignore) |
| `.kotlin/` | Кеш компилятора Kotlin | нет (gitignore) |
| `build/` | Выхлоп сборки. См. расшифровку ниже | нет (gitignore) |

### Откуда берётся `build/`

- **Рождается:** при первой Gradle-задаче (`./gradlew assembleDebug`, `./gradlew test` и т.п.) или при «Gradle sync» в Android Studio.
- **Что внутри:** скомпилированные `.class`, сгенерированный код (Hilt-DI, KSP, protobuf, Room-DAO, `R.java`), финальные `.aar`/`.apk`, кеши, отчёты.
- **Жизненный цикл:** инкрементально обновляется на каждом ребилде, чистится через `./gradlew clean` (или удалением папки — безопасно, восстановится при следующей сборке).
- **Почему gitignored:** 100% производное от `src/` + `build.gradle.kts` + `libs.versions.toml`. Гигабайты, машинозависимое, всегда восстановимо.

В проекте такие папки появляются параллельно у каждого модуля и в корне. Все четыре закрыты строкой `**/build/` в `.gitignore`:

```
./build
./app/build
./core-runtime/build
./core-settings/build
```

## 2. Ядро приложения

> Сюда копать обязательно. Это сам продукт.

| Папка | Что это | В git |
|---|---|---|
| `app/` | Главный модуль Android-приложения. Здесь живёт всё, что видит пользователь | да |
| `core-runtime/` | Ядро LLM-инференса (модели, реестр, загрузка, LiteRT-runtime, multimodal). Забрано из Google AI Edge Gallery, ~4100 строк | да |
| `core-settings/` | Настройки инференса на модель (temperature, top_k, top_p и т.п.) в DataStore+proto. Вынесено из Gallery в отдельный модуль, зависит от `core-runtime` | да |

### Что внутри `core-runtime/`

| Имя | Тип | Что это | В git |
|---|---|---|---|
| `build/` | папка | Артефакт сборки модуля. Кэш | нет (gitignore) |
| `src/` | папка | Исходники модуля: Kotlin-код + тесты | да |
| `build.gradle.kts` | файл | Конфиг сборки: 4 плагина (без protobuf), namespace `app.sanctum.machina.core`, ключевые зависимости — **`litertlm`** (LLM-движок), `work-runtime` (загрузчик моделей), `exifinterface` (метаданные изображений), `gson` | да |

**Откуда скорость.** On-device-скорость инференса обеспечивает **не наш код**, а зависимость `com.google.ai.edge.litertlm:0.10.0` — скомпилированный `.aar` с Google Maven. **Её исходника в нашем git-репо нет**; на этапе сборки Gradle качает `.aar` (нативные `.so`-библиотеки под arm64/armv7 + Kotlin/Java биндинги) из Google Maven и линкует в итоговый APK. Движок открытый: [github.com/google-ai-edge/LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) (+ [github.com/google-ai-edge/litert](https://github.com/google-ai-edge/litert) под капотом — GPU-делегат, XNNPACK, партиционирование графа), читать можно; компилировать самому не нужно.

В нашем `core-runtime` с LiteRT-LM напрямую общаются всего **четыре файла**:

- `inference/LlmChatModelHelper.kt` — главный вход (`Engine`, `Conversation`, `Message`, `SamplerConfig`, `MessageCallback`).
- `runtime/LlmModelHelper.kt` — загрузка файла модели в движок.
- `registry/DefaultModelRegistry.kt` — `resetConversation` (стирает контекст, не пересоздавая `Engine`).
- `common/MultimodalContentsBuilder.kt` — собирает image/audio/text в формат `Contents` движка.

Остальные сотни Kotlin-файлов работают через наши собственные абстракции (`Model`, `ModelEntry`, `ModelDownloadStatus`, …) и о LiteRT-LM ничего не знают.

**Практический вывод.** Заметно ускорить или замедлить инференс правкой **нашего** кода нельзя — скорость живёт в бинаре зависимости. Наша роль — оркестрация: discovery моделей, lifecycle движка, UI, мультимодальная обвязка, настройки. Это примерно ~8000 строк обвязки вокруг чужого движка — и именно этого не хватит, если подключить голый LiteRT-LM к пустому Android-проекту.

**Где живут значения по умолчанию для инференса:** `src/main/kotlin/.../core/data/Consts.kt` — `DEFAULT_TEMPERATURE = 1.0f`, `DEFAULT_TOPK = 64`, `DEFAULT_TOPP = 0.95f`, `DEFAULT_MAX_TOKEN = 1024`. Подставляются при инференсе, если у пользователя нет оверрайда в `core-settings`.

**Где склеивается всё вместе:** `src/main/kotlin/.../core/inference/LlmChatModelHelper.kt` — берёт значения из настроек или подставляет константу из `Consts.kt`, передаёт в LiteRT.

**Где живёт манифест модуля:** `src/main/AndroidManifest.xml` — декларация `POST_NOTIFICATIONS` и merge-директива, подключающая `SystemForegroundService` WorkManager'а. **Зачем:** модуль качает модели через WorkManager, foreground-сервис с уведомлением нужен, чтобы Android не убил загрузку на фоне. Потребителю (`app/`) ничего дополнительно настраивать не надо — подключил модуль, и разрешение с сервисом уже попадут в итоговый манифест приложения.

### Процесс: добавление и скачивание моделей

> Кросс-файловый сценарий. Начинается статическим каталогом в `assets/` на сборке, заканчивается файлом модели на устройстве пользователя. Затрагивает восемь файлов в `core-runtime` + один JSON-asset, триггер — из `:app` через `ModelRegistry`.

**End-to-end цепочка (данные вниз, статус вверх):**

```
  assets/model_allowlist.json         ← статический каталог моделей
         │
         │ парсинг + валидация через regex'ы (security gate)
         ▼
  registry/AllowlistLoader.kt
         │
         │ List<Model>
         ▼
  data/Model.kt                       ← «анкета» — общий словарь для всего ниже
         │
         │ оборачивается в ModelEntry (Model + downloadStatus + initStatus)
         ▼
  registry/ModelRegistry              ← UI подписан на StateFlow<List<ModelEntry>>
         │
         │ пользователь нажал «скачать» — Registry зовёт:
         ▼
  data/DownloadRepository             ← диспетчер
         │   Model → Data-bundle, OneTimeWorkRequest<DownloadWorker>, enqueue
         ▼
  worker/DownloadWorker               ← HTTP-качалка в WorkManager'е
         │
         ▼
  externalFilesDir/<normalizedName>/<version>/<fileName>   ← файл на диске
         ▲
         │ инференс потом зовёт Model.getPath() — тот же путь
         └── LiteRT-LM грузит файл в движок
```

Прогресс идёт в обратную сторону: worker публикует `WorkInfo.State` → Repository ловит через LiveData → переводит в `ModelDownloadStatus` → Registry мутирует `ModelEntry.downloadStatus` в `StateFlow` → UI перерисовывается. Одна подписка UI — всё видно.

---

#### 1. Каталог моделей (источник)

**`src/main/assets/model_allowlist.json`** — JSON-каталог (сейчас две Gemma 4: E2B и E4B, обе мультимодальные). На каждую запись: `modelId`, `modelFile`, `commitHash`, `sizeInBytes`, `minDeviceMemoryInGb`, флаги `llmSupport*`, `defaultConfig` (`topK`/`topP`/`temperature`/`maxContextLength`/`maxTokens`/`accelerators`), `taskTypes`, `bestForTaskTypes`. **Зачем:** жёсткий whitelist — грузить можно только то, что здесь перечислено. Лежит в `assets/`, потому что пакуется в APK как есть; удалённого allowlist’а в нашем форке нет.

#### 2. Валидация каталога (security gate)

**`src/main/kotlin/.../core/registry/AllowlistLoader.kt`** — парсит JSON через Gson, прогоняет каждую запись через охранные правила:

- `modelId` матчится на `^litert-community/[A-Za-z0-9._-]+$`
- `modelFile` — на `^[A-Za-z0-9._-]+$`
- `commitHash` — ровно 40 hex-символов в нижнем регистре
- `sizeInBytes` — в диапазоне `1..10 GB`
- итоговый URL обязан начинаться с `https://huggingface.co/`

При нарушении любого — `Result.failure`, список моделей в Registry не обновится. **Зачем:** физически невозможно начать качать что-то вне `litert-community`, в чужом формате или подозрительного размера. Чтобы расширить политику (другая org, другой источник, больший размер) — править regex’ы и константы **здесь, в одном файле**.

#### 3. Доменный объект (общий словарь)

**`src/main/kotlin/.../core/data/Model.kt`** — data-class `Model`, общий язык между парсером allowlist’а, Repository, воркером, Registry и инференсом. Поля: часть из JSON (`url`, `version`, `downloadFileName`, `sizeInBytes`, флаги мультимодальности), часть сгенерирована (`normalizedName` = `name` с не-алфанумом → `_`), часть — рантайм (скачанный `instance` движка, `configValues` пользователя, статус инициализации).

**Интуиция:** `Model` в скачке — анкета модели. До скачки отдаёт поля воркеру («что качать»); после скачки тот же объект через `getPath()` говорит, где файл лежит на диске. Одни и те же поля на запись и на чтение — поэтому парсер JSON, Repository, worker и инференс не расходятся в интерпретации «что такое эта модель».

#### 4. Витрина для UI

**`src/main/kotlin/.../core/registry/ModelRegistry.kt`** (интерфейс) + **`DefaultModelRegistry.kt`** (реализация); рядом — **`ModelEntry.kt`** (обёртка `Model + downloadStatus + initStatus`) и **`ModelInitStatus.kt`** (состояния движка). Единая реактивная точка для UI: `StateFlow<List<ModelEntry>>`. Триггерит `AllowlistLoader` через `refreshAllowlist()`, оборачивает callback-скачку Repository в `Flow` и зеркалит прогресс в `StateFlow`, делегирует отмену.

**Интуиция:** в блоке скачки ModelRegistry — UI-адаптер; остальные его обязанности относятся к блоку использования модели.

#### 5. Диспетчер скачки

**`src/main/kotlin/.../core/data/DownloadRepository.kt`** — интерфейс + `DefaultDownloadRepository`. Распаковывает поля `Model` в `Data`-bundle, создаёт `OneTimeWorkRequest<DownloadWorker>` с уникальностью по имени модели (`enqueueUniqueWork(REPLACE)`) и тегом для отмены, подписывается на `WorkInfo`-LiveData, переводит `WorkInfo.State` → `ModelDownloadStatus` для UI, посылает системное уведомление о завершении, если приложение свёрнуто. Принимает FQN `MainActivity` от `:app` через статическое поле-«почтовый ящик», чтобы не хардкодить класс в библиотеке.

**Интуиция:** Repository — **диспетчер**. `Model` — анкета, `Worker` — качалка, а Repository принимает анкету, выдаёт наряд качалке, следит за ходом и докладывает пользователю. Без него воркер не стартует, UI не узнаёт прогресс, отмена невозможна.

#### 6. HTTP-качалка

**`src/main/kotlin/.../core/worker/DownloadWorker.kt`** — `WorkManager` `CoroutineWorker`. Качает файл по HTTP с поддержкой возобновления (HTTP Range), показывает прогресс в системном уведомлении (тот самый foreground-service из манифеста модуля). Переживает сворачивание приложения и обрывы сети.

На входе получает через `Data`-bundle: URL файла, имя файла, `commitHash` (имя подпапки на диске), папка модели, общий размер; опционально — флаг ZIP, extras-URL’ы/имена; + FQN `MainActivity` для клика по уведомлению. JSON напрямую **не читает** — все поля приходят от Repository.

**Интуиция:** worker — тупая качалка (дали URL и куда положить — качает). Всё «умное» (что именно качать, уникальность, отмена, трансляция прогресса в UI) — в `DownloadRepository`.

---

#### Чтобы добавить новую модель

Править **только** `src/main/assets/model_allowlist.json` — добавить запись с `name`, `modelId`, `modelFile`, `commitHash`, `sizeInBytes`, `taskTypes` (остальные поля опциональны). Требования к записи:

- модель лежит на HuggingFace в org `litert-community`;
- формат LiteRT-LM (`.litertlm` или `.task`);
- размер ≤ 10 GB;
- `commitHash` — настоящий 40-символьный git-SHA коммита на HF.

Если модель **не из `litert-community`** или в другом формате — кроме JSON придётся ослаблять правила в `AllowlistLoader.kt`.

#### Практические оговорки

Валидатор проверяет **форму** записи, но не её **правдивость**. Что JSON не страхует:

- **APK нужно пересобрать** — bundled asset, не runtime-конфиг.
- **Флаги мультимодальности — честное слово автора.** Указал `llmSupportImage: true` модели, которая картинки не умеет → скачка пройдёт, LiteRT-LM упадёт при первой картинке.
- **Опечатка в `commitHash` проходит валидатор** — проверяется формат (40 hex), не существование. Сломан один символ → HTTP 404 у пользователя на скачивании.
- **Формат файла:** регексы проверяют только имя, не содержимое. GGUF под видом `.litertlm` → скачка пройдёт, init движка упадёт невнятно.
- **`minDeviceMemoryInGb` — только подсказка.** Блокировки при нехватке RAM нет. Занизил → OOM у части юзеров.
- **Пустой или неправильный `taskTypes`** → модель не появится на ожидаемых экранах. `taskTypes.isEmpty()` спасает (считается LLM), частичный список — нет.
- **Неподходящий `defaultConfig`** → модель отвечает, но может казаться тупее реальности (универсальные дефолты из `Consts.kt` не обязательно оптимальны).
- **Удаление модели из JSON** оставляет орфанские файлы на диске у тех, кто её уже скачал. Периодической очистки нет.
- **Gson молча игнорирует опечатки в именах полей.** `modelID` вместо `modelId` → поле не считается, используется дефолт.
- **Attribution в `app/src/main/assets/about.md` и `app/src/main/res/values/strings.xml`** захардкожена текстом «Gemma · LiteRT-LM». Не-Gemma модель → несоответствие в UI About.
- **Лицензирование модели** — не код. Лицензия на HF может ограничивать распространение/коммерческое использование.

#### Кандидаты на доработку

Если когда-нибудь решим автоматизировать что-то из оговорок выше:

- **Верификация capabilities модели** — пробный вызов с image/audio при инициализации, чтобы флаги не расходились с реальностью.
- **Блокировка/предупреждение при нехватке RAM** (сейчас `minDeviceMemoryInGb` только метка).
- **Валидация совместимости `taskTypes` с флагами мультимодальности** (нельзя `llm_ask_image` без `llmSupportImage`).
- **GC орфанских файлов моделей** — сканер «что на диске vs что в allowlist», очистка невостребованного.
- **HEAD-запрос на URL перед публикацией allowlist’а** — ловить несуществующие коммиты/файлы ещё при сборке.
- **Валидация формата файла модели** по magic-bytes / метаданным LiteRT-LM (если движок такое даёт).
- **Локальный импорт модели end-to-end** — scaffolding в `Model` есть (`imported` / `IMPORTS_DIR`), но цепочка до UI не проверена; возможно, недоделано в форке.

### Что внутри `core-settings/`

| Имя | Тип | Что это | В git |
|---|---|---|---|
| `build/` | папка | Артефакт сборки модуля. Кэш | нет (gitignore) |
| `src/` | папка | Исходники модуля: Kotlin-код, `.proto`-схема, тесты | да |
| `build.gradle.kts` | файл | Конфиг сборки: 5 плагинов (включая **`protobuf`** — только здесь), namespace `app.sanctum.machina.core.settings`, зависимость на `:core-runtime`, тянет `androidx.datastore` + `protobuf-javalite`. Содержит блок `protobuf { ... }` для кодогенерации Java-классов из `.proto` | да |

**Где живёт схема настроек:** `src/main/proto/app_settings.proto` — объявлен список параметров инференса (`max_tokens`, `top_k`, `top_p`, `temperature`, `enable_thinking`, `accelerator`, `system_prompt_default`) с именами, типами и тегами для бинарной сериализации. Proto описывает только форму записи.

**Где живёт Kotlin-логика:** `src/main/kotlin/.../core/settings/` — четыре файла + DI-папка:

- `AppSettingsSerializer.kt` — мост DataStore ↔ proto (байты ↔ объект). **Зачем:** DataStore умеет только писать/читать байты, а код работает с объектами — нужен переводчик между ними.
- `AppSettingsRepository.kt` — публичный интерфейс модуля (что могут вызывать снаружи). **Зачем:** фиксирует контракт наружу, остальной код зависит только от него — реализацию можно подменить (например, на фейк для тестов), не трогая вызовы.
- `DefaultAppSettingsRepository.kt` — реализация интерфейса, вся IO через DataStore. **Зачем:** в одном месте собрана вся работа с файлом настроек — читать/писать proto умеет только он.
- `di/CoreSettingsModule.kt` — Hilt-биндинги: связывает интерфейс с реализацией и создаёт `DataStore<AppSettings>` (файл `filesDir/datastore/app_settings.pb`). **Зачем:** без него Hilt не знает, кого подставить на интерфейс и как собрать DataStore — это точка, в которой модуль становится доступен остальным.

## 3. Конфиги сборки

> Работают, не трогаем. Часть генерируется автоматически.

| Файл/папка | Что это | В git |
|---|---|---|
| `build.gradle.kts` | Корневой Gradle-скрипт | да |
| `settings.gradle.kts` | Реестр модулей проекта (`app`, `core-runtime`, `core-settings`) | да |
| `gradle.properties` | Глобальные параметры Gradle | да |
| `gradle/` | Две истории сразу: пин самого Gradle (`wrapper/`) и список всех зависимостей проекта (`libs.versions.toml`). См. расшифровку ниже | да |
| `gradlew` / `gradlew.bat` | Скрипты-обёртки Gradle Wrapper (Unix / Windows) | да |
| `local.properties` | Локальные пути Android SDK (у каждого свои) | нет (gitignore) |

### Что внутри `gradle/`

| Файл | Аналог в Python-мире | Зачем |
|---|---|---|
| `wrapper/gradle-wrapper.jar` | — (своего аналога нет) | Маленький загрузчик (бинарник, ~43 KB). Скачивает нужный Gradle при первом запуске `./gradlew`. Автогенерируется, руками не правится |
| `wrapper/gradle-wrapper.properties` | `.python-version` (pyenv) | Пинит **сам Gradle** (одна строчка: `distributionUrl=...gradle-8.13-bin.zip`). Трогаем только при апгрейде Gradle |
| `libs.versions.toml` | **`requirements.txt`** | Version catalog: список **всех** библиотек и плагинов проекта с версиями. Сюда добавляем/обновляем зависимости. Главный файл этой папки |

## 4. Конфиги репозитория

> Работают, трогаем только если что-то меняется в правилах хранения.

| Файл | Что это | В git |
|---|---|---|
| `.gitattributes` | Атрибуты файлов для git (line endings и т.п.) | да |
| `.gitignore` | Что не трекать | да |

## 5. Документация и референсы

> Растущий публичный слой (для будущих пользователей репо) и vendored upstream.

| Папка | Что это | В git |
|---|---|---|
| `docs/` | Проектная документация. Сейчас содержит `design_handoff_phase3_ui/` — источник правды по UI/визуалу (токены, экраны, компоненты). Будет расти | да |
| `gallery-source/` | Vendored Google AI Edge Gallery (исходник форка, для подсматривания). На диске есть, в git не трекается — это норм | нет |

## 6. Методология (AI-first процесс)

> Внутренняя кухня. Живёт по своим правилам, порядок здесь — отдельная история.

| Файл/папка | Что это | В git |
|---|---|---|
| `.claude/` | Скиллы, агенты, команды Claude Code. «Мозги» AI-процесса | да |
| `work/` | Выхлоп процесса: `completed/` (архив 4 фаз), `research/` | да |
| `CLAUDE.md` | Инструкции Claude по этому проекту: язык, поведение, безопасность | да |
| `MAP.md` | Этот файл. Рабочий документ на время уборки | да |
| `NOTES.md` | Личная памятка пользователя | нет (untracked) |

## Открытые вопросы / кандидаты на уборку

_(пусто — все вопросы v1 закрыты)_
