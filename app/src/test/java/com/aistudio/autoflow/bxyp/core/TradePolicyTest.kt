package com.aistudio.autoflow.bxyp.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradePolicyTest {
    @Test fun `accepts only XAUUSD and explicit broker suffixes`() {
        assertTrue(TradePolicy.acceptableSymbol("XAUUSD"))
        assertTrue(TradePolicy.acceptableSymbol("XAUUSDm"))
        assertTrue(TradePolicy.acceptableSymbol("xauusd.pro"))
        assertFalse(TradePolicy.acceptableSymbol("EURUSD"))
        assertFalse(TradePolicy.acceptableSymbol("XAUUSD / EURUSD"))
    }

    @Test fun `accepts only direct market action labels`() {
        assertTrue(TradePolicy.buttonLabelMatches("Buy", TradeSide.BUY))
        assertTrue(TradePolicy.buttonLabelMatches("Sell by market", TradeSide.SELL))
        assertFalse(TradePolicy.buttonLabelMatches("Buy Limit", TradeSide.BUY))
        assertFalse(TradePolicy.buttonLabelMatches("Sell Stop", TradeSide.SELL))
    }

    @Test fun `rejects pending-order controls`() {
        assertTrue(TradePolicy.isPendingOrderLabel("Buy Stop Limit"))
        assertTrue(TradePolicy.isPendingOrderLabel("Pending Order"))
        assertFalse(TradePolicy.isPendingOrderLabel("Stop Loss"))
    }

    @Test fun `prevents duplicate execution while a tap is processing and stop invalidates it`() {
        val gate = ExecutionGate()
        gate.start()
        val token = requireNotNull(gate.tryAcquire())
        assertFalse(gate.tryAcquire() != null)
        assertTrue(gate.isCurrent(token))
        gate.stop()
        assertFalse(gate.isCurrent(token))
        assertFalse(gate.tryAcquire() != null)
    }

    @Test fun `requires an explicit demo or trial account label by default`() {
        assertTrue(TradePolicy.isDemoAccountLabel("Exness-MT5Trial"))
        assertTrue(TradePolicy.isDemoAccountLabel("Demo Account"))
        assertFalse(TradePolicy.isDemoAccountLabel("Exness Real"))
    }
}
