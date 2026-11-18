package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import com.rawr.camera.model.Orientation

typealias VideoControlState = com.rawr.camera.model.VideoControlState
typealias VideoTimingState = com.rawr.camera.model.VideoTimingState

/**
 * Record shutter at exactly the photo shutter size ([CaptureDimens.ShutterOuter]):
 * red ring + red dot idle, red ring + white STOP square while recording. The
 * running timer lives in the slot-top overlay ([VideoRecordTimer]), never in
 * this row, so photo/video rows measure identically.
 */
@Composable
internal fun VideoRecordButton(
    state: VideoControlState,
    orientation: Orientation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    countingDown: Boolean = false,
    /**
     * X14U hold-to-lock: holding the idle button this long fills an accent ring, then [onHoldLock] starts a locked
     * recording (a shorter press is a tap: [onClick]). Null or 0: tap only, as before.
     */
    holdMs: Int = 0,
    onHoldLock: (() -> Unit)? = null,
    /** True while the finger is down on the idle button (the viewfinder shows a hint). */
    onHolding: (Boolean) -> Unit = {}
) {
    val haptics = LocalCaptureHaptics.current
    val ring = if (state.busy) CaptureColors.Muted else CaptureColors.Danger
    val holdable = onHoldLock != null && holdMs > 0 && !state.recording && !state.busy && !countingDown
    val fill = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val latestClick by androidx.compose.runtime.rememberUpdatedState(onClick)
    val latestHold by androidx.compose.runtime.rememberUpdatedState(onHoldLock)
    val latestHolding by androidx.compose.runtime.rememberUpdatedState(onHolding)
    val press = if (holdable) {
        Modifier.pointerInput(holdMs) {
            awaitEachGesture {
                awaitFirstDown()
                latestHolding(true)
                val job = scope.launch {
                    fill.snapTo(0f)
                    fill.animateTo(1f, androidx.compose.animation.core.tween(holdMs, easing = androidx.compose.animation.core.LinearEasing))
                }
                var held = false
                val released = withTimeoutOrNull(holdMs.toLong()) { waitForUpOrCancellation() }
                if (released == null) {
                    held = true
                    haptics.capture()
                    latestHolding(false)
                    latestHold?.invoke()
                    waitForUpOrCancellation()
                }
                job.cancel()
                scope.launch { fill.snapTo(0f) }
                latestHolding(false)
                if (!held && released != null) {
                    haptics.capture()
                    latestClick()
                }
            }
        }
    } else {
        Modifier.captureClickable(shape = CircleShape, enabled = !state.busy, onClick = {
            haptics.capture()
            onClick()
        })
    }
    Box(
        modifier
            .size(CaptureDimens.ShutterOuter)
            .testTag(CaptureTestTags.VIDEO_RECORD)
            .then(press)
            .semantics {
                role = Role.Button
                contentDescription = when {
                    countingDown -> "Cancel self timer"
                    state.recording -> "Stop video recording"
                    holdable -> "Start video recording. Hold to record with settings locked"
                    else -> "Start video recording"
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // X14U Classic: the metal shutter with its red ring; a red record dot on the cap (a stop square while recording).
        val classic = isClassicStyle()
        Box(
            Modifier.size(CaptureDimens.ShutterOuter).then(
                if (classic) Modifier.drawBehind { drawMetalKnob(center, size.minDimension / 2f, ring = ClassicColors.shutterRing(state.recording)) }
                else Modifier.border(2.dp, ring, CircleShape)
            ),
            contentAlignment = Alignment.Center
        ) {
            if (classic) {
                if (state.recording) Box(Modifier.size(20.dp).background(ClassicColors.Red, RoundedCornerShape(4.dp)))
                else Box(Modifier.size(22.dp).background(ring, CircleShape))
            } else if (state.recording) {
                Box(
                    Modifier.size(CaptureDimens.VideoStopInner)
                        .background(Color.White, RoundedCornerShape(5.dp))
                )
            } else {
                Box(
                    Modifier.size(CaptureDimens.ShutterInner).background(ring, CircleShape)
                )
            }
        }
        if (fill.value > 0f) {
            androidx.compose.foundation.Canvas(Modifier.size(CaptureDimens.ShutterOuter)) {
                val stroke = 3.dp.toPx()
                drawArc(
                    CaptureColors.Accent, -90f, 360f * fill.value, useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke)
                )
            }
        }
    }
}

/**
 * Floating `REC mm:ss` readout pinned to the viewfinder slot top while
 * recording. Kept out of the shutter row so photo/video rows stay the same
 * height; drop-shadowed like other viewfinder glyphs.
 */
@Composable
internal fun VideoRecordTimer(
    status: String,
    dropped: Long?,
    orientation: Orientation,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .testTag(CaptureTestTags.VIDEO_RECORD_TIMER)
            .semantics { contentDescription = "Video recording $status" }
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            dropped?.let { "DROP $it" } ?: "DROP —",
            color = CaptureColors.Danger,
            fontFamily = CaptureMono,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1,
            style = com.rawr.camera.ui.ViewfinderTextStyle,
            modifier = Modifier.testTag(CaptureTestTags.VIDEO_RECORD_DROPS)
        )
        Box(Modifier.size(8.dp).background(CaptureColors.Danger, CircleShape))
        Text(
            status,
            color = Color.White,
            fontFamily = CaptureMono,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            maxLines = 1,
            style = com.rawr.camera.ui.ViewfinderTextStyle
        )
    }
}
