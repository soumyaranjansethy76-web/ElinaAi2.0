package com.elina.assistant.voice

import kotlinx.coroutines.flow.Flow

/**
 * Streaming TTS contract. Implementations should support **sentence-level
 * incremental synthesis** so the avatar can begin speaking before the LLM
 * finishes generating the full reply.
 *
 * Typical flow:
 *   1. [feed] is called repeatedly with text chunks as the LLM streams tokens.
 *   2. The implementation segments incoming text into sentences (by punctuation)
 *      and synthesizes each sentence to PCM in a background coroutine.
 *   3. [events] emits SpeakingStart per sentence and SpeakingEnd when the
 *      audio queue drains.
 *   4. [flush] should be called when the LLM stream ends, to flush any
 *      partial trailing sentence.
 *   5. [interrupt] cancels playback (e.g. when user starts a new question).
 */
interface TtsManager {
    val events: Flow<TtsEvent>

    /** Stable identifier for which voice/provider this is (e.g. "android-system-tts"), so a
     * caller or log can tell which one actually spoke once more than one exists. */
    val providerId: String get() = "unknown"

    suspend fun init()
    fun feed(textChunk: String)
    fun flush()

    /**
     * Speaks one complete, already-finished piece of text — for callers that have a whole
     * reply up front (e.g. the REST fallback path) rather than a token stream. [languageHint]
     * is a best-effort BCP-47-ish tag ("hi", "ja", "or", "en", ...) or plain language name; null
     * means "use whatever voice/locale is already active". Never throws — an unsupported/
     * missing locale falls back to the current voice and reports a [TtsEvent.Error] instead of
     * failing silently or blocking the caller. Default implementation ignores the hint and just
     * feeds+flushes; only [AndroidTtsManager] currently honors it.
     */
    fun speak(text: String, languageHint: String? = null) {
        feed(text)
        flush()
    }

    /**
     * True only if this provider has a REAL, currently-installed voice for [languageHint] —
     * never an optimistic guess. Exists so a caller (or a future settings screen) can check
     * "is Odia actually supported?" before speaking, instead of finding out from a failure.
     * The default is permissive (assumes yes) purely so providers that never need this check
     * still compile without overriding it — any real implementation MUST answer honestly.
     */
    fun supportsLanguage(languageHint: String): Boolean = true

    fun interrupt()
    fun release()
}

sealed class TtsEvent {
    data class SpeakingStart(val sentence: String) : TtsEvent()
    data object SpeakingEnd : TtsEvent()
    data class Error(val message: String, val cause: Throwable? = null) : TtsEvent()
}
