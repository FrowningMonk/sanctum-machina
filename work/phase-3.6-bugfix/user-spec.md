---
created: 2026-05-05
status: approved
type: bug
size: S
---

# User Spec: phase-3.6-bugfix — three Honor 200 testing bugs

## Что делаем

Чиним три бага, обнаруженных при ручном тестировании на Honor 200 в фазе 3.5: (1) повторы / likelihood traps в чате, (2) недоступные настройки инференса в новом persistent-чате, (3) визуальный зазор между поднятой клавиатурой и input bar. Без новых фич, только фикс.

## Зачем

Все три бага блокируют повседневное использование персистентных чатов: бесконечные повторы делают модель бесполезной до перезапуска приложения; недоступные настройки заставляют пользователя слать «пустое» первое сообщение; зазор под IME ломает визуал input bar. Это последний барьер перед началом работы над Phase 4 (agent + function calling).

## Как должно работать

**Сценарий 1 (KV-cache не утекает между чатами).**
1. Пользователь работает в персистентном чате A, накопил 30 сообщений.
2. Открывает drawer, переключается на чат B (или создаёт новый).
3. Чат B стартует с чистым контекстом — модель не «помнит» переписку из A; повторов нет.

**Сценарий 2 (настройки инференса работают вживую).**
1. Пользователь ловит likelihood trap (повторяющийся ответ).
2. Открывает settings sheet, снижает temperature с 1.0 до 0.7.
3. Закрывает sheet, шлёт следующее сообщение в этом же чате.
4. Ответ генерируется уже с новой temperature — повтор разорван.

**Сценарий 3 (settings доступны в новом persistent-чате).**
1. Пользователь создаёт новый чат с моделью — модель прогревается.
2. Сразу после статуса Ready (до отправки первого сообщения) кнопка settings в top app bar активна.
3. Пользователь открывает sheet, меняет system prompt и temperature.
4. Шлёт первое сообщение — оно генерируется с новыми настройками.

**Сценарий 4 (нет зазора под IME на Honor 200).**
1. Пользователь открывает чат, тапает по input bar → клавиатура поднимается.
2. Между поднятой клавиатурой и нижним краем input bar нет белого/серого зазора.
3. Поведение одинаково на других экранах с текстовым вводом (если такие появятся).

## Критерии приёмки

**Bug 1 — KV-cache reset:**
- [ ] AC-1.1: При переключении на другой persistent-чат через drawer Conversation пересоздаётся (KV-cache очищен).
- [ ] AC-1.2: При commit draft → новый persistent-чат Conversation пересоздаётся.
- [ ] AC-1.3: При изменении temperature / topK / topP / max_tokens в settings sheet изменения применяются к следующему ответу того же чата (Conversation пересоздаётся с новым SamplerConfig).
- [ ] AC-1.4: Если `resetConversation` вызван при non-Ready engine — логируется warning, не молчаливый skip.
- [ ] AC-1.5: Каждый `resetConversation` логируется с тегом причины (`chat-switch` | `draft-commit` | `light-override` | `system-prompt` | `heavy`).

**Bug 2 — Settings в новом persistent/draft чате:**
- [ ] AC-2.1: В draft-чате после прогрева модели (ModelInitStatus.Ready) кнопка settings в top app bar активна.
- [ ] AC-2.2: Изменения, сделанные до первого сообщения, применяются к первому сообщению (зависит от AC-1.3).
- [ ] AC-2.3: При не-готовом engine (Idle / Initializing / Failed) кнопка settings заблокирована.

**Bug 3 — Edge-to-edge / IME:**
- [ ] AC-3.1: `enableEdgeToEdge()` вызван в `MainActivity.onCreate` и в `CrashReportActivity.onCreate`.
- [ ] AC-3.2: На Honor 200 при открытой клавиатуре в чате между input bar и IME нет зазора.
- [ ] AC-3.3: Визуальный smoke на 6 экранах (Home, Drawer, ModelManager, Diagnostics, About, CrashReport) — нет регрессий: контент не уезжает под status/navigation bar, текст читаем.

## Ограничения

- **Платформа:** Android `minSdk 31`, `targetSdk 35`. Dev-target — Honor 200.
- **Движок:** LiteRT-LM 0.10.0 — opaque .aar; работаем только с публичным API (`Engine`, `Conversation`, `SamplerConfig`, `ConversationConfig`, `EngineConfig`, `Backend`).
- **DI:** Hilt — все новые зависимости через `@Inject` / `@Provides`.
- **Совместимость:** не ломать существующее поведение Quick chat, Model Manager, Diagnostics, экспорта логов.
- **Honor-lock запрещён** (см. memory `manifest_breadth_over_honor_lock`): Honor 200 — QA bench, фикс Bug 3 должен работать на стандартном AOSP edge-to-edge.

## Риски

- **Риск 1:** Принудительное пересоздание Conversation на каждое изменение slider'а в settings sheet может вызвать видимую задержку / лишний прогрев. **Митигация:** пересоздание выполнять при закрытии sheet (commit), не на каждое движение slider'а; если задержка > 200 мс — добавить debounce / loading-индикатор.
- **Риск 2:** `enableEdgeToEdge()` может сломать визуал на других экранах (status bar / navigation bar overlap). **Митигация:** AC-3.3 — обязательный визуальный smoke на 6 экранах перед merge.
- **Риск 3:** В Bug 1 может оказаться, что Conversation не получится «дёшево» пересоздать без полного re-init engine — тогда сценарий «снизил temperature → следующий ответ с ней» потребует heavy-перезагрузки. **Митигация:** проверить экспериментом до коммита; если так — AC-1.3 переформулировать (применение «в текущей сессии» через extraContext, либо явный warm-up индикатор).

## Технические решения

- Мы решили **не вводить новый user-spec interview** — источник требований уже прошёл диагностику в предыдущей сессии и подтверждён пользователем; этот документ — формализация уже принятых решений.
- Мы решили **не выкидывать silent-skip из `DefaultModelRegistry.resetConversation` молча**, а заменить на warning-лог + явный путь возврата — диагностируемость важнее тишины (см. AC-1.4).
- Мы решили **диагностические теги для reset вынести в enum** (`chat-switch`/`draft-commit`/`light-override`/`system-prompt`/`heavy`) — единая точка источника при логировании и тестировании.
- Мы решили **не трогать Bug 4** (audit inference settings) — отсутствующие в LiteRT-LM 0.10.0 параметры (`repetition_penalty`, `min_p` и т.п.) не существуют в API; вернёмся, если движок их добавит или мы заменим LiteRT-LM.

## Тестирование

**Unit-тесты:** делаются всегда, не обсуждаются. Покрытие: `DefaultModelRegistry.resetConversation` (warning path), `ChatViewModel.bootstrapChatModelId` / `commitDraft` / `applyLightOverrides` (через recording-fake `ModelRegistry`), `deriveTopAppBarState` (Draft × {Idle, Initializing, Ready, Failed}).

**Интеграционные тесты:** не делаем — фикс изолированный, нет новых интеграционных границ.

**E2E тесты:** не делаем — Honor 200 smoke покрывается ручной верификацией пользователем (Bug 3).

## Как проверить

### Агент проверяет

| Шаг | Инструмент | Ожидаемый результат |
|-----|-----------|-------------------|
| Запустить unit-тесты на затронутых модулях | `./gradlew :app:testDebugUnitTest :core-runtime:testDebugUnitTest` | Все тесты зелёные, в т.ч. новые на reset/bootstrap/commitDraft/lightOverride/deriveTopAppBarState |
| Проверить, что lint не упал | `./gradlew :app:lintDebug` | Нет новых warnings в затронутых файлах |
| Сборка debug APK | `./gradlew :app:assembleDebug` | APK собран без ошибок |

### Пользователь проверяет

- **Bug 1 / AC-1.1, AC-1.2:** В двух persistent-чатах сделать длинные диалоги (>20 сообщений) с разной темой. Переключаться между ними. В каждом чате модель должна отвечать только в контексте этого чата, без следов другого. Перед каждым переключением — спросить «о чём мы говорили выше?», ответ должен соответствовать ровно этому чату.
- **Bug 1 / AC-1.3:** Получить repetition loop (повторение фразы). Открыть settings sheet → снизить temperature до 0.5 → закрыть. Следующий ответ должен быть другим (не повтор).
- **Bug 1 / AC-1.5:** Проверить через `adb logcat` или Diagnostics-экран наличие тегированных reset-логов при реальных действиях.
- **Bug 2 / AC-2.1:** Создать новый persistent-чат с моделью → дождаться Ready → НЕ отправляя сообщение, тапнуть по иконке settings — sheet открывается.
- **Bug 2 / AC-2.2:** Изменить system prompt → отправить первое сообщение → проверить, что модель ведёт себя в соответствии с новым system prompt.
- **Bug 3 / AC-3.2:** Honor 200, чат, тап по input bar → клавиатура поднимается → визуально нет зазора между ней и input bar.
- **Bug 3 / AC-3.3:** Honor 200 — пройтись по Home / Drawer / Model Manager / Diagnostics / About / Crash Report — контент не уезжает под status/navigation bar, читается.
