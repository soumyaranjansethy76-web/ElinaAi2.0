package com.elina.assistant.voice

import kotlinx.coroutines.flow.Flow

/**
 * Push-to-talk ASR contract. Implementations may be on-device (sherpa-onnx)
 * or cloud (e.g. 阿里云 / 火山 / OpenAI Whisper).
 *
 * State: idle → listening (after [start]) → idle (after [stop]).
 * The implementation emits partial recognition while listening, and a final
 * recognition shortly after [stop].
 */
interface AsrManager {
    val events: Flow<AsrEvent>
    suspend fun init()
    fun start()
    fun stop()
    fun release()
}

sealed class AsrEvent {
    /** Mic opened, ready to capture. */
    data object Started : AsrEvent()
    /** In-flight recognition while user is still speaking. */
    data class Partial(val text: String) : AsrEvent()
    /** Final recognized text after [AsrManager.stop]. */
    data class Final(val text: String) : AsrEvent()
    /** Mic closed. */
    data object Stopped : AsrEvent()
    /** Recognition failed. */
    data class Error(val message: String, val cause: Throwable? = null) : AsrEvent()
}
