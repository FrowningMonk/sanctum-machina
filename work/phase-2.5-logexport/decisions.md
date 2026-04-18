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

<!-- Entries are added by agents as tasks are completed.

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
