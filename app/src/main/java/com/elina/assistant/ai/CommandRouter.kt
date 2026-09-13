package com.elina.assistant.ai

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import com.elina.assistant.memory.MemoryRepository
import com.elina.assistant.model.ToolResult
import com.elina.assistant.service.ElinaAccessibilityService
import org.json.JSONObject

class CommandRouter(private val context:Context, private val memory:MemoryRepository){
    suspend fun execute(name:String,args:JSONObject):ToolResult = runCatching {
        when(name){
            "open_app"->{
                val pkg=args.optString("packageName"); val launch=context.packageManager.getLaunchIntentForPackage(pkg)
                    ?: return@runCatching ToolResult(false,"That app is unavailable on this device.")
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(launch); ToolResult(true,"Opened $pkg.")
            }
            "go_back"->{val ok=ElinaAccessibilityService.instance?.goBack()==true;ToolResult(ok,if(ok)"Went back." else "Accessibility Service is disabled or the action was unavailable.")}
            "go_home"->{val ok=ElinaAccessibilityService.instance?.goHome()==true;ToolResult(ok,if(ok)"Opened Home." else "Accessibility Service is disabled or the action was unavailable.")}
            "click_text"->{val ok=ElinaAccessibilityService.instance?.clickText(args.optString("text"))==true;ToolResult(ok,if(ok)"Clicked the requested text." else "Could not click that visible control.")}
            "type_text"->{val ok=ElinaAccessibilityService.instance?.typeText(args.optString("text"))==true;ToolResult(ok,if(ok)"Typed the requested text." else "Could not type into the focused control.")}
            "scroll"->{val ok=ElinaAccessibilityService.instance?.scroll(args.optString("direction"))==true;ToolResult(ok,if(ok)"Scrolled ${args.optString("direction")}." else "Could not scroll the current screen.")}
            "media_control"->{val action=args.optString("action").uppercase(); val ok=when(action){"PLAY"->ElinaAccessibilityService.instance?.media(ElinaAccessibilityService.MediaAction.PLAY);"PAUSE"->ElinaAccessibilityService.instance?.media(ElinaAccessibilityService.MediaAction.PAUSE);"NEXT"->ElinaAccessibilityService.instance?.media(ElinaAccessibilityService.MediaAction.NEXT);"PREVIOUS"->ElinaAccessibilityService.instance?.media(ElinaAccessibilityService.MediaAction.PREVIOUS);else->false}==true;ToolResult(ok,if(ok)"Media $action sent." else "Media action was unavailable.")}
            "volume_control"->{val am=context.getSystemService(AudioManager::class.java);val dir=args.optString("direction").lowercase();val delta=if(dir=="up")1 else -1;am.adjustVolume(AudioManager.STREAM_MUSIC,delta);ToolResult(true,"Media volume adjusted $dir.")}
            "open_settings"->{val page=args.optString("page").lowercase();val action=when(page){"accessibility"->Settings.ACTION_ACCESSIBILITY_SETTINGS;"overlay"->Settings.ACTION_MANAGE_OVERLAY_PERMISSION;"wifi"->Settings.ACTION_WIFI_SETTINGS;"bluetooth"->Settings.ACTION_BLUETOOTH_SETTINGS;else->Settings.ACTION_SETTINGS};context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ToolResult(true,"Opened Android Settings.")}
            "memory_store"->{memory.add(args.optString("category","user-approved"),args.optString("content"));ToolResult(true,"Saved locally.")}
            "memory_delete"->{val q=args.optString("query");val ms=memory.search(q);if(ms.isEmpty()) ToolResult(false,"No matching saved memory was found.") else {memory.delete(ms.first());ToolResult(true,"Deleted the matching memory.")}}
            else->ToolResult(false,"Unsupported tool: $name")
        }
    }.getOrElse{ToolResult(false,"Action failed: ${it.message ?: "unknown error"}")}
}
