# Handoff: Phase 3 UI — Sanctum Machina

## Overview

Дизайн-концепт UI для **Phase 3 (История)** проекта Sanctum Machina — Android-приложения для локальных LLM (форк Google AI Edge Gallery). Покрывает: home hub, drawer с персистентной историей, обычный чат, инкогнито «быстрый чат», черновик с model-picker, состояния TopAppBar (ready/warming/loading/failed) и Model Manager.

Связан с `work/phase-3-history/user-spec.md` и критериями приёмки **AC-P3, AC-U5, AC-U6, AC-U7, AC-E7, AC-F7**.

## About the Design Files

Файлы в этом пакете — **HTML-референсы дизайна**, прототипы, показывающие задуманный внешний вид и поведение, а **не production-код для прямого копирования**.

Задача — воспроизвести эти экраны в целевом стеке проекта: **Jetpack Compose / Material 3** в модуле `app/src/main/kotlin/app/sanctum/machina/ui/`, используя уже установленные паттерны (`ChatScreen.kt`, `RestartCrashBanner`, `ModelManagerScreen` из Phase 2/2.5) и токены темы Compose.

Цветовые значения, типографика и spacing в HTML — это **исходные токены**. Их нужно перенести в `Color.kt` / `Type.kt` / `Theme.kt` Material 3.

## Fidelity

**High-fidelity.** Пиксель-перфект с финальными цветами, типографикой, отступами и состояниями. Воспроизводить точно, адаптируя только под соглашения Compose (`dp`/`sp` вместо `px`, `Modifier` вместо inline styles).

## Screens / Views

### 1. Home hub (`ScreenHome`)

**Purpose:** стартовая точка. Фоновый warmup default-модели.

**Layout:** column, центрирован вертикально. TopAppBar (52dp) → пустой gap → sigil + заголовок + kicker → 2 кнопки → footer со статусом.

**Components:**
- **Sigil** (56px SVG, `accent` цвет): ромб в ромбе, круг в центре, 4 линии-спицы. `strokeWidth=1`.
- **Title**: «Sanctum<br/><em>Machina</em>», Cormorant Garamond 500, 40px / line-height 0.95, `ink`. Слово "Machina" курсивом weight 400.
- **Kicker**: «On-device · no network · private», JetBrains Mono 10px, letter-spacing 0.18em, uppercase, `inkDim`.
- **Primary button**: full-width, `bg: ink`, `color: bg`, 16px padding, `borderRadius: 2`, Inter 15px/500. Иконка `bolt` слева + «Новый быстрый чат», `send2` справа.
- **Secondary button**: full-width, transparent, `1px solid hairStrong`. «Открыть историю» + счётчик в JetBrains Mono справа.
- **Footer**: border-top `1px hair`, padding 14/20. Слева — зелёная точка + «Прогрета» + чип модели. Справа — «2.7 GB · CPU» моно 10px.

### 2. Drawer (`ScreenDrawer`)

**Purpose:** история persistent-чатов с группами по датам.

**Layout:** column, `bg: bgSunk`. Header → 2 кнопки action → scroll list → footer.

**Components:**
- **Header** (padding 16/20): kicker «Historia · История чатов» + title «Sanctum/Machina» (Cormorant 26px).
- **Action row**: flex gap 8, padding 12/12. Primary «Новый чат» (bg=ink) + Incognito «Быстрый» (bg=incognitoBg, border=incognitoEdge).
- **Group header**: kicker 10px/0.18em/uppercase, inkDim, padding 14/20/6.
- **Chat row**: padding 11/20, flex. Если `active`: `bg=bgRaised` + `borderLeft: 2px solid accent`. Содержит: title (Inter 13.5, truncate) + `SmModelChip` + дата (JetBrains Mono 10px, width 30, right-align).
- **Footer**: border-top `1px hair`, 2 кнопки flex:1 — «Модели», «О приложении».

**Groups**: «Сегодня» / «Вчера» / «На этой неделе» / «Раньше», по `last_message_at DESC`.

### 3. Persistent chat (`ScreenChat`)

**Purpose:** обычный чат с историей.

**Components:**
- **TopAppBar**: зелёная точка + имя модели в JetBrains Mono 12px + «· it» в `inkDim`.
- **Date separator**: «── Вчера ──», kicker, centered.
- **USER bubble**: justify-end, max-width 82%, `bg: userBubble`, `color: ink`, `padding: 10/13`, `borderRadius: 14` + `borderBottomRightRadius: 4`. Inter 14.5.
- **ASSISTANT**: полная ширина без фона. Header: `SmSigil` 14px accent + kicker «Machina» 9px. Затем текст Inter 14.5 line-height 1.55. Во время стриминга — пульсирующая точка accent + caret 8×14px в конце.
- **Input bar**: border-top, bg raised, `borderRadius: 22`, padding 6/6/6/14. `attach` слева — placeholder text — круглая кнопка Send 34×34 bg=ink.

### 4. Quick chat / Incognito (`ScreenQuickChat`)

**Purpose:** эфемерный чат без сохранения. AC-U5.

**Critical:** `bg: incognitoBg` поверх любой темы, `boxShadow: inset 0 0 0 2px incognitoEdge` (киноварная кайма 2px изнутри).

**Components:**
- **TopAppBar**: `incognito=true` → тёмная плашка с `borderBottom: 2px accent`, иконка `eyeOff` цвета accent + имя модели в `incognitoInk`.
- **Subheader**: «Быстрый чат · ничего не сохраняется», моно, color=edge.
- **Sigillum plate** (margin 16/18): `border: 1px edge`, `bg: rgba(0,0,0,0.35)`, 12/14 padding. Кружок слева (32×32, border=edge, eyeOff icon) + текст справа.
- **Messages**: USER bubble полупрозрачный `rgba(232,223,203,0.08)`. ASSISTANT header — `edge` цвет («Machina · эфемерно»).
- **Input**: `bg: rgba(232,223,203,0.06)`, `border: 1px edge`, Send-кнопка `bg: edge` / `color: incognitoBg`.

### 5. Draft + model picker (`ScreenDraft`)

**Purpose:** новый persistent-чат до первой отправки. AC-E7.

**Components:**
- **TopAppBar**: центральный элемент — **button** (не label), `border: 1px hairStrong`, padding 5/10. Внутри: status dot + model name (моно 12px) + chevron-down.
- **Subheader**: «Черновик · модель можно сменить до первой отправки».
- **Dropdown** (показан раскрытым ниже TopAppBar): `bg: bgRaised`, `border: 1px hairStrong`, `boxShadow: 0 12px 24px rgba(0,0,0,0.06)`. Каждая строка: star/starFill icon (accent если default) + model-id моно + размер + check icon если active.
- **Empty state**: sigil 36px inkDim + «Новый разговор» Cormorant 22 inkMuted + подсказка.

### 6. TopAppBar states (`ScreenStates`)

**Purpose:** визуальная спецификация 4 режимов. AC-U6.

Четыре варианта один под другим на `bg: bgSunk`, разделены:

1. **Ready**: status dot (accent) + моно model name. Read-only label.
2. **Warming**: spinner 12×12 (`border 1.5px hairStrong`, `border-top: ink`, `animation: spin 0.9s linear`) + dim model name + «· прогрев».
3. **Loading** (cross-model reinit): spinner accent-color + «Модель перезагружается…».
4. **Failed**: button `border: 1px danger`, color=danger, icon `warn` + «Загрузить снова».

### 7. Model Manager (`ScreenModels`)

**Purpose:** управление моделями, установка default. AC-F7.

**Components:**
- **Header**: «Scientia / локальные модели» Cormorant 26px.
- **Downloaded card**: `border: 1px accent` если active, иначе `hairStrong`. Star-icon (filled/accent если default), model-id mono, variant/size kicker. Footer: status dot + состояние + button («Открыть чат» / «Загрузить»).
- **Available** section: те же карточки но `border: 1px dashed hairStrong`, download-icon + «Скачать».

## Interactions & Behavior

- **Drawer item click** → open chat. Swipe-left → reveal «Удалить». Long-press → контекстное меню.
- **Model chip в TopAppBar (draft)** → dropdown из списка downloaded. Выбор другой → cross-model confirmation.
- **Model chip (ready)** → read-only (не клик).
- **Failed state «Загрузить снова»** → retry `registry.initialize(modelId)`.
- **Streaming cursor**: `animation: sm-pulse 0.8s ease-in-out infinite`, opacity 0.35 ↔ 1.
- **Spinner rotation**: `animation: sm-spin 0.9s linear infinite`.
- **Incognito**: плашка статична, но весь экран визуально отличается от обычного (inset box-shadow 2px).

## State Management

Уже зафиксировано в `work/phase-3-history/user-spec.md` и `tech-spec.md`. UI-специфичное:

- `engineState: ready | warming | loading | failed` — управляет видом TopAppBar.
- `chatMode: persistent | quick | draft` — управляет темой (incognito vs нет) и наличием model-picker.
- `streamingMessage: StateFlow<String?>` — рендер «живого» пузыря до `done=true`.
- `activeChatId` → подсветка строки в drawer (borderLeft=accent).

## Design Tokens

### Colors — LIGHT

| Token | Value | Role |
|---|---|---|
| `bg` | `#f4efe6` | основной пергамент |
| `bgSunk` | `#ebe4d6` | drawer, section dividers |
| `bgRaised` | `#fbf7ee` | поднятые поверхности, input |
| `ink` | `#1a1714` | основной текст |
| `inkMuted` | `#5a504a` | подписи |
| `inkDim` | `#8a7f75` | метаданные |
| `hair` | `rgba(26,23,20,0.08)` | тонкие разделители |
| `hairStrong` | `rgba(26,23,20,0.16)` | бордеры кнопок |
| `userBubble` | `#e5ddcd` | USER сообщения |
| `accent` | `oklch(0.58 0.14 40)` | киноварь |
| `danger` | `oklch(0.56 0.16 28)` | |
| `incognitoBg` | `#1b1a18` | |
| `incognitoInk` | `#e8dfcb` | |
| `incognitoEdge` | `oklch(0.62 0.16 42)` | кайма incognito |

### Colors — DARK

| Token | Value |
|---|---|
| `bg` | `#15130f` |
| `bgSunk` | `#0d0b09` |
| `bgRaised` | `#1e1b16` |
| `ink` | `#e8dfcb` |
| `inkMuted` | `#a59a88` |
| `inkDim` | `#6e6458` |
| `hair` | `rgba(232,223,203,0.08)` |
| `hairStrong` | `rgba(232,223,203,0.18)` |
| `userBubble` | `#2a2620` |
| `accent` | `oklch(0.68 0.14 42)` |
| `danger` | `oklch(0.66 0.17 28)` |
| `incognitoBg` | `#0a0907` |
| `incognitoEdge` | `oklch(0.68 0.16 42)` |

### Typography

- **Display** (Cormorant Garamond, weight 500, letter-spacing −0.01em): 40sp / 26sp / 22sp
- **UI body** (Inter, weight 400/500/600): 15sp / 14.5sp / 13sp / 12sp
- **Kicker / chips** (JetBrains Mono, weight 400): 10sp uppercase letter-spacing 0.18em

На Android: подключить Cormorant Garamond + Inter + JetBrains Mono через `res/font/` либо Google Fonts provider (`androidx.compose.ui:ui-text-google-fonts`).

### Spacing

4, 6, 8, 10, 12, 14, 16, 18, 20, 22 dp. Следовать scale: используется везде кратно 2.

### Border radius

- Кнопки, карточки, чипы: **2dp** (очень острые — намеренный «оккультный» характер)
- Bubbles USER: **14dp** + asymmetric `bottomRight=4dp`
- Input bar container: **22dp** pill
- Round buttons (Send): **50% (34dp circle)**

### Shadows

- Dropdown: `0 12px 24px rgba(0,0,0,0.06)` light, усилить до `0 12px 32px rgba(0,0,0,0.4)` dark.
- Карточки — **без теней**, только hairline-бордеры.

## Assets

Никаких растровых ассетов — **всё SVG inline**. Иконки (20×20 grid, strokeWidth 1.5, round linecap/linejoin):
`menu, settings, plus, bolt, ghost, eyeOff, send, send2, mic, attach, star, starFill, more, check, back, edit, trash, warn, chevronDown, download, sparkle`.

Сигил ассистента (`SmSigil`) — ромб в ромбе + центральный круг + 4 линии-спицы.

На Android: завести в `res/drawable/` как `VectorDrawable` XML, либо использовать Compose `Icons.Outlined` + кастомные `ImageVector` для sigil/bolt/eyeOff.

## Files

- `Sanctum Machina.html` — главная сборка с design canvas (14 экранов)
- `sanctum-tokens.jsx` — токены цветов/шрифтов + SVG-иконки
- `sanctum-atoms.jsx` — `SmAppBar`, `SmModelChip`, `SmDot`, `SmSigil`, `SmGhostButton`
- `sanctum-frame.jsx` — Android-рамка с status bar
- `sanctum-screen-home.jsx` — home hub
- `sanctum-screen-drawer.jsx` — drawer истории
- `sanctum-screen-chat.jsx` — persistent chat + bubble components
- `sanctum-screen-quick.jsx` — incognito quick chat
- `sanctum-screen-more.jsx` — draft, states, model manager
- `design-canvas.jsx` — starter canvas (инфраструктура, не часть дизайна)

## Notes for implementation

1. **Material 3 уже используется** в проекте (M3 Theme и compose-bom). Расширить `Theme.kt` двумя наборами токенов.
2. **Sanctum это не Material**: borderRadius 2dp вместо 12, hairline-бордеры вместо surface elevation, serif для дисплейных текстов. Не ведитесь на дефолтные M3-компоненты — override `Surface`, `TopAppBar`, `Card`.
3. **Incognito-режим** — отдельная тема `SanctumIncognitoTheme`, применяется к NavHost-ветке `chat/quick`.
4. **Modifier для incognito-каймы**: `Modifier.drawBehind { inset(1.dp.toPx()) { drawRect(edge, style=Stroke(2.dp.toPx())) } }`.
5. Bubble для USER — `Surface(shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))`.
6. Стриминг-caret — `Box(Modifier.size(8.dp, 14.dp).background(accent))` + `infiniteTransition` на alpha.
