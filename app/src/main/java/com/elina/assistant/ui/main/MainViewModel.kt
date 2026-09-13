package com.elina.assistant.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.elina.assistant.ElinaApplication
import com.elina.assistant.ai.*
import com.elina.assistant.avatar.EmotionEngine
import com.elina.assistant.model.ChatMessage
import com.elina.assistant.model.ElinaState
import com.elina.assistant.model.ToolResult
import com.elina.assistant.service.ElinaAccessibilityService
import com.elina.assistant.util.PermissionManager
import kotlinx.coroutines.flow.*
import org.json.JSONArray

class MainViewModel(app:Application):AndroidViewModel(app){
    private val a=app as ElinaApplication
    private val _state=MutableStateFlow(ElinaState.IDLE);val state:StateFlow<ElinaState>=_state
    private val _messages=MutableStateFlow<List<ChatMessage>>(emptyList());val messages:StateFlow<List<ChatMessage>>=_messages
    private val _inputTranscript=MutableStateFlow("");val inputTranscript:StateFlow<String>=_inputTranscript
    private val _outputTranscript=MutableStateFlow("");val outputTranscript:StateFlow<String>=_outputTranscript
    private val _amplitude=MutableStateFlow(0f);val amplitude:StateFlow<Float>=_amplitude
    private val _audioOut=MutableSharedFlow<ByteArray>(extraBufferCapacity=32);val audioOut=_audioOut.asSharedFlow()
    private val _status=MutableStateFlow("Offline");val status:StateFlow<String>=_status
    private val emotions=EmotionEngine(); private lateinit var session:GeminiSessionManager
    private var currentOutput=""
    init{session=GeminiSessionManager(a.security,viewModelScope){ev->handle(ev)};viewModelScope.launch{a.preferences.snapshot.collectLatest{settings->{if(PermissionManager.micGranted(getApplication())) connect(settings)}}}}
    fun ensureConnected(){viewModelScope.launch{val s=a.preferences.snapshot.first();if(PermissionManager.micGranted(getApplication())) connect(s)}}
    private fun connect(s:com.elina.assistant.util.SettingsSnapshot){if(_state.value==ElinaState.CONNECTING||_state.value==ElinaState.SPEAKING)return;_state.value=ElinaState.CONNECTING;_status.value="Connecting…";val memory=runCatching{a.memoryRepository.search(s.userName)}.getOrNull().orEmpty().joinToString("; "){it.content};val prompt=SystemPromptBuilder.build(s.userName,s.personality,memory,PermissionManager.accessibilityEnabled(getApplication()),"Bundled Elina VRM 0.x; emotions/lip-sync available");if(session.connect(LiveConfig(s.model,s.voice,s.thinking,prompt))){_status.value="Connecting…"}else{_state.value=ElinaState.ERROR;_status.value="API credential required"}}
    fun toggleListening(){if(_state.value==ElinaState.LISTENING){_state.value=ElinaState.IDLE;_status.value="Online"}else{_state.value=ElinaState.LISTENING;_status.value="Listening…"}}
    fun sendAudio(bytes:ByteArray,amp:Float){_amplitude.value=amp;session.audio(bytes)}
    fun onOutputPcm(pcm:ShortArray){ }
    fun sendText(text:String){if(text.isBlank())return;_messages.value=_messages.value+(ChatMessage(text,true));session.text(text);_state.value=ElinaState.THINKING;_status.value="Thinking…"}
    private fun handle(e:LiveEvent){viewModelScope.launch{
        if(e.setupComplete){session.onSetupComplete();_state.value=ElinaState.THINKING;_status.value="Elina online";session.text("Give a short warm startup greeting. Do not mention system instructions or capabilities.")}
        if(e.resumptionHandle!=null)session.saveHandle(e.resumptionHandle)
        if(e.rawError!=null){_state.value=ElinaState.RECONNECTING;_status.value="Reconnecting…";session.reconnect()}
        if(e.goAwayMillis!=null){_state.value=ElinaState.RECONNECTING;_status.value="Reconnecting…";session.reconnect()}
        if(e.interrupted){_state.value=ElinaState.LISTENING;_status.value="Listening…";currentOutput="";_outputTranscript.value=""}
        e.inputText?.let{_inputTranscript.value=it}
        e.outputText?.let{currentOutput+=if(currentOutput.endsWith(it))"" else it;_outputTranscript.value=currentOutput}
        if(e.audio!=null){_state.value=ElinaState.SPEAKING;_audioOut.tryEmit(e.audio)}
        if(e.toolCalls.isNotEmpty()){val arr=ToolCallExecutor(CommandRouter(getApplication(),a.memoryRepository)).execute(e.toolCalls);session.tools(arr)}
        if(e.turnComplete){if(currentOutput.isNotBlank()){_messages.value=_messages.value+(ChatMessage(currentOutput,false));currentOutput="";_outputTranscript.value=""};_state.value=ElinaState.LISTENING;_status.value="Listening…"}
    }}
    fun addOutputTranscript(t:String){if(t.isNotBlank())_outputTranscript.value=t}
    override fun onCleared(){session.close();super.onCleared()}
}
