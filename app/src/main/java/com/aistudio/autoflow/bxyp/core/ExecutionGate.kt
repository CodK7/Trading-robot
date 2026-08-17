package com.aistudio.autoflow.bxyp.core

/** Serializes explicit taps and lets Stop invalidate work that has not reached the MT5 button. */
class ExecutionGate {
    private var armed = false
    private var processing = false
    private var generation = 0L

    @Synchronized
    fun start() {
        armed = true
    }

    @Synchronized
    fun stop() {
        armed = false
        processing = false
        generation++
    }

    @Synchronized
    fun tryAcquire(): Long? {
        if (!armed || processing) return null
        processing = true
        return generation
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = armed && generation == token

    @Synchronized
    fun release(token: Long) {
        if (generation == token) processing = false
    }
}
