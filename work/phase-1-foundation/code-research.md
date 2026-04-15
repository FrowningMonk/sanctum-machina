# Phase 1 Foundation — Code Research

Глубокое исследование `gallery-source/` для планирования user-spec **Phase 1: Foundation** проекта Sanctum Machina (PhoneWrap).

Используются абсолютные пути. Все номера строк — по состоянию gallery-source (clone google-ai-edge/gallery, тег соответствует `versionName = "1.0.11"`, см. `Android/src/app/build.gradle.kts:40`).

---

## 1. HF-token patch location

### Что читает/инъектит токен

**`C:/AI-WORK/PhoneWrap/gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/DownloadWorker.kt`**

- `32` — `import com.google.ai.edge.gallery.data.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN`
- `105` — `val accessToken = inputData.getString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN)`
- `134–137` — сам инжект в Authorization header:

```kotlin
if (accessToken != null) {
  Log.d(TAG, "Using access token: ${accessToken.subSequence(0, 10)}...")
  connection.setRequestProperty("Authorization", "Bearer $accessToken")
}
```

**`C:/AI-WORK/PhoneWrap/gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DownloadRepository.kt`**

- `123–125` — проброс токена в WorkManager input data:

```kotlin
if (model.accessToken != null) {
  inputDataBuilder.putString(KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, model.accessToken)
}
```

**`C:/AI-WORK/PhoneWrap/gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Consts.kt`**

- `33` — `const val KEY_MODEL_DOWNLOAD_ACCESS_TOKEN = "KEY_MODEL_DOWNLOAD_ACCESS_TOKEN"`

**`C:/AI-WORK/PhoneWrap/gallery-source/Android/src/app/src/main/java/com/google/ai/edge/gallery/data/Model.kt`**

- `273` — `var accessToken: String? = null,` (поле в `data class Model`)

### Оценка объёма патча

Реальный патч внутри файлов `:core-runtime` — действительно **~5–8 строк**, если из `Model` убрать поле `accessToken`:

| Файл | Действие | Строк |
|---|---|---|
| `DownloadWorker.kt:32` | удалить import | 1 |
| `DownloadWorker.kt:105` | удалить `val accessToken = …` | 1 |
| `DownloadWorker.kt:134–137` | удалить весь if-блок | 4 |
| `DownloadRepository.kt:123–125` | удалить if-блок | 3 |
| `Consts.kt:33` | удалить константу | 1 |
| `Model.kt:273` | убрать поле `accessToken` из конструктора | 1 |

**Итого: 11 строк удаления.**

Плюс: из `DownloadRepository.kt` нужно удалить `firebaseAnalytics?.logEvent(...)` (lines 175–178, 220–227, 257–263) — это уже не про HF-токен, но сопровождающая зачистка.

### Токен-плumbing глубже DownloadWorker/Repository

Токен НЕ нужен за пределами `:core-runtime`, но в Gallery он идёт глубже через:
- `ui/modelmanager/ModelManagerViewModel.kt:591–596, 692, 708, 751–765, 803–805, 853` — AppAuth OAuth flow (HF login), хранение в DataStore, `curAccessToken`, `saveAccessToken`.
- `ui/home/SettingsDialog.kt:201, 203, 236` — UI для ручного ввода токена.
- `ui/common/DownloadAndTryButton.kt:169–170, 210, 263, 302–309` — проверка, что модель с `huggingface.co`, перехват gated-моделей, перенаправление на OAuth.
- `data/DataStoreRepository.kt:50, 170, 182, 204` — запись/чтение `accessToken/refreshToken/expiresAt` в Proto DataStore.
- `common/ProjectConfig.kt:35–36` — endpoints `https://huggingface.co/oauth/authorize`, `/oauth/token`.

**Важный вывод:** всё это **НЕ попадает в Phase 1** (см. список "DO NOT touch" — AppAuth, Proto DataStore, `ModelManagerViewModel` не портируются). Поэтому в `:core-runtime` патч действительно тривиален — мы просто не тянем те файлы, где токен используется. Внутри тех 13 файлов, что копируются, — 11 строк удаления как показано выше.

Uncertain: `setRequestProperty("Range", ...)` (`DownloadWorker.kt:162–164`) к токену отношения не имеет — это resume-логика, её оставляем.

---

## 2. Transitive dependency graph для extraction set

### Прямые импорты каждого файла

| Файл | Импортирует из Gallery |
|---|---|
| `runtime/LlmModelHelper.kt` | `data.Model` |
| `runtime/ModelHelperExt.kt` | `data.Model`, `data.RuntimeType`, `runtime.aicore.AICoreModelHelper`, `ui.llmchat.LlmChatModelHelper` |
| `ui/llmchat/LlmChatModelHelper.kt` | `common.cleanUpMediapipeTaskErrorMessage`, `data.Accelerator`, `data.ConfigKeys`, `data.DEFAULT_*`, `data.Model`, `runtime.{CleanUpListener, LlmModelHelper, ResultListener}` |
| `data/Model.kt` | (внутренний: использует `Config`, `ConfigKey`, `ValueType`, `convertValueToTargetType`) |
| `data/Config.kt` | `R.string.*` (через `@StringRes`), `data.DEFAULT_*` из `Consts.kt` |
| `data/ConfigValue.kt` | ничего (самодостаточный) |
| `data/Consts.kt` | `data.Accelerator` (из `Types.kt`), `android.os.Build`, `compose.ui.unit.dp` |
| `data/Tasks.kt` | `R.string.chat_*`, `ImageVector`, `MutableState`; объявляет `Task`, `BuiltInTaskId` |
| `data/ModelAllowlist.kt` | `common.isPixel10`, `data.*` (Config, Model, Accelerator, BuiltInTaskId, DEFAULT_*), `SOC` |
| `data/Categories.kt` | `R.string.category_*` |
| `data/Types.kt` | ничего (только `Accelerator` enum) |
| `data/DownloadRepository.kt` | `AppLifecycleProvider`, `GalleryEvent`, `R`, `firebaseAnalytics`, `worker.DownloadWorker`, `data.Model*` |
| `worker/DownloadWorker.kt` | `data.KEY_*`, `data.TMP_FILE_EXT` |

### Транзитивное замыкание: обязательные файлы в `:core-runtime`

| # | Файл (относительно `gallery/`) | LoC | Примечание |
|---|---|---|---|
| 1 | `data/Model.kt` | 375 | рефакторим `Model.instance: Any?` в `ModelRuntimeHandle` |
| 2 | `data/Config.kt` | 340 | можно урезать до ~150 строк (выкинуть `createAICoreConfigs`, `BottomSheetSelectorConfig` — не нужны для LiteRT-LM text-only) |
| 3 | `data/ConfigValue.kt` | 100 | самодостаточный, закомментированный блок можно удалить (останется ~35 LoC) |
| 4 | `data/Consts.kt` | 79 | KEY_*, DEFAULT_*, SOC, TMP_FILE_EXT |
| 5 | `data/Types.kt` | 23 | только enum `Accelerator` |
| 6 | `data/Tasks.kt` | 160 | можно урезать до `BuiltInTaskId.LLM_CHAT` + минимальный `Task` или выкинуть совсем (в Phase 1 нужен только LLM_CHAT id как строка) |
| 7 | `data/Categories.kt` | 45 | нужен `CategoryInfo`, можно упростить |
| 8 | `data/ModelAllowlist.kt` | 230 | **критичный парсер JSON → Model**; убираем ветки `AICORE`, `npuOnly` можно оставить |
| 9 | `data/DownloadRepository.kt` | 343 | -HF-token, -firebaseAnalytics, -sendNotification deep-link (`DOWNLOAD_FROM_GLOBAL_MODEL_MANAGER_TASK_ID` можно оставить или упростить) |
| 10 | `worker/DownloadWorker.kt` | 369 | -HF-token, -hardcoded `"com.google.ai.edge.gallery.MainActivity"` → константа из app-модуля или передаваемый PendingIntent |
| 11 | `runtime/LlmModelHelper.kt` | 121 | as-is (interface) |
| 12 | `runtime/ModelHelperExt.kt` | 30 | УПРОСТИТЬ: выкинуть ветку AICORE, оставить только `LlmChatModelHelper` |
| 13 | `core/inference/LlmChatModelHelper.kt` | 309 | перенос из `ui/llmchat/`, as-is |
| 14 | `common/Utils.kt` | (381) | нужна **только функция** `cleanUpMediapipeTaskErrorMessage` (7 строк) и `isPixel10` (3 строки), остальное не копируем |

**Кроме того — зависимости от Gallery, которые НУЖНО заменить заглушками/убрать:**

- `com.google.ai.edge.gallery.R` — ресурсные id строк (`R.string.chat_generic_agent_name`, `R.string.category_llm`, `R.string.notification_title_*`) — **заменить на строковые литералы** в `:app` или в `:core-runtime` сделать свой `R`.
- `com.google.ai.edge.gallery.firebaseAnalytics` (из `Analytics.kt`) — **удалить** все вызовы.
- `com.google.ai.edge.gallery.GalleryEvent` — удалить.
- `com.google.ai.edge.gallery.AppLifecycleProvider` — **нужен в `:app`** (простой интерфейс из `GalleryLifecycleProvider.kt`, 32 LoC) для `DownloadRepository.sendNotification` (проверка is-in-foreground). Либо выбросить foreground-проверку совсем — она несущественна для Phase 1.

### Ассеты

- `model_allowlists/1_0_11.json` (форк, 209 строк) → `:core-runtime/src/main/assets/model_allowlist.json`.

### Проto/DataStore

- **НЕ нужны в Phase 1**. Gallery использует Proto DataStore для `Settings`, `UserData`, `AccessTokenData`, `BenchmarkResult`, `Skills`, `Cutouts` — всё это для фич, которые мы НЕ портируем. Ни одного `.proto` в `:core-runtime` не тащим.

### Зависимости из `libs.versions.toml` для `:core-runtime`

| Библиотека | Нужна? | Для чего |
|---|---|---|
| `androidx-core-ktx` | ДА | базовый Android |
| `kotlin-reflect` | НЕТ | только для config UI (`createLlmChatConfigs`) |
| `androidx-work-runtime` | ДА | `DownloadWorker` |
| `com-google-code-gson` | ДА | парсинг allowlist JSON (`@SerializedName`, `Gson().fromJson`) |
| `androidx-datastore` | НЕТ | Gallery использует Proto DataStore — пропускаем |
| `protobuf-javalite` | НЕТ | не нужно без Proto DataStore |
| `litertlm` (`com.google.ai.edge.litertlm:litertlm-android:0.10.0`) | ДА | `Engine`, `Conversation`, `Backend`, `Contents` |
| `hilt-android` + `hilt-android-compiler` | ДА | DI для `DownloadRepository`, `ModelRegistry` |
| `firebase-*` | НЕТ | удаляем analytics |
| `openid-appauth` | НЕТ | нет OAuth |
| `mlkit-genai-prompt` | НЕТ | |
| `tflite*` (play-services) | НЕТ | не используются LiteRT-LM фрейм |
| `camerax-*` | НЕТ | |
| `moshi-kotlin` | НЕТ | allowlist через Gson, не Moshi |
| `commonmark`, `richtext` | НЕТ (в `:core-runtime`) | — это для Markdown-рендера в `:app` |
| `kotlinx-serialization-json` | НЕТ | Gallery использует Gson для allowlist |
| `compose-bom`, `material3`, `material-icon-extended`, `navigation` | НЕТ (в `:core-runtime`) | только в `:app` |
| `androidx-security-crypto` | НЕТ | хранит HF-токен шифрованно — не нужен |

### Firebase/Analytics внутри extraction set

**`DownloadRepository.kt`** — 3 вызова `firebaseAnalytics?.logEvent(...)` на lines 175–178, 220–227, 257–263 + `import com.google.ai.edge.gallery.firebaseAnalytics` (неявный). Все удаляются при копировании. **Больше Firebase нигде в extraction set нет** (проверено grep'ом).

### Итоговый манифест `:core-runtime`

```
:core-runtime/src/main/kotlin/.../
  data/
    Model.kt                    (после рефакторинга ~300 LoC)
    Config.kt                   (урезан до ~150 LoC)
    ConfigValue.kt              (~35 LoC)
    Consts.kt                   (~80 LoC)
    Types.kt                    (23 LoC)
    Tasks.kt                    (опц., урезан до ~30 LoC, либо выброшен)
    Categories.kt               (опц., ~20 LoC, либо inline в ModelRegistry)
    ModelAllowlist.kt           (~180 LoC после чистки AICORE)
    DownloadRepository.kt       (~250 LoC после чистки)
  worker/
    DownloadWorker.kt           (~340 LoC после чистки)
  runtime/
    LlmModelHelper.kt           (121 LoC)
    ModelHelperExt.kt           (~15 LoC)
  core/inference/
    LlmChatModelHelper.kt       (309 LoC, перенесено из ui/llmchat)
  common/
    Utils.kt                    (только ~15 LoC — две функции)
:core-runtime/src/main/assets/
  model_allowlist.json          (~210 строк)
```

**Суммарный код в `:core-runtime`: ~1900–2100 LoC Kotlin** (vs 2524 total до чистки) + 210 строк JSON ассета.

---

## 3. Download flow — error scenarios и user-visible states

Анализ `DownloadRepository.kt` + `DownloadWorker.kt`.

### Состояния в `ModelDownloadStatusType` (Model.kt:353–360)

```kotlin
NOT_DOWNLOADED, PARTIALLY_DOWNLOADED, IN_PROGRESS, UNZIPPING, SUCCEEDED, FAILED
```

### Explicit user-visible states

| Состояние | Где устанавливается | Gallery UI |
|---|---|---|
| **ENQUEUED** (WorkManager) | `observerWorkerProgress:171–179` | НЕ показывается отдельно — сохраняется только `startTime` в SharedPreferences + firebase-log. Для пользователя ничего не меняется до первого `RUNNING`. |
| **IN_PROGRESS** | `observerWorkerProgress:181–199` | progress bar + `receivedBytes / totalBytes`, скорость (`bytesPerSecond`), оставшееся время (`remainingMs`). Обновляется каждые 200 ms (`DownloadWorker:209`). Также foreground-нотификация с % (`DownloadWorker:238–243`). |
| **UNZIPPING** | `DownloadWorker:263` + `observerWorkerProgress:200–205` | `ModelDownloadStatus(status=UNZIPPING)` — отдельная метка "распаковываем". В Phase 1 **не актуально** (bundled `.litertlm` — не zip). |
| **SUCCEEDED** | `observerWorkerProgress:208–229` | Локальная notification "Downloading finished" (в фоне), обновление статуса, Firebase success-event. |
| **FAILED** | `observerWorkerProgress:231–266` | Локальная notification "Download failed", статус `FAILED` + `errorMessage`. |
| **CANCELLED** | `observerWorkerProgress:239–241` | Трактуется как `NOT_DOWNLOADED` (не `FAILED`!). Уведомления нет. |

### Failure paths в `DownloadWorker.doWork`

| № | Место | Причина | Видимость |
|---|---|---|---|
| 1 | `:108` если `fileUrl == null \|\| fileName == null` | баг в Repository (не должно случаться) | `Result.failure()` без сообщения — UI покажет пустой `errorMessage` |
| 2 | `:192` `throw IOException("HTTP error code: ${responseCode}")` | сервер вернул не-200/206 (404 если файл удалён, 401/403 если gated — для анонимного доступа к community-repo быть не должно, но возможно при временной блокировке HF) | `FAILED` + `errorMessage = "HTTP error code: 404"` |
| 3 | `:195+ inputStream.read(...)` / сетевая операция | **любой `IOException`**: network drop, timeout, DNS fail, TLS fail | Ловится в `catch (e: IOException)` на `:313–318`. `errorMessage = e.message`. Ресурсы (`outputStream`, `inputStream`) **не закрываются** в catch — потенциальный leak, но не фатально. |
| 4 | `:196 FileOutputStream(..., true)` | Disk full / permission denied | Выбрасывается `IOException` / `FileNotFoundException` → `FAILED`. |
| 5 | `:258 outputTmpFile.renameTo(originalFile)` | возвращает `false` при ошибке ФС | **НЕ обрабатывается!** — молча продолжит и финальный файл окажется под именем `*.gallerytmp`. UI покажет `SUCCEEDED`, но файл будет битый. Это **тонкий баг** Gallery. |
| 6 | OS kill foreground service (low memory / user swipe) | `WorkManager` пометит как `CANCELLED` | UI получит `NOT_DOWNLOADED`. Файл `*.gallerytmp` останется в `externalFilesDir`. При повторном запуске downloader'а — **работает resume через `Range: bytes=N-`** (`DownloadWorker:156–165`). |
| 7 | User cancellation (UI кнопка) | `cancelDownloadModel` → `workManager.cancelAllWorkByTag` | `CANCELLED` → `NOT_DOWNLOADED`. `.gallerytmp` остаётся. Resume при следующей попытке. |
| 8 | Checksum / sha256 verification | **НЕТ** | Gallery **не проверяет** целостность файла. Повреждённый файл попадёт в inference → упадёт `Engine(engineConfig).initialize()` с невнятной ошибкой. |

### Что делает пользователь при FAILED

Gallery:
- Показывает `errorMessage` в UI карточки модели.
- Дальше — только кнопка Download опять. Нет авто-retry, нет диагностики ("check internet").
- Foreground-notification исчезает.

### Что нужно Phase 1 (acceptance criteria)

1. Состояния для UI: **NOT_DOWNLOADED / IN_PROGRESS (+ receivedBytes, totalBytes) / SUCCEEDED / FAILED (+errorMessage)**. `UNZIPPING` и `PARTIALLY_DOWNLOADED` можно не показывать отдельно (для bundled-JSON модели всегда одна стадия).
2. Пользователь видит: название модели, прогресс (XX MB / YY MB, XX%), скорость (опц.), ошибку текстом при FAILED.
3. Кнопки: Download / Cancel / (при FAILED) Retry = повторный Download.
4. Resume работает автоматически через `.gallerytmp` + `Range` header — это тянем из Gallery as-is.
5. **Без checksum**: в Phase 1 — ок; отмечаем риск для Phase 2+.
6. **Gemma-4-E2B-it ~2.4 GB**: на честном 4G-LTE ~10–20 минут. Foreground-notification обязательна иначе Android убьёт воркер.

### Риски не покрытые Gallery (важно для Phase 1 UX)

- Disk full — получаем `FAILED` с `IOException`, но пользователь не поймёт причину без текста.
- Partial download после reboot — `.gallerytmp` живёт в `externalFilesDir`, но запись о starte потерялась (SharedPreferences на `download_start_time_ms` всё ещё помнит). Нужно думать, что показывать: "возобновить?" vs "начать заново".
- Модель уже скачана — Gallery смотрит наличие файла по `Model.getPath(context)` и помечает как SUCCEEDED. В `:core-runtime` надо **самим** реализовать эту проверку при инициализации `ModelRegistry`.

---

## 4. Inference flow — error scenarios и user-visible states

Анализ `LlmChatModelHelper.kt` (309 LoC).

### `initialize(...)` (строки 60–150)

Failure modes:

| № | Строка | Причина | Как обрабатывается |
|---|---|---|---|
| 1 | `:105 model.getPath(context)` | пустой / неверный путь (модель не скачана, imported=false, override=empty) | Возвращает путь, файл которого может не существовать. `Engine()` упадёт ниже. |
| 2 | `:121 Engine(engineConfig)` | **native crash**: файл не существует, неправильный формат, corrupt `.litertlm`, несовместимая версия LiteRT-LM | `catch (e: Exception)` на `:145` → `onDone(cleanUpMediapipeTaskErrorMessage(e.message))`. Сообщение возвращается как **первый аргумент `onDone`** (вместо пустой строки при успехе). |
| 3 | `:122 engine.initialize()` | native init fail — GPU driver missing (`libOpenCL.so`), NPU не доступен на устройстве, OOM на модель-load (~2.5–5 GB для Gemma-4 E2B/E4B) | Тот же catch → string error via `onDone`. |
| 4 | `:127 engine.createConversation(...)` | invalid samplerConfig, некорректный system instruction | Тот же catch. |
| 5 | Race: второй вызов `initialize` до cleanup | `model.instance` перезапишется, старый `Engine` остаётся в памяти без close → утечка native-ресурсов (сотни MB) | **НЕ обрабатывается** Gallery. Phase 1: обязан гарантировать один `initialize` за раз (locking в `ModelRegistry`). |
| 6 | `model.initializing` флаг (`Model.kt:267`) | используется снаружи в `ModelManagerViewModel` (которого мы НЕ копируем) | Phase 1: реализовать свой interlock. |

Gallery surfacing: `onDone("")` = success, `onDone("Error message")` = fail. Строка попадает в `LlmChatViewModel.initModel(...)` и показывается как сообщение об ошибке в чате. Функция `cleanUpMediapipeTaskErrorMessage` обрезает нативный stack trace до первой строки `=== Source Location Trace` (`common/Utils.kt:57–63`) — получаем читабельный текст типа `"Failed to load model: file not found"`.

### `runInference(...)` (строки 243–302)

Failure modes:

| № | Строка | Причина | Обработка |
|---|---|---|---|
| 1 | `:254–258` | `model.instance == null` (инициализация не прошла / уже cleanup) | `onError("LlmModelInstance is not initialized.")` — callback в UI |
| 2 | `MessageCallback.onError` `:290–298` | **CancellationException** (пользователь нажал Stop → `stopResponse` → `conversation.cancelProcess()` на `:240`) | `Log.i(... cancelled)` + `resultListener("", true, null)` — stream помечается `done=true`. Для пользователя это штатная остановка, не ошибка. |
| 3 | `MessageCallback.onError` прочее | OOM во время генерации, native crash, context window exceeded, прерывание при NPU fall-over | `Log.e + onError("Error: ${throwable.message}")` — callback в UI. |
| 4 | Context window overflow | при генерации длинного ответа или длинного prompt | В LiteRT-LM обычно бросается как throwable с сообщением "context exhausted" или аналогичным. Gallery не делает prechecks длины — просто отдаёт на uplink. |
| 5 | Stream stall (модель зависла) | таймаута нет | **Нет watchdog**. Пользователь должен руками нажать Stop. В Phase 1 на Honor 200 реально возможно (особенно GPU cold-start). |
| 6 | Multiple `runInference` без ожидания | `cleanUpListeners[model.name] = cleanUpListener` перезапишется (`:261–263`) — это map, не conflict detection. `sendMessageAsync` будет вызван поверх занятой conversation → native-level конфликт. | Не обрабатывается. Phase 1: блокируем кнопку send пока `inProgress`. |

### `cleanUp(...)` (строки 209–236)

| Edge case | Поведение |
|---|---|
| `model.instance == null` | early return (`:210–212`), no-op — безопасно |
| `conversation.close()` бросает | log + продолжить (`:217–220`) |
| `engine.close()` бросает | log + продолжить (`:222–226`) |
| Двойной cleanUp | Второй вызов — no-op (instance = null после первого) |
| cleanUp во время активного inference | `stopResponse` **не вызывается автоматически**. Nативный `Conversation.close()` на лету — **риск SIGSEGV**. Phase 1: явно звать `stopResponse` перед `cleanUp`. |
| Leak listener | `cleanUpListeners.remove(model.name)` — ок, но если модель называется одинаково для разных сессий — можно потерять listener. В Phase 1 одна модель активна → ок. |

### Gallery surfacing (что видит пользователь)

- Ошибка `initialize` → в `LlmChatViewModel` появляется `ChatMessageInfo` / `Warning` с текстом. В Phase 1 делаем то же: сообщение в чате "Failed to load model: …" с кнопкой Retry.
- Ошибка `runInference` → Toast **нет**. В Gallery — просто streamed-сообщение обрывается на middle, выводится `onError` message как новое сообщение. **В Phase 1 лучше**: inline-ошибка под последним user-сообщением.
- Cancellation → visually streamed output просто замораживается, done=true.

### Что важно для Phase 1 на Honor 200

1. Gemma-4-E2B-it (2.4 GB `.litertlm`, fp4) при backend=GPU на Adreno-подобном GPU: **initialize может занять 5–30 секунд**. Нужен loader UI.
2. **Нет NPU на Honor 200** (Mediatek Dimensity 9010 без поддерживаемого LiteRT NPU-backend) — попытка `Accelerator.NPU` → fail в `Engine.initialize()`. В Phase 1 **не предлагать NPU** в UI (захардкодить GPU/CPU).
3. OOM при загрузке: 12 GB RAM на Honor 200 → модель 2.4 GB помещается, но Android OS может вытеснять в swap/zram. Риск umano.
4. Cancellation должна работать: кнопка Stop → `stopResponse`. Проверить, что native-отмена доходит.

---

## 5. Allowlist JSON structure

### Расположение

- Канонический источник: `C:/AI-WORK/PhoneWrap/gallery-source/model_allowlists/1_0_11.json` (209 строк).
- Gallery runtime **fetches** allowlist с GitHub через URL в коде (был в `ModelManagerViewModel`) — мы этот механизм **отключаем** и **bundled** версию 1_0_11.json кладём в `:core-runtime/src/main/assets/model_allowlist.json`.

### Schema (из `ModelAllowlist.kt`)

Корень (класс `ModelAllowlist`, строки 227–230):

```json
{
  "models": [ AllowedModel, ... ],
  "aicoreRequirements": { ... }  // optional, выкидываем
}
```

`AllowedModel` (строки 45–68, имя @SerializedName следует как в JSON):

| Поле | Тип | Обязательное | Нужно в Phase 1? |
|---|---|---|---|
| `name` | String | ДА | ДА |
| `modelId` | String | ДА | ДА (для `learnMoreUrl`) |
| `modelFile` | String | ДА | ДА |
| `commitHash` | String | ДА | ДА (в URL) |
| `description` | String | ДА | ДА |
| `sizeInBytes` | Long | ДА | ДА (показываем до download) |
| `defaultConfig` | DefaultConfig | ДА | ДА (topK/topP/temperature/maxTokens) |
| `taskTypes` | List<String> | ДА | частично (фильтруем `llm_chat`) |
| `disabled` | Boolean? | нет | нет |
| `llmSupportImage` | Boolean? | нет | **НЕТ** (text-only в Phase 1) |
| `llmSupportAudio` | Boolean? | нет | **НЕТ** |
| `llmSupportTinyGarden` | Boolean? | нет | НЕТ |
| `llmSupportMobileActions` | Boolean? | нет | НЕТ |
| `llmSupportThinking` | Boolean? | нет | можно оставить, но не обязательно |
| `minDeviceMemoryInGb` | Int? | нет | желательно (honor 200 = 12GB) |
| `bestForTaskTypes` | List<String>? | нет | НЕТ |
| `localModelFilePathOverride` | String? | нет | НЕТ |
| `url` | String? | нет | да (если указан — используется вместо HF URL) |
| `socToModelFiles` | Map<String, SocModelFile>? | нет | НЕТ (per-SoC варианты только Pixel 10) |
| `runtimeType` | RuntimeType? | нет | да (фильтровать на `litert_lm`) |
| `aicoreReleaseStage` | enum? | нет | НЕТ (AICORE не портируем) |
| `aicorePreference` | enum? | нет | НЕТ |

`DefaultConfig` (строки 26–34):

```
topK: Int?, topP: Float?, temperature: Float?,
accelerators: String? (csv "gpu,cpu"), visionAccelerator: String?,
maxContextLength: Int?, maxTokens: Int?
```

### URL download-конструирование

`AllowedModel.toModel()` на `:74`:
```kotlin
var downloadUrl = url ?: "https://huggingface.co/$modelId/resolve/$commitHash/$modelFile?download=true"
```

Это даёт **анонимный** HF download URL — community-repos (`litert-community/*`, `google/*-litert-lm`) открыты, токен не нужен.

### Sample entry: Gemma-4-E2B-it

Из `1_0_11.json:3–37`:

```json
{
  "name": "Gemma-4-E2B-it",
  "modelId": "litert-community/gemma-4-E2B-it-litert-lm",
  "modelFile": "gemma-4-E2B-it.litertlm",
  "description": "A variant of Gemma 4 E2B ready for deployment on Android using [LiteRT-LM]...",
  "sizeInBytes": 2583085056,
  "minDeviceMemoryInGb": 8,
  "commitHash": "7fa1d78473894f7e736a21d920c3aa80f950c0db",
  "llmSupportImage": true,
  "llmSupportAudio": true,
  "llmSupportThinking": true,
  "defaultConfig": {
    "topK": 64,
    "topP": 0.95,
    "temperature": 1.0,
    "maxContextLength": 32000,
    "maxTokens": 4000,
    "accelerators": "gpu,cpu",
    "visionAccelerator": "gpu"
  },
  "taskTypes": ["llm_chat", "llm_prompt_lab", "llm_agent_chat", "llm_ask_image", "llm_ask_audio"],
  "bestForTaskTypes": ["llm_chat", "llm_prompt_lab", "llm_agent_chat", "llm_ask_image", "llm_ask_audio"]
}
```

Размер: **~2.4 GiB** (`2_583_085_056` bytes). Hash модели + коммит HF — stable. URL будет: `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7fa1d78473894f7e736a21d920c3aa80f950c0db/gemma-4-E2B-it.litertlm?download=true`.

### Что можно дропнуть в форк-allowlist

Если urgency — оставляем все 8 моделей. Можно сузить до text-only для Phase 1:
- Gemma-4-E2B-it, Gemma-4-E4B-it (image+audio — флаги игнорим)
- Gemma-3n-E2B-it, Gemma-3n-E4B-it (аналогично)
- Gemma3-1B-IT (маленькая, ~580 MB, text-only)
- Qwen2.5-1.5B-Instruct (text)
- DeepSeek-R1-Distill-Qwen-1.5B (text, поддержка thinking)
- TinyGarden-270M, MobileActions-270M — **выкинуть** (требуют кастомных тасков, которые мы не портируем).

**Рекомендация**: в Phase 1 форк включает 3 модели — Gemma3-1B-IT (быстрая, 584 MB, test-fit), Gemma-4-E2B-it (taget, 2.4 GB), Qwen2.5-1.5B (разнообразие). Для Honor 200 (12 GB RAM) все три влезают.

---

## 6. Hidden dependencies

### AndroidManifest.xml — обязательное для Phase 1

Из `gallery-source/Android/src/app/src/main/AndroidManifest.xml` — нужны:

```xml
<uses-sdk android:minSdkVersion="31" android:targetSdkVersion="35" />

<!-- WorkManager foreground download -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<!-- LiteRT-LM GPU (OpenCL) — опционально, не required -->
<uses-native-library android:name="libOpenCL.so" android:required="false" />
<uses-native-library android:name="libvndksupport.so" android:required="false" />
<!-- libcdsprpc — только для Qualcomm NPU, на Honor 200 не нужно -->

<application>
  <!-- WorkManager foreground service — КРИТИЧНО, иначе DownloadWorker не сможет стать foreground -->
  <service
      android:name="androidx.work.impl.foreground.SystemForegroundService"
      android:foregroundServiceType="dataSync"
      android:exported="false"
      tools:node="merge" />
</application>
```

**НЕ нужно** в Phase 1:
- `CAMERA`, `RECORD_AUDIO` — нет image/audio.
- `com.google.android.apps.aicore.service.BIND_SERVICE` — нет AICore.
- `RECEIVE_BOOT_COMPLETED`, `com.google.android.c2dm.permission.RECEIVE`, `GET_ACCOUNTS` — нет FCM.
- `FileProvider` — нет image-share / media-intent.
- `OssLicensesMenuActivity` — нет licenses-screen (можно добавить позже).
- `GalleryFcmMessagingService`, `meta-data firebase.messaging.*` — нет push.
- `AppMeasurement*` receivers/services — нет Firebase Analytics.
- Deep-link intent-filter `com.google.ai.edge.gallery://...` — не используем.
- `android.permission.POST_NOTIFICATIONS` **нужно** runtime-запрашивать на SDK 33+ (Gallery делает это в `DownloadRepository.sendNotification:334`). В `:app` надо вызывать `ActivityResultContracts.RequestPermission` при первом download.

### Initialization steps в Application

Из `GalleryApplication.kt`:
- `@HiltAndroidApp` — **обязательно** (Hilt codegen).
- `@Inject lateinit var dataStoreRepository: DataStoreRepository` — у нас нет DataStore, убираем.
- `FirebaseApp.initializeApp(this)` — **убираем**.
- WorkManager инициализируется автоматически через `WorkManager.getInstance(context)` — **no-op в Application**. Если хотим кастомную `Configuration` — нужен `Configuration.Provider` на Application + Hilt WorkerFactory (не обязательно в Phase 1).

Минимальный `SanctumApplication`:
```kotlin
@HiltAndroidApp
class SanctumApplication : Application()
```

### Reflection / @Keep / ProGuard

- Gallery использует `@SerializedName` (Gson) — работает без reflection-based proguard rules если minifyEnabled=false. В Phase 1 Gallery имеет `isMinifyEnabled = false` (`build.gradle.kts:53`) — мы тоже так делаем.
- Hilt требует `@HiltAndroidApp`, `@AndroidEntryPoint`, `@HiltViewModel` — kapt-codegen, без reflection в runtime.
- LiteRT-LM тянет native libs; `uses-native-library` выше. `nativeLibraryDir` извлекается из `context.applicationInfo.nativeLibraryDir` (`LlmChatModelHelper.kt:90, 100`) — автоматически работает на Android.
- No `@Keep` в extraction set.

### BuildConfig / hardcoded package names

- `DownloadWorker.kt:342` — **захардкожено**: `Class.forName("com.google.ai.edge.gallery.MainActivity")`. **ПАТЧ**: передавать класс Activity через constructor parameter / Hilt constant / companion object из `:app`, либо сделать `BuildConfig.MAIN_ACTIVITY_CLASS_NAME`.
- `DownloadRepository.kt:299, 307` — deep-link `com.google.ai.edge.gallery://...` — **удалить** (мы не поддерживаем deep links в Phase 1). `sendNotification` можно упростить до обычного `launchIntentForPackage`.
- `ProjectConfig.kt:33–36` — HF OAuth endpoints — **не копируем**.
- `BuildConfig` упоминается в `BenchmarkViewModel.kt:22` — не в extraction set.

### Gradle-level

- `compileSdk = 35`, `minSdk = 31`, `targetSdk = 35`, `jvmTarget = "11"` — тянем из Gallery.
- `freeCompilerArgs += "-Xcontext-receivers"` — можно не тянуть.
- `buildFeatures { compose = true; buildConfig = true }` — compose только в `:app`, buildConfig — в обоих.

---

## 7. `tok/s` + TTFT metric — существует ли уже в Gallery?

### Что есть

**В benchmark-модуле** (`ui/benchmark/BenchmarkViewModel.kt:144–162`) — **есть готовая реализация**:

```kotlin
import com.google.ai.edge.litertlm.benchmark   // :32

val benchmarkInfo = benchmark(
    modelPath = modelPath,
    backend = backend,
    prefillTokens = prefillTokens,
    decodeTokens = decodeTokens,
    cacheDir = cacheDirPath,
)
val initTimeMs = benchmarkInfo.initTimeInSecond * 1000.0
prefillSpeeds.add(benchmarkInfo.lastPrefillTokensPerSecond)
decodeSpeeds.add(benchmarkInfo.lastDecodeTokensPerSecond)
timesToFirstToken.add(benchmarkInfo.timeToFirstTokenInSecond)
```

Это **top-level функция из `com.google.ai.edge.litertlm`** (SDK-level), не Gallery-код. Она прогоняет модель в "benchmark-режиме" на фиксированных prefill+decode и возвращает метрики. Подходит для отдельного screen "benchmark", **не для live-chat footer**.

### Что нужно для Phase 1 chat-footer (`N tokens · X.X tok/s · TTFT Yms`)

**В Gallery НЕТ live-режима** отдачи `lastDecodeTokensPerSecond` / `timeToFirstToken` из `Conversation.sendMessageAsync`. Метрики в chat-UI — только `latencyMs` (`LlmChatViewModel.kt:160`, просто `System.currentTimeMillis() - start`), без tokens/sec.

Что **есть в `MessageCallback.onMessage(message)`**:
- `message.toString()` — текущий text chunk.
- `message.channels["thought"]` — chain-of-thought (для support_thinking).

**Нет поля** на `Message` с cumulative-token-count / per-second-rate в чёткой форме. Uncertain: API LiteRT-LM 0.10.0 может иметь `message.channels["stats"]` или `conversation.lastStats` — **нужно проверить** по документации SDK или смотреть `com.google.ai.edge.litertlm:litertlm-android:0.10.0` source/javadoc.

### Вывод для Phase 1

Считаем **сами** в `ChatScreen` / `ChatViewModel`:
- `startMs = System.currentTimeMillis()` перед `runInference`.
- `firstTokenMs` — засекается в первом вызове `resultListener` с непустым `partialResult` (flag `firstRun`, как в `LlmChatViewModel.kt:170`).
- `TTFT = firstTokenMs - startMs`.
- **Счёт токенов**: по chunks `partialResult.split(" ")` — даст word-count ≠ token-count (неточно на ~20%). Для точности **нужен tokenizer** — его в `:core-runtime` из Gallery нет. В Phase 1 можно:
  - (a) Считать "символов / 4" как приближение (GPT-like tokenizer эвристика).
  - (b) Считать word-count и называть "words/sec" честно.
  - (c) Считать количество callback'ов `onMessage` как "chunks/sec" — легитимная метрика.
- `endMs = System.currentTimeMillis()` при `done=true`.
- `tok/s = tokens / (endMs - firstTokenMs)/1000`.

Рекомендация: **(c) chunks/sec** или **(a) char/4** — без внешнего tokenizer'а.

---

## 8. On-device verification checklist для Phase 1 (Honor 200)

Минимальный smoke-test:

1. **Установка APK**
   - Действие: `adb install -r build/outputs/apk/debug/app-debug.apk` (или через Android Studio).
   - Результат: иконка "Sanctum" появляется в лаунчере; тап запускает `MainActivity` без crash.

2. **Permissions**
   - Действие: запустить app.
   - Результат: запрашивается `POST_NOTIFICATIONS` (на Android 13+). Пользователь allow → продолжение без ошибок в logcat.

3. **Model list**
   - Действие: открыть `ModelManagerScreen`.
   - Результат: отображается список моделей из bundled-allowlist (минимум 3 — Gemma3-1B, Gemma-4-E2B, Qwen2.5-1.5B). Каждая показывает `name`, `sizeInBytes` в MB, статус `NOT_DOWNLOADED`.

4. **Download (small model)**
   - Действие: тап Download на `Gemma3-1B-IT` (584 MB).
   - Результат: прогресс-бар растёт 0% → 100%, foreground-нотификация видна в system tray, скорость отображается. По завершении — статус `SUCCEEDED`, файл в `/sdcard/Android/data/{app_id}/files/Gemma3_1B_IT/{commitHash}/gemma3-1b-it-int4.litertlm`, размер ≈ 584 MB.

5. **Resume после reboot**
   - Действие: начать download `Gemma-4-E2B-it` (~2.4 GB), дождаться 30%, выключить Wi-Fi / kill app через swipe → подождать 10 сек → включить Wi-Fi / запустить app → Download снова.
   - Результат: downloader продолжает с точки останова (есть `.gallerytmp` файл, HTTP-запрос отправляется с `Range: bytes=N-`, response 206 Partial). Видно в logcat tag `AGDownloadWorker`.

6. **Cancel**
   - Действие: во время download нажать Cancel.
   - Результат: прогресс замирает, статус возвращается в `NOT_DOWNLOADED`, foreground-нотификация исчезает. `.gallerytmp` остаётся на диске.

7. **Load model (initialize)**
   - Действие: после успешного download тап "Chat" / Enter на модели.
   - Результат: "Loading model…" 5–20 сек, затем ChatScreen открывается. В logcat tag `AGLlmChatModelHelper` нет exception. Backend=GPU указан в log (`Preferred backend: GPU`).

8. **Inference**
   - Действие: ввести "Hello, what can you do?" и нажать send.
   - Результат: стрим токенов виден посимвольно/пословно за ~1–5 сек TTFT, ответ завершается. Внизу сообщения — `N tokens · X.X tok/s · TTFT Yms`. На Gemma3-1B GPU на Honor 200 ожидание: TTFT 200–800 ms, ~20–40 tok/s (приближённо).

9. **Stop mid-generation**
   - Действие: задать длинный prompt ("write a 500-word essay about..."), во время генерации нажать Stop.
   - Результат: стрим останавливается в течение 500 ms. Сообщение помечается как завершённое. Следующий prompt отрабатывает штатно.

10. **Context / multi-turn**
    - Действие: 3 последовательных prompt-reply пары.
    - Результат: модель помнит предыдущий контекст (conversation persists). Нет OOM, нет native crash. Memory usage (Profiler или `adb shell dumpsys meminfo`): PSS < 4 GB для Gemma-4-E2B, < 1.5 GB для Gemma3-1B.

11. **Cleanup on exit model**
    - Действие: выйти из ChatScreen (back), вернуться в ModelManagerScreen.
    - Результат: native-memory освобождается (engine closed). PSS падает до базового. Повторный вход в chat — re-initialize с той же задержкой.

12. **Switch model**
    - Действие: загрузить два chat'а последовательно — сначала Gemma3-1B, затем Gemma-4-E2B.
    - Результат: первый корректно unload'ится, второй загружается без OOM, отвечает корректно.

13. **Offline mode после download**
    - Действие: выключить Wi-Fi + mobile data, открыть ChatScreen с уже скачанной моделью.
    - Результат: inference работает полностью. Confirm'ит, что mobile LLM — **полностью local**.

14. **Error case: corrupted model**
    - Действие: adb push случайного мусора в `.../Gemma3_1B_IT/.../gemma3-1b-it-int4.litertlm` (перезаписать), запустить chat.
    - Результат: `initialize` возвращает ошибку через `onDone(errorMessage)`, UI показывает читаемое сообщение, нет native crash.

**Acceptance для Phase 1**: пункты 1–13 должны пройти. Пункт 14 — желателен (показывает читаемую ошибку), но не блокер.

---

## Summary

- **HF-token patch**: 11 строк удаления по 4 файлам (DownloadWorker.kt, DownloadRepository.kt, Consts.kt, Model.kt) + снос Firebase-analytics вызовов. Глубокий token-plumbing (AppAuth, DataStore, ModelManagerViewModel) **не попадает в `:core-runtime`** вообще — его тянут только UI-файлы, которые мы не портируем.
- **Extraction set**: 13 Kotlin-файлов + 1 JSON-asset, ~1900–2100 LoC после чистки. Обязательные libs для `:core-runtime`: androidx-core-ktx, work-runtime, gson, litertlm, hilt-android. Нет Firebase, нет AppAuth, нет DataStore, нет Moshi, нет Compose.
- **Download states**: 6 ModelDownloadStatusType — для Phase 1 достаточно 4 (NOT_DOWNLOADED, IN_PROGRESS, SUCCEEDED, FAILED + errorMessage). Resume через `.gallerytmp` работает из коробки. Checksum НЕТ — оставляем риск.
- **Inference states**: 3 точки отказа в initialize (Engine constructor, engine.initialize, createConversation), 2 в runInference (null instance, native onError), 1 race в cleanUp (cleanup во время inference — нужен свой mutex).
- **Allowlist**: 209 строк JSON, 8 моделей, ~15 полей в AllowedModel — для Phase 1 нужны ~8 полей, остальные выкидываем или игнорируем.
- **Hidden deps**: 6 permissions в Manifest + SystemForegroundService + hardcoded reference на `MainActivity` (патчим через BuildConfig / инъекцию класса). @HiltAndroidApp — обязателен.
- **tok/s + TTFT**: готового live-API в `LlmChatModelHelper` НЕТ. Benchmark-SDK-функция из LiteRT-LM подходит только для dedicated benchmark screen. В Phase 1 считаем сами в ChatScreen — время засекаем через `System.currentTimeMillis()`, токены считаем приближённо (char/4 или chunks/sec).
- **Smoke test**: 14 пунктов покрывают install → permissions → download → resume → load → chat → stop → multi-turn → offline → switch → error.

---

## Updated: 2026-04-15 — Implementation-Level Detail (Sections 9-14)

Углубление research для tech-spec. Фокус — конкретные координаты версий, Hilt layout, API контракты.

---

## 9. Gradle / Module Setup (Concrete)

### 9.1 settings.gradle.kts (root)

Gallery берёт за основу (`gallery-source/Android/src/settings.gradle.kts`):
- pluginManagement с google/mavenCentral/gradlePluginPortal (строки 17-36)
- dependencyResolutionManagement `FAIL_ON_PROJECT_REPOS` (38-45)
- resolutionStrategy с хаком для `oss-licenses-plugin` → useModule (30-35) — для PhoneWrap не нужно

Для PhoneWrap:
```kotlin
rootProject.name = "PhoneWrap"
include(":app", ":core-runtime")
```
pluginManagement блок — копируем 1:1 минус `oss-licenses` eachPlugin.

### 9.2 Root build.gradle.kts

Gallery `gallery-source/Android/src/build.gradle.kts` — 6 плагинов apply false. Для PhoneWrap Phase 1 выкидываем `google-services` (Firebase) и `oss-licenses`:

```
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false   // NEW — для :core-runtime
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.hilt.application) apply false
  alias(libs.plugins.ksp) apply false
}
```

### 9.3 libs.versions.toml — версии для Phase 1

Извлечено из `gallery-source/Android/src/gradle/libs.versions.toml`. Для Phase 1 scope нужны:

**Versions — оставляем как у Gallery:**
- `agp = "8.8.2"`, `kotlin = "2.2.0"`
- `coreKtx = "1.15.0"`, `lifecycleRuntimeKtx = "2.8.7"`, `activityCompose = "1.10.1"`
- `composeBom = "2026.02.00"`, `navigation = "2.8.9"`
- `kotlinReflect = "2.2.21"`, `serializationPlugin = "2.0.21"`, `serializationJson = "1.7.3"`
- `materialIconExtended = "1.7.8"`
- `workRuntime = "2.10.0"` (для DownloadWorker)
- `gson = "2.12.1"` (для ModelAllowlist parse)
- `lifecycleProcess = "2.8.7"` (для замены AppLifecycleProvider через ProcessLifecycleOwner)
- `hilt = "2.57.2"`, `hiltNavigation = "1.3.0"`
- `ksp = "2.3.6"`
- `litertlm = "0.10.0"` — КРИТИЧЕСКИЙ (ядро)

**Выкидываем из Gallery (не нужны Phase 1):**
- `protobuf` / `protobufJavaLite` (Gallery: settings.pb/skills.pb/cutouts.pb — у нас Room/Paper)
- `commonmark` / `richtext` (markdown rendering — Phase 2)
- `tflite` / `tflite-gpu` / `tflite-support` (не LLM-путь)
- `cameraX*` (4 deps, нет vision)
- `netOpenidAppauth` / `androidx.webkit` (HF OAuth — убираем)
- `hilt-android-testing`, `exifinterface`, `securityCrypto`, `moshi*`
- `ossLicenses`, `playServicesOssLicenses`, `googleService`, `firebaseBom`, `firebase-*`
- `mlkit-genai-prompt`
- `dataStore = "1.1.7"` — Gallery хранит settings.pb — у нас Room+Paper; можно отложить
- `splashscreen` — Phase 2

**Плагины к удалению:** `protobuf`, `oss-licenses`, `google-services`. `kotlin-serialization` — оставляем для nav routes.

**Нужно добавить:**
```
android-library = { id = "com.android.library", version.ref = "agp" }
```

### 9.4 Kotlin 2.2 + Compose plugin

Gallery `libs.versions.toml:100`:
```
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```
То есть Compose Compiler Plugin тянется **тем же** `kotlin` ref (2.2.0), а не через устаревший `kotlinCompilerExtensionVersion`. Подтверждено — Kotlin 2.2.x включает встроенный Compose Compiler plugin.

### 9.5 :app build.gradle.kts (ключевые пункты)

Базой берём `gallery-source/Android/src/app/build.gradle.kts` (129 строк):

- `namespace = "com.phonewrap.app"` (вместо `com.google.ai.edge.gallery`)
- `applicationId = "com.phonewrap.app"` (из user-spec)
- `compileSdk = 35`, `minSdk = 31`, `targetSdk = 35` (те же что Gallery, строки 33/37-38)
- **Убрать manifestPlaceholders** для `appAuthRedirectScheme` и `applicationName` (строки 44-46) — у нас нет OAuth. `applicationName` задаём напрямую в AndroidManifest через `android:name=".PhoneWrapApp"`
- `kotlinOptions { jvmTarget = "11"; freeCompilerArgs += "-Xcontext-receivers" }` (Gallery строки 62-65) — нужен для `@ExperimentalApi` opt-ins
- Заменить `kapt(libs.hilt.android.compiler)` (строка 112) на `ksp(libs.hilt.android.compiler)`. Убрать `kotlin("kapt")` со строки 28 — Hilt 2.48+ поддерживает KSP, у нас 2.57.2.
- `implementation` оставить ~15 deps: core-ktx, lifecycle-runtime-ktx, activity-compose, compose-bom, ui, ui-graphics, ui-tooling-preview, material3, navigation-compose, kotlinx-serialization-json, kotlin-reflect, material-icon-extended, androidx-work-runtime, gson, androidx-lifecycle-process, litertlm, hilt-android, hilt-navigation-compose.

### 9.6 :core-runtime build.gradle.kts (новый)

Android library module:
```
plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.hilt.application)
  alias(libs.plugins.ksp)
}
android {
  namespace = "com.phonewrap.core.runtime"
  compileSdk = 35
  defaultConfig { minSdk = 31 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
}
dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.com.google.code.gson)
  implementation(libs.litertlm)
  implementation(libs.hilt.android)
  ksp(libs.hilt.android.compiler)
}
```

---

## 10. Hilt Module Layout (:core-runtime)

### 10.1 Gallery's current Hilt layout (reference)

`gallery-source/.../di/AppModule.kt` (186 строк), `@InstallIn(SingletonComponent)`. Provides:
- 5 DataStore<*> (Settings/CutoutCollection/UserData/BenchmarkResults/Skills) — **drop в Phase 1**
- `AppLifecycleProvider` (строка 154) → `GalleryLifecycleProvider()` — **replace with ProcessLifecycleOwner**
- `DataStoreRepository` (161) — drop
- `DownloadRepository` (179): `DefaultDownloadRepository(context, lifecycleProvider)` — **нужен**

`ModelManagerViewModel` `@HiltViewModel` с 5 @Inject зависимостями (строки 187-196): DownloadRepository, DataStoreRepository, AppLifecycleProvider, Set<CustomTask>, @ApplicationContext.

`DownloadWorker` — **plain CoroutineWorker**, НЕ @HiltWorker (`worker/DownloadWorker.kt:67-68`). Все данные через WorkManager Data bundle (ключи KEY_MODEL_*). grep `@HiltWorker|HiltWorkerFactory` в gallery-source — 0 matches.

### 10.2 PhoneWrap `:core-runtime` CoreRuntimeModule

`core-runtime/src/main/java/com/phonewrap/core/runtime/di/CoreRuntimeModule.kt`:

```
@Module
@InstallIn(SingletonComponent::class)
object CoreRuntimeModule {
  @Provides @Singleton
  fun provideDownloadRepository(@ApplicationContext ctx: Context): DownloadRepository =
    DefaultDownloadRepository(ctx)  // убираем lifecycleProvider

  @Provides @Singleton
  fun provideLlmModelHelper(): LlmModelHelper = LlmChatModelHelper
  // object Kotlin — de-facto singleton; wrap в @Provides для DI

  @Provides @Singleton
  fun provideModelRegistry(
    @ApplicationContext ctx: Context,
    downloadRepository: DownloadRepository,
    llmModelHelper: LlmModelHelper,
  ): ModelRegistry = DefaultModelRegistry(ctx, downloadRepository, llmModelHelper)
}
```

### 10.3 AppLifecycleProvider — skip вариант (ProcessLifecycleOwner)

Gallery использует `lifecycleProvider.isAppInForeground` **в 1 точке**: `DownloadRepository.sendNotification()` line 276:
```
if (lifecycleProvider.isAppInForeground) {
  return  // suppress notification on foreground
}
```
Единственная функция — гасить notification когда app в foreground.

Второе место: `ModelManagerViewModel.setAppInForeground(foreground)` (строки 1028-1029) вызывается из `GalleryNavGraph.kt:166,170` (composable lifecycle hooks) и пишет в `lifecycleProvider.isAppInForeground`.

**Три варианта замены:**
1. Всегда показывать notification — проще всего, но double-cue при foreground.
2. `ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)` — zero new deps (lifecycle-process уже подключена).
3. `isForeground: () -> Boolean` параметр конструктора.

**Рекомендация:** вариант 2. Убираем файл `GalleryLifecycleProvider.kt` полностью, убираем `lifecycleProvider` из конструктора `DefaultDownloadRepository` (`data/DownloadRepository.kt:83`), в `sendNotification:276` заменяем на ProcessLifecycleOwner check, убираем `setAppInForeground` handling везде.

### 10.4 DownloadWorker — остаётся plain

`DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker` (строка 67-68). Получает данные через `params.inputData.getString(KEY_MODEL_*)`. **HiltWorkerFactory НЕ нужен**, `androidx.hilt:hilt-work` НЕ добавляем. `@HiltAndroidApp` на PhoneWrapApp достаточно для остального DI.

### 10.5 LlmChatModelHelper как Kotlin `object`

`ui/llmchat/LlmChatModelHelper.kt:55` — `object LlmChatModelHelper : LlmModelHelper`. Singleton by design. Имеет private state `cleanUpListeners: MutableMap<String, CleanUpListener>` (строка 57) — **not thread-safe**, шарится по имени модели. В Phase 1 мы wrap через `ModelRegistry` + `Mutex` на жизненный цикл → гарантируется single-active-model → map safe.

Для DI: `@Provides fun provideLlmModelHelper(): LlmModelHelper = LlmChatModelHelper` — корректно, Hilt примет object.

---

## 11. Gallery Files: As-Is / Patch / Rewrite

### 11.1 Model.kt — patches

`data/Model.kt:273` — поле `var accessToken: String? = null`. **Изолировано** — var, не связано с другими полями init-логикой. Других HF-tangled полей в классе нет (`url`, `modelId`, `commitHash` generic по типу, HF только по значению).

Ripple: Gallery grep `model.accessToken` — 3 места:
- `data/DownloadRepository.kt:123-125` — `if (model.accessToken != null) inputDataBuilder.putString(...)`
- `ui/modelmanager/ModelManagerViewModel.kt:853` — `model.accessToken = tokenStatusAndData.data.accessToken` (OAuth flow)
- `worker/DownloadWorker.kt` — чтение header из inputData (см. existing research section 1)

**Action:** удалить строку Model.kt:273 + все 3 ripple-места + 4 KEY_MODEL_DOWNLOAD_ACCESS_TOKEN констант-ссылки.

### 11.2 Tasks.kt — оставить константу, drop class

`data/Tasks.kt` (161 строка):
- `data class Task(...)` (34-130) — generic, но в Phase 1 drop полностью
- `object BuiltInTaskId` (138-147) — 8 task ID констант
- `allLegacyTaskIds` (149-156) + `isLegacyTasks` (158) — legacy check

**LlmChatModelHelper НЕ ссылается на Task/BuiltInTaskId** — подтверждено: LlmChatModelHelper.kt imports только `data.Accelerator / ConfigKeys / DEFAULT_* / Model`.

**ModelAllowlist.kt:94-99** использует 6 констант `BuiltInTaskId.LLM_*` для `isLlmModel`-check. Мы упрощаем — у нас только chat.

**Action:**
- Оставить одну константу `const val TASK_ID_LLM_CHAT = "llm_chat"` (вынести в `core-runtime/.../data/TaskIds.kt` или просто `constants.kt`).
- В `AllowedModel.toModel()` (patched) заменить isLlmModel-check на `taskTypes.contains("llm_chat") || taskTypes.isEmpty()`.
- Файл `Tasks.kt` не копировать вообще.

### 11.3 Categories.kt — drop полностью

`data/Categories.kt` (46 строк) — `CategoryInfo` + `object Category { LLM, CLASSICAL_ML, EXPERIMENTAL }`.

grep `category|Category\.|allowedDeviceGroups` в ModelAllowlist.kt — **НЕ найдено** ссылок на `Category`/`CategoryInfo`. Поле `taskTypes: List<String>` — это task ID-ы, НЕ category.

Category используется только UI-grouping-ом home screen (`ModelManagerViewModel.kt:34-35` imports, `groupTasksByCategory:1209`, `getCategoryLabel:1255`) — мы в Phase 1 не тащим home screen Gallery.

**Action:** drop полностью. Не копировать.

### 11.4 Итоговая классификация

| Action | Gallery файл | Detail |
|--------|-------------|--------|
| As-is | `runtime/LlmModelHelper.kt` (interface, 122 строки) | Copy 1:1, rename package |
| As-is | `data/ConfigKeys.kt`, `Accelerator.kt`, `Config.kt`, `DEFAULT_*` constants | Copy |
| As-is | `common/cleanUpMediapipeTaskErrorMessage.kt` helper | Copy |
| Patch | `data/Model.kt` | Remove line 273 (accessToken) |
| Patch | `data/DownloadRepository.kt` | Remove: import line 39, ctor param line 83, token block 123-125, replace lifecycleProvider check 276 on ProcessLifecycleOwner |
| Patch | `worker/DownloadWorker.kt` | Remove HF token handling (3 sites — existing research section 1) |
| Patch | `data/Tasks.kt` | Drop file; inline `TASK_ID_LLM_CHAT = "llm_chat"` |
| Patch | `data/ModelAllowlist.kt` | Simplify isLlmModel; drop AICORE/NPU paths (Phase 1 GPU/CPU only) |
| Patch | `ui/llmchat/LlmChatModelHelper.kt` | Copy to `core-runtime/.../llm/` |
| Drop | `data/Categories.kt` | Не копировать |
| Drop | `GalleryLifecycleProvider.kt` + AppLifecycleProvider interface | Заменяем ProcessLifecycleOwner |
| Rewrite | `ui/modelmanager/ModelManagerViewModel.kt` (1409 строк) | Ядро → ModelRegistry (секция 12) |

---

## 12. ModelRegistry Contract

### 12.1 Ключевые операции Gallery ModelManagerViewModel → ModelRegistry

| # | Gallery метод (line) | Что делает | ModelRegistry API |
|---|---------------------|------------|-------------------|
| 1 | `loadModelAllowlist()` (870-1013) | JSON parse + toModel() + filter NPU/SOC + attach to tasks | `suspend fun refreshAllowlist(): Result<List<Model>>` |
| 2 | `getModelDownloadStatus(model)` (1273-1314) | scan ext files dir → статус | `fun scanLocalFiles(): Map<String, ModelDownloadStatus>` |
| 3 | `downloadModel(task, model)` (281-338) | delegate DownloadRepository | `fun download(model: Model): Flow<ModelDownloadStatus>` |
| 4 | `cancelDownloadModel` (339) | cancel tag | `fun cancelDownload(modelName: String)` |
| 5 | `deleteModel(model)` (350-388) | delete file+dirs | `suspend fun delete(modelName: String)` |
| 6 | `initializeModel` (390-458) | custom tasks → LlmChatModelHelper.initialize | `suspend fun initialize(modelName: String): Result<Unit>` — с GPU→CPU fallback |
| 7 | `cleanupModel` (460-504) | → LlmChatModelHelper.cleanUp | `suspend fun cleanup(modelName: String)` |
| 8 | `setDownloadStatus/setInitializationStatus` | update StateFlow | держим в ChatViewModel |
| 9 | `processPendingDownloads` (832-868) | re-enqueue PARTIALLY_DOWNLOADED at startup | `suspend fun resumePartialDownloads()` |

**Phase 1 ModelRegistry interface** (в `:core-runtime`):

```
interface ModelRegistry {
  val models: StateFlow<List<ModelEntry>>  // Model + DownloadStatus + InitStatus
  suspend fun refreshAllowlist(): Result<Unit>
  fun download(model: Model): Flow<ModelDownloadStatus>
  fun cancelDownload(modelName: String)
  suspend fun delete(modelName: String)
  suspend fun initialize(modelName: String): Result<Unit>
  suspend fun cleanup(modelName: String)
  suspend fun resetConversation(modelName: String, systemPrompt: String? = null)
  fun getInstance(modelName: String): LlmModelInstance?  // для runInference в ChatViewModel
}
```

### 12.2 Scan local files at startup

Gallery: `loadModelAllowlist` (870) → после парса allowlist вызывает `createUiState()` (1086) → внутри для каждого Model гоняет `getModelDownloadStatus(model)` (1273) → `File(model.getPath(context)).exists()`. Путь вычисляется в `Model.getPath()` (Model.kt:288-315):
- If imported → `externalFilesDir/fileName`
- If `localModelFilePathOverride` → use directly
- Else → `externalFilesDir/{normalizedName}/{version}/{downloadFileName}` (zip unwrapping учитывается)

`processPendingDownloads` (832-868) автоматически re-enqueue `PARTIALLY_DOWNLOADED`.

**PhoneWrap**: реплицируем в `DefaultModelRegistry.init { scanLocalFiles() }` + `resumePartialDownloads()` вызывается из Application.onCreate / ViewModel.init.

### 12.3 Concurrency pattern

Gallery **не использует Mutex** — использует флаги на model:
- `model.initializing: Boolean` (Model.kt:267)
- `model.cleanUpAfterInit: Boolean` (269)
- check `initializeModel:408-413` ("Skip if initialization is in progress")
- check `cleanupModel:494-502` (если init идёт — отложить cleanup через cleanUpAfterInit=true)

Также **stale cleanup guard** `cleanupModel:467-471`: если `instanceToCleanUp !== model.instance` → abort. Защита от cleanup старого инстанса после model switch.

**PhoneWrap** (user-spec mandates Mutex): заменяем флаги на `Mutex().withLock`. Single-active-model в Phase 1 → один Mutex на весь registry:

```
private val lifecycleMutex = Mutex()
suspend fun initialize(name: String) = lifecycleMutex.withLock { ... }
suspend fun cleanup(name: String) = lifecycleMutex.withLock { ... }
suspend fun resetConversation(name: String) = lifecycleMutex.withLock { ... }
```

Stale-instance guard всё равно нужен — защита от callback-гонок из LiteRT native. Проверяем `currentInstance === model.instance` перед cleanup.

---

## 13. resetConversation — confirmed API

`ui/llmchat/LlmChatModelHelper.kt:152-207` — **готовый метод** уже есть:

```
override fun resetConversation(
  model: Model,
  supportImage: Boolean = false,
  supportAudio: Boolean = false,
  systemInstruction: Contents? = null,
  tools: List<ToolProvider> = listOf(),
  enableConversationConstrainedDecoding: Boolean = false,
)
```

**Поведение (строки 161-206):**
1. Берёт `model.instance as LlmModelInstance` (data class с `engine: Engine` + `var conversation: Conversation`, строка 53).
2. `instance.conversation.close()` (165) — закрывает старую.
3. **Тот же** `instance.engine` переиспользуется (167) — engine не пересоздаётся (модель остаётся в RAM, огромный выигрыш).
4. `engine.createConversation(ConversationConfig(samplerConfig, systemInstruction, tools))` (184-199) — новая conversation с теми же sampler-параметрами.
5. `instance.conversation = newConversation` (201) — mutate.
6. `catch (e: Exception) { Log.d(TAG, ...) }` (204-206) — silent failure.

**Action для PhoneWrap:** используем as-is. "Reset Chat" UI-кнопка → `registry.resetConversation(modelName, systemPrompt)` → `LlmChatModelHelper.resetConversation(model, systemInstruction=Contents.of(listOf(Content.Text(prompt))))`. Engine НЕ пересоздаётся. Silent failure заворачиваем в Result — в ChatViewModel surface пользователю.

LiteRT-LM `Conversation` **НЕ имеет native reset-метода** — Gallery паттерн (close+recreate) является штатным способом сбросить KV-cache.

---

## 14. Backend Fallback (GPU → CPU)

`ui/llmchat/LlmChatModelHelper.kt:120-148`:

```
try {
  val engine = Engine(engineConfig)       // line 121
  engine.initialize()                     // line 122
  ExperimentalFlags.enableConversationConstrainedDecoding = ...
  val conversation = engine.createConversation(ConversationConfig(...))  // 127-142
  model.instance = LlmModelInstance(engine, conversation)
} catch (e: Exception) {
  onDone(cleanUpMediapipeTaskErrorMessage(e.message ?: "Unknown error"))
  return
}
onDone("")
```

**Ключевые факты:**
1. `try { ... } catch (e: Exception)` ловит **всё** (Engine ctor, engine.initialize(), createConversation).
2. **Не** выбрасывает — вызывает `onDone(errorMessage)`. Контракт: пустая строка = success, non-empty = error. Это общий контракт `LlmModelHelper.initialize` (LlmModelHelper.kt:55, см. комментарий "onDone callback invoked when initialization is completed successfully").
3. `cleanUpMediapipeTaskErrorMessage(msg)` (`common/`) чистит GPU stack trace noise — копируем as-is.

**GPU→CPU fallback pattern для PhoneWrap:**

```
suspend fun initializeWithFallback(ctx: Context, model: Model): Result<Unit> = lifecycleMutex.withLock {
  // Attempt 1: GPU (default из allowlist либо user preference)
  val err1 = awaitOnDone { onDone ->
    llmModelHelper.initialize(ctx, model, supportImage=false, supportAudio=false, onDone = onDone)
  }
  if (err1.isEmpty() && model.instance != null) return@withLock Result.success(Unit)

  Log.w(TAG, "GPU init failed: $err1. Falling back to CPU.")
  awaitOnDone { onDone -> llmModelHelper.cleanUp(model, onDone) }  // cleanup half-created

  // Patch config to CPU
  model.configValues = model.configValues.toMutableMap().apply {
    put(ConfigKeys.ACCELERATOR.label, Accelerator.CPU.label)
  }

  val err2 = awaitOnDone { onDone ->
    llmModelHelper.initialize(ctx, model, supportImage=false, supportAudio=false, onDone = onDone)
  }
  return@withLock if (err2.isEmpty()) Result.success(Unit)
                  else Result.failure(RuntimeException("GPU+CPU init failed: $err2"))
}

private suspend fun awaitOnDone(block: ((String) -> Unit) -> Unit): String =
  suspendCoroutine { cont -> block { err -> cont.resume(err) } }
```

**Важно:** НЕ полагаемся на try/catch вокруг `llmModelHelper.initialize()` — функция сама ловит Exception и передаёт через callback. Проверяем `errorMessage.isEmpty()` && `model.instance != null`.

**Обнаружение GPU-specific ошибки:** error string обычно содержит "GPU"/"OpenCL"/"GL"/"delegate"/"kernel". Но **бесусловный fallback проще и надёжнее** чем парсинг сообщения — при любой ошибке на preferred accelerator гоняем CPU retry.

**Race при fallback:** engine может быть частично создан (Engine() success, engine.initialize() failed) → **обязателен cleanUp перед retry**, иначе утечка native ресурсов.

---

## Summary of Added Sections 9-14

**9. Gradle:** точные координаты версий Phase 1 (~15 deps из 40+ Gallery), KSP вместо kapt, Kotlin 2.2 Compose plugin через `kotlin-compose` с `version.ref = "kotlin"` подтверждён, :core-runtime android-library с минимальным набором.

**10. Hilt:** CoreRuntimeModule provides DownloadRepository / LlmModelHelper (object) / ModelRegistry; AppLifecycleProvider удаляется (1 call-site в DownloadRepository:276 → ProcessLifecycleOwner); DownloadWorker plain CoroutineWorker — @HiltWorker НЕ нужен; ModelManagerViewModel зависимости 5 штук.

**11. Patch classification:** Model.kt — 1 строка (273); Tasks.kt — drop класс, inline const; Categories.kt — drop (ModelAllowlist не ссылается — grep 0 matches); DownloadRepository — 4 патча (import/ctor/token block/foreground check).

**12. ModelRegistry:** 9 операций из ModelManagerViewModel, Mutex вместо флагов model.initializing, scan local files через Model.getPath() + File.exists() в init{}, single-active-model один Mutex на весь registry; stale-instance guard сохраняем.

**13. resetConversation:** готовый API LlmChatModelHelper:152-207 — close()+createConversation() на том же engine (engine не пересоздаётся); LiteRT Conversation без native reset — паттерн Gallery штатный.

**14. Fallback:** исключения ловятся внутри initialize и идут через `onDone(errorMsg)`, не выбрасываются. Фаллбэк — безусловный cleanUp + CPU retry, без парсинга сообщения. Race: обязательный cleanUp между попытками.
