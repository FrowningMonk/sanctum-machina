---
created: 2026-04-18
status: draft
type: feature
size: M
---

# User Spec: Phase 2.5 — экспорт диагностического лога

## Что делаем

Добавляем в приложение механизм сбора и выгрузки диагностической информации, чтобы удалённый тестировщик мог одним нажатием сохранить файл с логами и переслать его разработчику через любой мессенджер или почту.

Фича закрывает три пути: мгновенный экран-отчёт при пойманном uncaught exception, баннер при следующем запуске после аварийного завершения, и постоянную кнопку в «О программе» для проактивного экспорта.

## Зачем

У разработчика есть конкретный открытый баг: после установки Phase-2 APK у товарища инициализация модели не проходит — приложение крашится, и причину установить невозможно. На сегодняшний день у товарища нет простого способа передать разработчику техническую информацию о том, что именно случилось: `ErrorLog` пишет в `filesDir/logs/`, и добраться до файла можно только через `adb`, чего обычный тестировщик не сделает.

Фича решает эту проблему на весь период закрытого тестирования Sanctum Machina: любой получатель debug-APK сможет одним действием собрать и переслать диагностический пакет. В Phase 5 тот же механизм ляжет в основу `SettingsScreen § Log export`, упомянутый в `deployment.md § Logging`.

## Как должно работать

### US-A: автоматический экран при краше

1. Товарищ работает в приложении, в какой-то момент Kotlin/Java поднимается uncaught exception (например, в `LlmChatModelHelper.initialize`).
2. Главный процесс ловит его через установленный `Thread.setDefaultUncaughtExceptionHandler`: синхронно записывает `filesDir/logs/crash.log` (overwrite, стэктрейс ≤100 КБ), запускает `CrashReportActivity` в отдельном процессе (`android:process=":crash"`), убивает себя (`Process.killProcess`).
3. Товарищ видит простой экран: заголовок «Приложение аварийно закрылось», короткое пояснение, две кнопки — «Сохранить лог» и «Закрыть».
4. «Сохранить лог» → системный диалог SAF (`ACTION_CREATE_DOCUMENT`, MIME `text/plain`, предложенное имя `sanctum-log-YYYYMMDD-HHmm.txt`) → товарищ выбирает папку Downloads или другую → файл сохранён → `crash.log` удаляется с диска → Toast «Лог сохранён» → экран закрывается.
5. Товарищ открывает файл в любом файловом менеджере, видит содержимое (device info + crash.log + errors.log + errors.log.1 если есть), пересылает разработчику в Telegram/почту.

### US-B: баннер после native crash или закрытия отчёта

1. Крэш произошёл, но `CrashReportActivity` по какой-то причине не запустился (native SIGSEGV из litertlm, bootstrap-ошибка) — или товарищ нажал «Закрыть» на экране отчёта.
2. `crash.log` остался на диске (если его вообще успели записать). Товарищ вручную запускает приложение снова.
3. На `ModelManagerScreen` над списком моделей появляется баннер: иконка предупреждения + текст «Прошлый запуск завершился аварийно.» + кнопка «Сохранить лог» + иконка ✕ «Скрыть».
4. «Сохранить лог» — тот же SAF-флоу, что в US-A, после сохранения `crash.log` удаляется и баннер пропадает. «Скрыть» — создаётся пустой флаг-файл `filesDir/logs/crash.log.dismissed`, баннер исчезает и больше не показывается, пока не произойдёт новый краш (новый handler удалит `.dismissed` вместе с перезаписью `crash.log`).

### US-C: проактивный экспорт без краша

1. Товарищ запускает приложение штатно, открывает «О программе» через иконку Info в `ModelManagerScreen`.
2. Внизу экрана, после манифеста и подвала, видит раздел «Диагностика» с кнопкой «Сохранить лог».
3. Нажимает → тот же SAF-диалог → сохраняет `.txt` c device info + `errors.log` (+`.1` если есть) + живым logcat-дампом от своего процесса (`logcat -d -v threadtime --pid=<ownpid> *:E`). В этом пути `crash.log` на диске не удаляется (его состояние не меняется).
4. Snackbar «Лог сохранён» в нижней части экрана.

### Dev-triggered тест-краш (для проверки пайплайна разработчиком)

На строке версии в подвале `AboutScreen` ловится жест «7 тапов подряд» (сброс счётчика при паузе >2 секунд). По достижении семи — `AlertDialog` «Спровоцировать тест-краш?» с кнопками «Да» / «Отмена». На «Да» — в `onClick` на main thread бросается `RuntimeException("test crash from About")`, который идёт через handler как настоящий краш.

## Критерии приёмки

### Handler и crash.log

- [ ] `Thread.setDefaultUncaughtExceptionHandler` установлен в `SanctumApplication.onCreate` первым действием после `super.onCreate()`, до `DefaultDownloadRepository.mainActivityFqn = ...`.
- [ ] Handler устанавливается ТОЛЬКО в главном процессе; в `:crash`-процессе (детектируется через `Application.getProcessName()`) установка пропускается.
- [ ] Handler ловит exceptions с любого потока (не только main).
- [ ] Handler синхронно пишет `filesDir/logs/crash.log` (overwrite, без append).
- [ ] Стэктрейс в `crash.log` ограничен 100 КБ (при превышении — обрезается первыми 100 КБ с пометкой `[truncated]`).
- [ ] После записи handler запускает `Intent(CrashReportActivity)` и вызывает `Process.killProcess(Process.myPid())`.
- [ ] Любое исключение внутри самого handler'а не приводит к циклу — код handler'а обёрнут в `try/catch`, при ошибке просто `Process.killProcess` без повторной записи/старта.
- [ ] Handler удаляет `crash.log.dismissed` перед тем, как переписать `crash.log` — чтобы баннер при следующем запуске снова показался.

### CrashReportActivity

- [ ] Activity объявлена в `AndroidManifest.xml` с атрибутами `android:process=":crash"`, `android:exported="false"`, `android:excludeFromRecents="true"`, `android:taskAffinity=""`.
- [ ] Activity НЕ использует Hilt injection (работает напрямую с `Context` + `File`).
- [ ] Заголовок, тексты, кнопки — из `strings.xml`, на русском языке, по тону `ux-guidelines.md`.
- [ ] Кнопка «Сохранить лог» запускает SAF через `ActivityResultContracts.CreateDocument("text/plain")` с предлагаемым именем `sanctum-log-YYYYMMDD-HHmm.txt`.
- [ ] Кнопка «Закрыть» вызывает `finish()`; `:crash`-процесс умирает сам.
- [ ] Отмена SAF пользователем (uri == null) возвращает на экран без сообщения; кнопки остаются активными.
- [ ] IOException при записи в выбранный uri показывает Toast «Не удалось сохранить лог», кнопки остаются активными.

### Restart-banner

- [ ] На `ModelManagerScreen` над `LazyColumn` со списком моделей появляется баннер (Material 3 `Card` + `Row { Icon, Text, TextButton, IconButton }`) тогда и только тогда, когда на диске существует `crash.log` И отсутствует `crash.log.dismissed`.
- [ ] Баннер содержит: иконку (`Icons.Outlined.ErrorOutline` или аналог), текст «Прошлый запуск завершился аварийно.», `TextButton` «Сохранить лог», `IconButton` с крестом (contentDescription «Скрыть»).
- [ ] «Сохранить лог» из баннера ведёт к тому же SAF-флоу; после успешной записи `crash.log` удаляется, баннер исчезает, Snackbar «Лог сохранён».
- [ ] «Скрыть» создаёт пустой файл `crash.log.dismissed` рядом с `crash.log`, баннер исчезает до следующего краша.

### AboutScreen

- [ ] После секций манифеста и подвала добавлен раздел «Диагностика» с `HorizontalDivider` сверху и одним `TextButton` «Сохранить лог».
- [ ] Нажатие запускает SAF-флоу; после успешной записи Snackbar «Лог сохранён».
- [ ] Строка версии (`BuildConfig.VERSION_NAME`) реагирует на жест «7 тапов в течение 2 секунд между тапами» — показывает `AlertDialog` «Спровоцировать тест-краш?» с «Да»/«Отмена».
- [ ] «Да» бросает `RuntimeException("test crash from About")` в `onClick` на main thread.
- [ ] Кнопка «Сохранить лог» в `AboutScreen` доступна ВСЕГДА, независимо от существования `crash.log` и `.dismissed`.

### Содержимое экспортированного .txt

- [ ] Файл начинается с хедера: `=== Sanctum Machina diagnostic log ===`, timestamp экспорта, `applicationId`, `versionName`, `versionCode`, `BuildConfig.DEBUG`, `Build.MANUFACTURER`+`Build.MODEL`+`Build.VERSION.SDK_INT`, total/available RAM, ID активной модели (или `none`), список скачанных моделей с размерами.
- [ ] Секция `=== crash.log ===` содержит содержимое файла или `[empty]`, если файла нет.
- [ ] Секция `=== errors.log ===` содержит содержимое или `[empty]`.
- [ ] Секция `=== errors.log.1 ===` включается только если файл существует; иначе пропускается.
- [ ] Секция `=== logcat ===`:
  - при экспорте из `AboutScreen` (live main process) — вывод `logcat -d -v threadtime --pid=<ownpid> *:E`, обрезанный последними 100 КБ при превышении;
  - при экспорте из `CrashReportActivity` — placeholder `[logcat available only via About export]`;
  - при ошибке `ProcessBuilder` — placeholder `[logcat unavailable: <reason>]`.

## Ограничения

- **Платформа:** Android, `minSdk = 31` (Android 12). SAF и `logcat --pid` поддерживаются без дополнительных разрешений.
- **Новых внешних зависимостей не добавляем** — только классы Android SDK, Kotlin stdlib, Compose Material 3 (уже подключён через BOM `2026.03.00`) и существующие Hilt-модули.
- **Новые строковые ключи** добавляются в `app/src/main/res/values/strings.xml` согласно правилу копирайта (`ux-guidelines.md § Copy Reference`). Никаких хардкоженных строк в Compose.
- **Whitelist `ErrorLog.ALLOWED_COMPONENTS` не расширяется** — `crash.log` это отдельная дорожка со своим писателем.
- **Размер .txt ≤ 4.3 МБ**: `errors.log` ≤ 2 МБ и `errors.log.1` ≤ 2 МБ (уже ограничено ротатором `ErrorLog`), `crash.log` ≤ 100 КБ, logcat ≤ 100 КБ, header ≤ 1 КБ. Этого достаточно для пересылки через любой мессенджер.
- **Приватность:** на фазе закрытого тестирования фильтрация содержимого не применяется — приоритет у полноты диагностики. В Phase 5 вопрос пересматривается перед публичным релизом (отмечено в `NOTES.md` backlog).
- **Dev-gesture доступен и в debug-, и в release-сборке на Phase 2.5.** В Phase 5 оборачивается в `if (BuildConfig.DEBUG)` при подготовке первого публичного APK.
- **Deploy:** ничего нового — ручной `./gradlew :app:assembleDebug`, перенос APK на устройство через adb или файлом.

## Риски

- **Bootstrap loop:** `CrashReportActivity` сам падает при старте → бесконечная цепочка крашей. **Митигация:** `try/catch` вокруг тела `uncaughtExceptionHandler`, при любом исключении внутри handler'а сразу `Process.killProcess` без повторной попытки записи/старта Activity.
- **Recursive handler в `:crash` процессе:** `SanctumApplication.onCreate` выполняется и в `:crash` процессе, там заново установленный handler мог бы перехватить внутренний сбой и пытаться запустить ещё одну `CrashReportActivity`. **Митигация:** `Application.getProcessName()` guard в самом начале `onCreate` — в неглавном процессе handler не устанавливается.
- **Native SIGSEGV из litertlm** обходит Kotlin-handler — в этом случае ни `crash.log`, ни CrashReportActivity не сработают. **Митигация:** принимаем как известное ограничение. Товарищ увидит системный диалог Android «Приложение остановлено». Если у ART-runtime хватило времени что-то записать — оно может подхватиться при проактивном экспорте из `AboutScreen` (logcat). Документируем в user-spec и в tech-spec.
- **logcat пустой на OEM с paranoid mode** (некоторые Honor/Xiaomi прошивки даже для своего pid возвращают пустой поток). **Митигация:** секция получает placeholder `[logcat unavailable: <reason>]`, остальной `.txt` всё равно сохраняется и пересылается.
- **Рост `strings.xml`:** добавляется ~10 новых ключей. **Митигация:** все строки короткие, в тон `ux-guidelines.md`, без «пожалуйста» и эмодзи.
- **Забытый dev-gesture в публичном релизе:** если в Phase 5 не обернуть в `BuildConfig.DEBUG`, случайный пользователь сможет спровоцировать тест-краш. **Митигация:** явная запись в `NOTES.md` и в Phase 5 user-spec «убрать dev-gesture из релизной сборки».

## Технические решения

- Пишем один текстовый файл, не zip — проще для товарища, видно глазами что именно пересылается.
- Используем SAF `ACTION_CREATE_DOCUMENT` вместо Share Intent + `FileProvider` — меньше кода, нет `FileProvider`-конфига и `grantUriPermissions`, пользователь сам выбирает место сохранения.
- `CrashReportActivity` живёт в отдельном процессе `:crash`, чтобы переживать смерть главного процесса (классический паттерн, применяется в библиотеке ACRA).
- `CrashReportActivity` не использует Hilt: в новом процессе `SingletonComponent` создаётся заново, шаринг с main невозможен. Activity работает с `Context.filesDir` и `ActivityResultContracts.CreateDocument` напрямую.
- В `SanctumApplication.onCreate` стоит guard по `Application.getProcessName()` — handler и инициализация companion-field выполняются только в главном процессе. API 28+; наш `minSdk=31` позволяет без рефлексии.
- Logcat собирается только в живом main-process пути (`AboutScreen`). Из `CrashReportActivity` pid главного процесса уже недоступен, pid `:crash`-процесса пустой — в `.txt` из CrashReportActivity секция содержит placeholder.
- `crash.log` хранит только последнюю uncaught (overwrite). Цепочка подряд идущих крашей в одной сессии сведётся к самому свежему; прошлые каугнутые ошибки уже в `errors.log`.
- Баннер привязан к существованию файла, а не к SharedPreferences/DataStore. Один файл — одна семантика «есть свежий невыгруженный краш». `.dismissed` рядом — «я видел, спасибо». Новый краш удаляет `.dismissed` и переписывает `crash.log`.
- Dev-gesture бросает исключение на main thread прямо в `onClick`, чтобы краш прошёл полный реальный путь (Compose → handler), а не обход в фоновом потоке.
- Никаких новых внешних зависимостей, никакого server-side, никакой телеметрии. Манифест Sanctum Machina («никаких cloud sync, никакого tracking») соблюдён.
- Whitelist `ErrorLog.ALLOWED_COMPONENTS` не расширяется — `crash.log` это отдельный канал с собственным писателем.

## Тестирование

**Unit-тесты:** делаются всегда, не обсуждаются.

Покрытие:
- `LogExportManager.composeExport()` — формат хедера, порядок секций, корректная вставка `[empty]` / `[unavailable]` placeholder'ов, обрезка logcat до 100 КБ с конца, обрезка `crash.log` до 100 КБ с начала.
- `DeviceInfoCollector.collect()` — корректное извлечение полей из `Build.*`, `PackageInfo`, `ActivityManager.MemoryInfo`.
- `CrashLogWriter.write(Throwable)` — формат стэктрейса, overwrite-семантика, одновременное удаление `.dismissed`.
- `LogcatReader.read(pid)` — корректный вызов `ProcessBuilder`, обработка `IOException` и пустого потока, таймаут 2 секунды.
- `CrashState.shouldShowBanner()` — логика «есть `crash.log` И нет `.dismissed`».

Используется Robolectric `@Config(sdk = [33])` в стиле существующего `ErrorLogTest.kt`; суспенд-функции через `kotlinx.coroutines.test.runTest`.

**Интеграционные тесты:** не делаем. SAF, отдельный процесс и uncaught-handler требуют `androidTest`-инструментации, которой в проекте нет (см. `architecture.md § Testing`). Их роль берёт на себя manual smoke.

**E2E тесты:** не делаем — по тем же причинам.

## Как проверить

### Агент проверяет

| Шаг | Инструмент | Ожидаемый результат |
|-----|-----------|---------------------|
| 1. `./gradlew :app:test` | bash | BUILD SUCCESSFUL; зелёные тесты на `LogExportManager`, `DeviceInfoCollector`, `CrashLogWriter`, `LogcatReader`, `CrashState`. |
| 2. `./gradlew :app:lintDebug` | bash | Без новых MissingPermission / MissingClass на добавленные компоненты. |
| 3. Grep `setDefaultUncaughtExceptionHandler` в `SanctumApplication.kt` | bash | Одна строка, расположена до присваивания `mainActivityFqn`. |
| 4. Grep `android:process=":crash"` в `app/src/main/AndroidManifest.xml` | bash | Одна строка внутри блока `<activity android:name=".crash.CrashReportActivity" ...>`. |
| 5. Grep `getProcessName` в `SanctumApplication.kt` | bash | Guard присутствует до установки handler'а. |
| 6. `./gradlew :app:assembleDebug` | bash | `app-debug.apk` собран, размер не вырос на >200 КБ. |

### Пользователь проверяет

- **Pipeline check (US-C):** установить APK на Honor 200 → открыть «О программе» → нажать «Сохранить лог» → SAF → сохранить в Downloads → открыть `.txt` в файловом менеджере → проверить наличие хедера, device info, секций `crash.log` (`[empty]`), `errors.log`, `logcat` с непустым выводом.
- **Crash path (US-A + dev-gesture):** в «О программе» тапнуть 7 раз по строке версии → диалог «Спровоцировать тест-краш?» → «Да» → приложение умирает → появляется `CrashReportActivity` с кнопками → «Сохранить лог» → SAF → проверить, что в `.txt` секция `=== crash.log ===` содержит `RuntimeException: test crash from About` и стэктрейс.
- **Restart-banner path (US-B):** повторить тест-краш → на `CrashReportActivity` нажать «Закрыть» → запустить приложение снова → на `ModelManagerScreen` увидеть баннер «Прошлый запуск завершился аварийно.» → нажать «Скрыть» → баннер исчезает, при следующем запуске не возвращается → снова тест-краш → баннер возвращается.
- **Negative path:** на `CrashReportActivity` / в `AboutScreen` открыть SAF → нажать системную «Отмена» → Activity остаётся на месте, никаких сообщений, кнопка всё ещё рабочая.

Если товарищ готов — ту же последовательность можно попросить его воспроизвести на его устройстве и прислать полученный `.txt`.
