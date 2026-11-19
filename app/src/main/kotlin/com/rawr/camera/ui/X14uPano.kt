package com.rawr.camera.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.LockWhiteBalance
import com.rawr.camera.architecture.SetExposureMode
import com.rawr.camera.architecture.SetFocusMode
import com.rawr.camera.architecture.SetWhiteBalanceMode
import com.rawr.camera.architecture.TriggerCapture
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.ExposureMode
import com.rawr.camera.model.FocusMode
import com.rawr.camera.model.Orientation
import com.rawr.camera.model.WhiteBalanceMode
import com.rawr.camera.storage.CaptureFileNames
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot

/** Pano sweep direction, as the phone is held (landscape: see [PanoSet.screenDirection]). */
internal enum class PanoDirection(val arrow: String) { Right("→"), Left("←"), Up("↑"), Down("↓") }

/** What the set covers: one row (or column), or a 3 × 3 grid around the first frame. */
internal enum class PanoShape(val label: String) { Wide("Wide"), Grid("3×3") }

/**
 * X14U Pano, automatic: the shutter starts a set (frame 1, which locks exposure, focus and white balance) and stops
 * it. In between every frame takes itself: a dot shows where the next frame is, and when it sits in the centre ring
 * and the phone holds still for a moment the frame fires. Wide (Nov 19): the sweep's direction is the way the phone
 * first turns after frame 1, and the set stops at the shutter or by itself after a full turn. Frames are for a panorama merge elsewhere (Lightroom Photo
 * Merge), numbered per set. The phone must be still when a frame fires: exposure is locked (no faster shutter for
 * motion) and a multiframe merge uses frames from just before the shutter.
 */
@Stable
internal class PanoSet {
    var direction by mutableStateOf(PanoDirection.Right)
    /** Held in landscape (followed until a set starts, then kept for the set). */
    var landscape by mutableStateOf(false)
    /**
     * [direction] on the screen (portrait axes): in landscape the top as held is the screen's right edge, so a sweep
     * to the right as held runs down the screen.
     */
    val screenDirection: PanoDirection
        get() = onScreen(direction)

    /** A sweep direction as held, shown on the screen (portrait axes); see [screenDirection]. */
    fun onScreen(held: PanoDirection): PanoDirection = if (!landscape) held else when (held) {
        PanoDirection.Right -> PanoDirection.Down
        PanoDirection.Left -> PanoDirection.Up
        PanoDirection.Up -> PanoDirection.Right
        PanoDirection.Down -> PanoDirection.Left
    }
    var overlap by mutableIntStateOf(30)
    var shape by mutableStateOf(PanoShape.Wide)
    /** Wide: false until the first clear turn after frame 1 picks [direction] (3 × 3: always true). */
    var directionChosen by mutableStateOf(false)
        private set
    /** A small copy of the viewfinder at each frame taken (null where it couldn't be read), for the strip. */
    val thumbs = androidx.compose.runtime.mutableStateListOf<androidx.compose.ui.graphics.ImageBitmap?>()
    /** Where the camera points now (for the strip), or null. */
    var live by mutableStateOf<PanoLive?>(null)
    var frames by mutableIntStateOf(0)
        private set
    var arming by mutableStateOf(false)
    var lastShotAt = 0L
        private set

    /** Set name part, from its start time ("223105"). */
    var tag = ""
        private set
    /** When the set started (for finding its frames to merge). */
    var startedAtMillis = 0L
        private set
    /** Phone orientation at frame 1; every target is an angle from it. */
    var reference: FloatArray? = null
        private set
    /** Field of view across the viewfinder's width and height (degrees), for the set's lens. */
    var fovX = 60f
        private set
    var fovY = 75f
        private set
    /** Frame places, (along, across) degrees from frame 1 in the set's axes; frame 1 is the first. */
    var targets: List<Pair<Float, Float>> = emptyList()
        private set
    /** The targets end the set (3 × 3, or a Wide stop angle). */
    var finite = false
        private set

    private data class Saved(val exposure: ExposureMode, val focus: FocusMode, val wb: WhiteBalanceMode)
    private var saved: Saved? = null

    val inSet: Boolean get() = frames > 0 || arming
    /** Wide up or down as held (so in the saved frames): the merge levels and projects it for that. */
    val heldVertical: Boolean
        get() = shape == PanoShape.Wide && directionChosen && (direction == PanoDirection.Up || direction == PanoDirection.Down)

    /** Up / down sweeps measure the turn as pitch; the rest as yaw. */
    val vertical: Boolean
        get() = shape == PanoShape.Wide && (screenDirection == PanoDirection.Up || screenDirection == PanoDirection.Down)

    fun frameTag(n: Int = frames) = "_PANO${tag}_%02d".format(n)

    /** Locks for frame 1 from what the camera is doing now, and lays out the frame places for [lensFactor]. */
    fun start(state: CaptureUiState, dispatch: CaptureDispatch, lensFactor: Float) {
        saved = Saved(state.exposureControl.mode, state.focus.mode, state.whiteBalanceMode)
        // Hours, minutes and seconds (Nov 19): two sets in the same minute shared "HHmm", so one merge took both
        // sets' frames and "delete frames" removed the other set's.
        tag = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US).format(java.util.Date())
        startedAtMillis = System.currentTimeMillis()
        fovX = fov(lensFactor, alongWidth = true)
        fovY = fov(lensFactor, alongWidth = false)
        directionChosen = shape == PanoShape.Grid
        thumbs.clear()
        live = null
        layOut()
        android.util.Log.i("RawrPano", "PANO_START shape=$shape overlap=$overlap fov=$fovX/$fovY targets=${targets.size}")
        arming = true
        if (state.exposureControl.mode != ExposureMode.Manual &&
            ExposureMode.Manual in state.capabilities.supportedExposureModes) dispatch(SetExposureMode(ExposureMode.Manual))
        if (state.focus.mode == FocusMode.Af) dispatch(SetFocusMode(FocusMode.AfLock))
        if (state.whiteBalanceMode != WhiteBalanceMode.ManualTempTint && state.capabilities.manualWhiteBalanceSupported) {
            dispatch(LockWhiteBalance(state.whiteBalanceTemperatureK, state.whiteBalanceTint))
        }
    }

    private fun layOut() {
        val share = 1f - overlap / 100f
        val stepX = share * fovX
        val stepY = share * fovY
        when (shape) {
            // Centre first, then the ring around it clockwise from the left: every move is to a neighbour.
            PanoShape.Grid -> {
                finite = true
                targets = listOf(
                    0f to 0f, -stepX to 0f, -stepX to stepY, 0f to stepY, stepX to stepY,
                    stepX to 0f, stepX to -stepY, 0f to -stepY, -stepX to -stepY
                )
            }
            // Frame 1 only until the direction is picked; then frames along it up to a full turn (the set ends
            // there by itself).
            PanoShape.Wide -> {
                finite = false
                if (!directionChosen) {
                    targets = listOf(0f to 0f)
                } else {
                    val step = if (vertical) stepY else stepX
                    val sign = if (screenDirection == PanoDirection.Right || screenDirection == PanoDirection.Up) 1f else -1f
                    val count = ceil(360f / step).toInt().coerceIn(2, 72)
                    targets = List(count) { k -> sign * k * step to 0f }
                }
            }
        }
    }

    /** How wide the frames taken so far reach (degrees, the larger of across and up), for the merge's projection. */
    val spanDegrees: Float
        get() {
            val taken = targets.take(frames.coerceAtLeast(1))
            if (taken.isEmpty()) return 0f
            if (shape == PanoShape.Grid) {
                val xs = taken.map { it.first }
                val ys = taken.map { it.second }
                return maxOf(xs.max() - xs.min() + fovX, ys.max() - ys.min() + fovY)
            }
            val along = taken.maxOf { kotlin.math.abs(it.first) } + if (vertical) fovY else fovX
            return maxOf(along, if (vertical) fovX else fovY)
        }

    /** Wide: the sweep goes [screen] (as seen on the screen), picked by the first clear turn after frame 1. */
    fun chooseScreenDirection(screen: PanoDirection) {
        if (directionChosen) return
        direction = PanoDirection.entries.first { onScreen(it) == screen }
        directionChosen = true
        layOut()
        android.util.Log.i("RawrPano", "PANO_DIRECTION screen=$screen held=$direction targets=${targets.size}")
    }

    /** Keeps [bitmap] as frame [n]'s thumbnail (1-based), scaled to about 240 px tall. */
    fun putThumb(n: Int, bitmap: android.graphics.Bitmap?) {
        if (n < 1 || n > frames) {
            bitmap?.recycle()
            return
        }
        val small = bitmap?.let { b ->
            val h = 240
            val w = (b.width * h / b.height.coerceAtLeast(1)).coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(b, w, h, true)
            if (scaled !== b) b.recycle()
            scaled.asImageBitmap()
        }
        while (thumbs.size < n) thumbs.add(null)
        thumbs[n - 1] = small
    }

    /** The next frame's place, or null when the set is complete. */
    val nextTarget: Pair<Float, Float>? get() = targets.getOrNull(frames)

    /** Takes the next frame; frame 1 also fixes the reference orientation. */
    fun shoot(orientation: FloatArray?, dispatch: CaptureDispatch) {
        if (frames == 0) reference = orientation
        android.util.Log.i("RawrPano", "PANO_SHOOT frame=${frames + 1} motion=${orientation != null} reference=${reference != null}")
        arming = false
        frames += 1
        lastShotAt = System.currentTimeMillis()
        CaptureFileNames.setNextStillSuffix(frameTag())
        dispatch(TriggerCapture)
    }

    /** Drops the last frame (the dot goes back to its place); returns its name tag. */
    fun undo(state: CaptureUiState, dispatch: CaptureDispatch): String? {
        if (frames == 0) return null
        val dropped = frameTag()
        frames -= 1
        while (thumbs.size > frames) thumbs.removeAt(thumbs.lastIndex)
        lastShotAt = System.currentTimeMillis()
        if (frames == 0) end(state, dispatch)
        return dropped
    }

    /** Ends the set and gives back the exposure, focus and white balance it locked. */
    fun end(state: CaptureUiState, dispatch: CaptureDispatch) {
        // One line (the caller's stack, kept for the Nov 17 hunt, filled 90 lines of every report).
        if (frames > 0 || arming) android.util.Log.i("RawrPano", "PANO_END frames=$frames arming=$arming")
        saved?.let { s ->
            if (s.exposure != state.exposureControl.mode && s.exposure in state.capabilities.supportedExposureModes) {
                dispatch(SetExposureMode(s.exposure))
            }
            if (s.focus != state.focus.mode) dispatch(SetFocusMode(s.focus))
            if (s.wb != WhiteBalanceMode.ManualTempTint && s.wb != state.whiteBalanceMode) dispatch(SetWhiteBalanceMode(s.wb))
        }
        saved = null
        arming = false
        frames = 0
        reference = null
        thumbs.clear()
        live = null
        directionChosen = false
        CaptureFileNames.setNextStillSuffix("")
    }
}

/**
 * Device orientation (rotation matrix, device to world) from the game rotation vector while [active].
 *
 * Nov 19: twice in one session (after the screen had been off and the activity rebuilt) the sensor gave no readings
 * at all: frame 1 was taken with no orientation, so the set could never aim or go on. A registration that stays
 * silent for 1.5 s is now made again (up to 3 times), and each step is logged.
 */
@Composable
internal fun rememberPanoMotion(active: Boolean): () -> FloatArray? {
    val context = LocalContext.current
    val latest = remember { arrayOfNulls<FloatArray>(1) }
    var attempt by remember { mutableIntStateOf(0) }
    DisposableEffect(active, attempt) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val since = android.os.SystemClock.elapsedRealtime()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, event.values)
                if (latest[0] == null) {
                    android.util.Log.i("RawrPano", "PANO_MOTION_FIRST attempt=$attempt ms=${android.os.SystemClock.elapsedRealtime() - since}")
                }
                latest[0] = r
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (active) {
            val registered = sensor != null && manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            android.util.Log.i("RawrPano", "PANO_MOTION registered=$registered sensor=${sensor?.name} attempt=$attempt")
        }
        onDispose {
            manager?.unregisterListener(listener)
            latest[0] = null
        }
    }
    LaunchedEffect(active, attempt) {
        if (!active) {
            if (attempt != 0) attempt = 0
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(1_500L)
        if (latest[0] == null && attempt < 3) {
            android.util.Log.w("RawrPano", "PANO_MOTION_SILENT attempt=$attempt: registering again")
            attempt += 1
        }
    }
    return { latest[0]?.copyOf() }
}

private fun deg(rad: Float) = Math.toDegrees(rad.toDouble()).toFloat()

/**
 * Where the camera points, in [ref]'s device frame (degrees): yaw toward the screen's right, pitch toward its top,
 * and the sideways and up/down tilt measured from the other axis (for the cross error).
 */
private class Aim(val yaw: Float, val pitch: Float, val up: Float, val side: Float)

private fun aimSince(ref: FloatArray, now: FloatArray): Aim {
    // The camera looks along device -Z.
    val wx = -now[2]
    val wy = -now[5]
    val wz = -now[8]
    val vx = ref[0] * wx + ref[3] * wy + ref[6] * wz
    val vy = ref[1] * wx + ref[4] * wy + ref[7] * wz
    val vz = ref[2] * wx + ref[5] * wy + ref[8] * wz
    return Aim(
        yaw = deg(atan2(vx, -vz)),
        pitch = deg(atan2(vy, -vz)),
        up = deg(asin(vy.coerceIn(-1f, 1f))),
        side = deg(asin(vx.coerceIn(-1f, 1f)))
    )
}

private fun wrap180(a: Float): Float {
    var v = a % 360f
    if (v > 180f) v -= 360f
    if (v <= -180f) v += 360f
    return v
}

/** Field of view across the viewfinder (portrait 3:4) for a lens zoom factor (1x = 23 mm equivalent). */
private fun fov(factor: Float, alongWidth: Boolean): Float {
    val f = 23f * factor.coerceAtLeast(.3f)
    val half = if (alongWidth) 12.98f else 17.31f  // 4:3 frame on the 35 mm diagonal
    return Math.toDegrees(2.0 * atan((half / f).toDouble())).toFloat()
}

/** Live aiming state for the overlay. */
private data class PanoAimState(
    /** Target dot from the viewfinder centre, in fractions of its width / height (+x right, +y down). */
    val dot: Offset,
    val inside: Boolean,
    val hold: Float,
    val fast: Boolean
)

/**
 * Where the camera points, for the strip (Nov 19): degrees from frame 1 in the screen's axes (x right, y up), the
 * turn about the lens (degrees, counter-clockwise as seen on the screen), and the hold (0..1) on the next frame.
 */
internal data class PanoLive(val x: Float, val y: Float, val roll: Float, val hold: Float)

private const val HOLD_MS = 500L
private const val SETTLE_MS = 1200L
/** Still enough to fire: degrees per second. */
private const val STEADY_DPS = 4f
/** Too fast to keep overlap and sharpness. */
private const val FAST_DPS = 35f
/** Wide: a turn this far one way (and twice as far as the other way) after frame 1 picks the sweep's direction. */
private const val PICK_DEG = 8f

/** The phone's turn about the lens since [ref] (degrees, counter-clockwise as seen on the screen). */
private fun rollSince(ref: FloatArray, now: FloatArray): Float {
    val wx = now[0]
    val wy = now[3]
    val wz = now[6]
    val x = ref[0] * wx + ref[3] * wy + ref[6] * wz
    val y = ref[1] * wx + ref[4] * wy + ref[7] * wz
    return deg(atan2(y, x))
}

/**
 * Aiming aid over the viewfinder frame (Nov 19): before a set, Wide | 3×3 and a hint; during one the centre ring,
 * the dot at the next frame's place with a breathing arrow toward it (no disc, no angle), the hold ring filling and a
 * flash when a frame is taken. Wide shows four faint arrows until the first turn picks the way. The frames taken
 * form under the viewfinder ([X14uPanoStrip]). It fires frames ([onFrame]) and ends a complete set ([onFinish]).
 */
@Composable
internal fun X14uPanoOverlay(
    set: PanoSet,
    motion: () -> FloatArray?,
    orientation: Orientation,
    onFrame: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalCaptureHaptics.current
    val landscape = orientation == Orientation.Landscape
    // Directions follow how the phone is held, fixed once a set starts.
    LaunchedEffect(landscape, set.inSet) { if (!set.inSet) set.landscape = landscape }
    var aim by remember { mutableStateOf<PanoAimState?>(null) }
    val flash = remember { Animatable(0f) }
    val frame by rememberUpdatedState(onFrame)
    val finish by rememberUpdatedState(onFinish)
    LaunchedEffect(set) {
        var holdStart = 0L
        var lastA = 0f
        var lastB = 0f
        var lastRawA = Float.NaN
        var accA = 0f
        var lastAt = 0L
        var speed = 0f
        var shotsSeen = set.frames
        var noAimLogged = false
        while (true) {
            kotlinx.coroutines.delay(50L)
            val ref = set.reference
            val now = motion()
            if (set.frames != shotsSeen) {
                // A frame was taken (or undone): flash on a new one, start the next hold fresh.
                if (set.frames > shotsSeen) {
                    haptics.capture()
                    flash.snapTo(.55f)
                    flash.animateTo(0f, tween(260))
                }
                shotsSeen = set.frames
                holdStart = 0L
            }
            if (ref == null || now == null || set.frames == 0) {
                if (set.frames > 0 && !noAimLogged) {
                    android.util.Log.i("RawrPano", "PANO_NO_AIM reference=${ref != null} motion=${now != null}")
                    noAimLogged = true
                }
                aim = null
                set.live = null
                lastRawA = Float.NaN
                accA = 0f
                continue
            }
            val a = aimSince(ref, now)
            val roll = rollSince(ref, now)
            if (!set.directionChosen) {
                // Wide after frame 1: the first clear turn picks the way.
                aim = null
                lastRawA = Float.NaN
                set.live = PanoLive(a.yaw, a.pitch, roll, 0f)
                val ax = abs(a.yaw)
                val ay = abs(a.pitch)
                when {
                    ax >= PICK_DEG && ax > ay * 2f -> set.chooseScreenDirection(if (a.yaw > 0) PanoDirection.Right else PanoDirection.Left)
                    ay >= PICK_DEG && ay > ax * 2f -> set.chooseScreenDirection(if (a.pitch > 0) PanoDirection.Up else PanoDirection.Down)
                }
                continue
            }
            val target = set.nextTarget
            if (target == null) {
                // Every frame is in: the set ends by itself.
                if (aim != null) android.util.Log.i("RawrPano", "PANO_COMPLETE frames=${set.frames} targets=${set.targets.size}")
                aim = null
                if (System.currentTimeMillis() - set.lastShotAt > 700L) finish()
                continue
            }
            // Along (unwrapped past ±180°) and across, in the sweep's axes.
            val rawA = if (set.vertical) a.pitch else a.yaw
            accA = if (lastRawA.isNaN()) rawA else accA + wrap180(rawA - lastRawA)
            lastRawA = rawA
            val b = if (set.vertical) a.side else a.up
            val t = System.currentTimeMillis()
            if (lastAt > 0L) {
                val dt = (t - lastAt).coerceAtLeast(1L) / 1000f
                val instant = hypot(accA - lastA, b - lastB) / dt
                speed += .35f * (instant - speed)
            }
            lastAt = t
            lastA = accA
            lastB = b
            // In the vertical sweep "along" is up the screen and "across" sideways; otherwise the other way round.
            val errA = target.first - accA
            val errB = target.second - b
            val spanA = if (set.vertical) set.fovY else set.fovX
            val spanB = if (set.vertical) set.fovX else set.fovY
            val radA = maxOf(1.5f, spanA * .06f)
            val radB = maxOf(1.5f, spanB * .06f)
            val inside = (errA / radA) * (errA / radA) + (errB / radB) * (errB / radB) <= 1f
            val settled = t - set.lastShotAt > SETTLE_MS
            val steady = speed < STEADY_DPS
            if (inside && steady && settled) {
                if (holdStart == 0L) holdStart = t
            } else holdStart = 0L
            val hold = if (holdStart == 0L) 0f else ((t - holdStart).toFloat() / HOLD_MS).coerceIn(0f, 1f)
            val dot = if (set.vertical) Offset(errB / set.fovX, -errA / set.fovY) else Offset(errA / set.fovX, -errB / set.fovY)
            aim = PanoAimState(dot, inside, hold, speed > FAST_DPS)
            set.live = if (set.vertical) PanoLive(b, accA, roll, hold) else PanoLive(accA, b, roll, hold)
            if (hold >= 1f) {
                holdStart = 0L
                frame()
            }
        }
    }
    // The arrow breathes: fades and grows a little, about once a second.
    val breathing = androidx.compose.animation.core.rememberInfiniteTransition(label = "pano arrow")
    val breath by breathing.animateFloat(
        initialValue = 0f, targetValue = 1f, label = "breath",
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(600), androidx.compose.animation.core.RepeatMode.Reverse
        )
    )
    val accent = CaptureColors.Accent
    val green = Color(0xFF5BD16A)
    Box(modifier.fillMaxSize()) {
        val state = aim
        val picking = set.shape == PanoShape.Wide && !set.directionChosen && !(set.frames > 0 && set.live == null)
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val ring = 30.dp.toPx()
            drawCircle(Color.Black.copy(alpha = .3f), ring, c, style = Stroke(4.dp.toPx()))
            drawCircle(Color.White.copy(alpha = .9f), ring, c, style = Stroke(2.dp.toPx()))
            if (picking) {
                // Any way: four faint arrows around the ring.
                for (k in 0 until 4) {
                    val ang = k * (Math.PI / 2).toFloat()
                    val at = Offset(c.x + kotlin.math.cos(ang) * 66.dp.toPx(), c.y + kotlin.math.sin(ang) * 66.dp.toPx())
                    drawPanoArrow(at, ang, 34.dp.toPx() * (.92f + .08f * breath), .35f + .3f * breath)
                }
            }
            if (state != null) {
                if (state.hold > 0f) {
                    drawArc(green, -90f, 360f * state.hold, false, Offset(c.x - ring, c.y - ring), Size(ring * 2, ring * 2),
                        style = Stroke(4.dp.toPx()))
                }
                val inset = 20.dp.toPx()
                val p = Offset(
                    (c.x + state.dot.x * size.width).coerceIn(inset, size.width - inset),
                    (c.y + state.dot.y * size.height).coerceIn(inset, size.height - inset)
                )
                val gap = hypot(p.x - c.x, p.y - c.y)
                if (!state.inside && gap > ring + 40.dp.toPx()) {
                    val ang = atan2(p.y - c.y, p.x - c.x)
                    val mid = Offset((c.x + p.x) / 2f, (c.y + p.y) / 2f)
                    drawPanoArrow(mid, ang, 46.dp.toPx() * (.9f + .2f * breath), .45f + .55f * breath)
                }
                drawCircle(Color.Black.copy(alpha = .45f), 11.dp.toPx(), p)
                drawCircle(if (state.inside) green else accent, 9.dp.toPx(), p)
            }
            if (flash.value > 0f) drawRect(Color.White.copy(alpha = flash.value))
        }
        if (set.frames > 0) {
            // The locks sit with the other status badges, clear of the histogram (top right as held): portrait top
            // left; landscape the top right as held (the screen's bottom right, above the lens row).
            if (landscape) {
                Column(Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 64.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("AE-L", "AF-L", "WB-L").forEach { PanoBadge(it, accent, orientation) }
                }
            } else {
                Row(Modifier.align(Alignment.TopStart).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("AE-L", "AF-L", "WB-L").forEach { PanoBadge(it, accent, orientation) }
                }
            }
            if (state?.fast == true) {
                Box(Modifier.align(Alignment.Center).offset(x = if (landscape) (-60).dp else 0.dp, y = if (landscape) 0.dp else 60.dp)) {
                    PanoHint("Slow down", orientation)
                }
            }
        } else {
            // Wide | 3×3 at the top as held (the histogram is top right), and how to start.
            PanoShapeChip(
                set, orientation,
                if (landscape) Modifier.align(Alignment.CenterEnd).padding(end = 12.dp) else Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
            )
            Box(Modifier.align(Alignment.Center).offset(x = if (landscape) (-104).dp else 0.dp, y = if (landscape) 0.dp else 104.dp)) {
                PanoHint(
                    when {
                        set.arming -> "Hold still…"
                        set.shape == PanoShape.Grid -> "Tap the shutter, then follow the dot"
                        else -> "Tap the shutter, then turn any way"
                    },
                    orientation
                )
            }
        }
    }
}

/** A plain white arrow with a soft shadow (no disc), centred on [at], pointing along [angle] (radians). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPanoArrow(at: Offset, angle: Float, length: Float, alpha: Float) {
    val ca = kotlin.math.cos(angle)
    val sa = kotlin.math.sin(angle)
    fun p(a: Float, b: Float) = Offset(at.x + a * ca - b * sa, at.y + a * sa + b * ca)
    val l = length
    val path = androidx.compose.ui.graphics.Path().apply {
        val pts = listOf(p(-l / 2, -l * .09f), p(l * .12f, -l * .09f), p(l * .12f, -l * .3f), p(l / 2, 0f),
            p(l * .12f, l * .3f), p(l * .12f, l * .09f), p(-l / 2, l * .09f))
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, Color.Black.copy(alpha = .28f * alpha), style = Stroke(4.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawPath(path, Color.White.copy(alpha = alpha))
}

/** Wide | 3×3 before a set (a set keeps its shape). */
@Composable
private fun PanoShapeChip(set: PanoSet, orientation: Orientation, modifier: Modifier) {
    val haptics = LocalCaptureHaptics.current
    Row(
        modifier
            .uprightLayoutInLandscape(orientation)
            .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(16.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        PanoShape.entries.forEach { shape ->
            val on = set.shape == shape
            Box(
                Modifier
                    .size(52.dp, 28.dp)
                    .background(if (on) CaptureColors.Accent.copy(alpha = .25f) else Color.Transparent, RoundedCornerShape(14.dp))
                    .captureClickable(shape = RoundedCornerShape(14.dp), enabled = !set.inSet) {
                        if (!on) {
                            haptics.selection()
                            set.shape = shape
                        }
                    }
                    .semantics { contentDescription = "Panorama ${shape.label}" },
                contentAlignment = Alignment.Center
            ) {
                Text(shape.label, fontFamily = X14uFont, fontSize = dpText(13f), maxLines = 1,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (on) CaptureColors.Accent else Color.White.copy(alpha = .9f))
            }
        }
    }
}

/** Plain text with a soft shadow over the picture (no pill). */
@Composable
private fun PanoHint(text: String, orientation: Orientation) {
    Text(
        text,
        fontFamily = X14uFont,
        fontSize = dpText(14f),
        color = Color.White,
        maxLines = 1,
        style = androidx.compose.ui.text.TextStyle(
            shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .8f), Offset(0f, 1f), 6f)
        ),
        modifier = Modifier.uprightLayoutInLandscape(orientation)
    )
}

/**
 * The panorama forming (Nov 19), where the readout row and the modes are during a set (both locked then): each frame
 * taken as a small copy of the viewfinder, placed by its angle; the next frame's place dashed (filling green while
 * held); a white box where the camera points now, turned as the phone is. Laid out in the screen's axes, as the
 * viewfinder: it fits the whole set when it can, else follows the next frame.
 */
@Composable
internal fun X14uPanoStrip(set: PanoSet, orientation: Orientation, modifier: Modifier) {
    val green = Color(0xFF5BD16A)
    val live = set.live
    // Frame places in the screen's axes (x right, y up), degrees from frame 1.
    fun screen(t: Pair<Float, Float>): Pair<Float, Float> = if (set.vertical) t.second to t.first else t.first to t.second
    val taken = set.targets.take(set.frames).map(::screen)
    val next = if (set.directionChosen) set.nextTarget?.let(::screen) else null
    Box(modifier.background(Color.Black.copy(alpha = .78f))) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (taken.isEmpty()) return@Canvas
            val fx = set.fovX
            val fy = set.fovY
            val places = taken + listOfNotNull(next)
            val minX = places.minOf { it.first } - fx / 2
            val maxX = places.maxOf { it.first } + fx / 2
            val minY = places.minOf { it.second } - fy / 2
            val maxY = places.maxOf { it.second } + fy / 2
            // Pixels per degree: everything when it fits, but a frame never smaller than half the strip's height
            // (or width) nor taller than it.
            val fitOne = minOf(size.width / fx, size.height / fy)
            val ppd = minOf(size.width / (maxX - minX), size.height / (maxY - minY)).coerceIn(fitOne * .5f, fitOne * .95f)
            // Centre the whole set; when it doesn't fit, slide so the next frame (else the last one) is in view.
            var cx = (minX + maxX) / 2f
            var cy = (minY + maxY) / 2f
            val focus = next ?: taken.last()
            val halfW = size.width / 2f / ppd
            val halfH = size.height / 2f / ppd
            cx = cx.coerceIn(focus.first + fx / 2 - halfW, focus.first - fx / 2 + halfW)
            cy = cy.coerceIn(focus.second + fy / 2 - halfH, focus.second - fy / 2 + halfH)
            fun at(x: Float, y: Float) = Offset(size.width / 2f + (x - cx) * ppd, size.height / 2f - (y - cy) * ppd)
            val fw = fx * ppd
            val fh = fy * ppd
            taken.forEachIndexed { i, (x, y) ->
                val o = at(x, y)
                val topLeft = Offset(o.x - fw / 2, o.y - fh / 2)
                val thumb = set.thumbs.getOrNull(i)
                if (thumb != null) {
                    drawImage(
                        thumb,
                        dstOffset = androidx.compose.ui.unit.IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(fw.toInt(), fh.toInt())
                    )
                } else drawRect(Color.White.copy(alpha = .22f), topLeft, Size(fw, fh))
            }
            next?.let { (x, y) ->
                val o = at(x, y)
                val topLeft = Offset(o.x - fw / 2, o.y - fh / 2)
                val hold = live?.hold ?: 0f
                if (hold > 0f) {
                    drawRect(green.copy(alpha = .35f), Offset(topLeft.x, topLeft.y + fh * (1 - hold)), Size(fw, fh * hold))
                    drawRect(green, topLeft, Size(fw, fh), style = Stroke(1.6.dp.toPx()))
                } else {
                    drawRect(Color.White.copy(alpha = .9f), topLeft, Size(fw, fh), style = Stroke(
                        1.4.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                    ))
                }
            }
            live?.let { l ->
                val o = at(l.x, l.y)
                rotate(-l.roll, o) {
                    drawRect(Color.Black.copy(alpha = .35f), Offset(o.x - fw / 2, o.y - fh / 2), Size(fw, fh), style = Stroke(4.dp.toPx()))
                    drawRect(if (l.hold > 0f) green else Color.White, Offset(o.x - fw / 2, o.y - fh / 2), Size(fw, fh), style = Stroke(2.dp.toPx()))
                }
            }
        }
        Text(
            if (set.finite) "${set.frames} of ${set.targets.size}" else if (set.frames == 1) "1 frame" else "${set.frames} frames",
            fontFamily = X14uFont, fontSize = dpText(11f), color = Color.White.copy(alpha = .7f),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 6.dp).uprightLayoutInLandscape(orientation)
        )
    }
}

@Composable
private fun PanoBadge(text: String, color: Color, orientation: Orientation) {
    Text(
        text,
        fontFamily = X14uFont,
        fontSize = dpText(11.5f),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .uprightLayoutInLandscape(orientation)
            .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    )
}

/** The shutter row in Pano: the shutter starts and stops a set (stop square while one runs); undo during a set. */
internal data class PanoShutter(val onShoot: () -> Unit, val onUndo: (() -> Unit)?, val locked: Boolean)
