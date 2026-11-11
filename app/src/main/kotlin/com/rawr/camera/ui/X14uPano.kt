package com.rawr.camera.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.atan
import kotlin.math.atan2

/** Pano sweep direction, as seen on the viewfinder. */
internal enum class PanoDirection(val arrow: String) { Right("→"), Left("←"), Up("↑"), Down("↓") }

/**
 * X14U Pano: a set of frames for a panorama merge elsewhere (Lightroom Photo Merge). The first frame locks exposure,
 * focus and white balance (and the UI locks lens, f-stop, format and multiframe) so every frame matches; a faded strip
 * of the last frame guides the overlap, and the motion sensor says when the phone has turned far enough.
 */
@Stable
internal class PanoSet {
    var direction by mutableStateOf(PanoDirection.Right)
    var overlap by mutableIntStateOf(30)
    var auto by mutableStateOf(false)
    var frames by mutableIntStateOf(0)
        private set
    /** Set name part, from its start time ("2231"). */
    private var tag = ""
    private val ghosts = mutableStateListOf<ImageBitmap?>()
    private val refs = ArrayList<FloatArray?>()
    val ghost: ImageBitmap? get() = ghosts.lastOrNull()
    val reference: FloatArray? get() = refs.lastOrNull()
    var lastShotAt = 0L
        private set
    var arming by mutableStateOf(false)

    private data class Saved(val exposure: ExposureMode, val focus: FocusMode, val wb: WhiteBalanceMode)
    private var saved: Saved? = null

    val inSet: Boolean get() = frames > 0 || arming

    fun frameTag(n: Int = frames) = "_PANO${tag}_%02d".format(n)

    /** Locks for frame 1, from what the camera is doing now. */
    fun lock(state: CaptureUiState, dispatch: CaptureDispatch) {
        saved = Saved(state.exposureControl.mode, state.focus.mode, state.whiteBalanceMode)
        tag = java.text.SimpleDateFormat("HHmm", java.util.Locale.US).format(java.util.Date())
        if (state.exposureControl.mode != ExposureMode.Manual &&
            ExposureMode.Manual in state.capabilities.supportedExposureModes) dispatch(SetExposureMode(ExposureMode.Manual))
        if (state.focus.mode == FocusMode.Af) dispatch(SetFocusMode(FocusMode.AfLock))
        if (state.whiteBalanceMode != WhiteBalanceMode.ManualTempTint && state.capabilities.manualWhiteBalanceSupported) {
            dispatch(LockWhiteBalance(state.whiteBalanceTemperatureK, state.whiteBalanceTint))
        }
    }

    /** Takes the next frame; returns its number (its ghost arrives later through [setGhost]). */
    fun shoot(reference: FloatArray?, dispatch: CaptureDispatch): Int {
        arming = false
        frames += 1
        ghosts.add(null)
        refs.add(reference)
        lastShotAt = System.currentTimeMillis()
        CaptureFileNames.stillSuffix = frameTag()
        dispatch(TriggerCapture)
        return frames
    }

    fun setGhost(frame: Int, ghost: ImageBitmap?) {
        if (frame in 1..ghosts.size) ghosts[frame - 1] = ghost
    }

    /** Drops the last frame; returns its name tag. */
    fun undo(state: CaptureUiState, dispatch: CaptureDispatch): String? {
        if (frames == 0) return null
        val dropped = frameTag()
        frames -= 1
        ghosts.removeAt(ghosts.lastIndex)
        refs.removeAt(refs.lastIndex)
        if (frames == 0) end(state, dispatch)
        return dropped
    }

    /** Ends the set and gives back the exposure, focus and white balance it locked. */
    fun end(state: CaptureUiState, dispatch: CaptureDispatch) {
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
        ghosts.clear()
        refs.clear()
        CaptureFileNames.stillSuffix = ""
    }
}

/** Device orientation (rotation matrix, device to world) from the game rotation vector while [active]. */
@Composable
internal fun rememberPanoMotion(active: Boolean): () -> FloatArray? {
    val context = LocalContext.current
    val latest = androidx.compose.runtime.remember { arrayOfNulls<FloatArray>(1) }
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

/** Turn since [ref] in degrees: x toward the screen's right, y toward its top. */
private fun turnSince(ref: FloatArray, now: FloatArray): Pair<Float, Float> {
    // The camera looks along device -Z; express today's view direction in the reference's device frame.
    val wx = -now[2]
    val wy = -now[5]
    val wz = -now[8]
    val vx = ref[0] * wx + ref[3] * wy + ref[6] * wz
    val vy = ref[1] * wx + ref[4] * wy + ref[7] * wz
    val vz = ref[2] * wx + ref[5] * wy + ref[8] * wz
    return Math.toDegrees(atan2(vx, -vz).toDouble()).toFloat() to Math.toDegrees(atan2(vy, -vz).toDouble()).toFloat()
}

/** Field of view across the viewfinder (portrait 3:4) for a lens zoom factor (1x = 23 mm equivalent). */
private fun fov(factor: Float, alongWidth: Boolean): Float {
    val f = 23f * factor.coerceAtLeast(.3f)
    val half = if (alongWidth) 12.98f else 17.31f  // 4:3 frame on the 35 mm diagonal
    return Math.toDegrees(2.0 * atan((half / f).toDouble())).toFloat()
}

internal data class PanoGuide(val text: String, val aligned: Boolean)

/** Where the phone points against the next frame's place. Null before frame 1 or without the sensor. */
private fun panoGuide(set: PanoSet, factor: Float, now: FloatArray?): PanoGuide? {
    val ref = set.reference ?: return null
    now ?: return null
    val (x, y) = turnSince(ref, now)
    val horizontal = set.direction == PanoDirection.Right || set.direction == PanoDirection.Left
    val along = when (set.direction) {
        PanoDirection.Right -> x
        PanoDirection.Left -> -x
        PanoDirection.Up -> y
        PanoDirection.Down -> -y
    }
    val cross = if (horizontal) y else x
    val span = fov(factor, alongWidth = horizontal)
    val target = (1f - set.overlap / 100f) * span
    val remaining = target - along
    val tolerance = maxOf(1.5f, target * .08f)
    val crossLimit = maxOf(2f, fov(factor, alongWidth = !horizontal) * .06f)
    return when {
        abs(cross) > crossLimit -> PanoGuide(
            if (horizontal) (if (cross > 0) "tilt down %.0f°" else "tilt up %.0f°").format(abs(cross))
            else (if (cross > 0) "turn left %.0f°" else "turn right %.0f°").format(abs(cross)), false
        )
        abs(remaining) <= tolerance -> PanoGuide("aligned · frame ${set.frames + 1}", true)
        remaining > 0 -> PanoGuide("${set.direction.arrow} %.0f°".format(remaining), false)
        else -> PanoGuide("too far · back %.0f°".format(-remaining), false)
    }
}

/** Ghost strip, arrow, locks and alignment over the viewfinder frame. */
@Composable
internal fun X14uPanoOverlay(
    set: PanoSet,
    lensFactor: Float,
    motion: () -> FloatArray?,
    orientation: Orientation,
    onAutoShoot: () -> Unit,
    modifier: Modifier = Modifier
) {
    var guide by androidx.compose.runtime.remember { mutableStateOf<PanoGuide?>(null) }
    val shoot by rememberUpdatedState(onAutoShoot)
    val factor by rememberUpdatedState(lensFactor)
    LaunchedEffect(set) {
        var alignedSince = 0L
        while (true) {
            val g = panoGuide(set, factor, motion())
            guide = g
            val now = System.currentTimeMillis()
            if (g?.aligned == true && set.auto && set.frames > 0) {
                if (alignedSince == 0L) alignedSince = now
                // Held steady in place, and the last frame had time to leave the sensor.
                if (now - alignedSince > 450L && now - set.lastShotAt > 1500L) {
                    alignedSince = 0L
                    shoot()
                }
            } else alignedSince = 0L
            kotlinx.coroutines.delay(66L)
        }
    }
    Box(modifier.fillMaxSize()) {
        val ghost = set.ghost
        val share = set.overlap / 100f
        if (ghost != null && set.frames > 0) {
            Canvas(Modifier.fillMaxSize()) {
                val bw = ghost.width
                val bh = ghost.height
                // Source strip of the last frame, and where it lands on the next.
                val (src, srcSize, dst, dstSize) = when (set.direction) {
                    PanoDirection.Right -> listOf(
                        IntOffset((bw * (1 - share)).toInt(), 0), IntSize((bw * share).toInt(), bh),
                        IntOffset(0, 0), IntSize((size.width * share).toInt(), size.height.toInt())
                    )
                    PanoDirection.Left -> listOf(
                        IntOffset(0, 0), IntSize((bw * share).toInt(), bh),
                        IntOffset((size.width * (1 - share)).toInt(), 0), IntSize((size.width * share).toInt(), size.height.toInt())
                    )
                    PanoDirection.Up -> listOf(
                        IntOffset(0, 0), IntSize(bw, (bh * share).toInt()),
                        IntOffset(0, (size.height * (1 - share)).toInt()), IntSize(size.width.toInt(), (size.height * share).toInt())
                    )
                    PanoDirection.Down -> listOf(
                        IntOffset(0, (bh * (1 - share)).toInt()), IntSize(bw, (bh * share).toInt()),
                        IntOffset(0, 0), IntSize(size.width.toInt(), (size.height * share).toInt())
                    )
                }.let { Quad(it[0] as IntOffset, it[1] as IntSize, it[2] as IntOffset, it[3] as IntSize) }
                drawImage(ghost, src, srcSize, dst, dstSize, alpha = .45f)
                val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                val edge = Color.White.copy(alpha = .85f)
                val stroke = 1.5.dp.toPx()
                when (set.direction) {
                    PanoDirection.Right -> drawLine(edge, Offset(dstSize.width.toFloat(), 0f), Offset(dstSize.width.toFloat(), size.height), stroke, pathEffect = dash)
                    PanoDirection.Left -> drawLine(edge, Offset(dst.x.toFloat(), 0f), Offset(dst.x.toFloat(), size.height), stroke, pathEffect = dash)
                    PanoDirection.Up -> drawLine(edge, Offset(0f, dst.y.toFloat()), Offset(size.width, dst.y.toFloat()), stroke, pathEffect = dash)
                    PanoDirection.Down -> drawLine(edge, Offset(0f, dstSize.height.toFloat()), Offset(size.width, dstSize.height.toFloat()), stroke, pathEffect = dash)
                }
            }
        }
        if (set.frames > 0) {
            Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("AE-L", "AF-L", "WB-L").forEach { PanoBadge(it, CaptureColors.Accent, orientation) }
            }
            val arrowAlign = when (set.direction) {
                PanoDirection.Right -> Alignment.CenterEnd
                PanoDirection.Left -> Alignment.CenterStart
                PanoDirection.Up -> Alignment.TopCenter
                PanoDirection.Down -> Alignment.BottomCenter
            }
            Text(
                set.direction.arrow, fontFamily = X14uFont, fontSize = dpText(26f), color = CaptureColors.Accent,
                modifier = Modifier.align(arrowAlign).padding(horizontal = 10.dp, vertical = 44.dp)
            )
            val g = guide
            Box(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 52.dp)) {
                PanoBadge(
                    g?.text ?: "${set.frames} frame${if (set.frames == 1) "" else "s"}",
                    if (g?.aligned == true) Color(0xFF5BD16A) else Color.White,
                    orientation
                )
            }
        } else {
            Box(Modifier.align(Alignment.Center)) {
                PanoBadge("Set exposure, then shoot frame 1", Color.White, orientation)
            }
        }
    }
}

private data class Quad(val src: IntOffset, val srcSize: IntSize, val dst: IntOffset, val dstSize: IntSize)

@Composable
private fun PanoBadge(text: String, color: Color, orientation: Orientation) {
    Text(
        text,
        fontFamily = X14uFont,
        fontSize = dpText(11.5f),
        color = color,
        maxLines = 1,
        modifier = Modifier
            .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .uprightInLandscape(orientation)
    )
}

/** Pano's row above the mode strip: direction · overlap · auto before a set; Done during one. */
@Composable
internal fun X14uPanoControls(set: PanoSet, enabled: Boolean, onDone: () -> Unit) {
    val haptics = LocalCaptureHaptics.current
    Row(
        Modifier.height(30.dp).testTag("x14u_pano_controls"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val locked = set.inSet || !enabled
        PanoChip("${set.direction.arrow} ${set.direction.name.lowercase()}", active = false, enabled = !locked) {
            set.direction = PanoDirection.entries[(set.direction.ordinal + 1) % PanoDirection.entries.size]
        }
        PanoChip("overlap ${set.overlap}%", active = false, enabled = !locked) {
            set.overlap = when (set.overlap) { 20 -> 30; 30 -> 40; else -> 20 }
        }
        if (set.inSet) {
            PanoChip("Done · ${set.frames}", active = true, enabled = enabled) {
                haptics.selection()
                onDone()
            }
        } else {
            PanoChip(if (set.auto) "auto on" else "auto off", active = set.auto, enabled = enabled) { set.auto = !set.auto }
        }
    }
}

@Composable
private fun PanoChip(text: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val haptics = LocalCaptureHaptics.current
    Text(
        text,
        fontFamily = X14uFont,
        fontSize = dpText(12.5f),
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        color = if (active) Color(0xFF2B2000) else Color.White,
        maxLines = 1,
        modifier = Modifier
            .alpha(if (enabled) 1f else .45f)
            .background(if (active) CaptureColors.Accent else Color.White.copy(alpha = .12f), RoundedCornerShape(14.dp))
            .captureClickable(shape = RoundedCornerShape(14.dp), enabled = enabled) {
                haptics.selection()
                onClick()
            }
            .semantics { contentDescription = text }
            .padding(horizontal = 11.dp, vertical = 5.dp)
    )
}

/** The shutter row in Pano: the shutter runs the set; undo (during a set) takes the front-camera button's place. */
internal data class PanoShutter(val onShoot: () -> Unit, val onUndo: (() -> Unit)?, val locked: Boolean)

/** Turns a viewfinder copy into the ghost of the frame just taken. */
internal fun android.graphics.Bitmap?.toGhost(): ImageBitmap? = this?.asImageBitmap()

