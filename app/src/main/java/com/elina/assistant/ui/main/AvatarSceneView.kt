package com.elina.assistant.ui.main

import android.content.Context
import android.widget.FrameLayout
import com.elina.assistant.avatar.AvatarRenderer

class AvatarSceneView(context:Context):FrameLayout(context){
    private val renderer=AvatarRenderer(context)
    init{addView(renderer.view(),LayoutParams(-1,-1))}
    fun setEmotion(name:String,strength:Float)=renderer.setEmotion(name,strength)
    fun setLip(shape:String,amount:Float)=renderer.setLip(shape,amount)
    fun blink()=renderer.blink()
    fun lookAt(x:Float,y:Float)=renderer.lookAt(x,y)
    fun release()=renderer.release()
}
