# Pre-deploy QA — phase-3.6-bugfix

**Date:** 2026-05-06
**Branch:** phase/3.6-bugfix
**QA agent:** main agent (Task 10)
**Verdict:** **BLOCKED**

User smoke на Honor 200 нашёл регрессию по AC-3.2: после Phase 3.6 зазор между поднятой клавиатурой и input bar **увеличился** относительно состояния до фикса. AC-2.1 подтверждён pass. Остальные user-smoke пункты (AC-1.1 / AC-1.2 / AC-1.3a / AC-1.3b / AC-1.5 / AC-2.2 / AC-3.3) пользователем не отвечены — помечены deferred. Все agent-проверки green.

---

## 1. Gradle results

Хост: Windows 11 Pro 10.0.26200, Android Studio JBR (`C:\Program Files\Android\Android Studio\jbr`) — OpenJDK 21.0.10. Системный JAVA_HOME (Eclipse Adoptium 25.0.3) у Gradle 8.13 / бандла Kotlin не парсится (`IllegalArgumentException: 25.0.3` — та же среда, что в Tasks 8/9).

| Команда | Результат | Детали |
|---|---|---|
| `./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest` | **PASS** | `BUILD SUCCESSFUL in 55s`. `:app` — 304 теста, 0 fail. `:core-runtime` — 95 тестов, 0 fail. Подсчёт по JUnit XML в `app/build/test-results/testDebugUnitTest/*.xml` + `core-runtime/build/test-results/testDebugUnitTest/*.xml`. |
| `./gradlew :app:lintDebug` | **PASS** | `BUILD SUCCESSFUL in 36s`. `0 errors, 81 warnings`. Все warnings вне файлов задач 1–6, кроме одного — см. ниже. |
| `./gradlew :app:assembleDebug` | **PASS** | `BUILD SUCCESSFUL in 7s`. APK `app/build/outputs/apk/debug/app-debug.apk` — 118 MB. |

### Excerpts

```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 55s
104 actionable tasks: 19 executed, 85 up-to-date
```

```
> Task :app:lintDebug
BUILD SUCCESSFUL in 36s
125 actionable tasks: 7 executed, 118 up-to-date
```

```
> Task :app:assembleDebug
BUILD SUCCESSFUL in 7s
97 actionable tasks: 4 executed, 93 up-to-date
```

### Lint warnings в файлах задач 1–6

Grep `lint-results-debug.txt` по списку файлов задач 1–6 → 1 hit:

- `app/src/main/kotlin/app/sanctum/machina/ui/chat/ChatViewModel.kt:1492` — `Bitmap.scale` `[UseKtx]`. Pre-existing, документировано в `decisions.md` Task 3 (line 80) и Task 6 (line 153) как «carried over, line shifted by new code, unrelated to this phase». Не новый warning.

Прочие 80 warnings — `build.gradle.kts`, `gradle/libs.versions.toml`, `SanctumApplication.kt:50`, `AppModule.kt:55`, `gradle-wrapper.properties:3`, `DeviceInfoCollector.kt:213`, `MainActivity.kt` AndroidGradlePluginVersion-семейство — все вне scope phase-3.6.

---

## 2. Static checks

| Проверка | Результат | Источник |
|---|---|---|
| Module boundary `:core-runtime` UI-free | **PASS** | `Grep "androidx\.compose\|androidx\.activity"` по `core-runtime/src/main` → 0 hits. |
| `LIGHT_FIELD_LABELS` без `MAX_TOKENS` | **PASS** | `ChatViewModel.kt:1636-1640` — содержит только `TEMPERATURE.label`, `TOPK.label`, `TOPP.label`. Комментарий 1631-1635 ссылается на Decision 4. `MAX_TOKENS` появляется только в `classifyApplyLevel` Heavy-условии (1417-1419). |
| `ErrorLog.ALLOWED_COMPONENTS` содержит `"inference-reset"` | **PASS** | `ErrorLog.kt:33-53` — 15 элементов, `"inference-reset"` в группе Phase 3.6 (line 52). |
| `patterns.md` § D15 Light bullet перезаписан | **PASS** | `patterns.md:64`. Старая фраза "applies from next send() without engine touch" отсутствует. Light bullet описывает `registry.resetConversation(reason = LIGHT_OVERRIDE)` + Conversation-recreation, явный pointer "maxTokens is baked into EngineConfig at engine creation and therefore lives in the Heavy tier". Tier разделение Light/Semi-light/Heavy актуальное. |
| Все callers `registry.resetConversation` передают явный `reason` | **PASS** | 4 production call sites в `ChatViewModel.kt`: `:368` (USER), `:497` (LIGHT_OVERRIDE), `:514` (SYSTEM_PROMPT), `:944` (parameter forward — передаётся вызывающей стороной из bootstrap (812-816) как DRAFT_COMMIT/CHAT_SWITCH). Интерфейс `ModelRegistry.kt` без default value на `reason: ResetReason` — компилятор принуждает. |
| `enableEdgeToEdge()` в обеих Activity | **PASS (статически)** | `MainActivity.kt:24` — сразу после `super.onCreate(...)`. `CrashReportActivity.kt:85` — после `setFlags(FLAG_SECURE, ...)` (line 84) и до `setContent`. Декларации import есть в обоих файлах. |

---

## 3. Audit findings carry-over

| Аудит | Verdict | CRITICAL/HIGH | Notes |
|---|---|---|---|
| Task 7 (code-audit) | **APPROVE** | 0 / 0 | 4 NIT (style consistency `is` vs `===`, optional snackbar, Decision-4 comment duplication, formatStatus-then-sanitize order). Не блокеры. |
| Task 8 (security-audit) | **APPROVE** | 0 / 0 / 0 MEDIUM | 1 LOW (formatStatus L1 — sanitize перед интерполяцией, defense-in-depth) + 2 INFO. Decision 5 drift vector структурно закрыт; FLAG_SECURE сохраняется через enableEdgeToEdge; manifest без новых permissions. |
| Task 9 (test-audit) | **APPROVE** | 0 / 0 | 1 MINOR (sharedCalls не различает reasons — параллельный resetReasons покрывает) + 4 INFO. Все 12 user-spec AC + 8 tech-spec AC покрыты unit-тестами или статикой; pyramid sanity preserved. |

Открытых блокеров из audit-волны нет.

---

## 4. AC matrix

### user-spec

| AC | Описание | Источник | Статус | Комментарий |
|---|---|---|---|---|
| AC-1.1 | KV-cache reset on chat switch | unit + user smoke | **deferred** | Unit: `ChatViewModelTest.bootstrapPersistent_chatSwitchReset_waitsForReady` — pass per Task 3. Honor 200 пользователем не пройден в этом раунде QA. |
| AC-1.2 | KV-cache reset on draft commit | unit + user smoke | **deferred** | Unit: `bootstrapPersistent_emitsDraftCommitReset_whenLastIsUnpairedUser` + ordering `bootstrapPersistent_draftCommit_resetsBeforeAutoResumeRunInference` — pass. Honor 200 пользователем не пройден. |
| AC-1.3a | Light apply (temp/topK/topP) | unit + user smoke | **deferred** | Unit: `applyLightOverrides_callsResetConversation_withLightOverrideReason` + `classifyApplyLevel_returnsLight_forTemperature` — pass. Honor 200 пользователем не пройден. |
| AC-1.3b | max_tokens via Heavy | unit + user smoke | **deferred** | Unit: `classifyApplyLevel_returnsHeavy_forMaxTokens` + `applyMaxTokens_followsHeavyDialogSequence` — pass. Honor 200 пользователем не пройден. |
| AC-1.4 | Non-Ready reset → warning log | unit | **PASS** | `DefaultModelRegistryTest.resetConversation_skipsAndLogsWarning_whenEngineIdle/Initializing/Failed` — pass per Task 2. Полностью unit-проверяемый. |
| AC-1.5 | Reset tagged log | unit + user logcat | **deferred** | Unit: `resetConversation_dispatchesAndLogsInfo_whenReady` + `applySystemPromptAndReset_resetsWithPrompt` (SYSTEM_PROMPT) + `resetConversation_clearsAll` (USER) + `whitelistCount_is15` — pass. Logcat выдержка пользователем не прислана. |
| AC-2.1 | Settings active in draft after Ready | unit + user smoke | **PASS** | Unit: `engineReady_combinatorics` Ready+no-warmup cell — pass. **User feedback:** «настройки инференса в чате могу открыть еще до первого сообщения». |
| AC-2.2 | Pre-first-msg edits applied | user smoke | **deferred** | Tech-spec § User-Spec Deviations: covered-implicit (DataStore → bootstrap re-merge → first-inference read chain). User smoke шага 7 (изменить system prompt → отправить первое сообщение → проверить поведение) пользователем не пройден. |
| AC-2.3 | Settings blocked when not Ready | unit | **PASS** | `engineReady_combinatorics` ячейки Idle/Initializing/Failed/Ready+warmup/entry==null → false — pass per Task 6. |
| AC-3.1 | `enableEdgeToEdge()` в обеих Activity | static | **PASS** | `MainActivity.kt:24` и `CrashReportActivity.kt:85` (после FLAG_SECURE), оба до `setContent`. Подтверждено grep'ом в § 2 + Task 4 / Task 7. |
| AC-3.2 | No IME gap on Honor 200 | user smoke | **FAIL** | **User feedback:** «зазор на моем телефон Honor 200 между клавиатурой и полем ввода теперь стал еще больше чем раньше». Регрессия — фикс ухудшил состояние. См. § 5 Blockers. |
| AC-3.3 | 6-screen visual smoke | user smoke | **deferred** | Honor 200 шаг 9 пользователем не пройден. Возможна сопутствующая регрессия (тот же `enableEdgeToEdge()` в `MainActivity` влияет на все 6 экранов). |

### tech-spec § Acceptance Criteria

| AC | Описание | Статус |
|---|---|---|
| Tests green | `:app:testDebugUnitTest :core-runtime:testDebugUnitTest` | **PASS** (399 тестов, 0 fail) |
| Lint clean | no new warnings в файлах задач 1–6 | **PASS** (1 carried-over UseKtx, не новый) |
| APK builds | `:app:assembleDebug` | **PASS** (APK 118 MB) |
| Module boundary | `:core-runtime` UI-free | **PASS** (0 hits) |
| ALLOWED_COMPONENTS | code + patterns.md | **PASS** (`ErrorLog.kt:52` + `patterns.md` § ErrorLog component strings) |
| patterns.md D15 rewritten | no old phrasing | **PASS** (старой фразы нет; `LIGHT_OVERRIDE` упомянут) |
| Explicit reason | all callers | **PASS** (4 callers в `ChatViewModel.kt`, интерфейс без default) |
| LIGHT_FIELD_LABELS | no MAX_TOKENS | **PASS** (только TEMPERATURE/TOPK/TOPP) |

---

## 5. Verdict

**BLOCKED.**

### Blockers

**B1 — AC-3.2 регрессия на Honor 200.**
Пользователь сообщил: «зазор на моем телефон Honor 200 между клавиатурой и полем ввода теперь стал еще больше чем раньше». Phase 3.6 ставила Bug 3 (нет зазора под IME) — фикс ухудшил наблюдаемое поведение, а не починил.

Возможные причины (требуют диагностики в fix-task'е):
- Двойной inset: `enableEdgeToEdge()` в `MainActivity` теперь корректно репортит IME, но `Scaffold + consumeWindowInsets(innerPadding) + imePadding()` в `ChatScreen.kt` мог начать применять IME-inset дважды — раньше отсутствие edge-to-edge маскировало overcount.
- `Scaffold` контейнер использует `WindowInsets.systemBars` по умолчанию, что под edge-to-edge включает IME-inset через `Scaffold.contentWindowInsets` — затем `imePadding()` поверх добавляет его ещё раз.
- EMUI 14 на Honor 200 может репортить IME-inset с включённым navigation-bar inset (известное поведение OEM); на чистом AOSP такого не было бы.

Per task edge case rule «Discrepancy между unit-тестами и user smoke: unit зелёный, юзер ловит баг — приоритет user, AC fail, вердикт BLOCKED». Merge запрещён.

**Рекомендация — создать fix-task в phase-3.6:**
- Воспроизвести зазор с `adb shell dumpsys input_method` / Layout Inspector, замерить inset до/после `enableEdgeToEdge()`.
- Кандидаты на правку: убрать `imePadding()` из `ChatScreen.kt` Column (`Scaffold` уже потребляет IME через `contentWindowInsets`), или явно задать `Scaffold(contentWindowInsets = WindowInsets.systemBars.exclude(WindowInsets.ime))` и оставить `imePadding()` на Column.
- Перепроверить AC-3.3 (6 экранов) после фикса — изменение insets-стратегии повлияет на весь visual smoke.

### Deferred

Пользователь подтвердил один пункт (AC-2.1) и сразу указал на блокер по другому (AC-3.2); остальные пользовательские шаги (AC-1.1, AC-1.2, AC-1.3a, AC-1.3b, AC-1.5, AC-2.2, AC-3.3) не прошёл в этом раунде.

Решение: устройство-зависимые AC переносятся на следующий раунд QA после фикса B1, потому что:
1. Любая правка edge-to-edge стратегии затронет `ChatScreen` / `Scaffold` / inset-handling — может всплыть AC-3.3 регрессии, которые сейчас не зафиксированы.
2. Bug 1 unit-coverage сильное (за 11 новых/расширенных тестов в `ChatViewModelTest` + `DefaultModelRegistryTest`), поэтому отложить device smoke до бандла с фиксом B1 безопасно.
3. Bug 2 имеет один device-pass (AC-2.1) — сам фикс работает, расширенный smoke (AC-2.2 system prompt → first message) делается одним прогоном вместе с переоткрытым AC-3.2.

После закрытия B1 — повторный Verify-user (одним сообщением) на оставшиеся deferred-пункты + AC-3.2 + AC-3.3.

### Гарантии, которые phase 3.6 уже даёт (не требуют пересдачи при фиксе B1)

- AC-1.4 / AC-2.3 / AC-3.1 — закрыты статически или unit-тестами, на B1-фикс не зависят.
- Все agent-проверки § 1–§ 2 сохранятся при изменении только inset-обвязки `ChatScreen` / `Scaffold` (B1 trapping область — UI-конфигурация Activity / Compose layout, не runtime registry / ErrorLog / ResetReason / `LIGHT_FIELD_LABELS`).

---

## Files

- Report: `work/phase-3.6-bugfix/logs/qa/pre-deploy-qa.md` (this file).
- APK: `app/build/outputs/apk/debug/app-debug.apk` (118 MB).
- Test results: `app/build/test-results/testDebugUnitTest/`, `core-runtime/build/test-results/testDebugUnitTest/`.
- Lint report: `app/build/reports/lint-results-debug.{txt,html,xml}`.
