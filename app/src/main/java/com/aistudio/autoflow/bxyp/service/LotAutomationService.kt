package com.aistudio.autoflow.bxyp.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aistudio.autoflow.bxyp.R
import com.aistudio.autoflow.bxyp.core.ExecutionGate
import com.aistudio.autoflow.bxyp.core.TradePolicy
import com.aistudio.autoflow.bxyp.core.TradeSide
import com.aistudio.autoflow.bxyp.data.EncryptedSettings
import java.time.Instant
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Executes one explicitly requested market-order button press only after every UI check succeeds. */
class LotAutomationService : AccessibilityService() {
    private val gate = ExecutionGate()
    private val work = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private lateinit var settings: EncryptedSettings
    private var commandsRegistered = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                LotCommands.ACTION_START -> {
                    gate.start()
                    startAutomationForeground()
                    publishResult("Automation armed. Open the MT5 market-order screen for XAUUSD.")
                }
                LotCommands.ACTION_STOP -> stopAutomation()
                LotCommands.ACTION_BUY -> queue(TradeSide.BUY)
                LotCommands.ACTION_SELL -> queue(TradeSide.SELL)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = EncryptedSettings(applicationContext)
        val filter = IntentFilter().apply {
            addAction(LotCommands.ACTION_START)
            addAction(LotCommands.ACTION_STOP)
            addAction(LotCommands.ACTION_BUY)
            addAction(LotCommands.ACTION_SELL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(commandReceiver, filter)
        }
        commandsRegistered = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are intentionally not used as trading signals and are not retained.
    }

    override fun onInterrupt() = stopAutomation()

    override fun onDestroy() {
        if (commandsRegistered) unregisterReceiver(commandReceiver)
        work.shutdownNow()
        super.onDestroy()
    }

    private fun queue(side: TradeSide) {
        val requestGeneration = gate.tryAcquire()
        if (requestGeneration == null) {
            publishResult("Refused: automation is stopped or another request is still processing.")
            log(side, "unknown", "rejected:not armed or busy")
            return
        }
        work.execute {
            try {
                if (gate.isCurrent(requestGeneration)) executeOne(side, requestGeneration)
            } finally {
                gate.release(requestGeneration)
            }
        }
    }

    private fun executeOne(side: TradeSide, requestGeneration: Long) {
        val initial = targetRoot() ?: return refuse(side, "unknown", "MT5 is not the visible app")
        val initialSnapshot = snapshot(initial)
        val symbol = initialSnapshot.symbol ?: return refuse(side, "unknown", "XAUUSD is not uniquely visible")
        if (!initialSnapshot.demoAccount) return refuse(side, symbol, "a demo account is not confirmed")
        if (!initialSnapshot.marketExecution) return refuse(side, symbol, "market-execution mode is not confirmed")
        if (initialSnapshot.lotFields.size != 1) return refuse(side, symbol, "the lot field is ambiguous or unavailable")
        if (initialSnapshot.pendingOrderVisible) return refuse(side, symbol, "a pending-order control is visible")

        val lotField = initialSnapshot.lotFields.single()
        val setArguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, TradePolicy.FIXED_LOT)
        }
        if (!lotField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArguments)) {
            return refuse(side, symbol, "MT5 rejected the fixed lot value")
        }
        Thread.sleep(250)
        if (!gate.isCurrent(requestGeneration)) return

        val verifiedRoot = targetRoot() ?: return refuse(side, symbol, "MT5 is no longer visible")
        val verified = snapshot(verifiedRoot)
        if (verified.symbol != symbol) return refuse(side, symbol, "the displayed symbol changed")
        if (!verified.demoAccount || !verified.marketExecution || verified.pendingOrderVisible) {
            return refuse(side, symbol, "market-order mode can no longer be confirmed")
        }
        if (verified.lotFields.size != 1 || normalize(verified.lotFields.single().text) != TradePolicy.FIXED_LOT) {
            return refuse(side, symbol, "the 0.01 lot value could not be verified")
        }
        val buttons = if (side == TradeSide.BUY) verified.buyButtons else verified.sellButtons
        if (buttons.size != 1) return refuse(side, symbol, "the $side button is ambiguous or unavailable")
        if (!gate.isCurrent(requestGeneration)) return
        if (buttons.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            val result = "$side market-order button activated for $symbol at ${TradePolicy.FIXED_LOT}."
            log(side, symbol, "button activated")
            publishResult(result, symbol)
        } else {
            refuse(side, symbol, "MT5 rejected the $side button press")
        }
    }

    private fun targetRoot(): AccessibilityNodeInfo? = rootInActiveWindow?.takeIf {
        it.packageName?.toString() == LotCommands.MT5_PACKAGE
    }

    private fun snapshot(root: AccessibilityNodeInfo): ScreenSnapshot {
        val symbols = linkedSetOf<String>()
        val lotFields = mutableListOf<AccessibilityNodeInfo>()
        val buyButtons = mutableListOf<AccessibilityNodeInfo>()
        val sellButtons = mutableListOf<AccessibilityNodeInfo>()
        var marketExecution = false
        var demoAccount = false
        var pendingOrderVisible = false

        fun visit(node: AccessibilityNodeInfo) {
            if (node.packageName?.toString() != LotCommands.MT5_PACKAGE || !node.isVisibleToUser) return
            val labels = listOf(node.text, node.hintText, node.contentDescription, node.viewIdResourceName)
                .filterNotNull()
                .map { it.toString().trim() }
            labels.filter(TradePolicy::acceptableSymbol).forEach(symbols::add)
            marketExecution = marketExecution || labels.any(TradePolicy::isMarketExecutionLabel)
            demoAccount = demoAccount || labels.any(TradePolicy::isDemoAccountLabel)
            pendingOrderVisible = pendingOrderVisible || labels.any(TradePolicy::isPendingOrderLabel)
            if (node.isEditable && node.isEnabled && !node.isPassword && labels.any(::isLotLabel)) lotFields += node
            if (node.isClickable && node.isEnabled && labels.any { TradePolicy.buttonLabelMatches(it, TradeSide.BUY) }) buyButtons += node
            if (node.isClickable && node.isEnabled && labels.any { TradePolicy.buttonLabelMatches(it, TradeSide.SELL) }) sellButtons += node
            for (index in 0 until node.childCount) node.getChild(index)?.let(::visit)
        }
        visit(root)
        return ScreenSnapshot(
            symbol = symbols.singleOrNull(),
            lotFields = lotFields,
            buyButtons = buyButtons,
            sellButtons = sellButtons,
            marketExecution = marketExecution,
            demoAccount = demoAccount,
            pendingOrderVisible = pendingOrderVisible
        )
    }

    private fun isLotLabel(value: String): Boolean {
        val label = value.lowercase(Locale.ROOT)
        return label.contains("lot") || label.contains("volume") || label.contains("حجم")
    }

    private fun normalize(value: CharSequence?): String? = value?.toString()?.trim()?.let {
        it.toBigDecimalOrNull()?.setScale(2)?.toPlainString()
    }

    private fun refuse(side: TradeSide, symbol: String, reason: String) {
        log(side, symbol, "rejected:$reason")
        publishResult("Refused: $reason.", symbol.takeIf { it != "unknown" })
    }

    private fun log(side: TradeSide, symbol: String, result: String) {
        settings.appendLog("${Instant.now()} | $side | $symbol | ${TradePolicy.FIXED_LOT} | $result")
    }

    private fun stopAutomation() {
        gate.stop()
        work.queue.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        publishResult("Automation stopped. No new BUY or SELL command will run.")
    }

    private fun startAutomationForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun publishResult(message: String, symbol: String? = null) {
        val intent = Intent(LotCommands.ACTION_RESULT).setPackage(packageName)
            .putExtra(LotCommands.EXTRA_RESULT, message)
        symbol?.let { intent.putExtra(LotCommands.EXTRA_SYMBOL, it) }
        sendBroadcast(intent)
    }

    private data class ScreenSnapshot(
        val symbol: String?,
        val lotFields: List<AccessibilityNodeInfo>,
        val buyButtons: List<AccessibilityNodeInfo>,
        val sellButtons: List<AccessibilityNodeInfo>,
        val marketExecution: Boolean,
        val demoAccount: Boolean,
        val pendingOrderVisible: Boolean
    )

    private companion object {
        const val CHANNEL_ID = "mt5_market_automation"
        const val NOTIFICATION_ID = 21
    }
}
