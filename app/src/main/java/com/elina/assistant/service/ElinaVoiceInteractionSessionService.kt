package com.elina.assistant.service
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.content.Intent
import android.util.Log
class ElinaVoiceInteractionSessionService:VoiceInteractionSessionService(){override fun onNewSession(args:Bundle?):VoiceInteractionSession=ElinaSession(this)}
private class ElinaSession(service:VoiceInteractionSessionService):VoiceInteractionSession(service){override fun onShow(args:Bundle?,flags:Int){super.onShow(args,flags);try{startActivity(Intent(context,com.elina.assistant.ui.main.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))}catch(e:Exception){Log.w("ELINA_WAKE","Unable to open main UI",e)}requestClose()}}
