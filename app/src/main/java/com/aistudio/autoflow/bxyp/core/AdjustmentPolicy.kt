package com.aistudio.autoflow.bxyp.core

import java.math.BigDecimal

object AdjustmentPolicy {
    const val DEFAULT_STEP = "0.10"
    const val ZERO_OFFSET = "0.00"
    private val maximumStep = BigDecimal("1000")
    private val maximumOffset = BigDecimal("1000000")

    fun normalizeStep(value: String?): String? {
        val parsed = value?.trim()?.replace(',', '.')?.toBigDecimalOrNull() ?: return null
        if (parsed <= BigDecimal.ZERO || parsed > maximumStep || parsed.scale().coerceAtLeast(0) > 3) return null
        return format(parsed)
    }

    fun adjust(current: String?, normalizedStep: String, side: TradeSide): String? {
        val offset = current?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val step = normalizedStep.toBigDecimalOrNull() ?: return null
        val updated = if (side == TradeSide.BUY) offset + step else offset - step
        return updated.takeIf { it.abs() <= maximumOffset }?.let(::format)
    }

    private fun format(value: BigDecimal): String = value.stripTrailingZeros()
        .let { if (it.scale() < 2) it.setScale(2) else it }
        .toPlainString()
}
