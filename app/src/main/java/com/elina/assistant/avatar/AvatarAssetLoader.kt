package com.elina.assistant.avatar

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

class AvatarAssetLoader(private val context:Context){
    data class Report(val exists:Boolean,val version:String,val humanoid:Boolean,val lookAt:Boolean,val springBone:Boolean,val expressions:Set<String>,val sha256:String)
    fun inspect():Report=runCatching{
        val bytes=context.assets.open("avatar/elina.vrm").use{it.readBytes()}
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){String.format("%02x",it)}
        val doc=VrmReader.read(bytes)
        val vrm=doc.optJSONObject("extensions")?.optJSONObject("VRM") ?: JSONObject()
        val meta=vrm.optString("specVersion","unknown")
        val humanoid=vrm.optJSONObject("humanoid")?.optJSONArray("humanBones")?.length()?.let{it>0}==true
        val sec=vrm.optJSONObject("secondaryAnimation")?.let{true}==true
        val names=buildSet<String>{vrm.optJSONObject("blendShapeMaster")?.optJSONArray("blendShapeGroups")?.let{a->for(i in 0 until a.length()) add(a.optJSONObject(i)?.optString("name","").orEmpty())}}
        Report(true,meta,humanoid,false,sec,names,hash)
    }.getOrElse{Report(false,"unknown",false,false,false,emptySet(),"")}
}

private object VrmReader {
    fun read(bytes:ByteArray):JSONObject{
        require(bytes.size>20 && bytes[0].toInt()==0x67 && bytes[1].toInt()==0x6C && bytes[2].toInt()==0x54 && bytes[3].toInt()==0x46){"Not a GLB/VRM file"}
        val len=(bytes[8].toInt() and 255) or ((bytes[9].toInt() and 255) shl 8) or ((bytes[10].toInt() and 255) shl 16) or ((bytes[11].toInt() and 255) shl 24)
        var p=12
        while(p<len){ val clen=java.nio.ByteBuffer.wrap(bytes,p,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int; val type=java.nio.ByteBuffer.wrap(bytes,p+4,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int; if(type==0x4E4F534A){val s=String(bytes,p+8,p+8+clen).trimEnd('\u0000',' ','\n','\r','\t');return JSONObject(s)};p+=8+clen }
        error("VRM JSON chunk missing")
    }
}
