package com.elina.assistant.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import com.elina.assistant.MainActivity
import com.elina.assistant.R
import java.util.Locale

/**
 * Wake-word foundation: listens for "hey Elina" using Android's built-in SpeechRecognizer
 * while a user-enabled microphone foreground service is active.
 *
 * This is NOT a true offline/always-on wake-word engine — it depends on the platform speech
 * recognizer being available and (per Android's own rules) will not reliably survive
 * aggressive background/battery restrictions (including MIUI's). It is the safe permission,
 * service-lifecycle, and state-machine scaffold that a real local wake-word model (e.g.
 * Porcupine, a TFLite keyword spotter) can be dropped into later — see WakeWordState for the
 * lifecycle this exposes to the UI.
 */
class WakeWordService : Service() {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var consecutiveFailures = 0

    override fun onCreate() {
        super.onCreate()
        WakeWordState.update(WakeWordState.READY)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            // Fail visibly rather than starting a foreground notification that will never
            // actually detect anything.
            WakeWordState.update(WakeWordState.ERROR)
            stopSelf()
            return
        }
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Elina is listening")
            .setContentText("Say “Hey Elina” to wake the assistant")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ID, notification)
            }
        } catch (e: Exception) {
            // The platform refused to start the foreground service (e.g. a background-start
            // restriction) — surface that instead of silently doing nothing.
            WakeWordState.update(WakeWordState.ERROR)
            stopSelf()
            return
        }
        startRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!listening && WakeWordState.current != WakeWordState.ERROR) startRecognizer()
        return START_STICKY
    }

    private fun startRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also { r ->
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: android.os.Bundle) {
                        val all = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val hit = all.any { it.lowercase(Locale.ROOT).contains("hey elina") || it.lowercase(Locale.ROOT).contains("hi elina") }
                        listening = false
                        consecutiveFailures = 0
                        if (hit) {
                            WakeWordState.update(WakeWordState.WAKE_DETECTED)
                            WakeWordState.update(WakeWordState.STARTING_CONVERSATION)
                            startActivity(
                                Intent(this@WakeWordService, MainActivity::class.java)
                                    .putExtra("WAKE_UP", true)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                            )
                            stopSelf()
                        } else {
                            restartSoon()
                        }
                    }
                    override fun onError(error: Int) {
                        listening = false
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            // Permission was revoked mid-session (e.g. from Settings) —
                            // retrying would just fail again forever.
                            WakeWordState.update(WakeWordState.ERROR)
                            stopSelf()
                            return
                        }
                        consecutiveFailures++
                        if (consecutiveFailures >= 5) {
                            // Bounded: don't retry silently forever if the mic/recognizer is
                            // stuck busy or unavailable — surface it instead.
                            WakeWordState.update(WakeWordState.ERROR)
                            stopSelf()
                        } else {
                            restartSoon()
                        }
                    }
                    override fun onReadyForSpeech(params: android.os.Bundle?) { WakeWordState.update(WakeWordState.LISTENING_FOR_WAKE) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: android.os.Bundle?) {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            listening = true
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            consecutiveFailures++
            if (consecutiveFailures >= 5) {
                WakeWordState.update(WakeWordState.ERROR)
                stopSelf()
            } else {
                restartSoon()
            }
        }
    }

    private fun restartSoon() {
        android.os.Handler(mainLooper).postDelayed({ if (WakeWordState.current != WakeWordState.ERROR) startRecognizer() }, 500)
    }

    override fun onDestroy() {
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
        if (WakeWordState.current != WakeWordState.ERROR) WakeWordState.update(WakeWordState.DISABLED)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Elina wake word", NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL = "elina_wake"
        private const val ID = 7001
    }
}
