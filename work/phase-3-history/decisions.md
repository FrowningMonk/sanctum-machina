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
- `./gradlew :app:connectedAndroidTest` → **отложено**: устройство Honor 200 не было подключено во время выполнения задачи. Проверка будет выполнена пользователем вручную и в Task 16 (Pre-deploy QA).
