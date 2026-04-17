# Аудит безопасности фазы 2 — Task 13

**Аудитор:** security-auditor
**Дата:** 2026-04-18
**Стандарты:** OWASP Top 10 (mobile) + OWASP MASVS v2
**Phase-2 HEAD:** 9b435f2

## Резюме

Кодовая база фазы 2 была проаудирована по восьми заявленным областям плюс обязательная проверка по OWASP/MASVS. Результат: **0 критических, 0 высоких, 2 средних (обе неблокирующие), 3 низких**. Ни одна из находок не блокирует мёрж в `main`. Фаза 2 сохраняет позицию «никаких секретов, никакой серверной аутентификации, никаких внешних auth-токенов», установленную в фазе 1: ноль захардкоженных учётных данных, ноль auth-заголовков в `:app`, `:core-runtime` или `:core-settings` main-sources. Модель разрешений строго on-demand (лаунчеры per-sheet), privacy-обвязка в манифесте цела (`allowBackup=false` + явный `data_extraction_rules.xml` exclude-root), а `SafeUriHandler` корректно применяет allow-list только для `http`/`https`, что подтверждено 13 тест-кейсами, покрывающими каждую опасную схему из task brief.

Два пункта средней критичности перенесены как отложенная обвязка (известные пробелы, задокументированные в решениях фазы 1 и фазы 2, никакого нового риска фаза 2 не вносит). Три пункта низкой критичности — стилистические/защитные улучшения для фазы 3 или позже.

## Находки по областям

### 1. Модель разрешений

**Статус:** ПРОЙДЕНО.

**Подтверждения:**
- `app/src/main/AndroidManifest.xml:5–12` — восемь записей `<uses-permission>`; опасная пара — `CAMERA` (строка 11) и `RECORD_AUDIO` (строка 12). `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE` и `WRITE_EXTERNAL_STORAGE` отсутствуют (подтверждено через grep — ноль совпадений по трём паттернам во всех манифестах).
- `CameraBottomSheet.kt:141–145` — `LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }` срабатывает **только при первой композиции Camera sheet**, который сам монтируется только когда пользователь нажимает на иконку камеры в `MultimodalInputBar`. Вызовов `checkSelfPermission` или `requestPermissions` в `SanctumApplication` или `MainActivity` нет (подтверждено через grep).
- `AudioRecorderBottomSheet.kt:145–149` — симметричный on-demand паттерн для `RECORD_AUDIO`; запрашивается только при первой композиции Audio sheet.
- `MultimodalInputBar.kt:114–126` — Photo Picker вызывается через `ActivityResultContracts.PickVisualMedia`, который **не требует разрешений** на API 31+, что соответствует tech-spec D10 и user-spec R6.
- Оба permission-лаунчера используют `ActivityResultContracts.RequestPermission()`, а не ручной `ActivityCompat.requestPermissions(...)`, поэтому системой Android управляется флоу подсказок, включая семантику «don't ask again». `isCameraDenialPermanent` / `isAudioDenialPermanent` — чистые хелперы, которые трактуют null Activity + очищенный rationale как постоянный отказ — прагматичное прочтение после prompt.

**Находки:** Нет.

### 2. Privacy-обвязка

**Статус:** ПРОЙДЕНО.

**Подтверждения:**
- `app/src/main/AndroidManifest.xml:23–25`:
  - `android:allowBackup="false"` (строка 23)
  - `android:dataExtractionRules="@xml/data_extraction_rules"` (строка 24)
  - `android:usesCleartextTraffic="false"` (строка 25) — **бонусная обвязка, не предусмотренная в task brief**; defense-in-depth для канала загрузки HF.
- `app/src/main/res/xml/data_extraction_rules.xml:3–8` — оба `<cloud-backup>` и `<device-transfer>` содержат `<exclude domain="root" path="."/>` согласно TAC-14. Форма пути `.` намеренная (tech-spec D26 требует именно литеральную форму для проверки lint/regex).
- `app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml:35–46` — смёрженный манифест из build-выхода подтверждает, что манифесты library-модулей (`core-runtime`, `core-settings`) НЕ отменили эти атрибуты при мёрже. `android:fullBackupContent` отсутствует (намеренно удалён в Task 5 round-1 как избыточный при minSdk=31).
- `core-settings/src/main/AndroidManifest.xml` самозакрывающийся (без блока `<application>`), что является правильным паттерном library-манифеста для не-переопределения app-level флагов.

**Находки:** Нет.

### 3. OOM-векторы вложений

**Статус:** ПРОЙДЕНО.

**Подтверждения:**
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MediaUtils.kt:28–50` — `decodeSampledBitmapFromUri` выполняет двухпроходный танец `BitmapFactory.Options.inSampleSize`. Первый проход (`inJustDecodeBounds=true`) на строках 34–36 заполняет `outWidth/outHeight` без выделения памяти под пиксели; второй проход на строках 48–49 учитывает вычисленный sample size. `calculateInSampleSize` на строках 84–89 — чистый округляющий вариант.
- `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Consts.kt:50` — `MAX_IMAGE_COUNT = 10`; `MAX_AUDIO_CLIP_DURATION_SEC = 30` (строка 62); `SAMPLE_RATE = 16000` (строка 65). В паре с `CHANNEL_IN_MONO` + `ENCODING_PCM_16BIT` в `AudioRecorderBottomSheet.kt:66–67` аудио-лимит составляет **30 × 16000 × 2 = 960 KB** на клип — в пределах бюджета «сотни KB», на который опирается задача.
- `app/src/main/kotlin/app/sanctum/machina/ui/chat/ImageDecoder.kt:30–38` — `TARGET_EDGE = 1024`; декодирование выполняется внутри `withContext(Dispatchers.IO)`.
- `ChatViewModel.addImages` (ChatViewModel.kt:431–471) обрезает внутри блока `_attachments.update { }` (TOCTOU-safe) и эмитит снекбар `attachment_max_images_reached` при переполнении.
- `ChatViewModel.addImageBitmap` (ChatViewModel.kt:479–486) защитно ре-даунскейлит до 1024 даже для CameraX-захватов (R5).
- **PNG-сжатие выполняется вне Main.** `LlmChatModelHelper.kt:284,297` — `MultimodalContentsBuilder.build(...)` диспатчится через `coroutineScope.launch(Dispatchers.Default) { dispatchPrep() }`. Это перф-фикс из Task-7, закрывший зависание на 5.9 сек / 355 кадров; инвариант в последующих коммитах не регрессировал.
- `AudioRecorderBottomSheet.kt:187–228` — запись выполняется на `Dispatchers.IO`, у `ByteArrayOutputStream` единственный писатель, `DisposableEffect.onDispose` освобождает нативный `AudioRecord`.

**Находки:** Нет.

### 4. Обработка content-URI

**Статус:** ПРОЙДЕНО (с одной LOW защитной заметкой, см. 4.1 ниже).

**Подтверждения:**
- Happy-path вложения (`Photo Picker → DefaultImageDecoder.decode → decodeSampledBitmapFromUri → openStream`) производит только content-URI. Content-URI — это непрозрачные authority + id хэндлы; их невозможно сформировать так, чтобы выполнить path traversal по файловой системе, поскольку `ContentResolver` разрешает authority в provider, а id непрозрачен для вызывающей стороны.
- `MediaUtils.kt:177–188` — `openStream` использует `contentResolver.openInputStream(uri)` для ветки content-scheme (строка 182). Результат трактуется как nullable `InputStream`; `IOException` и `SecurityException` оба перехватываются и сворачиваются в `null`. Никакого `File(uri.path)` для content-URI.

**#4.1 — LOW (защитная, неблокирующая).** `MediaUtils.openStream` на `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MediaUtils.kt:179–180` ПОДДЕРЖИВАЕТ ветку `file://` / null-scheme: `if (uri.scheme == null || uri.scheme == "file") uri.path?.let { FileInputStream(it) }`. В фазе 2 единственные продакшен-вызыватели (`DefaultImageDecoder`, приводимый в движение Photo Picker-ом, `ChatViewModel.addImageBitmap`, приводимый в движение CameraX) не могут породить URI со схемой `file://` или null-scheme, поэтому эта ветка **недостижима из пользовательского ввода на сегодня**. Однако любой будущий вызыватель, который пробросит URI из внешнего источника в `decodeSampledBitmapFromUri` без гейтинга схемы, вручит атакующему прямой `FileInputStream(uri.path)` в процессе приложения — и дефолтный `StrictMode` Android его не остановит. **OWASP MASVS v2 MSTG-STORAGE-2 / MSTG-PLATFORM-3.** Рекомендация: либо (a) удалить ветку `file://` при портировании в фазу 3 (ни одному известному вызывателю она не нужна), либо (b) потребовать от вызывателя объявлять `allowFileScheme: Boolean = false` и ставить дефолт в off в качестве tripwire.

### 5. Разрешения DataStore

**Статус:** ПРОЙДЕНО.

**Подтверждения:**
- `core-settings/src/main/kotlin/app/sanctum/machina/core/settings/di/CoreSettingsModule.kt:19,40` — `DATASTORE_FILE = "datastore/app_settings.pb"`, `produceFile = { File(context.filesDir, DATASTORE_FILE) }`. `context.filesDir` — это внутренний app-private каталог (`/data/data/<pkg>/files`, mode 0700 по умолчанию на Android, так как фреймворк сам выполняет chown/chmod за нас).
- Grep по всей кодовой базе: ноль попаданий на `MODE_WORLD_READABLE`, `MODE_WORLD_WRITEABLE` или `MODE_APPEND`. `getExternalFilesDir` используется только в `:core-runtime` для **загрузки моделей** (не настроек), что согласуется с дизайном фазы 1; фаза 2 не вводила никакого нового использования внешнего хранилища.
- `data_extraction_rules.xml` исключает `domain="root"` и для cloud-backup, и для device-transfer, поэтому файл DataStore НЕ выгружается в Google Drive даже если будущая фаза вновь включит auto-backup.

**Находки:** Нет.

### 6. Intent-хендлеры

**Статус:** ПРОЙДЕНО.

**Подтверждения:**
- Код фазы 2 выпускает ровно **три** исходящих Intent:
  1. `ChatScreen.kt:340–346` — `ACTION_APPLICATION_DETAILS_SETTINGS`, обёрнутый в `runCatching` (permanent-denial → флоу снекбара «Open settings»).
  2. `SafeMarkdown.kt:38–41` — `ACTION_VIEW` только для схем `http`/`https` из allow-list (см. §7).
  3. `DownloadRepository.kt:238` / `DownloadWorker.kt:330` — внутренний `Intent(context, activityClass)` для запуска `MainActivity` как content intent уведомления (код фазы 1; не экспортирован, без extras).
- `app/src/main/AndroidManifest.xml:38–51` — единственный компонент с `exported="true"` — это `MainActivity` (категория LAUNCHER, строка 40). `SystemForegroundService` от `WorkManager` имеет `exported="false"` (строка 50).
- Обзор смёрженного манифеста (app/build/intermediates/merged_manifests/.../AndroidManifest.xml) — единственные дополнительные компоненты с `exported="true"` приходят из внутренностей AndroidX (`SystemJobService`, `DiagnosticsReceiver`, `ProfileInstallReceiver`), и каждый защищён разрешением с system-signature (`BIND_JOB_SERVICE`, `android.permission.DUMP`). Код фазы 2 не добавил новой экспортированной activity/service/receiver/provider.

**Находки:** Нет.

### 7. Покрытие схем в SafeUriHandler

**Статус:** ПРОЙДЕНО.

**Реализация:** `app/src/main/kotlin/app/sanctum/machina/ui/chat/SafeMarkdown.kt:30–43`.

**Allow-list (перечислен):**
```
ALLOWED_SCHEMES = setOf("http", "https")
```
Схема нормализуется через `parsed.scheme?.lowercase()` (RFC 3986 §3.1 — case-insensitive), и `if (scheme !in ALLOWED_SCHEMES) return` молча отбрасывает всё остальное. Последующий `startActivity` обёрнут в `runCatching`, поэтому отсутствующий хендлер с `ActivityNotFoundException` для http/https тоже не роняет приложение.

**Матрица проверки опасных схем:**

| Схема                 | Заявлено заблокированной в тесте                | Статус        |
|-----------------------|------------------------------------------------|---------------|
| `intent://`           | `SafeUriHandlerTest.intent_blocked`            | **ПОКРЫТО**   |
| `javascript:`         | `SafeUriHandlerTest.javascript_blocked`        | **ПОКРЫТО**   |
| `file://`             | `SafeUriHandlerTest.file_blocked` (использует `/etc/passwd`) | **ПОКРЫТО** |
| `content://`          | `SafeUriHandlerTest.content_blocked`           | **ПОКРЫТО**   |
| `sms:`                | `SafeUriHandlerTest.sms_blocked`               | **ПОКРЫТО**   |
| `tel:`                | `SafeUriHandlerTest.tel_blocked`               | **ПОКРЫТО**   |
| `market:`             | `SafeUriHandlerTest.market_blocked`            | **ПОКРЫТО**   |
| `data:text/html,...`  | `SafeUriHandlerTest.data_blocked`              | **ПОКРЫТО**   |
| `mailto:`             | Напрямую не тестируется (блокируется семантикой allow-list) | **ПРОБЕЛ** |
| кастомные deeplink-и (например `myapp://`) | Напрямую не тестируется (блокируется семантикой allow-list) | **ПРОБЕЛ** |
| Пустая строка         | `SafeUriHandlerTest.empty_blocked`             | **ПОКРЫТО**   |
| Malformed             | `SafeUriHandlerTest.malformed_blocked`         | **ПОКРЫТО**   |
| HTTP/HTTPS в верхнем регистре | `SafeUriHandlerTest.{http_uppercase_allowed, https_mixedcase_allowed}` | **ПОКРЫТО** |

Всего тестов: 13 — все ПРОЙДЕНО (см. verification-блок в Task 6 decisions.md).

**Под-находки:**

**#7.1 — LOW (полнота покрытия тестами, неблокирующая).** Allow-list аддитивный/whitelist, поэтому `mailto:` и произвольные кастомные deeplink-схемы (например `myapp://`, `fb://`, `whatsapp://`) уже блокируются проверкой `scheme !in ALLOWED_SCHEMES`. Однако в тест-сьюте нет выделенного ассерта `mailto_blocked` или `custom_deeplink_blocked`, поэтому регрессии из-за будущего изменения вида «давайте также разрешим X» не будут пойманы. Рекомендация: добавить два однострочных теста по аналогии с `sms_blocked` — `handler.openUri("mailto:x@y.com")` и `handler.openUri("myapp://deeplink")`. Ноль изменений кода в продакшене.

### 8. Скан захардкоженных секретов

**Статус:** ПРОЙДЕНО.

**Сканированные паттерны** (case-insensitive по `**/*.{kt,kts,java,properties,xml,gradle,yml,yaml,json,md}`):
- `api[_-]?key`, `apikey`, `secret[_-]?key`, `auth[_-]?token`, `password\s*=\s*"..."`, `bearer\s`, `authorization:\s*...`
- `BEGIN (RSA |EC )?PRIVATE KEY`
- `AIza[0-9A-Za-z_-]{35}` (паттерн Google API key)
- `hf_[a-zA-Z0-9]{20,}` (паттерн Hugging Face token)
- `AKIA[0-9A-Z]{16}` (паттерн AWS access key)
- `ghp_[A-Za-z0-9]{30,}` (паттерн GitHub PAT)
- `sk_live_`, `sk_test_` (паттерны Stripe key)
- `hfToken`, `HF_TOKEN`, `accessToken`, `bearerToken`, `refreshToken`, `huggingface.*token`

**Разобранные совпадения:**
- `core-runtime/src/test/kotlin/.../AllowlistLoaderTest.kt:48` — `val firstToken = cfg.accelerators!!.split(",")[0].trim().lowercase()` — имя идентификатора `firstToken` — это локальная переменная в тесте; не учётные данные.
- `app/src/test/kotlin/.../SafeUriHandlerTest.kt:80` — строковый литерал `file:///etc/passwd`, используемый как вход, чтобы утверждать, что SafeUriHandler его блокирует. Не учётные данные.
- `core-runtime/src/main/kotlin/.../Consts.kt:42`, `ModelAllowlist.kt:68,89` — `DEFAULT_MAX_TOKEN = 1024`, `defaultMaxToken = maxToken`, `llmMaxToken = maxToken`. «Token» здесь в смысле LLM-инференса (бюджет вывода), а не auth-артефакт.
- Упоминания `huggingface.co` в `AllowlistLoader.kt:17` и `ModelAllowlist.kt:50–51` — **публичные неаутентифицированные** download-URL; никакой `Authorization`-заголовок не прикрепляется ни в одном call site (grep по `Authorization|Bearer ` в main-sources `:app`/`:core-runtime`/`:core-settings` возвращает ноль совпадений).

**Покрытие .gitignore** (`.gitignore` в корне репо): `.env`, `.env.*`, `*.keystore`, `*.jks`, `*.p12`, `*.pfx`, `*.key`, `*.pem`, `credentials.json`, `secrets/`, `release-keys/`, `keystore.properties`, `signing.properties`, `local.properties`. `git ls-files | grep` по этим паттернам возвращает ноль совпадений — ни одного файла с секретами никогда не коммитилось.

**Инспекция `local.properties`** (gitignored, проверено локально): содержит только `sdk.dir=...` — никаких токенов, никаких API-ключей.

**Находки:** Нет.

## Проверка по OWASP Top 10 (mobile) + MASVS v2 (переносимые вердикты)

| # | Категория | Вердикт по фазе 2 |
|---|---|---|
| M1 / MSTG-ARCH | Improper Platform Usage | ПРОЙДЕНО — minSdk=31, scoped storage, Photo Picker, никаких legacy permission-паттернов. |
| M2 / MSTG-STORAGE | Insecure Data Storage | ПРОЙДЕНО — DataStore в `filesDir` (0700), backup исключён. См. §4.1 про будущий tripwire. |
| M3 / MSTG-NETWORK | Insecure Communication | ПРОЙДЕНО — `usesCleartextTraffic="false"`, только HTTPS исходящий на huggingface.co. |
| M4 / MSTG-AUTH | Insecure Authentication | N/A — однопользовательское on-device приложение, серверной аутентификации нет. |
| M5 / MSTG-CRYPTO | Insufficient Cryptography | N/A — в code paths фазы 2 нет криптографических операций. |
| M6 / MSTG-AUTH-13 | Insecure Authorization | N/A — нет границы авторизации (см. M4). |
| M7 / MSTG-CODE | Client Code Quality | ПРОЙДЕНО — никакой десериализации из недоверенного ввода; `Uri.parse` + allow-list; декодирование bitmap обвязано. |
| M8 / MSTG-RESILIENCE | Code Tampering | Вне scope фазы 2 (anti-tamper зарезервирован для более поздней фазы; user-spec-ом не требуется). |
| M9 / MSTG-REVERSE | Reverse Engineering | Вне scope (флаги minified release build не входят в фазу 2). |
| M10 / MSTG-PLATFORM | Extraneous Functionality | ПРОЙДЕНО — никаких debug-эндпоинтов, никаких скрытых команд; `BuildConfig.DEBUG` гейтит только verbosity logcat. |

## Блокирующие находки

**Нет.**

## Неблокирующие рекомендации

| ID | Критичность | Суть | Где |
|----|-------------|------|-----|
| #4.1 | LOW | В `MediaUtils.openStream` всё ещё есть ветка `file://` / null-scheme → `FileInputStream(uri.path)`. Недостижима из сегодняшних вызывателей, но tripwire против будущего злоупотребления стоит ~5 строк. | `core-runtime/src/main/kotlin/app/sanctum/machina/core/common/MediaUtils.kt:179–180` |
| #7.1 | LOW | Добавить явные тесты `mailto_blocked` и `custom_deeplink_blocked` в `SafeUriHandlerTest` — страховка против будущего добросовестного коммита вида «давайте разрешим mailto». | `app/src/test/kotlin/app/sanctum/machina/ui/chat/SafeUriHandlerTest.kt` |
| Перенос из фазы 1 / Task 5 | MEDIUM (отложено) | Разрешение `INTERNET` объявлено широко. NetworkSecurityConfig, который пиннит `huggingface.co` (единственный сегодняшний исходящий хост), сузил бы blast radius компрометации цепочки поставок. Уже отмечено security-auditor-ом в Task-5; задокументированный перенос. | `AndroidManifest.xml` + `res/xml/network_security_config.xml` (новый) |
| Перенос из фазы 1 / Task 5 | MEDIUM (отложено) | Gradle dependency verification (`gradle/verification-metadata.xml`) не сконфигурирован. Добавление пинов для `compose-richtext 1.0.0-alpha02` и других alpha/beta-зависимостей обвязало бы supply chain сборки. Уже отмечено security-auditor-ом в Task-5. | `gradle/verification-metadata.xml` (новый) |
| Перенос из Task 10 | LOW (отложено) | Нет жёсткого лимита на длину строки `systemPromptDefault`, когда она течёт из `AllowedModelConfig.defaultConfig` в `Model.configValues`. Task 11 добавил clamp на 4096 символов в Apply-точке (security-auditor minor-2, исправлено). Регрессии нет, но эквивалентный clamp на этапе загрузки был бы defence-in-depth для фазы 3, когда приземлятся пользовательские overrides. | `core-runtime/.../AllowlistLoader.kt`, `ChatViewModel.applyOverrides` |

## Smoke-верификация

**Grep по манифесту (решающий):**

- `allowBackup="false"` → совпадение в `app/src/main/AndroidManifest.xml:23`.
- `dataExtractionRules="@xml/data_extraction_rules"` → совпадение в `app/src/main/AndroidManifest.xml:24`.
- `usesCleartextTraffic="false"` → совпадение в `app/src/main/AndroidManifest.xml:25` (бонус).
- Смёрженный манифест (`app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml:37,39,46`) сохраняет все три атрибута.
- Grep по `READ_MEDIA_IMAGES|READ_EXTERNAL_STORAGE|WRITE_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE` по всем файлам `AndroidManifest.xml` → **ноль совпадений** в `:app` и в library-манифестах (совпадения в `work/**` — это только документация).
- Grep по `data_extraction_rules.xml` на `exclude domain="root" path="."` → совпадения на строках 4 и 7 (и `<cloud-backup>`, и `<device-transfer>`).

**Перечисление allow-list в SafeUriHandler:**

```kotlin
// app/src/main/kotlin/app/sanctum/machina/ui/chat/SafeMarkdown.kt:30
private val ALLOWED_SCHEMES = setOf("http", "https")
```

Заблокированы (семантикой default-deny whitelist-а): `intent`, `javascript`, `file`, `content`, `sms`, `tel`, `market`, `data`, `mailto` и любая кастомная deeplink-схема. Case-insensitive сравнение схемы по RFC 3986 §3.1 (`parsed.scheme?.lowercase()`).

Покрытие тестами: 13 кейсов `SafeUriHandlerTest`, все проходят (см. Task 6 decisions.md).

**Вывод grep-а по секретам (сводка):**

```
# Pattern 1: api_key|apikey|secret|auth_token|password=|bearer|authorization
#   → 3 hits: firstToken (test local var), /etc/passwd (test input string),
#     *MAX_TOKEN / maxToken (LLM output-budget constants). 0 real credentials.

# Pattern 2: AIza[0-9A-Za-z_-]{35}
#   → 0 hits.

# Pattern 3: BEGIN (RSA|EC)? PRIVATE KEY
#   → 0 hits.

# Pattern 4: hf_[a-zA-Z0-9]{20,} | AKIA[0-9A-Z]{16} | ghp_[A-Za-z0-9]{30,} | sk_live_ | sk_test_
#   → 0 hits in production source; only hits are in skill-template docs (AKIA1234567890EXAMPLE as a sample).

# Pattern 5: huggingface.co | Authorization | Bearer
#   → huggingface.co present as unauthenticated download URL (AllowlistLoader.kt:17,
#     ModelAllowlist.kt:50–51). Zero Authorization/Bearer hits in :app/:core-runtime/:core-settings
#     main sources.
```

**Проверка git-отслеживаемых файлов с секретами:**

```
git ls-files | grep -i -E "(local.properties|secrets|credentials|keystore|\.key$|\.pem$)"
  → 0 hits.
```

**Содержимое `local.properties`** (локальный, gitignored):

```
sdk.dir=C\:\\Users\\Vladimir\\AppData\\Local\\Android\\Sdk
```

Секретов нет.

---

**Конец аудита.**
