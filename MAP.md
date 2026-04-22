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

**Где живут значения по умолчанию для инференса:** `src/main/kotlin/.../core/data/Consts.kt` — `DEFAULT_TEMPERATURE = 1.0f`, `DEFAULT_TOPK = 64`, `DEFAULT_TOPP = 0.95f`, `DEFAULT_MAX_TOKEN = 1024`. Подставляются при инференсе, если у пользователя нет оверрайда в `core-settings`.

**Где склеивается всё вместе:** `src/main/kotlin/.../core/inference/LlmChatModelHelper.kt` — берёт значения из настроек или подставляет константу из `Consts.kt`, передаёт в LiteRT.

### Что внутри `core-settings/`

| Имя | Тип | Что это | В git |
|---|---|---|---|
| `build/` | папка | Артефакт сборки модуля. Кэш | нет (gitignore) |
| `src/` | папка | Исходники модуля: Kotlin-код, `.proto`-схема, тесты | да |
| `build.gradle.kts` | файл | Конфиг сборки: 5 плагинов (включая **`protobuf`** — только здесь), namespace `app.sanctum.machina.core.settings`, зависимость на `:core-runtime`, тянет `androidx.datastore` + `protobuf-javalite`. Содержит блок `protobuf { ... }` для кодогенерации Java-классов из `.proto` | да |

**Где живёт схема настроек:** `src/main/proto/app_settings.proto` — объявлен список параметров инференса (`max_tokens`, `top_k`, `top_p`, `temperature`, `enable_thinking`, `accelerator`, `system_prompt_default`) с именами, типами и тегами для бинарной сериализации. Proto описывает только форму записи.

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
