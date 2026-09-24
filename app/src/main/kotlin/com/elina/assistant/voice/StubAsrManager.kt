package com.elina.assistant.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Placeholder ASR that simulates a 2-second recognition with a canned phrase.
 * Replace with [SherpaAsrManager] (TODO) once native libs and models are wired in.
 *
 * To wire sherpa-onnx:
 *   1. Drop libsherpa-onnx-jni.so into app/src/main/jniLibs/arm64-v8a/
 *   2. Drop streaming-zipformer model into app/src/main/assets/models/asr/
 *   3. Use sherpa-onnx Kotlin API (com.k2fsa.sherpa.onnx.OnlineRecognizer)
 *   4. Read mic via AudioRecord(16kHz, mono, PCM_16BIT), feed bytes into recognizer
 *   5. Emit AsrEvent.Partial as recognizer produces partial results
 *   6. Emit AsrEvent.Final on stop()
 */
class StubAsrManager : AsrManager {

    private val _events = MutableSharedFlow<AsrEvent>(replay = 0, extraBufferCapacity = 8)
    override val events: SharedFlow<AsrEvent> = _events

    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    override suspend fun init() { /* no-op for stub */ }

    override fun start() {
        job?.cancel()
        job = scope.launch {
            _events.emit(AsrEvent.Started)
            delay(400)
            _events.emit(AsrEvent.Partial("这件展品"))
            delay(500)
            _events.emit(AsrEvent.Partial("这件展品是什么"))
            delay(500)
            _events.emit(AsrEvent.Partial("这件展品是什么时候的？"))
        }
    }

    override fun stop() {
        scope.launch {
            job?.cancelAndJoin()
            _events.emit(AsrEvent.Final("这件展品是什么时候的？"))
            _events.emit(AsrEvent.Stopped)
        }
    }

    override fun release() {
        job?.cancel()
    }

    private suspend fun Job.cancelAndJoin() {
        cancel()
        try { join() } catch (_: Exception) {}
    }
}
