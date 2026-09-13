package com.elina.assistant.avatar

class EmotionEngine {
    data class EmotionState(val emotion:Emotion,val strength:Float)
    fun classify(text:String):EmotionState {
        val t=text.lowercase()
        val e=when { listOf("sorry","sad","unfortunately","miss").any(t::contains)->Emotion.SAD
            listOf("angry","mad","annoy").any(t::contains)->Emotion.ANGRY
            listOf("wow","surprise","really?!").any(t::contains)->Emotion.SURPRISED
            listOf("thank","great","awesome","happy","love that").any(t::contains)->Emotion.HAPPY
            listOf("think","let me","hmm").any(t::contains)->Emotion.THINKING
            listOf("careful","worried","concern").any(t::contains)->Emotion.WORRIED
            else->Emotion.NEUTRAL }
        return EmotionState(e, if(e==Emotion.NEUTRAL) .28f else .72f)
    }
}
