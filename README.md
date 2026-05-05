**English** | [Русский](README.ru.md)

# Sanctum Machina

> Local multimodal LLM client for Android. Models run entirely on-device — no cloud, no network, no telemetry.

A fork of [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) with a custom UI, persistent chat history, and a shared-context layer for everyday use.

---

## Status

**Pre-alpha / experimental.** APKs published in Releases are debug builds tagged `Pre-release`. The project name, architecture, and `applicationId` may still change; a future stable release **will not be able to upgrade** the currently installed APK — you will have to reinstall and lose local data (chat history, settings). Not intended for daily use.

## Install

APK files are on the [Releases](../../releases) page. Download the latest one, open it in a file manager on Android, and install. You will need to grant the "Install from unknown sources" permission.

## What it is

A thin wrapper around the [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) engine: model discovery, downloads, engine lifecycle, UI, and settings. The engine and the models themselves are binary artifacts shipped by Google and the community; we orchestrate them.

## Features

- On-device LLM inference (Android 12+).
- Support for **Gemma 4** models (E2B, E4B) from the HuggingFace [`litert-community`](https://huggingface.co/litert-community) repository.
- Multimodal input: text, image (gallery / camera), short audio clip.
- Separate thinking channel for models that support it.
- Per-model inference settings: temperature, top-K, top-P, max tokens, accelerator, system prompt.
- Persistent chat history (Room).
- Crash recovery, background model warm-up, diagnostic log export.

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
