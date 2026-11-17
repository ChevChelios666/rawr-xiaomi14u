package com.rawr.camera.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
 * and the phone holds still for a moment the frame fires. Frames are for a panorama merge elsewhere (Lightroom Photo
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
    /** Wide only: stop by itself after this many degrees (0 = until the stop button). */
    var stopAt by mutableIntStateOf(0)
    var frames by mutableIntStateOf(0)
        private set
    var arming by mutableStateOf(false)
    var lastShotAt = 0L
        private set

    /** Set name part, from its start time ("2231"). */
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
    /** Up / down sweeps measure the turn as pitch; the rest as yaw. */
    val vertical: Boolean
        get() = shape == PanoShape.Wide && (screenDirection == PanoDirection.Up || screenDirection == PanoDirection.Down)

    fun frameTag(n: Int = frames) = "_PANO${tag}_%02d".format(n)

    /** Locks for frame 1 from what the camera is doing now, and lays out the frame places for [lensFactor]. */
    fun start(state: CaptureUiState, dispatch: CaptureDispatch, lensFactor: Float) {
        saved = Saved(state.exposureControl.mode, state.focus.mode, state.whiteBalanceMode)
        tag = java.text.SimpleDateFormat("HHmm", java.util.Locale.US).format(java.util.Date())
        startedAtMillis = System.currentTimeMillis()
        fovX = fov(lensFactor, alongWidth = true)
        fovY = fov(lensFactor, alongWidth = false)
        layOut()
        android.util.Log.i("RawrPano", "PANO_START shape=$shape overlap=$overlap stopAt=$stopAt fov=$fovX/$fovY targets=${targets.size}")
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
            PanoShape.Wide -> {
                val step = if (vertical) stepY else stepX
                val span = if (vertical) fovY else fovX
                val sign = if (screenDirection == PanoDirection.Right || screenDirection == PanoDirection.Up) 1f else -1f
                val count = when {
                    stopAt <= 0 -> 72
                    stopAt >= 360 -> ceil(360f / step).toInt()
                    else -> ceil(((stopAt - span).coerceAtLeast(0f)) / step).toInt() + 1
                }.coerceIn(2, 72)
                finite = stopAt > 0
                targets = List(count) { k -> sign * k * step to 0f }
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
        lastShotAt = System.currentTimeMillis()
        if (frames == 0) end(state, dispatch)
        return dropped
    }

    /** Ends the set and gives back the exposure, focus and white balance it locked. */
    fun end(state: CaptureUiState, dispatch: CaptureDispatch) {
        // With the caller's stack: a set that ends after frame 1 shows who ended it (Nov 17 test).
        if (frames > 0 || arming) android.util.Log.i("RawrPano", "PANO_END frames=$frames arming=$arming", Throwable("pano end"))
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
        CaptureFileNames.setNextStillSuffix("")
    }
}

/** Device orientation (rotation matrix, device to world) from the game rotation vector while [active]. */
@Composable
internal fun rememberPanoMotion(active: Boolean): () -> FloatArray? {
    val context = LocalContext.current
    val latest = remember { arrayOfNulls<FloatArray>(1) }
    DisposableEffect(active) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, event.values)
                latest[0] = r
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (active && sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            manager?.unregisterListener(listener)
            latest[0] = null
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
    val message: String
)

private const val HOLD_MS = 500L
private const val SETTLE_MS = 1200L
/** Still enough to fire: degrees per second. */
private const val STEADY_DPS = 4f
/** Too fast to keep overlap and sharpness. */
private const val FAST_DPS = 35f

/**
 * Aiming aid over the viewfinder frame during a set: the centre ring, the dot at the next frame's place, the hold
 * ring filling, a progress strip on top, and a flash when a frame is taken. It fires frames ([onFrame]) and ends a
 * complete set ([onFinish]).
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
            val target = set.nextTarget
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
                lastRawA = Float.NaN
                accA = 0f
                continue
            }
            if (target == null) {
                // Every frame is in: the set ends by itself.
                if (aim != null) android.util.Log.i("RawrPano", "PANO_COMPLETE frames=${set.frames} targets=${set.targets.size}")
                aim = null
                if (System.currentTimeMillis() - set.lastShotAt > 700L) finish()
                continue
            }
            val a = aimSince(ref, now)
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
            val message = when {
                !settled -> "hold…"
                speed > FAST_DPS -> "slow down"
                inside -> "hold still"
                else -> {
                    val far = hypot(errA, errB)
                    // The badge turns with the phone, so the arrow is as held.
                    "%s %.0f°".format(arrowFor(if (set.landscape) Offset(dot.y, -dot.x) else dot), far)
                }
            }
            aim = PanoAimState(dot, inside, hold, message)
            if (hold >= 1f) {
                holdStart = 0L
                frame()
            }
        }
    }
    Box(modifier.fillMaxSize()) {
        if (set.frames > 0) {
            val accent = CaptureColors.Accent
            val green = Color(0xFF5BD16A)
            val state = aim
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val ring = 26.dp.toPx()
                val stroke = 2.dp.toPx()
                drawCircle(Color.White.copy(alpha = .85f), ring, c, style = Stroke(stroke))
                if (state != null) {
                    if (state.hold > 0f) {
                        drawArc(green, -90f, 360f * state.hold, false, Offset(c.x - ring, c.y - ring), Size(ring * 2, ring * 2),
                            style = Stroke(4.dp.toPx()))
                    }
                    val inset = 18.dp.toPx()
                    val p = Offset(
                        (c.x + state.dot.x * size.width).coerceIn(inset, size.width - inset),
                        (c.y + state.dot.y * size.height).coerceIn(inset, size.height - inset)
                    )
                    drawCircle(Color.Black.copy(alpha = .35f), 11.dp.toPx(), p)
                    drawCircle(if (state.inside) green else accent, 8.dp.toPx(), p)
                }
                if (flash.value > 0f) drawRect(Color.White.copy(alpha = flash.value))
            }
            // Placed as the phone is held: in landscape the top is the screen's right edge, the bottom its left.
            if (landscape) {
                Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("AE-L", "AF-L", "WB-L").forEach { PanoBadge(it, CaptureColors.Accent, orientation) }
                }
            } else {
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("AE-L", "AF-L", "WB-L").forEach { PanoBadge(it, CaptureColors.Accent, orientation) }
                }
            }
            PanoProgress(
                set,
                if (landscape) Modifier.align(Alignment.CenterEnd).padding(end = 36.dp).uprightLayoutInLandscape(orientation)
                else Modifier.align(Alignment.TopCenter).padding(top = 36.dp)
            )
            if (state != null) {
                // Below the centre as held, clear of the level line.
                Box(Modifier.align(Alignment.Center).offset(x = if (landscape) (-46).dp else 0.dp, y = if (landscape) 0.dp else 46.dp)) {
                    PanoBadge(state.message, if (state.inside) green else Color.White, orientation)
                }
            }
        } else {
            // Above the centre as held, clear of the level line.
            Box(Modifier.align(Alignment.Center).offset(x = if (landscape) 56.dp else 0.dp, y = if (landscape) 0.dp else (-56).dp)) {
                PanoBadge(if (set.arming) "Hold still…" else "Aim, then tap the shutter to start", Color.White, orientation)
            }
        }
    }
}

private fun arrowFor(dot: Offset): String {
    val ax = abs(dot.x)
    val ay = abs(dot.y)
    return when {
        ax > ay * 2f -> if (dot.x > 0) "→" else "←"
        ay > ax * 2f -> if (dot.y > 0) "↓" else "↑"
        dot.x > 0 && dot.y < 0 -> "↗"
        dot.x > 0 -> "↘"
        dot.y < 0 -> "↖"
        else -> "↙"
    }
}

/** Frames done in the set: a strip of blocks (Wide) or a 3 × 3 map. */
@Composable
private fun PanoProgress(set: PanoSet, modifier: Modifier) {
    val accent = CaptureColors.Accent
    if (set.shape == PanoShape.Grid) {
        // Grid cell of each target, in the order they're taken.
        val cells = listOf(1 to 1, 0 to 1, 0 to 0, 1 to 0, 2 to 0, 2 to 1, 2 to 2, 1 to 2, 0 to 2)
        Canvas(modifier.size(46.dp)) {
            val cell = size.width / 3f
            val gap = 2.dp.toPx()
            cells.forEachIndexed { i, (col, row) ->
                val topLeft = Offset(col * cell + gap / 2f, row * cell + gap / 2f)
                val box = Size(cell - gap, cell - gap)
                when {
                    i < set.frames -> drawRoundRect(accent, topLeft, box, CornerRadius(2.dp.toPx()))
                    i == set.frames -> drawRoundRect(Color.White, topLeft, box, CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                    else -> drawRoundRect(Color.White.copy(alpha = .25f), topLeft, box, CornerRadius(2.dp.toPx()))
                }
            }
        }
    } else {
        val shown = if (set.finite) set.targets.size else (set.frames + 1).coerceAtMost(14)
        val skip = if (set.finite) 0 else (set.frames + 1 - shown).coerceAtLeast(0)
        Row(modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(shown) { k ->
                val i = k + skip
                Box(
                    Modifier
                        .size(width = if (shown > 18) 8.dp else 12.dp, height = 8.dp)
                        .background(
                            when {
                                i < set.frames -> accent
                                i == set.frames -> Color.White
                                else -> Color.White.copy(alpha = .25f)
                            },
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }
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
