package com.elina.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.os.Bundle
import android.content.Intent
import android.media.AudioManager

class ElinaAccessibilityService:AccessibilityService(){
    companion object { @Volatile var instance:ElinaAccessibilityService?=null }
    enum class MediaAction{PLAY,PAUSE,NEXT,PREVIOUS}
    override fun onServiceConnected(){super.onServiceConnected();instance=this}
    override fun onAccessibilityEvent(event:android.view.accessibility.AccessibilityEvent?){}
    override fun onInterrupt(){}
    override fun onDestroy(){if(instance===this)instance=null;super.onDestroy()}
    fun goBack()=performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome()=performGlobalAction(GLOBAL_ACTION_HOME)
    fun clickText(text:String):Boolean{val root=rootInActiveWindow?:return false;val nodes=root.findAccessibilityNodeInfosByText(text);for(n in nodes){if(n.isClickable||n.isEnabled){if(n.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true}};return false}
    fun typeText(text:String):Boolean{val root=rootInActiveWindow?:return false;val n=findEditable(root)?:return false;n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);val b=Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,text);return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b)}
    fun scroll(direction:String):Boolean{val root=rootInActiveWindow?:return false;val nodes=mutableListOf<AccessibilityNodeInfo>();collectScrollable(root,nodes);for(n in nodes){val action=if(direction.lowercase()=="down")AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;if(n.performAction(action))return true};return false}
    fun media(a:MediaAction):Boolean{val am=getSystemService(AudioManager::class.java);return when(a){MediaAction.PLAY->am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PLAY));MediaAction.PAUSE->am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PAUSE));MediaAction.NEXT->am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_NEXT));MediaAction.PREVIOUS->am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_PREVIOUS))}}
    fun closeCurrentApp():Boolean=performGlobalAction(GLOBAL_ACTION_BACK)
    private fun findEditable(n:AccessibilityNodeInfo):AccessibilityNodeInfo?{if(n.isEditable)return n;for(i in 0 until n.childCount){n.getChild(i)?.let{findEditable(it)?.let{return it}}};return null}
    private fun collectScrollable(n:AccessibilityNodeInfo,out:MutableList<AccessibilityNodeInfo>){if(n.isScrollable)out.add(n);for(i in 0 until n.childCount)n.getChild(i)?.let{collectScrollable(it,out)}}
}
