package com.elina.assistant.service

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

class ElinaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return ElinaSession(this)
    }
}

private class ElinaSession(
    service: VoiceInteractionSessionService
) : VoiceInteractionSession(service) {

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)

        try {
            val intent = Intent(
                context,
                com.elina.assistant.ui.main.MainActivity::class.java
            )
            startAssistantActivity(intent)
        } catch (e: Exception) {
            Log.w("ELINA_WAKE", "Unable to open main UI", e)
        } finally {
            hide()
        }
    }
}
