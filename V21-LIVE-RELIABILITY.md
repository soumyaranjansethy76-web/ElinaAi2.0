# V21 — Gemini core reliability

Phase 2 scope: the AI/network/session layer only. No avatar, device-control, or wake-word
changes. Goal: text conversation must be 100% understandable and debuggable — every state
the app can be in has to be a real, observable fact about the connection, never a guess.

## New: a single connection-state model

`live/ConnectionState.kt` (new file) — one enum, one source of truth:

```
DISCONNECTED → CONNECTING → READY → LISTENING → THINKING → SPEAKING
                     ↑                                          ↓
                     └──────────────── ERROR ───────────────────┘
```

`GeminiLiveManager` is the only thing allowed to transition this state, driven only by real
signals (a `setupComplete` frame, a server message, a timeout, a socket event) — never by
something the UI hopes is true. `ChatViewModel.state` and `MainActivity.renderState()` only
*observe* it.

## What changed and why

- **`live/ConnectionState.kt`** (new) — the 7-state model above.
- **`live/GeminiLiveManager.kt`** (rewritten) — this is where nearly all of the reliability
  work lives:
  - `looksLikeValidApiKey()` rejects an obviously-wrong key (too short / contains whitespace)
    before spending a network round trip on it; a real key is then validated for real by the
    actual connection attempt, whose auth failures are classified precisely (see below) —
    rather than a second, redundant "is this key valid" network call.
  - Every send (`sendText`, mic audio) is gated on `setupComplete`, not on "the socket
    exists." Text sent before setup finishes is queued and flushed only after Gemini's
    `setupComplete` frame arrives; the mic recorder is likewise never started before that.
  - `armSetupWatchdog()` (10s) fails the connection attempt with a specific message if
    `setupComplete` never arrives — previously there was no such gate at all.
  - `armResponseWatchdog()` (7s) starts on every turn (text send, mic release). If nothing
    answers in time, a text turn is retried through the REST fallback; a voice turn (with no
    text to retry) surfaces a clear "didn't respond in time" error. **This is the fix for the
    permanent "Thinking…" state** — the equivalent watchdog existed in v20 as
    `scheduleFallback()` but was never actually called from anywhere; it's now wired into
    every path that can enter `THINKING`.
  - `scheduleReconnectOrGiveUp()` retries a dropped connection automatically (linear backoff,
    3 attempts) before surfacing a hard, actionable `ERROR`.
  - `cancelCurrentTurn()` cancels whatever the current turn is waiting on — a Live reply or
    an in-flight REST fallback call — and returns cleanly to `LISTENING`/`READY`. Sending a
    new message while a previous one is still pending now cancels the stale one instead of
    racing it.
  - `classifyServerError()` / `classifySocketFailure()` turn raw Gemini/OkHttp errors into
    specific, user-readable text: authentication, quota, unsupported model, malformed
    request, network-unavailable, timed-out, or a labelled fallback for anything else.
  - The REST fallback (`GeminiRestFallback`) is unchanged in what it does, but is now
    actually reachable — see above.
- **`live/GeminiRestFallback.kt`** — added `cancel()` / `wasCancelled()`. Fixes a bug found
  during this phase's verification pass: cancelling an in-flight fallback call (via
  `cancelCurrentTurn()` or `close()`) made the fallback's own `catch` block treat the
  cancellation as a real failure and stomp the state with `ERROR` right after the canceller
  had already set the correct state. A cancel is now silent, as it should be.
- **`live/GeminiLiveManager.kt`**, setup-timeout path — found and fixed during verification:
  the timeout handler set `ERROR` and then closed the socket, but closing the socket fires
  `onClosed(code=1000)` moments later on another thread, which was downgrading `ERROR` back
  to `DISCONNECTED` and silently erasing the message the user had just been shown. Now marked
  terminal before the close, matching the pattern the rest of the file already used for
  server-reported errors.
- **`chat/ChatViewModel.kt`** — `state` is now `StateFlow<ConnectionState>` instead of the old
  `DialogState`; `onConnectionState()` is the single entry point that updates it. `errorMessage`
  holds the latest human-readable failure text for the UI to display alongside `ERROR`.
  `chat/DialogState.kt` (the old, separate state sealed class) is removed — one state model,
  not two.
- **`MainActivity.kt`** — `renderState()` is the one place status text is decided, and it runs
  once immediately in `onCreate()` (before anything else) as well as on every subsequent state
  change, so the static layout text ("Elina is ready") can never be shown before the real state
  is known. `DISCONNECTED` now renders as "API key required…" when no key is configured, or
  "Elina is ready" when one is — never a false "ready."
- **`util/AppConfig.kt`** — added `hasApiKey()`, used by the above.
- **`res/values/strings.xml`** — added `status_api_key_required` and `status_connecting`,
  which `MainActivity` already referenced. Their absence was a build-breaking unresolved
  resource error, caught and fixed during this phase's static-correctness pass.

## Explicitly not touched this phase

Avatar (`ui/AvatarBridge.kt`, `assets/avatar/*`), device control
(`device/DeviceActionExecutor.kt`, `service/ElinaAccessibilityService.kt`), and wake word
(`service/WakeWordService.kt`) are unchanged — out of scope for this phase by design.

## Known limitation

No Android build environment or Kotlin compiler was available to actually compile this. What
was verified: brace/paren balance in every touched file, every `R.string`/`R.id`/`R.layout`/
`R.drawable`/`R.mipmap` reference cross-checked against declared resources, and every
`GeminiLiveManager.Callback` method matched against its implementation in `MainActivity`. This
is not a substitute for `./gradlew assembleRelease` on GitHub Actions, which remains the real
verification step.

## Separate note (not part of this phase, not fixed here)

`.gitignore` excludes `app/src/main/assets/avatar/model.vrm`. If this project folder is pushed
to GitHub with a normal `git add .`, the VRM file will silently never be committed, and the
CI's `test -f app/src/main/assets/avatar/model.vrm` step will fail on a fresh clone even though
the file is present in this ZIP. Worth removing that line from `.gitignore` in a future phase
since this project bundles its own VRM rather than fetching one via `scripts/setup-avatar.sh`.
