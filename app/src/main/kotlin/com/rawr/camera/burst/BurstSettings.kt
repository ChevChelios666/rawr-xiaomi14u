package com.rawr.camera.burst

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Burst file format: light-developed JPEG, JPEG + DNG, or RAW (DNG) only (developed later, frame by frame). */
enum class BurstFormat(val label: String) { Jpeg("JPEG"), JpegDng("JPEG+DNG"), Raw("RAW") }

/**
 * X14U Burst mode settings (kept across launches): pre-capture length, speed, file format. The top-bar PRE and
 * speed chips and the format button right of the shutter change them.
 */
object BurstSettings {
    val SPEEDS = listOf(10, 20, 30)
    const val DEFAULT_FPS = 20
    /** Pre-capture choices in seconds (0 = off). */
    val PRE_SECONDS = listOf(0, 1, 2, 3)
    const val DEFAULT_PRE = 2
    /** Most live frames per hold (the shutter's ring fills toward it); pre-capture frames come on top. */
    const val MAX_SHOTS = 100
    /** A JPEG-only burst keeps its RAW privately this long (full-quality develop later). */
    const val RAW_KEEP_DAYS = 7

    private var prefs: SharedPreferences? = null
    private val fpsFlow = MutableStateFlow(DEFAULT_FPS)
    private val formatFlow = MutableStateFlow(BurstFormat.Jpeg)
    private val preFlow = MutableStateFlow(DEFAULT_PRE)
    val fps: StateFlow<Int> = fpsFlow.asStateFlow()
    val format: StateFlow<BurstFormat> = formatFlow.asStateFlow()
    /** Seconds kept from before the shutter (0 = off). */
    val pre: StateFlow<Int> = preFlow.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("x14u_burst", Context.MODE_PRIVATE)
        prefs = p
        fpsFlow.value = p.getInt("fps", DEFAULT_FPS).takeIf { it in SPEEDS } ?: DEFAULT_FPS
        formatFlow.value = p.getString("format", null)?.let { name -> BurstFormat.entries.firstOrNull { it.name == name } }
            ?: if (p.getBoolean("dng", false)) BurstFormat.JpegDng else BurstFormat.Jpeg
        preFlow.value = p.getInt("pre", DEFAULT_PRE).takeIf { it in PRE_SECONDS } ?: DEFAULT_PRE
    }

    fun cycleFps() {
        val next = SPEEDS[(SPEEDS.indexOf(fpsFlow.value) + 1) % SPEEDS.size]
        fpsFlow.value = next
        prefs?.edit()?.putInt("fps", next)?.apply()
    }

    fun cycleFormat() {
        val next = BurstFormat.entries[(formatFlow.value.ordinal + 1) % BurstFormat.entries.size]
        formatFlow.value = next
        prefs?.edit()?.putString("format", next.name)?.apply()
    }

    fun cyclePre() {
        val next = PRE_SECONDS[(PRE_SECONDS.indexOf(preFlow.value) + 1) % PRE_SECONDS.size]
        preFlow.value = next
        prefs?.edit()?.putInt("pre", next)?.apply()
    }

    /** Frames the pre-capture ring holds for the current settings. */
    fun preFrames(): Int = pre.value * fps.value
}

/** The run a Burst shutter press starts, from the saved settings. */
object BurstControl {
    fun start(): com.rawr.camera.architecture.StartBurst = com.rawr.camera.architecture.StartBurst(
        fps = BurstSettings.fps.value,
        // Every frame keeps a (private) DNG so the viewer can develop it in full quality; a JPEG burst's DNGs go
        // after RAW_KEEP_DAYS, JPEG+DNG keeps them.
        format = when (BurstSettings.format.value) {
            BurstFormat.Jpeg, BurstFormat.JpegDng -> com.rawr.camera.model.CaptureOutputFormat.DngAndJpeg
            BurstFormat.Raw -> com.rawr.camera.model.CaptureOutputFormat.Dng
        },
        limit = BurstSettings.MAX_SHOTS,
        pre = BurstSettings.pre.value > 0,
        burstFormat = BurstSettings.format.value.name
    )
}
