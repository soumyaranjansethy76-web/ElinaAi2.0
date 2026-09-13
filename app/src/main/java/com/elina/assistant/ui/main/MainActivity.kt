package com.elina.assistant.ui.main

import android.Manifest
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.elina.assistant.ElinaApplication
import com.elina.assistant.ai.AudioEngine
import com.elina.assistant.avatar.BlinkController
import com.elina.assistant.avatar.EmotionController
import com.elina.assistant.avatar.EmotionEngine
import com.elina.assistant.databinding.ActivityMainBinding
import com.elina.assistant.model.ElinaState
import com.elina.assistant.ui.settings.SettingsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity:AppCompatActivity(){
    private lateinit var b:ActivityMainBinding
    private val vm:MainViewModel by viewModels()
    private lateinit var audio:AudioEngine
    private val askMic=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->{if(!granted)Toast.makeText(this,"Microphone permission is required for live voice.",Toast.LENGTH_LONG).show() else vm.ensureConnected()}}
    private val adapter=ChatAdapter()
    override fun onCreate(saved:Bundle?){super.onCreate(saved);b=ActivityMainBinding.inflate(layoutInflater);setContentView(b.root)
        b.chat.layoutManager=LinearLayoutManager(this);b.chat.adapter=adapter
        if(!com.elina.assistant.util.PermissionManager.micGranted(this))askMic.launch(Manifest.permission.RECORD_AUDIO)
        audio=AudioEngine(lifecycleScope,{bytes,amp->vm.sendAudio(bytes,amp)}, {pcm->b.avatar.setLip("aa", pcm.map{ s-> val v=s.toDouble()/32768.0; v*v }.average().let{kotlin.math.sqrt(it).toFloat().coerceIn(0f,1f)})},{}, {})
        b.settings.setOnClickListener{startActivity(android.content.Intent(this,SettingsActivity::class.java))}
        b.mic.setOnTouchListener{_,e->when(e.action){MotionEvent.ACTION_DOWN->{if(com.elina.assistant.util.PermissionManager.micGranted(this)){audio.interruptPlayback();vm.toggleListening();audio.startRecording()};true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{audio.stopRecording();vm.toggleListening();true};else->true}}
        lifecycleScope.launch{vm.state.collectLatest{state->b.status.text=when(state){ElinaState.IDLE->"Elina online";ElinaState.LISTENING->"Listening…";ElinaState.THINKING->"Thinking…";ElinaState.SPEAKING->"Speaking…";ElinaState.INTERRUPTED->"Interrupted";ElinaState.CONNECTING->"Connecting…";ElinaState.RECONNECTING->"Reconnecting…";ElinaState.ERROR->"Needs attention"}}}
        lifecycleScope.launch{vm.messages.collectLatest{adapter.submit(it);b.chat.scrollToPosition(adapter.itemCount-1)}}
        lifecycleScope.launch{vm.inputTranscript.collectLatest{t->if(t.isNotBlank())b.transcript.text=t}}
        lifecycleScope.launch{vm.outputTranscript.collectLatest{t->if(t.isNotBlank()){b.transcript.text=t;val e=EmotionEngine().classify(t);b.avatar.setEmotion(e.emotion.name.lowercase(),e.strength)}}}
        lifecycleScope.launch{vm.amplitude.collectLatest{b.waveform.setAmplitude(it)}}
        lifecycleScope.launch{vm.audioOut.collectLatest{bytes->audio.queueAudio(bytes);b.avatar.setLip("aa",.6f)}}
    }
    override fun onResume(){super.onResume();vm.ensureConnected()}
    override fun onDestroy(){audio.release();b.avatar.release();super.onDestroy()}
}
