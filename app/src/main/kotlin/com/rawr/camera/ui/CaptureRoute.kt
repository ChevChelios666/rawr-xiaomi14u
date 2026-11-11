package com.rawr.camera.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rawr.camera.architecture.CaptureFilmEvent
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.model.ViewfinderQuickState
import com.rawr.camera.architecture.CaptureScreenController
import com.rawr.camera.architecture.SetOrientation
import com.rawr.camera.integration.RawPreviewCoordinator
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.model.RenderProfileQuickState
import com.rawr.camera.model.controlProjection
import com.rawr.camera.model.monitorProjection
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun CaptureRoute(
    controller: CaptureScreenController,
    previewCoordinator: RawPreviewCoordinator,
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    onOpenSettings: () -> Unit,
    latestImageUri: Uri? = null,
    onOpenLatestImage: () -> Unit,
    onOpenRenderer: () -> Unit,
    onOpenFilmSimSettings: () -> Unit,
    onOpenMultiframeSettings: () -> Unit = {},
    onOpenLensSettings: (String) -> Unit = {},
    filmQuick: FilmSimQuickState? = null,
    onFilmEvent: (CaptureFilmEvent) -> Unit,
    quick: ViewfinderQuickState = ViewfinderQuickState(),
    onQuickEvent: (CaptureQuickEvent) -> Unit = {},
    videoControls: VideoControlState = VideoControlState(),
    onToggleVideoRecording: () -> Unit,
    extras: CaptureExtras = CaptureExtras()
) {
    val controlFlow = androidx.compose.runtime.remember(controller) {
        controller.state.map { it.controlProjection() }.distinctUntilChanged()
    }
    val initialControls = androidx.compose.runtime.remember(controller) { controller.state.value.controlProjection() }
    val state by controlFlow.collectAsStateWithLifecycle(initialControls)
    val monitor: @Composable () -> Unit = androidx.compose.runtime.remember(controller) {
        {
            val monitorFlow = androidx.compose.runtime.remember(controller) {
                controller.state.map { it.monitorProjection() }.distinctUntilChanged()
            }
            val initialMonitor = androidx.compose.runtime.remember(controller) { controller.state.value.monitorProjection() }
            val readouts by monitorFlow.collectAsStateWithLifecycle(initialMonitor)
            ExposureMonitor(readouts)
        }
    }
    var deviceRotationDegrees by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableIntStateOf(previewCoordinator.captureDeviceRotationDegrees)
    }
    val surfaceView = androidx.compose.runtime.remember { arrayOfNulls<android.view.SurfaceView>(1) }
    val preview = androidx.compose.runtime.remember(previewCoordinator, deviceRotationDegrees) {
        CapturePreview(
            nativeContent = true,
            deviceRotationDegrees = deviceRotationDegrees,
            content = { RawPreviewSurface(previewCoordinator, it, onView = { v -> surfaceView[0] = v }) },
            faceOverlay = {
                val faceFlow = androidx.compose.runtime.remember(controller) {
                    controller.state.map { it.faceDetections }.distinctUntilChanged()
                }
                val initialFaces = androidx.compose.runtime.remember(controller) { controller.state.value.faceDetections }
                val faces by faceFlow.collectAsStateWithLifecycle(initialFaces)
                FaceBoxes(faces)
            },
            onScopePresentation = { scopes ->
                val types = IntArray(3)
                val modes = IntArray(3)
                val rotations = IntArray(3)
                val rects = FloatArray(15)
                scopes.take(3).forEachIndexed { index, scope ->
                    types[index] = if (scope.type == com.rawr.camera.model.ScopeType.Waveform) 1 else 2
                    modes[index] = if (scope.type != com.rawr.camera.model.ScopeType.Waveform) 0 else when (scope.mode) {
                        com.rawr.camera.model.WaveformMode.RgbOverlay -> 1
                        com.rawr.camera.model.WaveformMode.Histogram -> 2
                        com.rawr.camera.model.WaveformMode.HistogramRgb -> 3
                        com.rawr.camera.model.WaveformMode.Luma -> 0
                    }
                    rotations[index] = scope.quarterTurns
                    rects[index * 5] = scope.x
                    rects[index * 5 + 1] = scope.y
                    rects[index * 5 + 2] = scope.width
                    rects[index * 5 + 3] = scope.height
                    rects[index * 5 + 4] = scope.cornerFraction
                }
                previewCoordinator.setScopePresentationState(types, modes, rotations, rects)
            },
            onLoupe = { values, meter -> previewCoordinator.setFocusLoupe(values, meter) },
            focusMeter = { previewCoordinator.focusMeterValue() },
            snapshot = { deliver -> copyPreview(surfaceView[0], deliver) }
        )
    }
    DeviceOrientationEffect(
        onOrientationChanged = { orientation -> controller.dispatch(SetOrientation(orientation)) },
        onDeviceRotationChanged = {
            previewCoordinator.setCaptureDeviceRotationDegrees(it)
            deviceRotationDegrees = it
        }
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalCaptureExtras provides extras) {
    CaptureScreen(
        state = state,
        dispatch = controller::dispatch,
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
        onToggleVideoRecording = onToggleVideoRecording
    )
    }
}

/** Half-size copy of the viewfinder surface (Pano ghost); null when it can't be read. */
private fun copyPreview(view: android.view.SurfaceView?, deliver: (android.graphics.Bitmap?) -> Unit) {
    val w = (view?.width ?: 0) / 2
    val h = (view?.height ?: 0) / 2
    if (view == null || w <= 0 || h <= 0 || !view.holder.surface.isValid) return deliver(null)
    val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    runCatching {
        android.view.PixelCopy.request(view, bitmap, { result ->
            deliver(if (result == android.view.PixelCopy.SUCCESS) bitmap else null)
        }, android.os.Handler(android.os.Looper.getMainLooper()))
    }.onFailure { deliver(null) }
}
