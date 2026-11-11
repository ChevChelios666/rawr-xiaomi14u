package com.rawr.camera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rawr.camera.model.Orientation
import com.rawr.camera.settings.model.FocusLoupeSettings

/**
 * X14U focus loupe: the live view around [center] (viewfinder fractions) magnified into a small rounded window in
 * a corner of the viewfinder, drawn natively (with peaking when it's on); a dashed box marks the spot. The focus
 * meter under it rises toward best focus, with a tick at the highest level since the loupe opened.
 * Drag the window to move it; it settles in the nearest corner. It never affects the photo.
 */
@Composable
internal fun X14uFocusLoupe(
    center: Offset,
    settings: FocusLoupeSettings,
    preview: CapturePreview,
    distanceLabel: String?,
    orientation: Orientation,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalCaptureHaptics.current
    // 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right.
    var corner by remember { mutableIntStateOf(0) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val fw = maxWidth
        val fh = maxHeight
        val w = fw * .56f
        val h = w * .66f
        val margin = 8.dp
        val zoom = settings.zoom.coerceAtLeast(1).toFloat()
        // Source window, kept inside the frame.
        val ww = (w / zoom) / fw
        val wh = (h / zoom) / fh
        val cx = center.x.coerceIn(ww / 2f, 1f - ww / 2f)
        val cy = center.y.coerceIn(wh / 2f, 1f - wh / 2f)
        val window = floatArrayOf(cx - ww / 2f, cy - wh / 2f, cx + ww / 2f, cy + wh / 2f)
        // The chosen corner, unless the spot being magnified sits under it: then the other side.
        fun cornerOrigin(c: Int): Pair<Dp, Dp> =
            (if (c % 2 == 0) margin else fw - w - margin) to (if (c < 2) margin else fh - h - margin)
        val chosen = run {
            val (x, y) = cornerOrigin(corner)
            val overlaps = window[2] * fw > x && window[0] * fw < x + w && window[3] * fh > y && window[1] * fh < y + h
            if (overlaps) corner xor 1 else corner
        }
        val (ox, oy) = cornerOrigin(chosen)
        val x = ox + with(androidx.compose.ui.platform.LocalDensity.current) { drag.x.toDp() }
        val y = oy + with(androidx.compose.ui.platform.LocalDensity.current) { drag.y.toDp() }
        val values = floatArrayOf(
            x / fw, y / fh, w / fw, h / fh,
            window[0], window[1], window[2], window[3],
            (12.dp / h)
        )
        val latestOnLoupe by rememberUpdatedState(preview.onLoupe)
        LaunchedEffect(values.toList(), settings.meter) { latestOnLoupe(values, settings.meter) }
        DisposableEffect(Unit) { onDispose { latestOnLoupe(null, false) } }

        // The magnified spot on the main view.
        Canvas(Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            drawRect(
                Color.White.copy(alpha = .9f),
                topLeft = Offset(window[0] * size.width, window[1] * size.height),
                size = Size((window[2] - window[0]) * size.width, (window[3] - window[1]) * size.height),
                style = Stroke(1.2.dp.toPx(), pathEffect = dash)
            )
        }
        // Frame, labels and meter over the natively drawn window.
        Box(
            Modifier
                .offset(x, y)
                .size(w, h)
                .border(2.dp, Color.White.copy(alpha = .85f), RoundedCornerShape(12.dp))
                .pointerInput(chosen, w, h) {
                    detectDragGestures(
                        onDragEnd = {
                            // Settle in the corner nearest to where it was let go.
                            val px = ox.toPx() + drag.x + w.toPx() / 2f
                            val py = oy.toPx() + drag.y + h.toPx() / 2f
                            corner = (if (px < fw.toPx() / 2f) 0 else 1) + (if (py < fh.toPx() / 2f) 0 else 2)
                            drag = Offset.Zero
                            haptics.selection()
                        },
                        onDragCancel = { drag = Offset.Zero }
                    ) { change, delta ->
                        change.consume()
                        drag += delta
                    }
                }
                .testTag("x14u_focus_loupe")
                .semantics { contentDescription = "Focus loupe, ${settings.zoom} times" }
        ) {
            Text(
                "${settings.zoom}×",
                fontFamily = X14uFont,
                fontSize = dpText(12f),
                color = CaptureColors.Accent,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
                    .background(Color.Black.copy(alpha = .55f), RoundedCornerShape(7.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
                    .uprightInLandscape(orientation)
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .background(Color.Black.copy(alpha = .6f), CircleShape)
                    .captureClickable(shape = CircleShape) {
                        haptics.selection()
                        onClose()
                    }
                    .semantics { contentDescription = "Close loupe" },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", fontFamily = X14uFont, fontSize = dpText(13f), color = Color.White)
            }
            if (distanceLabel != null && !settings.meter) {
                Text(
                    distanceLabel,
                    fontFamily = X14uFont,
                    fontSize = dpText(11f),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(7.dp)
                        .background(Color.Black.copy(alpha = .45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (settings.meter) X14uFocusMeter(preview, distanceLabel, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** Sharpness of the loupe area (relative), with a tick at the highest level since the loupe opened. */
@Composable
private fun X14uFocusMeter(preview: CapturePreview, distanceLabel: String?, modifier: Modifier) {
    var level by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    val read by rememberUpdatedState(preview.focusMeter)
    LaunchedEffect(Unit) {
        while (true) {
            val v = read()
            if (v >= 0f) {
                // Light smoothing against frame noise.
                level = if (level <= 0f) v else level + .45f * (v - level)
                if (level > peak) peak = level
            }
            kotlinx.coroutines.delay(66L)
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(Color.Black.copy(alpha = .45f), RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val labelW = if (distanceLabel != null) 44.dp.toPx() else 0f
            val barW = size.width - labelW
            val scale = (peak * 1.12f).coerceAtLeast(1e-3f)
            val fill = (level / scale).coerceIn(0f, 1f)
            val bh = 5.dp.toPx()
            val top = (size.height - bh) / 2f
            drawRoundRect(Color.White.copy(alpha = .22f), Offset(0f, top), Size(barW, bh),
                androidx.compose.ui.geometry.CornerRadius(bh / 2f))
            drawRoundRect(if (fill > .97f / 1.12f) Color(0xFF5BD16A) else Color.White.copy(alpha = .9f), Offset(0f, top),
                Size(barW * fill, bh), androidx.compose.ui.geometry.CornerRadius(bh / 2f))
            if (peak > 0f) {
                val px = barW / 1.12f
                drawLine(CaptureColors.Accent, Offset(px, top - 4.dp.toPx()), Offset(px, top + bh + 4.dp.toPx()), 2.dp.toPx())
            }
        }
        if (distanceLabel != null) {
            Text(distanceLabel, fontFamily = X14uFont, fontSize = dpText(10.5f), color = Color.White,
                modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}
