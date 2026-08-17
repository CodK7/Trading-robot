package com.aistudio.autoflow.bxyp

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aistudio.autoflow.bxyp.core.TradePolicy
import com.aistudio.autoflow.bxyp.data.EncryptedSettings
import com.aistudio.autoflow.bxyp.service.LotCommands

class MainActivity : android.app.Activity() {
    private lateinit var settings: EncryptedSettings
    private lateinit var status: TextView
    private lateinit var symbol: TextView
    private lateinit var log: TextView
    private lateinit var buy: Button
    private lateinit var sell: Button
    private var resultsRegistered = false
    private var armed = false

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            status.text = intent.getStringExtra(LotCommands.EXTRA_RESULT).orEmpty()
            intent.getStringExtra(LotCommands.EXTRA_SYMBOL)?.let {
                symbol.text = getString(R.string.symbol_value, it)
            }
            renderLog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = EncryptedSettings(applicationContext)
        setContentView(buildContent())
        renderLog()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(LotCommands.ACTION_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(resultReceiver, filter)
        }
        resultsRegistered = true
    }

    override fun onResume() {
        super.onResume()
        if (!armed) refreshReadiness()
    }

    override fun onStop() {
        if (resultsRegistered) unregisterReceiver(resultReceiver)
        resultsRegistered = false
        super.onStop()
    }

    private fun buildContent(): View = ScrollView(this).apply {
        val padding = dp(20)
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(text("MT5 Gold Market Orders", 24))
            status = text("Checking MT5…", 16)
            addView(status)
            symbol = text(getString(R.string.symbol_value, TradePolicy.BASE_SYMBOL), 18)
            addView(symbol)
            addView(text("Lot: ${TradePolicy.FIXED_LOT}", 18))
            addView(text("Account: Demo required", 16))
            addView(button("Start") { startAutomation() })
            addView(button("Stop") { stopAutomation() })
            buy = button("+  BUY") { sendCommand(LotCommands.ACTION_BUY) }.apply {
                setBackgroundColor(Color.rgb(27, 94, 32))
                setTextColor(Color.WHITE)
                isEnabled = false
            }
            sell = button("−  SELL") { sendCommand(LotCommands.ACTION_SELL) }.apply {
                setBackgroundColor(Color.rgb(183, 28, 28))
                setTextColor(Color.WHITE)
                isEnabled = false
            }
            addView(buy)
            addView(sell)
            addView(text("Local operation log", 17))
            log = text("No operations yet.", 13)
            addView(log)
        })
    }

    private fun startAutomation() {
        if (!isMt5Installed()) {
            status.setText(R.string.status_mt5_missing)
            return
        }
        if (!isAccessibilityEnabled()) {
            status.setText(R.string.status_accessibility_disabled)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        packageManager.getLaunchIntentForPackage(LotCommands.MT5_PACKAGE)?.let(::startActivity)
        armed = true
        buy.isEnabled = true
        sell.isEnabled = true
        sendCommand(LotCommands.ACTION_START)
        status.setText(R.string.status_starting)
    }

    private fun stopAutomation() {
        armed = false
        buy.isEnabled = false
        sell.isEnabled = false
        sendCommand(LotCommands.ACTION_STOP)
        status.setText(R.string.status_stopped)
    }

    private fun sendCommand(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
    }

    private fun renderLog() {
        log.text = settings.readLog().asReversed().joinToString("\n").ifBlank { "No operations yet." }
    }

    private fun refreshReadiness() {
        status.text = when {
            !isMt5Installed() -> getString(R.string.status_mt5_missing)
            !isAccessibilityEnabled() -> getString(R.string.status_enable_accessibility)
            else -> "Ready. Start opens MT5; + requests one BUY and − requests one SELL."
        }
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
                it.resolveInfo.serviceInfo.name == "${packageName}.service.LotAutomationService"
        }
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
    }

    private fun text(value: String, size: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
