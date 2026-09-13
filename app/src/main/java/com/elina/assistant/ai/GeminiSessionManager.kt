package com.elina.assistant.ai

import com.elina.assistant.util.SecurityManager
import kotlinx.coroutines.*
import org.json.JSONArray

class GeminiSessionManager(private val security:SecurityManager,private val scope:CoroutineScope,private val onEvent:(LiveEvent)->Unit):AutoCloseable{
    private var client=GeminiLiveClient(scope,onEvent)
    private var lastConfig:LiveConfig?=null
    private var lastHandle:String?=null
    private var reconnectJob:Job?=null
    fun connect(config:LiveConfig):Boolean{val key=security.readApiCredential()?:return false;lastConfig=config;client.connect(LiveCredential.ApiKey(key),config,lastHandle);return true}
    fun connectEphemeral(token:String,config:LiveConfig){lastConfig=config;client.connect(LiveCredential.EphemeralToken(token),config,lastHandle)}
    fun audio(b:ByteArray)=client.sendAudio(b)
    fun text(t:String)=client.sendText(t)
    fun tools(r:JSONArray)=client.sendToolResponses(r)
    fun onSetupComplete()=client.markSetupDone()
    fun saveHandle(h:String){lastHandle=h}
    fun reconnect(){reconnectJob?.cancel();reconnectJob=scope.launch{delay(350);lastConfig?.let{if(!connect(it)){} }} }
    override fun close(){reconnectJob?.cancel();client.close()}
}
