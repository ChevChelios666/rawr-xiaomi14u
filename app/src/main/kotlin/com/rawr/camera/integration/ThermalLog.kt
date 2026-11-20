package com.rawr.camera.integration

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * X14U (Nov 20): the phone's heat in the diagnostics. 4K30 recordings drop frames once the phone is hot (the HEVC
 * encoder has no spare speed at 4K30 and Android slows it down when hot); these lines show when that starts.
 * While the capture screen is in the foreground: one THERMAL line every 10 s and one on every change of Android's
 * thermal status (0 none, 1 light, 2 moderate, 3 severe, 4 critical, 5 emergency, 6 shutdown). headroom is Android's
 * forecast for 10 s ahead (1.0 = at the point where it starts slowing the phone; NaN when unknown); battery is the
 * battery temperature in degrees C; appMin is how long this app process has been running.
 */
class ThermalLog(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val power = context.getSystemService(PowerManager::class.java)
    private var active = false
    private val tick = object : Runnable {
        override fun run() {
            log("tick")
            handler.postDelayed(this, PERIOD_MS)
        }
    }
    private val statusListener = PowerManager.OnThermalStatusChangedListener { log("change") }

    fun setActive(value: Boolean) {
        handler.post {
            if (value == active) return@post
            active = value
            if (value) {
                runCatching { power?.addThermalStatusListener(statusListener) }
                handler.post(tick)
            } else {
                handler.removeCallbacks(tick)
                runCatching { power?.removeThermalStatusListener(statusListener) }
                log("pause")
            }
        }
    }

    private fun log(why: String) {
        val status = runCatching { power?.currentThermalStatus ?: -1 }.getOrDefault(-1)
        val headroom = runCatching { power?.getThermalHeadroom(10) ?: Float.NaN }.getOrDefault(Float.NaN)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
            ?.let { it / 10f }
        val appMin = (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 60_000f
        Log.i(
            TAG,
            "THERMAL %s status=%d headroom=%.2f battery=%sC appMin=%.1f".format(
                java.util.Locale.ROOT, why, status, headroom, battery?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "?",
                appMin
            )
        )
    }

    private companion object {
        const val TAG = "RawrThermal"
        const val PERIOD_MS = 10_000L
    }
}
