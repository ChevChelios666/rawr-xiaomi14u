package com.rawr.camera.burst

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * X14U Burst mode settings (kept across launches): speed, file format, focus. The top-bar speed chip and the two
 * buttons right of the shutter change them.
 */
object BurstSettings {
    val SPEEDS = listOf(10, 20, 30)
    const val DEFAULT_FPS = 20
    /** Most frames per hold (the shutter's ring fills toward it). */
    const val MAX_SHOTS = 200

    private var prefs: SharedPreferences? = null
    private val fpsFlow = MutableStateFlow(DEFAULT_FPS)
    private val dngFlow = MutableStateFlow(false)
    val fps: StateFlow<Int> = fpsFlow.asStateFlow()
    /** JPEG + DNG instead of JPEG only. */
    val dng: StateFlow<Boolean> = dngFlow.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("x14u_burst", Context.MODE_PRIVATE)
        prefs = p
        fpsFlow.value = p.getInt("fps", DEFAULT_FPS).takeIf { it in SPEEDS } ?: DEFAULT_FPS
        dngFlow.value = p.getBoolean("dng", false)
    }

    fun cycleFps() {
        val next = SPEEDS[(SPEEDS.indexOf(fpsFlow.value) + 1) % SPEEDS.size]
        fpsFlow.value = next
        prefs?.edit()?.putInt("fps", next)?.apply()
    }

    fun setDng(on: Boolean) {
        dngFlow.value = on
        prefs?.edit()?.putBoolean("dng", on)?.apply()
    }
}

/** The run a Burst shutter press starts, from the saved settings. */
object BurstControl {
    fun start(): com.rawr.camera.architecture.StartBurst = com.rawr.camera.architecture.StartBurst(
        fps = BurstSettings.fps.value,
        format = if (BurstSettings.dng.value) com.rawr.camera.model.CaptureOutputFormat.DngAndJpeg
        else com.rawr.camera.model.CaptureOutputFormat.Jpeg,
        limit = BurstSettings.MAX_SHOTS
    )
}
