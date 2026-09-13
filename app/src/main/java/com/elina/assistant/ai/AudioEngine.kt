package com.elina.assistant.ai

import android.media.*
import android.os.Process
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

class AudioEngine(
    private val scope: CoroutineScope,
    private val onInputPcm: (ByteArray, Float) -> Unit,
    private val onOutputPcm: (ShortArray) -> Unit,
    private val onSpeakingChanged: (Boolean) -> Unit,
    private val onInterrupted: () -> Unit
) {
    private val recording=AtomicBoolean(false); private var record:AudioRecord?=null; private var recordJob:Job?=null
    private val outputQueue=Channel<ByteArray>(Channel.UNLIMITED)
    private val playbackJob=scope.launch(Dispatchers.IO){ for(bytes in outputQueue){ if(playback.playState!=AudioTrack.PLAYSTATE_PLAYING)playback.play(); playback.write(bytes,0,bytes.size,AudioTrack.WRITE_BLOCKING); val s=ShortArray(bytes.size/2); java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s); onOutputPcm(s) } }
    private val playback=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(24000).setTransferMode(AudioTrack.MODE_STREAM).build()
    @Volatile private var muted=false
    fun startRecording(){ if(recording.getAndSet(true))return; val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(2048); record=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,min); record?.startRecording(); onSpeakingChanged(false); recordJob=scope.launch(Dispatchers.IO){Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO); val buf=ByteArray(1280); while(isActive&&recording.get()){val n=record?.read(buf,0,buf.size,AudioRecord.READ_BLOCKING)?:0;if(n>0&&!muted){val chunk=buf.copyOf(n); onInputPcm(chunk,rms(chunk));}}} }
    fun stopRecording(){recording.set(false);recordJob?.cancel();recordJob=null;runCatching{record?.stop()};runCatching{record?.release()};record=null}
    fun startPlayback(){if(playback.playState!=AudioTrack.PLAYSTATE_PLAYING)playback.play()}
    fun stopPlayback(){if(playback.playState==AudioTrack.PLAYSTATE_PLAYING)playback.pause()}
    fun queueAudio(pcm:ByteArray){outputQueue.trySend(pcm)}
    fun interruptPlayback(){playback.pause();playback.flush();onInterrupted()}
    fun setMuted(v:Boolean){muted=v}
    fun release(){stopRecording();outputQueue.close();playbackJob.cancel();runCatching{playback.stop()};playback.release()}
    private fun rms(b:ByteArray):Float{var sum=0.0;var i=0;while(i+1<b.size){val s=((b[i+1].toInt() shl 8) or (b[i].toInt() and 255)).toShort()/32768.0;sum+=s*s;i+=2};return sqrt(sum/max(1,b.size/2)).toFloat().coerceIn(0f,1f)}
}
