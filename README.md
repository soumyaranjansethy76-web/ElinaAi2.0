# Elina — Android AI Voice Assistant + VRM Companion

Elina is an Android Kotlin/MVVM voice assistant targeting API 36 with a bundled VRM avatar, Gemini Live WebSocket voice I/O, local Room memory, Accessibility-based controlled device actions, and Android VoiceInteraction integration.

## Build

Requires JDK 17, Android SDK 36, and Gradle 9.6.0 (the included `gradlew` will use Gradle on PATH or download the configured distribution). Run:

```bash
./gradlew assembleDebug
```

GitHub Actions is configured in `.github/workflows/android.yml` and uploads the debug APK as an artifact. A workflow-dispatched release job uses the optional `ELINA_KEYSTORE_BASE64`, `ELINA_KEYSTORE_PASSWORD`, `ELINA_KEY_ALIAS`, and `ELINA_KEY_PASSWORD` secrets; without them the release build falls back to the debug key for packaging/testing.

## Gemini Live

The implementation uses the v1beta `BidiGenerateContent` WebSocket endpoint, `gemini-3.1-flash-live-preview`, native audio, input/output transcription, function calling, session resumption, context-window compression, and GoAway handling. API credentials are never committed. Development keys are encrypted with Android Keystore-backed AES-GCM before local storage.

The official Live API docs specify the v1beta WebSocket endpoint, session resumption handles, context compression, GoAway, generation-complete signaling, and small PCM chunks for low latency.

## Avatar

The supplied asset is bundled at `app/src/main/assets/avatar/elina.vrm`. Inspection of the supplied file found VRM 0.0 metadata, 54 humanoid bones, spring-bone data, and standard A/I/U/E/O and emotion blendshapes. `avatar_manifest.json` is generated from the actual asset. There is intentionally no runtime VRM picker/import UI.

The avatar renderer uses the mature `@pixiv/three-vrm` WebGL runtime inside an Android WebView; the APK bundles the VRM itself. The renderer loads the runtime from jsDelivr at runtime, so the 3D avatar requires network access unless those JS packages are later vendored into `app/src/main/assets`.

**Important licensing note:** the supplied VRM advertises `Redistribution_Prohibited`. The file is included here because it was explicitly supplied for this build, but public distribution of the resulting APK should only happen if the avatar creator's license permits redistribution.

## Android capability boundaries

The Accessibility Service executes only the allow-listed actions implemented in `CommandRouter` and reports actual success/failure. Background custom hotwording uses Android's `VoiceInteractionService` architecture; exact always-listening wake behavior remains system/OEM dependent and is surfaced in Settings rather than faked. No hidden background microphone recorder is included.

No phone calls, SMS, WhatsApp, contacts, or related permissions/code are included.
