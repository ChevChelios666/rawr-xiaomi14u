package com.rawr.camera.architecture

import com.rawr.camera.model.*
import kotlin.math.roundToInt

// Presentation-only capture-screen state.
data class RestoreCapturePreferences(
    val jpegEnabled: Boolean,
    val dngEnabled: Boolean = true,
    val grid: GridMode,
    val armedOverlays: Set<OverlayMode>,
    val falseColorManual: Boolean,
    val activeScopes: List<ScopeType>,
    val waveformMode: WaveformMode,
    val filmSimEnabled: Boolean,
    val experimentalMultiframeEnabled: Boolean = false,
    val multiframeAlgorithm: com.rawr.camera.settings.model.MultiframeMergeAlgorithm = com.rawr.camera.settings.model.MultiframeMergeAlgorithm.Rexture,
    val captureLayout: CaptureControlLayout = CaptureControlLayout.Compact,
    val captureMode: CaptureMode = CaptureMode.Photo,
    val videoCrop: com.rawr.camera.video.VideoCrop = com.rawr.camera.video.VideoCrop.Default,
    val videoFps: Int = 30,
    val videoLogEnabled: Boolean = false,
    val selfTimer: com.rawr.camera.settings.model.SelfTimer =
        com.rawr.camera.settings.model.SelfTimer.Off
) : PresentationCaptureAction

data class SetOrientation(val value: Orientation) : PresentationCaptureAction

data object CycleOutputFormat : PresentationCaptureAction

data object ToggleMonitorPanel : PresentationCaptureAction

data object ToggleWhiteBalancePanel : PresentationCaptureAction

data object CloseTopBarPanels : PresentationCaptureAction

data class SetControlSurfaceStyle(val style: ControlSurfaceStyle) : PresentationCaptureAction

data class SetCaptureLayout(val layout: CaptureControlLayout) : PresentationCaptureAction

data class SetCaptureMode(val mode: CaptureMode) : PresentationCaptureAction

data object CycleVideoResolution : PresentationCaptureAction

/** X14U video crop panel. */
data class SetVideoCrop(val crop: com.rawr.camera.video.VideoCrop) : PresentationCaptureAction

data object CycleVideoFps : PresentationCaptureAction

data object ToggleVideoLog : PresentationCaptureAction

/** Cycles the self-timer Off → 3s → 5s → 10s → Off; cancels any active countdown. */
data object CycleSelfTimer : PresentationCaptureAction

/** Explicit self-timer selection; cancels any active countdown when the value changes. */
data class SetCaptureSelfTimer(val value: com.rawr.camera.settings.model.SelfTimer) : PresentationCaptureAction

/** Cancels an active self-timer countdown (tap-to-cancel). */
data object CancelSelfTimer : PresentationCaptureAction

/** Begins a countdown for the currently armed delay; no-op when Off or already running. */
data object StartSelfTimerCountdown : PresentationCaptureAction

/** Countdown tick from the owning job; stale run ids and non-positive values finish the run. */
data class TickSelfTimer(val remainingMs: Long, val runId: Long) : PresentationCaptureAction

data object ToggleFilmSim : PresentationCaptureAction

data object ToggleMultiframe : PresentationCaptureAction

data class ToggleOverlayArmed(val overlay: OverlayMode) : PresentationCaptureAction

data object ToggleFalseColor : PresentationCaptureAction

data class ToggleScope(val scope: ScopeType) : PresentationCaptureAction

/** X14U tools panel: the composition grid. */
data class SetGrid(val mode: GridMode) : PresentationCaptureAction

/** X14U tools panel Histogram tile: one scope (null = none), with the waveform look it uses. */
data class SetSingleScope(val scope: ScopeType?, val waveformMode: WaveformMode) : PresentationCaptureAction

data class ToggleScopeExpanded(val scope: ScopeType) : PresentationCaptureAction

data object ToggleWaveformMode : PresentationCaptureAction

data object OpenFocusSelector : PresentationCaptureAction

data object CloseFocusSelector : PresentationCaptureAction

// Application/backend intents. These must not be reducer-only mutations in production.
data class SetExposureMode(val mode: ExposureMode) : ApplicationCaptureAction

data class SetWhiteBalanceMode(val mode: WhiteBalanceMode) : ApplicationCaptureAction

data class SetWhiteBalanceTempTint(val temperatureK: Int, val tint: Int) : ApplicationCaptureAction

/**
 * V2 compact-button manual entry: locks the given temp/tint exactly as shown,
 * without re-seeding either axis from the HAL neutral estimate. Scrub entry
 * and long-press use this so the first touch never jumps.
 */
data class LockWhiteBalance(val temperatureK: Int, val tint: Int) : ApplicationCaptureAction

data class SetExposureCandidate(val parameter: ExposureParameter, val candidateId: String) : ApplicationCaptureAction

data class ScrubTone(val target: ToneParameter, val delta: Int) : ApplicationCaptureAction

/** Sets an active AgX control; null restores the selected saved look or built-in preset default. */
data class SetAgxControl(
    val parameter: com.rawr.camera.settings.model.AgxParameter,
    val value: Float?
) : ApplicationCaptureAction

/** Sets the active profile's render exposure to [tenths] × 0.1 EV (AgX strip RANGE cell). */
data class SetRenderExposureTenths(val tenths: Int) : ApplicationCaptureAction

/** Shifts the active profile's render exposure by [deltaTenths] × 0.1 EV (manual-mode EV button). */
data class ScrubRenderExposure(val deltaTenths: Int) : ApplicationCaptureAction

data class SyncToneControls(
    val blacks: Int,
    val shadows: Int,
    val contrast: Int,
    val midtones: Int,
    val highlights: Int,
    val whites: Int,
    val saturation: Int,
    val vibrance: Int,
    val renderExposureTenths: Int = 0
) : ApplicationCaptureAction {
    companion object {
        fun fromImageTone(tone: ImageToneState) =
            SyncToneControls(
                blacks = tone.blacks.roundToInt().coerceIn(-100, 100),
                shadows = tone.shadows.roundToInt().coerceIn(-100, 100),
                contrast = tone.contrast.roundToInt().coerceIn(-100, 100),
                midtones = tone.midtones.roundToInt().coerceIn(-100, 100),
                highlights = tone.highlights.roundToInt().coerceIn(-100, 100),
                whites = tone.whites.roundToInt().coerceIn(-100, 100),
                saturation = tone.saturation.roundToInt().coerceIn(-100, 100),
                vibrance = tone.vibrance.roundToInt().coerceIn(-100, 100),
                renderExposureTenths = renderExposureTenthsOf(tone.renderExposure)
            )
    }
}

data class SelectLens(val id: String) : ApplicationCaptureAction

data class SetManualFocus(val normalized: Float) : ApplicationCaptureAction

/** Backend/application capability snapshot replacement, e.g. after physical-camera switch. */
data class ApplyCameraCapabilities(val capabilities: CameraCapabilities) : ApplicationCaptureAction

data class FocusAt(val point: NormalizedPoint) : ApplicationCaptureAction

data object Refocus : ApplicationCaptureAction

/** Back to full auto: hides the tap-AF box and resumes continuous AF. */
data object ClearAutofocus : ApplicationCaptureAction

data class SetFocusMode(val mode: FocusMode) : ApplicationCaptureAction

data class ActivateSpotAe(val point: NormalizedPoint) : ApplicationCaptureAction

data class MoveSpotAe(val point: NormalizedPoint) : ApplicationCaptureAction

data object FinishSpotAeMove : ApplicationCaptureAction

data object CancelSpotAe : ApplicationCaptureAction

data object TriggerCapture : ApplicationCaptureAction

/** X14U synthesized 50 MP: the shot after its own countdown (the self-timer already ran before the camera's still). */
data object TriggerCaptureNow : ApplicationCaptureAction

/** X14U Burst: the shutter is held — frames at [fps] until [StopBurst], [limit] frames or a full save queue. */
data class StartBurst(
    val fps: Int,
    val format: com.rawr.camera.model.CaptureOutputFormat,
    val limit: Int,
    /** Pre-capture on: the frames held from before the press are saved too. */
    val pre: Boolean = false,
    /** BurstFormat name (manifest). */
    val burstFormat: String = "Jpeg"
) : ApplicationCaptureAction

/** X14U Burst: the shutter is let go. */
data object StopBurst : ApplicationCaptureAction

/** X14U: leaving Burst mode (stops a run, forgets the last count). */
data object EndBurstMode : ApplicationCaptureAction

/** X14U Burst: the thumbnail's frame count follows the burst library (0 = no burst left, e.g. all deleted). */
data class SyncBurstLastCount(val count: Int) : ApplicationCaptureAction
