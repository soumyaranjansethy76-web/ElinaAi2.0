package com.elina.assistant.avatar

import kotlinx.coroutines.*
import kotlin.random.Random

class BlinkController(private val renderer:AvatarRenderer){
    private var job:Job?=null
    fun start(scope:CoroutineScope){ job?.cancel(); job=scope.launch{ while(isActive){ delay(Random.nextLong(2600,6200)); renderer.blink(); if(Random.nextFloat()<.12f){ delay(110); renderer.blink() } } } }
    fun stop(){ job?.cancel(); job=null }
}
