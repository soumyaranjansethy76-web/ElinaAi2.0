package com.elina.assistant.ai

import android.util.Base64
import org.json.JSONObject

data class LiveEvent(val inputText:String?=null,val outputText:String?=null,val audio:ByteArray?=null,val interrupted:Boolean=false,val turnComplete:Boolean=false,val generationComplete:Boolean=false,val setupComplete:Boolean=false,val resumptionHandle:String?=null,val goAwayMillis:Long?=null,val toolCalls:List<ToolCallEvent> = emptyList(),val rawError:String?=null)
data class ToolCallEvent(val id:String,val name:String,val args:JSONObject)

object LiveMessageParser {
    fun parse(text:String):LiveEvent=runCatching{
        val o=JSONObject(text); var ev=LiveEvent()
        if(o.has("setupComplete")) ev=ev.copy(setupComplete=true)
        o.optJSONObject("sessionResumptionUpdate")?.let{if(it.optBoolean("resumable")&&it.optString("newHandle").isNotBlank()) ev=ev.copy(resumptionHandle=it.optString("newHandle"))}
        o.optJSONObject("goAway")?.let{val left=it.optString("timeLeft").removeSuffix("ms").toLongOrNull() ?: 0L; ev=ev.copy(goAwayMillis=left)}
        val calls=o.optJSONObject("toolCall")?.optJSONArray("functionCalls")
        if(calls!=null){val list=buildList{for(i in 0 until calls.length()){val c=calls.getJSONObject(i);add(ToolCallEvent(c.optString("id"),c.getString("name"),c.optJSONObject("args")?:JSONObject()))}};ev=ev.copy(toolCalls=list)}
        o.optJSONObject("serverContent")?.let{c->
            ev=ev.copy(interrupted=c.optBoolean("interrupted"),turnComplete=c.optBoolean("turnComplete"),generationComplete=c.optBoolean("generationComplete"))
            c.optJSONObject("inputTranscription")?.optString("text")?.takeIf{it.isNotBlank()}?.let{x->ev=ev.copy(inputText=x)}
            c.optJSONObject("outputTranscription")?.optString("text")?.takeIf{it.isNotBlank()}?.let{x->ev=ev.copy(outputText=x)}
            c.optJSONArray("modelTurn")?.let{parts->for(i in 0 until parts.length()){val part=parts.optJSONObject(i)?:continue;part.optJSONObject("inlineData")?.let{d->val mt=d.optString("mimeType");if(mt.startsWith("audio/")){ev=ev.copy(audio=Base64.decode(d.optString("data"),Base64.NO_WRAP))}};part.optString("text").takeIf{it.isNotBlank()}?.let{x->ev=ev.copy(outputText=(ev.outputText.orEmpty()+x))}}}
        }
        ev
    }.getOrElse{LiveEvent(rawError=it.message?:"Malformed Live API message")}
}
