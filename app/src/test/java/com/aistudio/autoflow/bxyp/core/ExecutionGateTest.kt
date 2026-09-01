package com.aistudio.autoflow.bxyp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ExecutionGateTest {
    @Test fun test_tryAcquire_busyAndStoppedStates_serializesAndInvalidatesToken() {
        val gate = ExecutionGate()
        assertEquals(GateAcquireResult.Stopped, gate.tryAcquire())
        gate.start()
        val granted = gate.tryAcquire() as GateAcquireResult.Granted
        assertEquals(GateAcquireResult.Busy, gate.tryAcquire())
        assertTrue(gate.isCurrent(granted.token))
        gate.stop()
        assertFalse(gate.isCurrent(granted.token))
        assertNull(gate.runIfCurrent(granted.token) { true })
    }

    @Test fun test_start_cancelledWorkerNotReleased_remainsBusyUntilRelease() {
        val gate = ExecutionGate()
        gate.start()
        val token = (gate.tryAcquire() as GateAcquireResult.Granted).token
        gate.stop()
        gate.start()
        assertEquals(GateAcquireResult.Busy, gate.tryAcquire())
        gate.release(token)
        assertTrue(gate.tryAcquire() is GateAcquireResult.Granted)
    }

    @Test fun test_runIfCurrent_armedToken_runsAction() {
        val gate = ExecutionGate()
        gate.start()
        val token = (gate.tryAcquire() as GateAcquireResult.Granted).token
        assertEquals("clicked", gate.runIfCurrent(token) { "clicked" })
        gate.release(token)
    }

    @Test fun test_stop_committedActionInProgress_waitsUntilActionFinishes() {
        val gate = ExecutionGate()
        gate.start()
        val token = (gate.tryAcquire() as GateAcquireResult.Granted).token
        val actionStarted = CountDownLatch(1)
        val allowActionToFinish = CountDownLatch(1)
        val stopStarted = CountDownLatch(1)
        val stopFinished = CountDownLatch(1)
        val actionThread = Thread {
            gate.runIfCurrent(token) {
                actionStarted.countDown()
                allowActionToFinish.await()
            }
        }.apply { start() }
        assertTrue(actionStarted.await(1, TimeUnit.SECONDS))
        val stopThread = Thread {
            stopStarted.countDown()
            gate.stop()
            stopFinished.countDown()
        }.apply { start() }
        assertTrue(stopStarted.await(1, TimeUnit.SECONDS))
        assertFalse(stopFinished.await(50, TimeUnit.MILLISECONDS))
        allowActionToFinish.countDown()
        assertTrue(stopFinished.await(1, TimeUnit.SECONDS))
        actionThread.join()
        stopThread.join()
    }
}
