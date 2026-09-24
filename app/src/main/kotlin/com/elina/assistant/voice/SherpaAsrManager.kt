package com.elina.assistant.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.elina.assistant.util.logE
import com.elina.assistant.util.logI
import com.elina.assistant.util.logW
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Sherpa-ONNX powered offline ASR for push-to-talk.
 *
 * Records 16 kHz mono PCM via AudioRecord while [start] is active, stops on
 * [stop], decodes the captured buffer with [OfflineRecognizer], and emits a
 * single [AsrEvent.Final] with the recognized text.
 *
 * Model layout — see [SherpaModelLayout]. Auto-detects between Paraformer and
 * Zipformer transducer based on which files are present.
 *
 * Uses RECORD_AUDIO permission. Caller is responsible for ensuring the
 * permission is granted before [start] is called.
 */
class SherpaAsrManager(
    private val context: Context,
) : AsrManager {

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val FRAME_SAMPLES = 1024
    }

    private val _events = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<AsrEvent> = _events

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var captureJob: Job? = null
    @Volatile private var captureBuffer: ArrayList<Float>? = null
    @Volatile private var audioRecord: AudioRecord? = null

    override suspend fun init() = withContext(Dispatchers.IO) {
        if (recognizer != null) return@withContext
        val asrDir = SherpaModelLayout.asrDir(context)
        val tokens = File(asrDir, "tokens.txt").absolutePath

        val modelConfig: OfflineModelConfig = when {
            File(asrDir, "model.onnx").exists() -> OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = File(asrDir, "model.onnx").absolutePath,
                ),
                tokens = tokens,
                modelType = "paraformer",
                numThreads = 2,
                debug = false,
            )

            File(asrDir, "encoder.onnx").exists() &&
                File(asrDir, "decoder.onnx").exists() &&
                File(asrDir, "joiner.onnx").exists() -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(asrDir, "encoder.onnx").absolutePath,
                    decoder = File(asrDir, "decoder.onnx").absolutePath,
                    joiner = File(asrDir, "joiner.onnx").absolutePath,
                ),
                tokens = tokens,
                modelType = "transducer",
                numThreads = 2,
                debug = false,
            )

            else -> error("No ASR model found in $asrDir — see scripts/setup-sherpa.sh")
        }

        val recognizerConfig = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(assetManager = null, config = recognizerConfig)
        logI("SherpaAsr ready")
    }

    @Suppress("MissingPermission")
    override fun start() {
        val rec = recognizer ?: run {
            scope.launch { _events.emit(AsrEvent.Error("ASR not initialized")) }
            return
        }
        captureJob?.cancel()
        captureBuffer = ArrayList(SAMPLE_RATE * 6) // 6s headroom

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(SAMPLE_RATE * 2)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                minBuf,
            )
        } catch (e: SecurityException) {
            scope.launch { _events.emit(AsrEvent.Error("Mic permission missing", e)) }
            return
        }
        audioRecord = record

        captureJob = scope.launch {
            try {
                record.startRecording()
                _events.emit(AsrEvent.Started)
                val pcm = ShortArray(FRAME_SAMPLES)
                while (isActive) {
                    val read = record.read(pcm, 0, pcm.size)
                    if (read <= 0) continue
                    val buffer = captureBuffer ?: break
                    for (i in 0 until read) {
                        buffer.add(pcm[i] / 32768f)
                    }
                }
            } catch (e: Exception) {
                logE("ASR capture loop failed", e)
                _events.emit(AsrEvent.Error(e.message ?: "capture failed", e))
            }
        }
    }

    override fun stop() {
        scope.launch {
            try {
                val record = audioRecord
                captureJob?.cancel()
                try { record?.stop() } catch (_: Exception) {}
                try { record?.release() } catch (_: Exception) {}
                audioRecord = null

                val samples = captureBuffer?.toFloatArray() ?: FloatArray(0)
                captureBuffer = null
                _events.emit(AsrEvent.Stopped)

                if (samples.size < SAMPLE_RATE / 4) {
                    // Less than 0.25s — too short to be a real utterance.
                    _events.emit(AsrEvent.Final(""))
                    return@launch
                }

                val rec = recognizer ?: run {
                    _events.emit(AsrEvent.Error("ASR not initialized"))
                    return@launch
                }

                val text = withContext(Dispatchers.Default) {
                    val stream = rec.createStream()
                    try {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        rec.decode(stream)
                        rec.getResult(stream).text
                    } finally {
                        stream.release()
                    }
                }
                logI("ASR final: $text")
                _events.emit(AsrEvent.Final(text))
            } catch (e: Exception) {
                logE("ASR decode failed", e)
                _events.emit(AsrEvent.Error(e.message ?: "decode failed", e))
            }
        }
    }

    override fun release() {
        captureJob?.cancel()
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        recognizer?.release()
        recognizer = null
        scope.cancel()
    }
}
