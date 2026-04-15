# UX Guidelines

## Purpose
UX standards and user-facing communication for AI agents. Helps agents write consistent UI text and follow design patterns.

---

## Interface Language

**Primary language:** Russian for all user-facing text (buttons, labels, empty states, error messages, About screen manifesto, settings).

**Localization:** Single language. No i18n framework, no `values-en/` shadow resources. Strings live in `res/values/strings.xml`. If bilingualism becomes necessary for sharing with non-Russian users after Phase 5, introduce Android resource localization at that point — not earlier.

**Exception: README.** Project README is Russian for Phase 1-5. When the repo goes public after the developer's personal test, an English version may be added (`README.en.md`) — deferred decision.

**Exception: code.** See `CLAUDE.md § Язык общения` for the code/comment/prompt language policy.

---

## Tone of Voice

**Overall tone:** Minimalist, pragmatic, neutral. "Инструмент, а не собеседник" — the UI text is written as if for an experienced user who does not need hand-holding.

**Writing style:**
- Short, direct sentences. Active voice. No filler words ("пожалуйста", "к сожалению", "упс", "ой").
- No exclamation marks except in the About / manifesto section.
- Imperative mood for button labels ("Создать", "Удалить", "Открыть").
- Error messages are concrete and stop after stating what went wrong — no apologies, no reassurance, no "try again" suggestions unless there is an actual retry action nearby.
- No emojis anywhere in the UI or in log messages (already mandated for logs in `patterns.md`). The manifesto in About may use a restrained typographic flourish, but no emoji.

**Voice characteristics:**
- **Formality level:** Neutral-formal. Address the user via "вы" form implicitly (avoid direct second-person where possible). Technical terms stay in their canonical form ("модель", "токен", "контекст"), not translated or softened.
- **Emotional tone:** Calm, unflustered. No warmth-signaling copy. The tool works or it fails clearly.
- **Technical complexity:** Assumes the user understands what a local LLM is. No onboarding tooltips explaining "what is a model" in Phase 2. Phase 5 onboarding (if any) may add a concise explanation, still assuming a technically aware audience.
- **Humor:** None in the chat / settings / error surfaces. The About screen and manifesto in Phase 5 may carry light WH40K / Cult of the Machine atmosphere — reserved, not parody.

**Example phrases by context:**

- ✅ Good: "Создать проект", "Модель не найдена", "Скачивание прервано", "Нет активных чатов"
- ❌ Avoid: "Ой, что-то пошло не так 😕", "Пожалуйста, попробуйте снова!", "Добро пожаловать в Sanctum Machina!", "Ваши проекты ✨"

---

## Domain Glossary

Terms used consistently across UI, specs, and code (Russian UI form / English code form).

- **Проект** / `Project` — A user-defined group of chats sharing a system prompt. UI entry point of the app.
  *UI example: "Мои проекты", "Создать проект".*
- **Чат** / `Chat` — One conversation session with a model. Belongs to a project or exists as a "quick chat" without a project.
  *UI example: "Новый чат", "История чатов".*
- **Быстрый чат** / `Quick chat` — Incognito-style ephemeral chat. Lives only in memory while open; leaving the screen or closing the app discards it. Never written to Room. Has its own dedicated entry action ("Новый быстрый чат"), analogous to Incognito in ChatGPT/Claude.
  *UI example: "Новый быстрый чат".*
- **Модель** / `Model` — A specific LLM (e.g., `Gemma-4-E4B-it`). Models are chosen at chat creation, stored with the chat.
  *UI example: "Выбрать модель", "Модель: Gemma-4-E4B-it".*
- **Системный промпт** / `System prompt` — Instruction injected at the start of every conversation in a project. Inherited by chats from their project; can be overridden per chat.
  *UI example: "Системный промпт проекта".*
- **Ризонинг** / `Thinking` — The model's internal reasoning channel (litertlm's `message.channels["thought"]`), shown optionally as a separate collapsible block above the answer.
  *UI example: "Показать ризонинг".*
- **Ядро** / `Core` — Internal term for the `:core-runtime` module. Not shown in UI; used in logs, commits, and specs.
- **Ковчег** — Manifesto term. Appears only in the About screen. UI-only, no code equivalent.

---

## Text Patterns

### Buttons

**Style:** Imperative verb + object for primary actions, single verb for secondary.

**Examples:**
- Primary actions: "Создать проект", "Отправить", "Скачать модель", "Начать чат"
- Secondary actions: "Назад", "Отмена", "Закрыть"
- Destructive actions: "Удалить проект", "Удалить чат", "Удалить модель"

### Error Messages

**Format:** Single sentence, concrete, ending with a period. If an actionable hint exists, it goes as a second sentence. No apologies, no filler.

**Examples:**
- "Не удалось загрузить модель." (simple)
- "Модель не найдена на устройстве. Скачайте заново." (with action hint)
- "Не удалось сохранить сообщение." (storage failure — no retry hint because user has no recovery action)
- "Нет подключения к интернету." (download scenario only — never shown during inference since inference is offline)

### Success / Confirmation Messages

**Format:** Prefer no confirmation at all when the UI visibly reflects the state change (new project appears in list → no toast). Confirmation only when state change is not visible.

**Examples where confirmation is appropriate:** "Модель скачана", "Лог экспортирован".
**Examples where confirmation is NOT needed** (UI itself confirms): creating a project, sending a message, deleting a chat (item disappears from list).

### Loading States

**Style:** Gerund form with trailing period-period-period. Short, specific.

**Examples:**
- "Загружается модель..."
- "Генерация..."
- "Скачивание..."
- "Подготовка..."

### Empty States

**Format:** One clear sentence describing the empty state, optionally followed by a primary action button.

**Examples:**
- Project list empty: "Нет проектов." + button "Создать проект".
- Chat list in project empty: "В проекте нет чатов." + button "Новый чат".
- Messages in chat empty (new chat, no turns yet): No text at all. Just an input field at the bottom, cleanly placed.

---

## Copy Reference

**Location:** All user-facing text in `app/src/main/res/values/strings.xml`. No inline hardcoded strings in Compose. This keeps all UI copy auditable in one place and ready for i18n if added later.

---

## Design System

**Design files:** None in Phase 1-2. Design emerges in code. In Phase 5 a Figma-style mockup may be assembled as reference for final polish, but it is not a prerequisite.

**Design references** (for visual inspiration, not copying):
- **Primary:** [Pocket LLM](https://github.com/dineshsoudagar/local-llms-on-android) — clean minimal list-based chat UI, ChatGPT-like. Closest to the target feel.
- **Secondary (for the sidebar/drawer pattern with chat history):** Claude mobile, ChatGPT mobile.
- **Philosophical contrast only, do not copy:** [LLM Hub](https://github.com/timmyy123/LLM-Hub) (non-OSI license, monetized), [SmolChat-Android](https://github.com/shubham0204/SmolChat-Android) (different inference engine — llama.cpp, not litertlm).

**Visual direction:**
- Material 3 as base.
- Dark-first. Users using Sanctum Machina are likely to use it in low-light or focus conditions; dark is also a better aesthetic fit for the Sanctum Machina manifesto. Light theme is supported but not the default.
- Whitespace over ornament. No gradients, no shadows-for-decoration, no rounded-corner explosions. A single corner radius used consistently across all elements.
- Typography hierarchy limited to three sizes (body, section header, screen title) plus monospace for code blocks inside assistant messages.
- Icons: Material Symbols, outlined style, single weight.

**Color palette** (indicative, finalized in Phase 5):
- Background (dark): near-black neutral, not pure #000000. Near-white neutral on light.
- Surface elevation: a single elevation level visible via a subtle background shift, not via shadows.
- Primary accent: a restrained warm tone (copper / brass) that connects to the Sanctum Machina manifesto without screaming the theme through the chat UI. Applied sparingly — send button, active project pill, selected state.
- Error: standard red, muted.
- User message bubble vs. assistant message: distinguished by alignment (user right, assistant full-width) and a single subtle background tint, not by shape.

**Key components to build:**
- `ProjectsListScreen` — top-level, shows list of projects + a persistent top action "Новый быстрый чат" (incognito) + FAB to create a project.
- `ProjectDetailScreen` — shows the project's chats list + project's system prompt as a header + FAB to create a chat in the project.
- `ChatScreen` — header shows model name and (if applicable) project name; body is the scrollable message list with streaming and thinking-channel support; bottom is the multimodal input bar (text field + camera + mic attach buttons).
- `QuickChatScreen` — visually identical to `ChatScreen` but with a clear "incognito" indicator in the header and no title / no "save" affordances. Leaving the screen ends the session.
- `ModelManagerScreen` — list of models from allowlist, each row showing download state (not downloaded / downloading N% / downloaded + size). Accessed from settings.
- `InferenceSettingsScreen` (per model) — exposes the Inference Settings Schema from `architecture.md`: Max tokens, topK, topP, temperature, Enable thinking (toggle), Accelerator (GPU/CPU), Default system prompt. Shows current effective value + "restore default" per field; global "Reset to defaults" action clears all overrides. Accessed from `ModelManagerScreen` row.
- `SettingsScreen` — theme switch, model manager entrypoint, log export action, HuggingFace sign-in button, About entrypoint.
- `AboutScreen` — Sanctum Machina manifesto + attribution to Google AI Edge Gallery, Gemma, LiteRT-LM, and open-source dependencies. Shows app version and Room DB version.

**Sanctum Machina atmosphere:** applied only to the About screen in Phase 5, not sprinkled throughout the chat UI. The rest of the app is neutral functional design with the manifesto living in one dedicated place.

---

## Accessibility

- All icon-only buttons have `contentDescription`.
- Text contrast meets WCAG 2.1 AA (4.5:1 normal, 3:1 large).
- Font sizes scale with system font size preference.
- Screen reader tested once at Phase 5 (TalkBack pass on critical flows: creating a chat, sending a message).
