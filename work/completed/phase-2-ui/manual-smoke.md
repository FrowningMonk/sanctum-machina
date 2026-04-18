# Manual Smoke — Phase 2 UI (Sanctum Machina)

> Чеклист ручной проверки на реальном устройстве Honor 200 (Android 14+).
> APK: `app/build/outputs/apk/debug/app-debug.apk`.
> Модели: **Gemma-4-E2B-it** и **Gemma-4-E4B-it** — оба прохода обязательны, где явно не указано иное.

Формат: пройти пункт → отметить **pass** или **fail**. При `fail` — описать что пошло не так.

Финальный гейт **AC-22** закрывается пользователем только после прохождения всех US-1..US-7 и deferred AC.

---

## 0. Пре-флайт

- [ ] Deinstall предыдущей версии Sanctum Machina на устройстве (если была).
- [ ] Установить свежий APK: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (или ручная передача через `adb install`).
- [ ] Открыть Logcat `adb logcat | grep -E "Sanctum|inference|camera|audio|settings-io|attachment-decode"` в отдельном окне для наблюдения.

---

## 1. US-1. Первый запуск и подготовка модели (AC-2, AC-5, AC-6, AC-21)

- [ ] На лаунчере Honor 200 ярлык приложения подписан **«Sanctum Machina»** (AC-2, также проверено автоматически через aapt — см. qa-report.json).
- [ ] Запуск: открывается `ModelManagerScreen` со списком Gemma-4-E2B-it и Gemma-4-E4B-it.
- [ ] В TopAppBar видна иконка «О приложении» (Info, Material outlined). Тап → открывается `AboutScreen` (AC-5).
- [ ] `AboutScreen` скроллится; текст из `assets/about.md` виден; в футере — версия (`BuildConfig.VERSION_NAME`) + атрибуция к Google AI Edge Gallery / Gemma / LiteRT-LM (AC-6).
- [ ] Back → возврат в `ModelManagerScreen`.
- [ ] Скачать Gemma-4-E2B-it (если не скачана) → прогресс виден → **«Загрузить»** → индикатор «Загрузка модели…» → открывается `ChatScreen`.
- [ ] В `ChatScreen` TopAppBar три action'а: **Settings (⚙ Tune) / Reset (↻) / Back**.

---

## 2. US-4 → проверка настроек (AC-4, AC-18, AC-21)

### Лёгкие настройки (D15 — light)

- [ ] Тап Settings → открывается bottom sheet с семью полями: `maxTokens`, `topK`, `topP`, `temperature`, `enableThinking`, `accelerator`, `systemPromptDefault`.
- [ ] Изменить `temperature` с 1.0 на 0.2 → **«Применить»** → sheet закрывается без диалога-предупреждения и без прогресс-индикатора (light).
- [ ] Отправить «Напиши короткое стихотворение» → ответ заметно менее разнообразный / более детерминированный, чем при temperature=1.0.
- [ ] Тап Settings → **«Default»** → overrides удаляются → следующий ответ снова разнообразнее.

### Полу-лёгкие настройки (D15 — semi-light)

- [ ] Settings → изменить `systemPromptDefault` на «Отвечай только в формате списка» → **«Применить»** → snackbar «Настройки применены, контекст чата сброшен»; история чата очищается; engine остаётся инициализированным (нет прогресс-диалога).
- [ ] Отправить «Расскажи три факта про Kotlin» → ответ в формате маркированного списка.
- [ ] Settings → `enableThinking = true` → **«Применить»** → snackbar + история очищается; engine не реинитится.
- [ ] Отправить любой вопрос → в пузыре ассистента появляется collapsible блок «Показать ризонинг» (левая вертикальная линия, приглушённый текст) — авто-раскрыт во время стрима, сворачивается после `done` (AC-14).
- [ ] Settings → `enableThinking = false` → **«Применить»** → thinking-блок в новых ответах отсутствует (AC-18).

### Тяжёлые настройки (D15 — heavy)

- [ ] Settings → изменить `accelerator` GPU → CPU → **«Применить»** → появляется **HeavyChangeDialog** («~5–30 секунд, контекст сбросится», кнопки Применить / Отмена).
- [ ] **Отмена** → dialog закрывается, настройка не применяется, engine не реинитится.
- [ ] Settings → снова `accelerator` GPU → CPU → **«Применить»** → **Подтвердить** → появляется **ReinitProgressDialog** (неотменяемый, CircularProgressIndicator) → через 5–30 сек engine на CPU backend, чат работает.
- [ ] Повторить обратно CPU → GPU для возврата к быстрому backend.

### Conditional buttons (AC-18)

- [ ] В sheet: если модель не поддерживает thinking (`llmSupportThinking=false`) — toggle `enableThinking` **отсутствует**. *Для Gemma-4-*it обеих моделей thinking поддерживается, этот кейс проверяется вручную только на модели без support.*

---

## 3. US-2. Мультимодальный чат с фото (AC-9, AC-10, AC-11, AC-13, AC-15, AC-26)

### Photo Picker (AC-10)

- [ ] В `ChatScreen` input bar: OutlinedTextField + три иконки (камера, галерея, микрофон) + Send (AC-9).
- [ ] Send **disabled** на пустом тексте без вложений.
- [ ] Тап галереи → открывается Android Photo Picker → выбрать 3 фото → над input bar появляется ThumbnailStrip с 3 миниатюрами.
- [ ] Тап ✕ на одной миниатюре → миниатюра удаляется.
- [ ] Снова Photo Picker → выбрать **15** фото → в ленту попадают первые 10, snackbar «Выбрано 10 из 15 — это максимум».
- [ ] Каждая миниатюра ≤ ~1024 px (визуально — без потери читаемости, но не fullsize).

### Камера (AC-11, AC-15)

- [ ] Тап иконки камеры (первый раз) → системный диалог CAMERA permission → **Разрешить**.
- [ ] Открывается full-screen bottom sheet с live preview (CameraX) + кнопка «Снять» + «Закрыть».
- [ ] Тап «Снять» → снимок → миниатюра добавляется над input bar → sheet закрывается.
- [ ] Тап камеры → «Закрыть» (без съёмки) → миниатюра не добавляется.
- [ ] **Edge: отказ permission.** Deinstall → переустановить → тап камеры → **Не разрешать** → snackbar «Разрешите доступ к камере».
- [ ] **Edge: permanent deny.** Снова тап камеры → «Не спрашивать снова» → snackbar «Открыть настройки» → тап → системный screen app-details.

### End-to-end multimodal inference (AC-13)

> Deferred — требует ручной визуальной оценки на Honor 200.

- [ ] С прикреплёнными 3 фото + текстом «Что на фото?» → Send → **стримящийся осмысленный ответ** на **Gemma-4-E2B-it**.
- [ ] Повторить на **Gemma-4-E4B-it** (Back → выбрать вторую модель → chat → 3 фото → вопрос).
- [ ] В пузыре USER-сообщения (история чата) фото отображаются миниатюрами над текстом (AC-26) — 5×2 FlowRow для 10 фото.
- [ ] 10 фото + «Расскажи про каждое» → ответ без UI-freeze (Choreographer не должен печатать skipped frames в Logcat).

### Автоскролл (AC-8)

- [ ] Во время стрима длинного ответа (≥ высоты экрана) лента прокручивается, показывая последние токены — не клипится.
- [ ] После `done` кастомный скролл вверх пользователем не перебивается последующими сообщениями.

---

## 4. US-3. Аудио-ввод (AC-12, AC-15, AC-19, AC-20, AC-13)

- [ ] Тап микрофона (первый раз) → системный диалог RECORD_AUDIO permission → **Разрешить**.
- [ ] Открывается bottom sheet с индикатором записи + таймером 00:00.
- [ ] Таймер отсчитывает до 0:30 → на 30-й секунде автостоп → миниатюра аудио-клипа (с длительностью) над input bar.
- [ ] Тап микрофона → записать 5 сек → **«Остановить»** → миниатюра добавляется.
- [ ] Текст «Что я сказал?» + аудио → Send → **осмысленный ответ** на Gemma-4-E2B-it (AC-13 audio).
- [ ] Повторить на **Gemma-4-E4B-it**.
- [ ] **AC-20.** При уже прикреплённом аудио — иконка микрофона **disabled** (contentDescription «Максимум один аудио-клип на сообщение»).
- [ ] **AC-19 входящий звонок.** Начать запись → позвонить себе с другого телефона → sheet закрывается, буфер отбрасывается, аудио НЕ добавляется.
- [ ] **AC-19 background.** Начать запись → свернуть приложение (Home) → вернуться → sheet закрыт, input bar без «залипшей» записи.
- [ ] **AC-19 lock screen.** Начать запись → заблокировать экран → разблокировать → то же поведение.

---

## 5. US-5. Переключение моделей + AC-16 (регрессия Phase 1)

- [ ] Back из `ChatScreen` → `ModelManagerScreen` → тап **«Загрузить»** на другой модели.
- [ ] Предыдущий engine выгружается без краша (Logcat: нет stacktrace, есть `cleanup` + `initialize`).
- [ ] Открывается новый `ChatScreen`, пустой.
- [ ] Текстовый запрос (без вложений) → стримящийся ответ без краша.
- [ ] Скорость ответа субъективно ≤ 1.5× Gallery (TTFT из Phase-1 baseline — сохраняется).
- [ ] Скачивание / возобновление / отмена модели работают (Phase 1 regression).

---

## 6. US-6. Выход = потеря чата (D1, D6 user-spec)

- [ ] В середине чата с вложениями → Back → `ModelManagerScreen`.
- [ ] Повторный вход в ту же модель → `ChatScreen` пустой (эфемерность). Overrides в DataStore сохранены (проверь Settings → нужный `temperature` на месте).

---

## 7. US-7. AboutScreen (AC-5, AC-6)

- [ ] Открыть `AboutScreen` через Info action на `ModelManagerScreen`.
- [ ] Markdown форматирование работает (заголовки, списки, ссылки).
- [ ] Тап на http(s) ссылку в About → открывается браузер (AC-22 ↔ D25 SafeUriHandler allow-list).
- [ ] Футер показывает актуальную версию и атрибуцию.
- [ ] *Optional:* отредактировать `app/src/main/assets/about.md`, пересобрать debug APK, установить — текст обновлён.

---

## 8. Edge cases (сверх US)

- [ ] Back во время стрима → стрим останавливается, сессия уничтожается, возврат в `ModelManagerScreen`.
- [ ] Reset (↻) в TopAppBar во время стрима → стрим прерывается, чат-лента очищается, engine остаётся инициализированным.
- [ ] Settings sheet открывается во время стрима — изменения light-полей принимаются, применяются к следующему ходу; heavy-изменение во время стрима → HeavyChangeDialog предупреждает, confirm прерывает стрим.
- [ ] Rotation (портрет ↔ ландшафт) во время активного чата → чат-лента сохраняется, attachments сохраняются (rememberSaveable).
- [ ] Долгий промпт (≥ maxTokens) → ответ обрывается на лимите, сообщение не крашит UI.
- [ ] Пустая сеть / отсутствие интернета → поведение при попытке скачивания модели (errror handling из Phase 1).

---

## 9. Deferred AC → pass/fail

| AC | Нужно | Статус |
|---|---|---|
| AC-2 | Launcher label «Sanctum Machina» | pass/fail |
| AC-13 | Multimodal inference end-to-end — обе модели, фото + аудио | pass/fail |
| AC-14 | Thinking block — обе модели, auto-expand → collapse | pass/fail |
| AC-16 | Phase-1 regression — текстовый чат, download, switch | pass/fail |
| US-1 | Первый запуск + download + ChatScreen | pass/fail |
| US-2 | Multimodal фото (picker + camera) | pass/fail |
| US-3 | Аудио-ввод + inference | pass/fail |
| US-4 | Настройки по ходу (light/semi-light/heavy) | pass/fail |
| US-5 | Переключение моделей | pass/fail |
| US-6 | Эфемерность чата | pass/fail |
| US-7 | AboutScreen | pass/fail |

---

## 10. Финальный гейт AC-22

После прохождения всех пунктов выше:

- [ ] **Пользователь явно подтверждает AC-22:** Phase 2 готова к закрытию.

> Закрытие AC-22 — только явным словом пользователя. Агент не закрывает автоматически.

---

## AC → автоматизация mapping

Полный отчёт → `logs/working/qa-report.json`.

| AC | Как проверено | Гейт |
|---|---|---|
| AC-1  | `AllowlistLoaderTest` (16 tests) + `fixtureMatchesProductionAsset` (TAC-6) | auto |
| AC-2  | `aapt dump resources` + ручная проверка лаунчера | semi-auto |
| AC-3  | `AppSettingsRepositoryTest` (6) + TAC-12 generated proto | auto |
| AC-4  | `ChatViewModelTest` state machines + manual-smoke §2 | semi-auto |
| AC-5  | `SanctumApp` NavHost + `ModelManagerScreen` Info + manual-smoke §1 | semi-auto |
| AC-6  | `AboutScreen` читает `assets/about.md` + manual-smoke §1, §7 | semi-auto |
| AC-7  | `SafeMarkdown` + `SafeUriHandlerTest` (14, TAC-13) + manual-smoke §3 | semi-auto |
| AC-8  | `ChatScreen` LaunchedEffect + manual-smoke §3 | user-only |
| AC-9  | `MultimodalInputBar` + `ChatViewModelTest` send-gate + manual-smoke §3 | semi-auto |
| AC-10 | manual-smoke §3 Photo Picker + AC-10 clip handling в ChatViewModelTest | semi-auto |
| AC-11 | `CameraBottomSheetTest` pure helpers + manual-smoke §3 | semi-auto |
| AC-12 | `AudioRecorderBottomSheetTest` pure helpers + manual-smoke §4 | semi-auto |
| AC-13 | manual-smoke §3, §4 | user-only |
| AC-14 | `ChatViewModelTest` thinking + manual-smoke §2, §3 | semi-auto |
| AC-15 | `ChatViewModelTest` permission paths + manual-smoke §3, §4 | semi-auto |
| AC-16 | manual-smoke §5 (Phase 1 regression) | user-only |
| AC-17 | full `:core-runtime:test` + `:core-settings:test` + `:app:test` (134 total) | auto |
| AC-18 | `ChatViewModelTest` conditional-buttons + manual-smoke §3 | semi-auto |
| AC-19 | `AudioRecorderBottomSheet` lifecycle code + manual-smoke §4 | user-only |
| AC-20 | `MultimodalInputBar` enabled-logic + manual-smoke §4 | semi-auto |
| AC-21 | D15 classification in ChatViewModel + manual-smoke §2 | semi-auto |
| AC-22 | финальный гейт — user confirmation | user-only |
| AC-26 | `ChatViewModelTest` send-transfers-attachments + manual-smoke §3 | semi-auto |

## TAC → автоматизация mapping (все auto-verified)

| TAC | Результат | Evidence |
|---|---|---|
| TAC-1 | pass | `./gradlew build` BUILD SUCCESSFUL |
| TAC-2 | pass | `:core-runtime:test` green (62 tests) |
| TAC-3 | pass | `:core-settings:test` green (6 tests) |
| TAC-4 | pass | `:app:test` green (66 tests) |
| TAC-5 | pass | `./gradlew lintDebug` no Error-level (после фикса ChatScreen.kt:71 `LocalContext.getString` → `LocalResources.getString`) |
| TAC-6 | pass | `fixtureMatchesProductionAsset` green |
| TAC-7 | pass | `grep androidx.compose/androidx.activity core-runtime/src/main` → empty |
| TAC-8 | pass | `grep androidx.compose/androidx.activity core-settings/src/main` → empty |
| TAC-9 | pass | `aapt dump permissions` → CAMERA + RECORD_AUDIO + INTERNET |
| TAC-10 | pass | `aapt dump xmltree` → `allowBackup=0x0` (false) |
| TAC-11 | pass | `grep 'Text("[А-Яа-я]' app/src/main` → empty |
| TAC-12 | pass | generated proto classes present, all has/get methods emitted (15 hits) |
| TAC-13 | pass | `SafeUriHandlerTest` 14 cases |
| TAC-14 | pass | `aapt dump xmltree res/xml/data_extraction_rules.xml` → cloud-backup + device-transfer с `exclude domain=root path=.` |
| TAC-15 | pass | `ErrorLogTest` 8 cases (whitelist + cause-chain bounding) |
