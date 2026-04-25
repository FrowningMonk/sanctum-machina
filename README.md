# Sanctum Machina

> Локальный мультимодальный LLM-клиент для Android. Модели работают целиком на устройстве — без облака, без сети, без телеметрии.

Форк [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) с собственным UI, persistent-историей чатов и слоем общего контекста для повседневного использования.

---

## Статус

**Pre-alpha / экспериментально.** Публикуемые в Releases APK — debug-сборки с пометкой `Pre-release`. Имя проекта, архитектура и `applicationId` могут поменяться; будущая стабильная версия **не сможет обновить** установленную сейчас APK — потребуется переустановка с потерей локальных данных (история чатов, настройки). Не для повседневного использования.

## Установка

APK-файлы — на странице [Releases](../../releases). Скачайте последний, откройте в файловом менеджере на Android и установите. Потребуется разрешение «Установка из неизвестных источников».

## Что это

Тонкая обвязка вокруг движка [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM): discovery моделей, загрузка, lifecycle движка, UI и настройки. Движок и сами модели — бинарные артефакты от Google и сообщества; мы оркестрируем их.

## Возможности

- Локальный инференс LLM на устройстве (Android 12+).
- Поддержка моделей **Gemma 4** (E2B, E4B) из репозитория HuggingFace [`litert-community`](https://huggingface.co/litert-community).
- Мультимодальный ввод: текст, изображение (галерея / камера), короткий аудио-клип.
- Отдельный канал рассуждений (thinking) у моделей, которые его поддерживают.
- Настройки инференса per-model: temperature, top-K, top-P, max tokens, ускоритель, системный промпт.
- Persistent история чатов (Room).
- Восстановление после крэшей, фоновый прогрев модели, экспорт диагностических логов.

## Приватность

- **Данные не покидают устройство.** Нет cloud-sync, нет телеметрии, нет аналитики.
- **Google Auto Backup отключён** — настройки и история не уезжают в Google Drive.
- Скачивание моделей — единственное сетевое действие; идёт напрямую к HuggingFace по жёсткому allowlist'у.

## Технический стек

- **Платформа:** Android, `minSdk 31`, `targetSdk 35`
- **Язык / UI:** Kotlin, Jetpack Compose, Material 3
- **LLM-движок:** [LiteRT-LM 0.10.0](https://github.com/google-ai-edge/LiteRT-LM) (`.aar` с Google Maven)
- **DI:** Hilt
- **Хранилище:** Room (история), DataStore + protobuf (настройки)
- **Загрузки:** WorkManager (foreground service)

## Лицензия

[Apache License 2.0](LICENSE), унаследовано от upstream Google AI Edge Gallery. Атрибуция и сведения о модификациях — в [`NOTICE`](NOTICE).

## Атрибуция

- [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) — основа форка.
- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) — рантайм on-device inference.
- [Gemma](https://ai.google.dev/gemma) — семейство моделей.
