package com.aistudio.autoflow.bxyp.core

import java.util.Locale

enum class TradeSide { BUY, SELL }

/** Non-market policy: validates only the explicit, fixed client instruction. */
object TradePolicy {
    const val BASE_SYMBOL = "XAUUSD"
    const val FIXED_LOT = "0.01"

    fun acceptableSymbol(value: String?): Boolean {
        val symbol = value?.trim()?.uppercase(Locale.ROOT) ?: return false
        return symbol.matches(Regex("^XAUUSD[A-Z0-9._-]*$"))
    }

    fun buttonLabelMatches(value: String?, side: TradeSide): Boolean {
        val label = value?.trim()?.lowercase(Locale.ROOT) ?: return false
        return when (side) {
            TradeSide.BUY -> label in setOf("buy", "buy by market", "market buy", "شراء", "شراء بالسوق")
            TradeSide.SELL -> label in setOf("sell", "sell by market", "market sell", "بيع", "بيع بالسوق")
        }
    }

    fun isPendingOrderLabel(value: String?): Boolean {
        val label = value?.lowercase(Locale.ROOT).orEmpty()
        return listOf("buy limit", "sell limit", "buy stop", "sell stop", "stop limit", "pending order", "أمر معلق").any(label::contains)
    }

    fun isMarketExecutionLabel(value: String?): Boolean {
        val label = value?.lowercase(Locale.ROOT).orEmpty()
        return label.contains("market execution") || label.contains("instant execution") ||
            label.contains("تنفيذ السوق") || label.contains("تنفيذ فوري")
    }

    fun isDemoAccountLabel(value: String?): Boolean {
        val label = value?.lowercase(Locale.ROOT).orEmpty()
        return label.contains("demo") || label.contains("trial") || label.contains("تجريبي")
    }
}
