# Implementation notes

## Current API facts verified during generation

- Gemini 3.1 Flash Live Preview model ID: `gemini-3.1-flash-live-preview`.
- Live WebSocket endpoint is `...GenerativeService.BidiGenerateContent` under `v1beta`; ephemeral-token connections use the constrained endpoint and `access_token`.
- Live audio input is raw 16-bit PCM, 16 kHz mono; output audio is 24 kHz. Google's best-practices page recommends small realtime chunks and session resumption/context compression for long sessions.
- `VoiceInteractionService` is the system-selected service path used for background hotword/voice interaction, while heavier work belongs in the associated session service.
- API 36 large-screen behavior makes adaptive/resizable layouts important; this project does not force a portrait-only activity.
