package com.elina.assistant.ui.main

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.sin

class WaveformView(context:Context):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeWidth=3f}
    @Volatile private var amp=0f
    fun setAmplitude(v:Float){amp=v;postInvalidateOnAnimation()}
    override fun onDraw(c:Canvas){super.onDraw(c);val cy=height/2f;val n=28;for(i in 0 until n){val x=width*(i+.5f)/n;val h=(4+amp*height*.22f)*(0.45f+0.55f*sin(i*0.8f).let{(it+1)/2});c.drawLine(x,cy-h,x,cy+h,p)}}
}
