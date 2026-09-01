package com.aistudio.autoflow.bxyp.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradePolicyTest {
    @Test fun test_acceptableSymbol_validAndInvalidSymbols_returnsExpectedResult() {
        assertTrue(TradePolicy.acceptableSymbol("XAUUSD"))
        assertTrue(TradePolicy.acceptableSymbol("XAUUSDm"))
        assertTrue(TradePolicy.acceptableSymbol("xauusd.pro"))
        assertFalse(TradePolicy.acceptableSymbol("EURUSD"))
        assertFalse(TradePolicy.acceptableSymbol("XAUUSD / EURUSD"))
    }

    @Test fun test_buttonLabelMatches_marketAndPendingLabels_returnsExpectedResult() {
        assertTrue(TradePolicy.buttonLabelMatches("Buy", TradeSide.BUY))
        assertTrue(TradePolicy.buttonLabelMatches("Sell by market", TradeSide.SELL))
        assertFalse(TradePolicy.buttonLabelMatches("Buy Limit", TradeSide.BUY))
        assertFalse(TradePolicy.buttonLabelMatches("Sell Stop", TradeSide.SELL))
    }

    @Test fun test_isPendingOrderLabel_pendingAndStopLossLabels_returnsExpectedResult() {
        assertTrue(TradePolicy.isPendingOrderLabel("Buy Stop Limit"))
        assertTrue(TradePolicy.isPendingOrderLabel("Pending Order"))
        assertFalse(TradePolicy.isPendingOrderLabel("Stop Loss"))
    }

    @Test fun test_isFixedLot_equivalentAndInvalidValues_returnsExpectedResult() {
        assertTrue(TradePolicy.isFixedLot("0.01"))
        assertTrue(TradePolicy.isFixedLot("0.010"))
        assertTrue(TradePolicy.isFixedLot("0,01"))
        assertFalse(TradePolicy.isFixedLot("0.011"))
        assertFalse(TradePolicy.isFixedLot("not-a-number"))
    }

    @Test fun test_isExnessLabel_realDemoAndOtherBroker_returnsExpectedResult() {
        assertTrue(TradePolicy.isExnessLabel("Exness-MT5Real"))
        assertTrue(TradePolicy.isExnessLabel("Exness Trial"))
        assertFalse(TradePolicy.isExnessLabel("Another broker"))
    }
}
