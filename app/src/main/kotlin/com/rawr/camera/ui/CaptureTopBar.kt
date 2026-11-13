package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.AspectRatio
import com.rawr.camera.ui.icons.outlined.Crop
import com.rawr.camera.ui.icons.outlined.Hd
import com.rawr.camera.ui.icons.outlined.HighQuality
import com.rawr.camera.ui.icons.outlined.Settings
import com.rawr.camera.ui.icons.outlined.SlowMotionVideo
import com.rawr.camera.ui.icons.outlined.Thermostat
import com.rawr.camera.ui.icons.outlined.Timer
import com.rawr.camera.ui.icons.outlined.TimerOff
import com.rawr.camera.ui.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.architecture.CancelSelfTimer
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.CycleOutputFormat
import com.rawr.camera.architecture.CycleSelfTimer
import com.rawr.camera.architecture.CycleVideoFps
import com.rawr.camera.architecture.CycleVideoResolution
import com.rawr.camera.architecture.ToggleMonitorPanel
import com.rawr.camera.architecture.ToggleWhiteBalancePanel
import com.rawr.camera.model.CaptureMode
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.WhiteBalanceMode
import com.rawr.camera.settings.model.SelfTimer
import com.rawr.camera.video.VideoResolutionMode

@Composable
internal fun CaptureTopBar(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    onOpenSettings: () -> Unit,
    videoLocked: Boolean = false,
    /** X14U: LOG lives in the shutter row of the new capture screen. */
    showLog: Boolean = true,
    /** X14U: lens option toggles (DCG, 50 MP), before Settings. */
    toggles: @Composable RowScope.() -> Unit = {},
    /** How many slots [toggles] fills, so the video chevron can be centred. */
    toggleCount: Int = 0,
    /**
     * X14U tools panel: non-null shows its arrow in the eye's place (accent, pointing up, while open) and drops the
     * WB and Settings buttons, which live in the panel.
     */
    toolsOpen: Boolean? = null,
    onToggleTools: () -> Unit = {},
    /** X14U: the crop chip opens the video crop panel (null: the chip cycles 1080p / 4K / Open Gate). */
    onVideoCrop: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** X14U video: over the picture, so only a light shade. */
    transparent: Boolean = false
) {
    val haptics = LocalCaptureHaptics.current
    Row(
        modifier
            .fillMaxWidth()
            .background(if (transparent) Color.Black.copy(alpha = .18f) else CaptureColors.Surface)
            .height(CaptureDimens.TopBarHeight),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // X14U video: the chevron is centred, so both sides get the same number of slots (empty ones fill the
        // shorter side): left resolution, fps (and LOG), right the timer and the lens toggles.
        val videoLeftSlots = 2 + (if (showLog) 1 else 0)
        val videoRightSlots = 1 + toggleCount
        if (state.captureMode == CaptureMode.Video) {
            if (toolsOpen != null) repeat((videoRightSlots - videoLeftSlots).coerceAtLeast(0)) { TopBarSlot {} }
            TopBarSlot {
                VideoTopBarChoice(
                    icon = if (onVideoCrop != null) Icons.Outlined.Crop else state.videoCrop.bitrateClass().topBarIcon(),
                    label = if (onVideoCrop != null) state.videoCrop.chipLabel else state.videoCrop.bitrateClass().topBarLabel(),
                    testTag = CaptureTestTags.TOPBAR_VIDEO_RESOLUTION,
                    description = "Video crop ${state.videoCrop.chipLabel.replace('\n', ' ')}. Tap to change.",
                    enabled = !videoLocked,
                    orientation = state.orientation,
                    // X14U: crop and frame rate turn as one piece (icon and label) in landscape.
                    rotateLabel = false,
                    rotateIcon = false,
                    rotateWhole = true,
                    onClick = {
                        haptics.selection()
                        if (onVideoCrop != null) onVideoCrop() else dispatch(CycleVideoResolution)
                    }
                )
            }
            TopBarSlot {
                VideoTopBarChoice(
                    icon = Icons.Outlined.SlowMotionVideo,
                    label = "${state.videoFps}",
                    testTag = CaptureTestTags.TOPBAR_VIDEO_FPS,
                    description = "Video frame rate ${state.videoFps} fps. Tap to change.",
                    enabled = !videoLocked,
                    orientation = state.orientation,
                    rotateLabel = false,
                    rotateIcon = false,
                    rotateWhole = true,
                    onClick = {
                        haptics.selection()
                        dispatch(CycleVideoFps)
                    }
                )
            }
            if (showLog) TopBarSlot {
                TextButton(
                    onClick = { haptics.selection(); dispatch(com.rawr.camera.architecture.ToggleVideoLog) },
                    enabled = !videoLocked,
                    modifier = Modifier.size(48.dp).testTag(CaptureTestTags.TOPBAR_VIDEO_LOG)
                        .semantics {
                            contentDescription = "LOG recording"
                            role = Role.Switch
                            toggleableState = ToggleableState(state.videoLogEnabled)
                        },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("LOG", fontFamily = CaptureMono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (state.videoLogEnabled) CaptureColors.Accent else CaptureColors.Muted,
                        modifier = Modifier.uprightInLandscape(state.orientation))
                }
            }
            // X14U: the timer sits right of the centred tools chevron (below).
            if (toolsOpen == null) TopBarSlot {
                SelfTimerTopBarChoice(
                    state = state,
                    dispatch = dispatch,
                    orientation = state.orientation
                )
            }
        } else {
            TopBarSlot {
                // A DNG-only (200 MP) lens pins the button to DNG; the saved choice returns on other lenses.
                val dngOnly = state.capabilities.dngOnly
                val format = com.rawr.camera.model.CaptureOutputFormat.from(state.dngEnabled, state.jpegEnabled, dngOnly)
                TextButton(
                    onClick = {
                        haptics.selection()
                        dispatch(CycleOutputFormat)
                    },
                    enabled = !dngOnly,
                    modifier = Modifier.size(48.dp).testTag(CaptureTestTags.TOPBAR_JPEG)
                        .semantics {
                            contentDescription = if (dngOnly) "Output format: DNG only on this lens"
                            else "Output format: ${format.label}. Tap to cycle formats"
                        },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        when (format) {
                            com.rawr.camera.model.CaptureOutputFormat.Dng -> "DNG"
                            com.rawr.camera.model.CaptureOutputFormat.DngAndJpeg -> "DNG +\nJPEG"
                            com.rawr.camera.model.CaptureOutputFormat.Jpeg -> "JPEG"
                        },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 13.sp,
                        color = CaptureColors.Accent,
                        fontFamily = CaptureMono,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.uprightInLandscape(state.orientation)
                    )
                }
            }
            TopBarSlot {
                SelfTimerTopBarChoice(
                    state = state,
                    dispatch = dispatch,
                    orientation = state.orientation
                )
            }
        }
        if (toolsOpen == null) TopBarSlot {
            IconToggleButton(
                checked =
                    state.whiteBalancePanelOpen ||
                        state.whiteBalanceMode != WhiteBalanceMode.Auto,
                onCheckedChange = {
                    haptics.selection()
                    dispatch(ToggleWhiteBalancePanel)
                },
                modifier = Modifier.testTag(CaptureTestTags.TOPBAR_WHITE_BALANCE)
                    .semantics { contentDescription = "White balance" },
                colors =
                    IconButtonDefaults.iconToggleButtonColors(
                        contentColor = CaptureColors.Muted,
                        checkedContentColor = CaptureColors.Accent
                    )
            ) {
                Icon(
                    Icons.Outlined.Thermostat,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp).uprightInLandscape(state.orientation)
                )
            }
        }
        if (toolsOpen != null) {
          TopBarSlot {
            Box(
                Modifier
                    .size(44.dp, 26.dp)
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = .1f), androidx.compose.foundation.shape.RoundedCornerShape(13.dp))
                    .captureClickable(shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp), enabled = !videoLocked) {
                        haptics.selection()
                        onToggleTools()
                    }
                    .testTag("capture_topbar_tools")
                    .semantics { contentDescription = if (toolsOpen) "Close tools" else "Open tools" },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (toolsOpen) X14uIcons.ChevronUp else X14uIcons.ChevronDown,
                    contentDescription = null,
                    tint = if (toolsOpen) CaptureColors.Accent else CaptureColors.Muted,
                    modifier = Modifier.size(20.dp)
                )
            }
          }
            // Video: timer, then empty slots so the chevron stays centred (as many slots right as left).
            if (state.captureMode == CaptureMode.Video) {
                TopBarSlot {
                    SelfTimerTopBarChoice(
                        state = state,
                        dispatch = dispatch,
                        orientation = state.orientation
                    )
                }
                repeat((videoLeftSlots - videoRightSlots).coerceAtLeast(0)) { TopBarSlot {} }
            }
        } else TopBarSlot {
            IconToggleButton(
                checked = state.monitorPanelOpen || state.armedOverlays.isNotEmpty() ||
                    state.falseColorManual || state.activeScopes.isNotEmpty(),
                onCheckedChange = {
                    haptics.selection()
                    dispatch(ToggleMonitorPanel)
                },
                modifier = Modifier.testTag(CaptureTestTags.TOPBAR_MONITOR)
                    .semantics { contentDescription = "Monitoring and overlays" },
                colors =
                    IconButtonDefaults.iconToggleButtonColors(
                        contentColor = CaptureColors.Muted,
                        checkedContentColor = CaptureColors.Accent
                    )
            ) {
                Icon(
                    Icons.Outlined.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp).uprightInLandscape(state.orientation)
                )
            }
        }
        toggles()
        if (toolsOpen == null) TopBarSlot {
            IconButton(
                enabled = !videoLocked,
                onClick = {
                    haptics.selection()
                    onOpenSettings()
                },
                modifier = Modifier.testTag(CaptureTestTags.TOPBAR_SETTINGS)
                    .semantics { contentDescription = "Open Settings" },
                colors = IconButtonDefaults.iconButtonColors(contentColor = CaptureColors.Muted.copy(alpha = .82f))
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(21.dp).uprightInLandscape(state.orientation)
                )
            }
        }
    }
}

private fun VideoResolutionMode.topBarIcon(): ImageVector = when (this) {
    VideoResolutionMode.HD1080 -> Icons.Outlined.Hd
    VideoResolutionMode.UHD4K -> Icons.Outlined.HighQuality
    VideoResolutionMode.OPEN_GATE -> Icons.Outlined.AspectRatio
}

private fun VideoResolutionMode.topBarLabel(): String = when (this) {
    VideoResolutionMode.HD1080 -> "1080p"
    VideoResolutionMode.UHD4K -> "4K"
    VideoResolutionMode.OPEN_GATE -> "OPEN"
}

@Composable
private fun VideoTopBarChoice(
    icon: ImageVector,
    label: String,
    testTag: String,
    description: String,
    enabled: Boolean,
    orientation: com.rawr.camera.model.Orientation,
    rotateLabel: Boolean = true,
    rotateIcon: Boolean = true,
    /** Icon and label turn together (X14U crop and frame rate). */
    rotateWhole: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).testTag(testTag)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp)
    ) {
        Column(
            if (rotateWhole) Modifier.uprightInLandscape(orientation) else Modifier,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) CaptureColors.Accent else CaptureColors.Muted,
                modifier = Modifier.size(20.dp).then(if (rotateIcon) Modifier.uprightInLandscape(orientation) else Modifier)
            )
            Text(
                label,
                color = if (enabled) CaptureColors.Accent else CaptureColors.Muted,
                fontFamily = CaptureMono,
                fontSize = 8.sp,
                lineHeight = 9.sp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                // Two lines when the label asks for it (crop: size over shape).
                maxLines = if ('\n' in label) 2 else 1,
                modifier = if (rotateLabel) Modifier.uprightInLandscape(orientation) else Modifier
            )
        }
    }
}

/**
 * Self-timer slot, immediately left of white balance in both modes. Cycles
 * Off → 3s → 5s → 10s; while a countdown runs it shows the remaining whole
 * seconds and a tap cancels instead of cycling.
 */
@Composable
private fun SelfTimerTopBarChoice(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    orientation: com.rawr.camera.model.Orientation
) {
    val haptics = LocalCaptureHaptics.current
    val remaining = state.selfTimerRemainingMs
    val counting = remaining != null
    val label = if (counting) {
        "${((remaining + 999) / 1000).toInt().coerceAtLeast(1)}s"
    } else {
        state.selfTimer.shortLabel
    }
    val armed = counting || state.selfTimer != SelfTimer.Off
    val description = if (counting) {
        "Self timer counting down $label. Tap to cancel."
    } else {
        "Self timer ${state.selfTimer.label}. Tap to change."
    }
    TextButton(
        onClick = {
            haptics.selection()
            dispatch(if (counting) CancelSelfTimer else CycleSelfTimer)
        },
        modifier = Modifier.size(48.dp).testTag(CaptureTestTags.TOPBAR_SELF_TIMER)
            .semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp)
    ) {
        // X14U: the icon alone, centred and the same size in both states; the seconds are a badge when armed.
        Box(Modifier.size(48.dp).uprightInLandscape(orientation), contentAlignment = Alignment.Center) {
            // Off is just greyed (the plain timer, no slash).
            Icon(
                Icons.Outlined.Timer,
                contentDescription = null,
                tint = if (armed) CaptureColors.Accent else CaptureColors.Muted,
                modifier = Modifier.size(22.dp)
            )
            if (armed) {
                Text(
                    label.removeSuffix("s"),
                    color = CaptureColors.Accent,
                    fontSize = 9.sp,
                    lineHeight = 9.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.Center).offset(x = 12.dp, y = 10.dp)
                )
            }
        }
    }
}

@Composable
internal fun RowScope.TopBarSlot(content: @Composable () -> Unit) {    Box(
        Modifier.weight(1f).fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
