package com.elina.assistant.service
import android.service.voice.VoiceInteractionService
import android.util.Log
class ElinaVoiceInteractionService:VoiceInteractionService(){override fun onReady(){super.onReady();Log.i("ELINA_WAKE","VoiceInteractionService selected by system. Hotword availability is OEM/system dependent.")};override fun onShutdown(){Log.i("ELINA_WAKE","Voice interaction service shutdown");super.onShutdown()}}
