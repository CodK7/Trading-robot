package com.aistudio.autoflow.bxyp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aistudio.autoflow.bxyp.core.TradeSide

class AutomationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = LotAutomationService.current() ?: return
        when (intent.action) {
            ACTION_BUY -> service.requestAdjustment(TradeSide.BUY)
            ACTION_SELL -> service.requestAdjustment(TradeSide.SELL)
            ACTION_STOP -> service.stopAutomation()
        }
    }

    companion object {
        const val ACTION_BUY = "com.aistudio.autoflow.bxyp.internal.BUY"
        const val ACTION_SELL = "com.aistudio.autoflow.bxyp.internal.SELL"
        const val ACTION_STOP = "com.aistudio.autoflow.bxyp.internal.STOP"
    }
}
