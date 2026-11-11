package com.rawr.camera.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.SetGrid
import com.rawr.camera.architecture.SetSingleScope
import com.rawr.camera.architecture.SetWhiteBalanceMode
import com.rawr.camera.architecture.ToggleFalseColor
import com.rawr.camera.architecture.ToggleOverlayArmed
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.GridMode
import com.rawr.camera.model.Orientation
import com.rawr.camera.model.OverlayMode
import com.rawr.camera.model.ScopeType
import com.rawr.camera.model.WaveformMode
import com.rawr.camera.model.WhiteBalanceMode
import com.rawr.camera.settings.model.PhotoAspect
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.CenterFocusStrong
import com.rawr.camera.ui.icons.outlined.Palette
import com.rawr.camera.ui.icons.outlined.Settings
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ---- Grid families (one menu entry each; spiral / triangles cycle their orientation on a second tap) ---------------

internal val X14uGridFamilies: List<Pair<String, List<GridMode>>> = listOf(
    "Off" to listOf(GridMode.Off),
    "Thirds" to listOf(GridMode.Thirds),
    "Points" to listOf(GridMode.ThirdsPoints),
    "Phi" to listOf(GridMode.GoldenRatio),
    "Spiral" to listOf(GridMode.SpiralBottomLeft, GridMode.SpiralBottomRight, GridMode.SpiralTopRight, GridMode.SpiralTopLeft),
    "Triangles" to listOf(GridMode.GoldenTriangles, GridMode.GoldenTrianglesMirrored),
    "Diagonals" to listOf(GridMode.Diagonals),
    "4×4" to listOf(GridMode.FourByFour),
    "Cross" to listOf(GridMode.Cross)
)

private fun gridLabel(mode: GridMode): String = X14uGridFamilies.firstOrNull { mode in it.second }?.first ?: "Thirds"

private val WbPresets = listOf(
    WhiteBalanceMode.Auto, WhiteBalanceMode.Daylight, WhiteBalanceMode.CloudyDaylight, WhiteBalanceMode.Shade,
    WhiteBalanceMode.Incandescent, WhiteBalanceMode.Fluorescent, WhiteBalanceMode.WarmFluorescent, WhiteBalanceMode.Twilight
)

private enum class HistogramChoice(val label: String, val short: String, val scope: ScopeType?, val mode: WaveformMode?) {
    Off("Off", "Off", null, null),
    Histogram("Histogram", "Luma", ScopeType.Waveform, WaveformMode.Histogram),
    HistogramRgb("RGB histogram", "RGB", ScopeType.Waveform, WaveformMode.HistogramRgb),
    Waveform("Waveform", "Wave", ScopeType.Waveform, WaveformMode.Luma),
    WaveformRgb("RGB waveform", "RGB wave", ScopeType.Waveform, WaveformMode.RgbOverlay),
    Vector("Vectorscope", "Vector", ScopeType.Vectorscope, null)
}

private fun CaptureUiState.histogramChoice(): HistogramChoice = when {
    activeScopes.isEmpty() -> HistogramChoice.Off
    activeScopes.first() == ScopeType.Vectorscope -> HistogramChoice.Vector
    else -> HistogramChoice.entries.firstOrNull { it.mode == waveformMode } ?: HistogramChoice.Waveform
}

private class X14uTile(
    val key: String,
    val label: String,
    val on: Boolean,
    val icon: ImageVector? = null,
    /** Big text instead of an icon (aspect ratio, WB). */
    val text: String? = null,
    /** Second line under the label (current choice). */
    val value: String? = null,
    val onTap: () -> Unit
)

/**
 * X14U tools panel (swipe down on the viewfinder, or the top-bar arrow): 4 × 3 tiles like stock. On/off tiles toggle;
 * tiles with choices (Grid, Aspect, Histogram, White balance) open them in the bottom row.
 */
@Composable
internal fun X14uToolsPanel(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    video: Boolean,
    frontLens: Boolean,
    choice: String?,
    onChoice: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Stock-sized tiles: 87 dp rows at the stock reference height. */
    tileHeight: androidx.compose.ui.unit.Dp = 86.dp
) {
    val extras = LocalCaptureExtras.current
    val tools = extras.tools
    val haptics = LocalCaptureHaptics.current
    val context = LocalContext.current
    val orientation = state.orientation
    fun locationPermitted() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) extras.onSetLocationTagging(true)
    }
    val toggleChoice: (String) -> Unit = { key -> onChoice(if (choice == key) null else key) }
    val wb = state.whiteBalanceMode
    val tiles = buildList {
        add(X14uTile("grid", "Grid", state.grid != GridMode.Off, X14uIcons.Grid, value = gridLabel(state.grid)) { toggleChoice("grid") })
        if (!video) {
            add(X14uTile("aspect", "Aspect", tools.photoAspect != PhotoAspect.Native,
                text = if (tools.photoAspect == PhotoAspect.Native) "4:3" else tools.photoAspect.label) { toggleChoice("aspect") })
        }
        add(X14uTile("level", "Level", tools.level, X14uIcons.Level) { extras.onToggleLevel() })
        val histogram = state.histogramChoice()
        add(X14uTile("hist", "Histogram", histogram != HistogramChoice.Off, X14uIcons.Histogram,
            value = histogram.short) { toggleChoice("hist") })
        add(X14uTile("peaking", "Peaking", OverlayMode.Peaking in state.armedOverlays, Icons.Outlined.CenterFocusStrong) {
            dispatch(ToggleOverlayArmed(OverlayMode.Peaking))
        })
        add(X14uTile("rawhl", "Raw\nhighlights", OverlayMode.RawHighlights in state.armedOverlays, X14uIcons.Sun) {
            dispatch(ToggleOverlayArmed(OverlayMode.RawHighlights))
        })
        add(X14uTile("shadows", "Tonemap\nshadows", OverlayMode.TonemapShadows in state.armedOverlays, X14uIcons.Moon) {
            dispatch(ToggleOverlayArmed(OverlayMode.TonemapShadows))
        })
        add(X14uTile("falsecolor", "False\ncolour", state.falseColorManual, Icons.Outlined.Palette) { dispatch(ToggleFalseColor) })
        add(X14uTile("wb", "White bal.", wb != WhiteBalanceMode.Auto,
            text = if (wb == WhiteBalanceMode.ManualTempTint) "${state.whiteBalanceTemperatureK}K"
            else if (wb == WhiteBalanceMode.Auto) "AWB" else wb.shortLabel) { toggleChoice("wb") })
        val locationOn = tools.location && locationPermitted()
        add(X14uTile("location", "Location", locationOn, X14uIcons.Pin) {
            when {
                locationOn -> extras.onSetLocationTagging(false)
                locationPermitted() -> extras.onSetLocationTagging(true)
                else -> locationPermission.launch(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                )
            }
        })
        add(X14uTile("settings", "Settings", false, Icons.Outlined.Settings) { onOpenSettings() })
        if (frontLens && !video) {
            add(X14uTile("mirror", "Mirror\nfront", state.selectedLensId in tools.mirroredLenses, X14uIcons.Mirror) {
                extras.onToggleLensMirror(state.selectedLensId)
            })
        }
    }
    val rows = tiles.chunked(4)
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .background(Color(0xF2282421), shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .pointerInput(Unit) {
                var travel = 0f
                detectVerticalDragGestures(onDragStart = { travel = 0f }, onDragEnd = {
                    if (travel < -40.dp.toPx()) onClose()
                }) { change, dy ->
                    travel += dy
                    change.consume()
                }
            }
            .testTag("x14u_tools_panel")
    ) {
        // A choice row replaces the last row of tiles, as on stock.
        val shownRows = if (choice != null && rows.size > 1) rows.dropLast(1) else rows
        shownRows.forEachIndexed { r, row ->
            Row(Modifier.fillMaxWidth()) {
                (0 until 4).forEach { c ->
                    val tile = row.getOrNull(c)
                    Box(Modifier.weight(1f).height(tileHeight)) {
                        if (tile != null) X14uTileView(tile, open = tile.key == choice, orientation = orientation) {
                            haptics.selection()
                            tile.onTap()
                        }
                    }
                }
            }
            if (r < shownRows.size - 1 || choice != null) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.White.copy(alpha = .08f)))
        }
        if (choice != null) {
            val (labels, selectedIndex, pick) = when (choice) {
                "grid" -> Triple(X14uGridFamilies.map { it.first }, X14uGridFamilies.indexOfFirst { state.grid in it.second }) { i: Int ->
                    val modes = X14uGridFamilies[i].second
                    // Tapping the current spiral / triangles again turns it to the next orientation.
                    val next = if (state.grid in modes) modes[(modes.indexOf(state.grid) + 1) % modes.size] else modes.first()
                    dispatch(SetGrid(next))
                }
                "aspect" -> Triple(PhotoAspect.entries.map { it.label }, tools.photoAspect.ordinal) { i: Int ->
                    extras.onSetPhotoAspect(PhotoAspect.entries[i])
                }
                "hist" -> Triple(HistogramChoice.entries.map { it.label }, state.histogramChoice().ordinal) { i: Int ->
                    val c = HistogramChoice.entries[i]
                    dispatch(SetSingleScope(c.scope, c.mode ?: state.waveformMode))
                }
                else -> Triple(WbPresets.map { it.label }, WbPresets.indexOf(wb)) { i: Int -> dispatch(SetWhiteBalanceMode(WbPresets[i])) }
            }
            Row(
                Modifier.fillMaxWidth().height(tileHeight).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                labels.forEachIndexed { i, label ->
                    val on = i == selectedIndex
                    Text(
                        label,
                        fontFamily = X14uFont,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                        fontSize = dpText(16f),
                        maxLines = 1,
                        color = if (on) CaptureColors.Accent else Color.White.copy(alpha = .9f),
                        modifier = Modifier
                            .captureClickable(shape = RoundedCornerShape(14.dp), role = Role.RadioButton) {
                                haptics.selection()
                                pick(i)
                            }
                            .semantics { selected = on }
                            .padding(horizontal = 10.dp, vertical = 10.dp)
                            .uprightInLandscape(orientation)
                    )
                }
            }
        }
    }
}

@Composable
private fun X14uTileView(tile: X14uTile, open: Boolean, orientation: Orientation, onTap: () -> Unit) {
    val color = when {
        open || tile.on -> CaptureColors.Accent
        else -> Color.White.copy(alpha = .92f)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(if (open) Color.White.copy(alpha = .07f) else Color.Transparent)
            .captureClickable(shape = RoundedCornerShape(0.dp), role = Role.Button) { onTap() }
            .semantics { contentDescription = "${tile.label.replace('\n', ' ')} ${tile.value ?: tile.text ?: if (tile.on) "on" else "off"}" }
            .testTag("x14u_tool_${tile.key}"),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.uprightInLandscape(orientation), horizontalAlignment = Alignment.CenterHorizontally) {
            if (tile.text != null) {
                Text(tile.text, fontFamily = X14uFont, fontSize = dpText(21f), maxLines = 1, color = color)
            } else if (tile.icon != null) {
                Icon(tile.icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
            }
            val label = if (tile.value != null) "${tile.label} · ${tile.value}" else tile.label
            Text(
                label,
                fontFamily = X14uFont,
                fontSize = dpText(12.5f),
                lineHeight = dpText(15f),
                maxLines = 2,
                textAlign = TextAlign.Center,
                color = if (tile.on || open) CaptureColors.Accent else Color.White.copy(alpha = .75f),
                modifier = Modifier.padding(top = 7.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

// ---- Viewfinder guides ------------------------------------------------------------------------------------------

/** Dims what an aspect ratio cuts off (the ratio's long side follows the frame's long side, as the file does). */
@Composable
internal fun X14uAspectMask(aspect: PhotoAspect, modifier: Modifier) {
    if (aspect == PhotoAspect.Native) return
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val long = maxOf(w, h)
        val short = minOf(w, h)
        val r = aspect.ratio
        val current = long / short
        if (abs(current - r) < .005f) return@Canvas
        val tall = h >= w
        // The visible window, centred.
        val (vw, vh) = if (r > current) {
            if (tall) (long / r) to h else w to (long / r)
        } else {
            if (tall) w to (short * r) else (short * r) to h
        }
        val left = (w - vw) / 2f
        val top = (h - vh) / 2f
        val scrim = Color.Black.copy(alpha = .6f)
        if (top > 0f) {
            drawRect(scrim, Offset.Zero, Size(w, top))
            drawRect(scrim, Offset(0f, top + vh), Size(w, h - top - vh))
        }
        if (left > 0f) {
            drawRect(scrim, Offset(0f, top), Size(left, vh))
            drawRect(scrim, Offset(left + vw, top), Size(w - left - vw, vh))
        }
        drawRect(Color.White.copy(alpha = .45f), Offset(left, top), Size(vw, vh), style = Stroke(1.dp.toPx()))
    }
}

/** Gravity from the phone's sensor, low-passed (device axes: x right, y up, z out of the screen). */
@Composable
private fun rememberGravity(): FloatArray? {
    val context = LocalContext.current
    var gravity by remember { mutableStateOf<FloatArray?>(null) }
    DisposableEffect(context) {
        val manager = context.getSystemService(android.hardware.SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        val smoothed = FloatArray(3)
        var primed = false
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(event: android.hardware.SensorEvent) {
                val k = .18f
                for (i in 0..2) smoothed[i] = if (primed) smoothed[i] + k * (event.values[i] - smoothed[i]) else event.values[i]
                primed = true
                gravity = smoothed.copyOf()
            }
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) manager.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        onDispose { manager?.unregisterListener(listener) }
    }
    return gravity
}

/**
 * Horizon level: a line with a centre tick that stays level with the world (amber, green within 1°, a click when it
 * gets there). Pointing the camera down at a table turns it into a bubble that centres when the phone is flat.
 */
@Composable
internal fun X14uLevel(modifier: Modifier) {
    val g = rememberGravity() ?: return
    val haptics = LocalCaptureHaptics.current
    val norm = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]).coerceAtLeast(.1f)
    val flat = abs(g[2]) / norm > .9f
    // Roll: 0 upright, + when the phone turns clockwise.
    val roll = Math.toDegrees(atan2(-g[0].toDouble(), g[1].toDouble())).toFloat()
    val nearest = (roll / 90f).roundToInt() * 90f
    val tilt = roll - nearest
    val bubbleX = g[0] / norm
    val bubbleY = -g[1] / norm
    val bubbleOff = Math.toDegrees(kotlin.math.asin(sqrt(bubbleX * bubbleX + bubbleY * bubbleY).coerceAtMost(1f).toDouble())).toFloat()
    val level = if (flat) bubbleOff < 1f else abs(tilt) < 1f
    var wasLevel by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(level) {
        if (level && !wasLevel) haptics.selection()
        wasLevel = level
    }
    val amber = Color(0xFFE8A33A)
    val green = Color(0xFF5BD16A)
    val color = if (level) green else amber
    Canvas(modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val stroke = 1.6.dp.toPx()
        if (flat) {
            val ring = 26.dp.toPx()
            drawCircle(Color.White.copy(alpha = .7f), ring, c, style = Stroke(1.4.dp.toPx()))
            drawCircle(Color.White.copy(alpha = .7f), 5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            // 10° of tilt moves the dot to the ring.
            val reach = ring * ((bubbleOff / 10f).coerceAtMost(1f))
            val len = sqrt(bubbleX * bubbleX + bubbleY * bubbleY).coerceAtLeast(1e-4f)
            drawCircle(color, 6.dp.toPx(), Offset(c.x + bubbleX / len * reach, c.y + bubbleY / len * reach))
        } else {
            val half = minOf(size.width, size.height) * .22f
            val gap = 10.dp.toPx()
            // Fixed reference stubs at the frame's horizontal for the nearest hold, and the live horizon.
            rotate(-nearest, c) {
                drawLine(Color.White.copy(alpha = .55f), Offset(c.x - half - gap - 14.dp.toPx(), c.y), Offset(c.x - half - gap, c.y), stroke)
                drawLine(Color.White.copy(alpha = .55f), Offset(c.x + half + gap, c.y), Offset(c.x + half + gap + 14.dp.toPx(), c.y), stroke)
            }
            rotate(-roll, c) {
                drawLine(color, Offset(c.x - half, c.y), Offset(c.x + half, c.y), stroke)
                drawLine(Color.White.copy(alpha = .85f), Offset(c.x, c.y - 8.dp.toPx()), Offset(c.x, c.y + 8.dp.toPx()), 1.dp.toPx())
            }
        }
    }
}
