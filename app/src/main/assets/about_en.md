# Sanctum Machina

A local multimodal LLM client: models run entirely on the device, with no calls to external services.

## Features

- Text chat with streaming generation and auto-scroll.
- Multimodal input: photo (gallery / camera) and short audio clip.
- Separate reasoning channel for models that support it.
- Per-model inference settings: temperature, top-K, top-P, max tokens, accelerator, system prompt.
- Persistent chat history with a sidebar drawer (rename, delete, sections by date), plus an incognito quick-chat mode.
- Per-message metrics in the chat footer: TTFT and decode tok/s.

## Privacy

- Data never leaves the device: no cloud sync, no telemetry.
- Google Auto Backup is disabled; chat history and settings are not uploaded to Google Drive.

## Attribution

The app uses open-source components:

- [Google AI Edge Gallery](https://github.com/google-ai-edge/gallery) — fork base; multimodal handling and UI patterns are derived from it.
- [Gemma](https://ai.google.dev/gemma) — model family from Google.
- [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) — on-device inference runtime.

## Licenses

- **EmbeddingGemma-300M** ships inside this application and is redistributed under the [Gemma Terms of Use](https://ai.google.dev/gemma/terms).
- **Gemma 4 E2B / E4B** chat models are downloaded by the user from Hugging Face under the same [Gemma Terms of Use](https://ai.google.dev/gemma/terms).

## Feedback

The project evolves iteratively. This file (`assets/about_en.md`) is edited directly — change its content as needed, rebuild the APK, and the About screen reflects the update.
