package com.elina.assistant.avatar

import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

class LookAtController(private val renderer:AvatarRenderer){
    private var job:Job?=null
    fun start(scope:CoroutineScope){ job?.cancel(); job=scope.launch{ while(isActive){ renderer.lookAt(sin(Random.nextFloat()*6.28f)*.14f, (Random.nextFloat()-.5f)*.06f); delay(Random.nextLong(1800,4200)); renderer.lookAt(0f,0f); delay(Random.nextLong(700,1600)) } } }
    fun stop(){job?.cancel();job=null}
}
