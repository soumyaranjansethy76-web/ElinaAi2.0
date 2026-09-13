package com.elina.assistant.ai

import com.elina.assistant.model.ToolResult
import org.json.JSONArray
import org.json.JSONObject

class ToolCallExecutor(private val router:CommandRouter){
    suspend fun execute(calls:List<ToolCallEvent>):JSONArray=JSONArray().apply{
        for(call in calls){val r=router.execute(call.name,call.args);put(JSONObject().put("id",call.id).put("name",call.name).put("response",JSONObject().put("success",r.success).put("result",r.result)))}
    }
}
