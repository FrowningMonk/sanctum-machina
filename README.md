**English** | [Русский](README.ru.md)

# Sanctum Machina

> Local multimodal LLM client for Android. Models run entirely on-device — no cloud, no network, no telemetry.

A fork of [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) focused on LLM chat — with a custom UI, persistent chat history, and an incognito quick-chat mode.

---

## Demo

Airplane mode on; everything below runs without network:

![Airplane mode demo](docs/media/demo-airplane-mode.webp)

Multiple persistent chats kept independent — switch from a Python codegen session to a privacy-first AI pitch (tagline → tweet → Spanish), then come back to the first chat and continue exactly where you left off. Each chat keeps its own KV-cache, settings, and message history:

![Chat demo](docs/media/demo-chat.webp)

## Status

**Pre-alpha / experimental.** APKs published in Releases are debug builds tagged `Pre-release`. The project name, architecture, and `applicationId` may still change; a future stable release **will not be able to upgrade** the currently installed APK — you will have to reinstall and lose local data (chat history, settings). Not intended for daily use.

## Install

APK files are on the [Releases](../../releases) page. Download the latest one, open it in a file manager on Android, and install. You will need to grant the "Install from unknown sources" permission.

## What it is

Built on the [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) engine: we own the model discovery, downloads, engine lifecycle, chat UI with persistent history, settings, and diagnostics. The engine and the models themselves are binary artifacts from Google and the community; we orchestrate them.

## Features

- On-device LLM inference (Android 12+).
- **Gemma 4** models (E2B, E4B) from the HuggingFace [`litert-community`](https://huggingface.co/litert-community) repository.
- Multimodal input: text, image (gallery / camera), short audio clip.
- Separate **reasoning channel** for models that support it.
- Per-model inference settings: temperature, top-K, top-P, max tokens, accelerator, system prompt.
- Persistent chat history with a sidebar drawer (rename, delete, sections by date), plus an incognito **quick-chat** mode.
- Pre-flight RAM gate — models that need more memory than the device has are blocked from download.
- Per-message metrics in the chat footer: TTFT and decode tok/s.
- Crash recovery, background model warm-up, diagnostic log export.

## Known issues

A few quirks and gaps that exist on day one — tracked, not surprises:

- **SwiftKey leaves a gap below the chat input bar on Honor 200** ([#1](../../issues/1)). IME-design behaviour, not an app layout bug — Gboard / system keyboard render flush.
- **Only the first photo is saved to history when multiple are sent in one message** ([#4](../../issues/4)). All photos still flow into the model's reply; only history is single-image.
- **Tested only on Honor 200** ([#5](../../issues/5)) — other Android 12+ devices should work but are unverified; reports welcome.

## Privacy

- **Data never leaves the device.** No cloud sync, no telemetry, no analytics.
- **Google Auto Backup is disabled** — settings and history do not get uploaded to Google Drive.
- Model downloads are the only network activity; they go directly to HuggingFace through a strict allowlist.

## Tech stack

- **Platform:** Android, `minSdk 31`, `targetSdk 35`
- **Language / UI:** Kotlin, Jetpack Compose, Material 3
- **LLM engine:** [LiteRT-LM 0.10.0](https://github.com/google-ai-edge/LiteRT-LM) (`.aar` from Google Maven)
- **DI:** Hilt
- **Storage:** Room (history), DataStore + protobuf (settings)
- **Downloads:** WorkManager (foreground service)

## License

[Apache License 2.0](LICENSE), inherited from upstream Google AI Edge Gallery. Attribution and modification notices are in [`NOTICE`](NOTICE).

## Attribution

- [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) — fork base.
- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) — on-device inference runtime.
- [Gemma](https://ai.google.dev/gemma) — model family.
