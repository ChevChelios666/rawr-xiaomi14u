package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rawr.camera.architecture.ActivateSpotAe
import com.rawr.camera.architecture.CancelSelfTimer
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.CaptureFilmEvent
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.architecture.CloseFocusSelector
import com.rawr.camera.architecture.FocusAt
import com.rawr.camera.architecture.ToggleMonitorPanel
import com.rawr.camera.architecture.ToggleWhiteBalancePanel
import com.rawr.camera.model.*

@Composable
internal fun CaptureViewfinder(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    preview: CapturePreview,
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    modifier: Modifier,
    /**
     * Top-centered frame for controls and modal palettes (the photo 3:4 frame).
     * Keeps their geometry identical across modes and video resolutions when
     * the viewfinder box itself is taller. Null = this box.
     */
    slotFrame: DpSize? = null,
    /** Video mode hides the film strip (tonemap strip stays); film engine itself is untouched. */
    forceTonemapStrip: Boolean = false,
    /** Forces the compact strip content (used by the video overlay regardless of layout). */
    forceCompact: Boolean = false,
    filmQuick: FilmSimQuickState? = null,
    onFilmEvent: (CaptureFilmEvent) -> Unit = {},
    quick: ViewfinderQuickState = ViewfinderQuickState(),
    onQuickEvent: (CaptureQuickEvent) -> Unit = {},
    /** X14U: a clean viewfinder (no strips or rail; the controls live below it). */
    x14uClean: Boolean = false,
    /** X14U: a tap first closes an open control or panel; true when it did (no focus then). */
    onViewfinderTap: () -> Boolean = { false },
    /** X14U: a vertical swipe on the viewfinder (+1 down, -1 up). */
    onViewfinderSwipe: ((Int) -> Unit)? = null,
    /** X14U: double-tap at a viewfinder point (fractions); null = no double-tap (single taps act at once). */
    onViewfinderDoubleTap: ((Float, Float) -> Unit)? = null,
    /** X14U digital zoom: two-finger pinch, as a scale factor per move; null = no pinch. */
    onViewfinderPinch: ((Float) -> Unit)? = null
) {
    val haptics = LocalCaptureHaptics.current
    val latestViewfinderTap by rememberUpdatedState(onViewfinderTap)
    val latestSwipe by rememberUpdatedState(onViewfinderSwipe)
    val latestDoubleTap by rememberUpdatedState(onViewfinderDoubleTap)
    val latestPinch by rememberUpdatedState(onViewfinderPinch)
    val doubleTapOn = onViewfinderDoubleTap != null
    Box(
        modifier
            .background(Color(0xFF263039))
            .testTag(CaptureTestTags.VIEWFINDER)
            .semantics { contentDescription = "Camera viewfinder" },
        contentAlignment = Alignment.Center
    ) {
        preview.content(Modifier.matchParentSize())
        if (!preview.nativeContent) {
            MonitoringOverlay(state.armedOverlays, state.falseColorManual, Modifier.matchParentSize())
        }
        GridOverlay(state.grid, Modifier.matchParentSize())

        var viewfinderSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        // Focus selector swaps the bottom slot to focus controls instead of
        // overlaying them. Monitor/render/white-balance palettes remain modal.
        val focusOnly =
            state.focus.selectorOpen && !state.monitorPanelOpen &&
                !state.whiteBalancePanelOpen
        val modalOpen = state.monitorPanelOpen || state.whiteBalancePanelOpen
        // The rail shares the right edge with the compact scopes; keep it below them.
        val density = LocalDensity.current
        val railTopLimit = with(density) {
            val bottom = rightScopeBottom(
                viewfinderSize.width.toDp(), viewfinderSize.height.toDp(),
                state.orientation == Orientation.Landscape, state.activeScopes.size,
                preview.deviceRotationDegrees
            )
            if (bottom > 0.dp) bottom + 6.dp else 0.dp
        }
        val popupOpen = focusOnly || modalOpen
        Box(
            Modifier
                .matchParentSize()
                .onSizeChanged { viewfinderSize = it }
                .then(
                    if (!popupOpen) {
                        Modifier.pointerInput(state.orientation, state.exposureControl.mode, doubleTapOn) {
                            detectTapGestures(
                                onDoubleTap = if (!doubleTapOn) null else { offset ->
                                    if (viewfinderSize.width > 0 && viewfinderSize.height > 0) {
                                        latestDoubleTap?.invoke(
                                            offset.x / viewfinderSize.width, offset.y / viewfinderSize.height
                                        )
                                    }
                                },
                                onTap = { offset ->
                                    if (latestViewfinderTap()) return@detectTapGestures
                                    if (viewfinderSize.width > 0 && viewfinderSize.height > 0) {
                                        haptics.selection()
                                        dispatch(FocusAt(offset.toNormalized(viewfinderSize)))
                                    }
                                },
                                onLongPress = { offset ->
                                    if (state.exposureControl.mode == ExposureMode.Auto &&
                                        viewfinderSize.width > 0 && viewfinderSize.height > 0
                                    ) {
                                        haptics.longPress()
                                        dispatch(ActivateSpotAe(offset.toNormalized(viewfinderSize)))
                                    }
                                }
                            )
                        }.then(
                            // Pinch: only while two fingers are down, so one-finger taps and swipes are untouched.
                            if (onViewfinderPinch == null) Modifier else Modifier.pointerInput(Unit) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    do {
                                        val event = awaitPointerEvent()
                                        if (event.changes.count { it.pressed } >= 2) {
                                            val factor = event.calculateZoom()
                                            if (factor != 1f && factor.isFinite()) {
                                                latestPinch?.invoke(factor)
                                                event.changes.forEach { it.consume() }
                                            }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                        ).then(
                            if (onViewfinderSwipe == null) Modifier else Modifier.pointerInput(Unit) {
                                var travel = 0f
                                detectVerticalDragGestures(
                                    onDragStart = { travel = 0f },
                                    onDragEnd = {
                                        val threshold = 48.dp.toPx()
                                        if (travel > threshold) latestSwipe?.invoke(1)
                                        else if (travel < -threshold) latestSwipe?.invoke(-1)
                                    }
                                ) { change, dy ->
                                    travel += dy
                                    change.consume()
                                }
                            }
                        )
                    } else {
                        Modifier
                    }
                )
        )

        ScopeLayer(state, dispatch, preview)
        Box(Modifier.matchParentSize().then(if (popupOpen) Modifier else Modifier.zIndex(2f))) {
            if (preview.nativeContent) preview.faceOverlay() else FaceBoxes(state.faceDetections)
            AfTarget(state, dispatch)
            SpotAeTarget(state, dispatch)
        }

        // Wrapper stays a direct child so the focus-only zIndex still lifts the
        // slot above the z20 dismiss scrim, and modal palettes still cover it.
        val controlFrameModifier = Modifier
            .align(Alignment.TopCenter)
            .then(if (slotFrame != null) Modifier.size(slotFrame) else Modifier.matchParentSize())
        Box(
            controlFrameModifier
                .then(if (focusOnly) Modifier.zIndex(21f) else Modifier)
        ) {
            ViewfinderBottomSlot(
                state = state,
                dispatch = dispatch,
                renderProfiles = renderProfiles,
                onSelectRenderProfile = onSelectRenderProfile,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = CaptureDimens.ViewfinderHorizontalPadding,
                        end = CaptureDimens.ViewfinderHorizontalPadding,
                        bottom = CaptureDimens.ViewfinderBottomPadding
                    ),
                forceCompact = forceCompact,
                forceTonemapStrip = forceTonemapStrip,
                filmQuick = filmQuick,
                onFilmEvent = onFilmEvent,
                quick = quick,
                onQuickEvent = onQuickEvent,
                railTopLimit = railTopLimit,
                x14uClean = x14uClean
            )
        }

        if (popupOpen) {
            // Focus bottom controls sit above the scrim (z21 vs z20) so they stay
            // interactive; taps on the image area still dismiss. Modal palettes
            // keep full coverage.
            Box(
                Modifier
                    .matchParentSize()
                    .zIndex(20f)
                    .pointerInput(
                        state.focus.selectorOpen,
                        state.monitorPanelOpen,
                        state.whiteBalancePanelOpen
                    ) {
                        detectTapGestures(
                            onTap = {
                                if (state.focus.selectorOpen) dispatch(CloseFocusSelector)
                                if (state.monitorPanelOpen) dispatch(ToggleMonitorPanel)
                                if (state.whiteBalancePanelOpen) dispatch(ToggleWhiteBalancePanel)
                            }
                        )
                    }
            )
        }
        if (modalOpen) {
            // Palettes share the photo control frame, even when the video preview
            // bleeds below it. Keep the wrapper above the full-preview dismiss scrim.
            Box(controlFrameModifier.zIndex(21f)) {
                val paletteModifier = Modifier.topBarPaletteModifier(state, this)
                if (state.monitorPanelOpen) {
                    MonitorPanel(state, dispatch, paletteModifier)
                }
                if (state.whiteBalancePanelOpen) {
                    WhiteBalancePanel(state, dispatch, paletteModifier)
                }
            }
        }
        if (state.captureFlash) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = .12f)))
        Box(controlFrameModifier.zIndex(3f)) {
            if (state.capabilities.lensUnavailable) {
                LensUnavailableNotice(state.orientation, Modifier.align(Alignment.Center))
            } else if (state.capabilities.profileError.isNotEmpty()) {
                ProfileErrorNotice(state.capabilities.profileError, state.orientation, Modifier.align(Alignment.Center))
            }
            HdrBracketedNotice(
                active = state.hdrPlusBracketedActive,
                orientation = state.orientation,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
            )
            // One status pill for every capture wait, in one place: moving it
            // while it fades out left an empty pill behind.
            CaptureStatusNotice(
                longExposure = state.longExposure,
                holdActive = state.bracketHoldActive,
                restoringPreview = state.restoringPreview,
                stacked = state.experimentalMultiframeEnabled && state.captureMode == CaptureMode.Photo,
                orientation = state.orientation,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
                fullSizeBinnedStills = state.fullSizeBinnedStills,
                fullSizeBurstsToSingle = state.fullSizeBurstsToSingle
            )
        }
        state.selfTimerRemainingMs?.let { remaining ->
            SelfTimerCountdownOverlay(
                remainingMs = remaining,
                orientation = state.orientation,
                onCancel = {
                    haptics.selection()
                    dispatch(CancelSelfTimer)
                },
                modifier = Modifier.matchParentSize().zIndex(3f)
            )
        }
    }
}

/**
 * Viewfinder bottom slot: focus controls, or the compact exposure/tonemap
 * strip, or the classic quick controls. Anchored to the bottom of the photo
 * 3:4 frame inside the viewfinder box, so its position is identical across
 * capture modes and video resolutions and switching never shifts
 * exposure/tonemap geometry.
 */
@Composable
internal fun ViewfinderBottomSlot(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    modifier: Modifier = Modifier,
    forceCompact: Boolean = false,
    forceTonemapStrip: Boolean = false,
    filmQuick: FilmSimQuickState? = null,
    onFilmEvent: (CaptureFilmEvent) -> Unit = {},
    quick: ViewfinderQuickState = ViewfinderQuickState(),
    onQuickEvent: (CaptureQuickEvent) -> Unit = {},
    railTopLimit: Dp = 0.dp,
    x14uClean: Boolean = false
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(CaptureDimens.ControlGap)
    ) {
        if (state.focus.selectorOpen && !x14uClean) {
            // X14U opens focus below its exposure row instead.
            // Long-press on the focus area swaps the bottom slot: exposure
            // rails + AMSI/SHD-HL are hidden and replaced by focus mode
            // buttons + MF rail. Same slot, same heights, no overlay.
            FocusBottomControls(state, dispatch, Modifier.fillMaxWidth())
        } else if (x14uClean) {
            // X14U: the exposure row, Look and Exposure panels live below the viewfinder.
        } else if (forceCompact || state.captureLayout == CaptureControlLayout.Compact) {
            // V2: bare WB/SS/ISO/EV strip floats over the preview bottom
            // (scrimmed for legibility). Focus selector still swaps in
            // FocusBottomControls; scopes/overlays/targets are untouched.
            CompactViewfinderStrip(
                state,
                dispatch,
                Modifier.fillMaxWidth(),
                forceTonemapStrip = forceTonemapStrip,
                renderProfiles = renderProfiles,
                onSelectRenderProfile = onSelectRenderProfile,
                filmQuick = filmQuick,
                onFilmEvent = onFilmEvent,
                quick = quick,
                onQuickEvent = onQuickEvent,
                railTopLimit = railTopLimit
            )
        } else {
            QuickControlsRow(state, dispatch)
            ExposureArea(state, dispatch)
        }
    }
}

private fun Modifier.topBarPaletteModifier(state: CaptureUiState, scope: BoxScope): Modifier = with(scope) { when {
    state.activeScopes.isEmpty() -> {
        this@topBarPaletteModifier.align(Alignment.Center)
    }

    state.orientation == Orientation.Portrait -> {
        this@topBarPaletteModifier
            .align(Alignment.BottomStart)
            .padding(bottom = CaptureDimens.TopBarPalettePortraitBottomPadding)
    }

    else -> {
        this@topBarPaletteModifier
            .align(Alignment.CenterStart)
            .offset(
                x = CaptureDimens.TopBarPaletteLandscapeStartOffset,
                y = CaptureDimens.TopBarPaletteLandscapeCrossOffset
            )
    }
}

}

private fun Offset.toNormalized(size: androidx.compose.ui.unit.IntSize) = NormalizedPoint(
    x = x / size.width,
    y = y / size.height
)
