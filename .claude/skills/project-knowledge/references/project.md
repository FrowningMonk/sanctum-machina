# Project Context

## Purpose
This file provides high-level project overview for AI agents. Helps agents understand WHAT we're building and WHY.

---

## Project Overview

**Name:** PhoneWrap (repository / working directory) / Sanctum Machina (application, public-facing name)

**Description:** Android app for local LLM inference. A chat tool that runs Gemma-class models entirely on the device via LiteRT-LM, organizes conversations into projects with shared system prompts and files, and depends on no cloud services.

Built on top of a surgically-extracted core from Google AI Edge Gallery (inference engine, model download, HuggingFace OAuth), with a custom UI designed around the ChatGPT/Claude mental model: projects → chats → messages.

---

## Target Audience

**Primary user:** The developer himself (solo use), as the first and main audience. Architecture is designed for future expansion to a broader audience of privacy-conscious users who want fully local LLMs without vendor lock-in.

**Use case:** Daily work with a local LLM on a personal phone — research, writing, coding help, image and audio analysis — with full privacy, persistent history, and the ability to organize work into long-running projects (each project carrying its own system prompt and, later, shared reference files).

**Target device class:** Modern Android phones with 8GB+ RAM, NPU preferable but not required. Reference device for development: Honor 200 (12GB RAM).

---

## Core Problem

Google AI Edge Gallery demonstrates that state-of-the-art local inference on a phone is possible and fast, but it is a reference/showcase app: **it does not persist chat history**, has no concept of projects, and mixes half a dozen demo flows (Agent Skills, Mobile Actions, Tiny Garden, Benchmark, Prompt Lab) that dilute the core chat experience. Its architecture is shaped around showcasing LiteRT-LM rather than being a usable daily tool.

**Sanctum Machina solves this by building the missing daily-driver wrapper around Gallery's core:** projects-first navigation, persisted history, multimodal input retained, strictly open-source stack, no cloud dependencies. The manifesto behind the name — "an ark of LLM knowledge, preserved on-device" — is not marketing, it is the hard constraint that shapes every technical decision.

---

## Key Features

Priorities: **Critical** = must exist for the tool to be useful at all, **Important** = needed for daily use, **Post-MVP** = later phases.

- **Local LLM inference** (Critical) — Gemma-4-E2B / E4B and other LiteRT-LM models run fully on-device via `:core-runtime` module extracted from Gallery. Streaming responses, thinking-channel support, CPU/GPU/NPU backend selection.
- **Multimodal input** (Critical) — photo (camera + gallery) and audio recording, fed into the model through litertlm's `Contents` API exactly as Gallery does.
- **Persistent chat history** (Critical) — every chat and message stored in Room; app restart preserves full state. Closed conversations can be reopened and continued within the model's context window.
- **Projects** (Critical) — first-class entity: a group of chats sharing a system prompt. Entry screen is the list of projects, not the list of chats.
- **Quick chat (incognito)** (Critical) — a separate "new quick chat" action, analogous to Incognito mode in ChatGPT/Claude. Lives only in memory while the chat is open and the app process is alive; leaving the chat or closing the app discards it. Never written to Room. The only way to converse without leaving a trace.
- **Model manager** (Important) — browse allowlist (forked from Gallery `1_0_11.json`), download via WorkManager, authenticate to HuggingFace via AppAuth when required, manage local storage.
- **Per-model inference settings** (Important) — ported from Gallery: Max tokens, topK, topP, temperature, and backend choice (CPU/GPU/NPU) tunable per model. These directly affect both speed and output quality and are part of what makes Gallery-quality inference possible. Defaults come from the allowlist; user overrides persist per model.
- **Local app settings, no telemetry** (Important) — theme, default model, system prompt templates, all in Proto DataStore. Nothing leaves the device.
- **Error log on device** (Important) — local file, ERROR level only, one line per event, no INFO/WARNING. For debugging when something breaks.
- **RAG over project files** (Post-MVP, Phase 4) — upload text files to a project, embed them via a future `Embedder` interface, retrieve relevant chunks as additional context for each chat in the project.
- **Polished visual identity** (Post-MVP, Phase 5) — themes, onboarding, About screen with the Sanctum Machina manifesto and attribution, full model-wipe-and-reinstall test.

<!--
Feature backlog and detailed phase plans live in work/{feature}/ directories as they are
planned (not in this file). The canonical phase roadmap for Sanctum Machina is:
- Phase 1: extract :core-runtime + minimal chat .apk
- Phase 2: proper UI + multimodality + settings
- Phase 3: persistent chat history
- Phase 4: projects + shared files (RAG)
- Phase 5: final visual identity + install-from-scratch test
See work/research/gallery-analysis.md for the fork decision rationale.
-->

---

## Out of Scope

- **Cloud sync of any kind.** No account system, no remote backups, no cross-device state.
- **Remote LLM APIs.** No OpenAI, Anthropic, Google Gemini, or any other cloud model. Local only.
- **Telemetry and analytics.** No event tracking, no crash reporting to external services. Error log stays on-device.
- **Inherited Gallery demo flows that do not fit a daily chat tool.** Agent Skills, Mobile Actions, Tiny Garden, Prompt Lab (split view), Benchmark screen — all removed.
- **iOS.** Not in this iteration. `:core-runtime` stays KMP-disciplined to keep the option open later.
- **Monetization.** No ads, no in-app purchases, no paid tiers, no subscriptions. Ever.
- **App Store distribution (Google Play / F-Droid).** Distribution is manual APK transfer and, after public release, GitHub Releases.

Technical exclusions (Firebase, AICore, closed-source dependencies) are enforced in `architecture.md` rather than restated here.
