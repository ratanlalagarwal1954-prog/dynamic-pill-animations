package com.dynamicdroid.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import java.util.Date

/** What the idle island shows: a tiny status bar (time, network, battery). */
data class StatusInfo(
    val time: String = "",
    val battery: Int = -1,
    val charging: Boolean = false,
    /** 0..4 bars, -1 when there is no network at all. */
    val bars: Int = -1,
    val wifi: Boolean = false,
)

/** Feeds StatusInfo from system broadcasts; only polls the radio signal, and only slowly. */
class StatusMonitor(private val context: Context, private val onChange: (StatusInfo) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var battery = -1
    private var charging = false
    private var wifi = false
    private var wifiBars = -1
    private var online = false
    var current = StatusInfo()
        private set
    private var running = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                battery = if (level < 0) -1 else level * 100 / scale
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }
            publish()
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            online = true
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            wifiBars = if (wifi && Build.VERSION.SDK_INT >= 29) rssiBars(caps.signalStrength) else -1
            handler.post { publish() }
        }
        override fun onLost(network: Network) {
            online = false; wifi = false
            handler.post { publish() }
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            publish()
            if (running) handler.postDelayed(this, SIGNAL_POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { cm?.registerDefaultNetworkCallback(netCallback) }
        handler.post(poll)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(poll)
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { cm?.unregisterNetworkCallback(netCallback) }
    }

    private fun publish() {
        val time = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm", Date()).toString()
        val bars = when {
            !online -> -1
            wifi -> if (wifiBars >= 0) wifiBars else 4
            else -> cellBars()
        }
        val next = StatusInfo(time, battery, charging, bars, wifi && online)
        if (next != current) {
            current = next
            onChange(next)
        }
    }

    private fun cellBars(): Int {
        if (Build.VERSION.SDK_INT < 28) return 4
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return 4
        return runCatching { tm.signalStrength?.level }.getOrNull()?.coerceIn(0, 4) ?: 4
    }

    private fun rssiBars(rssi: Int) = when {
        rssi == Int.MIN_VALUE || rssi == 0 -> 4
        rssi > -55 -> 4
        rssi > -66 -> 3
        rssi > -77 -> 2
        rssi > -88 -> 1
        else -> 0
    }

    companion object { private const val SIGNAL_POLL_MS = 20_000L }
}
