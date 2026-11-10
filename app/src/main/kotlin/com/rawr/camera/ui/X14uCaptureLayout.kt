package com.rawr.camera.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.CaptureFilmEvent
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.architecture.CaptureTransitions
import com.rawr.camera.architecture.LockWhiteBalance
import com.rawr.camera.architecture.ScrubRenderExposure
import com.rawr.camera.architecture.SelectLens
import com.rawr.camera.architecture.SetCaptureMode
import com.rawr.camera.architecture.SetExposureCandidate
import com.rawr.camera.architecture.SetExposureMode
import com.rawr.camera.architecture.SetFocusMode
import com.rawr.camera.architecture.SetManualFocus
import com.rawr.camera.architecture.SetWhiteBalanceMode
import com.rawr.camera.architecture.SetWhiteBalanceTempTint
import com.rawr.camera.architecture.ToggleVideoLog
import com.rawr.camera.model.CaptureMode
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.ExposureFormat
import com.rawr.camera.model.ExposureMode
import com.rawr.camera.model.ExposureParameter
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.model.FocusLadder
import com.rawr.camera.model.FocusMode
import com.rawr.camera.model.HighlightQuickMode
import com.rawr.camera.model.LensOptionsQuickState
import com.rawr.camera.model.RenderProfileQuickState
import com.rawr.camera.model.VideoHealth
import com.rawr.camera.model.VideoTakeSummary
import com.rawr.camera.model.ViewfinderQuickState
import com.rawr.camera.model.WhiteBalanceMode
import com.rawr.camera.model.capabilityFor
import com.rawr.camera.model.focusDistanceLabel
import com.rawr.camera.model.mfNormalizedFromDiopters
import com.rawr.camera.model.requestedCandidateIdFor
import com.rawr.camera.model.zeroEvCandidateId
import com.rawr.camera.settings.model.RawrBalance
import com.rawr.camera.settings.model.RawrDigitalGain
import com.rawr.camera.settings.model.RawrMaxShutter
import com.rawr.camera.settings.model.RawrMetering
import com.rawr.camera.settings.model.RawrNightLook
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.Exposure
import com.rawr.camera.video.VideoResolutionMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * X14U capture screen (design agreed 2026-11-10): top bar · clean viewfinder with the lens row inside it (Look and
 * Exposure buttons either side) · exposure row EV · F · SS · ISO · WB · AF · control area (Photo/Video when idle, a
 * ruler for the tapped value, or a Look / Exposure panel) · shutter row.
 */
internal object X14uDimens {
    val ExposureRow = 36.dp
    val ControlArea = 64.dp
    val ShutterRow = 92.dp
    val BottomInset = 6.dp
    val RoundButton = 40.dp
}

internal enum class X14uControl(val label: String) { Ev("EV"), Aperture("F"), Shutter("SS"), Iso("ISO"), Wb("WB"), Af("AF") }

internal enum class X14uPanel { Look, Exposure }

private val Amber = Color(0xFFFFB74D)

/** Front-facing lenses leave the lens row; the front-camera button switches to them. */
internal fun isFrontLens(id: String, name: String): Boolean =
    listOf(id, name).any { it.contains("front", ignoreCase = true) || it.contains("selfie", ignoreCase = true) }

@Composable
internal fun X14uCaptureLayout(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    preview: CapturePreview,
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    onOpenSettings: () -> Unit,
    latestImageUri: Uri?,
    onOpenLatestImage: () -> Unit,
    onOpenRenderer: () -> Unit,
    onOpenFilmSimSettings: () -> Unit,
    onOpenMultiframeSettings: () -> Unit,
    onOpenLensSettings: (String) -> Unit,
    modifier: Modifier,
    filmQuick: FilmSimQuickState?,
    onFilmEvent: (CaptureFilmEvent) -> Unit,
    quick: ViewfinderQuickState,
    onQuickEvent: (CaptureQuickEvent) -> Unit,
    videoControls: VideoControlState,
    onToggleVideoRecording: () -> Unit
) {
    val isVideo = state.captureMode == CaptureMode.Video
    val videoLocked = videoControls.busy || videoControls.recording
    val recordingLock = isVideo && videoLocked
    val videoBleed = isVideo && state.videoResolution != VideoResolutionMode.OPEN_GATE
    var control by remember { mutableStateOf<X14uControl?>(null) }
    var panel by remember { mutableStateOf<X14uPanel?>(null) }
    var showNumbers by remember { mutableStateOf(false) }
    LaunchedEffect(state.captureMode, state.selectedLensId) {
        control = null
        panel = null
    }
    val lensOptions = quick.lensOptions.firstOrNull { it.lensId == state.selectedLensId }
    val closeAll: () -> Boolean = {
        val open = control != null || panel != null
        control = null
        panel = null
        open
    }
    BoxWithConstraints(modifier) {
        val reserved = CaptureDimens.TopBarHeight + X14uDimens.ExposureRow + X14uDimens.ControlArea +
            X14uDimens.ShutterRow + X14uDimens.BottomInset
        val viewfinderMax = (maxHeight - reserved).coerceAtLeast(1.dp)
        val viewfinderWidth = minOf(maxWidth, viewfinderMax * .75f)
        val photoFrame = DpSize(viewfinderWidth, viewfinderWidth * 4f / 3f)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            CaptureTopBar(state, dispatch, onOpenSettings, videoLocked = recordingLock, showLog = false) {
                X14uOptionToggles(quick, lensOptions, isVideo, enabled = !recordingLock, onQuickEvent)
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                CaptureViewfinder(
                    state = state,
                    dispatch = dispatch,
                    preview = preview,
                    renderProfiles = renderProfiles.copy(locked = recordingLock),
                    onSelectRenderProfile = onSelectRenderProfile,
                    modifier = Modifier.x14uViewfinderFrame(videoBleed, viewfinderWidth).align(Alignment.TopCenter),
                    slotFrame = photoFrame,
                    forceCompact = isVideo,
                    forceTonemapStrip = isVideo,
                    filmQuick = filmQuick,
                    onFilmEvent = onFilmEvent,
                    quick = quick.copy(locked = recordingLock),
                    onQuickEvent = onQuickEvent,
                    x14uClean = true,
                    onViewfinderTap = closeAll
                )
                Box(Modifier.align(Alignment.TopCenter).size(photoFrame)) {
                    if (isVideo && videoControls.recording) {
                        X14uRecordTimer(
                            video = videoControls,
                            onTap = { if (videoControls.diagnostics) showNumbers = !showNumbers },
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                        )
                    }
                    X14uLensRow(
                        state = state,
                        dispatch = dispatch,
                        quick = quick,
                        panel = panel,
                        panelTitle = when (panel) {
                            X14uPanel.Look -> "LOOK · " + (renderProfiles.options.firstOrNull { it.id == renderProfiles.selectedId }
                                ?.label ?: "RAWR NTRL")
                            X14uPanel.Exposure -> "EXPOSURE · " + if (quick.rawrAe.enabled) "RAWR AE" else "DEVICE AE"
                            null -> null
                        },
                        enabled = !recordingLock,
                        onTogglePanel = { target ->
                            control = null
                            panel = if (panel == target) null else target
                        },
                        onOpenLensSettings = onOpenLensSettings,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    )
                }
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(photoFrame.height))
                    Column(
                        Modifier.fillMaxWidth().weight(1f)
                            .background(if (videoBleed) CaptureColors.Surface.copy(alpha = .62f) else CaptureColors.Surface)
                    ) {
                        when (panel) {
                            X14uPanel.Look -> X14uLookPanel(
                                state, dispatch, renderProfiles, onSelectRenderProfile, filmQuick, onFilmEvent, quick,
                                onQuickEvent, film = state.filmSimEnabled && !isVideo && filmQuick != null
                            )
                            X14uPanel.Exposure -> X14uExposurePanel(quick, onQuickEvent)
                            null -> {
                                X14uExposureRow(state, dispatch, quick, lensOptions, control) { target ->
                                    control = if (control == target) null else target
                                }
                                Box(Modifier.fillMaxWidth().height(X14uDimens.ControlArea), contentAlignment = Alignment.Center) {
                                    val open = control
                                    if (open != null) {
                                        X14uControlRuler(open, state, dispatch, quick, lensOptions, recordingLock, onQuickEvent)
                                    } else {
                                        X14uIdleControlArea(state, dispatch, videoControls, showNumbers, enabled = !recordingLock)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        X14uShutterRow(
                            state, dispatch, quick, latestImageUri, onOpenLatestImage, onOpenRenderer, onOpenFilmSimSettings,
                            onOpenMultiframeSettings, onOpenLensSettings, onQuickEvent, videoControls, onToggleVideoRecording,
                            enabled = !recordingLock
                        )
                    }
                }
            }
            Spacer(Modifier.height(X14uDimens.BottomInset))
        }
    }
}

private fun Modifier.x14uViewfinderFrame(videoBleed: Boolean, photoWidth: Dp): Modifier = if (videoBleed) {
    this.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).aspectRatio(9f / 16f)
} else {
    this.width(photoWidth).aspectRatio(3f / 4f)
}

// ---- Top bar toggles -------------------------------------------------------------------------------------------

/** DCG and 50 MP (every on/off option row) in the top bar; greyed on lenses without them, hidden in video if absent. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.X14uOptionToggles(
    quick: ViewfinderQuickState,
    options: LensOptionsQuickState?,
    video: Boolean,
    enabled: Boolean,
    onQuickEvent: (CaptureQuickEvent) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    lensOptionSlots(quick.lensOptions).filter { it.second.toggle }.forEach { (name, _) ->
        val row = options?.options?.indexOfFirst { it.name.trim().equals(name, ignoreCase = true) } ?: -1
        val available = options != null && row >= 0 && row !in options.unavailable
        if (video && !available) return@forEach
        val on = available && options!!.picks.getOrElse(row) { 0 } == 1
        TopBarSlot {
            Box(
                Modifier
                    .height(28.dp)
                    .widthIn(min = 40.dp)
                    .background(if (on) CaptureColors.Accent.copy(alpha = .16f) else Color.Transparent, RoundedCornerShape(14.dp))
                    .border(1.dp, when {
                        on -> CaptureColors.Accent.copy(alpha = .8f)
                        available -> Color.White.copy(alpha = .22f)
                        else -> Color.White.copy(alpha = .08f)
                    }, RoundedCornerShape(14.dp))
                    .captureClickable(shape = RoundedCornerShape(14.dp), enabled = available && enabled, role = Role.Switch) {
                        haptics.selection()
                        onQuickEvent(CaptureQuickEvent.SelectLensOption(options!!.lensId, row, if (on) 0 else 1))
                    }
                    .semantics { contentDescription = "$name ${if (on) "on" else "off"}" }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name.uppercase().replace(" ", ""),
                    fontFamily = CaptureMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = when {
                        on -> CaptureColors.Accent
                        available && enabled -> Color.White.copy(alpha = .72f)
                        else -> Color.White.copy(alpha = .24f)
                    }
                )
            }
        }
    }
}

// ---- Lens row --------------------------------------------------------------------------------------------------

@Composable
private fun X14uRoundButton(
    icon: ImageVector,
    active: Boolean,
    description: String,
    enabled: Boolean = true,
    size: Dp = X14uDimens.RoundButton,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    Box(
        Modifier
            .size(size)
            .background(if (active) CaptureColors.Accent.copy(alpha = .22f) else Color.Black.copy(alpha = .45f), CircleShape)
            .border(1.dp, if (active) CaptureColors.Accent.copy(alpha = .85f) else Color.White.copy(alpha = .18f), CircleShape)
            .captureCombinedClickable(
                shape = CircleShape,
                enabled = enabled,
                role = Role.Button,
                onLongClick = onLongClick?.let { action -> { haptics.longPress(); action() } }
            ) {
                haptics.selection()
                onClick()
            }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> Color.White.copy(alpha = .25f)
                active -> CaptureColors.Accent
                else -> Color.White.copy(alpha = .86f)
            },
            modifier = Modifier.size(size * .52f)
        )
    }
}

@Composable
private fun X14uLensRow(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    quick: ViewfinderQuickState,
    panel: X14uPanel?,
    panelTitle: String?,
    enabled: Boolean,
    onTogglePanel: (X14uPanel) -> Unit,
    onOpenLensSettings: (String) -> Unit,
    modifier: Modifier
) {
    val haptics = LocalCaptureHaptics.current
    val extras = LocalCaptureExtras.current
    val lenses = state.capabilities.lenses.filterNot { isFrontLens(it.id, it.displayName) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        X14uRoundButton(X14uIcons.Look, panel == X14uPanel.Look, "Look: profile, tone and highlights") {
            onTogglePanel(X14uPanel.Look)
        }
        val pill = RoundedCornerShape(20.dp)
        Box(
            Modifier
                .height(40.dp)
                .widthIn(min = 120.dp, max = 260.dp)
                .background(Color.Black.copy(alpha = .45f), pill)
                .border(1.dp, Color.White.copy(alpha = .12f), pill),
            contentAlignment = Alignment.Center
        ) {
            if (panelTitle != null) {
                Text(
                    panelTitle,
                    fontFamily = CaptureMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = CaptureColors.Accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .captureClickable(shape = pill) { panel?.let(onTogglePanel) }
                        .padding(horizontal = 14.dp)
                )
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    lenses.forEach { lens ->
                        val selected = state.selectedLensId == lens.id
                        val dot = lens.id in quick.overrideLenses || lens.displayName in quick.overrideLenses
                        Column(
                            Modifier
                                .height(40.dp)
                                .widthIn(min = 40.dp)
                                .captureCombinedClickable(
                                    shape = pill,
                                    enabled = enabled,
                                    onLongClickLabel = "${lens.displayName} lens settings",
                                    onLongClick = {
                                        haptics.selection()
                                        onOpenLensSettings(lens.id)
                                    }
                                ) {
                                    haptics.selection()
                                    if (!selected) dispatch(SelectLens(lens.id))
                                }
                                .semantics {
                                    contentDescription = "Lens ${lens.displayName}" + if (dot) ", has its own settings" else ""
                                    role = Role.RadioButton
                                    this.selected = selected
                                }
                                .padding(horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                lens.displayName,
                                fontFamily = CaptureMono,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (selected) 13.sp else 12.sp,
                                maxLines = 1,
                                color = when {
                                    selected -> if (enabled) CaptureColors.Accent else CaptureColors.Accent.copy(alpha = .45f)
                                    else -> Color.White.copy(alpha = if (enabled) .72f else .25f)
                                }
                            )
                            Box(
                                Modifier.padding(top = 2.dp).size(4.dp)
                                    .background(if (dot) CaptureColors.Accent else Color.Transparent, CircleShape)
                            )
                        }
                    }
                }
            }
        }
        X14uRoundButton(
            Icons.Outlined.Exposure,
            panel == X14uPanel.Exposure,
            "Exposure: auto exposure and metering. Long press for exposure settings.",
            onLongClick = extras.onOpenExposureSettings
        ) {
            onTogglePanel(X14uPanel.Exposure)
        }
    }
}

// ---- Exposure row ----------------------------------------------------------------------------------------------

private data class X14uCell(val control: X14uControl, val label: String, val value: String, val manual: Boolean, val enabled: Boolean)

private fun exposureValue(parameter: ExposureParameter, state: CaptureUiState): Pair<String, Boolean> {
    if (parameter == ExposureParameter.Ev && CaptureTransitions.evAdjustsRenderExposure(state)) {
        return renderExposureLabel(state.renderExposureTenths) to (state.renderExposureTenths != 0)
    }
    val capability = state.capabilities.capabilityFor(parameter)
    val requestedId = state.requestedCandidateIdFor(parameter)
    val locked = CaptureTransitions.isParameterLocked(state, parameter)
    val applied = when (parameter) {
        ExposureParameter.Shutter -> state.exposureApplied.shutter
        ExposureParameter.Iso -> state.exposureApplied.iso
        ExposureParameter.Ev -> state.exposureApplied.evOrMeter
    }
    val requested = capability.candidate(requestedId)?.displayLabel ?: "—"
    val isEv = parameter == ExposureParameter.Ev
    val isManualMeter = isEv && state.exposureControl.mode == ExposureMode.Manual
    val videoShutter = parameter == ExposureParameter.Shutter && state.captureMode == CaptureMode.Video
    val value = when {
        isManualMeter -> applied?.displayLabel ?: "—"
        locked || isEv -> requested
        videoShutter -> applied?.sourceCandidateId?.let { capability.candidate(it)?.displayLabel } ?: requested
        else -> applied?.displayLabel ?: requested
    }
    val manual = if (isEv) requestedId != capability.zeroEvCandidateId() else locked
    return value to manual
}

/** The aperture row of a lens, if it has one ("Aperture", or every choice an f-number). */
private fun apertureRow(options: LensOptionsQuickState?): Int {
    val list = options?.options ?: return -1
    return list.indexOfFirst { o -> !o.toggle && o.choices.isNotEmpty() && o.choices.all { it.label.trim().startsWith("f/") } }
        .takeIf { it >= 0 && it !in options.unavailable } ?: -1
}

@Composable
private fun X14uExposureRow(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    quick: ViewfinderQuickState,
    options: LensOptionsQuickState?,
    open: X14uControl?,
    onTap: (X14uControl) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    val (ev, evManual) = exposureValue(ExposureParameter.Ev, state)
    val (ss, ssManual) = exposureValue(ExposureParameter.Shutter, state)
    val (iso, isoManual) = exposureValue(ExposureParameter.Iso, state)
    val row = apertureRow(options)
    val aperture = if (row >= 0) options!!.options[row].choices.getOrNull(options.picks.getOrElse(row) { 0 })?.label.orEmpty()
    else quick.nativeApertures[state.selectedLensId]?.let { "f/" + ("%.1f".format(it)).removeSuffix(".0") } ?: "f/–"
    val wbManual = CaptureTransitions.isWhiteBalanceLocked(state)
    val wb = if (wbManual) "${state.whiteBalanceTemperatureK}K" else "AWB"
    val afTitle = when (state.focus.mode) {
        FocusMode.Af -> "AF"
        FocusMode.AfLock -> "AF-L"
        FocusMode.Mf -> "MF"
    }
    val cells = listOf(
        X14uCell(X14uControl.Ev, "EV", ev, evManual, true),
        X14uCell(X14uControl.Aperture, "F", aperture, false, row >= 0),
        X14uCell(X14uControl.Shutter, "SS", ss, ssManual, true),
        X14uCell(X14uControl.Iso, "ISO", iso, isoManual, true),
        X14uCell(X14uControl.Wb, "WB", wb, wbManual, state.capabilities.manualWhiteBalanceSupported || wbManual),
        X14uCell(X14uControl.Af, afTitle, state.focusDistanceLabel(), state.focus.mode != FocusMode.Af, true)
    )
    Row(
        Modifier.fillMaxWidth().height(X14uDimens.ExposureRow).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEach { cell ->
            val isOpen = open == cell.control
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .captureClickable(shape = CaptureTextRippleShape, enabled = cell.enabled) {
                        haptics.selection()
                        onTap(cell.control)
                    }
                    .semantics {
                        contentDescription = "${cell.label} ${cell.value}. Tap to adjust."
                        selected = isOpen
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    cell.label,
                    fontFamily = CaptureMono,
                    fontSize = 8.5.sp,
                    color = if (isOpen) CaptureColors.Accent else Color.White.copy(alpha = if (cell.enabled) .42f else .2f),
                    maxLines = 1
                )
                Text(
                    cell.value,
                    fontFamily = CaptureMono,
                    fontSize = 11.sp,
                    fontWeight = if (isOpen) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isOpen -> CaptureColors.Accent
                        !cell.enabled -> Color.White.copy(alpha = .3f)
                        cell.manual -> CaptureColors.AccentSoft
                        else -> Color.White.copy(alpha = .9f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

// ---- Control area ----------------------------------------------------------------------------------------------

@Composable
private fun X14uAutoButton(active: Boolean, enabled: Boolean = true, label: String = "A", onClick: () -> Unit) {
    val haptics = LocalCaptureHaptics.current
    Box(
        Modifier
            .size(34.dp)
            .background(if (active) CaptureColors.Accent.copy(alpha = .2f) else Color.White.copy(alpha = .06f), CircleShape)
            .border(1.dp, if (active) CaptureColors.Accent else Color.White.copy(alpha = .2f), CircleShape)
            .captureClickable(shape = CircleShape, enabled = enabled) {
                haptics.selection()
                onClick()
            }
            .semantics { contentDescription = if (label == "A") "Auto" else label },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontFamily = CaptureMono,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = if (active) CaptureColors.Accent else Color.White.copy(alpha = if (enabled) .8f else .3f)
        )
    }
}

@Composable
private fun X14uControlRuler(
    control: X14uControl,
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    quick: ViewfinderQuickState,
    options: LensOptionsQuickState?,
    recordingLock: Boolean,
    onQuickEvent: (CaptureQuickEvent) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        when (control) {
            X14uControl.Ev, X14uControl.Shutter, X14uControl.Iso -> {
                val parameter = when (control) {
                    X14uControl.Ev -> ExposureParameter.Ev
                    X14uControl.Shutter -> ExposureParameter.Shutter
                    else -> ExposureParameter.Iso
                }
                ExposureRulerRow(parameter, state, dispatch)
            }
            X14uControl.Aperture -> {
                val row = apertureRow(options)
                if (row < 0 || options == null) {
                    Text("Fixed aperture on this lens", fontFamily = CaptureMono, fontSize = 11.sp, color = CaptureColors.Muted,
                        modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                } else {
                    val option = options.options[row]
                    val pick = options.picks.getOrElse(row) { option.selected }
                    val locked = row in options.locked
                    X14uChoiceChips(
                        labels = option.choices.map { it.label },
                        selected = pick,
                        enabled = !locked && !recordingLock,
                        modifier = Modifier.fillMaxWidth()
                    ) { index ->
                        if (index != option.selected) onQuickEvent(CaptureQuickEvent.SelectLensOption(options.lensId, row, index))
                    }
                }
            }
            X14uControl.Wb -> WbRulerRow(state, dispatch)
            X14uControl.Af -> FocusRulerRow(state, dispatch)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ExposureRulerRow(
    parameter: ExposureParameter,
    state: CaptureUiState,
    dispatch: CaptureDispatch
) {
    val haptics = LocalCaptureHaptics.current
    if (parameter == ExposureParameter.Ev && CaptureTransitions.evAdjustsRenderExposure(state)) {
        // Full Manual: EV edits the render exposure, 0.1 EV steps.
        val tenths = state.renderExposureTenths
        X14uAutoButton(active = tenths == 0) { if (tenths != 0) dispatch(ScrubRenderExposure(-tenths)) }
        Spacer(Modifier.width(8.dp))
        X14uRuler(
            count = 101,
            index = tenths + 50,
            label = { i -> if ((i - 50) % 10 == 0) renderExposureLabel(i - 50).removeSuffix(".0") else null },
            major = { i -> (i - 50) % 5 == 0 },
            modifier = Modifier.weight(1f),
            onIndexChange = { new, old -> dispatch(ScrubRenderExposure(new - old)) }
        )
        return
    }
    val capability = state.capabilities.capabilityFor(parameter)
    val requestedId = state.requestedCandidateIdFor(parameter)
    val mode = state.exposureControl.mode
    val supported = state.capabilities.supportedExposureModes
    val isEv = parameter == ExposureParameter.Ev
    val locked = CaptureTransitions.isParameterLocked(state, parameter)
    val isManualMeter = isEv && mode == ExposureMode.Manual
    val interactive = !isManualMeter && (isEv || locked || CaptureTransitions.lockModeFor(parameter, mode, supported) != null)
    val applied = when (parameter) {
        ExposureParameter.Shutter -> state.exposureApplied.shutter
        ExposureParameter.Iso -> state.exposureApplied.iso
        ExposureParameter.Ev -> state.exposureApplied.evOrMeter
    }
    val seedId = if (locked || isEv) requestedId
    else applied?.sourceCandidateId?.takeIf { capability.candidate(it) != null } ?: requestedId
    val index = capability.indexOf(seedId).coerceAtLeast(0)
    val zeroId = if (isEv) capability.zeroEvCandidateId() else null
    val auto = if (isEv) requestedId == zeroId else !locked
    X14uAutoButton(active = auto, enabled = interactive || locked) {
        if (isEv) {
            if (zeroId != null && requestedId != zeroId) dispatch(SetExposureCandidate(parameter, zeroId))
        } else {
            CaptureTransitions.unlockModeFor(parameter, mode, supported)?.let { dispatch(SetExposureMode(it)) }
        }
    }
    Spacer(Modifier.width(8.dp))
    val anchors = capability.fullStopAnchorIds
    X14uRuler(
        count = capability.candidates.size,
        index = index,
        label = { i -> capability.candidates[i].takeIf { it.id in anchors }?.displayLabel },
        major = { i -> capability.candidates[i].id in anchors },
        enabled = interactive,
        modifier = Modifier.weight(1f),
        onDragStart = {
            if (!isEv && !locked) {
                CaptureTransitions.lockModeFor(parameter, mode, supported)?.let {
                    haptics.selection()
                    dispatch(SetExposureMode(it))
                }
            }
        },
        onIndexChange = { new, _ -> dispatch(SetExposureCandidate(parameter, capability.candidates[new].id)) }
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.WbRulerRow(state: CaptureUiState, dispatch: CaptureDispatch) {
    var tint by remember { mutableStateOf(false) }
    val locked = CaptureTransitions.isWhiteBalanceLocked(state)
    val supported = state.capabilities.manualWhiteBalanceSupported
    val temp = state.whiteBalanceTemperatureK
    val tintValue = state.whiteBalanceTint
    X14uAutoButton(active = !locked) { if (locked) dispatch(SetWhiteBalanceMode(WhiteBalanceMode.Auto)) }
    Spacer(Modifier.width(8.dp))
    val lockFirst = { if (!locked) dispatch(LockWhiteBalance(temp, tintValue)) }
    if (tint) {
        X14uRuler(
            count = WhiteBalanceMode.TINT_MAX - WhiteBalanceMode.TINT_MIN + 1,
            index = tintValue - WhiteBalanceMode.TINT_MIN,
            label = { i -> (i + WhiteBalanceMode.TINT_MIN).takeIf { it % 10 == 0 }?.let { if (it > 0) "+$it" else "$it" } },
            major = { i -> (i + WhiteBalanceMode.TINT_MIN) % 5 == 0 },
            enabled = supported,
            modifier = Modifier.weight(1f),
            onDragStart = lockFirst,
            onIndexChange = { new, _ -> dispatch(SetWhiteBalanceTempTint(temp, new + WhiteBalanceMode.TINT_MIN)) }
        )
    } else {
        val min = WhiteBalanceMode.TEMP_MIN_K
        X14uRuler(
            count = (WhiteBalanceMode.TEMP_MAX_K - min) / 100 + 1,
            index = ((temp - min) / 100f).roundToInt(),
            label = { i -> (min + i * 100).takeIf { it % 1000 == 0 }?.let { "${it / 1000}K" } },
            major = { i -> (min + i * 100) % 500 == 0 },
            enabled = supported,
            modifier = Modifier.weight(1f),
            onDragStart = lockFirst,
            onIndexChange = { new, _ -> dispatch(SetWhiteBalanceTempTint(min + new * 100, tintValue)) }
        )
    }
    Spacer(Modifier.width(8.dp))
    X14uAutoButton(active = tint, enabled = supported, label = "T") { tint = !tint }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.FocusRulerRow(state: CaptureUiState, dispatch: CaptureDispatch) {
    val capability = state.capabilities.manualFocus
    val ladder = FocusLadder.forCapability(capability)
    val minimum = capability.minimumFocusDistance
    val mode = state.focus.mode
    X14uAutoButton(active = mode == FocusMode.Af) { if (mode != FocusMode.Af) dispatch(SetFocusMode(FocusMode.Af)) }
    Spacer(Modifier.width(8.dp))
    val seed = when {
        mode == FocusMode.Mf -> state.focus.mfNormalized
        else -> state.focus.appliedFocusDiopters?.takeIf { it.isFinite() }
            ?.let { mfNormalizedFromDiopters(it, minimum) } ?: state.focus.mfNormalized
    }
    X14uRuler(
        count = 101,
        index = (seed * 100f).roundToInt().coerceIn(0, 100),
        label = { i ->
            when {
                i == 100 -> "∞"
                i % 25 != 0 -> null
                minimum > 0f && capability.distanceReadoutTrustworthy ->
                    ExposureFormat.formatFocusDistance((1f - i / 100f) * minimum)
                else -> "$i%"
            }
        },
        major = { i -> i % 5 == 0 },
        enabled = ladder != null,
        modifier = Modifier.weight(1f),
        onDragStart = { if (mode != FocusMode.Mf) dispatch(SetManualFocus(seed.coerceIn(0f, 1f))) },
        onIndexChange = { new, _ -> dispatch(SetManualFocus(new / 100f)) }
    )
}

/** Choice chips in one scrolling line (aperture stops, panel options). */
@Composable
private fun X14uChoiceChips(
    labels: List<String>,
    selected: Int,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .height(32.dp)
                    .widthIn(min = 44.dp)
                    .background(if (isSelected) CaptureColors.Accent.copy(alpha = .18f) else Color.White.copy(alpha = .06f), shape)
                    .border(1.dp, if (isSelected) CaptureColors.Accent else Color.White.copy(alpha = .14f), shape)
                    .captureClickable(shape = shape, enabled = enabled) {
                        haptics.selection()
                        onPick(index)
                    }
                    .semantics {
                        contentDescription = label
                        role = Role.RadioButton
                        this.selected = isSelected
                    }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontFamily = CaptureMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    maxLines = 1,
                    color = when {
                        !enabled -> Color.White.copy(alpha = .3f)
                        isSelected -> CaptureColors.Accent
                        else -> Color.White.copy(alpha = .8f)
                    }
                )
            }
        }
    }
}

/** Idle control area: a small Photo / Video selector (tap or swipe), or the last take's summary. */
@Composable
private fun X14uIdleControlArea(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    video: VideoControlState,
    showNumbers: Boolean,
    enabled: Boolean
) {
    val haptics = LocalCaptureHaptics.current
    var dismissed by remember { mutableStateOf<VideoTakeSummary?>(null) }
    val take = video.lastTake
    LaunchedEffect(take) {
        if (take != null) {
            kotlinx.coroutines.delay(if (take.hadProblems) 10_000L else 6_000L)
            dismissed = take
        }
    }
    val showTake = take != null && take !== dismissed && !video.recording &&
        state.captureMode == CaptureMode.Video && (take.hadProblems || video.diagnostics)
    when {
        video.recording && video.diagnostics && showNumbers -> X14uDiagnosticsLine(video)
        showTake -> X14uTakeSummary(take!!) { dismissed = take }
        else -> {
            var travel by remember { mutableStateOf(0f) }
            Row(
                Modifier
                    .fillMaxSize()
                    .pointerInput(enabled, state.captureMode) {
                        if (!enabled) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { travel = 0f },
                            onDragEnd = {
                                val threshold = 48.dp.toPx()
                                val target = when {
                                    travel < -threshold -> CaptureMode.Video
                                    travel > threshold -> CaptureMode.Photo
                                    else -> null
                                }
                                if (target != null && target != state.captureMode) {
                                    haptics.selection()
                                    dispatch(SetCaptureMode(target))
                                }
                                travel = 0f
                            }
                        ) { change, delta ->
                            change.consume()
                            travel += delta
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CaptureMode.entries.forEach { mode ->
                    val selected = state.captureMode == mode
                    Text(
                        if (mode == CaptureMode.Photo) "PHOTO" else "VIDEO",
                        fontFamily = CaptureMono,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 9.sp,
                        letterSpacing = .6.sp,
                        color = when {
                            selected -> if (enabled) CaptureColors.Accent else CaptureColors.Accent.copy(alpha = .4f)
                            else -> Color.White.copy(alpha = if (enabled) .4f else .15f)
                        },
                        modifier = Modifier
                            .captureClickable(shape = CaptureTextRippleShape, enabled = enabled) {
                                if (!selected) {
                                    haptics.selection()
                                    dispatch(SetCaptureMode(mode))
                                }
                            }
                            .semantics {
                                role = Role.Tab
                                this.selected = selected
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                }
            }
        }
    }
}

private fun timingLine(timing: com.rawr.camera.model.VideoTimingState, fps: Int): String =
    "FPS %.1f/%d · DROP %d · GPU %.0f/%.0f ms · ENC %.0f ms".format(
        timing.actualFps, fps, timing.dropped, timing.gpuMs, timing.gpuPeakMs, timing.encoderMs
    )

@Composable
private fun X14uDiagnosticsLine(video: VideoControlState) {
    val timing = video.timing
    Text(
        timing?.let { timingLine(it, video.fps) } ?: "Measuring…",
        fontFamily = CaptureMono,
        fontSize = 11.sp,
        color = if ((timing?.dropped ?: 0) > 0) CaptureColors.Danger else Color.White.copy(alpha = .9f),
        maxLines = 2,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    )
}

@Composable
private fun X14uTakeSummary(take: VideoTakeSummary, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxSize().captureClickable(shape = RoundedCornerShape(8.dp)) { onDismiss() }.padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val t = take.timing
        Text(
            if (take.hadProblems) "LAST TAKE: ${t.dropped} DROPPED" + (t.dropReason?.let { " ($it)" } ?: "")
            else "LAST TAKE: OK",
            fontFamily = CaptureMono,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = if (take.hadProblems) CaptureColors.Danger else CaptureColors.Success
        )
        Text(
            "%d:%02d · %.1f of %d fps · GPU peak %.0f ms · enc %.0f ms".format(
                take.seconds / 60, take.seconds % 60, t.actualFps, take.targetFps, t.gpuPeakMs, t.encoderPeakMs
            ),
            fontFamily = CaptureMono,
            fontSize = 10.sp,
            color = Color.White.copy(alpha = .75f),
            maxLines = 1
        )
    }
}

@Composable
private fun X14uRecordTimer(video: VideoControlState, onTap: () -> Unit, modifier: Modifier) {
    val dropped = video.timing?.dropped ?: 0L
    val color = when (video.health) {
        VideoHealth.Good -> CaptureColors.Success
        VideoHealth.Tight -> Amber
        VideoHealth.Dropping -> CaptureColors.Danger
    }
    Row(
        modifier
            .background(Color.Black.copy(alpha = .4f), RoundedCornerShape(12.dp))
            .captureClickable(shape = RoundedCornerShape(12.dp), enabled = video.diagnostics) { onTap() }
            .semantics {
                contentDescription = "Video recording ${video.status}" + if (dropped > 0) ", $dropped frames dropped" else ""
            }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(video.status, color = Color.White, fontFamily = CaptureMono, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            maxLines = 1, style = ViewfinderTextStyle)
        if (dropped > 0) {
            Text("· $dropped dropped", color = CaptureColors.Danger, fontFamily = CaptureMono, fontWeight = FontWeight.Bold,
                fontSize = 11.sp, maxLines = 1, style = ViewfinderTextStyle)
        }
    }
}

// ---- Panels ----------------------------------------------------------------------------------------------------

/** The panel's group tabs in the exposure row's place. */
@Composable
private fun X14uPanelTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val haptics = LocalCaptureHaptics.current
    Row(
        Modifier.fillMaxWidth().height(X14uDimens.ExposureRow).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tab ->
            Text(
                tab,
                fontFamily = CaptureMono,
                fontSize = 10.sp,
                fontWeight = if (index == selected) FontWeight.Bold else FontWeight.Normal,
                color = if (index == selected) CaptureColors.Accent else Color.White.copy(alpha = .55f),
                maxLines = 1,
                modifier = Modifier
                    .captureClickable(shape = CaptureTextRippleShape) {
                        haptics.selection()
                        onSelect(index)
                    }
                    .padding(horizontal = 9.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun X14uLookPanel(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    renderProfiles: RenderProfileQuickState,
    onSelectRenderProfile: (com.rawr.camera.model.RenderProfileSelection) -> Unit,
    filmQuick: FilmSimQuickState?,
    onFilmEvent: (CaptureFilmEvent) -> Unit,
    quick: ViewfinderQuickState,
    onQuickEvent: (CaptureQuickEvent) -> Unit,
    film: Boolean
) {
    val tabs = buildList {
        add("PROFILE")
        add("TONE")
        add("HIGHLIGHTS")
        if (film) add("FILM")
    }
    var tab by remember { mutableStateOf(0) }
    val current = tabs.getOrElse(tab) { tabs.first() }
    X14uPanelTabs(tabs, tabs.indexOf(current)) { tab = it }
    Box(
        Modifier.fillMaxWidth().wrapContentHeight().padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        when (current) {
            "PROFILE" -> X14uChoiceChips(
                labels = renderProfiles.options.map { it.label },
                selected = renderProfiles.options.indexOfFirst { it.id == renderProfiles.selectedId },
                enabled = !renderProfiles.locked && !renderProfiles.log,
                modifier = Modifier.fillMaxWidth()
            ) { index -> onSelectRenderProfile(renderProfiles.options[index].id) }
            "TONE" -> TonemapStrip(
                profiles = renderProfiles,
                state = state,
                onSelectProfile = onSelectRenderProfile,
                dispatch = dispatch,
                modifier = Modifier.fillMaxWidth()
            )
            "HIGHLIGHTS" -> X14uChoiceChips(
                labels = listOf("Off", "Propagation", "Inpaint Opposed"),
                selected = quick.highlight.mode.ordinal,
                enabled = !quick.locked,
                modifier = Modifier.fillMaxWidth()
            ) { index -> onQuickEvent(CaptureQuickEvent.SetHighlightMode(HighlightQuickMode.entries[index])) }
            "FILM" -> if (filmQuick != null) FilmStrip(
                quick = filmQuick,
                onSelectPreset = { onFilmEvent(CaptureFilmEvent.SelectPreset(it)) },
                onScrubParam = { key, value -> onFilmEvent(CaptureFilmEvent.ScrubNumeric(
                    com.rawr.camera.settings.model.FilmSimNumericParameter.valueOf(key), value)) },
                onResetParam = { key -> onFilmEvent(CaptureFilmEvent.ResetNumeric(
                    com.rawr.camera.settings.model.FilmSimNumericParameter.valueOf(key))) },
                onScrubDiscrete = { key, index -> onFilmEvent(CaptureFilmEvent.ScrubDiscrete(
                    com.rawr.camera.settings.model.FilmSimDiscreteField.valueOf(key), index)) },
                onToggleFlag = { key, enabled -> onFilmEvent(CaptureFilmEvent.SetFlag(
                    com.rawr.camera.settings.model.FilmSimFlag.valueOf(key), enabled)) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun X14uExposurePanel(quick: ViewfinderQuickState, onQuickEvent: (CaptureQuickEvent) -> Unit) {
    val ae = quick.rawrAe
    val tabs = if (ae.enabled) listOf("AE", "METERING", "NIGHT LOOK", "BALANCE", "SLOWEST", "DIGITAL GAIN") else listOf("AE")
    var tab by remember { mutableStateOf(0) }
    val current = tabs.getOrElse(tab) { tabs.first() }
    X14uPanelTabs(tabs, tabs.indexOf(current)) { tab = it }
    Box(Modifier.fillMaxWidth().height(X14uDimens.ControlArea).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
        val enabled = !quick.locked
        when (current) {
            "AE" -> X14uChoiceChips(listOf("Device AE", "Rawr AE"), if (ae.enabled) 1 else 0, enabled, Modifier.fillMaxWidth()) {
                if ((it == 1) != ae.enabled) onQuickEvent(CaptureQuickEvent.ToggleRawrAe)
            }
            "METERING" -> X14uChoiceChips(RawrMetering.entries.map { it.label }, ae.metering.ordinal, enabled, Modifier.fillMaxWidth()) {
                onQuickEvent(CaptureQuickEvent.SetMetering(RawrMetering.entries[it]))
            }
            "NIGHT LOOK" -> X14uChoiceChips(RawrNightLook.entries.map { it.label }, ae.nightLook.ordinal, enabled, Modifier.fillMaxWidth()) { i ->
                onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(nightLook = RawrNightLook.entries[i]) })
            }
            "BALANCE" -> X14uChoiceChips(RawrBalance.entries.map { it.label }, ae.balance.ordinal, enabled, Modifier.fillMaxWidth()) { i ->
                onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(balance = RawrBalance.entries[i]) })
            }
            "SLOWEST" -> X14uChoiceChips(RawrMaxShutter.entries.map { it.label }, ae.maxShutter.ordinal, enabled, Modifier.fillMaxWidth()) { i ->
                onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(maxShutter = RawrMaxShutter.entries[i]) })
            }
            "DIGITAL GAIN" -> X14uChoiceChips(RawrDigitalGain.entries.map { it.label }, ae.digitalGain.ordinal, enabled, Modifier.fillMaxWidth()) { i ->
                onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(digitalGain = RawrDigitalGain.entries[i]) })
            }
        }
    }
}

// ---- Shutter row -----------------------------------------------------------------------------------------------

@Composable
private fun X14uShutterRow(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    quick: ViewfinderQuickState,
    latestImageUri: Uri?,
    onOpenLatestImage: () -> Unit,
    onOpenRenderer: () -> Unit,
    onOpenFilmSimSettings: () -> Unit,
    onOpenMultiframeSettings: () -> Unit,
    onOpenLensSettings: (String) -> Unit,
    onQuickEvent: (CaptureQuickEvent) -> Unit,
    videoControls: VideoControlState,
    onToggleVideoRecording: () -> Unit,
    enabled: Boolean
) {
    val isVideo = state.captureMode == CaptureMode.Video
    val extras = LocalCaptureExtras.current
    val dcp = quick.dcps.firstOrNull { it.lensId == state.selectedLensId }
    val onSelectDcp: (String?) -> Unit = { file -> onQuickEvent(CaptureQuickEvent.SelectDcp(state.selectedLensId, file)) }
    // The front-camera button returns to the last back lens.
    val front = state.capabilities.lenses.firstOrNull { isFrontLens(it.id, it.displayName) }
    var lastBack by remember { mutableStateOf<String?>(null) }
    val onFront = state.capabilities.lenses.firstOrNull { it.id == state.selectedLensId }
        ?.let { isFrontLens(it.id, it.displayName) } == true
    androidx.compose.runtime.SideEffect {
        if (!onFront && state.selectedLensId.isNotEmpty()) lastBack = state.selectedLensId
    }
    Row(
        Modifier.fillMaxWidth().height(X14uDimens.ShutterRow).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(state = state, imageUri = latestImageUri, onClick = onOpenLatestImage, onLongClick = onOpenRenderer,
                modifier = Modifier)
            X14uRoundButton(
                X14uIcons.CameraSwitch,
                active = false,
                description = if (onFront) "Switch to the back camera" else "Switch to the front camera",
                enabled = enabled && front != null,
                size = 44.dp
            ) {
                val target = if (onFront) lastBack ?: state.capabilities.lenses.firstOrNull { !isFrontLens(it.id, it.displayName) }?.id
                else front?.id
                target?.let { dispatch(SelectLens(it)) }
            }
        }
        if (isVideo) {
            VideoRecordButton(
                state = videoControls,
                orientation = state.orientation,
                onClick = onToggleVideoRecording,
                countingDown = state.selfTimerRemainingMs != null
            )
        } else {
            Shutter(
                dispatch = dispatch,
                modifier = Modifier,
                countingDown = state.selfTimerRemainingMs != null,
                longExposure = state.longExposure
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (isVideo) {
                val muted = videoControls.audio.muted
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompactSidePill(
                        icon = X14uIcons.LogCurve,
                        label = "LOG",
                        active = state.videoLogEnabled,
                        testTag = CaptureTestTags.TOPBAR_VIDEO_LOG,
                        contentDescription = "LOG ${if (state.videoLogEnabled) "on" else "off"}. Long press for video settings.",
                        onClick = { if (enabled) dispatch(ToggleVideoLog) },
                        onLongClick = extras.onOpenVideoSettings,
                        onLongClickLabel = "Video settings",
                        minWidth = SidePillWidth
                    )
                    CompactSidePill(
                        icon = if (muted) X14uIcons.MicOff else X14uIcons.Mic,
                        label = if (muted) "MUTED" else "AUDIO",
                        active = !muted,
                        testTag = "capture_x14u_audio",
                        contentDescription = if (muted) "Audio muted. Tap to record sound. Long press for audio settings."
                        else "Audio on. Tap to mute. Long press for audio settings.",
                        onClick = { if (enabled) extras.onToggleAudioMuted() },
                        onLongClick = extras.onOpenVideoSettings,
                        onLongClickLabel = "Audio settings",
                        minWidth = SidePillWidth
                    )
                    DcpSidePill(dcp, { if (enabled) onSelectDcp(it) }) { onOpenLensSettings(state.selectedLensId) }
                }
            } else {
                PhotoLookToggles(state, dispatch, onOpenFilmSimSettings, onOpenMultiframeSettings) {
                    DcpSidePill(dcp, onSelectDcp) { onOpenLensSettings(state.selectedLensId) }
                }
            }
        }
    }
}

// ---- Ruler -----------------------------------------------------------------------------------------------------

/**
 * A horizontal ruler: ticks for every value, labels on some, the current value under the centre needle. Dragging
 * left moves to higher values (like a tape); every step crossed calls [onIndexChange] (new, previous).
 */
@Composable
internal fun X14uRuler(
    count: Int,
    index: Int,
    label: (Int) -> String?,
    major: (Int) -> Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    stepWidth: Dp = 11.dp,
    onDragStart: () -> Unit = {},
    onIndexChange: (Int, Int) -> Unit
) {
    if (count <= 0) return
    val haptics = LocalCaptureHaptics.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    var dragging by remember { mutableStateOf(false) }
    var gestureIndex by remember { mutableStateOf(index.coerceIn(0, count - 1)) }
    var residual by remember { mutableStateOf(0f) }
    val latestIndex by androidx.compose.runtime.rememberUpdatedState(index.coerceIn(0, count - 1))
    val latestOnChange by androidx.compose.runtime.rememberUpdatedState(onIndexChange)
    val latestOnStart by androidx.compose.runtime.rememberUpdatedState(onDragStart)
    val shown = if (dragging) gestureIndex else index.coerceIn(0, count - 1)
    val labelStyle = androidx.compose.ui.text.TextStyle(fontFamily = CaptureMono, fontSize = 9.sp)
    androidx.compose.foundation.Canvas(
        modifier
            .fillMaxHeight()
            .pointerInput(enabled, count) {
                if (!enabled) return@pointerInput
                val stepPx = stepWidth.toPx()
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        gestureIndex = latestIndex
                        residual = 0f
                        latestOnStart()
                    },
                    onDragEnd = { dragging = false; residual = 0f },
                    onDragCancel = { dragging = false; residual = 0f }
                ) { change, delta ->
                    change.consume()
                    residual -= delta
                    while (abs(residual) >= stepPx) {
                        val direction = if (residual > 0) 1 else -1
                        val next = (gestureIndex + direction).coerceIn(0, count - 1)
                        residual -= direction * stepPx
                        if (next == gestureIndex) {
                            residual = 0f
                            break
                        }
                        val previous = gestureIndex
                        gestureIndex = next
                        latestOnChange(next, previous)
                        if (major(next)) haptics.detent() else haptics.sliderTick()
                    }
                }
            }
            .semantics { contentDescription = label(shown) ?: "Ruler" }
    ) {
        val stepPx = stepWidth.toPx()
        val cx = size.width / 2f
        val baseline = size.height * .62f
        val offset = if (dragging) -residual else 0f
        val visible = (size.width / 2f / stepPx).toInt() + 2
        for (i in (shown - visible).coerceAtLeast(0)..(shown + visible).coerceAtMost(count - 1)) {
            val x = cx + (i - shown) * stepPx + offset
            if (x < 0f || x > size.width) continue
            val isMajor = major(i)
            val h = if (isMajor) 12.dp.toPx() else 6.dp.toPx()
            val fade = 1f - (abs(x - cx) / (size.width / 2f)).coerceIn(0f, 1f) * .75f
            drawLine(
                Color.White.copy(alpha = (if (enabled) .7f else .25f) * fade),
                androidx.compose.ui.geometry.Offset(x, baseline - h),
                androidx.compose.ui.geometry.Offset(x, baseline),
                strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx()
            )
            label(i)?.let { text ->
                val layout = measurer.measure(text, labelStyle)
                drawText(
                    layout,
                    color = Color.White.copy(alpha = (if (enabled) .75f else .3f) * fade),
                    topLeft = androidx.compose.ui.geometry.Offset(x - layout.size.width / 2f, baseline + 3.dp.toPx())
                )
            }
        }
        // Needle and the current value above it.
        drawLine(
            CaptureColors.Accent,
            androidx.compose.ui.geometry.Offset(cx, baseline - 18.dp.toPx()),
            androidx.compose.ui.geometry.Offset(cx, baseline + 1.dp.toPx()),
            strokeWidth = 2.dp.toPx()
        )
    }
}
