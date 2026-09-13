package com.elina.assistant.avatar

class LipSyncController(private val renderer:AvatarRenderer){
    private var energy=0f
    fun onPcm(pcm:ShortArray, transcriptHint:String?=null){
        if(pcm.isEmpty()) return
        var sum=0.0; for(s in pcm){ val v=s.toDouble()/32768.0; sum+=v*v }
        val rms=kotlin.math.sqrt(sum/pcm.size).toFloat().coerceIn(0f,1f)
        energy=energy*0.65f+rms*0.35f
        val vowel = transcriptHint?.let { inferVowel(it) } ?: "aa"
        renderer.setLip(vowel, energy)
    }
    fun stop(){ energy*=0.25f; renderer.setLip("aa",0f) }
    private fun inferVowel(t:String):String { val x=t.lowercase(); return when { x.matches(Regex(".*[ou].*"))->"ou"; x.matches(Regex(".*[ei].*"))->"ee"; x.matches(Regex(".*i.*"))->"ih"; x.matches(Regex(".*o.*"))->"oh"; else->"aa" } }
}
