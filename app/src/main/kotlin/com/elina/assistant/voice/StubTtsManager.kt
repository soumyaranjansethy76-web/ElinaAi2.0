package com.elina.assistant.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Placeholder TTS that segments text by sentence punctuation and emits
 * SpeakingStart events with realistic timing — but produces no audio.
 *
 * Replace with [SherpaTtsManager] (TODO) once native libs / models are wired.
 *
 * To wire sherpa-onnx TTS:
 *   1. libsherpa-onnx-jni.so already in jniLibs (shared with ASR)
 *   2. Drop a VITS / Matcha-TTS model into app/src/main/assets/models/tts/
 *   3. Use OfflineTts.generateWithCallback for streaming PCM
 *   4. Pipe PCM into AudioTrack(16kHz mono, MODE_STREAM)
 */
class StubTtsManager : TtsManager {

    private val _events = MutableSharedFlow<TtsEvent>(replay = 0, extraBufferCapacity = 16)
    override val events: SharedFlow<TtsEvent> = _events

    private val scope = CoroutineScope(Dispatchers.Default)
    private var pending = StringBuilder()
    private var playJob: Job? = null
    private val sentenceTerminators = setOf('。', '！', '？', '.', '!', '?', '\n')

    override suspend fun init() { /* no-op */ }

    override fun feed(textChunk: String) {
        pending.append(textChunk)
        drainSentences()
    }

    override fun flush() {
        if (pending.isNotBlank()) {
            speakSentence(pending.toString().trim())
            pending = StringBuilder()
        }
    }

    override fun interrupt() {
        playJob?.cancel()
        pending = StringBuilder()
    }

    override fun release() {
        playJob?.cancel()
    }

    private fun drainSentences() {
        var lastCut = 0
        for (i in pending.indices) {
            if (pending[i] in sentenceTerminators) {
                val sentence = pending.substring(lastCut, i + 1).trim()
                if (sentence.isNotEmpty()) speakSentence(sentence)
                lastCut = i + 1
            }
        }
        if (lastCut > 0) {
            pending = StringBuilder(pending.substring(lastCut))
        }
    }

    private fun speakSentence(sentence: String) {
        playJob = scope.launch {
            _events.emit(TtsEvent.SpeakingStart(sentence))
            // Simulate playback time proportional to sentence length.
            val ms = (sentence.length * 80L).coerceAtLeast(400L)
            delay(ms)
            _events.emit(TtsEvent.SpeakingEnd)
        }
    }
}
