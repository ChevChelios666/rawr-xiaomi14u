package com.rawr.camera.architecture

import com.rawr.camera.video.VideoResolutionMode
import com.rawr.camera.model.*

/** Pure reducer for presentation-only capture-screen state. */
object CaptureReducer {
    fun reduce(state: CaptureUiState, action: PresentationCaptureAction): CaptureUiState = when (action) {
        is RestoreCapturePreferences -> {
            state.copy(
                jpegEnabled = action.jpegEnabled,
                dngEnabled = action.dngEnabled,
                grid = action.grid,
                armedOverlays = action.armedOverlays,
                falseColorManual = action.falseColorManual,
                activeScopes = action.activeScopes.distinct().take(3),
                expandedScope = state.expandedScope.takeIf(action.activeScopes::contains),
                waveformMode = action.waveformMode,
                filmSimEnabled = action.filmSimEnabled,
                experimentalMultiframeEnabled = action.experimentalMultiframeEnabled,
                multiframeAlgorithm = action.multiframeAlgorithm,
                captureLayout = action.captureLayout,
                captureMode = action.captureMode,
                // The digital focal length isn't saved: settings restores keep the one in use.
                videoCrop = action.videoCrop.copy(digitalZoom = state.videoCrop.digitalZoom),
                // X14U: 60 fps only for small, light crops (the rest can't develop in 16.6 ms).
                videoFps = action.videoFps.coerceVideoFps()
                    .let { if (it == 60 && !action.videoCrop.allowsSixty()) 30 else it },
                videoLogEnabled = action.videoLogEnabled,
                selfTimer = action.selfTimer,
                // A restored preference never resumes a mid-flight countdown.
                selfTimerRemainingMs = null
            )
        }

        is SetOrientation -> {
            state.copy(orientation = action.value)
        }

        CycleOutputFormat -> {
            // A DNG-only lens shows DNG; the saved choice returns on other lenses.
            if (state.capabilities.dngOnly) state
            else CaptureOutputFormat.from(state.dngEnabled, state.jpegEnabled).next()
                // X14U: Pano with the merge on needs JPEG frames, so DNG only is skipped.
                .let { if (it == CaptureOutputFormat.Dng && CaptureOutputFormat.jpegRequired) it.next() else it }
                .let { state.copy(dngEnabled = it.dng, jpegEnabled = it.jpeg) }
        }

        ToggleMonitorPanel -> {
            state.copy(
                monitorPanelOpen = !state.monitorPanelOpen,
                whiteBalancePanelOpen = false,
                focus = state.focus.copy(selectorOpen = false)
            )
        }

        ToggleWhiteBalancePanel -> {
            state.copy(
                whiteBalancePanelOpen = !state.whiteBalancePanelOpen,
                monitorPanelOpen = false,
                focus = state.focus.copy(selectorOpen = false)
            )
        }

        CloseTopBarPanels -> {
            state.copy(monitorPanelOpen = false, whiteBalancePanelOpen = false)
        }

        is SetControlSurfaceStyle -> {
            state.copy(controlSurfaceStyle = action.style)
        }

        is SetCaptureLayout -> {
            state.copy(captureLayout = action.layout)
        }

        is SetCaptureMode -> {
            if (action.mode == state.captureMode) {
                state
            } else {
                state.copy(
                    captureMode = action.mode,
                    selfTimerRemainingMs = null,
                    selfTimerRunId = state.selfTimerRunId + 1L
                )
            }
        }

        CycleVideoResolution -> {
            // The classic layout's chip: the former 1080p, 4K and Open Gate in turn.
            val presets = listOf(
                com.rawr.camera.video.VideoCrop.fromLegacy(VideoResolutionMode.HD1080),
                com.rawr.camera.video.VideoCrop.fromLegacy(VideoResolutionMode.UHD4K),
                com.rawr.camera.video.VideoCrop.fromLegacy(VideoResolutionMode.OPEN_GATE)
            )
            val crop = presets[(presets.indexOf(state.videoCrop) + 1) % presets.size]
            state.copy(videoCrop = crop, videoFps = if (state.videoFps == 60 && !crop.allowsSixty()) 30 else state.videoFps)
        }

        is SetVideoCrop -> state.copy(
            videoCrop = action.crop,
            videoFps = if (state.videoFps == 60 && !action.crop.allowsSixty()) 30 else state.videoFps
        )

        ToggleVideoLog -> if (state.captureMode == CaptureMode.Video) state.copy(videoLogEnabled = !state.videoLogEnabled) else state

        CycleVideoFps -> {
            val sixty = state.videoCrop.allowsSixty() && state.capabilities.videoSixtyFps
            state.copy(videoFps = when (state.videoFps) { 24 -> 30; 30 -> if (sixty) 60 else 24; else -> 24 })
        }

        CycleSelfTimer -> {
            state.copy(
                selfTimer = com.rawr.camera.settings.model.SelfTimer.nextCaptureOption(state.selfTimer),
                selfTimerRemainingMs = null,
                selfTimerRunId = state.selfTimerRunId + 1L
            )
        }

        is SetCaptureSelfTimer -> {
            if (action.value == state.selfTimer) {
                state
            } else {
                state.copy(
                    selfTimer = action.value,
                    selfTimerRemainingMs = null,
                    selfTimerRunId = state.selfTimerRunId + 1L
                )
            }
        }

        CancelSelfTimer -> {
            if (state.selfTimerRemainingMs == null) {
                state
            } else {
                state.copy(
                    selfTimerRemainingMs = null,
                    selfTimerRunId = state.selfTimerRunId + 1L
                )
            }
        }

        StartSelfTimerCountdown -> {
            val seconds = state.selfTimer.seconds
            if (seconds <= 0 || state.selfTimerRemainingMs != null) {
                state
            } else {
                state.copy(
                    selfTimerRemainingMs = seconds * 1000L,
                    selfTimerRunId = state.selfTimerRunId + 1L
                )
            }
        }

        is TickSelfTimer -> {
            val remaining = state.selfTimerRemainingMs ?: return state
            if (action.runId != state.selfTimerRunId) {
                state
            } else if (action.remainingMs <= 0L || remaining <= 0L) {
                state.copy(selfTimerRemainingMs = null)
            } else {
                state.copy(selfTimerRemainingMs = action.remainingMs)
            }
        }

        ToggleFilmSim -> {
            state.copy(filmSimEnabled = !state.filmSimEnabled)
        }

        ToggleMultiframe -> {
            state.copy(experimentalMultiframeEnabled = !state.experimentalMultiframeEnabled)
        }

        is ToggleOverlayArmed -> {
            state.copy(
                armedOverlays =
                    if (action.overlay in state.armedOverlays) state.armedOverlays - action.overlay
                    else state.armedOverlays + action.overlay
            )
        }

        is ToggleFalseColor -> {
            state.copy(falseColorManual = !state.falseColorManual)
        }

        is ToggleScope -> {
            state.toggleScope(action.scope)
        }

        is SetGrid -> state.copy(grid = action.mode)

        is SetSingleScope -> state.copy(
            activeScopes = listOfNotNull(action.scope),
            expandedScope = null,
            waveformMode = action.waveformMode
        )

        ToggleWaveformMode -> {
            state.copy(
                waveformMode =
                    if (state.waveformMode ==
                        WaveformMode.Luma
                    ) {
                        WaveformMode.RgbOverlay
                    } else {
                        WaveformMode.Luma
                    }
            )
        }

        is ToggleScopeExpanded -> {
            if (action.scope in state.activeScopes) {
                state.copy(expandedScope = if (state.expandedScope == action.scope) null else action.scope)
            } else {
                state
            }
        }

        OpenFocusSelector -> {
            state.copy(
                focus = state.focus.copy(selectorOpen = true),
                monitorPanelOpen = false,
                whiteBalancePanelOpen = false
            )
        }

        CloseFocusSelector -> {
            state.copy(focus = state.focus.copy(selectorOpen = false))
        }
    }

    private fun Int.coerceVideoFps(): Int = if (this == 24 || this == 30 || this == 60) this else 30

    private fun CaptureUiState.toggleScope(scope: ScopeType): CaptureUiState {
        val active = if (scope in activeScopes) activeScopes - scope else activeScopes + scope
        return copy(
            activeScopes = active,
            expandedScope = expandedScope.takeIf(active::contains)
        )
    }
}
