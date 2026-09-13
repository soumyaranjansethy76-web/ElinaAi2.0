package com.elina.assistant.avatar

class EmotionController(private val renderer:AvatarRenderer){
    private var current=Emotion.NEUTRAL
    fun transition(to:Emotion,strength:Float){ current=to; renderer.setEmotion(to.name.lowercase(),strength) }
}
