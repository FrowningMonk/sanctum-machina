# MAP.md

> Карта верхнего уровня проекта PhoneWrap. Живой рабочий документ для навигации и уборки. Не публичная документация.

## Папки

| Папка | Что это | В git |
|---|---|---|
| `.claude/` | Скиллы, агенты, команды Claude Code. «Мозги» AI-процесса | да |
| `.git/` | Git-внутренности | — (служебная) |
| `.gradle/` | Кеш Gradle | нет (gitignore) |
| `.idea/` | Конфиг Android Studio / IntelliJ | нет (gitignore) |
| `.kotlin/` | Кеш компилятора Kotlin | нет (gitignore) |
| `app/` | Главный модуль Android-приложения. Здесь живёт всё, что видит пользователь | да |
| `build/` | Выхлоп сборки | нет (gitignore) |
| `core-runtime/` | Ядро LLM-инференса (модели, реестр, загрузка, LiteRT-runtime, multimodal). Забрано из Google AI Edge Gallery, ~4100 строк | да |
| `core-settings/` | Настройки инференса на модель (temperature, top_k, top_p и т.п.) в DataStore+proto. Вынесено из Gallery в отдельный модуль, зависит от `core-runtime` | да |
| `docs/` | Проектная документация. Сейчас содержит `design_handoff_phase3_ui/` — источник правды по UI/визуалу (токены, экраны, компоненты) | да |
| `gallery-source/` | Vendored Google AI Edge Gallery (исходник форка, для подсматривания). На диске есть, в git не трекается — это норм | нет |
| `gradle/` | Версии зависимостей Gradle (version catalog) | да |
| `work/` | Рабочее пространство фаз: `completed/` (архив 4 фаз), `research/` | да |

## Файлы

| Файл | Что это | В git |
|---|---|---|
| `.gitattributes` | Атрибуты файлов для git (line endings и т.п.) | да |
| `.gitignore` | Что не трекать | да |
| `CLAUDE.md` | Инструкции Claude по этому проекту: язык, поведение, безопасность | да |
| `NOTES.md` | Личная памятка пользователя (untracked, локальный) | нет (untracked) |
| `build.gradle.kts` | Корневой Gradle-скрипт | да |
| `gradle.properties` | Глобальные параметры Gradle | да |
| `gradlew` | Скрипт-обёртка Gradle Wrapper (Unix) | да |
| `gradlew.bat` | Скрипт-обёртка Gradle Wrapper (Windows) | да |
| `local.properties` | Локальные пути SDK (у каждого свои) | нет (gitignore) |
| `settings.gradle.kts` | Реестр модулей проекта (`app`, `core-runtime`, `core-settings`) | да |

## Открытые вопросы / кандидаты на уборку

_(пусто — все вопросы v1 закрыты)_
