# V20 Live fix

Based on Google's current Gemini Live API documentation and Google Gen AI Kotlin SDK examples.

Changes:
- WebSocket API-key auth moved to `x-goog-api-key` header.
- Live response watchdog prevents indefinite Thinking state.
- Direct Gemini REST fallback returns a text answer if Live produces no response.
- Live-produced response detection avoids duplicate fallback responses.
- Official `com.google.genai:google-genai-kotlin:1.1.0` dependency is included for the supported Kotlin Live API path.
