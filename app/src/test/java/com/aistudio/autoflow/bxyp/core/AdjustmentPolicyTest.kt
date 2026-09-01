package com.aistudio.autoflow.bxyp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdjustmentPolicyTest {
    @Test fun test_normalizeStep_validDotAndCommaValues_returnsCanonicalValue() {
        assertEquals("0.10", AdjustmentPolicy.normalizeStep("0.10"))
        assertEquals("1.25", AdjustmentPolicy.normalizeStep("1,25"))
        assertEquals("0.001", AdjustmentPolicy.normalizeStep("0.001"))
    }

    @Test fun test_normalizeStep_invalidBoundaryAndPrecision_returnsNull() {
        assertNull(AdjustmentPolicy.normalizeStep("0"))
        assertNull(AdjustmentPolicy.normalizeStep("-1"))
        assertNull(AdjustmentPolicy.normalizeStep("0.0001"))
        assertNull(AdjustmentPolicy.normalizeStep("1000.001"))
        assertNull(AdjustmentPolicy.normalizeStep("abc"))
    }

    @Test fun test_adjust_buyAndSell_updatesOffsetInExpectedDirection() {
        assertEquals("0.10", AdjustmentPolicy.adjust("0.00", "0.10", TradeSide.BUY))
        assertEquals("-0.10", AdjustmentPolicy.adjust("0.00", "0.10", TradeSide.SELL))
        assertEquals("0.00", AdjustmentPolicy.adjust("0.10", "0.10", TradeSide.SELL))
        assertNull(AdjustmentPolicy.adjust("1000000.00", "0.10", TradeSide.BUY))
    }
}
