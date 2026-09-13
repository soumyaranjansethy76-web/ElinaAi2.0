package com.elina.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

object ToolRegistry {
    fun declarations(): JSONArray = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply {
        put(fn("open_app","Open an installed application by package name or known alias",obj("packageName" to "string")))
        put(fn("go_back","Go back in the current app"))
        put(fn("go_home","Go to Android Home"))
        put(fn("click_text","Click a visible accessibility node by exact or close text",obj("text" to "string")))
        put(fn("type_text","Type text into the focused editable control",obj("text" to "string")))
        put(fn("scroll","Scroll a visible accessibility container",obj("direction" to "string")))
        put(fn("media_control","Control currently available media playback",obj("action" to "string")))
        put(fn("volume_control","Adjust media volume",obj("direction" to "string")))
        put(fn("open_settings","Open an Android Settings page",obj("page" to "string")))
        put(fn("memory_store","Store a user-approved local memory",obj("content" to "string","category" to "string")))
        put(fn("memory_delete","Delete a matching saved memory",obj("query" to "string")))
    }))
    private fun fn(name:String,desc:String,properties:JSONObject=JSONObject())=JSONObject().put("name",name).put("description",desc).put("parameters",JSONObject().put("type","object").put("properties",properties))
    private fun obj(vararg pairs:Pair<String,String>)=JSONObject().apply{for((k,t) in pairs)put(k,JSONObject().put("type",t))}
}
