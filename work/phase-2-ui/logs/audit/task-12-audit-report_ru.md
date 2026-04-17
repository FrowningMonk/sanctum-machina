# Аудит кода Phase 2 — Task 12

**Аудитор:** code-reviewer
**Дата:** 2026-04-18
**Область:** Phase 2 Tasks 1–11, все файлы Kotlin/Compose/XML
**Phase-2 HEAD:** 9b435f2
**Phase-1 baseline:** 31bd1d0

## Executive Summary

Кодовая база Phase 2 чисто проходит по всем семи осям аудита Task 12. Границы модулей
(TAC-7, TAC-8), lifecycle-гигиена для CameraX и AudioRecord, безопасность схем ссылок
в markdown, владение общими ресурсами и перформансный порог автоскролла — всё на месте
и подтверждено явными тестами или grep-проверками. Две неблокирующих находки
(отклонение ключа персистентности `systemPromptDefault`, уже зафиксированное в
`decisions.md`, и мелкое замечание по типобезопасности в `classifyApplyLevel`) — это
задел для Phase 3. Блокирующих находок нет; вердикт — **одобрено** для закрытия Phase 2.

Находки по тяжести: **critical 0 / high 0 / medium 0 / low 2**.

## Находки по осям

### 1. Границы модулей (TAC-7, TAC-8)

**Статус:** пройдено.

**Обоснование:** Все четыре smoke-grep'а возвращают пусто для `:core-runtime` и `:core-settings`:

- `import androidx.compose` — 0 совпадений в обоих модулях.
- `import android.app.Activity` — 0 совпадений в обоих модулях.
- `import androidx.activity` — 0 совпадений в обоих модулях.
- `class … : ViewModel(` / `extends Activity` — 0 совпадений в обоих модулях.

`core-runtime/build.gradle.kts` и `core-settings/build.gradle.kts` не содержат
зависимостей `androidx.compose` / `androidx.activity` / `androidx.lifecycle.viewmodel`.
Manifest `core-settings` в `core-settings\src\main\AndroidManifest.xml:1` — это
самозакрывающийся `<manifest>` без атрибутов `<application>`, что сохраняет
`allowBackup`/`dataExtractionRules` из `:app` при merge манифестов
(R7, patterns.md § library-module hygiene).

Три строковых попадания `ViewModel` в `core-runtime` находятся только в doc-комментариях
(`LlmChatModelHelper.kt:278`, `ModelRegistry.kt:11,77`), а не в импортах.

**Находки:** нет.

### 2. Lifecycle-гигиена

**Статус:** пройдено.

**CameraX (`app\src\main\kotlin\app\sanctum\machina\ui\chat\CameraBottomSheet.kt`):**

- `bindToLifecycle` в `CameraBottomSheet.kt:205`, предваряемый защитным `unbindAll` в
  `:204` внутри `LaunchedEffect(lifecycleOwner)`.
- `DisposableEffect(Unit) { onDispose { cameraProvider?.unbindAll(); executor.shutdown() } }`
  в `:219–224` — гарантированное освобождение при сбросе композиции.
- `LaunchedEffect` завязан на ключ `lifecycleOwner` (`:201`), поэтому смена owner'а,
  инициированная NavHost'ом, не оставит привязанные use case'ы сиротами (R8).
- Callback захвата уходит через `scope.launch { … }` (`:272, 276, 280`); `scope` — это
  `rememberCoroutineScope` самого sheet'а: swipe-dismiss во время захвата отменит его,
  так что `onCapture`/`onError`, доставленные после отмены, превращаются в тихие no-op
  (фантомные вложения исключены).
- Ошибки декодирования при захвате закрывают `ImageProxy` (`:268, 275`) до повторной
  отправки ошибки — предотвращается утечка нативного буфера.

**AudioRecord (`app\src\main\kotlin\app\sanctum\machina\ui\chat\AudioRecorderBottomSheet.kt`):**

- Конструктор в `:330`; проверка `state` в `:337` с немедленным `release()` в `:338`
  при провале инициализации.
- `DisposableEffect(Unit) { onDispose { job?.cancel(); recorder?.stop(); recorder.release() } }`
  в `:230–241`.
- `LifecycleEventEffect(Lifecycle.Event.ON_PAUSE)` в `:243` — переводит idempotency-латч
  `completed.set(true)` ДО запуска анимации закрытия, а затем синхронно вызывает
  `recorder.stop()`. Регрессия AC-19 закрыта: отложенный 30-секундный `finish()` внутри
  IO-цикла становится no-op, а буфер действительно сбрасывается за ~300 мс анимации
  скрытия sheet'а.
- Терминальный путь `finish()` в `:350–365` закрыт через `AtomicBoolean.compareAndSet` —
  гарантия единичного сохранения при гонках Stop-кнопки / auto-stop / onPause.
- Поля `RecorderHandle` помечены `@Volatile` (`:315, 317`) — безопасное межпотоковое
  чтение между Main-callback'ами и IO-корутиной записи.
- Путь прав защищает конструктор от непривилегированного вызова: `buildRecorder`
  вызывается только после `hasPermission == true` (`:155`); `@SuppressLint("MissingPermission")`
  в `:328` оправдан.

Сводка grep по точкам вызова нативных ресурсов (совпадения — только в двух sheet'ах и
в writer'е заголовка в `MediaUtils.kt`; сиротских конструкторов в других местах нет):

```
CameraBottomSheet.kt:204  provider.unbindAll()
CameraBottomSheet.kt:205  provider.bindToLifecycle(
CameraBottomSheet.kt:221  cameraProvider?.unbindAll()
AudioRecorderBottomSheet.kt:237  r.release()
AudioRecorderBottomSheet.kt:330  val recorder = AudioRecord(
AudioRecorderBottomSheet.kt:338  recorder.release()
```

**Находки:** нет.

### 3. Производительность автоскролла

**Статус:** пройдено.

**Обоснование:** `ChatScreen.MessageList` в `ChatScreen.kt:348–383`:

```kotlin
val lastTextLen = messages.lastOrNull()?.text?.length ?: 0
val lastThinkingLen = messages.lastOrNull()?.thinkingText?.length ?: 0
LaunchedEffect(messages.size, lastTextLen, lastThinkingLen) {
    if (messages.isNotEmpty()) {
        listState.animateScrollToItem(
            index = messages.lastIndex,
            scrollOffset = Int.MAX_VALUE / 2,
        )
    }
}
```

- Один консолидированный `LaunchedEffect` с ключами `(size, lastTextLen, lastThinkingLen)` —
  соответствует post-smoke плану из decisions.md Task 11.
- Ключи читают поля `Message` напрямую — без `snapshotFlow`, `derivedStateOf` и
  дополнительных snapshot-чтений `listState` во время композиции.
- `scrollOffset = Int.MAX_VALUE / 2` прижимает низ последнего элемента, устраняя
  регрессию с обрезанием, которую давал простой `animateScrollToItem(lastIndex)` на
  длинных стримах (commit 944b3b3).
- Повторный скролл срабатывает по токенам — так и задумано; хелпер
  `LazyListState.animateScrollToItem` внутренне дебаунсит против уже идущих анимаций
  скролла, поэтому быстрые смены ключей не накапливают конкурирующие анимации.
- Grep: `animateScrollToItem` встречается в `:app` единственный раз — дубликатов
  поверхностей автоскролла нет.

**Находки:** нет.

### 4. Консистентность flow запроса прав

**Статус:** пройдено.

Оба sheet'а следуют одному и тому же permission-паттерну:

| Шаг | `CameraBottomSheet.kt` | `AudioRecorderBottomSheet.kt` |
|------|------------------------|-------------------------------|
| Seed через `ContextCompat.checkSelfPermission` | `:110–115` | `:114–119` |
| `rememberLauncherForActivityResult(RequestPermission())` | `:127–139` | `:131–143` |
| Проверка permanent через `shouldShowRequestPermissionRationale` | `:134–136` | `:138–140` |
| Чистый helper для permanent-отказа | `isCameraDenialPermanent` в `:339` | `isAudioDenialPermanent` в `:386` |
| Отказ → `onPermissionDenied(permanent)` + закрытие | `:136–138` | `:140–142` |

`ChatScreen.ReadyContent` отрисовывает идентичный контракт snackbar для обоих:

- Не-permanent отказ → короткий snackbar с доменно-специфичным сообщением
  (`permission_camera_denied` / `permission_audio_denied`).
- Permanent отказ → длинный snackbar с action-label
  `R.string.permission_open_settings`, который по `ActionPerformed` вызывает общий
  deeplink `context.openAppSettings()`.

Реализация deeplink'а в `ChatScreen.kt:340–346` обёрнута в `runCatching`, чтобы
проглотить `ActivityNotFoundException` на зажатых OEM'ах — оба flow используют этот
единый helper, так что поведение не может разойтись.

**Находки:** нет.

### 5. Безопасность markdown

**Статус:** пройдено.

**Обоснование:** Grep по `Markdown(` и `RichText {` даёт четыре попадания в `:app`:

```
AboutScreen.kt:75              SafeMarkdown(text = markdown)
ThinkingBlock.kt:112           SafeMarkdown(
MessageBubble.kt:91            SafeMarkdown(text = display)
SafeMarkdown.kt:46             fun SafeMarkdown(
SafeMarkdown.kt:82             Markdown(content = text)
```

Единственный call-site сырого `com.halilibo.richtext.commonmark.Markdown` / `RichText`
находится внутри самого `SafeMarkdown` в `SafeMarkdown.kt:67–83`. `SafeMarkdown`
оборачивает дерево контента в `CompositionLocalProvider(LocalUriHandler provides SafeUriHandler(context))`
в `:58`, поэтому каждая отрисованная ссылка проходит через whitelist схем в `:30–42`:

- `ALLOWED_SCHEMES = setOf("http", "https")` — нижний регистр согласно RFC 3986 §3.1.
- Схемы не из whitelist возвращают управление тихо, без `startActivity`.
- Некорректные URI ловятся через `runCatching { Uri.parse(uri) }` в `:34`.
- Сам `context.startActivity` обёрнут в `runCatching` в `:41` — случайный
  `ActivityNotFoundException` от вырожденных intent'ов не может уронить чат.

Пользовательский текст в `MessageBubble.kt:85–90` рендерится через обычный `Text`
(без парсинга markdown), что исключает клиентскую инъекцию markdown в собственные
пузыри пользователя.

`SafeUriHandlerTest` покрывает 14 кейсов (разрешённые + заблокированные схемы +
пустые/некорректные), TAC-13 зелёный.

**Находки:** нет.

### 6. Дублирующая инициализация между компонентами

**Статус:** пройдено.

**Движок / точки вызова `ModelRegistry.initialize`** (grep):

- `ChatViewModel.kt:102` — первичная загрузка внутри `init { viewModelScope.launch { … } }`.
- `ChatViewModel.kt:398` — heavy-reinit внутри `applyHeavySetting` после `registry.cleanup`.

Оба сайта бьют в один и тот же singleton `DefaultModelRegistry`; `:170` защищает работу
общим `lifecycleMutex.withLock { … }` и вызывает `releaseEngine(model)` перед
`awaitInitialize`, чтобы идемпотентно снести любой предыдущий нативный экземпляр.
Вторичных вызывающих (фрагментов, дополнительных ViewModel, обращений
`ModelManagerViewModel`) нет — grep подтверждает.

**DataStore singleton:**

- `CoreSettingsModule.provideAppSettingsDataStore` в `CoreSettingsModule.kt:32–43` —
  `@Provides @Singleton`, путь `context.filesDir/datastore/app_settings.pb`.
- `DefaultAppSettingsRepository` в `DefaultAppSettingsRepository.kt:17–20` — это
  `@Singleton` с инжектированным `DataStore<AppSettings>`, единственный потребитель
  DataStore-handle; совпадает с таблицей общих ресурсов из техспека.
- `DefaultAppSettingsRepository` ловит `IOException` на observe/save/reset (R13) и
  логирует через whitelist-компонент `"settings-io"` — корректно.

**Hilt / `ErrorLog`:** `@Singleton` в `ErrorLog.kt:65`, альтернативных call-site'ов
конструктора нет.

**Находки:** нет.

### 7. Соблюдение договорённостей по общим ресурсам

**Статус:** пройдено.

Таблица § Shared resources из техспека против фактического кода:

| Ресурс | Владелец по техспеку | Потребители по техспеку | Фактический владелец | Фактические потребители | Вердикт |
|----------|-----------------|---------------------|--------------|------------------|---------|
| `DataStore<AppSettings>` | `CoreSettingsModule` (Hilt singleton) | `DefaultAppSettingsRepository` | `CoreSettingsModule.kt:32` | `DefaultAppSettingsRepository.kt:17` | совпадает |
| `AppSettingsRepository` | `CoreSettingsModule` | `ChatViewModel`, `InferenceSettingsBottomSheet` | `@Binds` в `CoreSettingsModule.kt:26` | `ChatViewModel.kt:62`, `InferenceSettingsBottomSheet.kt:69` | совпадает |
| litertlm движок | `DefaultModelRegistry` | `ChatViewModel` | `DefaultModelRegistry.kt:74` | `ChatViewModel.kt:57` | совпадает (single-active через `lifecycleMutex`) |
| CameraX `ProcessCameraProvider` | `CameraBottomSheet` | только sheet | `CameraBottomSheet.kt:203` | только sheet, bind/unbind в `DisposableEffect` | совпадает |
| `AudioRecord` | `AudioRecorderBottomSheet` | только sheet | `AudioRecorderBottomSheet.kt:330` | только sheet, release в `onDispose` + `ON_PAUSE` | совпадает |
| `ErrorLog` (residual из Phase 1) | `:core-runtime` (Hilt `@Singleton`) | VM-ы, репозиторий, registry | `ErrorLog.kt:65` | `ChatViewModel`, `DefaultAppSettingsRepository`, `DefaultModelRegistry` | совпадает, whitelist `ALLOWED_COMPONENTS` в `:32–43` расширен по D27 |

Все call-site'ы `errorLog.e(...)` используют whitelist-компоненты
(`"download"`, `"inference-init"`, `"inference"`, `"inference-cleanup"`, `"settings-io"`,
`"camera"`, `"audio"`, `"attachment-decode"`).

**Находки:** нет.

## Блокирующие находки

Нет.

## Неблокирующие наблюдения

### NB-1. Отклонение ключа персистентности от техспека D3 (низкий)

**Файлы:** `app\src\main\kotlin\app\sanctum\machina\ui\chat\ChatViewModel.kt:262, 280, 316, 338, 355, 393, 587`.

**Наблюдение:** техспек D3 требует `map<string, PerModelSettings>` с ключом по
`Model.modelId`; `DefaultAppSettingsRepository.savePerModelSettings(modelId, …)`
принимает этот контракт дословно. Call-site в `ChatViewModel` передаёт в качестве ключа
`modelName` (`savePerModelSettings(modelName, settings)` и т. п.). В рамках Phase 2 эти
два варианта функционально взаимозаменяемы (имена в allowlist стабильны), однако
переименование модели в allowlist оставит сохранённые override-ы сиротами.

**Статус:** уже зафиксировано в `decisions.md` Task 11 в разделе Deviations —
*"Per-model settings keyed by `Model.name`, not `Model.modelId`. Phase-2 allowlist names
are stable; Phase 3 can migrate when Room schema lands."* Не регрессия, отложено
намеренно. Вынесено сюда, чтобы owner Phase 3 подхватил это вместе с миграцией на Room.

**Рекомендация:** в Phase 3 пробросить `Model.modelId` через `ModelRegistry` (или через
`ModelEntry`) и переключить VM на передачу `modelId` — правка в одну строку в call-site
сразу, как только исходное поле станет доступным.

### NB-2. `classifyApplyLevel` сравнивает значения proto-типа и Kotlin-типа (низкий)

**Файл:** `app\src\main\kotlin\app\sanctum\machina\ui\chat\ChatViewModel.kt:546–566`.

**Наблюдение:** `classifyApplyLevel(current, target)` проверяет равенство значений,
взятых из `model.configValues` (Phase-1 типизирован как `Any`, наполняется из
`LabelConfig` `defaultValue` / override'ов-строк из allowlist), против значений,
которые возвращает `EffectiveConfig.merge`, — а она явно выдаёт Kotlin
`Int` / `Float` / `Boolean` / `String` по каждому proto-полю. Когда override'а нет,
сторона `current` может тянуть `Int`-литерал из allowlist через `createLlmChatConfigs`
— это нормально; но если какое-то поле будет записано как boxed-число через
неканонический путь, то `Int(40) != java.lang.Integer(40)` может ложно сигнализировать
"heavy changed" и прогонять через `HeavyChangeDialog` на no-op изменении. Сегодня все
writer'ы идут через `EffectiveConfig.merge`, так что сравнение типобезопасно;
code-reviewer Round-1 пометил это как "harmless, convertValueToTargetType
нормализует при чтении" (см. decisions.md Task 11 M4).

**Статус:** латентный. Текущими writer'ами не активируется.

**Рекомендация:** при Phase 3-рефакторинге, затрагивающем `configValues`, прогонять
сравнение через те же type-narrowing хелперы (`getIntConfigValue` / `getFloatConfigValue`),
которые использует `LlmChatModelHelper.initialize`, вместо сырой равенства
`Map<String, Any>`.

## Результаты smoke-grep'ов

### §Verification Steps — Smoke 1 (границы модулей)

`:core-runtime` и `:core-settings` — `import androidx.compose` / `import android.app.Activity` / `import androidx.activity` / объявления класса `ViewModel`:

```
(все четыре grep'а возвращают: No matches found)
```

### §Verification Steps — Smoke 2 (lifecycle AudioRecord)

```
AudioRecorderBottomSheet.kt:330  val recorder = AudioRecord(
AudioRecorderBottomSheet.kt:338  recorder.release()      # init-failure branch
AudioRecorderBottomSheet.kt:237  r.release()             # DisposableEffect.onDispose
```

Один конструктор, два пути `release()` — вместе покрывают каждый выход (провал
инициализации, штатное завершение, `ON_PAUSE`, отмена — `DisposableEffect.onDispose`
служит гарантированным catch-all'ом).

### §Verification Steps — Smoke 3 (CameraX bindToLifecycle)

```
CameraBottomSheet.kt:204  provider.unbindAll()
CameraBottomSheet.kt:205  provider.bindToLifecycle(
CameraBottomSheet.kt:221  cameraProvider?.unbindAll()   # DisposableEffect.onDispose
```

Один `bindToLifecycle`, парный `unbindAll` непосредственно перед bind'ом (защитно) и
безусловно в `onDispose` — lifecycle сбалансирован.
