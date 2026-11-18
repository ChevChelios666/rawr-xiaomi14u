package com.rawr.camera.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.SetFocusMode
import com.rawr.camera.model.BurstUiState
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.FocusMode
import com.rawr.camera.model.Orientation

/**
 * X14U Burst shutter: hold to shoot, let go to stop. Ready: a dashed ring inside the outline marks it as a burst
 * shutter. Running: the count and speed inside, an accent ring filling toward the run's limit.
 */
@Composable
internal fun X14uBurstShutter(
    burst: BurstUiState,
    fps: Int,
    orientation: Orientation,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    var pressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(if (pressed) .94f else 1f, tween(90), label = "burstPressScale")
    val accent = CaptureColors.Accent
    val progress = if (burst.limit > 0) (burst.shots.toFloat() / burst.limit).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .size(CaptureDimens.ShutterOuter)
            .testTag(CaptureTestTags.SHUTTER)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .semantics {
                role = Role.Button
                contentDescription = if (burst.running) "Burst, ${burst.shots} photos. Let go to stop."
                else "Burst shutter. Hold to shoot at $fps frames a second."
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressed = true
                    haptics.capture()
                    start()
                    try {
                        tryAwaitRelease()
                    } finally {
                        stop()
                        pressed = false
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        // X14U Classic: the metal shutter with a red ring; ready, a dashed ring on its collar; running, the count on the cap.
        val classic = isClassicStyle()
        Canvas(Modifier.size(CaptureDimens.ShutterOuter)) {
            val outline = 2.dp.toPx()
            val r = size.minDimension / 2f
            // Outline, as on the photo shutter; while running, the progress ring over it.
            if (classic) drawMetalKnob(center, r, ring = ClassicColors.shutterRing(pressed || burst.running), pressed = pressed)
            else drawCircle(Color.White.copy(alpha = .86f), radius = r - outline / 2f, style = Stroke(outline))
            if (burst.running) {
                val w = 4.dp.toPx()
                drawArc(
                    color = accent,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2f, w / 2f),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    style = Stroke(w, cap = StrokeCap.Round)
                )
            } else {
                // Ready: the dashed burst ring between the outline and the button.
                val dash = 3.dp.toPx()
                drawCircle(
                    Color.White.copy(alpha = .9f), radius = r - 5.5.dp.toPx(),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)))
                )
            }
        }
        val inner = if (burst.running) CaptureDimens.ShutterInner - 6.dp else CaptureDimens.ShutterInner - 12.dp
        Box(
            Modifier.size(inner).then(
                if (classic) Modifier else Modifier.background(Color(0xFFF5F5F5).copy(alpha = if (pressed) .8f else 1f), CircleShape)
            ),
            contentAlignment = Alignment.Center
        ) {
            if (burst.running) {
                Column(Modifier.uprightInLandscape(orientation), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${burst.shots}", color = if (classic) ClassicColors.Engraved else Color(0xFF1A1A1A), fontFamily = X14uFont,
                        fontWeight = FontWeight.Medium, fontSize = dpText(18f), maxLines = 1)
                    // The speed reached (after the first moment), so a slower save path shows honestly.
                    val elapsed = android.os.SystemClock.uptimeMillis() - burst.startedAtMs
                    val rate = if (elapsed > 700L && burst.shots > 1) (burst.shots * 1000f / elapsed).toInt() else fps
                    Text("$rate fps", color = if (classic) ClassicColors.EngravedDim else Color(0xFF555555), fontFamily = X14uFont, fontSize = dpText(9f), maxLines = 1)
                }
            }
        }
    }
}

/**
 * Burst's two buttons right of the shutter (Photo's HDR+ and FILM places): focus following the subject (AF-C) or
 * held (AF-L), and the file format (JPEG, or JPEG + DNG).
 */
@Composable
internal fun X14uBurstToggles(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    enabled: Boolean,
    pillModifier: Modifier
) {
    val format by com.rawr.camera.burst.BurstSettings.format.collectAsState()
    val focus = state.focus.mode
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        // Always lit (each is a choice): the icon says which.
        CompactSidePill(
            icon = when (focus) {
                FocusMode.Af -> X14uIcons.FocusFollow
                FocusMode.AfLock -> X14uIcons.FocusLock
                FocusMode.Mf -> X14uIcons.FocusManual
            },
            label = when (focus) {
                FocusMode.Af -> "AF-C"
                FocusMode.AfLock -> "AF-L"
                FocusMode.Mf -> "MF"
            },
            active = true,
            testTag = "capture_x14u_burst_focus",
            contentDescription = when (focus) {
                FocusMode.Af -> "Focus follows the subject during the burst. Tap to hold it."
                FocusMode.AfLock -> "Focus held for the burst. Tap to follow the subject."
                FocusMode.Mf -> "Manual focus. Tap for focus that follows the subject."
            },
            onClick = {
                if (enabled) dispatch(SetFocusMode(if (focus == FocusMode.Af) FocusMode.AfLock else FocusMode.Af))
            },
            minWidth = SidePillWidth,
            modifier = pillModifier
        )
        // Always lit (each is a choice): one sheet = JPEG, two = JPEG + DNG, sensor pattern = RAW only.
        CompactSidePill(
            icon = when (format) {
                com.rawr.camera.burst.BurstFormat.Jpeg -> X14uIcons.SheetOne
                com.rawr.camera.burst.BurstFormat.JpegDng -> X14uIcons.SheetTwo
                com.rawr.camera.burst.BurstFormat.Raw -> X14uIcons.SheetRaw
            },
            label = format.label,
            active = true,
            testTag = "capture_x14u_burst_format",
            contentDescription = when (format) {
                com.rawr.camera.burst.BurstFormat.Jpeg -> "Burst saves JPEG (RAW kept privately for 7 days). Tap for JPEG and DNG."
                com.rawr.camera.burst.BurstFormat.JpegDng -> "Burst saves JPEG and DNG. Tap for RAW only."
                com.rawr.camera.burst.BurstFormat.Raw -> "Burst saves RAW only, developed later. Tap for JPEG."
            },
            onClick = { if (enabled) com.rawr.camera.burst.BurstSettings.cycleFormat() },
            minWidth = SidePillWidth,
            modifier = pillModifier
        )
    }
}

/** Burst running: the count and the speed reached, large, in the control area above the shutter. */
@Composable
internal fun X14uBurstCounter(burst: BurstUiState, orientation: Orientation) {
    val elapsed = android.os.SystemClock.uptimeMillis() - burst.startedAtMs
    val rate = if (elapsed > 700L && burst.shots > 1) (burst.shots * 1000f / elapsed).toInt() else burst.fps
    Row(
        Modifier.uprightInLandscape(orientation)
            .background(Color.Black.copy(alpha = .55f), androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("${burst.shots}", color = CaptureColors.Accent, fontFamily = X14uFont, fontWeight = FontWeight.Medium,
            fontSize = dpText(30f), maxLines = 1)
        Column {
            Text("of ${burst.limit}", color = Color.White.copy(alpha = .75f), fontFamily = X14uFont, fontSize = dpText(12f), maxLines = 1)
            Text("$rate fps", color = Color.White, fontFamily = X14uFont, fontSize = dpText(12f), maxLines = 1)
        }
    }
}
