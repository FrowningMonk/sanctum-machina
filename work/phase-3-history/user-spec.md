---
created: 2026-04-20
status: draft
type: feature
size: L
---

# User Spec: Phase 3 — История чатов

## Что делаем

Переводим Sanctum Machina из демо в daily-driver: все чаты сохраняются между сессиями в Room, а движок модели прогревается в фоне при старте приложения, так что 20–30 секунд инициализации больше не блокируют первое сообщение. Сохраняется и явный режим «Быстрый чат» для одноразовых разговоров без следов в истории. Навигация переделывается под ChatGPT/Claude-подобную структуру: главный экран — кнопка «Новый быстрый чат», слева drawer с историей persistent-чатов, переходы между открытыми в одной сессии чатами на прогретой модели — мгновенные.

## Зачем

Сегодня у приложения две боли, превращающие его в demo:

1. **Нет истории.** Любой swipe-away из переключателя задач, любой kill системой — и весь разговор исчезает. Вчерашний контекст задачи утром недоступен, даже если процесс жив всё это время.
2. **20–30 секунд прогрева на каждом открытии.** На телефоне работа burst'ами: glance → набрать → отправить → закрыть. Полминуты ожидания перед первым сообщением — критически больно, больше чем потеря истории. В реальном использовании это возвращает пользователя в ChatGPT/Claude даже при наличии манифеста on-device privacy.

Phase 3 закрывает обе боли: персистентность через Room и прогрев в фоне. **Приоритет в дизайн-решениях — прогрев**, где конфликтует с персистентностью.

Побочно закрываем накопившийся долг:
- Silent-failure инициализации модели (товарищ на realme получил чёрный экран и пустой лог — Google-код `LlmChatModelHelper.initialize` не бросает exception и не пишет в `ErrorLog`). Оборачиваем try/catch + явный `ErrorLog.e("model", ...)` + Snackbar с кодом ошибки.
- Phase 2.5 TODO: `DeviceInfoCollector` стаблен — экспорт показывает `active model: none` и `downloaded models: (none)`. Phase 3 всё равно расширяет `ModelRegistry` → бесплатно чиним.
- UX-баг Phase 2: кнопка Send прячется под клавиатурой → добавляем `imePadding` на input bar.
- Drift `Model.name` vs `Model.modelId` (NB-1 из Phase 2): Room хранит стабильный HF-id, DataStore per-model настройки — по имени. Чиним одноразовой миграцией при первом запуске.

## Как должно работать

### Главный экран (home hub)

Стартовая точка приложения. TopAppBar: слева hamburger (открывает drawer), заголовок «Sanctum Machina», справа иконка Settings. Тело — центральная кнопка **«Новый быстрый чат»**, если хотя бы одна модель скачана. Если не скачано ни одной — placeholder «Для начала работы скачайте модель.» + кнопка «Открыть Model Manager»; drawer-кнопка «Новый чат» disabled.

Фоновое: пока открывается главный экран, в `SanctumApplication.onCreate` (после `CrashHandler` install, внутри process-name guard) запущен warmup модели-по-умолчанию. Пользователь видит экран мгновенно, warmup идёт параллельно.

### Drawer (swipe вправо / тап hamburger)

Шапка «История чатов», сверху кнопка **«Новый чат»** (создаёт persistent), ниже — список persistent-чатов, отсортированный по `last_message_at DESC`. Каждая строка: название (ручное или auto-title из первых 20 символов первого сообщения) + имя модели + относительная дата. Внизу drawer'а — пункты «Модели» (Model Manager) и «О приложении».

Empty-state drawer: «Нет сохранённых чатов.» + кнопка «Новый чат».

**Swipe влево** на строке → красная кнопка «Удалить» → диалог подтверждения. **Long-press** на строке → контекстное меню «Переименовать / Удалить».

### US-1. Новый быстрый чат

1. Тап «Новый быстрый чат» на главном экране.
2. ChatScreen открывается мгновенно, история пустая, TopAppBar показывает имя текущей прогретой модели, incognito-индикатор (иконка перечёркнутого глаза + подзаголовок «Быстрый чат»).
3. Пользователь печатает текст / прикрепляет фото / аудио. Send активен если (а) движок готов и (б) есть текст или вложение.
4. Пока движок не прогрет — Send disabled, но набирать можно. Как только warmup завершился — Send активен.
5. На Send → стрим ответа; по завершении ответ остаётся в памяти до закрытия чата.
6. Back / swipe-away / kill процесса → сессия и `filesDir/quick/` стираются. В БД — ничего. В file manager в `filesDir/quick/` после закрытия — пусто.

### US-2. Новый persistent-чат

1. Drawer → тап «Новый чат».
2. ChatScreen открывается в **черновом** состоянии: строка в Room ещё не создана, активная модель = прогретая по умолчанию.
3. TopAppBar имя модели **кликабельное**: тап → dropdown скачанных моделей → выбор другой → если не совпадает с прогретой, cross-model dialog → reinit. Пользователь может сменить модель перед первым сообщением сколько угодно раз.
4. Пользователь пишет первое сообщение, жмёт Send. **В этот момент**: Room INSERT `chats` row с `model_id = активная модель` + USER message (с атомарной записью файлов вложений в `filesDir/attachments/{chatId}/`). С этой секунды TopAppBar read-only label, `model_id` залочен.
5. После `done=true` ответа модели → auto-title генерируется из первых 20 символов первого USER-сообщения (trim → collapse whitespace → cut по пробелу → «…» если обрезано; fallback «Чат от DD.MM HH:mm» если первое сообщение без текста). Обновляется `chats.title` и `chats.last_message_at`.

### US-3. Открытие старого чата на прогретой модели

1. Drawer → тап на чате; `model_id` совпадает с активной.
2. ChatScreen открывается **мгновенно**, вся история из Room отображается, Send доступен сразу.
3. Пользователь продолжает разговор — никакой задержки.

### US-4. Открытие старого чата на другой модели

1. Drawer → тап на чате; `model_id` ≠ активная.
2. До навигации показывается диалог:

   > **Переключить модель?**
   > Этот чат использует {modelA}. Сейчас загружена {modelB}. Переключение займёт 20–30 секунд.
   > [Отмена] [Переключить]

3. «Отмена» → остаёмся в drawer, активная модель не меняется.
4. «Переключить» → навигация на ChatScreen, история видна сразу, reinit идёт в фоне, в TopAppBar спиннер + текст «Модель перезагружается…». Send disabled, набирать текст можно.
5. По завершении reinit → спиннер убирается, Send активен, разговор продолжается.

### US-5. Продолжение разговора после перезапуска приложения

1. Приложение убито (любой способ — swipe / LMK / reboot / adb install / краш).
2. Запуск → `SanctumApplication.onCreate` в фоне стартует warmup default-модели (`AppSettings.default_model_id` → fallback `last_used_model_id`).
3. Пользователь видит главный экран. Открывает drawer, тапает вчерашний чат → история сразу видна, warmup уже почти завершился (шёл параллельно с открытием drawer'а / скроллом). Если ещё нет — спиннер, Send disabled.
4. Warmup завершился → Send активен → пишу «продолжим» → стрим.

### US-6. Переименование чата

Drawer → long-press на строке → «Переименовать» → диалог:

> **Переименовать чат**
> [TextField с текущим названием, автофокус]
> [Отмена] [Сохранить]

Лимит 60 символов, trim whitespace. Пустое имя → восстанавливается auto-title. Пользовательское имя имеет приоритет: auto-title генерируется **только** для чатов без ручного переименования.

### US-7. Удаление чата

1. Drawer → swipe влево на строке → красная кнопка «Удалить».
2. Диалог подтверждения:

   > **Удалить чат?**
   > «{название}» — {N} сообщений. Отменить удаление будет нельзя.
   > [Отмена] [Удалить]

3. «Удалить» → Room CASCADE DELETE (`messages` удаляются автоматически по FK) + `File(filesDir, "attachments/$chatId").deleteRecursively()`. Если чат сейчас открыт — `navController.popBackStack()` + Snackbar «Чат удалён».
4. **Если в момент удаления шёл стрим ответа модели**: сначала `helper.stopResponse(model)`, потом CASCADE DELETE + файлы + popBack + Snackbar. Поздние колбэки `onMessage` игнорируются (VM проверяет актуальный `chatId`).

### US-8. Первый запуск без скачанных моделей

1. Свежая установка, ни одной модели не скачано.
2. Главный экран: вместо кнопки «Новый быстрый чат» — placeholder «Для начала работы скачайте модель.» + кнопка «Открыть Model Manager».
3. Drawer тоже отражает состояние: кнопка «Новый чат» disabled, в empty-state фразе — подсказка «Сначала скачайте модель».
4. Пользователь переходит в Model Manager → скачивает модель → она автоматически становится default → главный экран превращается в обычный.

### US-9. Молчаливый сбой инициализации модели

1. Пользователь (реальный кейс: товарищ на realme RMX3085, Gemma-4-E4B) тапает на чат / «Новый чат».
2. `LlmChatModelHelper.initialize` падает: либо бросает JVM exception, либо тихо возвращает ошибочный статус без exception.
3. Обёртка `try/catch` вокруг вызова → `ErrorLog.e("model", description, cause)` → запись попадает в `filesDir/logs/errors.log`.
4. На UI: Snackbar с кратким текстом ошибки и её кодом (не «что-то пошло не так», а конкретно — например, «Init failed: CPU backend unavailable»).
5. ChatScreen остаётся открытым с красным индикатором «Модель недоступна» в TopAppBar; Send и Reset disabled.
6. Пользователь через «О программе» → «Сохранить лог» (Phase 2.5) выгружает диагностический .txt и присылает разработчику. Там **видна реальная причина сбоя**, не пустой файл как было до Phase 3.

### US-10. Экспорт диагностики показывает реальное состояние

1. Пользователь открывает «О программе» → раздел «Диагностика» → «Сохранить лог» (уже существует с Phase 2.5).
2. Экспортированный .txt содержит в device-info секции:
   - `active_model: gemma-4-E4B-it-litert-lm` (или `none` если реально не прогрета)
   - `downloaded_models: gemma-4-E4B-it-litert-lm, gemma-4-E2B-it-litert-lm` (или `(none)` если пусто)

   Вместо заглушек «active model: none» / «downloaded models: (none)», которые были после Phase 2.5.

### US-11. Отправка сообщения с открытой клавиатурой

1. Пользователь в ChatScreen, тапает input field → появляется IME (экранная клавиатура).
2. Content (включая input bar с кнопкой Send) автоматически **поднимается над клавиатурой** за счёт `WindowInsets.ime` / `imePadding`.
3. Пользователь печатает, видит кнопку Send → тапает → сообщение уходит без необходимости сначала сворачивать клавиатуру.

### US-12. Загрузка модели из Model Manager

1. Model Manager → тап «Загрузить» на строке скачанной модели.
2. Открывается **новый quick chat** на этой модели (эфемерный, в БД не пишется).
3. Если модель уже прогрета — открывается мгновенно. Если другая — cross-model dialog в fashion US-4.
4. Пользователь может попробовать модель в quick chat, после закрытия всё стирается.

### US-13. Повреждённая БД на старте

1. `SanctumApplication.onCreate` пытается открыть `sanctum.db` → `Room.databaseBuilder.build()` кидает exception (corrupt файл / schema-mismatch).
2. Graceful recovery: файл переименовывается в `sanctum.db.corrupt_{YYYYMMDD-HHmmss}`, создаётся свежая пустая БД, `ErrorLog.e("history-read", "db open failed — renamed, new empty created", cause)`.
3. Приложение стартует с пустой историей.
4. На главном экране **одноразовый** (до закрытия app) баннер:

   > ⚠ **Прошлая история оказалась повреждена. Старая база сохранена.**
   > [Сохранить лог] [Закрыть]

5. «Сохранить лог» → SAF share-sheet (Phase 2.5 `LogExportManager`) → пользователь шлёт мне. «Закрыть» → баннер исчезает до следующего запуска.

## Критерии приёмки

### Персистентность и навигация

- [ ] **AC-P1.** Закрытие приложения (swipe-away, kill, reboot) и последующий запуск сохраняют persistent-чаты с полной историей сообщений и вложений.
- [ ] **AC-P2.** Quick chat не оставляет следов: после leave-screen или process-kill `filesDir/quick/` пуст, Room rows по этой сессии отсутствуют.
- [ ] **AC-P3.** Start destination приложения — новый home hub (не Model Manager как в Phase 2).
- [ ] **AC-P4.** Drawer открывается свайпом вправо и тапом hamburger; persistent-чаты отсортированы по `last_message_at DESC`.
- [ ] **AC-P5.** Nav route persistent-чата — `chat/{chatId}`; quick chat имеет отдельный route (например, `chat/quick`).
- [ ] **AC-P6.** Пустой черновик persistent-чата (drawer «Новый чат» → открыт, но первое сообщение не отправлено) не создаёт строку в Room.

### Движок и warmup

- [ ] **AC-E1.** `SanctumApplication.onCreate` запускает warmup default-модели в фоне **после** `installCrashHandler()` и **внутри** `getProcessName() == packageName` guard'а.
- [ ] **AC-E2.** Переход между двумя persistent-чатами на активной модели в одной сессии — без reinit (мгновенно, Send доступен сразу).
- [ ] **AC-E3.** При переходе на чат с `model_id != активная` — cross-model dialog **до** навигации; «Отмена» остаётся в drawer, «Переключить» навигирует и запускает reinit в фоне.
- [ ] **AC-E4.** ChatScreen открывается мгновенно даже если engine не готов; Send disabled, текст можно набирать.
- [ ] **AC-E5.** При закрытии процесса (любым способом) engine теряется; при запуске warmup начинается заново.
- [ ] **AC-E6.** `ChatViewModel.onCleared()` НЕ вызывает `registry.cleanup(modelName)`.
- [ ] **AC-E7.** В черновом persistent-чате (до первого Send) model picker в TopAppBar активен; после первого Send — read-only label.

### Room / хранение

- [ ] **AC-R1.** USER сообщение пишется в Room синхронно до вызова движка (в том же transaction с записью файлов вложений).
- [ ] **AC-R2.** ASSISTANT сообщение пишется в Room **только** на `done=true`, не по токенам.
- [ ] **AC-R3.** Процесс убит посреди стрима → USER в Room, ASSISTANT потерян. Пользователь при следующем открытии видит свой вопрос без ответа и может перезадать.
- [ ] **AC-R4.** `chats.project_id` nullable в v1 schema.
- [ ] **AC-R5.** `app/schemas/app.sanctum.machina.data.SanctumDatabase/1.json` закоммичен в git.
- [ ] **AC-R6.** `messages.chat_id` FK с `ON DELETE CASCADE` работает (instrumented test).
- [ ] **AC-R7.** Room v1 schema НЕ содержит таблицы `project_files` (Phase 4 добавит через `Migration(1,2)`).
- [ ] **AC-R8.** На старте новой версии (первый запуск Phase 3): одноразовая миграция DataStore ключей `PerModelSettings` из `Model.name` в `Model.modelId`. Orphan-ключи (нет соответствия в allowlist) удаляются с `ErrorLog.e("settings-io", "orphan key …", ...)`.

### Вложения

- [ ] **AC-A1.** При Send persistent-чата вложения атомарно пишутся в `filesDir/attachments/{chatId}/`; путь хранится в `messages.image_path` / `messages.audio_path`.
- [ ] **AC-A2.** Delete chat удаляет Room rows И всю директорию `filesDir/attachments/{chatId}/`.
- [ ] **AC-A3.** `filesDir/quick/` очищается в `SanctumApplication.onCreate` на каждом старте.
- [ ] **AC-A4.** Disk full / IOException при записи вложений — атомарный отказ: Send блокируется, USER не пишется в Room, `_attachments` остаются для retry, Snackbar «Не удалось сохранить вложение» / «Недостаточно места», `ErrorLog.e("attachment-save", ...)`.
- [ ] **AC-A5.** Вложение на диске битое/отсутствующее при открытии старого чата → бабл показывает placeholder «Вложение недоступно», `ErrorLog.e("attachment-read", ...)`.

### UX affordances

- [ ] **AC-U1.** Rename через long-press → диалог, 60-char лимит, пустое имя восстанавливает auto-title.
- [ ] **AC-U2.** Auto-title — 20 символов первого USER-сообщения, trim → collapse whitespace → cut по последнему пробелу ≤ 20 → «…» если обрезано; fallback «Чат от DD.MM HH:mm» если первое сообщение без текста. Auto-title запускается только для чатов без ручного имени.
- [ ] **AC-U3.** Delete через swipe влево на строке drawer → confirmation dialog с названием и количеством сообщений → CASCADE + файлы + popBackStack если открыт.
- [ ] **AC-U4.** Кнопка Send в ChatScreen остаётся видимой над клавиатурой на всех состояниях IME.
- [ ] **AC-U5.** Quick chat визуально отличим от persistent — incognito-иконка в TopAppBar + подзаголовок «Быстрый чат».

### Диагностика (включая Phase 2.5 follow-up)

- [ ] **AC-D1.** `DeviceInfoCollector` выдаёт реальные `active_model_id` и `downloaded_models` из `ModelRegistry` (не stubbed).
- [ ] **AC-D2.** Ошибка инициализации модели (JVM exception или негативный статус) пишется в `errors.log` через `ErrorLog.e("model", description, cause)`.
- [ ] **AC-D3.** При сбое init вместо чёрного экрана — Snackbar с читаемым текстом ошибки (кодом).
- [ ] **AC-D4.** Все новые failure-path в коде Phase 3 (warmup, Room DAO read/write, attachment save/read) вызывают `ErrorLog.e(...)` с соответствующим компонентом. Whitelist `ErrorLog.ALLOWED_COMPONENTS` расширяется: `"model"`, `"engine-warmup"`, `"history-read"`, `"history-write"`, `"attachment-save"`, `"attachment-read"`.
- [ ] **AC-D5.** При повреждении БД на старте: graceful recovery (rename + empty new DB), одноразовый баннер на home hub с кнопками «Сохранить лог» и «Закрыть».

### Defaults и первый запуск

- [ ] **AC-F1.** `AppSettings.proto` содержит `optional string default_model_id` и `optional string last_used_model_id`. Proto3-optional, нет wire-breakage.
- [ ] **AC-F2.** Первый запуск без скачанных моделей → главный экран placeholder + кнопка в Model Manager; drawer «Новый чат» disabled.
- [ ] **AC-F3.** После первого успешного скачивания любой модели она автоматически становится `default_model_id`.
- [ ] **AC-F4.** Model Manager «Загрузить» открывает новый quick chat на этой модели (cross-model dialog если другая сейчас прогрета).

### Регрессия (Phase 2 + Phase 2.5)

- [ ] **AC-G1.** Мультимодальный ввод (фото / камера / аудио), streaming ответа, thinking-блок, settings sheet работают без изменений.
- [ ] **AC-G2.** AboutScreen + раздел «Диагностика» Phase 2.5 работает; 7-tap жест для test-crash работает.
- [ ] **AC-G3.** CrashHandler Phase 2.5 работает: uncaught exception → CrashReportActivity → SAF → Toast; `RestartCrashBanner` на Model Manager работает. Phase 3 не ломает ни одного механизма Phase 2.5.
- [ ] **AC-G4.** Phase 1 регрессия: текстовый чат на Gemma-4-E2B-it работает со скоростью не хуже Gallery (TTFT не регрессировал).

## Ограничения

### Вне области Phase 3

- **Проекты как сущность + UI** — Phase 4. В Phase 3 persistent-чат standalone, `chats.project_id` nullable.
- **RAG / `project_files` / embedding** — Phase 4. Таблица `project_files` **не** создаётся в v1-схеме; Phase 4 добавит через `Migration(1,2)`.
- **Foreground service для удержания движка между сессиями** — отвергнуто: постоянное уведомление, 2–5 ГБ RAM всегда занято, UX-hostile.
- **Model switching mid-chat** (смена `chats.model_id` после первого сообщения) — deferred per `patterns.md`. В Phase 3 разрешён выбор модели только в черновом состоянии (до первого Send); после — чат залочен на свою модель.
- **Cloud sync, account system, telemetry** — манифест.
- **Экспорт/импорт истории, поиск по чатам, выборочное удаление сообщений, multi-select / batch delete** — post-MVP, предположительно Phase 5.
- **Внешнее/shared-storage хранение модельных файлов + авто-обнаружение** — пользователь пока не определился, deferred.
- **Ручная кнопка «Выгрузить модель» в Model Manager** — не нужна. Хочешь освободить RAM — закрой приложение (swipe-away).
- **Держать две модели в RAM одновременно** — запрещено: LiteRT-LM не рассчитан, Android LMK, тепло, батарея.

### Архитектурные ограничения

- **Room 2.7.x**, SQLite, единая БД `sanctum.db` в `filesDir`.
- **DAO — suspend / Flow, никаких blocking-методов.**
- **`chats.project_id` nullable** в v1 (Phase 4 может пересмотреть с миграцией).
- **Schema JSON** экспортируется в `app/schemas/` и коммитится в git.
- **Instrumented DAO-тесты** через `connectedAndroidTest` на Honor 200 — требуется подключённое устройство для гейта перед merge в main.
- **Вложения** как файлы (PNG/WAV), не BLOB в БД. Пути в `messages.image_path` / `messages.audio_path`.
- **Один engine в RAM** за раз. Engine всегда погибает вместе с процессом.
- **Truncation при переполнении context window** — hard-cut oldest-first (MVP per `patterns.md`).
- **Стрим пишется в Room только на `done=true`**, не по токенам. Прерванный стрим = USER персист, ASSISTANT потерян.

### Интеграция с Phase 2.5

- Warmup в `SanctumApplication.onCreate` идёт **после** `installCrashHandler()`, **внутри** process-name guard'а — чтобы warmup-exceptions ловились обработчиком, а `:crash` процесс не пытался прогревать движок.
- Новый `:app`-level Hilt модуль (AppModule для Room) по паттерну `LogExportModule` (Phase 2.5 прецедент).
- `RestartCrashBanner` на `ModelManagerScreen` остаётся на месте; Model Manager доступен из drawer-entry.
- Все новые failure-paths Phase 3 пишут через `ErrorLog.e(component, ...)` → `errors.log` → подхватывается `LogExportManager`-экспортом из Phase 2.5.

## Риски

- **Риск:** `ChatViewModel.onCleared()` в коде Phase 2 unconditionally вызывает `registry.cleanup(modelName)`. С Application-scope warmup это уничтожит прогретый движок при первом же Back из чата. **Митигация:** убрать `cleanup` из `onCleared` совсем; движок выгружается только при cross-model dialog + OK, либо при смерти процесса. Acceptance: AC-E6.

- **Риск:** drift `Model.name` vs `Model.modelId` из Phase 2 (NB-1). Room будет хранить `chats.model_id` как стабильный HF-id, а `PerModelSettings` DataStore сегодня keyed по `Model.name`. Без миграции — настройки будут искаться по неправильному ключу, применятся дефолты. Тихий баг. **Митигация:** одноразовая миграция при первом запуске Phase 3: читаем старый map, перекладываем по `modelId`, orphan-ключи дропаем с `ErrorLog`. Acceptance: AC-R8.

- **Риск:** наивная `Flow<List<Message>> = dao.observeByChat(chatId)` на потоке 10–50 токенов/сек приведёт к тому что Room re-emit'ит весь список на каждой записи → I/O-bound, UI тормозит. **Митигация:** in-memory `StateFlow<Message?>` для streaming assistant-бабла + Room-запись только на `done=true`; UI combines two flows. Acceptance: AC-R2.

- **Риск:** Room DB corruption на старте (внезапный power-off во время write, schema-mismatch, bit-rot) → приложение не открывается вообще. **Митигация:** graceful recovery — rename повреждённого файла в `sanctum.db.corrupt_{ts}`, создание свежей пустой БД, одноразовый баннер с возможностью выгрузить лог. Acceptance: AC-D5.

- **Риск:** `Bitmap.compress(PNG, 100, ...)` при записи вложения на диск занимает 200–600 ms на 1024×1024 фото на Honor 200 (проверено в Phase 2 на стороне inference). Если жмём на Main — Choreographer фиксирует skipped frames. **Митигация:** запись вложений происходит в `Dispatchers.IO`, USER message идёт в Room только после успешной записи всех файлов (атомарность).

- **Риск:** warmup на `SanctumApplication.onCreate` на слабом устройстве может падать (и падать повторно). Если fail silent — пользователь опять получает чёрный экран. **Митигация:** `ErrorLog.e("engine-warmup", ...)`, на ChatScreen — явный red-state в TopAppBar + disabled Send + отсылка в Model Manager. US-9 покрывает обёртку try/catch. Acceptance: AC-D2, AC-D3, AC-D4.

- **Риск:** drawer с тысячами сообщений в одном чате → `MessageDao.observeByChat(chatId)` отдаёт весь список → `LazyColumn` re-diff'ит на каждой insert'е. **Митигация:** MVP — принимаем естественную границу context window (32K токенов ≈ ~150 сообщений), которая физически ограничивает глубину чатов. Paging добавим в Phase 5 если станет больно.

- **Риск:** пользователь удалил чат, пока он генерировал ответ → поздний callback `onMessage` может попытаться записать в уже удалённую строку. **Митигация:** `helper.stopResponse(model)` **перед** CASCADE DELETE; VM игнорирует callbacks с устаревшим `chatId`.

## Технические решения

- **Мы решили** использовать Room 2.7.x (не 3.0), потому что 3.0 alpha на момент планирования; миграция 2.7 → 3.0 сделаем позже.
- **Мы решили** не хранить engine в foreground service, потому что постоянное уведомление + 2–5 ГБ RAM — хуже чем 20–30 сек warmup раз в день.
- **Мы решили** прогревать default/last-used модель в `SanctumApplication.onCreate`, потому что это единственный способ спрятать init-latency за UX без foreground service.
- **Мы решили** убрать `registry.cleanup` из `ChatViewModel.onCleared`, потому что Application-scope engine иначе уничтожается при первом же Back.
- **Мы решили** записывать ASSISTANT-сообщение в Room только на `done=true`, потому что на 10–50 Hz запись на токен даёт Flow-backpressure и I/O-bound UI.
- **Мы решили** сохранять только завершённые сообщения в БД, потому что partial-interrupted rows создают path-complexity (нужен флаг, resume-semantics, UI отличие), а процесс-kill — редкое событие.
- **Мы решили** хранить вложения как файлы в `filesDir/attachments/{chatId}/`, а не BLOB в Room, потому что Bitmap/PCM большие, Room не про бинари, FS cheap.
- **Мы решили** сделать `chats.project_id` nullable в Phase 3, потому что проекты появятся в Phase 4 и не хочется костылить фиктивный «Inbox»-проект.
- **Мы решили** не создавать стаб-таблицу `project_files` в v1, потому что Migration(1,2) в Phase 4 проще и чище, чем мёртвая таблица в схеме.
- **Мы решили** разрешать смену модели только в черновом persistent-чате (до первого Send), потому что mid-chat switch нарушает `patterns.md` business rule «один model_id на lifetime», а choice перед началом — естественная часть создания чата.
- **Мы решили** использовать auto-title из первых 20 символов USER-сообщения без LLM-суммаризации, потому что LLM-вызов добавляет 3–10 сек latency на и без того загруженный inference.
- **Мы решили** мигрировать `PerModelSettings` DataStore-ключи с `Model.name` на `Model.modelId` одноразовым rewrite'ом при первом запуске Phase 3, потому что Room использует стабильный `modelId` и двойная key-system породит тихие баги.
- **Мы решили** покрывать все новые failure-paths через `ErrorLog.e(...)`, потому что Phase 2.5 `LogExportManager` уже агрегирует `errors.log` в экспорт для тестеров.

## Тестирование

**Unit-тесты:** делаются всегда, не обсуждаются.

- Target: `ChatRepository` (обёртка над DAO, мокаем DAO), `EngineCoordinator` (жизненный цикл singleton engine, warmup orchestration, cross-model reinit gating), `AutoTitleGenerator` (pure func над строкой), `EffectiveConfig` migration helper (Model.name → Model.modelId mapper + orphan-key handling), attachment file writer (с mock `File`).
- Robolectric там где нужен Context (прецедент Phase 2).

**Интеграционные тесты:** делаем для Room — instrumented DAO-тесты через `./gradlew :app:connectedAndroidTest` на Honor 200. Покрываем:
- CRUD каждого DAO (`ChatDao`, `MessageDao`): insert / query by id / update / delete.
- FK cascade: удаление `chats` row → удаляются все `messages` с этим `chat_id`.
- Flow emission: `observeByChat(chatId)` реагирует на insert/update.
- Migration test (пока единственная возможная — v1 как свежая БД; при Phase 4 добавится тест Migration(1,2)).
- Schema JSON snapshot — проверка что генерируемый `app/schemas/...SanctumDatabase/1.json` закоммичен и соответствует Entity.

**E2E тесты:** не делаем. Compose UI-тесты прецедентно out-of-scope в Phase 2; end-to-end multimodal inference проверяется manual smoke на Honor 200 (нет модели в CI).

## Как проверить

### Агент проверяет

| Шаг | Инструмент | Ожидаемый результат |
|-----|-----------|-------------------|
| 1. Сборка проекта | `./gradlew build` | `BUILD SUCCESSFUL` |
| 2. Юнит-тесты `:app` | `./gradlew :app:testDebugUnitTest` | Все тесты зелёные, включая новые Phase-3 (ChatRepository, EngineCoordinator, AutoTitleGenerator, etc.) |
| 3. Юнит-тесты `:core-runtime` | `./gradlew :core-runtime:testDebugUnitTest` | Зелёно; включая новые `ErrorLog` тесты на расширенный whitelist |
| 4. Юнит-тесты `:core-settings` | `./gradlew :core-settings:testDebugUnitTest` | Зелёно; включая тест proto-миграции `default_model_id` / `last_used_model_id` |
| 5. Instrumented DAO (устройство) | `./gradlew :app:connectedAndroidTest` | Все DAO-тесты зелёные; cascade-тест прошёл; schema JSON существует |
| 6. Lint | `./gradlew lintDebug` | Нет критичных ошибок |
| 7. Schema snapshot | `ls app/schemas/app.sanctum.machina.data.SanctumDatabase/1.json` | Файл присутствует в коммите |
| 8. ErrorLog whitelist | `grep -En "\"model\"\|\"engine-warmup\"\|\"history-read\"\|\"history-write\"\|\"attachment-save\"\|\"attachment-read\"" core-runtime/.../ErrorLog.kt` | Все 6 компонентов в `ALLOWED_COMPONENTS` |
| 9. Proto изменение | `grep -En "default_model_id\|last_used_model_id" core-settings/.../app_settings.proto` | Оба поля присутствуют, `optional` |
| 10. Privacy manifest без регрессий | `aapt dump xmltree app-debug.apk AndroidManifest.xml` | `allowBackup=false`, `dataExtractionRules=@xml/data_extraction_rules` без изменений относительно Phase 2 |
| 11. Debug APK | `./gradlew :app:assembleDebug` | `app-debug.apk` появился в `app/build/outputs/apk/debug/` |

### Пользователь проверяет

- **AC-P1 (персистентность через kill):** пошлю сообщение в persistent-чате → swipe-away из переключателя задач → открою снова → чат на месте с историей.
- **AC-P2 (quick chat инкогнито):** открою quick chat → прикреплю фото → leave screen → через «О программе» → «Сохранить лог» выгружу .txt → проверю что в секции device-info / logcat нет следов quick-chat контента (сам файл проверить содержимое не могу — но `filesDir/quick/` должна быть пустой).
- **AC-E2 (мгновенный switch same-model):** открою 3 persistent-чата подряд на одной прогретой модели → между вторым и третьим задержки 0.
- **AC-E3 + US-4 (cross-model):** открою чат на E2B (пока активна E4B) → увижу диалог до открытия → «Переключить» → ChatScreen открывается мгновенно, история есть, спиннер в TopAppBar, Send disabled → через 20–30 сек всё готово.
- **AC-E7 + US-2 (model picker в черновом):** drawer → «Новый чат» → тапну имя модели в TopAppBar → выберу другую из dropdown → cross-model dialog → ok → reinit → отправлю первое сообщение → попробую снова тапнуть имя модели — теперь это label, не кликается.
- **US-5 (cold start):** kill приложения → запуск → drawer сразу доступен → тапну вчерашний чат → история видна моментально, warmup ещё идёт в фоне, Send активируется через секунды (а не через 30 сек как в Phase 2).
- **US-6, US-7 (rename / delete):** long-press → rename → dialog → save → название обновилось. Swipe влево → Удалить → confirmation → чат исчез из drawer.
- **US-8 (первый запуск без моделей):** проверить на чистом устройстве / после очистки данных.
- **US-9 (silent init failure) — на realme RMX3085:** установить APK → попытаться открыть Gemma-4-E4B → вместо чёрного экрана увидеть Snackbar с читаемой ошибкой + красный индикатор в TopAppBar → экспортировать лог через «О программе» → увидеть запись `ERROR [model] init failed :: …` в файле.
- **US-10 (diagnostic export):** «О программе» → «Сохранить лог» → открыть .txt → убедиться что `active_model` и `downloaded_models` содержат реальные значения, а не `none`.
- **US-11 (keyboard Send):** тапнуть input field в любом чате → клавиатура появилась → Send остался виден → отправить без сворачивания клавиатуры.
- **AC-A4 (disk full):** искусственно забить диск (копировать большой файл в `filesDir`), попробовать отправить чат с фото → Snackbar, вложения не потеряны, USER не в Room.
- **AC-D5 (corrupt DB):** вручную (через adb shell) испортить `sanctum.db` → запустить приложение → увидеть баннер «Прошлая история оказалась повреждена» → тапнуть «Сохранить лог», проверить что лог выгружается через SAF.
- **AC-G1..G4 (регрессия):** текстовый + мультимодальный чат на Gemma-4-E2B-it и E4B-it, streaming, thinking-блок, settings sheet, About → Диагностика, CrashHandler test-crash через 7-tap — всё работает как в Phase 2.5. Скорость не хуже Gallery.
