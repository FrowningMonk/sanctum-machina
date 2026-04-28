---
created: 2026-04-28
status: approved
branch: phase/3.5-diagnostics
size: M
---

# Tech Spec: Phase 3.5 — диагностическая стабилизация

## Solution

Phase 3.5 закрывает класс OOM-крашей нативного движка LiteRT-LM на сабтрешолд-устройствах через **pre-flight memory gate** в Model Manager и попутно собирает диагностический UI в отдельный экран «Диагностика», расширяя экспортируемый `.txt` тремя дополнительными источниками сигнала.

Технически фаза состоит из шести независимых, но связанных слайсов:

1. **Pre-flight memory gate.** Существующее поле `Model.minDeviceMemoryInGb: Int?` (объявлено с Phase 1, но никогда не маппленное парсером — `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Model.kt:59`) теперь мапится `AllowedModel.toModel()` и проверяется парсером (`null/missing` → отклонение записи + `ErrorLog.e("download", …)`). Bundled allowlist рекалиброван (E2B 8→4, E4B 12→6). Pure-функция `gateAllowsDownload(totalBytes, minGb)` сравнивает `MemoryInfo.totalMem` с порогом без преждевременного округления; `ModelManagerViewModel` экспонирует per-row `GateDecision`, `ModelStatusSection` отрисовывает disabled-кнопку + secondary-text. Срабатывание гейта **не** логируется в `ErrorLog` — это превентивный UX, не incident.

2. **versionName ↔ git tag.** `app/build.gradle.kts:18` (хардкод `"0.1.0"`) заменяется на чтение `git describe --tags --always --dirty=-dev` через Gradle `providers.exec` API (config-cache-совместимый, AGP 8.13.2 на проекте). Чтобы pure-функция чтения была JUnit-тестируемой (см. Testing Strategy AC-T7), exec и парсинг разделяются: exec живёт в convention-плагине под `build-logic/` (или `buildSrc/`), pure-функция парсинга `gitVersionParse(stdout, exitCode): String?` принимает уже captured stdout/exitCode и возвращает либо валидный version-string, либо `null` (тогда вызывающий код подставляет хардкод `"v0.3.5-diagnostics-fallback"`). Это разделяет тестируемую логику от Gradle exec API.

3. **LogcatReader `*:E` → `*:W`.** Одно изменение в `argv[5]` в `LogcatReader.kt:37` плюс синхронный апдейт `LogcatReaderTest.kt:102`. Стратегия tail-truncation в `LogExportManager` остаётся валидной — самые свежие записи (где живут предсмертные WARN от LiteRT-LM) сохраняются в первую очередь.

4. **DeviceInfoCollector header expansion.** Добавляются три источника сигнала: `threshold` + `lowMemory` (доступны в текущем `MemoryInfo`, нулевая стоимость), новая строка `process:` (`Debug.MemoryInfo` + `Runtime`, callable in any process), и новая строка `last init:` (читается из `DiagnosticsState`). В `:crash`-процессе `last init:` всегда `«пока не было»` — singleton живёт только в основном процессе.

5. **InitDiagnostics interface seam.** `:core-runtime` нуждается в записи RAM-снимка в момент init, но `DiagnosticsState` логически принадлежит `:app`. Прямая зависимость `:core-runtime` → `:app` запрещена. Решение: интерфейс `InitDiagnostics` в `:core-runtime/registry/`, реализация `DiagnosticsState` (`@Singleton`, `AtomicReference<InitSnapshot>`) в `:app/diagnostics/`, Hilt-binding через `@Binds` в новом `DiagnosticsModule` (`:app/diagnostics/di/`). Это тот же паттерн `interface-в-:core-runtime + @Binds-в-:app`, что используется в `LogExportModule.@Binds CommandRunner` и `LogExportModule.@Binds DeviceInfoProvider`. `DefaultModelRegistry.initialize` зовёт `onInitStart` после `releaseEngine(model)` и непосредственно перед `try`-блоком (между строками 234 и 236, до первого `awaitInitialize` на строке 243), `onInitEnd(true)` на обоих success-arm'ах (после строк 245/258), `onInitEnd(false)` на failure-arm'е после `errorLog.e` (строка 262) и до `Result.failure` (строка 263).

6. **DiagnosticsScreen + Drawer pin + AboutScreen refactor.** Новый top-level route `diagnostics`, `DiagnosticsViewModel` с 1-секундным `viewModelScope`-тиком на free-RAM, две секции — «RAM» и «Логи». Кнопка SAF-экспорта переезжает из AboutScreen целиком (composable, launcher state, ViewModel-вызов) — никакого дублирования. AboutFooter, 7-tap, AlertDialog остаются на месте на AboutScreen. В `DrawerFooter` добавляется третий `NavigationDrawerItem` между «Модели» и «О приложении».

Все слайсы проектно независимы кроме связи (4)↔(5)↔(6): `DeviceInfoCollector.last init:` читает `DiagnosticsState`, `DefaultModelRegistry` пишет туда, `DiagnosticsScreen` тоже читает оттуда. Wave-планирование ниже эту зависимость учитывает.

## Architecture

### What we're building/modifying

- **`AllowlistLoader.parse`** (`core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/AllowlistLoader.kt:41-66`, `internal fun` в `companion object`, не suspend) — добавляются `require(m.minDeviceMemoryInGb != null) { ... }` и `require(m.minDeviceMemoryInGb in 1..64) { ... }` рядом с существующими `require`-проверками. Бросается `IllegalArgumentException` с точным id отклонённой модели в сообщении.
- **`AllowlistLoader.load`** (`core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/AllowlistLoader.kt:27-34`, `suspend fun`) — оборачивает вызов `parse(...)`; на `IllegalArgumentException` от `parse` пишет `errorLog.e("download", "model rejected: <message>")` и пропускает запись. `ErrorLog.e` — suspend, вызывается отсюда (не из `parse`, т.к. companion-функция non-suspend и `ErrorLog`-зависимости не имеет). Точная стратегия (per-record collect vs aggregate failure): solver выбирает в task-decomposition; контракт — отклонённые модели не попадают в registry, событие в `errors.log` есть.
- **`AllowedModel.toModel()`** (`core-runtime/src/main/kotlin/app/sanctum/machina/core/data/ModelAllowlist.kt:49-97`) — добавляется проброс `minDeviceMemoryInGb` в `Model` ctor (поле уже объявлено в `Model.kt:59`).
- **`core-runtime/src/main/assets/model_allowlist.json`** — рекалибровка двух значений.
- **`DeviceInfoProvider`** + **`AndroidDeviceInfoProvider`** (`app/src/main/kotlin/app/sanctum/machina/logexport/DeviceInfoCollector.kt`) — interface растёт на 6 методов: `thresholdMemoryBytes()`, `isLowMemory()`, `processJavaHeapBytes()`, `processNativeHeapBytes()`, `processTotalPssBytes()`, `lastInitSnapshot(): InitSnapshot?`. `AndroidDeviceInfoProvider` (Hilt + secondary :crash ctor) заполняет первые 5 в обоих процессах; шестой возвращает `null` в :crash-ctor.
- **`DeviceInfoCollector.buildHeader`** — три новых блока строк: `memory:` дополняется `threshold=` + `lowMemory=`; добавляются строки `process:` и `last init:` между `memory:` и `active model:`.
- **`LogcatReader.argv`** — индекс 5 (`"*:E"` → `"*:W"`), один токен.
- **`InitDiagnostics`** (новый, `:core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/InitDiagnostics.kt`) — interface seam:
  ```kotlin
  interface InitDiagnostics {
      fun onInitStart(modelName: String, freeRamBytes: Long, atEpochMs: Long)
      fun onInitEnd(success: Boolean)
  }
  ```
  Также `NoOpInitDiagnostics` в test-source-set `:core-runtime` для изоляции registry-тестов.
- **`DiagnosticsState`** (новый, `app/src/main/kotlin/app/sanctum/machina/diagnostics/DiagnosticsState.kt`) — `@Singleton`, реализует `InitDiagnostics`. Хранит `AtomicReference<InitSnapshot?>` где `InitSnapshot(modelName, freeRamBytes, atEpochMs, outcome: Outcome)` и `Outcome = InProgress | Ok | Failed`. Сменяется атомарно: `onInitStart` записывает новую запись с `InProgress`; `onInitEnd` через `compareAndSet`/`update` меняет outcome той же записи.
- **`DiagnosticsModule`** (новый, `app/src/main/kotlin/app/sanctum/machina/diagnostics/di/DiagnosticsModule.kt`) — Hilt `@InstallIn(SingletonComponent::class)` `@Binds InitDiagnostics: DiagnosticsState`.
- **`DefaultModelRegistry`** (`core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`) — `@Inject` принимает `InitDiagnostics`; в `initialize()` вызов `onInitStart` после `releaseEngine(model)` и непосредственно перед `try`-блоком (между строками 234 и 236, до первого `awaitInitialize` на 243); `onInitEnd(true)` после успешного return на arm'ах GPU (после строки 245) и CPU (после строки 258); `onInitEnd(false)` на failure-arm'е после `errorLog.e` (строка 262) и до `Result.failure(...)` (строка 263). `MemoryInfo.availMem` читается через уже инжектированный `@ApplicationContext context` + `ActivityManager`.
- **`Model.minDeviceMemoryInGb`** (`core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Model.kt:59`) — уже объявлено, не меняется. KDoc обновляется: «PhoneWrap gate input — see Phase 3.5 tech-spec».
- **`ModelManagerViewModel`** (`app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerViewModel.kt`) — добавляется `DeviceInfoProvider` в конструктор (через Hilt), новый `data class GateDecision(val allowed: Boolean, val totalBytes: Long, val minGb: Int)`, новый `StateFlow<List<ModelRowState>>` где `ModelRowState(entry, gate)`. `onDownload` короткозамыкается если `!gate.allowed`.
- **`gateAllowsDownload(totalBytes, minGb)`** — pure-функция в `ModelManagerViewModel.kt` (или sibling top-level), pure-JVM-тестируемая.
- **`ModelStatusSection`** (`ModelManagerScreen.kt:265-330`) — ветка `NOT_DOWNLOADED` принимает `gate: GateDecision`; при `!gate.allowed` рендерит disabled-`Button` + новый `Text(...)` с secondary-text. На supported-устройствах разметка не меняется.
- **`app/build.gradle.kts:18`** — `versionName = gitVersionName()` где `gitVersionName(): String` — extension/top-level helper в `app/build.gradle.kts` (или `gradle/git-version.gradle.kts` apply-from). Использует `providers.exec`, try/catch с fallback на `"v0.3.5-diagnostics-fallback"`.
- **`DiagnosticsScreen`** (новый, `app/src/main/kotlin/app/sanctum/machina/ui/diagnostics/DiagnosticsScreen.kt`) — Compose-экран с TopAppBar (title «Диагностика», back-arrow), две секции: «RAM» (две `Text`-строки от ViewModel'и) и «Логи» (одна `Button` «Сохранить лог» с тем же SAF-launcher что был в About).
- **`DiagnosticsViewModel`** (новый, `app/src/main/kotlin/app/sanctum/machina/ui/diagnostics/DiagnosticsViewModel.kt`) — `@HiltViewModel`. Зависимости: `DiagnosticsState` (для `lastInitSnapshot()`), `DeviceInfoProvider` (для `availableMemoryBytes()`), `LogExportManager` (`buildAndWrite(uri)` — мигрирует из `AboutViewModel`). Экспонирует `StateFlow<DiagnosticsUiState>` где UI-state содержит уже отформатированную строку «Последняя инициализация» (с тремя ветками AC-D6: ok / ошибка / «пока не было»; политика рендера `Outcome.InProgress` — см. Decision 12) и текущий `freeRamBytes`. Внутри `init` запускает стандартный Kotlin Coroutines `viewModelScope`-цикл: `viewModelScope.launch { while (isActive) { delay(1_000); refreshFreeRam() } }` — отмена цикла происходит автоматически в `onCleared()` через cancellation `viewModelScope`.
- **`SanctumApp.kt`** (`app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`) — новый `composable("diagnostics") { DiagnosticsScreen(onBack = ...) }` после `about`-route (line 172); новый callback `onNavigateToDiagnostics` в `DrawerContent`-инвокации (line 39-75).
- **`DrawerContent.kt`** (`app/src/main/kotlin/app/sanctum/machina/ui/drawer/DrawerContent.kt:78-188`) — новый параметр `onNavigateToDiagnostics: () -> Unit`; в `DrawerFooter` третий `NavigationDrawerItem` между двумя существующими. Иконка: `Icons.Outlined.MonitorHeart` (или `BugReport` — solver выбирает в task-decomposition).
- **`AboutScreen.kt`** — удаляется секция «Диагностика» (lines 165–178), удаляется SAF-launcher state (lines 116, 120–134), удаляется `Button` «Сохранить лог». Остаётся: `SafeMarkdown`, `AboutFooter`, `tapCounter`, `AlertDialog` (test-crash dialog), 7-tap-обвязка на version-label.
- **`strings.xml`** — три новых строки: `drawer_nav_diagnostics`, `model_gate_secondary` (формат для secondary-text под disabled-кнопкой), `diagnostics_*` (заголовки секций, метки).

### How it works

**Гейт-поток (источник правды — Model Manager):**
1. `AllowlistLoader.load` парсит JSON → `AllowedModel` → `Model` (с заполненным `minDeviceMemoryInGb`). Запись с `null` отбрасывается на этапе парсинга, в `errors.log` пишется компонент `"download"`.
2. `DefaultModelRegistry` хранит уже отфильтрованный список моделей.
3. `ModelManagerViewModel` подписывается на `registry.models` и в `combine` с `DeviceInfoProvider.totalMemoryBytes()` (читается один раз при старте VM — `totalMem` не меняется в runtime) строит `List<ModelRowState>`.
4. `ModelManagerScreen` рендерит каждый row через `ModelStatusSection`, передавая `gate`. На `NOT_DOWNLOADED` + `!gate.allowed` — disabled-кнопка + secondary-text.
5. Тап по disabled-кнопке либо невозможен (Compose disabled), либо early-return в `onDownload` (defence-in-depth).

**Diagnostics-поток (init-snapshot):**
1. `WarmupCoordinator.launchWarmup` или `ChatViewModel.applyHeavySetting` зовёт `registry.initialize(modelName)`.
2. Внутри `DefaultModelRegistry.initialize` под `lifecycleMutex`: непосредственно перед `awaitInitialize` (первая GPU-попытка) вызывается `initDiagnostics.onInitStart(modelName, am.memoryInfo.availMem, System.currentTimeMillis())`.
3. `DiagnosticsState` атомарно записывает новый `InitSnapshot(...)` с `InProgress`.
4. На любой success-ветке (GPU или CPU fallback) — `onInitEnd(true)` → `Ok`. На failure-ветке (GPU+CPU fail) — `onInitEnd(false)` → `Failed`.
5. `DiagnosticsViewModel` читает `state.lastInitSnapshot()` при composition'е и форматирует строку. `DeviceInfoCollector` читает то же самое при `buildHeader()`.

**SAF-export reuse:**
`LogExportManager.buildExport(ExportSource.About)` остаётся. `AboutViewModel.buildAndWrite` переезжает в `DiagnosticsViewModel` (или общий `LogExportManager.writeTo` напрямую вызывается из ViewModel'и). `ExportSource.About` enum-value сохраняется (не переименовывается в Diagnostics) — это серверная сторона export'а, поведение идентично, переименование create churn без выгоды.

### Shared resources

| Resource | Owner (creates) | Consumers | Instance count |
|----------|-----------------|-----------|----------------|
| `DiagnosticsState` (`@Singleton`) | Hilt graph (`DiagnosticsModule.@Binds`) | `DefaultModelRegistry` (writer, через `InitDiagnostics`), `DeviceInfoCollector` (reader), `DiagnosticsViewModel` (reader) | 1 (singleton, in-memory только в main-процессе) |
| `DeviceInfoProvider` (`AndroidDeviceInfoProvider`) | Hilt graph (`LogExportModule`) — основной процесс; secondary ctor — `:crash`-процесс | `DeviceInfoCollector`, `ModelManagerViewModel` (новый consumer для `totalMemoryBytes`), `DiagnosticsViewModel` (новый consumer для `availableMemoryBytes` 1-сек тика) | 1 на процесс (2 инстанса в системе при `:crash`-recovery, см. `architecture.md § Non-Hilt construction in :crash`) |
| `ActivityManager` | Системный | `AndroidDeviceInfoProvider`, `DefaultModelRegistry` (новый consumer для `availMem` снимка) | n/a — system service |

`InitDiagnostics` interface — это **read/write seam**, не resource. Singleton (`DiagnosticsState`) единственный — он уже в таблице.

## Decisions

### Decision 1: Pre-flight gate at Download time, not at init time
**Decision:** Гейтить на этапе показа кнопки «Скачать» в Model Manager, а не на init-time.
**Rationale:** Для устройств с заведомо недостаточной RAM нет смысла тратить трафик на 2.5–3.7 ГБ модели и потом ловить нативный краш. Гейт перед download'ом — корректный UX и единственный способ закрыть OOM-класс крашей в источнике.
**Alternatives considered:** Init-time гейт (отвергнут — модель уже скачана, юзер уже потратил трафик и видел успешный download bar; запрет на init выглядит как баг). Двойной гейт (отвергнут — usermodel уже не позволяет начать download, init-time проверка избыточна на passing-устройствах).
**Supports:** US-G1, US-G2, US-G3, US-G4, US-G5, US-G6, US-G7.

### Decision 2: Reuse existing `Model.minDeviceMemoryInGb: Int?`, do not introduce `minRamBytes: Long`
**Decision:** Использовать уже объявленное в `Model.kt:59` поле `minDeviceMemoryInGb: Int?`. Маппить его в `AllowedModel.toModel()` (где сейчас silent-drop). Парсер требует non-null, иначе отклоняет запись.
**Rationale:** Поле декларировано с Phase 1 как upstream-Gallery property, но dead. GB-precision (целое число) достаточно: разница «нужно 4 ГБ vs 4096 МБ» на реальных устройствах не имеет UX-значения. Введение нового `minRamBytes: Long` создаёт два конкурирующих поля и инвитит drift.
**Alternatives considered:** Новое поле `minRamBytes: Long = 0L` (отвергнут — два поля «об одном и том же» приводят к путанице; см. code-research §A.1 «Allowlist field rename concern»). Удаление `minDeviceMemoryInGb` целиком и введение `minRamBytes` (отвергнут — silently drops upstream Gallery field, ломает совместимость с потенциальными pull-через-rebase).
**Supports:** US-G1, US-G2, US-G4. **Deviates from code-research draft**, не от user-spec — user-spec уже принял это решение в § «Технические решения», строка 134.

### Decision 3: Compare against `MemoryInfo.totalMem`, byte-precise, no early rounding
**Decision:** Гейт-предикат: `totalBytes >= minGb * 1_073_741_824L` (с `Long`-арифметикой).
**Rationale:** `MemoryInfo.totalMem` на устройствах с номинальными 12 ГБ возвращает ~11.5 ГБ (kernel reserves), на 6 ГБ — ~5.7 ГБ. Преждевременное округление (`totalGb >= minGb` где `totalGb = totalBytes / 1_073_741_824`) даёт false-negative на edge-cases (12 ГБ устройство показывает `11`, не проходит порог `12`). Сравниваем в байтах, округляем только для отображения юзеру.
**Alternatives considered:** Округление до GB и потом сравнение (отвергнут — false-negative на номинальных устройствах). Сравнение `availMem` вместо `totalMem` (отвергнут — available плавает от других приложений; устройство-характеристика — это `totalMem`).
**Supports:** US-G4, US-G5.

### Decision 4: Recalibrate allowlist values (E2B 8→4, E4B 12→6)
**Decision:** В `model_allowlist.json` поставить `minDeviceMemoryInGb: 4` для E2B, `6` для E4B.
**Rationale:** Gallery-defaults (`8` / `12`) перестраховочные — Honor 200 (12 ГБ номинальных, 11.5 ГБ totalMem) на старом значении `12` для E4B был бы **заблокирован**. Калибровка эмпирическая: E2B = 4 при S20 FE 5.3 ГБ failing (порог между 5.3 и 6+ — берём 4 с запасом снизу); E4B = 6 при Honor 200 11.5 ГБ working и SM-G780F 5.3 ГБ failing.
**Alternatives considered:** Оставить Gallery-defaults (отвергнут — блокирует dev-target). Калибровка из API ML-Kit's heuristics (отвергнут — нет такой метрики; Gallery'я тоже эмпирическая).
**Supports:** US-G3.

### Decision 5: Reject null `minDeviceMemoryInGb` at parse time, log from `load()`
**Decision:** В `AllowlistLoader.parse` (companion-функция, non-suspend) добавить `require(m.minDeviceMemoryInGb != null) { "model <id> rejected: missing minDeviceMemoryInGb" }` и `require(m.minDeviceMemoryInGb in 1..64) { "model <id> rejected: minDeviceMemoryInGb out of [1..64]" }`. Бросаемое `IllegalArgumentException` ловится в `load()` (suspend), в catch-ветке вызывается `errorLog.e("download", message)` — запись пропускается, не попадает в registry.
**Rationale:** Future Phase 4 добавит FunctionGemma-270M в allowlist. Если разработчик забудет выставить порог — без fail-loud-семантики модель тихо пропустит гейт (порог `null` = «всем разрешено») и снова крашнет на сабтрешолд-устройствах. Парсер-уровневое отклонение делает забывание видимым в `errors.log` и пустым местом в Model Manager. Range `1..64` нужен для защиты от опечаток (отрицательное значение → `totalBytes >= negative*GB` всегда true → silent fail-open; абсурдное `1024` → universal block без сигнала). Логирование вытащено в `load()`, потому что `parse` — companion non-suspend без `ErrorLog`-зависимостей; `ErrorLog.e` — suspend.
**Alternatives considered:** `null` = «всем разрешено» (отвергнут — silent fail-open, повторяет исходный баг). `null` = «никому не разрешено» (отвергнут — модель видна в UI как неработающая, путает юзера). Compile-time проверка через kotlinx.serialization (отвергнут — Gson reflection слишком привычен в проекте; миграция на kotlinx — отдельная фаза). Сделать `parse` suspend и логировать на месте (отвергнут — `parse` остаётся pure transformation, не нужно тащить корутины в утилиту, сложнее тестировать).
**Supports:** US-G2.

### Decision 6: Interface seam `InitDiagnostics` in `:core-runtime`, impl in `:app`
**Decision:** Интерфейс `InitDiagnostics` объявлен в `:core-runtime/registry/`, реализация `DiagnosticsState` (`@Singleton`) — в `:app/diagnostics/`, Hilt-binding в `app/diagnostics/di/DiagnosticsModule.kt` через `@Binds`.
**Rationale:** `:core-runtime` не может импортировать `:app` (модульная граница, см. `architecture.md § Module graph`). Альтернатива — поместить `DiagnosticsState` в `:core-runtime` (KMP-discipline снят, технически легально). Выбран interface seam, потому что (а) `DiagnosticsState` логически принадлежит `:app` (UI-фича, не runtime engine concern), (б) тот же паттерн `interface-в-:core-runtime + @Binds-в-:app` уже используется в `LogExportModule` для двух пар (`CommandRunner` → `DefaultCommandRunner`, `DeviceInfoProvider` → `AndroidDeviceInfoProvider`), (в) тесты `:core-runtime/registry` могут использовать `NoOpInitDiagnostics` без подтаскивания `:app`-зависимостей.
**Alternatives considered:** `DiagnosticsState` в `:core-runtime` (отвергнут — concern leakage; UI-state в runtime-модуле). Использовать существующий `ErrorLog` для записи init-attempts (отвергнут — `ErrorLog` суспенд + bounded по 500 chars; снимок RAM — это state, не event log). Передавать `DiagnosticsState` через параметр в `WarmupCoordinator.warmup()` (отвергнут — не покрывает `ChatViewModel.applyHeavySetting`, который зовёт `registry.initialize` напрямую). Static `@Volatile var` companion-field setter в стиле `DefaultDownloadRepository.mainActivityFqn` (отвергнут — тот паттерн используется для FQN-string injection из main-process startup, не для multi-method-interface, который Hilt умеет binding'овать намного чище).
**Supports:** US-D2.

### Decision 7: Atomic snapshot via `AtomicReference<InitSnapshot?>`
**Decision:** `DiagnosticsState` хранит `private val ref = AtomicReference<InitSnapshot?>(null)`. `onInitStart` делает `ref.set(InitSnapshot(name, ram, time, InProgress))`. `onInitEnd(success)` делает `ref.updateAndGet { current -> current?.copy(outcome = if (success) Ok else Failed) }`.
**Rationale:** AC-D1 требует атомарность: читатель никогда не видит частично-обновлённое состояние (например, model id новой попытки со старым outcome). `AtomicReference.updateAndGet` гарантирует CAS-обновление; единственный writer (только из `lifecycleMutex`-секции `DefaultModelRegistry`) исключает write-write race по построению, но `AtomicReference` всё равно нужен для visibility между writer-thread (Dispatchers.Default) и reader-thread (Main, через ViewModel state-flow).
**Alternatives considered:** `@Volatile var snapshot: InitSnapshot?` (отвергнут — visibility OK, но `copy(outcome=...)` в `onInitEnd` требует read-modify-write; without CAS возможен lost update если хоть когда-то появится второй writer). `Mutex` + suspending API (отвергнут — `DefaultModelRegistry.initialize` уже под `lifecycleMutex.withLock`; nested locking + suspending boundary в init-path излишен).
**Supports:** US-D1.

### Decision 8: Snapshot site at `DefaultModelRegistry.initialize`, before first `awaitInitialize`
**Decision:** Вызов `initDiagnostics.onInitStart(...)` сразу после `releaseEngine(model)` и перед началом `try`-блока (между строками 234 и 236; первый `awaitInitialize` — на 243). Один снимок на init-attempt; CPU-fallback (повторный `awaitInitialize` на строке 256) не пишет новый snapshot, оставляя `Outcome.InProgress` неизменным до финального arm'а. `onInitEnd(true)` — после успешного return на одном из success-arm'ов (после строк 245 или 258); `onInitEnd(false)` — на failure-arm'е после `errorLog.e` на 262 и до `Result.failure` на 263.
**Rationale:** Снимок — это «состояние RAM в момент попытки init», а не «попытка init на конкретном accelerator». GPU+CPU fallback — это одна попытка с точки зрения юзера. Регистрировать оба arm'а отдельно создаёт duplicate-snapshot ambiguity. Точка после `releaseEngine` (а не до) даёт более честную метрику: до `releaseEngine` старый движок ещё в памяти, после — мы реально измеряем RAM, доступную новому init'у.
**Alternatives considered:** Снимок в `WarmupCoordinator.launchWarmup` (отвергнут — heavy-setting reinit идёт мимо WarmupCoordinator через `ChatViewModel.applyHeavySetting`, см. `patterns.md § Heavy-setting reinit`; пропустит снимок). Снимок до `releaseEngine` (отвергнут — мерим RAM с прошлым движком в памяти). Два снимка (GPU + CPU fallback) (отвергнут — semantic ambiguity, два snapshot'а перезатирают друг друга в `AtomicReference`).
**Supports:** US-D3.

### Decision 9: `MemoryInfo.availMem` read via already-injected `@ApplicationContext`
**Decision:** В `DefaultModelRegistry` использовать существующий `@ApplicationContext context` (line 105) для `context.getSystemService(ACTIVITY_SERVICE) as ActivityManager` и `am.getMemoryInfo(MemoryInfo())`.
**Rationale:** `Context` уже инжектирован для `Model.getPath(context)` в других call-site'ах. Никакой новой DI-связности. JNI-call синхронный, документировано thread-safe — безопасен под `Dispatchers.Default + lifecycleMutex`. Cost ≈ 50 µs на init-attempt (2-3 раза за сессию).
**Alternatives considered:** Новый `ActivityManagerProvider` interface (отвергнут — overkill; добавляет seam без выигрыша в тестируемости — `AtomicReference`-based `DiagnosticsState` уже тестируется без устройства). Передать `MemoryInfo`-snapshot как параметр в `onInitStart` снаружи (отвергнут — call-site в `WarmupCoordinator` не имеет `Context` без новой инъекции; точка снимка должна быть атомарной с моментом init-старта, см. Decision 8).
**Supports:** US-D4.

### Decision 10: Convention plugin in `build-logic/` for git version, with pure-fun seam for tests
**Decision:** Логика чтения git-версии разносится на два уровня:
1. **Convention-плагин в `build-logic/`** (или `buildSrc/`, solver выбирает в task-decomposition; `build-logic/` предпочтительнее для config-cache совместимости в Gradle 8.x). Плагин использует Gradle `providers.exec { commandLine("git", "describe", "--tags", "--always", "--dirty=-dev") }`, читает stdout/exitCode.
2. **Pure-функция `gitVersionParse(stdout: String, exitCode: Int): String?`** — принимает уже captured stdout/exitCode (не дёргает exec сама). Возвращает trimmed version-string или `null` при ошибке (non-zero exit, пустой stdout). Лежит либо в `build-logic/src/main/kotlin/.../GitVersionParse.kt` (если convention plugin), либо как extracted helper в `app/src/main/kotlin/app/sanctum/machina/build/GitVersionParse.kt` (тогда convention-plugin импортирует её). Solver выбирает размещение в task-decomposition.
3. В `app/build.gradle.kts`: `versionName = gitVersionName.get() ?: "v0.3.5-diagnostics-fallback"` где `gitVersionName: Provider<String?>` приходит из плагина.
**Rationale:** `providers.exec` — config-cache-совместимый Gradle Provider API (vs `Runtime.getRuntime().exec` или legacy `exec { ... }` task). Разделение exec и парсинга нужно потому, что `app/src/test/` не может импортировать классы из `app/build.gradle.kts` (build-script classpath отдельный); pure-функция парсинга в `build-logic/` (convention-plugin classpath) или в `app/src/main/` (production classpath) — обе тестируемы JUnit.
**Alternatives considered:** Inline `providers.exec` прямо в `app/build.gradle.kts` без выделения pure-функции (отвергнут — невозможно протестировать, повторяет ошибку по которой `versionName = "0.1.0"` хардкод заехал в Phase 1 и не двигался 4 фазы). Отдельный exec-task с outputs (отвергнут — overengineered, не нуждается в task graph). `Runtime.getRuntime().exec` (отвергнут — не config-cache-friendly). Хардкод + ручная синхронизация (отвергнут — история фаз показывает, что забывается всегда).
**Supports:** US-V1, US-V2, US-V3, US-V4.

### Decision 11: SAF-launcher state migrates whole, not duplicates
**Decision:** SAF-launcher (`rememberLauncherForActivityResult(CreateDocument("text/plain"))`), `var launching: Boolean`, `viewModel.buildAndWrite(uri)` — мигрируют из `AboutScreen`/`AboutViewModel` в `DiagnosticsScreen`/`DiagnosticsViewModel` целиком. `AboutViewModel.buildAndWrite` удаляется (если у `AboutScreen` после рефакторинга больше нет вызовов).
**Rationale:** AC-A1 требует «никакой дублирующей реализации». Дублирование state + launcher создаёт два места для багов (например, `launching`-flag не сбрасывается в одном из них). Переезд целиком — один writer, одна точка изменений.
**Alternatives considered:** Дублировать кнопку в обоих экранах (отвергнут — нарушает AC-A1; выглядит legacy). Оставить кнопку в About как «hidden». (отвергнут — manifest/About-экран должен быть чистым).
**Supports:** US-A1.

### Decision 12: `Outcome.InProgress` UI rendering — fourth variant with «initializing» marker
**Decision:** Расширяем AC-D6 четвёртым рендер-вариантом для `InProgress`. `DiagnosticsViewModel` рендерит данные текущего snapshot'а: `freeRamBytes` (на момент `onInitStart`), `atEpochMs`, `modelName` — с явным маркером «инициализация», без outcome-эмодзи/слова. Финальный формат строки (точная wording — за solver'ом, например): «Идёт инициализация: X.X ГБ RAM · HH:mm · <model id>». Когда приходит `onInitEnd`, строка переключается на ok/ошибка-вариант с теми же значениями полей. При `snapshot=null` — «пока не было».
**Rationale:** Юзер-разработчик одобрил в [Phase-3.5-TechSpec session, 2026-04-28]. Цели: (а) видеть `freeRamBytes` на момент запуска прогрева сразу, не дожидаясь финиша init'а — диагностическая ценность сохраняется и для зависших init'ов, не дошедших до `onInitEnd`; (б) экран отвечает на вопрос «прогрета ли модель сейчас» — InProgress vs Ok visible. Скрывать `InProgress` (отвергнутый proposal) терял эти два сигнала.
**Alternatives considered:**
- (a) Скрыть `InProgress`, показывать предыдущий snapshot (отвергнут юзером — теряет «прогрета ли сейчас» сигнал и RAM-на-момент-запуска до финала init'а).
- (b) Заменить на «пока не было» при `onInitStart` (отвергнут — теряет предыдущий завершённый init).
- (c) Показывать просто слово «инициализация…» без полей (отвергнут — нет диагностической ценности; юзеру нужны RAM-цифра и время).
**Supports:** US-D1; **расширяет US-D6** (расширение явное, см. User-Spec Deviations).

### Decision 12: Final Wave = Pre-deploy QA only, no Deploy/Post-deploy
**Decision:** `[TECHNICAL]` Финальная волна содержит только `pre-deploy-qa`. Deploy и post-deploy verification отсутствуют.
**Rationale:** Sanctum Machina — Android-приложение, distribution = manual APK transfer (см. `deployment.md § Deployment Platform`). CI/CD для production не настроен. Live-environment verification невозможна как concept — APK ставится юзером на Honor 200, post-deploy MCP-инструменты неприменимы. Honor 200 manual smoke выполняется в рамках pre-deploy-qa.
**Alternatives considered:** none — структурное ограничение мобильной дистрибуции.
**Marker:** [TECHNICAL] — не выводится из user-spec, диктуется проектной инфраструктурой.

## Data Models

### `InitSnapshot`

```kotlin
// :app/src/main/kotlin/app/sanctum/machina/diagnostics/InitSnapshot.kt
data class InitSnapshot(
    val modelName: String,
    val freeRamBytes: Long,
    val atEpochMs: Long,
    val outcome: Outcome,
)

enum class Outcome { InProgress, Ok, Failed }
```

Hosted by `DiagnosticsState` as `AtomicReference<InitSnapshot?>` — null = «пока не было».

### `GateDecision`

```kotlin
// :app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/GateDecision.kt (or sibling)
data class GateDecision(val allowed: Boolean, val totalBytes: Long, val minGb: Int)

data class ModelRowState(val entry: ModelEntry, val gate: GateDecision)

// Pure-function gate predicate, top-level in same file
fun gateAllowsDownload(totalBytes: Long, minGb: Int?): Boolean =
    minGb != null && totalBytes >= minGb.toLong() * 1_073_741_824L

// Pure-function formatter for AC-G5 secondary-text. Floor-precision to 1 decimal,
// no rounding-up, Locale.ROOT-stable. Tested via fixture-table:
//   formatRamShortage(5_694_498_816L, 6) == "Недостаточно RAM (5.3 ГБ устройство, нужно 6 ГБ)"
//   formatRamShortage(11_500_000_000L, 16) == "Недостаточно RAM (10.7 ГБ устройство, нужно 16 ГБ)"
fun formatRamShortage(totalBytes: Long, minGb: Int): String =
    "Недостаточно RAM (${formatGbFloor(totalBytes)} ГБ устройство, нужно $minGb ГБ)"

private fun formatGbFloor(bytes: Long): String {
    val tenths = (bytes * 10L) / 1_073_741_824L  // floor to 0.1 GB without rounding-up
    return "${tenths / 10}.${tenths % 10}"
}
```

### `Model.minDeviceMemoryInGb`

Уже объявлено (`core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Model.kt:59`):
```kotlin
val minDeviceMemoryInGb: Int? = null,
```
Не меняется. KDoc обновляется однострочным комментарием: «Phase 3.5 gate input: total RAM threshold below which the model is hidden from Model Manager. Null is rejected by the parser — see Phase 3.5 tech-spec».

### `InitDiagnostics`

```kotlin
// :core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/InitDiagnostics.kt
interface InitDiagnostics {
    fun onInitStart(modelName: String, freeRamBytes: Long, atEpochMs: Long)
    fun onInitEnd(success: Boolean)
}
```

`NoOpInitDiagnostics` — test-source-set-only fake (no-op stubs) для изоляции `DefaultModelRegistryTest`.

## Dependencies

### New packages

None. Все зависимости (`AtomicReference` в JDK, `ActivityManager`/`Debug`/`Runtime` в Android SDK, `providers.exec` в AGP 8.8, Material `Icons.Outlined.MonitorHeart` в `material-icons-extended` уже подключён) — уже на месте.

### Using existing (from project)

- **Hilt 2.57.1 + KSP** — новый `DiagnosticsModule`, инъекция `InitDiagnostics` в `DefaultModelRegistry` (`:core-runtime`).
- **`ErrorLog`** (`:core-runtime/log/`) — новая call-site в `AllowlistLoader.parse` с компонентом `"download"` (whitelist не расширяется — компонент уже в allowlist'е).
- **`DeviceInfoProvider`** (`:app/logexport/`) — новый consumer (`ModelManagerViewModel`, `DiagnosticsViewModel`).
- **`LogExportManager`** (`:app/logexport/`) — `buildExport(ExportSource.About)` остаётся, новая call-site в `DiagnosticsViewModel`.
- **`SafeMarkdown`** — НЕ используется в `DiagnosticsScreen`. Контент экрана — фиксированные plain-strings (timestamp, byte counts, model id) — рендерятся через обычный `Text`.
- **Navigation Compose** — новая `composable("diagnostics") { ... }` route.

## Testing Strategy

**Feature size:** M

### Unit tests (pure-JVM, без Robolectric где возможно)

- **AllowlistLoader parser**:
  - `minDeviceMemoryInGb = null` → запись отклоняется, `ErrorLog.e("download", ...)` вызван (verify через test fake).
  - `minDeviceMemoryInGb = 6` → попадает в `Model.minDeviceMemoryInGb`.
  - Фикстура (`model_allowlist_fixture.json`) синхронизирована с production-asset (existing `fixtureMatchesProductionAsset` test ловит drift).
- **`gateAllowsDownload(totalBytes, minGb)` — pure-функция**:
  - Граничные случаи: `totalBytes = 11.5 ГБ vs minGb = 6` → true; `totalBytes = 5.3 ГБ vs minGb = 6` → false; `totalBytes = 5.3 ГБ vs minGb = 4` → true; `totalBytes = 4.0 ГБ vs minGb = 4` → true (точное равенство); `totalBytes = 3.99 ГБ vs minGb = 4` → false; `minGb = null` → false.
- **`ModelManagerViewModel`**:
  - Передавая `DeviceInfoProvider` с `totalMemoryBytes() = 5.3 ГБ` и моделью с `minDeviceMemoryInGb = 6` → `gate.allowed = false`.
  - `onDownload` short-circuits when `!gate.allowed` (verify через fake `ModelRegistry`: `download()` не вызван).
- **`DiagnosticsState` / `InitDiagnostics` impl**:
  - Initial state: `lastInitSnapshot() == null`.
  - `onInitStart` → snapshot non-null с `outcome == InProgress`.
  - `onInitStart` → `onInitEnd(true)` → `outcome == Ok`, `modelName/freeRamBytes/atEpochMs` сохранены неизменными.
  - `onInitStart` → `onInitEnd(false)` → `outcome == Failed`.
  - Атомарность (concrete contract): два потока, ≥10 000 итераций. Writer-thread в цикле чередует `onInitStart("modelA", ...)`/`onInitEnd(true)` и `onInitStart("modelB", ...)`/`onInitEnd(false)`. Reader-thread в цикле читает `lastInitSnapshot()` и assert'ит, что прочитанная пара `(modelName, outcome)` принадлежит одному из валидных attempt-set'ов: `(modelA, InProgress|Ok)` либо `(modelB, InProgress|Failed)` либо `null`. Любая «смесь» (`modelA, Failed` или `modelB, Ok`) — fail. Без зависимости от jcstress/lincheck — обычный `Thread` + `CountDownLatch`-контроль.
  - `onInitStart` → новый `onInitStart` (без `onInitEnd` между ними) → новая попытка перезатирает старый snapshot, outcome `InProgress` (фиксируем поведение: replace, не «pending»).
- **`DiagnosticsViewModel`**:
  - Snapshot = `Ok` → UI-state содержит «X.X ГБ RAM · HH:mm · <model> · ok».
  - Snapshot = `Failed` → «X.X ГБ RAM · HH:mm · <model> · ошибка».
  - Snapshot = null → «пока не было».
  - Snapshot = `InProgress` → 4-й вариант рендера (Decision 12): строка содержит маркер «инициализация», `freeRamBytes` (форматирован как `X.X ГБ`), `atEpochMs` (форматирован как `HH:mm`), `modelName` — без слова «ok»/«ошибка». Тест-фикстура: `Snapshot(modelName="Gemma-4-E4B-it", freeRamBytes=3_500_000_000, atEpochMs=..., outcome=InProgress)` → строка содержит подстроки «инициализация», «3.2 ГБ», «<HH:mm>», «Gemma-4-E4B-it» и **не** содержит «ok» / «ошибка».
  - 1-секундный тик free-RAM: `runTest { advanceTimeBy(3_000); ... }` — `freeRamBytes` обновлён 3 раза (или N раз за `N*1_000`мс — стабильная пин-проверка через `verify-count`-fake `DeviceInfoProvider`).
  - `onCleared()` → опрос отменяется: после `viewModel.clear()` (или scope cancellation в `runTest`) дальнейший `advanceTimeBy` не приводит к новым вызовам `availableMemoryBytes()`.
- **`RecordingInitDiagnostics` test fake** (positive-assertion path для `:core-runtime/registry`):
  - Fake записывает все вызовы `onInitStart`/`onInitEnd` в список. `DefaultModelRegistryTest` проверяет: один `onInitStart` перед первым `awaitInitialize`; ровно один `onInitEnd(true)` на success-arm'е; ровно один `onInitEnd(false)` на failure-arm'е; на CPU-fallback-success — ровно один `onInitEnd(true)` (не два). Без RecordingInitDiagnostics — `NoOpInitDiagnostics` для тестов, не зависящих от call-order.
- **AC-H4 `:crash`-process degradation**: `DeviceInfoCollector.buildHeader` с secondary-ctor `AndroidDeviceInfoProvider` (где `lastInitSnapshot() == null`) рендерит `last init: пока не было`; `memory:` и `process:` строки рендерятся нормально (новые поля доступны через системные API без `DiagnosticsState`).
- **`formatRamShortage` formatter (AC-G5)**:
  - Fixture-table тест на pure-функцию: `formatRamShortage(5_694_498_816L, 6) == "Недостаточно RAM (5.3 ГБ устройство, нужно 6 ГБ)"`, `formatRamShortage(11_500_000_000L, 16) == "Недостаточно RAM (10.7 ГБ устройство, нужно 16 ГБ)"`, граничные случаи (`5_368_709_120L → "5.0"`, `4_294_967_295L → "3.9"` — floor, не round-up).
- **`DeviceInfoCollector.buildHeader`**:
  - `memory:` строка содержит `threshold=` и `lowMemory=` с правильным форматом.
  - `process:` строка появилась с тремя полями; при ошибке источника — `n/a` для ошибочного поля, остальные ОК.
  - `last init:` ветка `Ok` / `Failed` / null (рендер «пока не было»).
  - Расширенный `expected`-blob в `headerFormatting_deterministicFromStub`.
  - `StubDeviceInfoProvider` растёт с интерфейсом.
- **`LogcatReader`**:
  - `argv[5] == "*:W"` (обновление существующего `argvShape_*` теста).
  - Остальные тесты (timeout, empty stdout, drain) проходят без изменений.
- **`gitVersionParse(stdout, exitCode)` pure-функция** (см. Decision 10):
  - `gitVersionParse("v0.3.5-diagnostics\n", 0) == "v0.3.5-diagnostics"` (trim).
  - `gitVersionParse("v0.3.5-diagnostics-3-gabc1234\n", 0) == "v0.3.5-diagnostics-3-gabc1234"`.
  - `gitVersionParse("v0.3.5-diagnostics-dev\n", 0) == "v0.3.5-diagnostics-dev"`.
  - `gitVersionParse("", 0) == null` (пустой stdout).
  - `gitVersionParse("anything", 1) == null` (non-zero exit).
  - `gitVersionParse("anything", 128) == null` (git error code).
  - Тест либо в `build-logic/src/test/kotlin/.../GitVersionParseTest.kt` (если convention-plugin), либо в `app/src/test/kotlin/app/sanctum/machina/build/GitVersionParseTest.kt` (если pure-функция в main). Решение по размещению — в task-decomposition Task 2.

### Existing tests to verify still passing

После Phase 3.5 реализации перечисленные ниже тесты должны пройти без изменений (или с минимальной адаптацией под новые DI-зависимости):

- `ModelManagerViewModelTest` — VM-конструктор получает новый параметр `DeviceInfoProvider`; существующие тесты передают stub. Существующие assert'ы на `setDefaultModel_*`, `defaultModelId_*`, `onLoad_*` остаются.
- `AboutViewModelTest` — если `buildAndWrite` мигрирует целиком, соответствующий тест переносится в `DiagnosticsViewModelTest`. Иначе остаётся.
- `LogExportManagerTest.headerContainsRequiredFields` — substring-проверки уже open-ended (`assertTrue(out.contains(...))`), не ломаются от добавления `process:` / `last init:`. Желательно расширить тест на новые substring'и.
- `WarmupCoordinatorTest` — fake `ModelRegistry` не меняется (`InitDiagnostics` — деталь импла registry, не интерфейса). Тесты без изменений.
- `AllowlistLoaderTest.fixtureMatchesProductionAsset` — после рекалибровки JSON фикстура копируется один-в-один, тест продолжает фейлить drift.
- `AppCorruptionStateTest` — независимо.

### Integration tests

None. Каждый компонент тестируется через interface seam (existing `DeviceInfoProvider`, новый `InitDiagnostics`, gradle через `CommandRunner`-fake). Cross-component flow (Model Manager UI → ModelManagerViewModel → registry → AllowlistLoader → JSON) покрывается комбинацией unit-тестов на каждом seam'е. Отсутствие integration-тестов — следствие test-strategy проекта (`architecture.md § Testing` — JUnit + Robolectric, no instrumented tests кроме DAO).

### E2E tests

None. Compose UI tests не входят в проектную тестовую стратегию (`architecture.md § Testing`). Honor 200 manual smoke — это E2E-эквивалент, исполняется в pre-deploy-qa.

## Agent Verification Plan

**Source:** user-spec «Как проверить» section.

### Verification approach

Per-task smoke checks указаны в каждой задаче ниже (`Verify-smoke`/`Verify-user`). Большинство задач — internal-logic, покрываются unit-тестами; smoke-проверки минимальны до финальной QA-волны.

В соответствии с проектной памятью «Verify UI chain before device smoke» — устройственная верификация (Honor 200 manual smoke) **не дробится** по задачам, а собирается в Pre-deploy QA задачу финальной волны. Промежуточные UI-задачи могут проходить только sanity-проверкой через Gradle build / unit tests.

### Tools required

- `bash` — Gradle команды (`./gradlew :core-runtime:test`, `./gradlew :app:test`, `./gradlew :app:assembleDebug`, `./gradlew :app:lintDebug`), `git describe`, grep'ы.
- `aapt` (часть Android SDK build-tools) — для проверки `BuildConfig.VERSION_NAME` через `aapt dump badging`.
- Honor 200 (физическое устройство) — финальный manual smoke в pre-deploy-qa.

Никаких MCP-инструментов (Playwright, Telegram MCP) — мобильное приложение не имеет live-environment'а в облаке.

## Risks

| Risk | Mitigation |
|------|-----------|
| Некорректная калибровка `minDeviceMemoryInGb` (false-positive: устройство, на котором модель в реальности запустилась бы) | Калибровка занижена с запасом (E2B = 4 при S20 FE 5.3 ГБ failing; E4B = 6 при Honor 200 11.5 ГБ working). При false-positive репорте — рекалибровка в патч-релизе через JSON-asset edit. |
| `providers.exec("git describe ...")` ломает билд на shallow-clone / tarball | `Provider.orElse("v0.3.5-diagnostics-fallback")` — fallback на хардкод. Pure-JVM-тест на функцию через injected `CommandRunner` fake (паттерн из `LogcatReader`). |
| `*:W` логкат флудит на noisy OEM (Realme/Samsung), вытесняет полезные ERROR-записи под cap'ом 100 KB | Tail-truncation strategy в `LogExportManager` сохраняет самые свежие записи (а это — где живут предсмертные WARN от LiteRT). Cap 100 KB ≈ 670 строк threadtime ≈ 2-4 секунды активности на WARN. Honor 200 manual smoke в pre-deploy-qa (AC-L3) явно проверяет, что cap не вытесняет ERROR из лога. Если впритык — поднимаем `MAX_LOGCAT_BYTES` в патче. |
| 1-секундный опрос `MemoryInfo` на DiagnosticsScreen — battery drain | Опрос в `viewModelScope`, отменяется в `onCleared()`. Фоновый poll невозможен (экран закрыт = ViewModel умер). `getMemoryInfo` ≈ 50 µs на JNI hop — пренебрежимо. |
| Рефакторинг AboutScreen ломает 7-tap test-crash dialog (R-5 в interview) | `tapCounter`, `AboutFooter`, `AlertDialog`, version-label `Modifier.clickable` остаются на AboutScreen без изменений. Удаляются ТОЛЬКО строки 165-178 (секция «Диагностика») + связанный SAF-launcher state. Honor 200 manual smoke в pre-deploy-qa явно проверяет 7-tap → dev_crash_dialog. |
| `:crash`-процесс не имеет `DiagnosticsState` (singleton живёт только в main-процессе) | `lastInitSnapshot()` возвращает `null` в secondary-ctor `AndroidDeviceInfoProvider`. `DeviceInfoCollector.buildHeader` рендерит «пока не было» в обоих процессах при null. AC-H4 ловит этот контракт. |
| `AtomicReference.updateAndGet` стоит дороже чем `@Volatile var` на init-pathе (2 раза за сессию) | Стоимость ≈ 100 ns CAS-loop, зато гарантирует atomic outcome-update. Аргумент инверсный: `@Volatile var` без CAS даёт lost update, если когда-нибудь добавится второй writer. Для 2-3 init'ов за сессию overhead в наносекундах нерелевантен. |

## User-Spec Deviations

- **Decision 12 — `Outcome.InProgress` UI rendering, 4-й вариант рендера** (расширение AC-D6): user-spec AC-D6 перечисляет три рендера; tech-spec добавляет четвёртый — рендер `InProgress` snapshot'а с полями `freeRamBytes`/`atEpochMs`/`modelName` под маркером «инициализация», без outcome-маркера. Расширение мотивировано двумя дополнительными сигналами для юзера: «сколько RAM было на момент старта прогрева» и «прогрета ли модель сейчас». **Marker:** [APPROVED 2026-04-28] (явное одобрение юзером в Phase-3.5-TechSpec session).

User-spec в § «Технические решения» уже зафиксировал все ключевые архитектурные выборы (использование существующего `Model.minDeviceMemoryInGb`, interface seam через `InitDiagnostics`, гейтинг на этапе Download, `MemoryInfo.totalMem` vs `availMem`, отказ от breakpad, отказ от логирования срабатывания гейта). Tech-spec их разворачивает в implementation detail, не меняя направления.

Code-research draft (где-то между его написанием и финализацией user-spec) использовал имя `minRamBytes: Long` — user-spec корректировал в § «Технические решения» строка 134, tech-spec следует user-spec.

## Acceptance Criteria

Технические критерии (дополняют пользовательские из user-spec):

- [x] `./gradlew :core-runtime:test :app:test` — все юнит-тесты зелёные.
- [x] `./gradlew :app:assembleDebug` — APK собран, ноль git-related warning'ов в выводе.
- [x] `./gradlew :app:lintDebug` — ноль новых lint warning'ов; existing baselined warnings не сдвинуты.
- [x] Grep `:core-runtime` не ссылается на `:app`-пакеты: `grep -rEn "app\.sanctum\.machina\.diagnostics" core-runtime/src/main` → ноль матчей. Interface seam соблюдён.
- [x] Grep `ErrorLog.ALLOWED_COMPONENTS` — список из 14 компонентов из `patterns.md` без изменений.
- [x] Grep `:core-runtime` имеет ноль Compose / Activity / ViewModel импортов: `grep -rEn "androidx.compose|androidx.activity" core-runtime/src/main` → ноль (TAC-7).
- [x] `app/build.gradle.kts` — config-cache compatibility: `./gradlew :app:assembleDebug --configuration-cache` отрабатывает без warning'ов про сериализацию.
- [x] `BuildConfig.VERSION_NAME` соответствует `git describe --tags --always --dirty=-dev` для текущего HEAD после `:app:assembleDebug` (проверка через `aapt dump badging`).
- [x] `minDeviceMemoryInGb` присутствует у каждой модели в `core-runtime/src/main/assets/model_allowlist.json`. Парсер-тест с null/missing значением проходит. Range-check (1..64) отвергает out-of-range значения.
- [x] `DiagnosticsModule.kt` зарегистрирован как `@InstallIn(SingletonComponent::class)`; `@Binds InitDiagnostics: DiagnosticsState`.
- [x] `formatRamShortage` pure-функция покрыта fixture-table тестом (минимум 4 кейса: точное равенство порогу, edge-case под 4 ГБ, реальные значения 5.3/11.5/10.7).
- [x] `RecordingInitDiagnostics` test-fake существует в `:core-runtime/src/test/`, используется хотя бы одним тестом `DefaultModelRegistryTest` с positive-assertion'ом на call-order.
- [x] `DiagnosticsViewModel` тест покрывает 4-й рендер для `Outcome.InProgress` (AC расширения user-spec'а Decision 12): строка содержит маркер «инициализация», `freeRamBytes`, `atEpochMs`, `modelName` — без «ok»/«ошибка».

## Implementation Tasks

<!-- Phase 3.5 wave plan. Wave 1 — независимые слайсы. Wave 2 — UI/integration, зависят от Wave 1.
     Verify-user намеренно отсутствует на промежуточных задачах (см. memory: «Verify UI chain
     before device smoke» — отложено до Pre-deploy QA). -->

### Wave 1 (независимые)

#### Task 1: Allowlist mapping + recalibration + parser rejection
- **Description:** Замаппить `Model.minDeviceMemoryInGb` в `AllowedModel.toModel()`, добавить в `parse()` `require`-проверки (non-null + range 1..64), вынести логирование отклонения в suspend-функцию `load()` с компонентом `"download"`. Рекалибровать `model_allowlist.json` (E2B 8→4, E4B 12→6) и фикстуру.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/ModelAllowlist.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/AllowlistLoader.kt`, `core-runtime/src/main/assets/model_allowlist.json`, `core-runtime/src/test/resources/model_allowlist_fixture.json`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/AllowlistLoaderTest.kt`
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Model.kt`, `core-runtime/src/main/kotlin/app/sanctum/machina/core/log/ErrorLog.kt`, `.claude/skills/project-knowledge/references/patterns.md` (whitelist `download`)

#### Task 2: gradle git-describe versionName via convention plugin
- **Description:** Реализовать чтение `git describe --tags --always --dirty=-dev` через convention-плагин в `build-logic/` (предпочтительно) или `buildSrc/`, разделив exec и парсинг: `providers.exec` в плагине, pure-функция `gitVersionParse(stdout, exitCode): String?` тестируема JUnit'ом. В `app/build.gradle.kts` использовать `versionName = ... ?: "v0.3.5-diagnostics-fallback"`. Решение по точному размещению pure-функции (внутри плагина vs `:app/src/main/`) — за solver'ом.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Verify-smoke:** `./gradlew :app:assembleDebug --configuration-cache && aapt dump badging app/build/outputs/apk/debug/app-debug.apk | grep versionName` → значение совпадает с `git describe --tags --always --dirty=-dev`, ноль warning'ов про config-cache serialization.
- **Files to modify:** `app/build.gradle.kts`, `settings.gradle.kts` (если регистрация convention-плагина через `pluginManagement`), новые: `build-logic/build.gradle.kts`, `build-logic/src/main/kotlin/.../GitVersionPlugin.kt`, `build-logic/src/main/kotlin/.../GitVersionParse.kt` (или эквивалентное расположение), тест к pure-функции
- **Files to read:** `gradle/libs.versions.toml`, `app/build.gradle.kts` (текущий defaultConfig), `app/src/main/kotlin/app/sanctum/machina/logexport/LogcatReader.kt` (паттерн pure-функция + injected runner — для inspiration по seam'у)

#### Task 3: LogcatReader argv `*:E` → `*:W`
- **Description:** Поменять элемент 5 в `argv` с `"*:E"` на `"*:W"`. Обновить пин-тест синхронно.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, test-reviewer
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/logexport/LogcatReader.kt`, `app/src/test/kotlin/app/sanctum/machina/logexport/LogcatReaderTest.kt`
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/logexport/LogExportManager.kt` (truncation cap, чтобы понимать риск AC-L3)

#### Task 4: InitDiagnostics seam + DiagnosticsState + Hilt module
- **Description:** Создать interface `InitDiagnostics` в `:core-runtime/registry/`, реализацию `DiagnosticsState` (`@Singleton`, `AtomicReference<InitSnapshot?>`) в `:app/diagnostics/`, Hilt-binding в новом `app/diagnostics/di/DiagnosticsModule.kt`. `NoOpInitDiagnostics` в `:core-runtime/src/test/`. Никаких consumer'ов в этой задаче — чистый seam.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** новые: `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/InitDiagnostics.kt`, `app/src/main/kotlin/app/sanctum/machina/diagnostics/InitSnapshot.kt`, `app/src/main/kotlin/app/sanctum/machina/diagnostics/DiagnosticsState.kt`, `app/src/main/kotlin/app/sanctum/machina/diagnostics/di/DiagnosticsModule.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/NoOpInitDiagnostics.kt`, `app/src/test/kotlin/app/sanctum/machina/diagnostics/DiagnosticsStateTest.kt`
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/engine/AppCorruptionState.kt` (паттерн singleton'а), `app/src/main/kotlin/app/sanctum/machina/logexport/LogExportModule.kt` (паттерн Hilt @Binds)

### Wave 2 (зависит от Wave 1)

<!-- Wave 2 — три параллельные задачи, не пересекающиеся по файлам.
     Task 5 правит strings.xml (один новый ключ — model_gate_secondary).
     Task 6, Task 7 не трогают strings.xml.
     UI-screen изменения (DiagnosticsScreen, drawer, AboutScreen + остальные strings) — отдельный Wave 3. -->

#### Task 5: Pre-flight gate logic + Model Manager UI
- **Description:** Добавить `gateAllowsDownload(...)` + `formatRamShortage(...)` pure-функции + `GateDecision` + `ModelRowState`. Расширить `ModelManagerViewModel` инъекцией `DeviceInfoProvider`, экспонировать per-row `GateDecision`. В `ModelStatusSection` ветка `NOT_DOWNLOADED` рендерит disabled-кнопку + secondary-text от `formatRamShortage` при `!gate.allowed`. Зависит от Task 1.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerViewModel.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerScreen.kt`, `app/src/main/res/values/strings.xml` (один ключ `model_gate_secondary`), `app/src/test/kotlin/app/sanctum/machina/ui/modelmanager/ModelManagerViewModelTest.kt`, новые: `app/src/test/kotlin/app/sanctum/machina/ui/modelmanager/GateAllowsDownloadTest.kt`, `app/src/test/kotlin/app/sanctum/machina/ui/modelmanager/FormatRamShortageTest.kt`
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/logexport/DeviceInfoCollector.kt` (`DeviceInfoProvider` interface), `core-runtime/src/main/kotlin/app/sanctum/machina/core/data/Model.kt`

#### Task 6: Wire InitDiagnostics into DefaultModelRegistry.initialize
- **Description:** Инжектировать `InitDiagnostics` в `DefaultModelRegistry`. В `initialize(modelName)` под `lifecycleMutex` вызвать `onInitStart` после `releaseEngine` и до `try`-блока (`availMem` через инжектированный `@ApplicationContext`); `onInitEnd(true)` на успешных GPU/CPU arm'ах; `onInitEnd(false)` на failure-arm'е после `errorLog.e`. Создать `RecordingInitDiagnostics` test-fake для positive-assertion'ов в тестах registry. Зависит от Task 4.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistry.kt`, `core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/DefaultModelRegistryTest.kt`, новый `core-runtime/src/test/kotlin/app/sanctum/machina/core/registry/RecordingInitDiagnostics.kt`
- **Files to read:** `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/InitDiagnostics.kt` (Task 4), `app/src/main/kotlin/app/sanctum/machina/engine/WarmupCoordinator.kt` (call-site context)

#### Task 7: DeviceInfoCollector header expansion
- **Description:** Расширить `DeviceInfoProvider` шестью методами (`thresholdMemoryBytes`, `isLowMemory`, `processJavaHeapBytes`, `processNativeHeapBytes`, `processTotalPssBytes`, `lastInitSnapshot`). `AndroidDeviceInfoProvider` Hilt-primary заполняет все 6 (последний — через `DiagnosticsState`); secondary `:crash`-ctor возвращает `null` для `lastInitSnapshot`. `buildHeader` дополняет `memory:` (`threshold=`, `lowMemory=`) и добавляет строки `process:`, `last init:` между `memory:` и `active model:`. AC-H4 `:crash`-degradation покрывается тестом. Зависит от Task 4.
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** `app/src/main/kotlin/app/sanctum/machina/logexport/DeviceInfoCollector.kt`, `app/src/test/kotlin/app/sanctum/machina/logexport/DeviceInfoCollectorTest.kt`, `app/src/test/kotlin/app/sanctum/machina/logexport/LogExportManagerTest.kt` (substring-проверки)
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/diagnostics/DiagnosticsState.kt` (Task 4), `core-runtime/src/main/kotlin/app/sanctum/machina/core/registry/InitDiagnostics.kt`

### Wave 3 (зависит от Wave 2 — новый экран + drawer + AboutScreen refactor)

<!-- Изолированная волна для UI-изменений с большим scope:
     Task 8 единственная правит drawer / DiagnosticsScreen / AboutScreen / strings (несколько ключей).
     Невозможно безопасно совместить с Task 5 в одной волне из-за общего strings.xml. -->

#### Task 8: DiagnosticsScreen + ViewModel + Drawer pin + AboutScreen refactor
- **Description:** Создать `DiagnosticsScreen` (TopAppBar, две секции «RAM»/«Логи») и `DiagnosticsViewModel` (snapshot-mapping + `viewModelScope` 1-секундный free-RAM тик с авто-cancel в `onCleared`). Зарегистрировать `composable("diagnostics")` после `about` в `SanctumApp.kt`. Добавить третий `NavigationDrawerItem` «Диагностика» в `DrawerFooter` (`DrawerContent.kt:190-224`). Из `AboutScreen` удалить секцию «Диагностика» (`AboutScreen.kt:165-178`) + SAF-launcher state (`AboutScreen.kt:116, 120-134`); сохранить `SafeMarkdown`, `AboutFooter`, `tapCounter`, 7-tap-обвязку, `AlertDialog`. Зависит от Task 4 (DiagnosticsState).
- **Skill:** code-writing
- **Reviewers:** code-reviewer, security-auditor, test-reviewer
- **Files to modify:** новые: `app/src/main/kotlin/app/sanctum/machina/ui/diagnostics/DiagnosticsScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/diagnostics/DiagnosticsViewModel.kt`, `app/src/test/kotlin/app/sanctum/machina/ui/diagnostics/DiagnosticsViewModelTest.kt`. Изменения: `app/src/main/kotlin/app/sanctum/machina/ui/SanctumApp.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/drawer/DrawerContent.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/about/AboutScreen.kt`, `app/src/main/kotlin/app/sanctum/machina/ui/about/AboutViewModel.kt`, `app/src/main/res/values/strings.xml` (`drawer_nav_diagnostics`, `diagnostics_*`)
- **Files to read:** `app/src/main/kotlin/app/sanctum/machina/ui/about/AboutScreen.kt` (SAF-launcher паттерн, lines 116-178), `app/src/main/kotlin/app/sanctum/machina/diagnostics/DiagnosticsState.kt` (Task 4), `app/src/main/kotlin/app/sanctum/machina/ui/drawer/DrawerContent.kt` (`DrawerContent` 78-188; `DrawerFooter` 190-224 — куда вставляется третий пин)

### Audit Wave

<!-- Параллельно: 3 аудитора читают весь код фичи и пишут отчёты. -->

#### Task 9: Code Audit
- **Description:** Полный code-quality аудит фичи. Прочитать все файлы, изменённые/созданные в Tasks 1-8 (см. decisions.md + tech-spec «Files to modify»). Проверить кросс-компонентные проблемы: дублирование инициализации ресурсов, соответствие Shared Resources таблице из Architecture, архитектурную целостность interface seam'а `InitDiagnostics`, отсутствие модульных нарушений (`grep -rEn "androidx.compose|androidx.activity" core-runtime/src/main`), соблюдение `ErrorLog.ALLOWED_COMPONENTS` whitelist'а. Написать audit report.
- **Skill:** code-reviewing
- **Reviewers:** none

#### Task 10: Security Audit
- **Description:** Полный security-аудит фичи. Прочитать все файлы из Tasks 1-8. Проверить OWASP Top 10 в контексте мобильного приложения: input validation в парсере allowlist (regex'и `MODEL_ID_REGEX` etc.), отсутствие path traversal через `model_allowlist.json`, отсутствие command injection в `gitVersionName()` (argv list, не shell), отсутствие сенситивных данных в экспортируемом `.txt` (PII, токены, пути с username), thread-safety записи `DiagnosticsState`, корректность `:crash`-degradation. Написать audit report.
- **Skill:** security-auditor
- **Reviewers:** none

#### Task 11: Test Audit
- **Description:** Полный test-quality аудит фичи. Прочитать все тестовые файлы из Tasks 1-8. Проверить: покрытие AC-T1..AC-T9 из user-spec, осмысленность assert'ов (не trivial-тесты), соблюдение test-pyramid (`architecture.md § Testing` — pure-JVM приоритет, Robolectric только где нужен Bitmap/Compose-resources), отсутствие `Mockito`/`MockK` (hand-rolled fakes по конвенции), test-isolation (каждый тест self-contained). Написать audit report.
- **Skill:** test-master
- **Reviewers:** none

### Final Wave

#### Task 12: Pre-deploy QA
- **Description:** Acceptance testing. Прогнать `./gradlew :core-runtime:test :app:test :app:lintDebug :app:assembleDebug`. Проверить все AC из user-spec (G1-G7, V1-V4, L1-L3, H1-H4, D1-D10, A1-A3, T1-T9). Honor 200 manual smoke по полному чеклисту user-spec § «Как проверить» → «Пользователь проверяет» (пины drawer'а, экран диагностики, формат шапки `.txt`, 7-tap dev-жест, локальная проверка гейта через подмену порога в JSON). Проверить отсутствие регрессий в существующих unit-тестах. Собрать AVP-отчёт по AC-L3 (cap логката не вытесняет ERROR на Honor 200). Внешние тестеры (S20 FE 4G / SM-G780F / realme RMX3085) — установить APK, прислать скриншот disabled-«Скачать» с текстом «Недостаточно RAM (X ГБ устройство, нужно Y ГБ)», подтверждает SC-1.
- **Skill:** pre-deploy-qa
- **Reviewers:** none
