# Decisions Log: phase-2.5-logexport

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

---

## Task 1: Add Russian strings for diagnostics surfaces

**Status:** Done
**Commit:** b5812b5
**Agent:** main agent
**Summary:** Добавлено 10 новых ключей в `app/src/main/res/values/strings.xml` под новой секцией `<!-- Crash report / diagnostics (Phase 2.5) -->`. Для кнопки «Сохранить лог» заведён один общий ключ `log_export_save_button`, переиспользуемый CrashReportActivity / RestartCrashBanner / AboutScreen. Для кнопок «Закрыть» и «Отмена» переиспользованы существующие `btn_close` и `btn_cancel` без дубликатов; contentDescription иконки Warning не вводился (иконка рядом с текстом — декоративная). Формулировки — на русском по тону `ux-guidelines.md`: императив кнопок, короткое нейтральное тело экрана, error-Toast одним предложением без апологий и ретрай-хинта, точка у баннера, без точки у Toast/Snackbar.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: OK → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)

**Verification:**
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
- `./gradlew :app:lintDebug` → 0 errors, 54 warnings (все предупреждения — либо пре-существующие dependency-version warnings, либо ожидаемые `UnusedResources` на новых ключах до Wave 2)
- `./gradlew :app:test` → 66 tests passed, 0 failures across 5 suites

**Keys added (10):** `crash_report_title`, `crash_report_body`, `log_export_save_button`, `log_export_success_toast`, `log_export_error_toast`, `crash_banner_body`, `crash_banner_dismiss_description`, `about_diagnostics_title`, `dev_crash_dialog_title`, `dev_crash_dialog_confirm`.
**Keys reused:** `btn_close` (CrashReportActivity), `btn_cancel` (dev-dialog).

---

## Task 2: CrashHandler + CrashState file primitives

**Status:** Done
**Commit:** b22d429
**Agent:** main agent
**Summary:** Добавлен пакет `app/.../crash/` с тремя файлами: `Killer` (интерфейс + companion `Default` на `Process.killProcess`), `CrashHandler` (UncaughtExceptionHandler: overwrite-запись `crash.log` ≤100 КБ с маркером `[truncated at 100 KB]`, удаление `.dismissed`, запуск `CrashReportActivity` через строковый FQN — TODO на Task 4, внешний try/catch с единичным `Log.e` + `Killer.kill`), `CrashState` (`@Singleton`, `hasUnresolvedCrash: StateFlow<Boolean>` читается с диска на каждый `refresh()`, плюс `markDismissed`/`clear`; в `init { refresh() }` публикуется правда сразу после инъекции). Декодирование внутреннего сбоя под контролем через конструкторный test-seam `crashLogWriter: (File, String) -> Unit` (Decision 5-стиль: handrolled fakes, без Mockito).
**Deviations:** В `CrashHandler` введён конструкторный seam `crashLogWriter` (internal val) — не прописан буквально в tech-spec, но укладывается в Testing Strategy «handrolled fakes, no Mockito/MockK» и в task-2 «Форма seam'а — выбор имплементатора».

**Reviews:**

*Round 1:*
- code-reviewer: 6 low-severity findings → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: OK → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: 3 low-severity findings → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-2/code-reviewer-2.json](logs/working/task-2/code-reviewer-2.json)
- test-reviewer: OK → [logs/working/task-2/test-reviewer-2.json](logs/working/task-2/test-reviewer-2.json)

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "*CrashHandlerTest*" --tests "*CrashStateTest*"` → BUILD SUCCESSFUL, 15 tests green (7 CrashHandlerTest + 8 CrashStateTest). Gradle не принимает `--tests` для lifecycle-таска `:app:test`, потому используем базовый `testDebugUnitTest`.
- `grep -rEn "ErrorLog\|errorLog" app/src/main/kotlin/app/sanctum/machina/crash/` → 0 hits (D10).
- `grep -n "kotlinx.coroutines" app/src/main/kotlin/app/sanctum/machina/crash/CrashHandler.kt` → 0 hits.
- `git diff core-runtime/.../ErrorLog.kt` → empty (whitelist не тронут).
- `SanctumApplication.kt` и `AndroidManifest.xml` без изменений (оба — Task 5).

---

## Task 3: LogExportManager + DeviceInfoCollector + LogcatReader + TapCounter

**Status:** Done
**Commit:** 405e382 (fixes on top of 487b1fa implementation)
**Agent:** main agent
**Summary:** Реализовано ядро сборки диагностического `.txt`-экспорта (Wave 1 фазы 2.5): `TapCounter` (pure-JVM 7-tap с inclusive ≤2 с окном, single-trigger per cycle), `LogcatReader` + `CommandRunner`/`DefaultCommandRunner` (own-pid `logcat -d -v threadtime --pid=<mypid> *:E`, concurrent pipe-drain через фоновый worker для обхода 64-KB deadlock, 2-с таймаут, placeholder'ы для четырёх edge-ветвей), `DeviceInfoCollector` + `DeviceInfoProvider`/`AndroidDeviceInfoProvider` (детерминированный header по формату tech-spec Data Models; `activeModelId`/`downloadedModels` заглушены до фазы интеграции с ModelRegistry), `LogExportManager` @Singleton (первичный Hilt-friendly ctor + secondary `LogExportManager(context)` без Hilt для Decision 5, `buildExport(source: ExportSource)` на `Dispatchers.IO` с head-truncate `crash.log` / tail-truncate `logcat` ≤100 KB + маркерами, `errors.log.1` пропускается при отсутствии, CrashReport ветка не вызывает LogcatReader). Тестовая пирамида — Robolectric (mirror `ErrorLogTest`) для классов с Android-зависимостями + plain JUnit4 для `TapCounter`/`DeviceInfoCollector`; 30 зелёных, hand-rolled fakes, без Mockito/MockK.
**Deviations:** Добавлен test seam `internal var openOutputStreamForTest: (Uri) -> OutputStream?` в `LogExportManager` — аналог `crashLogWriter` seam'а из Task 2 (принятого в round 2 как «форма на выбор имплементатора»). Причина: `ContentResolver.openOutputStream` объявлен `final` в Android API, а Robolectric 4.12 ShadowContentResolver не позволяет форсить возврат `null`. Seam покрывает тесты `writeTo_ioException_surfaces` и `writeTo_nullOutputStream_surfacesAsIoException` без переписывания прод-API. Внесено в NOTES.md: backlog-запись `Phase 2.5 follow-up: wire DeviceInfoCollector.activeModelId/downloadedModels to real ModelRegistry` — `activeModelId()`/`downloadedModels()` во временной заглушке возвращают `null`/empty, TODO в коде ссылается на backlog. `LogExportModule` намеренно не создан в Task 3 (Hilt прокачивает граф через class-level `@Inject constructor` на `LogExportManager` / `DeviceInfoCollector` / `LogcatReader`; биндинги для интерфейсов `CommandRunner` / `DeviceInfoProvider` понадобятся только если Wave 2 действительно инжектит их через Hilt — на данный момент secondary-ctor путь решает non-Hilt сторону без модуля; AC `LogExportModule scope discipline` тривиально проходит в отсутствие файла).

**Reviews:**

*Round 1:*
- code-reviewer: 6 low-severity (optional) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- security-auditor: OK → [logs/working/task-3/security-auditor-1.json](logs/working/task-3/security-auditor-1.json)
- test-reviewer: 3 low-severity → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-3/code-reviewer-2.json](logs/working/task-3/code-reviewer-2.json)
- test-reviewer: OK → [logs/working/task-3/test-reviewer-2.json](logs/working/task-3/test-reviewer-2.json)

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "*LogExportManagerTest*" --tests "*DeviceInfoCollectorTest*" --tests "*LogcatReaderTest*" --tests "*TapCounterTest*"` → BUILD SUCCESSFUL, 30 tests green (6 + 3 + 6 + 15). Как в Task 2: `--tests` фильтр работает на `:testDebugUnitTest`, не на lifecycle `:test`.
- `./gradlew :app:testDebugUnitTest` (full app suite) → 110 tests, 0 failures (включая 80 пре-существующих + 30 новых).
- `./gradlew :core-runtime:test` → BUILD SUCCESSFUL (регрессия-гейт по `ErrorLog` — не тронут, whitelist не расширен).
- `grep -rEn "ErrorLog|errorLog" app/src/main/kotlin/app/sanctum/machina/logexport/` → 1 hit, и тот в KDoc `LogExportManager.kt:37` (Decision 10: call-sites = 0 — AC выполнен).
- `grep -rEn "^import android\." app/src/main/kotlin/app/sanctum/machina/logexport/TapCounter.kt` → 0 hits (pure-JVM AC).
- `grep -rEn "@Provides\s+(fun\s+)?(provideLogExportManager|provideCrashState|provideDeviceInfoCollector|provideLogcatReader)" app/src/main/kotlin/app/sanctum/machina/logexport/` → 0 hits (LogExportModule scope discipline AC).
- `git diff main -- gradle/libs.versions.toml app/build.gradle.kts` → empty (no new deps AC).

## Task 4: CrashReportActivity (non-Hilt) + RestartCrashBanner composable

**Status:** Done
**Commit:** 4933c4d (fixes on top of 204886e implementation)
**Agent:** main agent
**Summary:** Добавлены два файла в `app/.../crash/`: `CrashReportActivity` — plain `ComponentActivity` без `@AndroidEntryPoint` (Decision 5), инстанциирует `LogExportManager` через secondary context-only ctor из Task 3, SAF `CreateDocument("text/plain")` с именем `sanctum-log-yyyyMMdd-HHmm.txt` (Locale.ROOT), in-flight guard через `mutableStateOf`-backed поле (чтобы кнопка «Сохранить лог» атомарно disable-илась при рекомпозиции), `try/finally` в корутине SAF-callback'а очищает guard для всех трёх веток (success / IOException / cancel); success-путь удаляет `crash.log` и `finish()`-ится. `RestartCrashBanner` — чистый stateless composable `Card { Row { Icon, Text, TextButton, IconButton } }` с `Icons.Outlined.Warning` (декоративная, contentDescription=null) и `Icons.Outlined.Close` (с CD); никакого internal `remember`. Smoke-гейты зелёные; end-to-end user-верификация отложена до Task 6/7 по R1-даунгрейду из task spec.
**Deviations:** `launching` реализован как `mutableStateOf`-backed property (не plain `var`, как в implementation hints) — требуется для атомарной рекомпозиции кнопки `enabled`. Документировано KDoc на поле. В round-1 фиксах добавлены S4-1 `FLAG_SECURE` на окно активности (defense-in-depth против Recents-скринов) и S4-3 `Toast.makeText(applicationContext, ...)` (избегаем Activity-leak через Toast view). S4-2 (race между `writeTo` и `delete()` при rotation-cancel lifecycleScope) — принят как известный эргономический edge-case по task spec «На crash-экране это приемлемо (редкий кейс)»; нет PII-expose, пользовательский файл сохранён до delete().

**Reviews:**

*Round 1:*
- code-reviewer: 3 low-severity observations → [logs/working/task-4/code-reviewer-1.json](logs/working/task-4/code-reviewer-1.json)
- security-auditor: 4 findings (2 applied, 2 deferred) → [logs/working/task-4/security-auditor-1.json](logs/working/task-4/security-auditor-1.json)
- test-reviewer: OK → [logs/working/task-4/test-reviewer-1.json](logs/working/task-4/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-4/code-reviewer-2.json](logs/working/task-4/code-reviewer-2.json)
- security-auditor: OK → [logs/working/task-4/security-auditor-2.json](logs/working/task-4/security-auditor-2.json)

**Verification:**
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (9s, `app-debug.apk` собран).
- `./gradlew :app:lintDebug` → 0 errors, 48 warnings (ни одно не атрибутировано `crash/CrashReportActivity.kt` или `crash/RestartCrashBanner.kt`).
- `grep -n "AndroidEntryPoint" app/src/main/kotlin/app/sanctum/machina/crash/CrashReportActivity.kt` → 0 совпадений (Decision 5 AC).
- `grep -n "CreateDocument" app/src/main/kotlin/app/sanctum/machina/crash/CrashReportActivity.kt` → 1 совпадение (Decision 2 AC).
- `grep -n "Icons.Outlined.Warning\|Icons.Outlined.Close" app/src/main/kotlin/app/sanctum/machina/crash/RestartCrashBanner.kt` → оба совпадения.
- User-verification (US-A crash path, SAF behaviour, cancel/error branches) отложена до Task 6 (dev-gesture trigger) и Task 7 (banner wiring) по R1-даунгрейду task spec.

---

## Task 5: SanctumApplication handler install + AndroidManifest activity

**Status:** Done
**Commit:** 70e7fc4
**Agent:** main agent
**Summary:** Подключена машинерия Task 2 и Task 4 к OS-видимым точкам. В `AndroidManifest.xml` между `MainActivity` и `SystemForegroundService` добавлен `<activity android:name=".crash.CrashReportActivity">` с шестью атрибутами из Decision 3 (`process=":crash"`, `exported="false"`, `excludeFromRecents="true"`, `taskAffinity=""`, `theme="@style/Theme.Sanctum"`), без `<intent-filter>`. В `SanctumApplication.onCreate` добавлен guard `if (getProcessName() == packageName) { installCrashHandler() }` строго до существующего `DefaultDownloadRepository.mainActivityFqn = …` (Decision 4; порядок «сначала handler, потом FQN» страхует от ClassLoader-падения на FQN-строке). `installCrashHandler()` — приватный одно-оператор, ставит `Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext, Killer.Default))`, без try/catch (внутренние сбои ловит сам handler через outer try/catch в Task 2).
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: OK → [logs/working/task-5/code-reviewer-1.json](logs/working/task-5/code-reviewer-1.json)
- security-auditor: OK → [logs/working/task-5/security-auditor-1.json](logs/working/task-5/security-auditor-1.json)
- test-reviewer: OK → [logs/working/task-5/test-reviewer-1.json](logs/working/task-5/test-reviewer-1.json)

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "*SanctumApplicationTest*"` → BUILD SUCCESSFUL, 2 tests green (оба бранча Decision 4). Через `testDebugUnitTest`, т.к. `--tests` не работает на lifecycle `:test` (см. Task 2).
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (full app suite, 0 regressions).
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (manifest merger принял новую `<activity>`-декларацию; Task 4 FQN `app.sanctum.machina.crash.CrashReportActivity` разрешился).
- `grep -n "android:process=\":crash\"" app/src/main/AndroidManifest.xml` → ровно 1 совпадение (line 49).
- `grep -n "getProcessName" app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt` → 1 совпадение (line 17, внутри guard'а).
- `grep -n "setDefaultUncaughtExceptionHandler" app/src/main/kotlin/app/sanctum/machina/SanctumApplication.kt` → 1 совпадение (line 24, внутри `installCrashHandler()` который вызывается только из guard-блока).
- `git diff main -- gradle/libs.versions.toml app/build.gradle.kts` → empty (AC «Никаких новых зависимостей»).

**Test harness note:** процесс-имя стабируется через reflection на `ActivityThread.mBoundApplication.processName` — того же поля, которое `Application.getProcessName()` читает на API 28+. Путь выбран по варианту (б) из task spec; вариант (а) (подкласс `SanctumApplication`) отпадает, т.к. класс `final` + `@HiltAndroidApp`, а task явно запрещает делать его `open` ради тестируемости. `@Before`/`@After` симметрично сохраняют и восстанавливают и global uncaught handler, и `processName`, чтобы не пачкать соседние Robolectric-классы.

## Task 6: AboutScreen — Diagnostics section + 7-tap dev-gesture

**Status:** Done
**Commit:** be28764
**Agent:** main agent
**Summary:** Добавлены две пользовательские поверхности Flow C и Dev-gesture в `AboutScreen.kt`. Раздел «Диагностика» под `AboutFooter` с кнопкой «Сохранить лог»: `@HiltViewModel AboutViewModel` (трёхстрочный делегат над `LogExportManager.buildExport(About)` + `writeTo`, ловит `IOException` в `Result.failure`), SAF-лончер `CreateDocument("text/plain")` с именем `sanctum-log-yyyyMMdd-HHmm.txt` (Locale.ROOT), in-flight guard `launching` сбрасывается в `finally` независимо от ветки (success / IOException / cancel), Snackbar-результат через `SnackbarHost` в `Scaffold`. Dev-gesture на строке версии: `Modifier.clickable(indication = null)` → `TapCounter.tap(): Boolean` → `showDialog` живёт в composable, не в детекторе (Вариант А из таска — `AboutFooter` знает только про `onVersionTap: () -> Unit`). `AlertDialog` на срабатывании: confirm `{ showDialog = false; throw RuntimeException("test crash from About") }` прямо на UI-потоке без Handler/Thread/launch (Decision 9), порядок «закрыть → бросить» предотвращает мелькание старого state на рестарте `:crash`.
**Deviations:** Добавлен новый файл `LogExportModule.kt` с `@Binds` для `CommandRunner → DefaultCommandRunner` и `DeviceInfoProvider → AndroidDeviceInfoProvider`. В Task 3 этот модуль был намеренно отложен («понадобится только если Wave 2 действительно инжектит `LogExportManager` через Hilt» — см. decisions.md Task 3); Task 6 — первый потребитель Hilt-графа `LogExportManager` (через `AboutViewModel`), поэтому бинды добавлены здесь. Попутно `DefaultCommandRunner` получил `@Inject constructor()` чтобы `@Binds` разрешался. `AboutViewModel` оставлен внутри `AboutScreen.kt` (3-строчный делегат, не заслуживает отдельного файла).

**Reviews:**

*Round 1:*
- code-reviewer: OK → [logs/working/task-6/code-reviewer-1.json](logs/working/task-6/code-reviewer-1.json)
- security-auditor: OK → [logs/working/task-6/security-auditor-1.json](logs/working/task-6/security-auditor-1.json)
- test-reviewer: OK → [logs/working/task-6/test-reviewer-1.json](logs/working/task-6/test-reviewer-1.json)

**Verification:**
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (регрессий Wave 1 нет; новых юнит-тестов Task 6 не добавил по TDD Anchor — Compose UI проект не покрывает).
- `./gradlew :app:lintDebug` → 0 errors, 46 warnings (все pre-existing). `HardcodedText` / `MissingPermission` / `UnusedResources` на файлах Task 6 — 0.
- User-verification on Honor 200 (Android 16, HONOR ELI-NX9):
  - Flow C (Save from About): SAF открыл диалог с именем `sanctum-log-20260419-1156.txt`, Snackbar «Лог сохранён», файл содержит хедер + `crash.log=[empty]` + `errors.log=[empty]` + живой `logcat` с own-pid фильтрацией (AC «logcat непустой, а не placeholder» — пройден, Decision 8 works).
  - Flow A + dev-gesture (Crash path): 7 тапов по версии → `AlertDialog` → «Да» → приложение падает → `CrashReportActivity` появляется. `crash.log` содержит верхний фрейм `AboutScreen.kt:194` (ровно строка с `throw`), путь `Dialog.dispatchTouchEvent` → `Compose.ClickableNode.onPointerEvent` → UI-callback, **без** `Handler.post` / `Thread.run` / coroutine frames — Decision 9 подтверждён стактрейсом. `thread: main`, `logcat` из `:crash`-пути — placeholder `[logcat available only via About export]` (Decision 8 works).
  - Cancel: отмена SAF-диалога — Snackbar не показан, кнопка снова кликабельна.
  - Boundary: 3 тапа → пауза >2 с → 5 тапов — диалог не появляется (счётчик сбросился); 7 подряд без пауз — диалог показан.

---

## Task 7: ModelManagerScreen — restart banner wiring

**Status:** Done
**Commit:** 511b0b9 (impl) + 13b9b97 (review fix)
**Agent:** main agent
**Summary:** Вшит `RestartCrashBanner` (Task 4) в `ModelManagerScreen` над списком моделей с рендером iff `CrashState.hasUnresolvedCrash == true`. Добавлен `SnackbarHost` (отсутствовал до этого — code-research §3), SAF-лончер `CreateDocument("text/plain")` с suggested filename `sanctum-log-YYYYMMDD-HHmm.txt` (`Locale.ROOT`, паритет с Task 4/6), `LaunchedEffect(Unit) { viewModel.refreshCrashState() }` для Decision 6 (фс — источник правды), и in-flight guard со сбросом в `finally` во всех ветках (success / IOException / Cancel). Для инъекции `CrashState` и `LogExportManager` расширен `ModelManagerViewModel` (точка инъекции — лёгкий путь из Implementation hints); добавлен прокси `saveLogAndClearCrash(uri): Result<Unit>`, повторяющий форму `AboutViewModel.buildAndWrite` из Task 6 с дополнительным `crashState.clear()` на успехе (обязательно по Flow B).
**Deviations:** None. Точка инъекции — расширение `ModelManagerViewModel`, как рекомендует Implementation hints (а не wrapper-VM). Поведенческих отклонений от tech-spec Flow B и 10 AC task 7 нет.

**Reviews:**

*Round 1:*
- code-reviewer: 1 nit (битая KDoc-ссылка `[clearCrashState]`) → [logs/working/task-7/code-reviewer-1.json](logs/working/task-7/code-reviewer-1.json)
- security-auditor: 1 info (A09 — узкий `catch (IOException)`; `SecurityException`/`IllegalArgumentException` от `openOutputStream` могли бы обойти Snackbar) → [logs/working/task-7/security-auditor-1.json](logs/working/task-7/security-auditor-1.json). Принято: деферим на Phase 5 ради паритета с `AboutViewModel.buildAndWrite`; расширение ловли здесь без параллельной правки About привело бы к расхождению паттерна.
- test-reviewer: 2 nits на AC→user-verify mapping (AC 4 блокирующий таб и AC 6 IOException структурно, но не наблюдаемы в 3 сценариях) + та же KDoc-ссылка → [logs/working/task-7/test-reviewer-1.json](logs/working/task-7/test-reviewer-1.json). TDD-Anchor (unit-тесты на UI пропускаем) подтверждён как консистентный с tech-spec Testing Strategy.

*Round 2 (after fixes):*
- Применена единственная актуальная правка — KDoc на `hasUnresolvedCrash` переформулирован (`[saveLogAndClearCrash] which calls CrashState.clear() on success`). Повторный прогон ревьюеров не требовался: остальные ниты либо info-уровня с явным решением оставить, либо документационные предложения к тексту task.md.

**Verification:**
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (34 s, 93 tasks).
- `./gradlew :app:test` → BUILD SUCCESSFUL (34 s, 166 tasks); тесты волны 1 (`CrashHandlerTest`, `CrashStateTest`, `LogExportManagerTest`, `DeviceInfoCollectorTest`, `LogcatReaderTest`, `TapCounterTest`, `SanctumApplicationTest`) остались зелёными.
- `./gradlew :app:lintDebug` → BUILD SUCCESSFUL; новых `HardcodedText` ошибок нет, все строки идут из `R.string.*` Task 1.
- User-verification on Honor 200 (все три сценария из task 7 "Verification Steps → User" — Save path, Dismiss path, Cancel path): прошли.

---

## Task 8: Code Audit

**Status:** Done
**Commit:** 00ac52f
**Agent:** main agent
**Summary:** Проведён холистический код-аудит Phase 2.5 по 11 измерениям скилла `code-reviewing` плюс 5 межкомпонентных пунктов из Description таска (shared-file-state, module boundaries, patterns.md, Compose-паттерны, Decisions 5 & 10). Все 9 structural invariants зелёные (module boundary, `ErrorLog` whitelist не тронут, manifest-процесс, process-name guard, non-Hilt `CrashReportActivity`, единственный `Log.e` breadcrumb в handler, SafeMarkdown сохранён). Shared-file-state контракт между `CrashHandler`/`CrashState`/`CrashReportActivity`/`LogExportManager` согласован (пути `filesDir/logs/`, overwrite-семантика, `.dismissed` lifecycle). Отчёт: [logs/working/task-8/code-audit-report.md](logs/working/task-8/code-audit-report.md).
**Deviations:** None.

**Findings:** Три LOW-severity, все — doc/style drift. CRITICAL/HIGH/MEDIUM отсутствуют. (1) Устаревший TODO в `CrashHandler.kt:62`, ссылающийся на Task 4 — Task 4 уже завершён, а `setClassName(pkg, FQN)` indirection должна остаться (избегает загрузки класса `CrashReportActivity` в main-процессе); (2) KDoc на `DefaultCommandRunner` (`LogcatReader.kt:76-79`) не соответствует классификатору в `read()` — говорит «empty», возвращает «unknown»; (3) Литералы маркера обрезки `crash.log` расходятся между `CrashHandler.kt:17` и `LogExportManager.kt:22` (trailing `\n`), путь в `LogExportManager` в текущем коде недостижим. Task 11 (pre-deploy QA) не блокируется; фиксы можно оформить отдельным housekeeping-коммитом либо отложить.

**Reviews:** Нет (Code Audit сам является ревью — JSON-ревьюеры для него не назначаются).

**Verification:**
- Все 9 structural grep-проверок зафиксированы в отчёте в секции «Structural checks» как таблица expected-vs-actual — все PASS.
- `git diff main -- core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt` → пусто (Decision 10 сохранён).
- Gradle-гейты в scope этого таска не запускаются — это задача Task 11 (pre-deploy QA).

---

## Task 9: Security Audit

**Status:** Done
**Commit:** 6b3e6c5
**Agent:** main agent
**Summary:** Проведён full-feature security audit Phase 2.5 против OWASP Top 10 (2021). Вердикт — **PASS**: Critical/High/Medium/Low findings отсутствуют. Все 10 осей явно оценены (A02/A06/A07/A10 — N/A с обоснованием; A01/A03/A04/A05/A08/A09 — PASS с доказательствами по файлам и строкам). Два residual-риска — Decision 11 (нефильтрованный экспорт) и native-SIGSEGV fallback — явно приняты как осознанные trade-off'ы со ссылкой на user-spec "Ограничения"/"Риски". Одно информационное наблюдение — per-process permissions не фильтруются Android'ом (`:crash` наследует INTERNET/CAMERA/RECORD_AUDIO, но код в `:crash` их не использует). Task 11 не блокируется; новая fix-задача не требуется. Полный отчёт: [logs/working/task-9/security-audit-report.md](logs/working/task-9/security-audit-report.md).
**Deviations:** None.

**Findings:** Critical 0 · High 0 · Medium 0 · Low 0. Accepted residual risks: RR-1 (Decision 11 — unfiltered export), RR-2 (native SIGSEGV fallback).

**Reviews:** Нет (Security Audit сам является ревью для своего направления — JSON-ревьюеры для Task 9 не назначаются).

**Verification:**
- 9 structural `grep`-проверок в секции «Structural Checks» отчёта — все PASS (exported=false на CrashReportActivity, 0 `openInputStream`/`takePersistableUriPermission`/`Runtime.exec`, единственный `ProcessBuilder` с фиксированным argv, `FLAG_SECURE` on crash window, process=":crash" ровно 1 раз, 0 `ErrorLog.e` под `crash/`, 0 `BuildConfig.DEBUG` в AboutScreen — последнее осознанно по Decision 12).
- Формат отчёта — markdown, секции Summary / OWASP Top 10 Evaluation / Accepted Residual Risks / Findings / Recommendations / Structural Checks.

---

## Task 10: Test Audit

**Status:** Done
**Commit:** e6dfb5a
**Agent:** main agent
**Summary:** Проведён full-feature test-quality audit семи новых test-файлов Phase 2.5 (47 @Test-методов на CrashHandlerTest / CrashStateTest / LogExportManagerTest / DeviceInfoCollectorTest / LogcatReaderTest / TapCounterTest / SanctumApplicationTest) по всем 7 осям из Description: поведенческое покрытие AC (head/tail-truncation направленные, порядок секций по индексам, banner truth-table, overwrite-семантика, cross-component `.dismissed` reset), содержательность ассертов (0 тестов-сахарин, 2 легитимных `assertNotNull` как pre-check), style consistency с `ErrorLogTest` (Robolectric + hand-rolled fakes, 0 Mockito/MockK импортов), pyramid balance (unit-only оправдан через code-research §9.1 — `androidTest` не сконфигурирован, `compose-ui-test` не на classpath; SAF/`:crash`/dispatch вынесены на manual user-verify), seam coverage (Killer / CommandRunner / DeviceInfoProvider + функциональные seam'ы `crashLogWriter` и `openOutputStreamForTest`), negative paths (IOException / null OS / 4 logcat-placeholder ветки / handler internal failure), reflection+argv инварианты (handlerShape via declaredMethods; argv exactly-6 + regex pid). **Вердикт — approve**, Task 11 не блокируется. Полный отчёт: [logs/working/task-10/test-audit-report.md](logs/working/task-10/test-audit-report.md).
**Deviations:** None.

**Findings:** Critical 0 · Major 0 · Minor 0. Три nit-level наблюдения: (F1) ячейка (F,T) truth-table `CrashState` — `.dismissed` существует при отсутствующем `crash.log` — не покрыта отдельным тестом, математически эквивалентна (F,F), но не локнута от AND→OR рефакторинга; (F2) `assertNotNull(out)` в `nonHiltConstruction_instantiatesWithoutInjection:286` избыточен по типу; (F3) tolerance window `bytes.size >= maxBytes - 1024` в `stacktraceOver100KB_truncationMarkerAtEnd` можно сузить до `-128`. Все три — опциональны, не блокируют Final Wave; решение по фиксам за пользователем / агентом Task 11.

**Reviews:** Нет (Test Audit сам является ревью — JSON-ревьюеры для Task 10 не назначаются).

**Verification:**
- `./gradlew :app:test` (precondition-гейт аудита) → BUILD SUCCESSFUL in 21s; 47 новых тестов Phase 2.5 зелёные.
- Coverage matrix (26 измерений) → 25 covered, 1 partial (Finding 1); полная таблица с именами тестов — в отчёте §2.
- `grep "org.mockito\|io.mockk"` по аудируемым файлам → 0 реальных импортов (единственное вхождение строки «no Mockito/MockK» — в KDoc-комментарии `LogcatReaderTest.kt:12`).
- `grep assertNotNull` → 2 попадания в аудируемых файлах, оба — легитимный pre-check перед substantive assertion.

---

## Task 11: Pre-deploy QA

**Status:** Done
**Commit:** 1deac53
**Agent:** main agent
**Summary:** Финальная приёмка Phase 2.5 перед hand-off. Все автоматические гейты зелёные (`:app:test` 113/113, `:core-runtime:test` 62/62 с `ErrorLogTest` 8/8, `:core-settings:test` 6/6, `:app:lintDebug` 0 errors, `:app:assembleDebug` BUILD SUCCESSFUL). APK-дельта над `main@4c0b8b5` (baseline измерен в `git worktree add /tmp/pw-main main`) — **+148.6 КБ** (122 605 261 − 122 453 051 байт), укладывается в бюджет ≤ ~200 КБ. 7 структурных грепов — все PASS (нет Compose/Activity в `:core-*`, нет `ErrorLog` в `crash/`, ровно один `:crash` в манифесте, ровно один `setDefaultUncaughtExceptionHandler` внутри process-guard, diff `ErrorLog.kt` пуст, `libs.versions.toml`/`app/build.gradle.kts` без новых зависимостей, `CrashReportActivity` без Hilt-аннотаций — единственное вхождение `@AndroidEntryPoint` только в KDoc с пометкой "NOT"). 25 AC user-spec + 11 AC tech-spec — все PASS; 14 user-spec AC помечены `PASS*` (file/test-level подтверждение + визуальная проверка на устройстве вынесена в hand-off чеклист). Блокеров 0, FAIL 0, findings upstream-аудитов (Task 8/9/10) уже разрешены. Вердикт: **READY**. Отчёт: [logs/working/task-11/pre-deploy-qa-report.md](logs/working/task-11/pre-deploy-qa-report.md). Hand-off чеклист из 4 сценариев (US-C pipeline, US-A crash path, US-B restart-banner, Negative path) передаётся пользователю в чате для on-device прогона на Honor 200.
**Deviations:** None.

**Reviews:** Нет (QA сам является верификацией — upstream-аудиты Task 8/9/10 уже выступили в роли ревью; JSON-ревьюеры для Task 11 не назначаются).

**Verification:**
- `./gradlew :app:test` → BUILD SUCCESSFUL, 113 tests, 0 failures (47 Phase 2.5 + 66 pre-existing).
- `./gradlew :core-runtime:test` → BUILD SUCCESSFUL, 62 tests, 0 failures; `ErrorLogTest` 8/8.
- `./gradlew :core-settings:test` → BUILD SUCCESSFUL, 6 tests, 0 failures.
- `./gradlew :app:lintDebug` → BUILD SUCCESSFUL, 0 errors, 46 warnings (все pre-existing или intentional, 0 `UnusedResources` атрибутировано Phase 2.5 строкам).
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL; APK 122 605 261 B (delta над main: +148 610 B ≈ +148.6 КБ, 74% бюджета).
- Structural greps (7): все PASS (детали — §4 отчёта).
- AC coverage: 36/36 PASS (25 user-spec + 11 tech-spec); 14 user-spec AC содержат on-device observation (hand-off §6 отчёта).

---

<!-- Template remnants below were pre-existing artifact from earlier entries; preserved for transparency.
Format is strict — use only these sections, do not add others.
Do not include: file lists, findings tables, JSON reports, step-by-step logs.
Review details — in JSON files via links. QA report — in logs/working/.

## Task N: [title]

**Status:** Done
**Commit:** abc1234
**Agent:** [teammate name or "main agent"]
**Summary:** 1-3 sentences: what was done, key decisions. Not a file list.
**Deviations:** None / Deviated from spec: [reason], did [what].

**Reviews:**

*Round 1:*
- code-reviewer: 2 findings → [logs/working/task-N/code-reviewer-1.json]
- security-auditor: OK → [logs/working/task-N/security-auditor-1.json]

*Round 2 (after fixes):*
- code-reviewer: OK → [logs/working/task-N/code-reviewer-2.json]

**Verification:**
- `npm test` → 42 passed
- Manual check → OK

-->
