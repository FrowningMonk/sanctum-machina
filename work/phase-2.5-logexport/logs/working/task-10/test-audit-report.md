---
task: 10
type: test-audit
feature: phase-2.5-logexport
auditor: Opus-4.7 (1M) acting as test-master
audited_on: 2026-04-19
suite_precondition: ./gradlew :app:test → BUILD SUCCESSFUL in 21s (47 new tests green)
---

# Test Audit Report — Phase 2.5 Log Export (Tasks 2–5)

## 1. Summary

Все семь новых тестовых файлов (47 @Test-методов) покрывают поведенческие AC user-spec содержательно: ассерты направленные (head vs tail truncation, маркеры, порядок секций по индексам, точные плейсхолдеры), hand-rolled fakes консистентны с эталоном `ErrorLogTest`, нет ни одного Mockito/MockK-импорта. Пирамида сбалансирована для size M unit-only, отсутствие integration/E2E явно обосновано code-research §9.1 (`androidTest` не сконфигурирован, `compose-ui-test` не на classpath). Нашлось два nit'а (одна явно непокрытая ячейка истинностной таблицы `CrashState` и пара assertNotNull-«сахарин»), оба предписательные-но-некритичные. **Vердикт — approve**; Final Wave (Task 11) можно стартовать без предварительной доработки.

## 2. Coverage Matrix

Legend: **covered** = минимум один тест с направленным ассертом на это измерение; **partial** = покрыто косвенно или не на каждом требуемом измерении; **missing** = нет теста.

| # | Измерение | Покрывающий(е) тест(ы) | Статус |
|---|---|---|---|
| 1 | Header: applicationId, version+debug, device, memory, active model, downloaded models | `DeviceInfoCollectorTest.headerFormatting_deterministicFromStub` (byte-exact строковое сравнение); `LogExportManagerTest.headerContainsRequiredFields` (6 полей) | covered |
| 2 | Header: active model = `none` когда null | `DeviceInfoCollectorTest.nullActiveModelId_rendersNone` | covered |
| 3 | Header: `(none)` placeholder для пустого списка моделей (и не «downloaded models:» со следующим пустым маркером) | `DeviceInfoCollectorTest.emptyDownloadedModels_rendersNonePlaceholder` (позитивный + негативный assert); `LogExportManagerTest.emptyDownloadedModels_rendersBlankList` | covered |
| 4 | `[empty]` placeholder при отсутствующих source-файлах | `LogExportManagerTest.missingErrorsLog_rendersEmpty`, `missingCrashLog_rendersEmpty`, `freshInstall_noLogsDir_succeeds` (обе секции одновременно) | covered |
| 5 | Порядок секций `crash → errors → errors.1 → logcat` через сравнение индексов (а не regex на весь файл) | `LogExportManagerTest.allSectionsPresent_orderedCorrectly` — 4 `indexOf` и транзитивная цепочка `crashIdx < errorsIdx < errors1Idx < logcatIdx` + проверка маркеров в правильной секции | covered |
| 6 | `errors.log.1` отсутствует → секция опускается целиком, без пустого дивайдера | `LogExportManagerTest.errorsLog1Absent_sectionOmitted` (assertFalse на наличие `=== errors.log.1 ===`) | covered |
| 7 | `crash.log` head-truncation: сохраняется head + маркер в хвосте; размер ≤ 100 KB + overhead | `LogExportManagerTest.crashLogHeadTruncation_preservesHeadAndMarks` (unique head-marker preserved, `endsWith("[truncated at 100 KB]")`, byte-cap); `CrashHandlerTest.stacktraceOver100KB_truncationMarkerAtEnd` (тот же инвариант на стороне писателя) | covered |
| 8 | `logcat` tail-truncation: сохраняется tail + маркер в голове; размер ≤ 100 KB + overhead | `LogExportManagerTest.logcatTailTruncation_preservesTailAndMarks` (unique tail-marker preserved, `[truncated: head ... bytes]` в первых 200 символах, byte-cap) | covered |
| 9 | `ExportSource.CrashReport` → logcat placeholder `[logcat available only via About export]`, и `CommandRunner` вообще не вызывается (ThrowingCommandRunner со `throw AssertionError`) | `LogExportManagerTest.crashReportSource_logcatPlaceholder` | covered |
| 10 | `ExportSource.About` → logcat populated из runner (позитивный контртест к #9) | `LogExportManagerTest.aboutSource_logcatPopulatedFromRunner` | covered |
| 11 | Logcat placeholders: empty / exit=N / timeout / unknown — все 4 ветки `LogcatReader` | `LogcatReaderTest.emptyStdout_returnsUnavailableEmpty`, `nonZeroExit_returnsUnavailableExitN`, `nullExitNoTimeout_returnsUnavailableUnknown`, `timeout_returnsUnavailableTimeout` | covered |
| 12 | Logcat happy path: stdout возвращается verbatim (tail-обрезка — ответственность caller'а) | `LogcatReaderTest.happyPath_returnsStdoutVerbatim` | covered |
| 13 | Argv-shape logcat: ровно 6 аргументов, позиции 0-3 и 5 — константы, pos 4 matches `^--pid=\d+$` (OWASP A03: нет shell-wrapping) | `LogcatReaderTest.argvShape_exactlySixArgs_knownPositions` | covered |
| 14 | Truth table баннера — все ячейки: (T,F)→true, (T,T)→false, (F,F)→false | `CrashStateTest.crashLogExists_noDismissed_flowTrue`, `bothExist_flowFalse`, `neitherExists_flowFalse`, `freshInstall_noLogsDir_flowFalseNoException` (второй F,F без директории вообще) | partial — см. Finding 1 |
| 15 | `markDismissed` / `clear` / `refresh` transitions | `CrashStateTest.markDismissed_flipsToFalseAndCreatesFlag`, `clear_deletesBothFiles`, `refresh_rereadsAfterExternalChange` | covered |
| 16 | Границы 7-tap: 7 внутри окна, gap >2s reset, gap=2s inclusive, gap=2s+1ns exclusive, <7 no trigger, 8-й tap не retriggers | `TapCounterTest` × 6 тестов (`sevenTaps_withinWindow_triggersOnSeventh`, `gapOverTwoSeconds_resetsCounter`, `gapExactlyTwoSeconds_inclusive`, `gapTwoSecondsPlusOneNs_exclusive`, `fewerThanSeven_noTrigger`, `eighthTapDoesNotRetrigger`) | covered |
| 17 | Overwrite-семантика `crash.log` (повтор handler'а сохраняет только последний stacktrace, а не конкатенирует) | `CrashHandlerTest.repeatedCrashes_overwriteNotAppend` (assertTrue на содержимое second, assertFalse на наличие first-маркера) | covered |
| 18 | Cross-component контракт `.dismissed`: handler удаляет flag ↔ state снова эмитит true | `CrashHandlerTest.deletesDismissedFlagOnNewCrash` (writer side) + `CrashStateTest.dismissedThenNewCrash_reappears` (reader side после refresh) — обе стороны | covered |
| 19 | `crash.log` пишется ДО вызова `Killer` (гарантия «запись жива при смерти процесса») | `CrashHandlerTest.writesCrashLogBeforeKiller` — `RecordingKiller` читает `crash.log` внутри `kill()` и стэшит байты; ассерт на RuntimeException + "boom" в стэшенных байтах | covered |
| 20 | Internal failure handler'а (writer бросает) → killer вызывается один раз, не зацикливается | `CrashHandlerTest.handlerInternalFailure_killsOnce` (throwingWriter seam) | covered |
| 21 | Null-message exception не даёт литерал «null» в `crash.log` | `CrashHandlerTest.nullMessageException_rendersEmptyMessageNotLiteralNull` (assertTrue `contains("\nmessage: \n")` + assertFalse `contains("message: null")`) | covered |
| 22 | Handler shape (Decision 10) — нет suspend-методов, нет Continuation в сигнатурах | `CrashHandlerTest.handlerShape_noSuspendCallsNoCoroutineImports` (reflection over `declaredMethods`) | covered |
| 23 | `writeTo` IO: happy / IOException / null OutputStream | `LogExportManagerTest.writeTo_happyPath_writesContent`, `writeTo_ioException_surfaces`, `writeTo_nullOutputStream_surfacesAsIoException` (ассерты на точное e.message — `openOutputStream returned null`) | covered |
| 24 | Non-Hilt инстанциация `LogExportManager` (Decision 5 — `:crash` без DI) | `LogExportManagerTest.nonHiltConstruction_instantiatesWithoutInjection` | covered |
| 25 | Process-name guard (Decision 4) — main install + `:crash` skip | `SanctumApplicationTest.installsCrashHandlerInMainProcess`, `skipsInstallInCrashProcess` (оба с `assertSame(SentinelHandler, after)` на skip-ветке) | covered |
| 26 | Fresh-install инвариант `CrashState` — папки `logs/` нет, `refresh()` не падает | `CrashStateTest.freshInstall_noLogsDir_flowFalseNoException` | covered |

**Итог матрицы:** 25/26 covered, 1 partial (Finding 1 в §9). Все измерения из задания — в таблице.

## 3. Assertion Quality

Выборка содержательных ассертов (все с direction / exact bytes / exact markers, а не `assertNotNull`):

- `LogExportManagerTest.crashLogHeadTruncation_preservesHeadAndMarks:183`
  `assertTrue(crashSection.contains(headMarker))` + `assertTrue(crashContent.endsWith("[truncated at 100 KB]"))` — проверяется **направление усечения**: уникальный head-marker должен выжить, маркер truncation — в конце.
- `LogExportManagerTest.logcatTailTruncation_preservesTailAndMarks:163`
  `assertTrue(logcatSection.contains(tailMarker))` в конце + `logcatSection.take(200).contains("[truncated: head")` — обратное направление: tail выжил, маркер в голове.
- `LogExportManagerTest.allSectionsPresent_orderedCorrectly:82-97`
  Индексно-транзитивная цепочка `crashIdx < errorsIdx < errors1Idx < logcatIdx` **плюс** маркеры в правильных секциях — устойчиво к форматным изменениям.
- `LogExportManagerTest.writeTo_nullOutputStream_surfacesAsIoException:273`
  `assertEquals("openOutputStream returned null", e.message)` — фиксирует **точный контракт сообщения**, а не просто факт IOException. Защита от рефакторинга на `!!`.
- `LogcatReaderTest` × 4 placeholder-ветки — **byte-exact** строковые equals (`assertEquals("[logcat unavailable: exit=13]", reader.read())`), не `contains`.
- `LogcatReaderTest.argvShape_exactlySixArgs_knownPositions:93-102`
  Шесть отдельных ассертов на позиции argv + regex `^--pid=\d+$` на pos 4 — защищает Decision 8 + OWASP A03.
- `DeviceInfoCollectorTest.headerFormatting_deterministicFromStub:48`
  `assertEquals(expected, header)` на полностью собранный многострочный header — **snapshot-like, но явно inlined**, не бинарный `toMatchSnapshot()`.
- `DeviceInfoCollectorTest.emptyDownloadedModels_rendersNonePlaceholder:60-66`
  Позитивный `contains("downloaded models:\n  (none)\n")` + **негативный** `assertFalse contains("downloaded models:\n\n")` — явно закрывает регрессию «пустой заголовок со следующей пустой строкой».
- `CrashHandlerTest.repeatedCrashes_overwriteNotAppend:83`
  `assertFalse(content.contains("first-boom"))` — **запрет конкатенации**, не просто «второй stacktrace есть».
- `CrashHandlerTest.nullMessageException_rendersEmptyMessageNotLiteralNull:166-173`
  Позитив `contains("\nmessage: \n")` + негатив `!contains("message: null")` — парный ассерт, ловит Kotlin's `$` toString-fallback.
- `CrashStateTest.dismissedThenNewCrash_reappears:132-135`
  Четыре направленных ассерта (`killer invoked once`, `dismissed deleted`, `crash.log written`, `flow flips to true`) — **полный** end-to-end контракт в одном тесте без лишнего.
- `SanctumApplicationTest.skipsInstallInCrashProcess:77-80`
  `assertSame(SentinelHandler, after)` — не просто `!is CrashHandler`, а **идентичность** с пре-существующим handler'ом. Защищает инвариант «в `:crash` вообще ничего не происходит».

### Sugar / assertNotNull usage review

`grep assertNotNull` в аудируемых файлах — 2 попадания, оба **легитимны**:

- `CrashHandlerTest:49` — `assertNotNull("crash.log must exist before kill()", killer.observedBytes)` — пре-чек перед немедленным `String(bytes!!)` + содержательным `contains("RuntimeException")`. Без `assertNotNull` тест бы упал NPE без контекста. Acceptable.
- `LogExportManagerTest:286` — `assertNotNull(out)` в `nonHiltConstruction_instantiatesWithoutInjection`, сразу за ним `assertTrue(out.isNotEmpty())` и `contains("=== crash.log ===")` + `contains("[logcat available only via About export]")`. assertNotNull здесь избыточен (Kotlin `String` non-null by type), но безвреден. Finding 2 — nit.

**Ни одного теста-сахарина** (только `assertNotNull` / только `expect(true).toBe(true)` / только mock.toHaveBeenCalled) не найдено.

## 4. Style Consistency vs `ErrorLogTest`

Эталон — `core-runtime/src/test/kotlin/app/sanctum/machina/core/log/ErrorLogTest.kt`. Чек-лист:

| Признак | ErrorLogTest | Аудируемые файлы | Отклонения |
|---|---|---|---|
| `@RunWith(RobolectricTestRunner::class)` | ✓ | CrashHandlerTest ✓, CrashStateTest ✓, LogExportManagerTest ✓, LogcatReaderTest ✓, SanctumApplicationTest ✓ | DeviceInfoCollectorTest и TapCounterTest — pure-JVM. **Правильное отклонение:** DeviceInfoCollector работает через `DeviceInfoProvider` interface, Android SDK не трогает; TapCounter по AC "не содержит ни одного Android-импорта". Отсутствие Robolectric в них экономит время suite и подчеркивает независимость. Оба файла имеют KDoc-обоснование. |
| `@Config(sdk = [33])` | ✓ | Все 5 Robolectric-файлов ✓ | Нет. |
| `runTest` для suspend | ✓ (errorLog.e suspend) | LogExportManagerTest ✓ на всех suspend buildExport/writeTo. CrashHandlerTest / CrashStateTest / LogcatReaderTest / SanctumApplicationTest / DeviceInfoCollectorTest / TapCounterTest — публичные API не suspend, `runTest` не требуется. | Нет. |
| Seed + teardown `filesDir/logs/` в `@Before`/`@After` (симметричный cleanup) | ✓ | CrashHandlerTest ✓ (строки 27-39), CrashStateTest ✓ (26-39), LogExportManagerTest ✓ (33-46), SanctumApplicationTest ✓ (snapshot/restore `DefaultUncaughtExceptionHandler` + `processName`). | Нет. |
| Hand-rolled fakes | ✓ (ErrorLogTest — no fakes needed) | RecordingKiller / CountingKiller / StubCommandRunner / ThrowingCommandRunner / RecordingCommandRunner / StubDeviceInfoProvider / SentinelHandler / ThrowingOutputStream — все private nested class, no framework. | Нет. |
| Отсутствие Mockito/MockK | ✓ | `grep "org.mockito\|io.mockk"` по аудируемым файлам → **0 реальных импортов**. Единственное вхождение строки — в KDoc-комментарии LogcatReaderTest.kt:12 «no Mockito/MockK per task rules» (мета-объяснение). `libs.versions.toml` тоже не содержит Mockito/MockK. Соответствие code-research §9.3. | Нет. |

**Вывод:** стиль консистентен с `ErrorLogTest`, все отклонения обоснованы доменом (Android-free классы).

## 5. Pyramid Balance

Size M (tech-spec frontmatter). Unit-only strategy обоснована **code-research §9.1**:

> «C:/AI-WORK/PhoneWrap/app/src/test/kotlin/...» — только unit. «No androidTest / instrumentation suites — explicitly deferred (`CameraBottomSheetTest.kt:24-27` KDoc). Compose-ui-test is not on the classpath. No Robolectric setup for `:app` activities beyond bottom-sheet helpers.»

**47 unit-тестов** (CrashHandlerTest 7 + CrashStateTest 8 + LogExportManagerTest 15 + DeviceInfoCollectorTest 3 + LogcatReaderTest 6 + TapCounterTest 6 + SanctumApplicationTest 2) распределены по ~200 строкам продакшен-кода — отношение разумное.

**Явно отнесено к ручной user-верификации** (см. user-spec «Как проверить / Пользователь проверяет»):

- SAF-диалог `ActivityResultContracts.CreateDocument("text/plain")` — требует instrumentation, отсутствующего в проекте. Устное подтверждение правильного suggested filename (`sanctum-log-YYYYMMDD-HHmm.txt`) и cancel-path перенесено на Honor 200.
- `:crash` process boundary — `android:process=":crash"` переживает `Process.killProcess`; поведение нельзя проверить вне реального Android framework. Пользователь триггерит через 7-tap dev-gesture.
- Dispatch `Thread.setDefaultUncaughtExceptionHandler` платформой — мы тестируем handler **сам по себе** через прямой вызов `handler.uncaughtException(...)` (CrashHandlerTest) и тестируем **установку** handler'а через `app.onCreate()` (SanctumApplicationTest), но сам факт того, что JVM-ART правильно вызовет установленный handler при реальной uncaught exception — не покрывается и покрыт быть не может unit-тестом. Делегировано ручному тест-крашу через dev-gesture.
- Banner reappearance across cold-starts — process lifecycle, только на устройстве.

Все три принципиально instrumentation-only поверхности **явно перечислены** в user-spec и tech-spec. Никакой «маскировки» этих пробелов flaky unit-тестом или фейковым E2E.

## 6. Seam Coverage

| Seam | Где определён | Тестовый дублёр (dubhel) | Статус |
|---|---|---|---|
| `Killer` | `app/.../crash/Killer.kt` | `RecordingKiller` (CrashHandlerTest:176-186, читает crash.log внутри kill() и фиксирует bytes) + `CountingKiller` (CrashStateTest:138-143, только callCount) | ✓ hand-rolled, не-Mockito |
| `CommandRunner` | `app/.../logexport/LogcatReader.kt` | `StubCommandRunner` (и LogExportManagerTest, и LogcatReaderTest) + `ThrowingCommandRunner` (LogExportManagerTest — AssertionError если вызван из CrashReport-пути) + `RecordingCommandRunner` (LogcatReaderTest — для argv-shape assert) | ✓ три разных fake под три разные роли |
| `DeviceInfoProvider` | `app/.../logexport/DeviceInfoCollector.kt` | `StubDeviceInfoProvider` (DeviceInfoCollectorTest:80-108) + anon `stubProvider()` (LogExportManagerTest:336-355) | ✓ hand-rolled |
| `crashLogWriter: (File, String) -> Unit` (function-type seam) | `CrashHandler` конструктор | `throwingWriter` lambda в CrashHandlerTest.handlerInternalFailure_killsOnce | ✓ function-type seam, без интерфейса — легко |
| `openOutputStreamForTest: ((Uri) -> OutputStream?)?` | `LogExportManager` (var setter для теста) | `ByteArrayOutputStream` (happy), `ThrowingOutputStream` (IOException), `{ null }` (null-return) | ✓ три ветки |

Все seam-ы покрыты **осмысленными fake-реализациями**. Ни один не подменяется `mock()` + `when().thenReturn()` (что и не мог бы — MockK/Mockito нет на classpath).

## 7. Negative Paths

- `LogExportManagerTest.writeTo_ioException_surfaces` — `ThrowingOutputStream` бросает IOException в `write()`; catch проверяет `e.message == "forced write failure"`. ✓
- `LogExportManagerTest.writeTo_nullOutputStream_surfacesAsIoException` — `openOutputStreamForTest` возвращает `null`; assertEquals `"openOutputStream returned null"`. ✓
- `LogcatReaderTest` четыре `[logcat unavailable: *]` ветки: empty / exit=13 / timeout / unknown — byte-exact assertEquals. ✓
- `CrashHandlerTest.handlerInternalFailure_killsOnce` — writer бросает IOException; `killer.callCount == 1`, без re-entry. ✓
- `CrashStateTest.freshInstall_noLogsDir_flowFalseNoException` — нет `logs/`, `refresh()` не бросает, flow = false. ✓
- `LogExportManagerTest.freshInstall_noLogsDir_succeeds` — то же для экспортёра, возвращает осмысленный header + `[empty]`-секции. ✓

## 8. Reflection / Argv Invariants

- **`CrashHandlerTest.handlerShape_noSuspendCallsNoCoroutineImports`** (lines 142-153): итерирует `CrashHandler::class.java.declaredMethods`, для каждого — `for (p in m.parameterTypes)` и `assertFalse(p.name == "kotlin.coroutines.Continuation")`. Защищает Decision 10 (handler не должен стать suspend — dispatcher может быть мёртв в момент uncaught) **без хрупкого совпадения по тексту исходника**. Reflection на JVM-level имена классов — стабильный инвариант. ✓
- **`LogcatReaderTest.argvShape_exactlySixArgs_knownPositions`** (lines 84-103): `RecordingCommandRunner` фиксирует argv, затем 6 ассертов на позиции + regex `^--pid=\d+$` на pos 4. Защищает Decision 8 + **OWASP A03 injection** (нет shell-wrapping, фиксированные strings + `Process.myPid()` integer). ✓

Оба инварианта — **правильно** reflection/argv-level, а не grep по исходнику; не ломаются при переименовании файлов.

## 9. Findings

### Finding 1 — CrashState truth-table, четвёртая ячейка не покрыта явно

- **Severity:** minor (nit)
- **Где:** `app/src/test/kotlin/app/sanctum/machina/crash/CrashStateTest.kt`
- **Проблема:** Истинностная таблица `hasUnresolvedCrash = crash.log ∧ ¬dismissed` имеет 4 ячейки:
  1. `(T, F) → true` — `crashLogExists_noDismissed_flowTrue` ✓
  2. `(T, T) → false` — `bothExist_flowFalse` ✓
  3. `(F, F) → false` — `neitherExists_flowFalse` + `freshInstall_noLogsDir_flowFalseNoException` ✓
  4. **`(F, T) → false`** — НЕ тестируется отдельно. Сценарий «`.dismissed` остался с прошлого раза, но `crash.log` был очищен вручную / `clear()`-ом».
- **Риск:** хотя логика AND математически делает ячейку 4 эквивалентной ячейке 3, рефакторинг на OR / de Morgan / ранний `return false if dismissed exists` мог бы тихо пройти — никакой тест не упадёт.
- **Recommended fix:** добавить однострочный тест:
  ```kotlin
  @Test
  fun onlyDismissedFlag_flowFalse() {
      logsDir.mkdirs()
      dismissedFlag.createNewFile()
      state.refresh()
      assertFalse(state.hasUnresolvedCrash.value)
  }
  ```
- **Блокирует Final Wave:** нет.

### Finding 2 — `assertNotNull(out)` в `nonHiltConstruction_instantiatesWithoutInjection` избыточен

- **Severity:** nit
- **Где:** `LogExportManagerTest.kt:286`
- **Проблема:** `out: String` по типу non-null, следующий `assertTrue(out.isNotEmpty())` уже покрывает non-null + non-empty. `assertNotNull` шумит (и единственное его упоминание, кроме CrashHandlerTest-pre-check).
- **Recommended fix:** удалить строку 286.
- **Блокирует Final Wave:** нет.

### Finding 3 — `CrashHandlerTest.stacktraceOver100KB` tolerance window немного широк

- **Severity:** nit
- **Где:** `CrashHandlerTest.kt:123-125`
- **Проблема:** `bytes.size >= maxBytes - 1024` (толерантность 1 KB снизу) в теории пропустит недобор вплоть до 99 KB при теоретической регрессии «writer пишет меньше, чем просят». С `20000` кадрами реальный размер будет ~2.5 MB pre-truncation → практически risk почти нулевой.
- **Recommended fix:** сузить до `>= maxBytes - 128` (overhead marker + newline), либо вовсе убрать lower-bound (upper-bound + `endsWith("[truncated at 100 KB]")` уже доказывают, что truncation сработала).
- **Блокирует Final Wave:** нет.

**Critical findings:** 0. **Major findings:** 0. **Minor findings:** 0 (все три — nit).

## 10. Verdict

**APPROVE** — no fixes required before Final Wave.

Суммарная картина:
- 47 новых unit-тестов, все зелёные (`./gradlew :app:test` → BUILD SUCCESSFUL in 21s).
- 25/26 запрошенных измерений покрытия покрыты направленными ассертами; 1 partial (ячейка (F,T) truth-table) — nit.
- 0 Mockito/MockK-импортов, 100% hand-rolled fakes, консистентно с `ErrorLogTest`.
- 2 `assertNotNull` на 47 тестов, оба легитимны как pre-check; 0 тестов-сахарин.
- Пирамида корректна для size M unit-only, instrumentation-gaps явно документированы в user-spec как manual user-verification.
- Reflection + argv-shape инварианты защищают Decision 10 и OWASP A03 на правильном уровне абстракции.

**Что должно случиться для разблокировки Task 11 pre-deploy QA:** ничего. Test Audit пройден.

Опциональные nit-фиксы (Findings 1-3) можно:
- либо подхватить в Final Wave Task 11 по ходу (Finding 1 — одна строка в новый @Test),
- либо оставить как backlog-задел на следующий test-pass,
- либо проигнорировать полностью (риск каждой — околонулевой).

Решение по фиксам — за пользователем / на усмотрение Task 11 агента.
