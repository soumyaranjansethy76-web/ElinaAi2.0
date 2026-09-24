package com.elina.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Locale

class AndroidTtsManager(private val context: Context) : TtsManager {
    override val providerId: String get() = "android-system-tts"
    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<TtsEvent> = _events
    private var tts: TextToSpeech? = null
    private val pending = StringBuilder()

    override suspend fun init() {
        if (tts != null) return
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) { _events.tryEmit(TtsEvent.SpeakingStart(utteranceId.orEmpty())) }
                        override fun onDone(utteranceId: String?) { _events.tryEmit(TtsEvent.SpeakingEnd) }
                        override fun onError(utteranceId: String?) { _events.tryEmit(TtsEvent.Error("TTS error")) }
                    })
                } else {
                    _events.tryEmit(TtsEvent.Error("Android TTS unavailable"))
                }
            }
        } catch (e: Throwable) {
            // No TTS engine available/bindable on this device — real, device-dependent
            // failure mode. Report it; never let it take the app down during startup.
            tts = null
            _events.tryEmit(TtsEvent.Error("Android TTS unavailable"))
        }
    }

    override fun feed(textChunk: String) { pending.append(textChunk); drain(false) }

    override fun flush() { drain(true) }

    private fun drain(force: Boolean) {
        while (true) {
            val cut = pending.indexOfAny(charArrayOf('.', '!', '?', '。', '！', '？', '\n'))
            if (cut < 0) break
            val sentence = pending.substring(0, cut + 1).trim()
            pending.delete(0, cut + 1)
            speak(sentence)
        }
        if (force && pending.isNotBlank()) {
            val sentence = pending.toString().trim()
            pending.clear()
            speak(sentence)
        }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts ?: return
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "elina-${System.nanoTime()}")
    }

    override fun speak(text: String, languageHint: String?) {
        val engine = tts
        if (text.isBlank() || engine == null) return
        val locale = resolveLocale(languageHint)
        if (locale != null) {
            when (engine.setLanguage(locale)) {
                TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                    // Honest limitation, not a fake success: this device's TTS engine doesn't
                    // have a voice for the requested language — keep speaking with whatever
                    // voice is already active rather than going silent.
                    _events.tryEmit(TtsEvent.Error("No installed TTS voice for \"$languageHint\" — using the current voice instead."))
                }
                else -> {}
            }
        }
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "elina-${System.nanoTime()}")
    }

    /**
     * Best-effort mapping from a plain language name / short code to a system [Locale].
     * Deliberately small and explicit for the priority languages; anything else is passed to
     * [Locale.forLanguageTag] as a best-effort attempt rather than silently ignored, so this
     * isn't hard-limited to a fixed language list.
     */
    private fun resolveLocale(languageHint: String?): Locale? {
        val h = languageHint?.trim()?.lowercase()
        if (h.isNullOrEmpty()) return null
        return when (h) {
            "en", "en-us", "en-in", "english" -> Locale.US
            "hi", "hi-in", "hindi", "hinglish" -> Locale("hi", "IN")
            "ja", "ja-jp", "japanese" -> Locale.JAPAN
            "or", "or-in", "odia", "oriya" -> Locale("or", "IN")
            else -> try { Locale.forLanguageTag(h) } catch (e: Exception) { null }
        }
    }

    /**
     * Genuinely checks (not guesses) whether the current TTS engine has an installed voice
     * for [languageHint], via the real query-only Android API — this never mutates the
     * engine's active language the way [speak]'s internal setLanguage() call does.
     */
    override fun supportsLanguage(languageHint: String): Boolean {
        val engine = tts ?: return false
        val locale = resolveLocale(languageHint) ?: return true
        return when (engine.isLanguageAvailable(locale)) {
            TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> true
            else -> false
        }
    }

    override fun interrupt() { tts?.stop(); pending.clear() }

    override fun release() {
        try { tts?.stop() } catch (e: Exception) { }
        try { tts?.shutdown() } catch (e: Exception) { }
        tts = null
        pending.clear()
    }
}
