package com.elina.assistant.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.elina.assistant.util.logE
import com.elina.assistant.util.logI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sherpa-ONNX powered offline TTS with **sentence-level streaming**.
 *
 * The visitor's experience: as the LLM streams tokens, [feed] is called in
 * real time. We segment the running text by punctuation; each completed
 * sentence is dispatched to the synthesizer + AudioTrack queue immediately,
 * so the avatar starts speaking the first sentence before the LLM finishes.
 *
 * Uses VITS Chinese models. Sample rate is read from the model.
 */
class SherpaTtsManager(
    private val context: Context,
) : TtsManager {

    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<TtsEvent> = _events

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var tts: OfflineTts? = null
    @Volatile private var sampleRate: Int = 24_000
    @Volatile private var audioTrack: AudioTrack? = null

    private val pending = StringBuilder()
    private val sentenceTerminators = setOf('。', '！', '？', '.', '!', '?', '\n', ';', '；')

    /** Queue of sentences awaiting synthesis. */
    private val sentenceQueue = Channel<String>(capacity = Channel.UNLIMITED)
    private var workerJob: Job? = null
    private var currentSpeakJob: Job? = null

    override suspend fun init() = withContext(Dispatchers.IO) {
        if (tts != null) return@withContext
        val ttsDir = SherpaModelLayout.ttsDir(context)
        val modelPath = File(ttsDir, "model.onnx")
        val tokensPath = File(ttsDir, "tokens.txt")
        check(modelPath.exists() && tokensPath.exists()) {
            "TTS model files missing in $ttsDir — see scripts/setup-sherpa.sh"
        }

        val lexiconFile = File(ttsDir, "lexicon.txt")
        val dataDir = File(ttsDir, "espeak-ng-data")

        val ttsConfig = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelPath.absolutePath,
                    lexicon = if (lexiconFile.exists()) lexiconFile.absolutePath else "",
                    tokens = tokensPath.absolutePath,
                    dataDir = if (dataDir.exists()) dataDir.absolutePath else "",
                    noiseScale = 0.667f,
                    noiseScaleW = 0.8f,
                    lengthScale = 1.0f,
                ),
                numThreads = 2,
                debug = false,
            ),
        )
        tts = OfflineTts(assetManager = null, config = ttsConfig)
        sampleRate = tts!!.sampleRate()
        startWorker()
        logI("SherpaTts ready (sampleRate=$sampleRate)")
    }

    override fun feed(textChunk: String) {
        pending.append(textChunk)
        drainSentences()
    }

    override fun flush() {
        if (pending.isNotBlank()) {
            sentenceQueue.trySend(pending.toString().trim())
            pending.clear()
        }
    }

    override fun interrupt() {
        currentSpeakJob?.cancel()
        // Drain pending sentences
        while (sentenceQueue.tryReceive().isSuccess) { /* drop */ }
        pending.clear()
        try { audioTrack?.pause(); audioTrack?.flush() } catch (_: Exception) {}
    }

    override fun release() {
        scope.launch {
            workerJob?.cancelAndJoin()
            currentSpeakJob?.cancelAndJoin()
            try { audioTrack?.stop() } catch (_: Exception) {}
            try { audioTrack?.release() } catch (_: Exception) {}
            audioTrack = null
            tts?.release()
            tts = null
            scope.cancel()
        }
    }

    private fun drainSentences() {
        var lastCut = 0
        for (i in pending.indices) {
            if (pending[i] in sentenceTerminators) {
                val sentence = pending.substring(lastCut, i + 1).trim()
                if (sentence.isNotEmpty()) sentenceQueue.trySend(sentence)
                lastCut = i + 1
            }
        }
        if (lastCut > 0) {
            val tail = pending.substring(lastCut)
            pending.clear(); pending.append(tail)
        }
    }

    private fun startWorker() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            for (sentence in sentenceQueue) {
                speakSentence(sentence)
            }
        }
    }

    private suspend fun speakSentence(sentence: String) {
        val engine = tts ?: return
        try {
            _events.emit(TtsEvent.SpeakingStart(sentence))
            ensureAudioTrack()
            val track = audioTrack ?: return
            track.play()

            currentSpeakJob = scope.launch {
                // Capture this coroutine's context so the (non-suspending)
                // synth callback can check cancellation without a CoroutineScope receiver.
                val ctx = coroutineContext
                // generateWithCallback delivers PCM samples in chunks as they
                // are produced; we write them to AudioTrack live.
                engine.generateWithCallback(text = sentence) { samples ->
                    if (!ctx.isActive) return@generateWithCallback 0
                    if (samples.isEmpty()) return@generateWithCallback 1
                    val shortPcm = ShortArray(samples.size)
                    for (i in samples.indices) {
                        val v = (samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
                        shortPcm[i] = v.toShort()
                    }
                    track.write(shortPcm, 0, shortPcm.size, AudioTrack.WRITE_BLOCKING)
                    1 // continue
                }
            }
            currentSpeakJob?.join()

            // Drain track and signal end-of-sentence
            try { track.flush() } catch (_: Exception) {}
            _events.emit(TtsEvent.SpeakingEnd)
        } catch (e: Exception) {
            logE("TTS speak failed for sentence: $sentence", e)
            _events.emit(TtsEvent.Error(e.message ?: "speak failed", e))
        }
    }

    private fun ensureAudioTrack() {
        if (audioTrack != null) return
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }
}
