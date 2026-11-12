package com.rawr.camera.integration

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * X14U video stabilisation: integrates the gyroscope into the camera's turn, follows it with a smoothed path, and
 * sends the difference (the shake) to the native recorder with its timestamp, so each frame is corrected for the
 * moment it was captured (geometry/Stabilizer.h).
 *
 * The smoothed path is an alpha-beta tracker (critically damped), so a steady pan is followed without lagging
 * behind; when the shake would need more margin than the crop leaves, the path is pulled along instead.
 * Camera axes for the back lenses (sensor turned 90°): image x = -device y, image y = -device x, forward = -device z.
 */
internal class StabilizerSensor(
    context: Context,
    private val push: (timeNs: Long, pitch: Float, yaw: Float, roll: Float) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val gyro = manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    @Volatile private var enabled = false
    @Volatile private var crop = 1f
    @Volatile private var seconds = .5f
    @Volatile private var rollToo = true
    @Volatile private var focalNorm = .665f
    /** The horizon lock's current turn (radians), which uses up part of the margin. */
    @Volatile var horizonRoll = 0f
    private var listening = false

    private var lastNanos = 0L
    private val angle = FloatArray(3)
    private val level = FloatArray(3)
    private val velocity = FloatArray(3)

    /** [totalCrop] is the whole fixed zoom (with any horizon lock); [rollToo] when no horizon mode handles roll. */
    @Synchronized
    fun configure(on: Boolean, totalCrop: Float, smoothingSeconds: Float, rollToo: Boolean, focalNorm: Float) {
        this.crop = totalCrop.coerceAtLeast(1f)
        this.seconds = smoothingSeconds.coerceAtLeast(.05f)
        this.rollToo = rollToo
        this.focalNorm = focalNorm.coerceIn(.2f, 6f)
        val want = on && gyro != null
        if (want != enabled) {
            lastNanos = 0L
            angle.fill(0f); level.fill(0f); velocity.fill(0f)
        }
        enabled = want
        if (want && !listening) {
            listening = manager?.registerListener(this, gyro, 5_000) == true
        } else if (!want && listening) {
            manager?.unregisterListener(this)
            listening = false
        }
    }

    fun close() = configure(false, 1f, .5f, true, .665f)

    override fun onSensorChanged(event: SensorEvent) {
        if (!enabled) return
        val t = event.timestamp
        if (lastNanos == 0L) {
            lastNanos = t
            return
        }
        val dt = ((t - lastNanos) / 1e9f).coerceIn(0f, .05f)
        lastNanos = t
        if (dt <= 0f) return
        // Device rates to camera axes (x right, y down, z forward in the image).
        val w = floatArrayOf(-event.values[1], -event.values[0], -event.values[2])
        val alpha = 1f - exp(-dt / seconds)
        val beta = alpha * alpha / (2f - alpha)
        val shake = FloatArray(3)
        for (i in 0..2) {
            angle[i] += w[i] * dt
            val predicted = level[i] + velocity[i] * dt
            val r = angle[i] - predicted
            level[i] = predicted + alpha * r
            velocity[i] += beta / dt * r
            shake[i] = angle[i] - level[i]
        }
        // Margin the crop leaves around the (turned) output frame, in the camera's normalized units, with a little
        // held back for the roll shake and the lens's edges.
        val hx = .5f / focalNorm
        val hy = hx * 9f / 16f
        val c = abs(cos(horizonRoll))
        val s = abs(sin(horizonRoll))
        val mx = ((hx - (hx * c + hy * s) / crop) * .85f).coerceAtLeast(0f)
        val my = ((hy - (hx * s + hy * c) / crop) * .85f).coerceAtLeast(0f)
        // Pitch (about x) moves the image vertically, yaw (about y) horizontally.
        clampAxis(0, shake, my)
        clampAxis(1, shake, mx)
        clampAxis(2, shake, if (rollToo) .035f else 0f)
        push(t, shake[0], shake[1], shake[2])
    }

    /** Keeps the shake on [axis] within [limit], pulling the smoothed path along when it would go past. */
    private fun clampAxis(axis: Int, shake: FloatArray, limit: Float) {
        val v = shake[axis]
        if (abs(v) <= limit) return
        val clamped = if (v < 0f) -limit else limit
        level[axis] = angle[axis] - clamped
        shake[axis] = clamped
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
