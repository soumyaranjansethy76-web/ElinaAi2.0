package com.elina.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.Locale

class AndroidAsrManager(private val context: Context) : AsrManager {
    private val _events = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<AsrEvent> = _events
    private var recognizer: SpeechRecognizer? = null

    override suspend fun init() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _events.tryEmit(AsrEvent.Error("Speech recognition is not available on this device"))
            return
        }
        val main = android.os.Handler(Looper.getMainLooper())
        main.post {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { _events.tryEmit(AsrEvent.Started) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { _events.tryEmit(AsrEvent.Stopped) }
                    override fun onError(error: Int) { _events.tryEmit(AsrEvent.Error("Speech recognition error: $error")) }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) _events.tryEmit(AsrEvent.Final(text))
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        if (text.isNotBlank()) _events.tryEmit(AsrEvent.Partial(text))
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    override fun start() {
        android.os.Handler(Looper.getMainLooper()).post {
            val r = recognizer ?: return@post
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            r.startListening(intent)
        }
    }

    override fun stop() { android.os.Handler(Looper.getMainLooper()).post { recognizer?.stopListening() } }

    override fun release() {
        android.os.Handler(Looper.getMainLooper()).post {
            recognizer?.destroy()
            recognizer = null
        }
    }
}
