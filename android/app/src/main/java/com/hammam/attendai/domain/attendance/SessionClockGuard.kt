package com.hammam.attendai.domain.attendance

import kotlin.math.abs

class SessionClockGuard(private val elapsedNow:()->Long,private val thresholdMillis:Long=120_000L){
    private data class Sample(val wall:Long,val elapsed:Long)
    private val samples=mutableMapOf<String,Sample>()
    private val anomalous=mutableSetOf<String>()

    @Synchronized fun start(sessionId:String,wallMillis:Long){samples[sessionId]=Sample(wallMillis,elapsedNow())}
    @Synchronized fun observe(sessionId:String,wallMillis:Long):Boolean{
        if(sessionId in anomalous)return true
        val current=Sample(wallMillis,elapsedNow())
        val previous=samples.put(sessionId,current)?:return false
        if(abs((current.wall-previous.wall)-(current.elapsed-previous.elapsed))>thresholdMillis) anomalous+=sessionId
        return sessionId in anomalous
    }
    @Synchronized fun isAnomalous(sessionId:String)=sessionId in anomalous
    @Synchronized fun clear(sessionId:String){samples.remove(sessionId);anomalous.remove(sessionId)}
}
