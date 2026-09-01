package com.aistudio.autoflow.bxyp

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aistudio.autoflow.bxyp.core.AdjustmentPolicy
import com.aistudio.autoflow.bxyp.core.TradePolicy
import com.aistudio.autoflow.bxyp.core.TradeSide
import com.aistudio.autoflow.bxyp.data.EncryptedSettings
import com.aistudio.autoflow.bxyp.service.LotAutomationService
import com.aistudio.autoflow.bxyp.service.LotCommands
import com.aistudio.autoflow.bxyp.service.TradeRequestResult

class MainActivity : Activity() {
    private lateinit var settings: EncryptedSettings
    private lateinit var status: TextView
    private lateinit var symbol: TextView
    private lateinit var offset: TextView
    private lateinit var step: EditText
    private lateinit var log: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var buy: Button
    private lateinit var sell: Button
    private var pendingStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = EncryptedSettings(applicationContext)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        renderOffset()
        renderLog()
        refreshReadiness()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST && pendingStart) {
            pendingStart = false
            startConnectedService()
        }
    }

    private fun buildContent(): View = ScrollView(this).apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addHeader(this)
            addAdjustmentControls(this)
            addTradeControls(this)
            addView(text(R.string.operation_log_title, 17))
            log = dynamicText(getString(R.string.no_operations), 13)
            addView(log)
        })
    }

    private fun addHeader(layout: LinearLayout) = with(layout) {
        addView(text(R.string.screen_title, 24))
        status = text(R.string.status_checking, 16)
        addView(status)
        symbol = dynamicText(getString(R.string.symbol_value, TradePolicy.BASE_SYMBOL), 18)
        addView(symbol)
        addView(dynamicText(getString(R.string.lot_value, TradePolicy.FIXED_LOT), 18))
        addView(text(R.string.broker_value, 16))
    }

    private fun addAdjustmentControls(layout: LinearLayout) = with(layout) {
        addView(text(R.string.adjustment_step_label, 16))
        step = EditText(this@MainActivity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(settings.readStep(AdjustmentPolicy.DEFAULT_STEP))
            contentDescription = getString(R.string.adjustment_step_description)
        }
        addView(step)
        offset = dynamicText("", 22).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.rgb(255, 213, 79))
            setPadding(dp(12), dp(16), dp(12), dp(16))
        }
        addView(offset, marginParams())
    }

    private fun addTradeControls(layout: LinearLayout) = with(layout) {
        start = button(R.string.action_start, Color.rgb(30, 79, 128)) { startAutomation() }
        stop = button(R.string.action_stop, Color.rgb(80, 80, 80)) { stopAutomation() }
        addView(row(start, stop))
        buy = button(R.string.action_buy_full, Color.rgb(27, 94, 32)) { requestAdjustment(TradeSide.BUY) }
        sell = button(R.string.action_sell_full, Color.rgb(183, 28, 28)) { requestAdjustment(TradeSide.SELL) }
        addView(row(buy, sell))
        addView(text(R.string.usage_hint, 14))
    }

    private fun startAutomation() {
        if (!isMt5Installed()) return status.setText(R.string.status_mt5_missing)
        if (!isAccessibilityEnabled()) {
            status.setText(R.string.status_accessibility_disabled)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStart = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
            return
        }
        startConnectedService()
    }

    private fun startConnectedService() {
        val service = LotAutomationService.current()
        if (service == null) {
            status.setText(R.string.status_service_connecting)
            refreshButtons(false, false)
            return
        }
        try {
            settings.writeReferenceOffset(AdjustmentPolicy.ZERO_OFFSET)
        } catch (_: Exception) {
            status.setText(R.string.reason_storage_unavailable)
            return
        }
        if (!service.startAutomation()) {
            refreshServiceState(service)
            return
        }
        renderOffset()
        refreshReadiness()
    }

    private fun stopAutomation() {
        LotAutomationService.current()?.stopAutomation()
        status.setText(R.string.status_stopped)
        refreshButtons(false, false)
        renderLog()
    }

    private fun requestAdjustment(side: TradeSide) {
        val normalizedStep = AdjustmentPolicy.normalizeStep(step.text?.toString())
        if (normalizedStep == null) return status.setText(R.string.status_invalid_step)
        val service = LotAutomationService.current()
        if (service == null) return status.setText(R.string.status_service_connecting)
        when (service.requestAdjustment(side, normalizedStep)) {
            TradeRequestResult.ACCEPTED -> {
                renderOffset()
                refreshButtons(true, true)
                openMt5()
            }
            TradeRequestResult.INVALID -> status.setText(R.string.status_invalid_step)
            else -> refreshServiceState(service)
        }
    }

    private fun openMt5() {
        val intent = packageManager.getLaunchIntentForPackage(LotCommands.MT5_PACKAGE)
        if (intent == null) {
            status.setText(R.string.status_mt5_missing)
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(intent)
    }

    private fun refreshReadiness() {
        when {
            !isMt5Installed() -> {
                status.setText(R.string.status_mt5_missing)
                refreshButtons(false, false)
            }
            !isAccessibilityEnabled() -> {
                status.setText(R.string.status_enable_accessibility)
                refreshButtons(false, false)
            }
            LotAutomationService.current() == null -> {
                status.setText(R.string.status_service_connecting)
                refreshButtons(false, false)
            }
            else -> refreshServiceState(requireNotNull(LotAutomationService.current()))
        }
    }

    private fun refreshServiceState(service: LotAutomationService) {
        val state = service.state()
        status.text = state.message.ifBlank { getString(R.string.status_ready) }
        state.symbol?.let { symbol.text = getString(R.string.symbol_value, it) }
        refreshButtons(state.armed, state.processing)
    }

    private fun refreshButtons(armed: Boolean, processing: Boolean) {
        start.isEnabled = !armed
        stop.isEnabled = armed
        buy.isEnabled = armed && !processing
        sell.isEnabled = armed && !processing
    }

    private fun renderOffset() {
        val value = settings.readReferenceOffset(AdjustmentPolicy.ZERO_OFFSET)
        val signed = if (value.startsWith("-") || value == AdjustmentPolicy.ZERO_OFFSET) value else "+$value"
        offset.text = getString(R.string.reference_offset_value, signed)
    }

    private fun renderLog() {
        log.text = settings.readLog().asReversed().joinToString("\n").ifBlank { getString(R.string.no_operations) }
    }

    private fun isMt5Installed(): Boolean = try {
        @Suppress("DEPRECATION")
        packageManager.getApplicationInfo(LotCommands.MT5_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == LotAutomationService::class.java.name
        }
    }

    private fun row(vararg buttons: Button) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(4), dp(6), dp(4), dp(6)) }) }
    }

    private fun button(label: Int, color: Int, action: () -> Unit) = Button(this).apply {
        setText(label)
        setTextColor(Color.WHITE)
        setBackgroundColor(color)
        setOnClickListener { action() }
    }

    private fun text(value: Int, size: Int) = dynamicText(getString(value), size)

    private fun dynamicText(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun marginParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(0, dp(8), 0, dp(8)) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val NOTIFICATION_PERMISSION_REQUEST = 501
    }
}
