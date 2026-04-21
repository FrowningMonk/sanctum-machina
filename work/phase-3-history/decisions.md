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
