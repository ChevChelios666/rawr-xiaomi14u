package com.rawr.camera.video

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * X14U video "hold the shutter to lock" (Nov 17): a tap records as before; holding the record button for [holdMs]
 * records with the chosen settings locked from that moment, released when recording stops. Kept across launches;
 * Settings → Video Encoder changes them.
 */
object VideoLockSettings {
    data class Values(
        val enabled: Boolean = true,
        val holdMs: Int = 1000,
        val shutter: Boolean = true,
        val iso: Boolean = true,
        val focus: Boolean = true,
        val whiteBalance: Boolean = true
    )

    val HOLD_TIMES = listOf(500, 1000, 1500)

    private var prefs: SharedPreferences? = null
    private val flow = MutableStateFlow(Values())
    val values: StateFlow<Values> = flow.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("x14u_video_lock", Context.MODE_PRIVATE)
        prefs = p
        flow.value = Values(
            enabled = p.getBoolean("enabled", true),
            holdMs = p.getInt("holdMs", 1000).takeIf { it in HOLD_TIMES } ?: 1000,
            shutter = p.getBoolean("shutter", true),
            iso = p.getBoolean("iso", true),
            focus = p.getBoolean("focus", true),
            whiteBalance = p.getBoolean("wb", true)
        )
    }

    fun update(next: Values) {
        flow.value = next
        prefs?.edit()
            ?.putBoolean("enabled", next.enabled)
            ?.putInt("holdMs", next.holdMs)
            ?.putBoolean("shutter", next.shutter)
            ?.putBoolean("iso", next.iso)
            ?.putBoolean("focus", next.focus)
            ?.putBoolean("wb", next.whiteBalance)
            ?.apply()
    }
}
