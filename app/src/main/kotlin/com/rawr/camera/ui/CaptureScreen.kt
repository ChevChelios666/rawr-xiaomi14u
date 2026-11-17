package com.rawr.camera.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.CaptureFilmEvent
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.model.ViewfinderQuickState
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.model.RenderProfileQuickState
import com.rawr.camera.model.monitorProjection

@Composable
fun CaptureScreen(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    modifier: Modifier = Modifier,
    preview: CapturePreview = CapturePreview(),
    monitor: @Composable () -> Unit = { ExposureMonitor(state.monitorProjection()) },
    renderProfiles: RenderProfileQuickState = RenderProfileQuickState(),
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    latestImageUri: Uri? = null,
    onOpenLatestImage: () -> Unit = {},
    onOpenRenderer: () -> Unit = {},
    onOpenFilmSimSettings: () -> Unit = {},
    onOpenMultiframeSettings: () -> Unit = {},
    onOpenLensSettings: (String) -> Unit = {},
    filmQuick: FilmSimQuickState? = null,
    onFilmEvent: (CaptureFilmEvent) -> Unit = {},
    quick: ViewfinderQuickState = ViewfinderQuickState(),
    onQuickEvent: (CaptureQuickEvent) -> Unit = {},
    videoControls: VideoControlState = VideoControlState(),
    onToggleVideoRecording: () -> Unit = {}
) {
    val haptics = rememberCaptureHaptics()
    // X14U: the Classic style's accent (orange) everywhere the accent is read.
    androidx.compose.runtime.SideEffect { CaptureColors.classic = state.controlSurfaceStyle == com.rawr.camera.model.ControlSurfaceStyle.Classic }
    // X14U Classic: every label engraved into the metal: a fine light edge under each letter, where the cut catches
    // the light (texts with their own style, e.g. the viewfinder's drop shadow, keep it).
    val classicStyle = state.controlSurfaceStyle == com.rawr.camera.model.ControlSurfaceStyle.Classic
    val engraveEdge = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
    val baseText = androidx.compose.material3.LocalTextStyle.current
    val textStyle = if (!classicStyle) baseText else baseText.merge(
        androidx.compose.ui.text.TextStyle(
            shadow = androidx.compose.ui.graphics.Shadow(
                androidx.compose.ui.graphics.Color.White.copy(alpha = .17f),
                androidx.compose.ui.geometry.Offset(0f, engraveEdge),
                0f
            )
        )
    )
    CompositionLocalProvider(
        LocalControlSurfaceStyle provides state.controlSurfaceStyle,
        LocalCaptureHaptics provides haptics,
        androidx.compose.material3.LocalTextStyle provides textStyle
    ) {
        CaptureStateHaptics(state)
        Surface(modifier.fillMaxSize(), color = CaptureColors.Background) {
            PhysicalCaptureLayout(
                state = state,
                dispatch = dispatch,
                preview = preview,
                monitor = monitor,
                renderProfiles = renderProfiles,
                onSelectRenderProfile = onSelectRenderProfile,
                onOpenSettings = onOpenSettings,
                latestImageUri = latestImageUri,
                onOpenLatestImage = onOpenLatestImage,
                onOpenRenderer = onOpenRenderer,
                onOpenFilmSimSettings = onOpenFilmSimSettings,
                onOpenMultiframeSettings = onOpenMultiframeSettings,
                onOpenLensSettings = onOpenLensSettings,
                filmQuick = filmQuick,
                onFilmEvent = onFilmEvent,
                quick = quick,
                onQuickEvent = onQuickEvent,
                videoControls = videoControls,
                onToggleVideoRecording = onToggleVideoRecording,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
            )
        }
    }
}
