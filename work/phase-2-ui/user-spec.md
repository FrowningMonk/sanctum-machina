---
# Creation date (YYYY-MM-DD)
created: 2026-04-16

# Status: draft | approved
status: approved

# Work type: feature | bug | refactoring
type: feature

# Feature size: S (1-3 files, local fix) | M (several components) | L (new architecture)
size: L
---

# User Spec: phase-2-ui

## Что делаем

Превращаем Phase-1 foundation (два примитивных экрана) в полноценную мультимодальную чат-оболочку вокруг уже работающего `:core-runtime`. Подход — «обёртка»: ядро не переизобретаем, при необходимости расширяем портированием компонентов из `gallery-source/` (image preprocessing, audio recorder, multimodal Contents builder). Добавляется мультимодальный ввод (фото с камеры / галереи + аудио-запись через AudioRecord), ризонинг-канал (thinking) в чате, настройки инференса per-model (maxTokens, topK, topP, temperature, enableThinking, accelerator, systemPromptDefault) с persistent-хранением в Proto DataStore в новом модуле `:core-settings`, markdown-рендеринг ответов через compose-richtext, автоскролл ленты, заготовка AboutScreen для манифеста, переименование `app_name` в "Sanctum Machina". Чаты остаются эфемерными (история в Room — Phase 3, проекты — Phase 4). Настройки инференса теперь живут как bottom sheet **внутри** ChatScreen, а не отдельным экраном.

## Зачем

В Phase 1 доказана работоспособность ядра (Gemma-4-E2B на Honor 200 — не медленнее Gallery). Но Phase-1 UI — «минимальная лампочка для валидации»: нет мультимодальности, нет настройки инференса, нет ризонинга, UI грубый. Использовать приложение как ежедневный инструмент нельзя.

Phase 2 делает приложение пригодным для ежедневного использования пользователем (solo dev) на личном Honor 200. Без Room и Projects — они приедут в Phase 3 и Phase 4 соответственно. Приоритет пользователя по болевым пробелам Phase 1 (от важного к опциональному): мультимодальность → настройки инференса → ризонинг → визуальная полировка → переименование.

## Как должно работать

### US-1. Первый запуск и подготовка модели

1. Запускаю Sanctum Machina. Открывается `ModelManagerScreen` — список из двух моделей (Gemma-4-E2B-it, Gemma-4-E4B-it), как в Phase 1. В TopAppBar — кнопка "О приложении" (icon-only), открывает `AboutScreen`.
2. Скачиваю модель (если не скачана), как в Phase 1 (foreground download с прогрессом). Когда готово — тап "Загрузить" → индикатор "Загрузка модели…" → открывается `ChatScreen`.
3. В `ChatScreen` TopAppBar — три action'а: "Настройки" (⚙), "Сбросить" (↻), Back. Тап "Настройки" открывает bottom sheet с семью полями инференса: `maxTokens`, `topK`, `topP`, `temperature`, `enableThinking`, `accelerator` (GPU/CPU), `systemPromptDefault`. Эффективные значения = defaults из allowlist ∪ overrides из DataStore.
4. Меняю `temperature` с 1.0 на 0.7. Жму **"Применить"** — override сохраняется в DataStore по ключу `Model.modelId`, применяется к следующему ходу текущего чата.
5. Жму **"Default"** — все overrides для этой модели удаляются из DataStore, настройки возвращаются к allowlist-defaults.
6. Если меняю **"тяжёлое"** поле (накопительный список: `accelerator`, `enableThinking`; точный список уточнится после ручных тестов на Honor 200) — перед применением показывается диалог-предупреждение «Изменение этого параметра потребует переинициализации модели (~5–30 сек). Контекст текущего чата будет сброшен.» с кнопками "Применить" / "Отмена".

### US-2. Мультимодальный чат с фото

7. В чате пустая лента. Внизу input bar: `OutlinedTextField` + три кнопки (camera, gallery, microphone) + Send.
8. Тап иконки галереи → Android Photo Picker (система сама показывает UI) → выбираю 1–10 фото. Над input bar появляется горизонтальная строка миниатюр с кнопкой ✕ на каждой. Downscale до ~1024×1024 при приёме из content URI.
9. Тап иконки камеры → bottom sheet с live-preview через CameraX. Снимок → Bitmap in-memory → миниатюра над input bar.
10. Печатаю "Что на фото?" → Send. Bitmap'ы пакуются в `List<Bitmap>`, уходят в `helper.runInference`. Приходит стримящийся ответ, автоскролл тянет ленту к последнему сообщению. Markdown рендерится через compose-richtext.
11. Если `enableThinking=true` и модель поддерживает — над основным ответом collapsible блок "Показать ризонинг" с левой вертикальной линией и приглушённым текстом. Пока стрим идёт — раскрыт; когда закончился — можно свернуть.

### US-3. Аудио-ввод

12. Тап иконки микрофона → bottom sheet с индикатором записи и таймером до 30 сек. AudioRecord пишет raw PCM 16 kHz mono прямо в ByteArray (не в файл — litertlm ест PCM).
13. Жму "Остановить" (или автостоп на 30-й секунде) → миниатюра аудио-клипа над input bar с указанием продолжительности.
14. Печатаю "Что я сказал?" → Send. ByteArray уходит в `helper.runInference(audioClips = listOf(pcm))`, модель отвечает.

### US-4. Изменение настроек по ходу

См. US-1 шаги 3–6. Настройки всегда доступны одной кнопкой **внутри** ChatScreen — не нужно возвращаться в ModelManager.

### US-5. Переключение между моделями

15. Жму Back → возвращаюсь в `ModelManagerScreen`. Тап "Загрузить" на другой модели. Предыдущий engine выгружается (`cleanup`), новый инициализируется. Открывается новый чат для новой модели. Новая модель использует свои overrides из DataStore (или defaults).

### US-6. Выход = потеря чата

16. Жму Back из `ChatScreen` → сессия уничтожена. `ChatViewModel.onCleared()` освобождает in-memory Bitmap'ы и ByteArray'и. При повторном входе чат пустой — эфемерность. Overrides и app settings сохранены между сессиями.

### US-7. About-экран

17. Тап "О приложении" на `ModelManagerScreen` → открывается `AboutScreen` (независимый destination в NavHost, любой экран может вызвать `navigate("about")` — кнопка не прибита к конкретному месту).
18. `AboutScreen` — scrollable, рендерит markdown из ассета `app/src/main/assets/about.md` через compose-richtext. Пользователь редактирует `about.md` напрямую и наполняет манифестом по желанию. В футере автоматически — версия из `BuildConfig.VERSION_NAME` и attribution к Google AI Edge Gallery / Gemma / LiteRT-LM.

## Критерии приёмки

### Обязательные (блокирующие Phase 2)

- [ ] **AC-1.** Починен парсинг allowlist: `AllowedModel.toModel()` в `core-runtime/.../data/ModelAllowlist.kt` пробрасывает поля `llmSupportImage / llmSupportAudio / llmSupportThinking` из JSON в `Model`. **Блокер Phase 2** — без этого `DefaultModelRegistry.initialize` передаёт `supportImage=false` и multimodal физически не стартует. `model_allowlist_fixture.json` и релевантные кейсы `AllowlistLoaderTest` обновляются под новые поля.
- [ ] **AC-2.** `strings.xml`: `app_name = "Sanctum Machina"`. Проверка — launcher-лейбл на Honor 200 показывает «Sanctum Machina».
- [ ] **AC-3.** Создан новый gradle-модуль `:core-settings`: свой `build.gradle.kts`, свой `AndroidManifest.xml`, Hilt-модуль с провайдером `DataStore<AppSettings>`. `app/src/main/proto/app_settings.proto` со схемой `AppSettings { map<string, PerModelSettings> per_model_overrides }` и `PerModelSettings { optional int32 max_tokens; optional int32 top_k; optional float top_p; optional float temperature; optional bool enable_thinking; optional string accelerator; optional string system_prompt_default }`. Ключ map — `Model.modelId` (например, `litert-community/gemma-4-E2B-it-litert-lm`).
- [ ] **AC-4.** `ChatScreen` TopAppBar содержит три action'а: Settings / Reset / Back. Settings открывает bottom sheet с семью редакторами. **"Применить"** сохраняет overrides в DataStore и применяет к текущему чату: лёгкие параметры (`temperature, topK, topP, maxTokens, systemPromptDefault`) применяются на лету; тяжёлые (`accelerator, enableThinking`) требуют переинициализации engine — перед подтверждением показывается диалог-предупреждение. **"Default"** удаляет overrides по `modelId` и применяет allowlist-defaults.
- [ ] **AC-5.** `SettingsScreen` в этой фазе **не существует**. `AboutScreen` — отдельный destination в NavHost; вход — кнопка "О приложении" на `ModelManagerScreen` (расположение в коде не прибито — любой экран может открыть AboutScreen через `navigate("about")`).
- [ ] **AC-6.** `AboutScreen` — scrollable, рендерит markdown из `app/src/main/assets/about.md`. Пользователь редактирует `.md` напрямую. В футере — версия приложения из `BuildConfig.VERSION_NAME` и текст-атрибуция к Google AI Edge Gallery / Gemma / LiteRT-LM.
- [ ] **AC-7.** В `ChatScreen` ответы ассистента рендерятся через compose-richtext (markdown). Блок thinking — collapsible (порт из Gallery `MessageBodyThinking.kt`) с левой вертикальной линией и приглушённым текстом; авто-раскрыт во время стрима.
- [ ] **AC-8.** Автоскролл: при получении новых токенов стрима и при отправке нового пользовательского сообщения лента `LazyColumn` прокручивается к последнему сообщению.
- [ ] **AC-9.** Input bar: `OutlinedTextField` + три IconButton'а (camera, gallery, microphone) + Send. Send disabled, если текст пустой **и** нет ни одного вложения (ни фото, ни аудио). Send разрешён при наличии хотя бы одного: текст, фото или аудио.
- [ ] **AC-10.** Photo Picker (Android 13+ system sheet; на 31–32 — совместимый fallback через `PickMultipleVisualMedia`): выбор до 10 фото; при выборе >10 берутся первые 10 с snackbar-уведомлением. Каждое фото downscale до ~1024×1024 через `decodeSampledBitmapFromUri` (порт из Gallery). Миниатюры над input bar с кнопкой ✕ на каждой.
- [ ] **AC-11.** Камера: IconButton открывает bottom sheet с live preview (CameraX PreviewView), кнопкой "Снять" и кнопкой "Закрыть". Снимок через `ImageCapture.takePicture(OnImageCapturedCallback)` → `ImageProxy → Bitmap` in-memory → добавляется в список миниатюр.
- [ ] **AC-12.** Аудио: IconButton открывает bottom sheet с индикатором записи и таймером до 30 сек. Внутри — AudioRecord (CHANNEL_IN_MONO, PCM_16BIT, 16 kHz). Автостоп на 30-й секунде. Кнопка "Остановить" → `ByteArray` (raw PCM) → добавляется как audio-вложение. На выходе `helper.runInference(audioClips = listOf(pcm))`.
- [ ] **AC-13.** Multimodal inference end-to-end: отправка «текст + 1–3 фото» возвращает осмысленный стримящийся ответ на Gemma-4-E2B-it и Gemma-4-E4B-it. Отправка «текст + аудио» возвращает осмысленный ответ на обеих моделях. Проверяется manual smoke на Honor 200.
- [ ] **AC-14.** Thinking-канал: при `enableThinking=true` и поддерживающей модели — `partialThinkingResult` из `ResultListener` накапливается в `Message.thinkingText` и отображается collapsible-блоком над текстом ответа. При `enableThinking=false` или `llmSupportThinking=false` для модели — блок скрыт, thinking не накапливается.
- [ ] **AC-15.** Runtime permissions: CAMERA и RECORD_AUDIO запрашиваются on-demand при первом тапе соответствующей иконки. При отказе — snackbar "Разрешите доступ к камере" / "Разрешите доступ к микрофону". При выборе "Не спрашивать снова" — snackbar со ссылкой в системные настройки. Photo Picker permissions не требует.
- [ ] **AC-16.** Регрессия Phase 1: текстовый чат (без вложений) на обеих моделях отвечает стримящимся ответом без краша; скачивание/возобновление/отмена работают; переключение между моделями работает (предыдущий engine выгружается без краша, новый инициализируется, новый ChatScreen открывается пустым, текстовый запрос получает ответ); AC-14 Phase 1 (паритет скорости с Gallery — TTFT Phase 2 ≤ 1.5× TTFT Gallery на той же модели и том же промпте) продолжает соблюдаться.
- [ ] **AC-17.** Юнит-тесты `:core-runtime` остаются зелёными. Добавлены новые юнит-тесты:
  - `AppSettingsRepositoryTest` (в `:core-settings`) — save/read round-trip через in-memory DataStore, merge defaults ∪ overrides, reset удаляет запись по `modelId`.
  - `AllowlistLoaderTest` расширен кейсами для полей `llmSupportImage/Audio/Thinking` (каждое true/false и сочетания).
  - `EffectiveConfigTest` — корректность слияния allowlist-defaults с per-model overrides.
  - Multimodal preparation layer (новые тесты в `:core-runtime/src/test/`):
    - `MediaUtilsTest` — `decodeSampledBitmapFromUri` возвращает Bitmap корректного разрешения при разных `reqWidth/reqHeight` для тестовых PNG/JPEG, `rotateBitmap` применяет ExifInterface orientation корректно.
    - `AudioClipTest` — round-trip raw-PCM ByteArray через `AudioClip(audioData, sampleRate)`, граничные случаи (пустой массив, нечётный размер).
    - `MultimodalContentsBuilderTest` — сборка `Contents` из `(text, List<Bitmap>, List<ByteArray>)`: корректное число `Content.ImageBytes + Content.AudioBytes + Content.Text`, пустой текст не добавляет `Content.Text`.
- [ ] **AC-18.** Поведение при ограниченных возможностях модели:
  - Если `Model.llmSupportImage = false` — иконки камеры и галереи в input bar **скрыты**.
  - Если `Model.llmSupportAudio = false` — иконка микрофона в input bar **скрыта**.
  - Если `Model.llmSupportThinking = false` — toggle `enableThinking` в bottom sheet настроек **отсутствует**; thinking-блок в чате не показывается даже если пришли данные.
- [ ] **AC-19.** Прерывание аудиозаписи: при входящем звонке, переводе приложения в фон (onPause) или сворачивании во время активной записи — `AudioRecord.release()` вызывается, незавершённый буфер отбрасывается, bottom sheet закрывается, вложение не добавляется. При возврате в приложение — input bar в исходном состоянии без «залипшей» записи.
- [ ] **AC-20.** Микрофон при уже вложенном аудио: `MAX_AUDIO_CLIP_COUNT = 1` (Consts.kt). Если уже есть прикреплённый аудио-клип, иконка микрофона либо **disabled**, либо её тап вызывает snackbar "Максимум один аудио-клип на сообщение. Удалите текущий, чтобы записать новый." Выбор механики — за tech-spec.
- [ ] **AC-21.** Timing применения настроек:
  - **Лёгкие** поля (`temperature, topK, topP, maxTokens, systemPromptDefault`): override сохраняется в DataStore немедленно, применяется начиная со **следующего пользовательского хода**; не прерывает активный стрим.
  - **Тяжёлые** поля (`accelerator`; статус `enableThinking` подтвердится ручным тестом на Honor 200 и может быть перенесён в «лёгкие», если litertlm поддерживает смену на лету): после подтверждения диалога-предупреждения — `cleanup` + `initialize` engine (5–30 сек индикатор), история чата в UI сохраняется, контекст внутри engine сбрасывается.
  - `systemPromptDefault`: применяется при следующей переинициализации engine (через `resetConversation(systemPrompt = ...)`); в рамках текущей engine-сессии изменение видно с ближайшего `resetConversation` (триггер — тап Reset в TopAppBar или тяжёлое изменение настроек).
- [ ] **AC-22.** Финальный гейт Phase 2: AC-1..21 выполнены; регрессии Phase 1 (AC-16) отсутствуют; пользователь явно проставил апрув на Honor 200 после ручного прохождения US-1..US-7. Численный гейт TTFT/latency отдельно не замеряется (это не валидационная фаза) — достаточно AC-16 backstop'а.

### Желательные (не блокируют выпуск Phase 2)

- [ ] **AC-23.** Миниатюры аудио-вложений показывают длительность в секундах.
- [ ] **AC-24.** Индикатор уровня звука при записи аудио (peak amplitude — порт helper'а из Gallery `common/Utils.kt`).
- [ ] **AC-25.** Input bar умеет рендерить строку миниатюр в две строки или с горизонтальным скроллом, если вложений больше, чем помещается в одну строку.

## Ограничения

**Технические:**
- Без Room — чаты и сообщения в `:app` живут только в памяти `ChatViewModel.StateFlow`. Room (Phase 3), Projects (Phase 4), HuggingFace OAuth (пока не нужен, откладываем до появления gated-моделей) — **не в этой фазе**.
- `:core-runtime` в Phase 2 — **только три типа изменений**: (1) AC-1 (починка парсинга allowlist `AllowedModel.toModel()` для полей `llmSupportImage/Audio/Thinking`), (2) портирование media-утилит из `gallery-source/` в `:core-runtime/common/` (`decodeSampledBitmapFromUri`, `rotateBitmap`, `convertWavToMonoWithMaxSeconds`, `calculatePeakAmplitude`, тип `AudioClip`), (3) новые unit-тесты (AC-17). **Никакой реархитектуры ядра**, никакого переписывания `LlmChatModelHelper`, никакого изобретения собственных абстракций. Multimodal Contents API в `LlmChatModelHelper.runInference` уже готов из Phase 1 и принимает `List<Bitmap>` + `List<ByteArray>`.
- `:core-runtime` должен оставаться без Compose / Activity / ViewModel (по `patterns.md`). Все UI Compose и DataStore hooks живут в `:app`/`:core-settings`.
- Bitmap → litertlm остаётся PNG (как в Gallery) — ядро не трогаем.
- Аудио — AudioRecord (не MediaRecorder): raw PCM 16 kHz mono прямо в litertlm, без промежуточной конверсии файла.
- Ключ per-model overrides в DataStore — `Model.modelId` (устойчивый к переименованиям в allowlist).
- Attachments — исключительно in-memory (Bitmap и ByteArray в VM StateFlow). Диск не трогаем, нет cleanup-кода, нет временных файлов.
- Лимиты уже в `:core-runtime/data/Consts.kt`: `MAX_IMAGE_COUNT = 10`, `MAX_AUDIO_CLIP_COUNT = 1`, `MAX_AUDIO_CLIP_DURATION_SEC = 30`, `SAMPLE_RATE = 16000`.

**Дизайн / UX:**
- Русский интерфейс, все строки в `app/src/main/res/values/strings.xml`, без хардкода литералов в Compose.
- Тон: минималистичный, без эмодзи, imperative verbs на кнопках.
- Material 3. Тема — **только системная** через `isSystemInDarkTheme()` (оставляем логику Phase 1). Переключатель темы в настройках **не добавляется в Phase 2** — добавится позже (proto3 позволит расширить `AppSettings` полем `theme` без миграции).
- Один corner radius, три размера шрифта, Material Symbols outlined, copper/brass акцент применяется экономно (send button, active selection) — через `CustomColors` pattern (порт из Gallery).
- Референс Pocket LLM (primary), Claude/ChatGPT mobile (для drawer/history-паттерна на будущие фазы).
- Стабильность > визуал: «делаем на годы» — кнопка предпочтительнее свайпа, используем стабильные свежие версии UI-библиотек (не alpha/beta без необходимости).

**Модули / зависимости:**
- Новый gradle-модуль `:core-settings` (Proto DataStore). Proto плагин `com.google.protobuf 0.9.5` + runtime `protobuf-javalite 4.26.1` + `androidx.datastore 1.1.7`.
- Новые зависимости `:app`: `androidx.camera:1.4.2` (camera-core, camera-camera2, camera-lifecycle, camera-view), `com.halilibo.richtext:richtext-commonmark` (markdown). AudioRecord — platform API, без зависимости.
- Иконка приложения остаётся системным placeholder'ом до Phase 5 (графический дизайн).

## Риски

- **R1. Блокер allowlist parsing.** `AllowedModel.toModel()` не пробрасывает `llmSupportImage/Audio/Thinking` — engine инициализируется без vision backend. **Митигация:** AC-1 как первая Task Phase 2; до её закрытия остальные multimodal AC проверять невозможно.
- **R2. Тяжёлые настройки ломают UX.** Смена `accelerator` или `enableThinking` требует reinit engine (5–30 сек) и сброса контекста чата. **Митигация:** диалог-предупреждение перед применением таких изменений (AC-4). Точный список «тяжёлых» полей подтверждается на Honor 200 в процессе реализации.
- **R3. compose-richtext alpha.** Gallery использует `1.0.0-alpha02`. **Митигация:** при поломке — откат на plain `Text(message)` без markdown до Phase 3, фича thinking и ответы деградирует, но не ломает сборку.
- **R4. Thinking-канал нестабилен в litertlm 0.10.0 на Gemma-4.** `message.channels["thought"]` может приходить пустым/null. **Митигация:** manual smoke на реальной модели; если thinking не работает — AC-14 помечается как manual-verify-only, и thinking-блок остаётся выключенным (но код готов).
- **R5. OOM на устройствах с 8 ГБ RAM при 10 фото.** `MAX_IMAGE_COUNT = 10` в Consts.kt + downscale до 1024×1024 в `decodeSampledBitmapFromUri` уже снижают риск. **Митигация:** Honor 200 (12 ГБ) — основное устройство теста; риск на 8 ГБ документируем, но не закрываем в Phase 2.
- **R6. Proto plugin 0.9.5 + AGP 8.8.2.** Известных incompatibility нет. **Митигация:** проверка сборки на первом build; при проблемах — откат на 0.9.4 или обновление.
- **R7. Библиотечный манифест `:core-settings`.** Новый gradle-модуль требует корректной настройки namespace, manifest hygiene и merge-override (аналог `:core-runtime`, `patterns.md § Library-module manifest hygiene`). **Митигация:** точно воспроизвести паттерн `:core-runtime/src/main/AndroidManifest.xml`.
- **R8. Photo Picker UX на API 31–32.** Системный Photo Picker выглядит по-разному. **Митигация:** проверить на Honor 200 (Android 14+) — это main target; на старых Android UX может отличаться, но функционально работает.

## Технические решения

- **D1. Attachments — in-memory.** Bitmap и ByteArray живут в `ChatViewModel.StateFlow<List<Attachment>>`. На диск не пишем. Причина: упрощает lifecycle, убирает cleanup-код, переживает rotation через VM.
- **D2. Настройки инференса — в новом модуле `:core-settings` через Proto DataStore.** Не в `:app` — чтобы `:core-runtime` мог читать настройки через DI без зависимости от `:app`; build-isolation; KMP-путь; по аналогии с `:core-runtime`.
- **D3. Ключ per-model overrides — `Model.modelId`.** Стабильный идентификатор; при ручном переименовании `modelName` в allowlist настройки не теряются.
- **D4. Настройки инференса — bottom sheet ВНУТРИ ChatScreen.** Не отдельный destination `InferenceSettingsScreen`. Причина: пользователь настраивает по ходу разговора, сразу видит эффект, не уходит из чата. `ModelManagerScreen` остаётся чистым списком моделей.
- **D5. `SettingsScreen` в Phase 2 не создаётся.** В нём нечему жить: темы нет, HF нет, единственный релевантный пункт (About) вынесен прямой кнопкой. `AboutScreen` — независимый destination, расположение кнопки входа — гибкое.
- **D6. Тема — только системная через `isSystemInDarkTheme()`.** Логика Phase 1 сохраняется. Override темы (AUTO/LIGHT/DARK) появится позже по запросу, proto3 позволяет расширить схему без миграции. YAGNI.
- **D7. Аудио — AudioRecord, не MediaRecorder.** Выдаёт raw PCM напрямую, без конверсии файла — именно этого формата ждёт litertlm. Gallery делает так же.
- **D8. Bitmap → PNG для litertlm.** Оставляем как в Gallery и в текущем `:core-runtime/.../inference/LlmChatModelHelper.kt`. Ядро Phase 2 не переписываем.
- **D9. compose-richtext для markdown с Phase 2.** Cost ~300–500 КБ, применяется и к thinking, и к основным ответам. Если откажется — plain `Text` как fallback.
- **D10. Photo Picker (`PickMultipleVisualMedia`) вместо `ACTION_PICK` или `READ_MEDIA_IMAGES`.** Не требует permission, работает на API 31+ через Google Play update на старых девайсах.
- **D11. Multimodal preparation-код — в `:core-runtime` / `:app`.** Media helpers (`decodeSampledBitmapFromUri`, `rotateBitmap`, `convertWavToMonoWithMaxSeconds`, `calculatePeakAmplitude`, `AudioClip`-тип) — в `:core-runtime/common/` (переиспользуемо, non-UI). Compose-компоненты (camera sheet, audio recorder panel, message input bar, thinking block) — в `:app/ui/chat/` (UI, Compose, tied to Activity).
- **D12. Переименование "Sanctum" → "Sanctum Machina".** Только `res/values/strings.xml → app_name`. `applicationId`, `namespace`, package **не трогаем** — это технические идентификаторы. Причина: чем позже, тем больше точек прикосновения; сейчас — один файл.

## Тестирование

**Unit-тесты:** делаются всегда, не обсуждаются.

Новые unit-тесты в Phase 2:
- `:core-settings` — `AppSettingsRepositoryTest` (save/read round-trip, merge defaults ∪ overrides, reset clears entry).
- `:core-runtime` — `AllowlistLoaderTest` расширяется кейсами для новых полей `llmSupportImage/Audio/Thinking`; `EffectiveConfigTest` проверяет слияние defaults и overrides; `MediaUtilsTest` (`decodeSampledBitmapFromUri`, `rotateBitmap`); `AudioClipTest` (round-trip raw PCM, граничные случаи); `MultimodalContentsBuilderTest` (сборка `Contents` из text + Bitmap + ByteArray).

**Интеграционные тесты:** не делаем — Phase 2 не вводит инфраструктуры, для которой они нужны; litertlm в JVM-тестах не воспроизводим (native-зависимость).

**E2E тесты:** не делаем — Compose UI-тесты для solo dev на фазе частых UI-изменений неоправданы; multimodal end-to-end проверяется manual smoke на Honor 200.

## Как проверить

### Агент проверяет

| Шаг | Инструмент | Ожидаемый результат |
|-----|-----------|-------------------|
| 1. Сборка проекта | `./gradlew build` | `BUILD SUCCESSFUL` |
| 2. Юнит-тесты ядра | `./gradlew :core-runtime:test` | Все прежние + новые тесты зелёные |
| 3. Юнит-тесты settings | `./gradlew :core-settings:test` | `AppSettingsRepositoryTest` зелёный |
| 4. Lint | `./gradlew lintDebug` | Нет критичных ошибок; нет `MissingPermission` из-за library-manifest hygiene |
| 5. Parse allowlist с новыми полями | `./gradlew :core-runtime:test --tests AllowlistLoaderTest` | Поля `llmSupportImage/Audio/Thinking` парсятся корректно; fixture = prod |
| 6. Debug APK | `./gradlew :app:assembleDebug` | `app-debug.apk` появляется в `app/build/outputs/apk/debug/` |

### Пользователь проверяет

- **AC-1 (multimodal backend включён).** После установки `app-debug.apk` и загрузки модели — в логах Logcat при init engine нет «supportImage=false»; тап на фото-иконку → приложение не крашится, фото прикрепляется и отправляется.
- **AC-2 (имя приложения).** На лаунчере Honor 200 ярлык подписан **"Sanctum Machina"**.
- **US-1..US-7 end-to-end.** Прохожу все пользовательские сценарии из «Как должно работать» на Honor 200 с обеими моделями (Gemma-4-E2B-it и Gemma-4-E4B-it).
- **AC-13 (multimodal inference).** Отправляю «что на фото?» с тестовым фото — получаю осмысленный ответ. Отправляю аудио с короткой фразой — модель расшифровывает или реагирует.
- **AC-14 (thinking).** Включаю `enableThinking=true` через bottom sheet настроек — под ответом появляется collapsible блок с текстом ризонинга. Выключаю — блок исчезает.
- **AC-4 (настройки применяются).** Меняю `temperature` с 1.0 на 0.2 → следующий ответ заметно менее разнообразный. Меняю `accelerator` с GPU на CPU → появляется диалог-предупреждение → после подтверждения engine переинициализируется (5–30 сек), чат продолжает работу с новым backend.
- **AC-16 (регрессия Phase 1).** Текстовый чат на Gemma-4-E2B-it работает со скоростью не хуже Gallery (субъективно + TTFT из футера Phase 1 не регрессировал относительно Phase 1 AC-14).
- **Edge cases:** отказ в CAMERA / RECORD_AUDIO → snackbar; выбор 15 фото в Photo Picker → берутся 10 + snackbar; аудио-запись 30+ сек → автостоп; back во время стрима → стрим останавливается, сессия уничтожается; входящий звонок во время записи → запись отбрасывается (AC-19); тап микрофона при уже прикреплённом аудио → disabled либо snackbar (AC-20); открытие bottom sheet настроек во время стрима — допустимо, настройки принимаются, но применяются только к следующему ходу; тяжёлое изменение во время стрима — диалог предупреждает, что стрим будет прерван, при подтверждении вызывается `stopResponse` + reinit.
- **AC-6 (About).** Открываю `AboutScreen` → текст отображается, версия в футере корректная. Меняю `assets/about.md`, пересобираю APK, ставлю — текст обновился.
