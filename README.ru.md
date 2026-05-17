[English](README.md) | **Русский**

# Sanctum Machina

> Локальный мультимодальный LLM-клиент для Android. Модели работают целиком на устройстве — без облака, без сети, без телеметрии.
>
> **Две вариации Gemma 4 в одном on-device стеке** — Gemma-4-E2B/E4B для чата, EmbeddingGemma-300M для retrieval. Загружаешь PDF в проект, задаёшь вопросы поверх корпуса — каждый ответ снабжается citation-чипами, по которым можно увидеть какой чанк из какого документа нашла модель.

Форк [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery), сфокусированный на LLM-чате — с собственным UI, persistent-историей чатов, инкогнито-режимом быстрого чата и проектами с multi-PDF RAG.

---

## Демо

Авиарежим включён; всё что ниже — работает без сети:

![Airplane mode demo](docs/media/demo-airplane-mode.webp)

Несколько persistent-чатов держат состояние независимо — переключился с сессии Python codegen на pitch privacy-first AI приложения (tagline → твит → испанский), потом вернулся к первому чату и продолжил ровно с того места. У каждого чата — свой KV-cache, свои настройки, своя история:

![Chat demo](docs/media/demo-chat.webp)

**Проекты + RAG над твоими PDF.** Создаёшь проект, кидаешь в него один или несколько PDF — каждый чат внутри проекта на каждый send автоматически подмешивает к промпту найденные чанки из корпуса. Citation-чипы под каждым ответом ассистента ведут к конкретному чанку конкретного документа; тап открывает модалку с raw-текстом чанка — можно глазами сверить каждое утверждение. 

![Projects + RAG demo](docs/media/demo-rag.webp)

## Статус

**Pre-alpha / экспериментально.** Публикуемые в Releases APK — debug-сборки с пометкой `Pre-release`. Имя проекта, архитектура и `applicationId` могут поменяться; будущая стабильная версия **не сможет обновить** установленную сейчас APK — потребуется переустановка с потерей локальных данных (история чатов, настройки). Не для повседневного использования.

## Установка

APK-файлы — на странице [Releases](../../releases). Скачайте последний, откройте в файловом менеджере на Android и установите. Потребуется разрешение «Установка из неизвестных источников».

## Что это

Построено вокруг движка [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM): на нашей стороне — discovery моделей, загрузка, lifecycle движка, чат-UI с persistent-историей, настройки и диагностика. Движок и сами модели — бинарные артефакты от Google и сообщества; мы их оркестрируем.

Проекты + RAG (появились в `v0.4.2-RAG`) добавляют поверх второй runtime: EmbeddingGemma-300M работает через LiteRT Interpreter параллельно с чат-моделью на litert-lm, и каждый send в project-чате проходит цикл embed → cosine-retrieve top-K → injection в промпт → generate. Эмбеддинги, citation'ы и сами PDF-файлы лежат в app-private хранилище; ничего из RAG-пайплайна не выходит в сеть.

## Возможности

**Чат:**

- Локальный инференс LLM на устройстве (Android 12+).
- Модели **Gemma 4** (E2B, E4B) из репозитория HuggingFace [`litert-community`](https://huggingface.co/litert-community).
- Мультимодальный ввод: текст, изображение (галерея / камера), короткий аудио-клип.
- Отдельный канал **ризонинга** у моделей, которые его поддерживают.
- Настройки инференса per-model: temperature, top-K, top-P, окно контекста, ускоритель, системный промпт.
- Persistent история чатов с боковым drawer-ом (переименование, удаление, разбивка по датам), плюс инкогнито-режим **быстрого чата**.
- Pre-flight RAM gate — модели, которым не хватает памяти устройства, блокируются от скачивания.
- Метрики в футере чата на каждое сообщение: TTFT и decode tok/s.
- Восстановление после крэшей, фоновый прогрев модели, экспорт диагностических логов.

**Проекты + RAG (`v0.4.2-RAG`):**

- **Multi-PDF на проект** — загружаешь один или несколько PDF, каждый чат внутри проекта ретривит чанки из общего корпуса на каждый send.
- **EmbeddingGemma-300M** для multilingual retrieval, вшита в APK — никакой HuggingFace-авторизации, никакой runtime-загрузки, RAG обходится без сети полностью.
- **Background ingest** через WorkManager foreground service — приложение можно свернуть, индексация продолжается под persistent-нотификацией (живой счётчик «стр. N · M чанков»).
- **Citation-чипы** под каждым ответом ассистента — `[filename · стр. N]`; тап открывает модалку с raw-текстом чанка — можно сверить любое утверждение глазами. Citation'ы persistent в Room и переживают удаление PDF — остаются приглушёнными чипами «(источник удалён)».
- **Per-project настройки RAG** — размер чанка, перекрытие, top-K, размерность embedding. Light-изменения применяются мгновенно; структурные изменения (размер/перекрытие) запускают полную переиндексацию через confirm-диалог.

## Известные проблемы

Несколько шероховатостей, которые есть на старте — отслеживаются, не сюрпризы:

- **Если в одном сообщении несколько фото — в историю сохраняется только первое** ([#4](../../issues/4)). Модель видит все фото и отвечает с их учётом; в истории остаётся только одно.
- **Тестировалось только на Honor 200** ([#5](../../issues/5)) — другие Android 12+ устройства должны работать, но не проверены; репорты приветствуются.
- **Размер APK ≈ 357 МБ.** В APK вшиты `.tflite` EmbeddingGemma (≈196 МБ) и SentencePiece tokenizer model (≈4.7 МБ). Это осознанный размен — см. раздел «Приватность».

## Приватность

- **Данные не покидают устройство.** Нет cloud-sync, нет телеметрии, нет аналитики.
- **Google Auto Backup отключён** — настройки и история не уезжают в Google Drive.
- **PDF, которые ты грузишь в проекты, обрабатываются целиком на устройстве** — извлечение текста, чанкинг, эмбеддинг, retrieval, генерация ответа. PDF-файлы лежат в app-private хранилище (`filesDir/projects/{id}/docs/`), vector store — в локальной SQLite, citation'ы persistent в той же базе как JSON. RAG работает в авиарежиме; нет ни remote-обновления allowlist'а, ни телеметрии вокруг индексации или запросов.
- Скачивание чат-моделей с HuggingFace — единственное сетевое действие. Идёт напрямую к репозиториям `litert-community/*` через жёсткий allowlist. **EmbeddingGemma вшита в APK**, поэтому RAG работает сразу после установки — без шага скачивания, без auth flow, без сетевых запросов.

## Технический стек

- **Платформа:** Android, `minSdk 31`, `targetSdk 35`
- **Язык / UI:** Kotlin, Jetpack Compose, Material 3
- **LLM-движок (чат):** [LiteRT-LM 0.10.0](https://github.com/google-ai-edge/LiteRT-LM) (`.aar` с Google Maven)
- **LLM-движок (RAG-эмбеддер):** [LiteRT Interpreter 2.1.4](https://ai.google.dev/edge/litert) — отдельный native runtime, co-resident с litert-lm
- **Эмбеддер + tokenizer:** EmbeddingGemma-300M (`.tflite`, вшита) + чистый Kotlin-порт SentencePiece BPE (байт-идентично upstream `sentencepiece` 0.2.1 на EN/RU/BiDi фикстурах)
- **Извлечение текста из PDF:** [`pdfbox-android` 2.0.27.0](https://github.com/TomRoush/PdfBox-Android) (Tom Roush fork, Apache 2.0)
- **DI:** Hilt
- **Хранилище:** Room (история, проекты, embeddings; схема v2 начиная с `v0.4.2-RAG`), DataStore + protobuf (настройки)
- **Загрузки / background ingest:** WorkManager (foreground services)
- **Вшитые ассеты:** `embeddinggemma-300M_seq2048_mixed-precision.tflite` (≈196 МБ, git LFS) + `sentencepiece.model` (≈4.7 МБ)

**Сборка из исходников:** в репозитории используется git LFS для вшитого эмбеддера. После `git clone` нужно выполнить `git lfs install && git lfs pull` ДО первой Gradle-сборки — иначе APK скомпилируется, но `.tflite` будет заменён 130-байтовым LFS-pointer'ом и эмбеддер не сможет инициализироваться на устройстве.

## Лицензия

[Apache License 2.0](LICENSE), унаследовано от upstream Google AI Edge Gallery. Атрибуция и сведения о модификациях — в [`NOTICE`](NOTICE).

## Атрибуция

- [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) — основа форка.
- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) и [LiteRT](https://ai.google.dev/edge/litert) — on-device inference рантаймы (чат + эмбеддер).
- [Gemma](https://ai.google.dev/gemma) — семейство моделей. Использование Gemma-4-E2B/E4B и EmbeddingGemma-300M регулируется [Gemma Terms of Use](https://ai.google.dev/gemma/terms); `.tflite` EmbeddingGemma вшит в APK, атрибуция также вынесена на экран «О приложении».
- [pdfbox-android](https://github.com/TomRoush/PdfBox-Android) — извлечение текста из PDF (форк Apache PDFBox от Tom Roush).
