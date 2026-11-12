package com.rawr.camera.integration

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * X14U video horizon lock: reads gravity and tells the native pipeline how far the phone is rolled past the framing
 * being kept level, with the fixed crop for the range. The viewfinder and the recording rotate the picture back by
 * that much (see geometry/HorizonLock.h).
 *
 * Framing kept level: an upright or sideways hold (multiple of 90°). Before recording it follows the phone, switching
 * only once the phone is clearly turned (60° past the current one, so it never flickers near a diagonal); during a
 * recording it stays the one the take started with, since the video's orientation is fixed then. Past a limited
 * range the correction eases into its limit and the horizon follows the phone from there, with no jump.
 */
internal class HorizonLockSensor(
    context: Context,
    private val apply: (rollRadians: Float, crop: Float) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val gravity = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)

    /** 0 = off; >= 90 = full. */
    @Volatile private var rangeDegrees = 0
    @Volatile private var crop = 1f
    @Volatile private var recording = false
    @Volatile private var front = false
    private var listening = false
    private var gx = 0f
    private var gy = 0f
    private var primed = false
    private var frozenBase: Float? = null
    private var currentBase: Float? = null

    /** Lock with [range] degrees (0 off, >= 90 full) and its [cropFactor]; [front] lenses see the roll mirrored. */
    @Synchronized
    fun configure(range: Int, cropFactor: Float, recording: Boolean, front: Boolean) {
        this.rangeDegrees = range
        this.crop = if (range > 0) cropFactor.coerceAtLeast(1f) else 1f
        if (!recording) frozenBase = null
        this.recording = recording
        this.front = front
        val want = range > 0 && gravity != null
        if (want && !listening) {
            primed = false
            listening = manager?.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME) == true
        } else if (!want && listening) {
            manager?.unregisterListener(this)
            listening = false
        }
        if (!want) {
            currentBase = null
            apply(0f, 1f)
        }
    }

    fun close() = configure(0, 1f, recording = false, front = false)

    override fun onSensorChanged(event: SensorEvent) {
        val range = rangeDegrees
        if (range <= 0) return
        // Light smoothing (about 60 ms at the game rate) against hand tremor; the vector, not the angle, so it never
        // wraps.
        val x = event.values[0]
        val y = event.values[1]
        if (!primed) {
            gx = x
            gy = y
            primed = true
        } else {
            gx += .25f * (x - gx)
            gy += .25f * (y - gy)
        }
        // Clockwise roll (as the user sees the screen): 0 upright, 90 turned onto the right side.
        val theta = Math.toDegrees(atan2(-gx, gy).toDouble()).toFloat()
        val held = currentBase
        val idleBase = if (held == null || abs(wrap180(theta - held)) > 60f) (theta / 90f).roundToInt() * 90f else held
        currentBase = idleBase
        val base = if (recording) frozenBase ?: idleBase.also { frozenBase = it } else idleBase
        var residual = wrap180(theta - base)
        if (range < 90) residual = softLimit(residual, range.toFloat())
        // The front camera looks back at the user, so the same roll turns its picture the other way.
        if (front) residual = -residual
        apply(Math.toRadians(residual.toDouble()).toFloat(), crop)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** [r] up to near [limit], then easing into it over the last few degrees (never past it). */
    private fun softLimit(r: Float, limit: Float): Float {
        val knee = minOf(5f, limit * .3f)
        val start = limit - knee
        val m = abs(r)
        if (m <= start) return r
        val eased = start + knee * kotlin.math.tanh(((m - start) / knee).toDouble()).toFloat()
        return if (r < 0f) -eased else eased
    }

    private fun wrap180(a: Float): Float {
        var v = a % 360f
        if (v > 180f) v -= 360f
        if (v <= -180f) v += 360f
        return v
    }
}
