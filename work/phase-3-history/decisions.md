# Decisions Log: phase-3-history

Agent reports on completed tasks. Each entry is written by the agent that executed the task.

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

## Task 0: Design System

**Status:** Done
**Commit:** 72e9550
**Agent:** main agent
**Summary:** Создан полный дизайн-фундамент Sanctum Machina: SanctumColors (15 токенов для light/dark), SanctumTypography (Cormorant Garamond/Inter/JetBrains Mono через Google Fonts), Theme.kt заменён с dynamic color на Sanctum ColorScheme + Shapes (2dp/22dp), SanctumIncognitoTheme, SanctumIcons (21 ImageVector), SmSigil (Canvas composable). Ключевое отклонение: task говорит "13 токенов" — в действительности 15 полей в SanctumColors (дизайн-файл содержит accentInk + incognitoInk сверх 13 перечисленных).
**Deviations:** SanctumColors содержит 15 полей вместо указанных в AC 13 — это соответствует дизайн-файлу sanctum-tokens.jsx, где есть accentInk и incognitoInk. SanctumIcons размещён в пакете `ui/` вместо `ui/theme/` — архитектурно правильнее.

**Reviews:**

*Round 1:*
- code-reviewer: 3 findings (1 major — letterSpacing em, 2 minor — staticCompositionLocal, dead arc code) → [logs/working/task-0/code-reviewer-1.json]
- test-reviewer: 2 notes (оба informational) → [logs/working/task-0/test-reviewer-1.json]

*Fixes applied after round 1:*
- letterSpacing 0.18sp → TextUnit(0.18f, Em)
- compositionLocalOf → staticCompositionLocalOf
- Redundant degenerate arcs removed from filled circles

**Verification:**
- `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (app-debug.apk собран)
- User: установить APK на Honor 200 и проверить визуально (пергаментный фон, Cormorant Garamond, 2dp скругления)

## Task 1: Room foundation

**Status:** Done
**Commit:** b9f47f1 (impl), fa3b7e1 (review round 1 fixes)
**Agent:** main agent
**Summary:** Добавлен Room 2.7.1 в version catalog и `:app` (runtime/ktx/compiler через KSP, testing в androidTest). Создана v1-схема `sanctum.db`: `ChatEntity` (`chats`, индекс по `last_message_at`, `project_id` nullable без FK — Decision 1), `MessageEntity` (`messages`, FK `chat_id → chats.id ON DELETE CASCADE`, индексы по `chat_id` и `created_at`, `text NOT NULL DEFAULT ''`). DAO — только `suspend`/`Flow`, без блокирующих методов. `SanctumDatabase.create()` ставит `PRAGMA foreign_keys = ON` через `addCallback.onOpen`; callback вынесен во внутренний `val`, чтобы instrumented-тесты могли подключать его явно и реально проверять production-путь. Схема JSON сгенерирована и закоммичена в `app/schemas/app.sanctum.machina.data.SanctumDatabase/1.json` (AC-R5).
**Deviations:** KSP-аргумент `room.schemaLocation` размещён в top-level блоке `ksp { ... }` (идиоматично для KSP 2.x), а не внутри `android { defaultConfig { ... } }`, как буквально написано в task. Поведение идентично: `$projectDir/schemas` → `app/schemas/`. Дополнительно добавлены `androidx-test-runner` 1.6.2 и `androidx-test-ext-junit` 1.2.1 — без `ext:junit` не работает `@RunWith(AndroidJUnit4::class)`.

**Reviews:**

*Round 1:*
- code-reviewer: 1 major (T1-M1 — тесты обходили production callback), 4 minor, 3 nit → [logs/working/task-1/code-reviewer-1.json](logs/working/task-1/code-reviewer-1.json)
- security-auditor: APPROVED, 1 low + 3 info → [logs/working/task-1/security-auditor-1.json](logs/working/task-1/security-auditor-1.json)
- test-reviewer: 2 major (false-positive FK-тест; `observeAllEmitsOnInsert` без живого коллектора) + minors → [logs/working/task-1/test-reviewer-1.json](logs/working/task-1/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: APPROVED, T1-M1 полностью снят → [logs/working/task-1/code-reviewer-2.json](logs/working/task-1/code-reviewer-2.json)
- test-reviewer: APPROVED, оба major снятыми, новые тесты специфичны → [logs/working/task-1/test-reviewer-2.json](logs/working/task-1/test-reviewer-2.json)

**Verification:**
- `./gradlew :app:kspDebugKotlin` → BUILD SUCCESSFUL
- `app/schemas/app.sanctum.machina.data.SanctumDatabase/1.json` → сгенерирован, поля/индексы/FK соответствуют tech-spec Data Models
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL
- `./gradlew :app:assembleDebugAndroidTest` → BUILD SUCCESSFUL (все тесты компилируются)
- `./gradlew :app:connectedAndroidTest` → **BUILD SUCCESSFUL** на Honor 200 (ELI-NX9, Android 16): 18/18 instrumented-тестов прошли (ChatDaoTest 6, MessageDaoTest 7, SanctumDatabaseTest 5). Промежуточная итерация (19 тестов) отловила, что `RoomDatabase.isOpen` ложный до первого запроса — тест `freshDbOpens` (который оба ревьюера отмечали как low-value) удалён, остальные тесты покрывают открытие БД содержательно.

## Task 2: Settings + core fixes

**Status:** Done
**Commit:** 6c673d4 (impl), e2b102b (review round 1 fix)
**Agent:** main agent
**Summary:** Расширен `app_settings.proto` тремя `optional` полями (`default_model_id`, `last_used_model_id`, `settings_keys_migrated`); `AppSettingsRepository` получил 8 новых методов (get/set/observe для default и last-used + сентинель миграции). `Model` обзавёлся `val modelId: String = ""`, который пробрасывается через `AllowedModel.toModel()`. `ErrorLog.ALLOWED_COMPONENTS` расширен до 14 (+ 6 Phase-3 компонентов). `AndroidDeviceInfoProvider` получил Hilt-конструктор с `ModelRegistry` (через thunk-паттерн) и non-Hilt secondary ctor `(Context)` для `:crash`-процесса — сохранён null-стаб Decision 10. Создан `SettingsMigrationHelper` — одноразовый атомарный ре-кей `per_model_overrides` с `Model.name` на `Model.modelId`; сентинель и ре-кей слиты в один `updateData`-transform (защита от kill-window между записями).
**Deviations:**
- Тесты репозитория добавлены в существующий `AppSettingsRepositoryTest.kt` (тот же класс, тот же фикстура) вместо нового `DefaultAppSettingsRepositoryTest.kt` из TDD Anchor — избегаем дублирования `@Before` setup.
- В `AndroidDeviceInfoProvider` вместо `registry: ModelRegistry?` (как подсказывал task) использован thunk `() -> List<ModelEntry>` — Kotlin `?` и non-null конструкторы сталкиваются по JVM-сигнатуре; thunk сохраняет тот же `LogExportManager(context)` контракт без NullRegistry-заглушки.
- Первая версия мигратора записывала сентинель отдельным `updateData` (через `repository.markSettingsMigrated()`) — security-auditor (medium) выявил kill-window между двумя записями; fix объединяет сентинель и ре-кей в один атомарный transform.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED, 5 minor/nit → [logs/working/task-2/code-reviewer-1.json](logs/working/task-2/code-reviewer-1.json)
- security-auditor: APPROVED, 1 medium (non-atomic sentinel write), 1 low (defense-in-depth) → [logs/working/task-2/security-auditor-1.json](logs/working/task-2/security-auditor-1.json)
- test-reviewer: APPROVED, 4 nits → [logs/working/task-2/test-reviewer-1.json](logs/working/task-2/test-reviewer-1.json)

*Round 2 (after fixes):*
- security-auditor: APPROVED, medium закрыт, regression-test `migration_writesRekeyAndSentinel_inSingleAtomicWrite` зафиксирован → [logs/working/task-2/security-auditor-2.json](logs/working/task-2/security-auditor-2.json)

**Verification:**
- `./gradlew :core-runtime:testDebugUnitTest :core-settings:testDebugUnitTest` → BUILD SUCCESSFUL
- `./gradlew :app:testDebugUnitTest --tests "app.sanctum.machina.data.SettingsMigrationHelperTest"` → BUILD SUCCESSFUL (6 миграционных тестов)
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (полный набор `:app` unit-тестов, без регрессий Phase 2.5)
- `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL (Hilt-граф собирается: `DataStore<AppSettings>`, `ModelRegistry`, `ErrorLog`, `AppSettingsRepository` — все биндинги на месте)
- `grep -c "engine-warmup\|history-read\|history-write\|attachment-save\|attachment-read\|\"model\"" core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt` → `6` (AC smoke)

## Task 3: ModelRegistry — activeModelName StateFlow

**Status:** Done
**Commit:** b2afb96 (impl), 3dba5f7 (review round 1 fix)
**Agent:** main agent
**Summary:** `ModelRegistry` получил `val activeModelName: StateFlow<String?>`; в `DefaultModelRegistry` реализован как производный StateFlow через `map + stateIn` из `_models`, эмитит `model.modelId` (стабильный HF id) единственной `Ready`-записи или `null`. Логика вынесена во внутренний top-level `deriveActiveModelName(models, scope)` — единственный production call site в классе, что позволяет напрямую тестировать деривацию без построения всего registry-графа. Тестовый файл `ModelRegistryActiveModelTest` содержит 7 тестов: 5 helper-driven (initial null, modelId projection, Idle/Failed transitions, J5-атомарность), 1 mixed-list (пинит `firstOrNull { Ready }` предикат) и 1 real-class wiring test под Robolectric, конструирующий `DefaultModelRegistry` с hand-rolled `NoOp*` стабами и проверяющий `activeModelName` через writer-loop + `first { ... }` с UUID-probe, чтобы обойти гонку с init-block scan.
**Deviations:**
- TDD anchor просил 6 тестов (5 fake-driven + 1 real wiring). Фактически 7 — добавлен `activeModelName_withMixedList_picksOnlyReadyEntry` после test-reviewer round 1, чтобы зафиксировать `firstOrNull` предикат (раньше все тесты использовали одно-элементные списки).
- Wiring test не использует TestScope для registry scope (как предполагал task) — `DefaultModelRegistry` создаёт scope внутри, refactoring для injection выходил за рамки задачи. Вместо этого применён writer-loop на `Dispatchers.Default` + `withTimeout + first`, что race-proof против init-block scan и не требует изменения сигнатуры конструктора.
- `_models` поле повышено с `private val` до `@VisibleForTesting internal val` для детерминированной мутации из wiring-теста. Read-only проекция `models: StateFlow<List<ModelEntry>>` остаётся публичным API.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED_WITH_SUGGESTIONS, 4 minor (KDoc orphaning D24, test 6 not real wiring, override comment duplication, `assertEquals(true, ...)` idiom) → [logs/working/task-3/code-reviewer-1.json](logs/working/task-3/code-reviewer-1.json)
- test-reviewer: NEEDS_IMPROVEMENT, 1 major (test 6 wiring gap) + 3 minor (mixed-list coverage, tautology assertion, test 5 name) + 2 nit → [logs/working/task-3/test-reviewer-1.json](logs/working/task-3/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: APPROVED, все round-1 findings закрыты, 3 optional minors (as Any casts, scope leak в wiring-тесте, `_models` ослабленная инкапсуляция) → [logs/working/task-3/code-reviewer-2.json](logs/working/task-3/code-reviewer-2.json)
- test-reviewer: PASSED, 2 non-blocking minors (scope leak при teardown, writer-loop комментарий переоценивает механизм) → [logs/working/task-3/test-reviewer-2.json](logs/working/task-3/test-reviewer-2.json)

**Verification:**
- `./gradlew :core-runtime:testDebugUnitTest` → BUILD SUCCESSFUL (7/7 новых тестов прошли, без регрессий в `ErrorLogTest`, `SystemInstructionTest`, `AllowlistLoaderTest`, `AudioClipTest`, `MediaUtils*Test`, `MultimodalContentsBuilderTest`)
- `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL (Hilt-граф резолвится с новым интерфейс-членом)

## Task 4: ChatRepository

**Status:** Done
**Commit:** 2c1a8b6 (impl), 99ebbd9 (review round 1 fixes)
**Agent:** main agent
**Summary:** Создан data-layer Phase 3: интерфейс `ChatRepository` + `DefaultChatRepository` (атомарный `commitDraftChat` через `database.withTransaction { … }` с rename внутри транзакции — если `rename()` возвращает false, бросаем IOException, Room откатывает оба INSERT'а; outer-catch чистит staging) + pure-функция `AutoTitleGenerator` (AC-U2: trim → collapse `\s+` → cut по последнему пробелу ≤20 + "…", fallback `"Чат от DD.MM HH:mm"`). `deleteChat` логирует `ErrorLog.e("history-write", ...)` если `deleteRecursively()` упал. `sweepZombieChats` удаляет чаты с 0 сообщений и отсутствующей директорией с логом по каждому. Тесты — 10 для AutoTitleGenerator + 11 для ChatRepository (Robolectric + hand-rolled FakeDAOs + TemporaryFolder + injectable rename/transactionRunner seam через `@VisibleForTesting` primary ctor).
**Deviations:**
- `commitDraftChat` принимает `stagingDir: File?` (вместо `File` из task) и **дополнительно** `filesDir: File` (4-й параметр, не указан в task signature) — после security-review T4-S1 (medium). Containment-check `stagingDir.canonicalPath.startsWith(filesDir/attachments + File.separator)` отбивает попытки stage-вне-attachments-tree до Room INSERT. Без filesDir этот check был бы привязан к `stagingDir.parentFile` — фрагильно (T4-m4).
- Rename перенесён ВНУТРЬ `database.withTransaction { … }` (вместо двухшаговой схемы из task: "после успеха transaction → rename; при падении → `chatDao.deleteById(chatId)`"). Причина — T4-S2 (low) security: двухшаговая схема имеет kill-window между rename-fail и deleteById. Внутри транзакции — Room откатывает автоматически. Outer try/catch чистит staging.
- Затронуты файлы вне scope: `SettingsMigrationHelperTest.kt` и `ChatViewModelTest.kt` — добавлен недостающий `override val activeModelName: StateFlow<String?>` в их FakeModelRegistry-стабы (Task-3 добавил член интерфейса, но эти fakes в Task 3 не были обновлены; без фикса `:app:compileDebugUnitTestKotlin` падал).
- Отложено до Task 5/8 (с пояснением в commit message): T4-m2 (focused `@Query("UPDATE …")` методы в `ChatDao` для `updateChatLastMessage` / `updateChatTitle`), T4-m3 (`suspend fun getAll()` в `ChatDao` вместо `observeAll().first()` в sweep), T4-T3 (assertion на dispatcher-hop), T4-T5 (отдельные unit-тесты на `updateChatTitle` / `updateChatLastMessage`). Все — изменения DAO либо out-of-scope для Task 4.
- Известная остаточная щель (документирована security-auditor round 2 как T4-S6, low): kill между успешным `rename(2)` и SQLite WAL-commit может оставить orphan-директорию без chat-row. Текущий `sweepZombieChats` ловит row-orphans, не dir-orphans. Лечится отдельным sweep в SanctumApplication (Task 6) — фиксируем как known follow-up.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED_WITH_SUGGESTIONS, 5 minor (deleteChat без warn-log, read-modify-write update, observeAll().first() в sweep, stagingDir.parentFile coupling, ctor seam visibility) + 4 nit → [logs/working/task-4/code-reviewer-1.json](logs/working/task-4/code-reviewer-1.json)
- security-auditor: APPROVED_WITH_SUGGESTIONS, 1 medium (T4-S1 containment), 1 low (T4-S2 rollback kill-window), 3 info → [logs/working/task-4/security-auditor-1.json](logs/working/task-4/security-auditor-1.json)
- test-reviewer: APPROVED_WITH_SUGGESTIONS, 10 minor/nit (test name overpromise, no in-tx rollback test, dispatcher hop, missing null/outside/update coverage, log-id substring, weak nullText assertion) → [logs/working/task-4/test-reviewer-1.json](logs/working/task-4/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: APPROVED, 4 optional nits (VisibleForTesting=PRIVATE vs INTERNAL, TODO breadcrumb, modelId logging contract, transactionRunner deep-copy helper) → [logs/working/task-4/code-reviewer-2.json](logs/working/task-4/code-reviewer-2.json)
- security-auditor: APPROVED, 1 low T4-S6 (orphan dir window — тех. долг для Task 6 sweep), 2 info → [logs/working/task-4/security-auditor-2.json](logs/working/task-4/security-auditor-2.json)
- test-reviewer: PASSED, 3 minor (helper-extension возможность, runner asymmetry, duplicated rollback closures) → [logs/working/task-4/test-reviewer-2.json](logs/working/task-4/test-reviewer-2.json)

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "app.sanctum.machina.data.AutoTitleGeneratorTest" --tests "app.sanctum.machina.data.ChatRepositoryTest"` → BUILD SUCCESSFUL (10 + 11 = 21 тестов, 0 failures, 0 errors)
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (полный `:app` unit-test без регрессий после фикса FakeModelRegistry-стабов)
- `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL (Hilt-граф резолвится — `DefaultChatRepository` готов к биндингу из `AppModule` в Task 5)

## Task 5: WarmupCoordinator + AppModule

**Status:** Done
**Commit:** b466277 (impl), 1fed4b5 (review round 1 fixes)
**Agent:** main agent
**Summary:** Создан `:app`-уровневый `WarmupCoordinator` (`@Singleton`, инжектит `ModelRegistry` + `AppSettingsRepository` + `ErrorLog`) — держит `warmupJob`, резолвит модель по AC-F5 (default → last-used → no-op), пишет `last_used_model_id` на успехе и `ErrorLog.e("engine-warmup", ...)` на фейле; `cancelAndRestart(modelId)` через `cancelAndJoin` снимает in-flight Job перед новым `initialize()` — mutex `lifecycleMutex` внутри `DefaultModelRegistry` гарантированно освобождён (user-spec Risk double-wait закрыт). `isWarmupInProgress: StateFlow<Boolean>` переключается в `true` до `cancelAndJoin` и сбрасывается в inner `finally` только если `warmupJob === coroutineContext[Job]` — без этого была бы видна транзиентная `false`-гэп между cancellation и стартом новой Job (Task-10 спиннер бы мигал). AC-F3 observer запускается в `init{}` и одноразово пишет `default_model_id` при первом `SUCCEEDED`-entry. `AppModule` (`@InstallIn(SingletonComponent)`, abstract + companion) биндит `ChatRepository ← DefaultChatRepository` и провайдит `SanctumDatabase` (через `SanctumDatabase.create(context)`), `ChatDao`, `MessageDao`. `WarmupCoordinator` резолвится автоматически — нужных `@Provides` нет.
**Deviations:**
- `WarmupCoordinator` имеет `@VisibleForTesting` primary-constructor, принимающий `CoroutineScope` — это seam для тестов (task этого не требовал явно, но иначе `Dispatchers.Default` из production-scope не ложится на test-scheduler). Production path через secondary `@Inject constructor` создаёт `CoroutineScope(SupervisorJob() + Dispatchers.Default)` — полностью по spec.
- Round-1 code-reviewer major: изначально `_isWarmupInProgress.value = true` стоял ПОСЛЕ `cancelAndJoin` — outgoing-Job's `finally` писал `false` первым, создавая flicker. Fix: `warmupJob = null` + `true` ПЕРЕД `cancelAndJoin`; inner `finally` проверяет владение через `warmupJob === coroutineContext[Job]`.
- Round-1 code-reviewer major: `setLastUsedModelId` и observer-body изначально не имели try/catch — DataStore failures уходили в logcat. Fix: `persistLastUsedModelId` helper + try/catch в observer, оба логируют через `ErrorLog.e("settings-io", ...)` (компонент уже был в `ALLOWED_COMPONENTS`).
- Round-1 test-reviewer major: тесты использовали `UnconfinedTestDispatcher` — task Implementation Hint явно указывает `StandardTestDispatcher`. Fix: переключение + 4 новых теста (bare cancellation, warmupDefault twice, empty list, first-of-multiple) + gated transition-observation в `isWarmupInProgress` тестах.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED_WITH_SUGGESTIONS, 2 major (flag flicker, unhandled exceptions в background) + 6 minor → [logs/working/task-5/code-reviewer-1.json](logs/working/task-5/code-reviewer-1.json)
- security-auditor: APPROVED, 0 findings — injection/logging/concurrency/exposure/storage — ни одного вектора → [logs/working/task-5/security-auditor-1.json](logs/working/task-5/security-auditor-1.json)
- test-reviewer: NEEDS_IMPROVEMENT, 3 major (terminal-state-only assertions в isWarmupInProgress тестах, dispatcher deviation) + 5 minor → [logs/working/task-5/test-reviewer-1.json](logs/working/task-5/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: APPROVED, 0 findings — ownership handshake через mutex корректен, новые тесты пинят контракты → [logs/working/task-5/code-reviewer-2.json](logs/working/task-5/code-reviewer-2.json)
- test-reviewer: PASSED, 0 findings — все 3 round-1 major закрыты, gated-гейты на CompletableDeferred работают, subscriptionCount-assertion пинит self-cancel → [logs/working/task-5/test-reviewer-2.json](logs/working/task-5/test-reviewer-2.json)

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "*.WarmupCoordinatorTest"` → BUILD SUCCESSFUL (13 тестов, 0 failures — 10 из TDD Anchor + 3 edge-cases)
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (полный `:app` unit-test без регрессий)
- `./gradlew :app:kspDebugKotlin` → BUILD SUCCESSFUL (Hilt code-gen резолвит `@Binds ChatRepository ← DefaultChatRepository` и auto-resolve `WarmupCoordinator` через `@Inject constructor`)

## Task 6: SanctumApplication rework

**Status:** Done
**Commit:** 6324975 (impl), bf10d40 (review round 1 fixes)
**Agent:** main agent
**Summary:** Расширен cold-start sequence в `SanctumApplication.onCreate` под `getProcessName() == packageName` guard: `warmupCoordinator.warmupDefault()` зовётся напрямую (сам запускает внутреннюю coroutine), а `settingsMigrationHelper.migrateIfNeeded()` и вынесенный `StartupHousekeeper.run()` запускаются на `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. `AppModule.provideSanctumDatabase` обёрнут в `try/catch(Exception)` вокруг `Room.databaseBuilder(...).build()` + принудительного `openHelper.writableDatabase`: при ошибке `sanctum.db` переименовывается в `sanctum.db.corrupt_{yyyyMMdd-HHmmss}` (Locale.ROOT), SQLite-sidecars (`-journal`/`-wal`/`-shm`) удаляются, `AppCorruptionState.corruptionOccurred` выставляется в `true` через `@Volatile`-поле, `ErrorLog.e("history-read", ...)` логирует фактический исход — rename выполнен / rename отвергнут+файл удалён / no-op. Fresh `build()` на следующей строке намеренно без try/catch — любой сбой уже на чистом FS должен уходить в CrashHandler, не в бесконечный recovery-loop.

**Deviations:**
- **Вынесен `StartupHousekeeper` (`@Singleton`, `open class`) вместо inline-кода в `onCreate`.** Task-текст предписывал housekeeping inline — отклонение оправдано: real-FS fail-пути (`quick/` purge throw, `.staging-*` cleanup throw, `sweepZombieChats` throw) иначе потребовали бы HiltAndroidRule-based интеграционного теста поверх всего application-graph, а `patterns.md` требует hand-rolled fakes и JUnit4+Robolectric. Выделенный класс с `@VisibleForTesting internal var deleter: (File) -> Unit` покрывает все три fail-ветки в `StartupHousekeeperTest` через синтетический `IOException`.
- **`WarmupCoordinator` и `SettingsMigrationHelper` помечены `open class` + `open fun warmupDefault()` / `open suspend fun migrateIfNeeded()`.** Чисто для `SanctumApplicationTest` recording-fakes — subclass через `@VisibleForTesting internal`-constructor. Альтернатива (интерфейс-seam) отложена: текущая поверхность теста стабильна, production-импакт нулевой, `code-reviewer-2` одобрил.
- **`sweepZombieChats` failure-branch логирует `"history-write"`, не `"attachment-save"`.** Task-текст AC-line указывал `attachment-save`, но code-reviewer-1 прав: `ChatRepository.sweepZombieChats` своими per-row delete-логами уже пишет `history-write` (ChatRepository.kt:77), а `patterns.md` требует 1:1 mapping component ↔ failure-mode. Принято round-1.
- **Побочный fix в `:core-runtime`: `DefaultDownloadRepository.workManager = by lazy {...}`.** Hilt `SingletonComponent` eager-init сломал `SanctumApplicationTest` (DownloadRepository'sctor обращался к `WorkManager.getInstance` под Robolectric без bootstrapped ContentProvider). Lazy deferrit до первого download-вызова — в production не меняет поведение, в тестах восстанавливает работоспособность graph'а.
- **`SanctumDatabase.create(context)` companion-factory удалён.** Dead code после того, как `AppModule.provideSanctumDatabase` стал единственной точкой сборки Room-инстанса с corruption-handler'ом. `ForeignKeysOnOpenCallback` остаётся (используется instrumented-тестами).
- **Round-1 code-reviewer major fixes:** (1) `buildSanctumDatabase` перенесён внутрь try-блока — раньше спек-указанный `build()`-throw уходил в CrashHandler; (2) `dbFile.renameTo()` return value теперь захватывается, при `false` — fallback-`delete()` чтобы fresh build не сел поверх corrupt-файла, log-line отражает реальный исход (`"renamed to X"` / `"rename refused — corrupt file deleted"` / `"no-op"`).
- **Round-1 security-auditor major fix:** `@Volatile` на `AppCorruptionState.corruptionOccurred` — без memory-barrier ARM-reader Task-10's `HomeScreen LaunchedEffect` (другой поток, чем Hilt injection) мог наблюдать stale `false` и пропустить "история повреждена" баннер.
- **Round-1 test-reviewer major fixes:** добавлены `testHousekeepingRunsInMainProcess` (positive: все три collaborator'а зовутся) + расширен `testHousekeepingSkippedInCrashProcess` на все три counter'а + `testMainActivityFqnAssignedEvenInCrashProcess` (lock-in out-of-guard контракта для DownloadWorker background-процесса) + `sweepZombieChatsFailureLogsHistoryWriteAndDoesNotThrow` + sidecar-assertions в `AppModuleCorruptionTest` + regex-check shape'а `corrupt_`-суффикса.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED_WITH_SUGGESTIONS, 2 major (build() outside try, renameTo return ignored) + 6 minor → [logs/working/task-6/code-reviewer-1.json](logs/working/task-6/code-reviewer-1.json)
- security-auditor: APPROVED, 5 minor (rename log integrity, symlink defence-in-depth, runBlocking main-thread, @Volatile missing, timestamp collision) → [logs/working/task-6/security-auditor-1.json](logs/working/task-6/security-auditor-1.json)
- test-reviewer: NEEDS_IMPROVEMENT, 2 major (warmup/migration invocation unverified, sidecar cleanup unverified) + 6 minor → [logs/working/task-6/test-reviewer-1.json](logs/working/task-6/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: APPROVED, 0 blockers, 2 optional minor (sidecar-delete duplication, ordering narrative) → [logs/working/task-6/code-reviewer-2.json](logs/working/task-6/code-reviewer-2.json)
- security-auditor: APPROVED, 2 findings resolved (#1, #4), 3 deferred без active exploit path (#2 symlink, #3 runBlocking ANR-class, #5 second-resolution timestamp) → [logs/working/task-6/security-auditor-2.json](logs/working/task-6/security-auditor-2.json)
- test-reviewer: PASSED, 5 findings resolved (#1, #2, #3, #4, #8), 3 deferred (duplication, interface seam, AppCorruptionStateTest worth) → [logs/working/task-6/test-reviewer-2.json](logs/working/task-6/test-reviewer-2.json)

**Verification:**
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (164 тестов, 0 failures — включая 2 AppCorruptionStateTest, 5 StartupHousekeeperTest, 5 SanctumApplicationTest)
- `./gradlew :app:compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL (`AppModuleCorruptionTest` скомпилирован; 2 instrumented-теста — `testCorruptDbRenamedAndFreshDbCreated`, `testNormalDbOpenNoCorruptionFlag`)
- `./gradlew build` → BUILD SUCCESSFUL (full project, включая lint)
- `./gradlew :app:connectedDebugAndroidTest` → **отложено** (нет подключённого устройства/эмулятора в текущей сессии; юзеру прогнать перед merge к main — покрывает AC "All new instrumented tests pass")

## Task 7: Navigation + HomeScreen

**Status:** Done
**Commit:** b3ba3f6 (impl), 6f4e8d9 (review round 1 fixes)
**Agent:** main agent
**Summary:** `SanctumApp.kt` переведён на новую навигацию Phase 3: `startDestination = "home"`, `NavHost` обёрнут в `ModalNavigationDrawer` (stub content — Task 9 заполнит), зарегистрированы типизированные chat-роуты `chat/quick?modelId={String?}`, `chat/draft`, `chat/{chatId:Long}`; старый `chat/{modelName}` оставлен как tombstone-редирект на `home` (комментарий `// TOMBSTONE: deprecated in Task 7, removed in Task 8`). Созданы `HomeScreen` (центрированная идентификация — SmSigil + "Sanctum Machina" + подзаголовок — в `HomeIdentity()`, primary `FilledTonalButton` "Новый быстрый чат" / secondary `TextButton` "Открыть историю" когда `hasDownloadedModels=true`, placeholder "Для начала работы скачайте модель." + "Открыть Model Manager" когда `false`; bottom status bar показывает `activeModelName` или "Модель не прогрета") и `HomeViewModel` (`hasDownloadedModels: StateFlow<Boolean>` через `registry.models.map { any { it.downloadStatus.status == SUCCEEDED } }.stateIn(viewModelScope, WhileSubscribed(5_000), false)` + pass-through `activeModelName: StateFlow<String?>`). Все пользовательские строки вынесены в `strings.xml`.
**Deviations:**
- **Tombstone вместо удаления `chat/{modelName}`.** AC указывает "Old route `chat/{modelName}` removed from the NavHost", но What-to-do / Details явно предписывают оставить tombstone с `LaunchedEffect` редиректом на `home`. Следую What-to-do: иначе deep-link в текущей сессии между Task 7 и Task 8 (которые идут подряд) вызвал бы route-not-found crash. Task 8 удалит tombstone.
- **`ModelManagerScreen.onLoad` обновлён на `navController.navigate("chat/quick?modelId=$modelId")` без `Uri.encode`.** Полная wiring (обработка modelId в ChatViewModel, preload, обратная связь) — Task 11; `Uri.encode` добавится вместе с ним (код-ревью `c3`).
- **HomeStatusBar — plain text, не chip+dot.** Design говорит "warmup state dot + model chip" — это Task 10 (TopAppBar state machine + incognito indicator + corruption banner); здесь оставлен TODO(Task 10) с примечанием про display-name resolution для сырого HF modelId.
- **Settings IconButton — `enabled = false`.** SettingsScreen появится в Phase 5; до тех пор кнопка не должна выглядеть кликабельной (no-op tap target). После round-1 code-review.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED_WITH_SUGGESTIONS, 0 critical / 0 major / 7 minor → [logs/working/task-7/code-reviewer-1.json](logs/working/task-7/code-reviewer-1.json)
- test-reviewer: PASSED, 0 critical / 0 major / 3 minor → [logs/working/task-7/test-reviewer-1.json](logs/working/task-7/test-reviewer-1.json)

*Fixes applied after round 1:*
- 4-space indent в HomeScreen.kt / HomeViewModel.kt (совпадает с `ui/modelmanager` соседями)
- Settings `IconButton(enabled = false)` — no-op до Phase 5
- Общий `HomeIdentity()` — дедуплицирован SmSigil + product name + kicker между ready / no-models ветками
- TODO(Task 10) на HomeStatusBar (dot+chip + display-name lookup)
- `subscribe` свёрнут в `createSubscribedViewModel()` фабрику — нельзя забыть коллектор для `WhileSubscribed` StateFlow
- Добавлен `hasDownloadedModels_initialSeedIsFalse_beforeAnyCollection` — пинит AC-F2 против случайного flip на `initialValue = true`
- `activeModelName_isPassedThrough` усилен до реактивного теста (null → "model-a" → "model-b")

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "app.sanctum.machina.ui.home.HomeViewModelTest"` → BUILD SUCCESSFUL (6 тестов, 0 failures — 4 TDD-anchor + initial-seed + reactive activeModelName)
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (полный `:app` unit-test, без регрессий)
- `./gradlew build` → BUILD SUCCESSFUL (full project, включая lint)
- User: установить APK на устройство и проверить: (1) приложение открывается на HomeScreen (не Model Manager); (2) hamburger открывает drawer слева; (3) с хотя бы одной скачанной моделью виден `FilledTonalButton` "Новый быстрый чат" (таппается → лендит на placeholder `chat/quick`); (4) без скачанных моделей виден placeholder "Для начала работы скачайте модель." + "Открыть Model Manager" (таппается → Model Manager).

## Task 8: ChatViewModel rework

**Status:** Done
**Commit:** a0f804c (impl), 05c9732 (review round 1), 6c267c9 (review round 2)
**Agent:** main agent
**Summary:** `ChatViewModel` переписан вокруг `ChatIdentity` sealed (`Quick` / `Draft` / `Persistent(id: Long)`), которая читается из `SavedStateHandle` в конструкторе. Императивный `init { registry.initialize(modelName) }` заменён на реактивный `combine(registry.models, _chatModelId)` observer, который маппит `ModelInitStatus` → `ChatUiState` и сохраняет `isGenerating` между Ready-эмиссиями. Для Persistent добавлена связка `messageDao.observeByChat(chatId)` × `_streamingMessage` через `combine` — инвариант двойного-пузыря реализован в единственной точке (combine лямбда подавляет streaming-overlay, как только Room-лист оканчивается на ASSISTANT). Draft-send вызывает `chatRepository.commitDraftChat` и эмитит `ChatNavigationEvent.NavigateToPersistent(chatId)` для host-composable (подключение — Task 10). `onCleared` больше не вызывает `registry.cleanup` (D5 / AC-E6). Quick/Draft пинятся на первый non-null `activeModelName` — после этого наблюдают тот же modelId, даже если engine уходит в Initializing/Failed. `SanctumApp.kt` получил `kind` nav-arg с `defaultValue`, чтобы различать Quick и Draft routes без chatId.
**Deviations:**
- **M2 (code-reviewer round 1) — закрывается архитектурно в Task 10.** `applyHeavySetting` продолжает вызывать `registry.cleanup` + `registry.initialize` напрямую (не через `warmupCoordinator.cancelAndRestart`). Вместо миграции кода Task 10 добавляет инвариант: Settings IconButton `enabled = false` пока `uiState != Ready(isGenerating = false) || reinitInProgress`. Это гарантирует, что `applyHeavySetting` стартует только на прогретом engine, когда `lifecycleMutex` свободен — race 50–60 s становится недостижимым. См. `tasks/10.md` → Acceptance Criteria → "Settings gate (Phase-3 debt 1)".
- **Draft-attachment persistence — закрыто в Task 17.** Task 8 передавал `stagingDir = null` в `commitDraftChat` и не сохранял вложения, прикреплённые в Draft. Task 17 добавил `writeAttachmentStaging` + `savePersistentAttachment` в `ChatRepository`, ввёл lazy `draftStagingDir` в VM и замкнул путь attachment → диск → Room-transaction-rename → `attachments/{chatId}/`. Debt 2 закрыт (см. `tasks/17.md`, commits 4fa6c89 → 263a507 → 17dce3a).
- **`chat/{modelName}` tombstone оставлен** из Task 7 (redirect to home) — удалять не стали, потому что deep-link из предыдущих сессий может всё ещё приходить.

**Reviews:**

*Round 1:*
- code-reviewer: changes_requested (2 major / 4 minor) → [logs/working/task-8/code-reviewer-1.json](logs/working/task-8/code-reviewer-1.json)
- security-auditor: approved (3 minor) → [logs/working/task-8/security-auditor-1.json](logs/working/task-8/security-auditor-1.json)
- test-reviewer: changes_requested (3 major / 7 minor) → [logs/working/task-8/test-reviewer-1.json](logs/working/task-8/test-reviewer-1.json)

*Fixes applied after round 1:*
- M1: `commitDraft` синхронно переводит uiState в `Ready(isGenerating = true)`; на failure возвращает `Ready(false)` — double-tap больше не проскакивает Send gate
- m2: убран `observePersistedForHandover` — combine в `buildMessagesFlow` остался единственным инвариантом
- m3/m4: удалены дублирующие `_reinitInProgress = false` внутри try; `first { it != null }!!` заменён на `mapNotNull { it }.first()`
- s2: `updateChatLastMessage` гейтится на успех `savePersistentMessage` (и для USER, и для ASSISTANT)
- T1: инвариант double-bubble теста усилен до "ASSISTANT count ≤ 1 в каждом emission" — независимо от streaming-флага
- T2: добавлены три Persistent-теста AC-R1 / AC-R2 / AC-R3 (USER до runInference через cross-fake event log, ASSISTANT только на done=true, stop() mid-stream не синтезирует ASSISTANT)
- T3: `engineStateTransition_readyDrivesReady` seed-ит Ready сначала, чтобы пинить `_chatModelId`, затем проходит Ready → Initializing → Ready

*Round 2:*
- code-reviewer: approved (1 cosmetic kdoc) → [logs/working/task-8/code-reviewer-2.json](logs/working/task-8/code-reviewer-2.json)
- security-auditor: approved (1 new minor s4) → [logs/working/task-8/security-auditor-2.json](logs/working/task-8/security-auditor-2.json)
- test-reviewer: passed → [logs/working/task-8/test-reviewer-2.json](logs/working/task-8/test-reviewer-2.json)

*Fixes applied after round 2:*
- s4: добавлен hard-gate в `runInferencePersistent` — если `savePersistentMessage(USER)` бросает, запускать inference нельзя (иначе на done=true в Room окажется ASSISTANT без предшествующего USER, что инвертирует AC-R3). Гейт восстанавливает Send-gate (Ready(isGenerating=false)) + эмитит `chat_load_failed_title` snackbar. ErrorLog.e вынесен в детачный `launch`, чтобы не блокировать uiState на `Dispatchers.IO`. Добавлен тест `persistentMode_userSaveFailure_abortsInference`.
- n1: устаревшие kdoc-ссылки на `observePersistedForHandover` заменены описанием combine как единственного механизма.

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "app.sanctum.machina.ui.chat.ChatViewModelTest"` → BUILD SUCCESSFUL (35 тестов, 0 failures: 9 TDD-anchor + 4 AC-R1/R2/R3/s4 + 22 унаследованных/адаптированных Phase-2 поведений)
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (полный `:app` unit-тест, без регрессий)
- `./gradlew :core-runtime:testDebugUnitTest :core-settings:testDebugUnitTest` → BUILD SUCCESSFUL

## Task 9: Drawer UI

**Status:** Done
**Commit:** 08f6769 (impl), 8018a28 (review round 1 fixes)
**Agent:** main agent
**Summary:** Создан `DrawerViewModel` + `DrawerContent` для Phase-3 drawer'а истории. VM комбинирует `chatRepository.observeChats()` с `registry.models` в `drawerUiState: StateFlow<DrawerUiState>` с секционированием по `LocalDate` в системном часовом поясе («Сегодня» / «Вчера» / «На этой неделе» / «Раньше»). `ChatRowUiModel.isModelAvailable` вычисляется eagerly как `entry.downloadStatus.status == ModelDownloadStatusType.SUCCEEDED` (Decision 7: on-disk predicate, не runtime-движок). `DrawerContent` использует Material 3 `SwipeToDismissBox` (swipe-to-confirm) + `combinedClickable` для long-press rename + tap с model-availability dialog. `DrawerEvent.PopBack` эмитится когда удалён текущий открытый чат; VM не держит ссылку на NavController — `DrawerContent` форвардит событие через `onPopCurrentChat` callback в `SanctumApp`, который делает `navController.popBackStack()`. Для blank-rename VM регенерирует auto-title через `messageDao.firstByChatIdAndRole(chatId, "user")` + `AutoTitleGenerator` без расширения `ChatRepository.updateChatTitle` signature. Тестов: 18 unit-тестов (hand-rolled fakes, `StandardTestDispatcher`, `Dispatchers.setMain`).
**Deviations:**
- **Swipe-to-confirm вместо swipe-to-reveal-button.** Task-text предписывал «swipe влево → красная кнопка "Удалить" → dialog», имплементировано как «swipe влево past threshold → dialog напрямую» (красный background + delete-icon видны во время свайпа). Material 3 `SwipeToDismissBox` нативно поддерживает этот паттерн; reveal-button-then-tap потребовал бы кастомного `AnchoredDraggableState`. AC-U3 intent (CASCADE DELETE только после явного подтверждения) сохранён. Code-reviewer-1 одобрил.
- **`messageCount` удалён из `ChatRowUiModel`, считается on-demand в `DeleteChatDialog`.** Task Details указывал `messageCount: Int` в ChatRowUiModel; выполнять COUNT(*) per-row per-emission дорого, а нужно только в delete dialog. `VM.getMessageCount(chatId): Int?` — nullable, возвращает null при I/O failure, dialog тогда показывает no-count body (без misleading "0 сообщ." перед CASCADE DELETE).
- **Новый `MessageDao.firstByChatIdAndRole(chatId, role) LIMIT 1`** добавлен вместо `getByChatId(chatId).firstOrNull { role == "user" }` — O(1) vs O(N) для blank-rename path. Все три test fakes (ChatRepositoryTest / ChatViewModelTest / DrawerViewModelTest) получили стабы нового метода.
- **`checkModelAvailable(chatId)` — `suspend fun`, читает из `chatDao.getById + registry.models.value`.** Изначально читал `drawerUiState.value.sections`, но StateFlow сбрасывается в `Initial` после `WhileSubscribed(5_000L)` без подписчиков → off-screen tap возвращал spurious false. Метод в production имеет только stale-drawer-guard назначение — UI обычный tap-path использует precomputed `ChatRowUiModel.isModelAvailable`. Code-reviewer-2 оптимально предложил либо дропнуть, либо wire в tap-handler; оставлен как test-exposed API (не блокер, 2 optional minors открытыми).
- **Остаточные optional minors** (не блокеры, не исправлены): `SimpleDateFormat` per-call allocation в `formatRelative`; duplicate `MAX_MANUAL_TITLE_LEN` между VM и Content; `checkModelAvailable` без production call site; `getMessageCount` ловит Room failure через `runCatching.getOrNull()` без `ErrorLog.e("history-read", ...)` — Phase-2.5 диагностика не увидит устойчивый Room-сбой в delete-dialog.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED_WITH_SUGGESTIONS, 0 critical / 3 major (swallowed history-read error в DeleteChatDialog, O(N) getByChatId в blank-rename, fragile StateFlow read в checkModelAvailable) + 9 minor → [logs/working/task-9/code-reviewer-1.json](logs/working/task-9/code-reviewer-1.json)
- test-reviewer: NEEDS_IMPROVEMENT, 0 critical / 2 major (blank-rename litmus не пинил AutoTitleGenerator; date boundary недостаточно покрыт — одна точка на секцию не ловит off-by-one на day 7) + 5 minor → [logs/working/task-9/test-reviewer-1.json](logs/working/task-9/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: APPROVED, 0 findings — все 3 major закрыты, 2 optional minor (checkModelAvailable без production caller, getMessageCount без ErrorLog) → [logs/working/task-9/code-reviewer-2.json](logs/working/task-9/code-reviewer-2.json)
- test-reviewer: PASSED, 0 critical / 0 major / 1 minor (60-char cap test не покрывает trim-before-take ordering) → [logs/working/task-9/test-reviewer-2.json](logs/working/task-9/test-reviewer-2.json)

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "*.DrawerViewModelTest"` → BUILD SUCCESSFUL (18 тестов, 0 failures — 10 TDD-anchor + 8 добавленных по ревью: blank-rename exact-title + fallback, 60-char cap, boundary {0,1,2,6,7,10}-дней, local-midnight 23:59-yesterday, DESC sort within section, empty-list pin, checkModelAvailable model-removed branch)
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (полный `:app` unit-test, без регрессий Phase 1/2/2.5/3)
- `./gradlew :app:kspDebugKotlin` → BUILD SUCCESSFUL (Hilt граф с `DrawerViewModel` резолвится)
- `./gradlew :app:lintDebug` → BUILD SUCCESSFUL (нет критичных ошибок)
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (APK собран)
- User device smoke — **deferred to phase-level QA** (memory: `Verify UI chain before device smoke`). Task 9 замыкает drawer + tap navigation, но chat/{chatId} (Task 10) и ChatScreen reshape для ChatIdentity.Persistent (Task 10) ещё не сделаны — полноценный device smoke из `tasks/9.md → Verification Steps → User` (открыть drawer, swipe-delete, long-press rename, tap unavailable model, tap available model → открыть чат) требует UI-цепочки, которой всё ещё нет. Прогон выполнится когда Wave 5 Phase 3 закроется.

## Task 17: Attachment staging (Draft→Persistent file atomicity)

**Status:** Done
**Commit:** 4fa6c89 (impl), 263a507 (review round 1), 17dce3a (review round 2 test fixes)
**Agent:** main agent
**Summary:** Закрыт Phase-3 debt 2 из Task 8 (Draft-режим терял вложения при коммите). `ChatRepository` получил `writeAttachmentStaging(stagingDir, filesDir, attachment)`, `savePersistentAttachment(chatId, filesDir, attachment)`, `deleteStagedAttachment(…, filename)` и `pruneStagingDir(…, retain)` — все с containment-check через `requireInsideAttachmentsRoot` (security T4-S1 паттерн). `commitDraftChat` внутри Room @Transaction после `chatDao.insert` переписывает `imagePath`/`audioPath` первого сообщения с `attachments/{chatId}/{filename}`, затем переименовывает staging-директорию — row и диск согласованы on-commit (Decision 6). `Attachment.Image`/`Audio` получили `stagedFilename: String?`; `Audio.equals/hashCode` расширены включением `stagedFilename`, без этого `StateFlow.update` не эмитил бы обновление при замене attachment'а копией с новым stagedFilename. `ChatViewModel` в Draft: lazy `draftStagingDir = .staging-{uuid}/`, async-staging на `addImage*/addAudio`, delete staged файла через репозиторий на `removeAttachment`, send-gate блокирует коммит пока `stagedFilename == null` у любого attachment'а (snackbar `attachment_still_saving`). В Persistent: `savePersistentAttachment` перед `savePersistentMessage(USER)` — на IOException hard-gate abort (аналог AC-R1), snackbar `attachment_save_failed`. Multi-image MVP: только первое фото сохраняется (`attachment_only_first_persisted` warning), перед commit'ом `pruneStagingDir` удаляет нессылающиеся файлы — закрывает одновременно orphan-проблему и remove-during-inflight race (security T17-S4).
**Deviations:**
- **Имена файлов: UUID вместо sequential index.** Task 17 Details hint'овал `System.nanoTime()` или `listFiles().size`. Первая реализация использовала size-based индекс — code-reviewer-1 поднял T17-R1 (critical: concurrent `addImages` launches N coroutines, все читают `listFiles().size` одновременно, все выбирают `img_0.png` → collision). Переключились на `UUID.randomUUID()` — SecureRandom, CWE-330 не применим, order/diagnostic reads по prefix (`img_`/`audio_`) остаются читаемы.
- **`writeAttachmentStaging` принимает `filesDir` explicit, не derive из `stagingDir.parentFile?.parentFile`.** Security round 1 T17-S1: derivation даёт tautological containment-check (root строится из ancestry кандидата — path outside реального app storage никогда не reject). Параметр сделан explicit, parity с `savePersistentAttachment` и `commitDraftChat`.
- **Новый `pruneStagingDir` в ChatRepository (не в VM).** Изначальный план был inline `withContext(Dispatchers.IO) { stagingDir.listFiles()?.forEach { ... } }` в VM. Под `UnconfinedTestDispatcher` это вышло бы за пределы test dispatcher'а (real IO dispatcher не advance'ится через `advanceUntilIdle`), и тесты времени коммита становились flaky. Вынесли в репозиторий (использует инжектированный `ioDispatcher`) — чистый суспензионный контракт, тесты детерминированы.
- **Multi-image: сохраняем только первое фото + snackbar warning.** Task 17 Details hint'овал "MVP = one attachment per message (текущий Attachment-UI в Phase 2 уже так работает фактически — single image + single audio per send)" — фактически некорректно: `MAX_IMAGES=10` в Phase 2 allows до 10 картинок. Honest MVP fix: оставили UI capacity, warn'им юзера перед commit'ом через `attachment_only_first_persisted`, extras pass'им в inference (для модели важны все), но на диск пишем только первую + prune'им остальные staging-файлы перед rename чтобы не плодить orphans в `attachments/{chatId}/`.
- **`payloadWriter` seam на `@VisibleForTesting` ctor.** Добавлен во втором round'е review для test-reviewer-2 T17-T2 — Robolectric Bitmap.compress слишком permissive чтобы упасть на recycled bitmap, ENOTDIR на non-dir staging blokирует FileOutputStream до создания файла, → реальный test rollback path требовал seam. Production wiring через `defaultPayloadWriter` top-level — Hilt `@Inject` ctor не меняется.
- **Остаточный debt (не в scope этой задачи):** T4-S6 (orphan attachment dir при kill между успешным rename и WAL commit) — неизменно открыт, ожидает отдельного подхода; не регрессирует в Task 17. Non-regular-file entries в staging (symlinks, sub-dirs) skip'аются `pruneStagingDir`'ом — cheap insurance было бы `isFile()` guard, но сейчас staging tree плоский. Привязка T17R2-S1 минор security-auditor-2, принято как документированный контракт.

**Reviews:**

*Round 1:*
- code-reviewer: CHANGES_REQUESTED (1 critical T17-R1 filename race, 1 major T17-R2 multi-image drop, 1 major T17-R3 derivation, 5 minor/1 nit) → [logs/working/task-17/code-reviewer-1.json](logs/working/task-17/code-reviewer-1.json)
- security-auditor: approved (5 minor: T17-S1/S2/S3/S4/S5 + 1 info) → [logs/working/task-17/security-auditor-1.json](logs/working/task-17/security-auditor-1.json)
- test-reviewer: NEEDS_IMPROVEMENT (3 major: T17-T1 remove under-asserted, T17-T2 partial-write rollback missing, T17-T3 containment missing + parity gap, 5 minor) → [logs/working/task-17/test-reviewer-1.json](logs/working/task-17/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: APPROVED_WITH_SUGGESTIONS (3 optional minors, все round-1 закрыты) → [logs/working/task-17/code-reviewer-2.json](logs/working/task-17/code-reviewer-2.json)
- security-auditor: approved (1 minor T17R2-S1 non-regular-file skip в prune + 1 info) → [logs/working/task-17/security-auditor-2.json](logs/working/task-17/security-auditor-2.json)
- test-reviewer: needs_improvement (3 major litmus false-positives — `draftStagingDir` reset vacuous, T17-T2 rollback не exercising, T17-T3 bad-filesDir triggers mkdirs not containment) → [logs/working/task-17/test-reviewer-2.json](logs/working/task-17/test-reviewer-2.json)

*Round 3 (test fixes):*
- test-reviewer: PASSED (все 3 litmus закрыты через `payloadWriter` seam для T17-T2, honest rename + KDoc для T17-T3, `vm.stop()`-based второй commit для draftStagingDir reset) → [logs/working/task-17/test-reviewer-3.json](logs/working/task-17/test-reviewer-3.json)

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "app.sanctum.machina.data.ChatRepositoryTest" --tests "app.sanctum.machina.ui.chat.ChatViewModelTest"` → BUILD SUCCESSFUL (оба файла зелёные, включая 10 новых ChatRepositoryTest и 13 новых ChatViewModelTest по Task 17)
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (204 тестов, 0 failures — без регрессий в Phase 1/2/2.5/3 unit tests)
- `./gradlew :core-runtime:testDebugUnitTest :core-settings:testDebugUnitTest` → BUILD SUCCESSFUL (UP-TO-DATE, Task 17 не касается этих модулей)
- User device smoke — **deferred to phase-level QA**. Task 17 меняет только ChatRepository + ChatViewModel + unit-тесты; smoke-шаги из `tasks/17.md` → Verification Steps → User (Draft+картинка → Send → история; kill-and-reopen; cancel удаляет staging; orphan cleanup; Persistent+картинка) требуют UI-цепочки, которой сейчас нет: drawer stub без содержимого (ждёт Task 10), ChatScreen/TopAppBar ещё в Phase-2 виде (ждёт Task 9). Прогон выполнится когда UI-waves Phase 3 будут сделаны — как часть общей приёмки фазы.

## Task 10: ChatScreen + HomeScreen updates

**Status:** Done
**Commit:** 55dcd7a (impl), 4105365 (review round 1 fixes)
**Agent:** main agent
**Summary:** `ChatViewModel` получил `TopAppBarState` sealed (Draft/Loading/Failed/Ready) и реактивный `topAppBarState: StateFlow` через `combine(registry.models, _chatModelId, warmupCoordinator.isWarmupInProgress, registry.activeModelName)`; инъекция `WarmupCoordinator`, метод `loadModel(modelId)` делегирует в `cancelAndRestart`. `ChatScreen` переписан: новый stateless `ChatTopAppBarTitle` с dropdown скачанных моделей (Draft + cross-model `AlertDialog`), `OutlinedButton "Загрузить"` (Failed), spinner+label (Loading), ReadyTitle c SmDot+name; Quick identity оборачивает экран в `SanctumIncognitoTheme` с `SanctumIcons.IconEyeOff` + subtitle «Быстрый чат». `Modifier.imePadding()` на контейнере с `MultimodalInputBar` (AC-U4). Settings IconButton gate (Phase-3 debt 1) — `enabled = !isGenerating && !reinitInProgress`. `SanctumApp.kt` подключил `ChatScreen` ко всем трём chat-роутам (placeholder'ы удалены), Draft→Persistent коллектит `ChatNavigationEvent`. `HomeScreen` получил corruption banner (`Card` в `errorContainer` тоне + SAF-launcher для `LogExportManager` + in-memory remember-флаг); `HomeViewModel` расширен `corruptionOccurred` + `buildAndWrite(uri)`. `WarmupCoordinator` получил `open val isWarmupInProgress` и `open fun cancelAndRestart` для тестового seam; `LogExportManager` — `open class` + `open suspend fun buildExport/writeTo` для fake'а в HomeViewModelTest.
**Deviations:**
- **`SanctumIncognitoTheme` оборачивает весь `ChatScreen` в Quick (не только Ready-branch).** Task edge-case говорит «не применять incognito-тему к loading/failed состояниям независимо от identity», но What-to-do предписывает «обернуть содержимое ChatScreen в SanctumIncognitoTheme». Выбрал второе — иначе тема мигала бы между warmup и первым Ready (bad UX), а edge-case явно адресует кросс-identity сценарии (Persistent во время warmup не должен быть incognito), а не внутри-Quick Loading. Code-reviewer-1 пометил как spec ambiguity; зафиксировано здесь.
- **Навигация wired up в рамках этой задачи.** Task говорит «не трогает навигацию» (это про NavHost-структуру, которую Task 7 уже создал), но `SanctumApp.kt` держал placeholder'ы с `TODO(Task 10)` — подключил `ChatScreen` ко всем трём маршрутам, это необходимо для работы самой фичи и для user-verification шагов.
- **Тесты для TopAppBarState в существующем `ChatViewModelTest.kt`**, не в отдельном `ChatTopAppBarStateTest.kt` (как предписывает TDD Anchor). Причина: все hand-rolled fakes (`FakeModelRegistry`, `FakeChatRepository`, `FakeMessageDao`, `FakeChatDao`, `FakeAppSettingsRepository`, `FakeLlmHelper`, `FakeImageDecoder`) живут в ChatViewModelTest как `private class`; отдельный test-файл потребовал бы либо дублирования этих fakes, либо вынесения их в shared utility (выход за рамки задачи). Покрытие TDD-anchor'а идентичное — 7 тестов `topAppBarState_*` + `loadModel_delegatesToWarmupCoordinator`.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions, 0 critical / 0 major / 5 optional minor → [logs/working/task-10/code-reviewer-1.json](logs/working/task-10/code-reviewer-1.json)
- test-reviewer: needs_improvement, 2 major (corruptionOccurred / buildAndWrite без тестов) + 4 minor → [logs/working/task-10/test-reviewer-1.json](logs/working/task-10/test-reviewer-1.json)

*Round 2 (after fixes):*
- code-reviewer: approved, все round-1 findings закрыты, 1 optional stylistic nit (inline `kotlinx.coroutines.SupervisorJob()`) → [logs/working/task-10/code-reviewer-2.json](logs/working/task-10/code-reviewer-2.json)
- test-reviewer: passed, оба major закрыты honestly (4 новых HomeViewModelTest + 2 дополнительных ChatViewModelTest + FakeWarmupCoordinator dedicated-scope) → [logs/working/task-10/test-reviewer-2.json](logs/working/task-10/test-reviewer-2.json)

**Verification:**
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (0 failures — 8 новых ChatViewModelTest + 4 новых HomeViewModelTest + без регрессий Phase 1/2/2.5/3)
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (APK собран)
- `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL (Hilt-граф резолвит новый `WarmupCoordinator` параметр в `ChatViewModel` и `AppCorruptionState` + `LogExportManager` в `HomeViewModel`)
- User device smoke — **deferred to phase-level QA** (memory: `Verify UI chain before device smoke`). Wave 5 Phase 3 ещё не закрыта; `tasks/10.md` → Verification Steps → User (imePadding, Draft model picker, «Загрузить», incognito indicator) требуют прогона на Honor 200 в составе общей приёмки фазы вместе с Task 11.

## Task 11: Model Manager updates

**Status:** Done
**Commit:** 27e8588 (impl), ac1cf1e (review round 1 fixes)
**Agent:** main agent
**Summary:** `ModelManagerViewModel` теперь инжектит `AppSettingsRepository`, экспозит `defaultModelId: StateFlow<String>` через `observeDefaultModelId().stateIn(viewModelScope, WhileSubscribed(5_000), "")` и `setDefaultModel(modelId, modelName)` (DataStore write → `NavEvent.ShowSnackbar`). `NavEvent.OpenChat` упразднён: `onLoad(modelId)` эмитит `NavEvent.OpenQuickChat(modelId)`; `SanctumApp.kt` навигирует на `chat/quick?modelId=${Uri.encode(modelId)}` (percent-encoding нужен — HF paths содержат `/`). `ModelManagerScreen` рендерит `SanctumIcons.IconStarFill` (accent tint) лидирующим бейджем на строке default-модели и `DropdownMenu` overflow с единственным пунктом «Сделать по умолчанию», видимым только для `SUCCEEDED` и не-default строк; `overflowExpanded` — per-card `remember`. Три строки ресурсов добавлены (badge desc / overflow desc / menu item); snackbar-текст захардкожен per tasks/11.md. Закрывает AC-F4 (quick chat routing) и AC-F7 (default selection UX), US-8 (п. 7) и US-12.
**Deviations:** None.

**Reviews:**

*Round 1:*
- code-reviewer: APPROVED, 0 critical / 0 major / 4 optional minor (modifier order, LazyColumn key comment, UnconfinedTestDispatcher pattern note, future-i18n note) → [logs/working/task-11/code-reviewer-1.json](logs/working/task-11/code-reviewer-1.json)
- test-reviewer: PASSED, 0 critical / 0 major / 4 minor (redundant assertFalse, ordering coverage gap, UNDISPATCHED comment rationale, missing empty-string test) → [logs/working/task-11/test-reviewer-1.json](logs/working/task-11/test-reviewer-1.json)

*Fixes applied after round 1:*
- Modifier reorder: `.padding(end = 8.dp).size(20.dp)` — icon draws at true 20dp.
- Comment added on `LazyColumn` key vs `modelId` identity split.
- Redundant `assertFalse { simpleName == "OpenChat" }` удалён (exact-list `assertEquals` уже это enforcer).
- Extracted `collectNavEvents(vm)` helper (DRY across three tests); reworded UNDISPATCHED rationale as defensive-discipline.
- New test `setDefaultModel_persistsBeforeEmittingSnackbar` — sequencing regression guard via `FakeAppSettingsRepository.onSetDefaultModelId` probe.
- New test `defaultModelId_emitsEmptyStringWhenUnset` — locks proto3-default first-launch contract.

**Verification:**
- `./gradlew :app:testDebugUnitTest --tests "app.sanctum.machina.ui.modelmanager.ModelManagerViewModelTest"` → BUILD SUCCESSFUL (5 тестов, 0 failures — 3 TDD-anchor + 2 добавленных по ревью)
- `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL (полный `:app` unit-test, без регрессий Phase 1/2/2.5/3)
- `./gradlew build` → BUILD SUCCESSFUL (lint + assembleDebug + assembleRelease + testRelease)
- User device smoke — **deferred to phase-level QA** (memory: `Verify UI chain before device smoke`). Task 11 замыкает last piece of Wave 5 UI — полноценный прогон (star indicator, overflow + snackbar + ⭐ migration, quick-chat launch из Model Manager) выполняется в составе общей приёмки Phase 3.

### Task 11 follow-ups (device smoke fallout)

User device smoke на Honor 200 после Task 11 вскрыл четыре дефекта в smoke-цепочке Wave 4-5 — ни один не regression собственно Task 11, но каждый требовал фикса прежде чем «Загрузить» / «Начать быстрый чат» заработало реально. Фиксы — отдельные коммиты, ссылки ниже. Тесты остались зелёными, APK пересобран между итерациями.

**1. Explicit-modelId warmup** (коммит `a4e035f`, `fix: trigger warmup for explicit quick-chat modelId`)
  `ChatViewModel.bootstrapChatModelId()` пиннил nav-arg `modelId` в `_chatModelId` но не звал `warmupCoordinator.cancelAndRestart(id)` — если WarmupCoordinator не грел именно эту модель (cold-start без default, cross-model switch), entry оставался в `ModelInitStatus.Idle` → `ChatUiState.Loading` навсегда. Фикс: тригерим warmup когда `explicitModelId != null && initStatus != Ready`.

**2. Auto-warmup после первого скачивания** (коммит `b5beac5`, `fix: auto-trigger warmup when default_model_id is auto-set on first download`)
  `WarmupCoordinator.startDefaultModelObserver` авто-записывал `default_model_id` при первом `SUCCEEDED`, но warmup не триггерил — Home «Начать быстрый чат» при первом запуске без default suspending навсегда в `registry.activeModelName.first()`. Фикс: после `appSettings.setDefaultModelId` сразу зовём `launchWarmup(modelId)`. Регрессионный тест `ac_f3Observer_triggersWarmup_afterAutoSettingDefault` пинит поведение.

**3. modelId → Model.name translation** (коммит `85cb71f`, `fix: translate modelId → Model.name before registry.initialize`)
  **Главный дефект** (нашли через `errors.log`): `WarmupCoordinator.launchWarmup(modelId)` передавал HF `modelId` в `registry.initialize()`, а `DefaultModelRegistry.initialize(modelName)` ищет по `Model.name` (storage filename). Результат: `IllegalArgumentException: unknown model: <modelId>` → warmup тихо падал в `engine-warmup` лог, UI крутился бесконечно. Фикс: внутри `launchWarmup` резолвим `registry.models.firstOrNull { modelId == X }?.model?.name` и зовём `initialize(name)`. Тесты получили helper `seedAllowlist(vararg modelIds)` — `ChatViewModelTest`, `WarmupCoordinatorTest` все прошли.

**4. Single-engine invariant violation** (коммит `58bb44e`, `fix: release prior Ready engine on cross-model initialize`)
  User flow A → write → B → write → back to A → чат открывался мгновенно без переинициализации. `DefaultModelRegistry.initialize(B)` делал `releaseEngine(B)` (идемпотент на том же target), но НЕ чистил ранее Ready'нутый A — оба native instance висели в памяти одновременно, нарушая single-engine invariant (D-T9, R3). Фикс: перед флипом target'а в `Initializing` итерируем snapshot `models.filter { Ready && name != target }` и делаем `releaseEngine + флип в Idle` (под `lifecycleMutex`, новых Ready не появится).

**5. ChatScreen IME + navigation-bar insets** (коммит `8ebd2ce`, `chore: ChatScreen imePadding + NOTES deferral for nav-bar gap`)
  Task 10 добавил `.imePadding()` чтобы поле ввода поднималось над клавиатурой (AC-U4). Побочный эффект: `Scaffold.innerPadding` уже включает nav-bar, `imePadding` добавляет ПОЛНУЮ высоту IME (которая уже накрывает nav-bar) → двойной учёт, пустой зазор высотой с нав-бар между клавиатурой и панелью ввода. Фикс: `.consumeWindowInsets(innerPadding).imePadding()` — стандартный Compose-паттерн `max(IME, navBar)` вместо суммы. На Honor 200 визуально зазор всё ещё виден — вынесено в NOTES как deferred follow-up (подозрение на non-standard inset reporting в HarmonyOS).

**Verification после фиксов:**
- `./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest` → BUILD SUCCESSFUL (241 тест, 0 failures кроме одного Robolectric flaky `testHousekeepingSkippedInCrashProcess` — проходит в изоляции, pre-existing state-leak между тестами, не касается фиксов).
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- User device smoke на Honor 200 (`2026-04-22`): (a) «Загрузить» A → работает, (b) «Загрузить» B → работает, (c) возврат на A после фикса 4 → корректно переинициализируется, (d) «Начать быстрый чат» после перезапуска → мгновенный (default модель прогрета `warmupDefault` на старте app'а), (e) star-индикатор + overflow «Сделать по умолчанию» — визуально корректны.

## Task 12: Project knowledge docs update

**Status:** Done
**Commit:** d224198 (impl), 65762c7 (review round 1 fixes)
**Agent:** main agent
**Summary:** Pure docs edit. `architecture.md` обновлён под Phase-3 реальность: `chats.project_id` описан как nullable INTEGER без FK (AC-R4/R7), `project_files` убрана из v1 и заменена forward-reference на `Migration(1,2)` в Phase 4, Key Constraints секция переведена на v1-only (FK только `messages.chat_id`; индексы `messages.chat_id`, `messages.created_at`, `chats.last_message_at`), NavHost routes перечислены типизированно (`home` / `chat/quick?modelId={id}` / `chat/draft` / `chat/{chatId}` / `model_manager` / `about`) с примечанием про оставшийся `chat/{modelName}` tombstone, Project Structure получил `drawer/` и `home/` подпапки, SanctumApplication comment расширен через `WarmupCoordinator.warmupDefault()` + `SettingsMigrationHelper.migrateIfNeeded()` + `StartupHousekeeper` (Room corruption handler корректно атрибутирован `di/AppModule.provideSanctumDatabase`, не SanctumApplication). Model lifecycle секция переписана через `DefaultModelRegistry` + `activeModelName` + single-engine invariant + Task 18 CancellationException recovery. `patterns.md`: ErrorLog whitelist расширен 8 → 14 компонентов с Phase-origin breakdown; Model lifecycle переписан через ownership-at-app-layer, `onCleared()` does NOT cleanup (Decision 5 / AC-E6), cleanup только на `cancelAndRestart` или process death, `resetConversation` cheap path, heavy-setting reinit bypasses `WarmupCoordinator`; Business Rule «Model switching mid-chat» переписан через Draft picker + AlertDialog → `cancelAndRestart`, Persistent «Загрузить» button, auto-resume для unpaired USER (Task 18 B4).
**Deviations:** None — все AC выполнены строго. Code-reviewer round 1 поймал два claim-vs-code mismatch'а (corruption handler file, `chats.title` nullability) — ровно тот класс stale-doc ошибок, который Task 12 должен был устранить; оба пофикшены в round 2.

**Reviews:**

*Round 1:*
- code-reviewer: `changes_required`, 2 critical / 0 major / 3 minor → [logs/working/task-12/code-reviewer-1.json](logs/working/task-12/code-reviewer-1.json)
  - C1: SanctumApplication.kt comment ошибочно приписывал Room corruption handler этому файлу — реально в `di/AppModule.provideSanctumDatabase`.
  - C2: `chats.title (TEXT, not null)` противоречит `ChatEntity.kt:22` (`val title: String? = null`).
  - Minor: `chat/{modelName}` tombstone всё ещё зарегистрирован (только deprecated); phase-origin `settings-io` — Phase 2 не 2.5 per D27.

*Fixes applied after round 1 (commit `65762c7`):*
- C1: comment переписан — corruption handler атрибутирован `di/AppModule.provideSanctumDatabase`, описан пошагово (rename → sidecar delete → `corruptionOccurred=true` → `errorLog.e("history-read", …)` → fresh rebuild).
- C2: `chats.title` возвращён к `nullable`, добавлено описание `AutoTitleGenerator` + «Без названия» fallback в drawer.
- Minor: NavHost comment признал tombstone; phase-origin `settings-io` / `camera` / `audio` / `attachment-decode` объединены под Phase 2 с ссылкой на D27.

**Verification:**
- Grep checks из Task 12 Verification: `project_files` count = 1 ✅, nullable `project_id` присутствует ✅, `chat/{modelName}` только в tombstone-комментарии ✅, все 6 Phase-3 ErrorLog компонентов присутствуют в `patterns.md` ✅, `onCleared` описан как «does NOT call cleanup» ✅.
- Code-reviewer round 1 verified accurate: ErrorLog ALLOWED_COMPONENTS = 14; `activeModelName` projectов `Model.modelId`; single-engine release + CE reset в `DefaultModelRegistry.initialize`; `loadModel` pins `_chatModelId` перед `cancelAndRestart` (Task 18 fix); `applyHeavySetting` обходит WarmupCoordinator; `chats.project_id` nullable без FK; schema version = 1.
- No build run — task docs-only.
- No user verify — docs sync, не UI change.

## Task 18: Phase-3 device-smoke bug batch

**Status:** Done
**Commit:** b3074eb (impl), 69e88e0 (review round 1 fixes), 3fc1104 (post-smoke round)
**Agent:** main agent
**Summary:** Пять дефектов, всплывших на Honor 200 после Task 11 follow-ups и Task 17 — все в швах между задачами. **B1** `ChatViewModel.toDomainMessageWithAttachments` теперь декодит `imagePath`/`audioPath` с контейнмент-чеком (`resolveInsideAttachmentsRoot` через `canonicalFile.startsWith`) и кэширует по `MessageEntity.id` в `attachmentCache`; добавлена симметричная `wavToPcm` в `core-runtime/.../MediaUtils.kt` (RIFF/WAVE header validation, data-size bound, div-zero guard). **B2** `DefaultModelRegistry.initialize` сбрасывает `Initializing → Idle` на `CancellationException` и расширяет single-engine release на `Ready || Initializing` (не только Ready — закрывает scenario 3 из post-mortem); `TopAppBarState.Loading` теперь несёт `modelName`, `LoadingTitle` рендерит «Загружаю %1$s…». **B3** `HomeViewModel.defaultModelName: StateFlow<String?>` через `combine(observeDefaultModelId, registry.models)`; `HomeScreen` рендерит clickable label «Модель по умолчанию: {name}» под product title. **B4** `MessageDao.lastByChat` + `ChatViewModel.bootstrapChatModelId` детектит unpaired USER row, `observeFirstReadyThenResume` дожидается первого Ready и зовёт `resumePendingAssistantPersistent`; `autoResumeAttempted` флипается ВНУТРИ диспатча (после `currentReadyModel()` проверки) — Ready→Idle flap не сжигает попытку. **B5** `DrawerContent` футер `NavigationDrawerItem` «Модели» + «О приложении» (закрывает decomposition gap Task 9 vs user-spec §37); `HomeScreen` settings gear удалён, `home_settings_open` orphan string выкошен.
**Deviations:** B4 передаёт `pending = emptyList()` в авторезюме — attachments USER-строки декодятся для UI (B1) но не скармливаются движку. Явно разрешено в tasks/18.md Implementation hints и отмечено в коде; полное решение потребует симметричной декодировки Bitmap/PCM для runInference, вынесено в follow-up. B3 пошёл вариантом (a) discoverability (Home label) без device-verify гипотезы (b) — вариант 2a в tasks/18.md прямо предлагается автору без verify, это UX-улучшение независимо от того что рендерит overflow.

**Reviews:**

*Round 1:*
- code-reviewer: approved_with_suggestions, 0 critical / 3 major (B4 attachments dropped, Failed-state invariant undocumented, autoResume flag ordering) / 9 minor → [logs/working/task-18/code-reviewer-1.json](logs/working/task-18/code-reviewer-1.json)
- test-reviewer: needs_improvement, 0 critical / 3 major (traversal test trivial-pass, missing-file не проверял лог, cross-model не асертил Ready target) / 6 minor → [logs/working/task-18/test-reviewer-1.json](logs/working/task-18/test-reviewer-1.json)
- security-auditor: approved, 0 critical / 0 major / 4 minor (WAV file-size cap, explicit isAbsolute reject — defensive hardening) → [logs/working/task-18/security-auditor-1.json](logs/working/task-18/security-auditor-1.json)

*Fixes applied after round 1:*
- code-reviewer R2: KDoc на `observeFirstReadyThenResume` фиксирует Failed-state invariant (арм до Ready through Failed).
- code-reviewer R3: `autoResumeAttempted=true` и `autoResumeTarget=null` перенесены в `resumePendingAssistantPersistent` ПОСЛЕ `currentReadyModel()` проверки — Ready→Idle flap не потребляет попытку.
- test-reviewer T18-TEST-1: traversal test теперь подкладывает decoy PNG в `filesDir/secret.png` — ассершн «attachments.size == 0» теперь гейтится контейнмент-чеком, а не отсутствием файла.
- test-reviewer T18-TEST-2: переименован в `_andLogged`, читает real `filesDir/logs/errors.log` через bounded wait-poll, асертит вхождение `attachment-read` компонента.
- test-reviewer T18-TEST-3: `initializeHandler` в cross-model тесте теперь публикует Ready для target и Idle для prior; тест асертит оба terminal state'а на `registry.models` snapshot.
- code-reviewer/test-reviewer остальные minor — triage: attachmentCache eviction отложен (MVP budget OK при двух attachments на ряд), прочие стилистические / doc-nit без активного риска.
- security-auditor minor — (file-size cap, isAbsolute reject) помечены как defensive hardening без активной угрозы; откладываем до явного incident-driven review.

**Verification:**
- `./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest :core-settings:testDebugUnitTest` → BUILD SUCCESSFUL (0 failures; 13 новых unit-тестов после post-smoke раунда: 4 B1 + 3 B4 в `ChatViewModelTest`, 2 B2 в `WarmupCoordinatorTest`, 3 B3 в `HomeViewModelTest`, +1 `persistentMode_autoResume_decodesAttachmentsAndPassesToInference` для round-2 фикса #6).
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.
- User device smoke на Honor 200 (`2026-04-22`): (a) B1 — фото в истории после cold-start ✅, (b) B3 — Home label «Модель по умолчанию: {name}» ✅, (c) B4 text — drawer «Новый чат» → текстовое сообщение обрабатывается на первом send ✅, (d) B5 — drawer footer «Модели» / «О приложении» + settings gear удалена ✅.
- **Post-smoke round (commit `3fc1104`):** юзер нашёл три bugs, все пофикшены:
  - **#4+#5 cross-model switch freeze.** `loadModel(modelId)` не обновлял `_chatModelId` → `observeEngineState` продолжал смотреть entry старой модели (Idle после single-engine release) → `uiState` застревал на `Loading` навечно → старый Phase-1 full-screen `LoadingContent` overlay маскировал всё. Fix: `loadModel` re-pins `_chatModelId.value = modelId` перед `cancelAndRestart`; `LoadingContent` composable удалён, `ChatUiState.Loading` рендерит `ReadyContent` с `isGenerating=false` (sendGated уже блочил отправку через `topAppBarState is Loading`); TopAppBar chip «Загружаю {name}…» — единственный индикатор переинициализации. Settings/Reset tophar-кнопки задизейблены пока `topAppBarState !is Ready`.
  - **#6 first-send attachments dropped.** `resumePendingAssistantPersistent` передавал `pending = emptyList()` — MVP trade-off из round 1 оказался неприемлем для юзера (вложения уже в истории через B1, но модель их не видела). Fix: функция теперь принимает целиком `MessageEntity`, декодит `imagePath`/`audioPath` через B1-хелперы и форвардит `pending` в `dispatchInferencePersistent`.
- User device smoke on `3fc1104` (`2026-04-22`): (e) cross-model switch A→B без залипшего overlay ✅, (f) первое сообщение с фото в drawer «Новый чат» → модель отвечает про картинку ✅, (g) Settings/Reset дизейблятся во время reinit, активны после Ready ✅.

## Task 13: Code Audit

**Status:** Done
**Commit:** 1b772ad
**Agent:** main agent
**Summary:** Holistic Phase-3 audit across all 7 focus areas from the tech-spec (single-engine invariant, `ChatIdentity` branches, staging cleanup, Main-thread hygiene, Hilt scopes, `modelId`/`name` discipline, `onCleared` hygiene). Six of seven areas PASS outright; focus area 4 (Main-thread hygiene) PASS WITH NOTES. Findings: 0 Critical / 1 Major / 5 Minor / 3 Suggestion. The Major (M1) is that `ChatViewModel.buildMessagesFlow` runs `BitmapFactory.decodeFile` and `wavToPcm` in the Room-flow `.map` operator without a `flowOn(Dispatchers.IO)`, so decode executes on the collector (Main) context — a frame-drop / AC-A1 hazard on first chat-open of long histories. Minors include the documented `runBlocking` in `AppModule.provideSanctumDatabase` corruption branch, read-modify-write in `updateChatLastMessage`/`updateChatTitle`, `observeAll().first()` inside `sweepZombieChats`, a stale KDoc referencing a missing `flowOn` stage, and `SimpleDateFormat` allocation hot-spots. No code changes were made — the report is the deliverable.
**Deviations:** None.

**Reviews:** No external reviewers — the auditor IS the review for this task (per tech-spec).

**Verification:**
- Report → [logs/working/task-13/code-audit-report.md](logs/working/task-13/code-audit-report.md)
- All 9 acceptance criteria from tasks/13.md verified in the report: 7 focus-area verdicts present; Major finding M1 carries file+line range+fix; `ChatViewModel.onCleared()` confirmed free of `registry.cleanup`; `cancelAndRestart` cancel-before-reinit confirmed; all three `ChatIdentity` branches confirmed covered; staging cleanup confirmed on success + failure + `StartupHousekeeper`; Main-thread I/O audited (Major M1 raised); Hilt scopes verified; no `Model.name` used for Room/DataStore persistence.


## Task 14: Security Audit

**Status:** Done
**Commit:** <pending>
**Agent:** main agent
**Summary:** Полный security audit Phase 3 по семи focus areas из tech-spec (file-path injection, SQL, quick/ purge, PII в логах, attachment decode, cross-process crash state, DataStore migration atomicity) + OWASP Mobile Top 10 sweep + SafeMarkdown/privacy-manifest regression check. Все семь focus areas — PASS. Findings: 0 Critical / 0 High / 0 Medium / 1 Low / 1 Info. **F1 (Low)**: `SettingsMigrationHelper` — транзиентный IOException в `isSettingsMigrated()` возвращает false и запускает re-migration на уже-мигрированных данных; все ключи станут orphan и сдропятся. Fix: перенести sentinel-check внутрь `dataStore.updateData { ... }` блока. **F2 (Info)**: `ChatViewModel.kt:1192,1275` форвардит `onError` payload движка в ErrorLog без caller-side cap — полагается на 500-char truncate в ErrorLog; harden `safeMsg.take(200)`. No Critical / High findings — Task 16 QA может стартовать.
**Deviations:** None.

**Reviews:** Нет сторонних ревьюверов — security-auditor IS the review для этой задачи (per task-14 spec).

**Verification:**
- Report → [logs/working/task-14/security-audit-report.md](logs/working/task-14/security-audit-report.md)
- Все 7 acceptance criteria из tasks/14.md проверены в отчёте: 7 focus-area verdicts с evidence, F1 и F2 с severity/file:line/description/fix, OWASP sweep завершён без unaddressed critical/high, SafeMarkdown verified на новом UI (HomeScreen, DrawerContent используют plain `Text`), `allowBackup=false` + `dataExtractionRules` unchanged, отчёт записан на указанный путь, запись в decisions.md (этот блок).
