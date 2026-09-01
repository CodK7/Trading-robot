package com.aistudio.autoflow.bxyp.core

sealed interface GateAcquireResult {
    data class Granted(val token: Long) : GateAcquireResult
    data object Stopped : GateAcquireResult
    data object Busy : GateAcquireResult
}

data class GateState(val armed: Boolean, val processing: Boolean)

/** Serializes requests and makes Stop atomic with the final MT5 button press. */
class ExecutionGate {
    private var armed = false
    private var activeToken: Long? = null
    private var generation = 0L

    @Synchronized
    fun start() {
        armed = true
    }

    @Synchronized
    fun stop() {
        armed = false
        generation++
    }

    @Synchronized
    fun tryAcquire(): GateAcquireResult {
        if (!armed) return GateAcquireResult.Stopped
        if (activeToken != null) return GateAcquireResult.Busy
        return GateAcquireResult.Granted(generation).also { activeToken = it.token }
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = armed && generation == token

    fun <T> runIfCurrent(token: Long, action: () -> T): T? = synchronized(this) {
        if (!armed || generation != token || activeToken != token) null else action()
    }

    @Synchronized
    fun release(token: Long) {
        if (activeToken == token) activeToken = null
    }

    @Synchronized
    fun state(): GateState = GateState(armed, activeToken != null)
}
