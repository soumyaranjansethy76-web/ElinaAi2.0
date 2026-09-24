package com.elina.assistant.live

/**
 * The single source of truth for what the Gemini Live session is doing right now.
 *
 * [GeminiLiveManager] is the only thing that transitions this state. Every other layer
 * (ChatViewModel, MainActivity, the avatar bridge) only ever *observes* it — nobody else
 * is allowed to guess or optimistically set a state, so the UI can never show something
 * that isn't actually true of the connection.
 *
 *   DISCONNECTED ──[connect()]──▶ CONNECTING ──[setupComplete]──▶ READY
 *   READY ──[startMic()]──▶ LISTENING ──[stopMic()]──▶ THINKING
 *   THINKING ──[model reply]──▶ SPEAKING ──[turnComplete]──▶ READY | LISTENING
 *   * ──[unrecoverable error]──▶ ERROR (needs a manual retry)
 *   * ──[socket drop]──▶ CONNECTING (auto-reconnect) or ERROR (attempts exhausted)
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    READY,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR,
}
