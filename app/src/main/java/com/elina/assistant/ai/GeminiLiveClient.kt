package com.elina.assistant.ai

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class LiveCredential { data class ApiKey(val value:String):LiveCredential(); data class EphemeralToken(val value:String):LiveCredential() }
data class LiveConfig(val model:String="gemini-3.1-flash-live-preview",val voice:String="Aoede",val thinking:String="minimal",val systemInstruction:String)

class GeminiLiveClient(private val scope:CoroutineScope,private val events:(LiveEvent)->Unit){
    private val http=OkHttpClient.Builder().readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(20,TimeUnit.SECONDS).build()
    private var ws:WebSocket?=null
    @Volatile private var setupDone=false
    fun connect(credential:LiveCredential,config:LiveConfig,resumeHandle:String?=null){
        setupDone=false
        val url=when(credential){
            is LiveCredential.ApiKey->"wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${credential.value}"
            is LiveCredential.EphemeralToken->"wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained?access_token=${credential.value}"
        }
        ws?.cancel()
        ws=http.newWebSocket(Request.Builder().url(url).build(),object:WebSocketListener(){
            override fun onOpen(w:WebSocket,response:Response){sendSetup(w,config,resumeHandle)}
            override fun onMessage(w:WebSocket,text:String){events(LiveMessageParser.parse(text))}
            override fun onFailure(w:WebSocket,t:Throwable,response:Response?){events(LiveEvent(rawError=t.message?:"WebSocket failure"))}
            override fun onClosed(w:WebSocket,code:Int,reason:String){events(LiveEvent(rawError="WebSocket closed: $code $reason"))}
        })
    }
    private fun sendSetup(w:WebSocket,c:LiveConfig,h:String?){
        val generation=JSONObject().put("responseModalities",JSONArray().put("AUDIO"))
            .put("speechConfig",JSONObject().put("voiceConfig",JSONObject().put("prebuiltVoiceConfig",JSONObject().put("voiceName",c.voice))))
            .put("thinkingConfig",JSONObject().put("thinkingLevel",c.thinking.uppercase()))
        val setupCfg=JSONObject().put("model","models/${c.model}")
            .put("generationConfig",generation)
            .put("systemInstruction",JSONObject().put("parts",JSONArray().put(JSONObject().put("text",c.systemInstruction))))
            .put("tools",ToolRegistry.declarations())
            .put("sessionResumption",JSONObject().apply{if(!h.isNullOrBlank())put("handle",h)})
            .put("contextWindowCompression",JSONObject().put("slidingWindow",JSONObject()))
            .put("inputAudioTranscription",JSONObject())
            .put("outputAudioTranscription",JSONObject())
        w.send(JSONObject().put("setup",setupCfg).toString())
    }
    fun markSetupDone(){setupDone=true}
    fun sendAudio(bytes:ByteArray){if(setupDone)ws?.send(JSONObject().put("realtimeInput",JSONObject().put("audio",JSONObject().put("data",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("mimeType","audio/pcm;rate=16000"))).toString())}
    fun sendText(text:String){if(setupDone)ws?.send(JSONObject().put("realtimeInput",JSONObject().put("text",text)).toString())}
    fun sendToolResponses(responses:JSONArray){if(setupDone)ws?.send(JSONObject().put("toolResponse",JSONObject().put("functionResponses",responses)).toString())}
    fun close(){ws?.close(1000,"client shutdown");ws=null;setupDone=false}
}
