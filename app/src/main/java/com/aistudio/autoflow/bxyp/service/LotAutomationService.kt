package com.aistudio.autoflow.bxyp.service

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.aistudio.autoflow.bxyp.MainActivity
import com.aistudio.autoflow.bxyp.R
import com.aistudio.autoflow.bxyp.core.AdjustmentPolicy
import com.aistudio.autoflow.bxyp.core.ExecutionGate
import com.aistudio.autoflow.bxyp.core.GateAcquireResult
import com.aistudio.autoflow.bxyp.core.TradePolicy
import com.aistudio.autoflow.bxyp.core.TradeSide
import com.aistudio.autoflow.bxyp.data.EncryptedSettings
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class TradeRequestResult { ACCEPTED, STOPPED, BUSY, INVALID, UNAVAILABLE }

data class AutomationState(
    val connected: Boolean,
    val armed: Boolean,
    val processing: Boolean,
    val message: String,
    val symbol: String?
)

class LotAutomationService : AccessibilityService() {
    private val gate = ExecutionGate()
    private val work = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1))
    private lateinit var settings: EncryptedSettings
    @Volatile private var latestMessage = ""
    @Volatile private var latestSymbol: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        settings = EncryptedSettings(applicationContext)
        activeService = this
        publishResult(getString(R.string.status_ready))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = stopAutomation()

    override fun onDestroy() {
        gate.stop()
        work.shutdownNow()
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    fun state(): AutomationState {
        val state = gate.state()
        return AutomationState(true, state.armed, state.processing, latestMessage, latestSymbol)
    }

    fun startAutomation(): Boolean {
        gate.start()
        return try {
            startAutomationForeground()
            publishResult(getString(R.string.status_armed))
            true
        } catch (_: Exception) {
            gate.stop()
            publishResult(getString(R.string.status_refused, getString(R.string.reason_foreground_unavailable)))
            false
        }
    }

    fun stopAutomation() {
        gate.stop()
        work.queue.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
        publishResult(getString(R.string.status_stopped))
    }

    fun requestAdjustment(side: TradeSide, requestedStep: String? = null): TradeRequestResult {
        val step = AdjustmentPolicy.normalizeStep(
            requestedStep ?: settings.readStep(AdjustmentPolicy.DEFAULT_STEP)
        ) ?: return TradeRequestResult.INVALID
        return when (val acquired = gate.tryAcquire()) {
        GateAcquireResult.Stopped -> rejectRequest(side, TradeRequestResult.STOPPED, R.string.reason_stopped)
        GateAcquireResult.Busy -> rejectRequest(side, TradeRequestResult.BUSY, R.string.reason_busy)
        is GateAcquireResult.Granted -> adjustAndSubmit(side, step, acquired.token)
        }
    }

    private fun adjustAndSubmit(side: TradeSide, step: String, token: Long): TradeRequestResult {
        val offset = AdjustmentPolicy.adjust(
            settings.readReferenceOffset(AdjustmentPolicy.ZERO_OFFSET), step, side
        ) ?: run {
            gate.release(token)
            return TradeRequestResult.INVALID
        }
        return try {
            settings.writeStep(step)
            settings.writeReferenceOffset(offset)
            submit(side, token)
        } catch (_: Exception) {
            gate.release(token)
            refuse(side, UNKNOWN_SYMBOL, R.string.reason_storage_unavailable)
            TradeRequestResult.UNAVAILABLE
        }
    }

    private fun rejectRequest(
        side: TradeSide,
        result: TradeRequestResult,
        reason: Int
    ): TradeRequestResult {
        refuse(side, UNKNOWN_SYMBOL, reason)
        return result
    }

    private fun submit(side: TradeSide, token: Long): TradeRequestResult {
        publishResult(getString(R.string.status_waiting_for_mt5, side.name))
        return try {
            work.execute {
                try {
                    if (gate.isCurrent(token)) executeOne(side, token)
                } finally {
                    gate.release(token)
                }
            }
            TradeRequestResult.ACCEPTED
        } catch (_: RejectedExecutionException) {
            gate.release(token)
            refuse(side, UNKNOWN_SYMBOL, R.string.reason_service_unavailable)
            TradeRequestResult.UNAVAILABLE
        }
    }

    private fun executeOne(side: TradeSide, token: Long) {
        val initial = awaitReadyScreen(side, token) ?: return
        val lotField = initial.snapshot.lotFields.single()
        if (!setFixedLot(lotField)) return refuse(side, initial.symbol, R.string.reason_lot_rejected)
        if (!waitForUi(token, LOT_SETTLE_DELAY_MS)) return

        val verified = verifyAfterLotChange(side, initial.symbol) ?: return
        val button = verified.snapshot.buttonFor(side).single()
        val clicked = gate.runIfCurrent(token) {
            button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } ?: return
        if (!clicked) return refuse(side, initial.symbol, R.string.reason_click_rejected)

        log(side, initial.symbol, "submitted", verified.snapshot.exnessVisible)
        publishResult(getString(R.string.status_submitted, side.name, initial.symbol, TradePolicy.FIXED_LOT), initial.symbol)
    }

    private fun awaitReadyScreen(side: TradeSide, token: Long): ReadyScreen? {
        val deadline = SystemClock.elapsedRealtime() + SCREEN_WAIT_TIMEOUT_MS
        var reason = R.string.reason_mt5_not_visible
        var symbol = UNKNOWN_SYMBOL
        while (gate.isCurrent(token) && SystemClock.elapsedRealtime() < deadline) {
            val root = targetRoot()
            if (root != null) {
                val snapshot = snapshot(root)
                symbol = snapshot.symbol ?: UNKNOWN_SYMBOL
                reason = readinessFailure(snapshot, side) ?: return ReadyScreen(symbol, snapshot)
            }
            if (!waitForUi(token, POLL_INTERVAL_MS)) return null
        }
        if (gate.isCurrent(token)) refuse(side, symbol, reason)
        return null
    }

    private fun readinessFailure(snapshot: ScreenSnapshot, side: TradeSide): Int? = when {
        snapshot.symbol == null -> R.string.reason_symbol_unavailable
        snapshot.pendingOrderVisible -> R.string.reason_pending_order
        snapshot.lotFields.size != 1 -> R.string.reason_lot_unavailable
        !snapshot.isMarketOrderScreen -> R.string.reason_market_mode
        snapshot.buttonFor(side).size != 1 -> R.string.reason_button_unavailable
        else -> null
    }

    private fun setFixedLot(field: AccessibilityNodeInfo): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, TradePolicy.FIXED_LOT)
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    private fun verifyAfterLotChange(side: TradeSide, expectedSymbol: String): ReadyScreen? {
        val root = targetRoot() ?: return refuseAndNull(side, expectedSymbol, R.string.reason_mt5_not_visible)
        val snapshot = snapshot(root)
        if (snapshot.symbol != expectedSymbol) return refuseAndNull(side, expectedSymbol, R.string.reason_symbol_changed)
        if (snapshot.pendingOrderVisible || !snapshot.isMarketOrderScreen) {
            return refuseAndNull(side, expectedSymbol, R.string.reason_market_mode_changed)
        }
        if (snapshot.lotFields.size != 1 || !TradePolicy.isFixedLot(snapshot.lotFields.single().text)) {
            return refuseAndNull(side, expectedSymbol, R.string.reason_lot_not_verified)
        }
        if (snapshot.buttonFor(side).size != 1) {
            return refuseAndNull(side, expectedSymbol, R.string.reason_button_unavailable)
        }
        return ReadyScreen(expectedSymbol, snapshot)
    }

    private fun refuseAndNull(side: TradeSide, symbol: String, reason: Int): ReadyScreen? {
        refuse(side, symbol, reason)
        return null
    }

    private fun waitForUi(token: Long, delayMs: Long): Boolean = try {
        Thread.sleep(delayMs)
        gate.isCurrent(token)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun targetRoot(): AccessibilityNodeInfo? = rootInActiveWindow?.takeIf {
        it.packageName?.toString() == LotCommands.MT5_PACKAGE
    }

    private fun snapshot(root: AccessibilityNodeInfo): ScreenSnapshot {
        val collector = ScreenCollector()
        visit(root, collector)
        return collector.snapshot()
    }

    private fun visit(node: AccessibilityNodeInfo, collector: ScreenCollector) {
        if (node.packageName?.toString() != LotCommands.MT5_PACKAGE || !node.isVisibleToUser) return
        collector.capture(node, nodeLabels(node))
        for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it, collector) }
    }

    private fun nodeLabels(node: AccessibilityNodeInfo): List<String> =
        listOf(node.text, node.hintText, node.contentDescription, node.viewIdResourceName)
            .filterNotNull()
            .map { it.toString().trim() }

    private fun refuse(side: TradeSide, symbol: String, reasonId: Int) {
        val reason = getString(reasonId)
        log(side, symbol, "rejected:$reason", false)
        publishResult(getString(R.string.status_refused, reason), symbol.takeIf { it != UNKNOWN_SYMBOL })
    }

    private fun log(side: TradeSide, symbol: String, result: String, exnessVisible: Boolean) {
        val broker = if (exnessVisible) "Exness" else "unverified"
        settings.appendLog("${Instant.now()} | $side | $symbol | ${TradePolicy.FIXED_LOT} | $broker | $result")
    }

    private fun publishResult(message: String, symbol: String? = null) {
        latestMessage = message
        latestSymbol = symbol
    }

    private fun startAutomationForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text))
        .setContentIntent(activityIntent())
        .addAction(notificationAction(R.string.action_buy, AutomationActionReceiver.ACTION_BUY, 1))
        .addAction(notificationAction(R.string.action_sell, AutomationActionReceiver.ACTION_SELL, 2))
        .addAction(notificationAction(R.string.action_stop, AutomationActionReceiver.ACTION_STOP, 3))
        .setOngoing(true)
        .build()

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        this,
        requestCode,
        Intent(this, AutomationActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notificationAction(title: Int, action: String, requestCode: Int) = Notification.Action.Builder(
        Icon.createWithResource(this, R.drawable.ic_launcher),
        getString(title),
        actionIntent(action, requestCode)
    ).build()

    private inner class ScreenCollector {
        private val symbols = linkedSetOf<String>()
        private val lotFields = mutableListOf<AccessibilityNodeInfo>()
        private val buyButtons = mutableListOf<AccessibilityNodeInfo>()
        private val sellButtons = mutableListOf<AccessibilityNodeInfo>()
        private var marketExecution = false
        private var pendingOrderVisible = false
        private var exnessVisible = false

        fun capture(node: AccessibilityNodeInfo, labels: List<String>) {
            labels.filter(TradePolicy::acceptableSymbol).forEach(symbols::add)
            marketExecution = marketExecution || labels.any(TradePolicy::isMarketExecutionLabel)
            pendingOrderVisible = pendingOrderVisible || labels.any(TradePolicy::isPendingOrderLabel)
            exnessVisible = exnessVisible || labels.any(TradePolicy::isExnessLabel)
            if (node.isEditable && node.isEnabled && !node.isPassword && labels.any(::isLotLabel)) lotFields += node
            if (node.isClickable && node.isEnabled && labels.any { TradePolicy.buttonLabelMatches(it, TradeSide.BUY) }) buyButtons += node
            if (node.isClickable && node.isEnabled && labels.any { TradePolicy.buttonLabelMatches(it, TradeSide.SELL) }) sellButtons += node
        }

        fun snapshot(): ScreenSnapshot = ScreenSnapshot(
            symbols.singleOrNull(), lotFields, buyButtons, sellButtons,
            marketExecution, pendingOrderVisible, exnessVisible
        )
    }

    private fun isLotLabel(value: String): Boolean {
        val label = value.lowercase(Locale.ROOT)
        return label.contains("lot") || label.contains("volume") || label.contains("حجم")
    }

    private data class ReadyScreen(val symbol: String, val snapshot: ScreenSnapshot)

    private data class ScreenSnapshot(
        val symbol: String?,
        val lotFields: List<AccessibilityNodeInfo>,
        val buyButtons: List<AccessibilityNodeInfo>,
        val sellButtons: List<AccessibilityNodeInfo>,
        val marketExecution: Boolean,
        val pendingOrderVisible: Boolean,
        val exnessVisible: Boolean
    ) {
        val isMarketOrderScreen: Boolean
            get() = marketExecution || (buyButtons.size == 1 && sellButtons.size == 1)

        fun buttonFor(side: TradeSide): List<AccessibilityNodeInfo> =
            if (side == TradeSide.BUY) buyButtons else sellButtons
    }

    companion object {
        private const val CHANNEL_ID = "mt5_market_automation"
        private const val NOTIFICATION_ID = 21
        private const val UNKNOWN_SYMBOL = "unknown"
        private const val SCREEN_WAIT_TIMEOUT_MS = 15_000L
        private const val POLL_INTERVAL_MS = 150L
        private const val LOT_SETTLE_DELAY_MS = 300L
        @Volatile private var activeService: LotAutomationService? = null

        fun current(): LotAutomationService? = activeService
    }
}
