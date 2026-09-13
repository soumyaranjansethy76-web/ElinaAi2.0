package com.elina.assistant.avatar

import com.elina.assistant.model.ElinaState

class AvatarController(private val renderer:AvatarRenderer, private val emotions:EmotionController){
    fun setState(state:ElinaState){ when(state){ElinaState.IDLE->emotions.transition(Emotion.NEUTRAL,.35f);ElinaState.LISTENING->emotions.transition(Emotion.NEUTRAL,.55f);ElinaState.THINKING->emotions.transition(Emotion.THINKING,.7f);ElinaState.SPEAKING->{emotions.transition(Emotion.HAPPY,.45f)};ElinaState.INTERRUPTED->emotions.transition(Emotion.SURPRISED,.5f);ElinaState.CONNECTING,ElinaState.RECONNECTING->emotions.transition(Emotion.THINKING,.45f);ElinaState.ERROR->emotions.transition(Emotion.WORRIED,.8f)} }
    fun lip(shape:String,amount:Float)=renderer.setLip(shape,amount)
}
