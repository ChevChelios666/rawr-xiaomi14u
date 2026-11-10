package com.rawr.camera.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.CaptureFilmEvent
import com.rawr.camera.architecture.CaptureQuickEvent
import com.rawr.camera.architecture.CaptureTransitions
import com.rawr.camera.architecture.LockWhiteBalance
import com.rawr.camera.architecture.ScrubRenderExposure
import com.rawr.camera.architecture.ScrubTone
import com.rawr.camera.architecture.SelectLens
import com.rawr.camera.architecture.SetCaptureMode
import com.rawr.camera.architecture.SetExposureCandidate
import com.rawr.camera.architecture.SetExposureMode
import com.rawr.camera.architecture.SetFocusMode
import com.rawr.camera.architecture.SetManualFocus
import com.rawr.camera.architecture.SetRenderExposureTenths
import com.rawr.camera.architecture.SetWhiteBalanceMode
import com.rawr.camera.architecture.SetWhiteBalanceTempTint
import com.rawr.camera.architecture.ToggleVideoLog
import com.rawr.camera.model.CaptureMode
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.DiscreteExposureCapability
import com.rawr.camera.model.ExposureMode
import com.rawr.camera.model.ExposureParameter
import com.rawr.camera.model.FilmSimQuickParam
import com.rawr.camera.model.FilmSimQuickSection
import com.rawr.camera.model.FilmSimQuickState
import com.rawr.camera.model.FilmSimQuickSubsection
import com.rawr.camera.model.FilmStripItem
import com.rawr.camera.model.FocusLadder
import com.rawr.camera.model.FocusMode
import com.rawr.camera.model.HighlightQuickMode
import com.rawr.camera.model.LensOptionsQuickState
import com.rawr.camera.model.Orientation
import com.rawr.camera.model.RenderProfileQuickState
import com.rawr.camera.model.TonemapCatalog
import com.rawr.camera.model.VideoHealth
import com.rawr.camera.model.VideoTakeSummary
import com.rawr.camera.model.ViewfinderQuickState
import com.rawr.camera.model.WhiteBalanceMode
import com.rawr.camera.model.agxStripState
import com.rawr.camera.model.capabilityFor
import com.rawr.camera.model.focusDistanceLabel
import com.rawr.camera.model.formatFilmStripValue
import com.rawr.camera.model.mfNormalizedFromDiopters
import com.rawr.camera.model.requestedCandidateIdFor
import com.rawr.camera.model.zeroEvCandidateId
import com.rawr.camera.settings.model.LensOverrides
import com.rawr.camera.settings.model.RawrBalance
import com.rawr.camera.settings.model.RawrDigitalGain
import com.rawr.camera.settings.model.RawrMaxShutter
import com.rawr.camera.settings.model.RawrMetering
import com.rawr.camera.settings.model.RawrNightLook
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.Exposure
import com.rawr.camera.video.VideoResolutionMode
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * X14U capture screen, laid out like the stock camera (measured on its screenshots; design of 2026-11-10):
 * top bar · viewfinder (12–68% of the screen, corner brackets) with the lens row inside its bottom and the Look /
 * Exposure buttons at its edges · exposure row (value over label) · control area (Photo / Video when idle, a ruler
 * or chips for the tapped value) · shutter row. Text is sized in dp so the system font size can't clip it.
 * In landscape nothing moves; each element's content turns in place.
 */
internal enum class X14uPanel { Look, Exposure }

private val Amber = Color(0xFFFFB74D)
private val X14uFont = FontFamily.Default

/** Text size in dp (immune to the system font scale). */
@Composable
private fun dpText(value: Float): TextUnit = with(LocalDensity.current) { value.dp.toSp() }

/** Front-facing lenses leave the lens row; the front-camera button switches to them. */
internal fun isFrontLens(id: String, name: String): Boolean =
    listOf(id, name).any { it.contains("front", ignoreCase = true) || it.contains("selfie", ignoreCase = true) }

/** A tappable cell of the exposure row (value over label). */
private data class X14uCell(
    val key: String,
    val value: String,
    val label: String,
    /** Manual / changed from default: value in the soft accent. */
    val changed: Boolean = false,
    val enabled: Boolean = true,
    /** Starts a new group (thin divider before it). */
    val groupStart: Boolean = false
)

/** What the control area shows for the selected cell. */
private sealed interface X14uEditor {
    data class Ruler(
        val count: Int,
        val index: Int,
        val valueText: String,
        val label: (Int) -> String?,
        val major: (Int) -> Boolean,
        val spacing: Dp = 10.dp,
        val enabled: Boolean = true,
        /** Null: no A button. */
        val auto: Boolean? = null,
        val onAuto: () -> Unit = {},
        val onReset: () -> Unit = {},
        val onDragStart: () -> Unit = {},
        val endIcons: Boolean = false,
        val onIndex: (new: Int, old: Int) -> Unit
    ) : X14uEditor

    data class Chips(val labels: List<String>, val selected: Int, val enabled: Boolean = true, val onPick: (Int) -> Unit) :
        X14uEditor

    data class Message(val text: String) : X14uEditor
}

private object X14uGeometry {
    /** Stock content height (status and navigation bars excluded) the dp values below were measured at. */
    val reference = 841.dp
}

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
    val orientation = state.orientation
    var selected by remember { mutableStateOf<String?>(null) }
    var panel by remember { mutableStateOf<X14uPanel?>(null) }
    var lookTab by remember { mutableStateOf("TONE") }
    var panelKey by remember { mutableStateOf<String?>(null) }
    var showNumbers by remember { mutableStateOf(false) }
    LaunchedEffect(state.captureMode, state.selectedLensId) {
        selected = null
        panel = null
    }
    val lensOptions = quick.lensOptions.firstOrNull { it.lensId == state.selectedLensId }
    val closeAll: () -> Boolean = {
        val open = selected != null || panel != null
        selected = null
        panel = null
        open
    }
    val film = state.filmSimEnabled && !isVideo && filmQuick != null

    // Cells and editors for what is showing.
    val cells: List<X14uCell>
    val editorFor: (String) -> X14uEditor
    when (panel) {
        null -> {
            cells = exposureCells(state, quick, lensOptions)
            editorFor = { key -> exposureEditor(key, state, dispatch, lensOptions, recordingLock, onQuickEvent) }
        }
        X14uPanel.Exposure -> {
            cells = aeCells(quick)
            editorFor = { key -> aeEditor(key, quick, onQuickEvent) }
        }
        X14uPanel.Look -> {
            val tabs = lookTabs(film)
            val tab = lookTab.takeIf { it in tabs } ?: "TONE"
            when (tab) {
                "PROFILE" -> {
                    val label = renderProfiles.options.firstOrNull { it.id == renderProfiles.selectedId }?.label ?: "RAWR NTRL"
                    cells = listOf(X14uCell("profile", label, "PROFILE"))
                    editorFor = {
                        X14uEditor.Chips(
                            renderProfiles.options.map { it.label },
                            renderProfiles.options.indexOfFirst { it.id == renderProfiles.selectedId },
                            enabled = !renderProfiles.locked && !renderProfiles.log
                        ) { i -> onSelectRenderProfile(renderProfiles.options[i].id) }
                    }
                }
                "HL" -> {
                    val hl = quick.highlight
                    cells = listOf(
                        X14uCell("hl.method", listOf("Off", "Propagation", "Opposed")[hl.mode.ordinal], "METHOD",
                            changed = hl.mode != HighlightQuickMode.Off),
                        X14uCell("hl.compression", hl.compression.roundToInt().toString(), "COMPRESSION",
                            changed = hl.compression != hl.compressionDefault, enabled = hl.mode != HighlightQuickMode.Off)
                    )
                    editorFor = { key -> highlightEditor(key, quick, onQuickEvent) }
                }
                "FILM" -> {
                    val q = filmQuick!!
                    cells = listOf(X14uCell("film.preset", q.activePresetName, "PRESET", changed = q.modified)) +
                        configCells(q)
                    editorFor = { key ->
                        if (key == "film.preset") {
                            X14uEditor.Chips(q.presets.map { it.label }, q.presets.indexOfFirst { it.id == q.selectedPresetId }) { i ->
                                onFilmEvent(CaptureFilmEvent.SelectPreset(q.presets[i].id))
                            }
                        } else {
                            configEditor(key, q, filmActions(onFilmEvent))
                        }
                    }
                }
                else -> {
                    val agx = renderProfiles.agx
                    val profileLabel = renderProfiles.options.firstOrNull { it.id == renderProfiles.selectedId }?.label ?: "AgX"
                    val q = if (agx != null) agxStripState(agx, state.renderExposureTenths, profileLabel) else toneStripState(state)
                    cells = if (renderProfiles.log) emptyList() else configCells(q)
                    val actions = if (agx != null) agxActions(dispatch) else toneActions(state, dispatch)
                    editorFor = { key -> configEditor(key, q, actions) }
                }
            }
        }
    }
    val currentKey = if (panel == null) selected else (panelKey?.takeIf { k -> cells.any { it.key == k } } ?: cells.firstOrNull()?.key)

    BoxWithConstraints(modifier) {
        val s = (maxHeight / X14uGeometry.reference).coerceIn(.8f, 1.25f)
        val top = maxOf(CaptureDimens.TopBarHeight + 4.dp, 65.dp * s)
        val frameWidth = minOf(maxWidth - 16.dp, 379.dp * s, (maxHeight * .6f) * .75f)
        val frame = DpSize(frameWidth, frameWidth * 4f / 3f)
        val bottom = top + frame.height
        val fullHeight = maxHeight
        val shutterCenter = minOf(bottom + 175.dp * s, fullHeight - 50.dp)
        Box(Modifier.fillMaxSize()) {
            CaptureViewfinder(
                state = state,
                dispatch = dispatch,
                preview = preview,
                renderProfiles = renderProfiles.copy(locked = recordingLock),
                onSelectRenderProfile = onSelectRenderProfile,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = top)
                    .size(if (videoBleed) DpSize(frameWidth, frameWidth * 16f / 9f) else frame),
                slotFrame = frame,
                forceCompact = isVideo,
                forceTonemapStrip = isVideo,
                filmQuick = filmQuick,
                onFilmEvent = onFilmEvent,
                quick = quick.copy(locked = recordingLock),
                onQuickEvent = onQuickEvent,
                x14uClean = true,
                onViewfinderTap = closeAll
            )
            // Controls float over a 16:9 preview that runs past the photo frame.
            if (videoBleed) {
                Box(
                    Modifier.fillMaxWidth().offset(y = bottom).height(fullHeight - bottom)
                        .background(CaptureColors.Background.copy(alpha = .6f))
                )
            }
            Box(Modifier.align(Alignment.TopCenter).offset(y = top).size(frame)) {
                FrameBrackets(Modifier.fillMaxSize())
                if (isVideo && videoControls.recording) {
                    X14uRecordTimer(
                        video = videoControls,
                        orientation = orientation,
                        onTap = { if (videoControls.diagnostics) showNumbers = !showNumbers },
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    )
                }
                X14uLensRow(
                    state = state,
                    dispatch = dispatch,
                    quick = quick,
                    panel = panel,
                    lookTabs = lookTabs(film),
                    lookTab = lookTab,
                    onLookTab = { lookTab = it; panelKey = null },
                    enabled = !recordingLock,
                    onTogglePanel = { target ->
                        selected = null
                        panelKey = null
                        panel = if (panel == target) null else target
                    },
                    onOpenLensSettings = onOpenLensSettings,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(bottom = (31.dp * s - 18.dp).coerceAtLeast(4.dp), start = 7.dp, end = 7.dp)
                )
            }
            CaptureTopBar(state, dispatch, onOpenSettings, videoLocked = recordingLock, showLog = false) {
                X14uOptionToggles(quick, lensOptions, isVideo, enabled = !recordingLock, orientation, onQuickEvent)
            }
            X14uCellRow(
                cells = cells,
                selectedKey = currentKey,
                orientation = orientation,
                modifier = Modifier.fillMaxWidth().offset(y = bottom + 6.dp * s).height(48.dp * s)
            ) { key ->
                if (panel == null) selected = if (selected == key) null else key else panelKey = key
            }
            Box(
                Modifier.fillMaxWidth().offset(y = bottom + 58.dp * s).height(76.dp * s).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val key = currentKey
                if (key != null) {
                    X14uEditorView(editorFor(key), orientation)
                } else if (panel == null) {
                    X14uIdleControlArea(state, dispatch, videoControls, showNumbers, enabled = !recordingLock)
                }
            }
            X14uShutterRow(
                state, dispatch, quick, latestImageUri, onOpenLatestImage, onOpenRenderer, onOpenFilmSimSettings,
                onOpenMultiframeSettings, onOpenLensSettings, onQuickEvent, videoControls, onToggleVideoRecording,
                enabled = !recordingLock,
                modifier = Modifier.fillMaxWidth().offset(y = shutterCenter - 46.dp).height(92.dp)
            )
        }
    }
}

private fun lookTabs(film: Boolean): List<String> = buildList {
    add("PROFILE")
    add("TONE")
    add("HL")
    if (film) add("FILM")
}

@Composable
private fun FrameBrackets(modifier: Modifier) {
    Canvas(modifier) {
        val l = 22.dp.toPx()
        val w = 1.5.dp.toPx()
        val c = Color.White.copy(alpha = .55f)
        val corners = listOf(Offset(0f, 0f) to Offset(1f, 1f), Offset(size.width, 0f) to Offset(-1f, 1f),
            Offset(0f, size.height) to Offset(1f, -1f), Offset(size.width, size.height) to Offset(-1f, -1f))
        corners.forEach { (p, d) ->
            val o = Offset(p.x + d.x * w / 2, p.y + d.y * w / 2)
            drawLine(c, o, Offset(o.x + d.x * l, o.y), w)
            drawLine(c, o, Offset(o.x, o.y + d.y * l), w)
        }
    }
}

// ---- Top bar toggles -------------------------------------------------------------------------------------------

/** DCG and 50 MP (every on/off option row) as plain text: accent when on, struck through when off or absent. */
@Composable
private fun RowScope.X14uOptionToggles(
    quick: ViewfinderQuickState,
    options: LensOptionsQuickState?,
    video: Boolean,
    enabled: Boolean,
    orientation: Orientation,
    onQuickEvent: (CaptureQuickEvent) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    lensOptionSlots(quick.lensOptions).filter { it.second.toggle }.forEach { (name, _) ->
        val row = options?.options?.indexOfFirst { it.name.trim().equals(name, ignoreCase = true) } ?: -1
        val available = options != null && row >= 0 && row !in options.unavailable
        if (video && !available) return@forEach
        val on = available && options!!.picks.getOrElse(row) { 0 } == 1
        TopBarSlot {
            Text(
                name.uppercase().replace(" ", ""),
                fontFamily = X14uFont,
                fontWeight = FontWeight.Medium,
                fontSize = dpText(13f),
                maxLines = 1,
                textDecoration = if (on) TextDecoration.None else TextDecoration.LineThrough,
                color = when {
                    on -> if (enabled) CaptureColors.Accent else CaptureColors.Accent.copy(alpha = .45f)
                    available && enabled -> Color.White.copy(alpha = .55f)
                    else -> Color.White.copy(alpha = .22f)
                },
                modifier = Modifier
                    .captureClickable(shape = RoundedCornerShape(8.dp), enabled = available && enabled, role = Role.Switch) {
                        haptics.selection()
                        onQuickEvent(CaptureQuickEvent.SelectLensOption(options!!.lensId, row, if (on) 0 else 1))
                    }
                    .semantics { contentDescription = "$name ${if (on) "on" else "off"}" }
                    .padding(horizontal = 6.dp, vertical = 10.dp)
                    .uprightInLandscape(orientation)
            )
        }
    }
}

// ---- Lens row --------------------------------------------------------------------------------------------------

/** Round dark button: accent icon and ring when [highlighted] (lens overrides), filled accent when [open]. */
@Composable
private fun X14uRoundButton(
    icon: ImageVector,
    description: String,
    orientation: Orientation,
    open: Boolean = false,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 36.dp,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    Box(
        Modifier
            .size(size)
            .background(if (open) CaptureColors.Accent else Color.Black.copy(alpha = .5f), CircleShape)
            .then(if (highlighted && !open) Modifier.border(1.2.dp, CaptureColors.Accent, CircleShape) else Modifier)
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
                open -> Color.Black
                highlighted -> CaptureColors.Accent
                else -> Color.White.copy(alpha = .9f)
            },
            modifier = Modifier.size(size * .55f).uprightInLandscape(orientation)
        )
    }
}

@Composable
private fun X14uLensRow(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    quick: ViewfinderQuickState,
    panel: X14uPanel?,
    lookTabs: List<String>,
    lookTab: String,
    onLookTab: (String) -> Unit,
    enabled: Boolean,
    onTogglePanel: (X14uPanel) -> Unit,
    onOpenLensSettings: (String) -> Unit,
    modifier: Modifier
) {
    val haptics = LocalCaptureHaptics.current
    val extras = LocalCaptureExtras.current
    val orientation = state.orientation
    val lenses = state.capabilities.lenses.filterNot { isFrontLens(it.id, it.displayName) }
    val groups = quick.overrideGroups
    Box(modifier.height(40.dp)) {
        Box(Modifier.align(Alignment.CenterStart)) {
            X14uRoundButton(
                X14uIcons.Look, "Look: profile, tone and highlights", orientation,
                open = panel == X14uPanel.Look,
                highlighted = groups.any { it != LensOverrides.GROUP_AE }
            ) { onTogglePanel(X14uPanel.Look) }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            X14uRoundButton(
                Icons.Outlined.Exposure, "Exposure: auto exposure and metering. Long press for exposure settings.", orientation,
                open = panel == X14uPanel.Exposure,
                highlighted = LensOverrides.GROUP_AE in groups,
                onLongClick = extras.onOpenExposureSettings
            ) { onTogglePanel(X14uPanel.Exposure) }
        }
        val pill = RoundedCornerShape(18.dp)
        Box(Modifier.align(Alignment.Center).padding(horizontal = 42.dp)) {
        Row(
            Modifier
                .height(36.dp)
                .background(Color.Black.copy(alpha = .4f), pill)
                .padding(horizontal = 4.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (panel) {
                X14uPanel.Look -> lookTabs.forEach { tab ->
                    val on = tab == lookTab || (tab == "TONE" && lookTab !in lookTabs)
                    PillItem(if (tab == "HL") "HIGHLIGHTS" else tab, on, orientation, width = null) {
                        haptics.selection()
                        onLookTab(tab)
                    }
                }
                X14uPanel.Exposure -> PillItem(
                    if (quick.rawrAe.enabled) "RAWR AE" else "DEVICE AE", true, orientation, width = null
                ) { onTogglePanel(X14uPanel.Exposure) }
                null -> lenses.forEach { lens ->
                    val isSelected = state.selectedLensId == lens.id
                    Box(
                        Modifier
                            .size(42.dp, 36.dp)
                            .captureCombinedClickable(
                                shape = CircleShape,
                                enabled = enabled,
                                onLongClickLabel = "${lens.displayName} lens settings",
                                onLongClick = {
                                    haptics.selection()
                                    onOpenLensSettings(lens.id)
                                }
                            ) {
                                haptics.selection()
                                if (!isSelected) dispatch(SelectLens(lens.id))
                            }
                            .semantics {
                                contentDescription = "Lens ${lens.displayName}"
                                role = Role.RadioButton
                                this.selected = isSelected
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) Box(Modifier.size(32.dp).background(Color.Black.copy(alpha = .55f), CircleShape))
                        Text(
                            lens.displayName,
                            fontFamily = X14uFont,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                            fontSize = dpText(if (isSelected) 13f else 14f),
                            maxLines = 1,
                            color = when {
                                isSelected -> if (enabled) CaptureColors.Accent else CaptureColors.Accent.copy(alpha = .45f)
                                else -> Color.White.copy(alpha = if (enabled) .9f else .3f)
                            },
                            modifier = Modifier.uprightInLandscape(orientation)
                        )
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun PillItem(text: String, on: Boolean, orientation: Orientation, width: Dp?, onClick: () -> Unit) {
    Text(
        text,
        fontFamily = X14uFont,
        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
        fontSize = dpText(12f),
        maxLines = 1,
        color = if (on) CaptureColors.Accent else Color.White.copy(alpha = .7f),
        modifier = Modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .captureClickable(shape = RoundedCornerShape(14.dp)) { onClick() }
            .padding(horizontal = 10.dp, vertical = 9.dp)
    )
}

// ---- Exposure row ----------------------------------------------------------------------------------------------

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

/** The aperture row of a lens, if it has one (every choice an f-number). */
private fun apertureRow(options: LensOptionsQuickState?): Int {
    val list = options?.options ?: return -1
    return list.indexOfFirst { o -> !o.toggle && o.choices.isNotEmpty() && o.choices.all { it.label.trim().startsWith("f/") } }
        .takeIf { it >= 0 && it !in options.unavailable } ?: -1
}

private fun exposureCells(state: CaptureUiState, quick: ViewfinderQuickState, options: LensOptionsQuickState?): List<X14uCell> {
    val (ev, evManual) = exposureValue(ExposureParameter.Ev, state)
    val (ss, ssManual) = exposureValue(ExposureParameter.Shutter, state)
    val (iso, isoManual) = exposureValue(ExposureParameter.Iso, state)
    val row = apertureRow(options)
    val aperture = if (row >= 0) options!!.options[row].choices.getOrNull(options.picks.getOrElse(row) { 0 })?.label.orEmpty()
    else quick.nativeApertures[state.selectedLensId]?.let { "f/" + "%.1f".format(it).removeSuffix(".0") } ?: "f/–"
    val wbManual = CaptureTransitions.isWhiteBalanceLocked(state)
    val af = when (state.focus.mode) {
        FocusMode.Af -> "AF"
        FocusMode.AfLock -> "AF-L"
        FocusMode.Mf -> "MF"
    }
    return listOf(
        X14uCell("ev", ev, "EV", evManual),
        X14uCell("f", aperture.replace("f/", "F"), "A", enabled = row >= 0),
        X14uCell("ss", ss, "S", ssManual),
        X14uCell("iso", iso, "ISO", isoManual),
        X14uCell("wb", if (wbManual) "${state.whiteBalanceTemperatureK}K" else "AWB", "WB", wbManual,
            enabled = state.capabilities.manualWhiteBalanceSupported || wbManual),
        X14uCell("af", if (state.focus.mode == FocusMode.Mf) state.focusDistanceLabel() else af, "F",
            state.focus.mode != FocusMode.Af)
    )
}

@Composable
private fun X14uCellRow(
    cells: List<X14uCell>,
    selectedKey: String?,
    orientation: Orientation,
    modifier: Modifier,
    onTap: (String) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    val scroll = cells.size > 6
    Row(
        modifier
            .then(if (scroll) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        cells.forEach { cell ->
            if (cell.groupStart) {
                Box(Modifier.padding(horizontal = 4.dp).size(1.dp, 22.dp).background(Color.White.copy(alpha = .2f)))
            }
            val isOpen = cell.key == selectedKey
            Box(
                Modifier
                    .then(if (scroll) Modifier.width(66.dp) else Modifier.weight(1f))
                    .fillMaxHeight()
                    .captureClickable(shape = CaptureTextRippleShape, enabled = cell.enabled) {
                        haptics.selection()
                        onTap(cell.key)
                    }
                    .semantics {
                        contentDescription = "${cell.label} ${cell.value}"
                        selected = isOpen
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier.uprightInLandscape(orientation),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        cell.value,
                        fontFamily = X14uFont,
                        fontSize = dpText(if (scroll) 14f else 16f),
                        lineHeight = dpText(if (scroll) 16f else 19f),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        color = when {
                            isOpen -> CaptureColors.Accent
                            !cell.enabled -> Color.White.copy(alpha = .35f)
                            cell.changed -> CaptureColors.AccentSoft
                            else -> Color.White
                        }
                    )
                    Text(
                        cell.label,
                        fontFamily = X14uFont,
                        fontSize = dpText(10.5f),
                        lineHeight = dpText(12f),
                        maxLines = 1,
                        color = if (isOpen) CaptureColors.Accent else Color.White.copy(alpha = if (cell.enabled) .55f else .25f)
                    )
                }
            }
        }
    }
}

// ---- Editors ---------------------------------------------------------------------------------------------------

private fun numberOf(id: String): Double? = id.substringAfter(':', "").toDoubleOrNull()

/** Standard 1/3-stop ISO values. */
private val THIRD_STOP_ISO = listOf(
    25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200,
    4000, 5000, 6400, 8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400, 204800,
    409600
)

/**
 * The ruler's stops for a camera axis, as candidate indexes: shutter on its 1/3 stops, ISO on the standard 1/3-stop
 * values (nearest camera value each), EV and video shutter angles as they are.
 */
private fun rulerStops(parameter: ExposureParameter, capability: DiscreteExposureCapability): List<Int> {
    val candidates = capability.candidates
    return when (parameter) {
        ExposureParameter.Shutter -> {
            val thirds = candidates.indices.filter { candidates[it].id in capability.magneticSnapAnchorIds }
            thirds.ifEmpty { candidates.indices.toList() }
        }
        ExposureParameter.Iso -> {
            val values = candidates.map { numberOf(it.id) }
            if (values.any { it == null }) return candidates.indices.toList()
            val min = values.first()!!
            val max = values.last()!!
            val wanted = (listOf(min) + THIRD_STOP_ISO.map { it.toDouble() }.filter { it > min * 1.05 && it < max / 1.05 } + listOf(max))
            wanted.map { w -> values.indices.minBy { abs(ln(values[it]!! / w)) } }.distinct()
        }
        ExposureParameter.Ev -> candidates.indices.toList()
    }
}

private fun exposureEditor(
    key: String,
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    options: LensOptionsQuickState?,
    recordingLock: Boolean,
    onQuickEvent: (CaptureQuickEvent) -> Unit
): X14uEditor = when (key) {
    "ev" -> exposureRuler(ExposureParameter.Ev, state, dispatch)
    "ss" -> exposureRuler(ExposureParameter.Shutter, state, dispatch)
    "iso" -> exposureRuler(ExposureParameter.Iso, state, dispatch)
    "f" -> {
        val row = apertureRow(options)
        if (row < 0 || options == null) X14uEditor.Message("Fixed aperture on this lens")
        else {
            val option = options.options[row]
            X14uEditor.Chips(
                option.choices.map { it.label },
                options.picks.getOrElse(row) { option.selected },
                enabled = row !in options.locked && !recordingLock
            ) { i -> if (i != option.selected) onQuickEvent(CaptureQuickEvent.SelectLensOption(options.lensId, row, i)) }
        }
    }
    "wb" -> wbRuler(state, dispatch)
    else -> focusRuler(state, dispatch)
}

private fun exposureRuler(parameter: ExposureParameter, state: CaptureUiState, dispatch: CaptureDispatch): X14uEditor {
    if (parameter == ExposureParameter.Ev && CaptureTransitions.evAdjustsRenderExposure(state)) {
        // Full Manual: EV edits the render exposure, 0.1 EV steps.
        val tenths = state.renderExposureTenths
        return X14uEditor.Ruler(
            count = 101,
            index = tenths + 50,
            valueText = renderExposureLabel(tenths),
            label = { i -> if ((i - 50) % 10 == 0) renderExposureLabel(i - 50).removeSuffix(".0") else null },
            major = { i -> (i - 50) % 5 == 0 },
            spacing = 8.dp,
            auto = tenths == 0,
            onAuto = { if (tenths != 0) dispatch(ScrubRenderExposure(-tenths)) },
            onReset = { if (tenths != 0) dispatch(ScrubRenderExposure(-tenths)) },
            onIndex = { new, old -> dispatch(ScrubRenderExposure(new - old)) }
        )
    }
    val capability = state.capabilities.capabilityFor(parameter)
    val candidates = capability.candidates
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
    val stops = rulerStops(parameter, capability)
    val seed = capability.indexOf(seedId).coerceAtLeast(0)
    val seedValue = numberOf(candidates[seed].id)
    val index = stops.indices.minByOrNull { i ->
        val v = numberOf(candidates[stops[i]].id)
        if (v != null && seedValue != null && v > 0 && seedValue > 0) abs(ln(v / seedValue)) else abs(stops[i] - seed).toDouble()
    } ?: 0
    val zeroId = if (isEv) capability.zeroEvCandidateId() else null
    val auto = if (isEv) requestedId == zeroId else !locked
    val (valueText, _) = exposureValue(parameter, state)
    val anchors = capability.fullStopAnchorIds
    val video = parameter == ExposureParameter.Shutter && state.captureMode == CaptureMode.Video
    val labelled: (Int) -> Boolean = { i ->
        val c = candidates[stops[i]]
        when {
            video || isEv -> c.id in anchors
            parameter == ExposureParameter.Iso -> numberOf(c.id)?.let { v ->
                val stopsFrom100 = ln(v / 100.0) / ln(2.0)
                abs(stopsFrom100 - stopsFrom100.roundToInt()) < .08 && stopsFrom100.roundToInt() % 2 == 0
            } == true
            else -> c.id in anchors && numberOf(c.id)?.let { ns ->
                // Every third full stop (… 1/1000, 1/125, 1/15, 1/2, 4 s …).
                val stopsFrom1s = ln(ns / 1e9) / ln(2.0)
                ((stopsFrom1s.roundToInt() % 3) + 3) % 3 == 0
            } == true
        }
    }
    val reset: () -> Unit = {
        if (isEv) {
            if (zeroId != null && requestedId != zeroId) dispatch(SetExposureCandidate(parameter, zeroId))
        } else {
            CaptureTransitions.unlockModeFor(parameter, mode, supported)?.let { dispatch(SetExposureMode(it)) }
        }
    }
    return X14uEditor.Ruler(
        count = stops.size,
        index = index,
        valueText = valueText,
        label = { i -> if (labelled(i)) candidates[stops[i]].displayLabel else null },
        major = { i -> candidates[stops[i]].id in anchors },
        spacing = when (parameter) {
            ExposureParameter.Iso -> 12.dp
            ExposureParameter.Shutter -> if (video) 14.dp else 8.5.dp
            ExposureParameter.Ev -> 10.dp
        },
        enabled = interactive,
        auto = auto,
        onAuto = reset,
        onReset = reset,
        onDragStart = {
            if (!isEv && !locked) CaptureTransitions.lockModeFor(parameter, mode, supported)?.let { dispatch(SetExposureMode(it)) }
        },
        onIndex = { new, _ -> dispatch(SetExposureCandidate(parameter, candidates[stops[new]].id)) }
    )
}

private fun wbRuler(state: CaptureUiState, dispatch: CaptureDispatch): X14uEditor {
    val locked = CaptureTransitions.isWhiteBalanceLocked(state)
    val temp = state.whiteBalanceTemperatureK
    val tint = state.whiteBalanceTint
    val min = WhiteBalanceMode.TEMP_MIN_K
    val toAuto: () -> Unit = { if (locked) dispatch(SetWhiteBalanceMode(WhiteBalanceMode.Auto)) }
    return X14uEditor.Ruler(
        count = (WhiteBalanceMode.TEMP_MAX_K - min) / 100 + 1,
        index = ((temp - min) / 100f).roundToInt(),
        valueText = if (locked) "${temp}K" else "AWB",
        label = { i -> (min + i * 100).takeIf { it % 2000 == 0 }?.let { "${it}K" } },
        major = { i -> (min + i * 100) % 1000 == 0 },
        spacing = 8.dp,
        enabled = state.capabilities.manualWhiteBalanceSupported,
        auto = !locked,
        onAuto = toAuto,
        onReset = toAuto,
        onDragStart = { if (!locked) dispatch(LockWhiteBalance(temp, tint)) },
        onIndex = { new, _ -> dispatch(SetWhiteBalanceTempTint(min + new * 100, tint)) }
    )
}

private const val FOCUS_STEPS = 30

private fun focusLabel(diopters: Float): String {
    if (diopters <= .01f) return "∞"
    val m = 1f / diopters
    return if (m < 1f) "${(m * 100).roundToInt()}cm" else if (m < 10f) "%.1fm".format(m).replace(".0m", "m") else "${m.roundToInt()}m"
}

private fun focusRuler(state: CaptureUiState, dispatch: CaptureDispatch): X14uEditor {
    val capability = state.capabilities.manualFocus
    val ladder = FocusLadder.forCapability(capability)
    val minimum = capability.minimumFocusDistance
    val mode = state.focus.mode
    val seed = when {
        mode == FocusMode.Mf -> state.focus.mfNormalized
        else -> state.focus.appliedFocusDiopters?.takeIf { it.isFinite() }
            ?.let { mfNormalizedFromDiopters(it, minimum) } ?: state.focus.mfNormalized
    }
    val diopters: (Int) -> Float = { i -> (1f - i / FOCUS_STEPS.toFloat()) * minimum }
    // Label the steps nearest 10 cm, 30 cm, 1 m and 3 m (when the lens reaches them).
    val labelled = if (minimum > 0f && capability.distanceReadoutTrustworthy) {
        listOf(.1f, .3f, 1f, 3f).filter { 1f / it <= minimum }.map { m ->
            (0..FOCUS_STEPS).minBy { abs(diopters(it) - 1f / m) }
        }.toSet()
    } else emptySet()
    val toAf: () -> Unit = { if (mode != FocusMode.Af) dispatch(SetFocusMode(FocusMode.Af)) }
    return X14uEditor.Ruler(
        count = FOCUS_STEPS + 1,
        index = (seed * FOCUS_STEPS).roundToInt().coerceIn(0, FOCUS_STEPS),
        valueText = if (mode == FocusMode.Mf) state.focusDistanceLabel() else "AF",
        label = { i -> if (i in labelled) focusLabel(diopters(i)) else null },
        major = { i -> i in labelled || i == FOCUS_STEPS },
        spacing = 10.dp,
        enabled = ladder != null,
        auto = mode == FocusMode.Af,
        onAuto = toAf,
        onReset = toAf,
        endIcons = true,
        onDragStart = { if (mode != FocusMode.Mf) dispatch(SetManualFocus(seed.coerceIn(0f, 1f))) },
        onIndex = { new, _ -> dispatch(SetManualFocus(new / FOCUS_STEPS.toFloat())) }
    )
}

private fun highlightEditor(key: String, quick: ViewfinderQuickState, onQuickEvent: (CaptureQuickEvent) -> Unit): X14uEditor {
    val hl = quick.highlight
    return if (key == "hl.method") {
        X14uEditor.Chips(listOf("Off", "Propagation", "Inpaint Opposed"), hl.mode.ordinal, enabled = !quick.locked) { i ->
            onQuickEvent(CaptureQuickEvent.SetHighlightMode(HighlightQuickMode.entries[i]))
        }
    } else {
        X14uEditor.Ruler(
            count = 61,
            index = (hl.compression / 5f).roundToInt().coerceIn(0, 60),
            valueText = hl.compression.roundToInt().toString(),
            label = { i -> if (i % 10 == 0) "${i * 5}" else null },
            major = { i -> i % 5 == 0 },
            spacing = 9.dp,
            enabled = hl.mode != HighlightQuickMode.Off && !quick.locked,
            onReset = { onQuickEvent(CaptureQuickEvent.SetHighlightCompression(hl.compressionDefault)) },
            onIndex = { new, _ -> onQuickEvent(CaptureQuickEvent.SetHighlightCompression(new * 5f)) }
        )
    }
}

private fun aeCells(quick: ViewfinderQuickState): List<X14uCell> {
    val ae = quick.rawrAe
    val on = ae.enabled
    return listOf(
        X14uCell("ae", if (on) "Rawr" else "Device", "AE", changed = on),
        X14uCell("ae.metering", ae.metering.label, "METER", enabled = on),
        X14uCell("ae.night", ae.nightLook.label, "NIGHT", enabled = on),
        X14uCell("ae.balance", ae.balance.label.replace("Balanced", "Bal."), "BALANCE", enabled = on),
        X14uCell("ae.slowest", ae.maxShutter.label.removeSuffix(" (1/15)"), "SLOWEST", enabled = on),
        X14uCell("ae.gain", ae.digitalGain.label, "GAIN", enabled = on)
    )
}

private fun aeEditor(key: String, quick: ViewfinderQuickState, onQuickEvent: (CaptureQuickEvent) -> Unit): X14uEditor {
    val ae = quick.rawrAe
    val enabled = !quick.locked
    return when (key) {
        "ae.metering" -> X14uEditor.Chips(RawrMetering.entries.map { it.label }, ae.metering.ordinal, enabled) {
            onQuickEvent(CaptureQuickEvent.SetMetering(RawrMetering.entries[it]))
        }
        "ae.night" -> X14uEditor.Chips(RawrNightLook.entries.map { it.label }, ae.nightLook.ordinal, enabled) { i ->
            onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(nightLook = RawrNightLook.entries[i]) })
        }
        "ae.balance" -> X14uEditor.Chips(RawrBalance.entries.map { it.label }, ae.balance.ordinal, enabled) { i ->
            onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(balance = RawrBalance.entries[i]) })
        }
        "ae.slowest" -> X14uEditor.Chips(RawrMaxShutter.entries.map { it.label }, ae.maxShutter.ordinal, enabled) { i ->
            onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(maxShutter = RawrMaxShutter.entries[i]) })
        }
        "ae.gain" -> X14uEditor.Chips(RawrDigitalGain.entries.map { it.label }, ae.digitalGain.ordinal, enabled) { i ->
            onQuickEvent(CaptureQuickEvent.SetRawrAeOption { it.copy(digitalGain = RawrDigitalGain.entries[i]) })
        }
        else -> X14uEditor.Chips(listOf("Device AE", "Rawr AE"), if (ae.enabled) 1 else 0, enabled) {
            if ((it == 1) != ae.enabled) onQuickEvent(CaptureQuickEvent.ToggleRawrAe)
        }
    }
}

// ---- Tone / AgX / film controls (one structure: FilmSimQuickState sections) ------------------------------------

private class ConfigActions(
    val set: (String, Float) -> Unit,
    val reset: (String) -> Unit,
    val discrete: (String, Int) -> Unit = { _, _ -> },
    val flag: (String, Boolean) -> Unit = { _, _ -> }
)

/** The regular profile tone controls in the film-strip structure. */
private fun toneStripState(state: CaptureUiState): FilmSimQuickState {
    val params = TonemapCatalog.all.associate { d ->
        val value = if (d.param == null) state.renderExposureTenths / 10f else TonemapCatalog.valueOf(state, d.param).toFloat()
        d.jsonKey to FilmSimQuickParam(
            key = d.jsonKey, shortLabel = d.shortLabel, value = value, defaultValue = d.defaultValue,
            minimum = d.minimum, maximum = d.maximum, step = d.step, decimals = d.decimals,
            displayValue = if (d.param == null) renderExposureLabel(state.renderExposureTenths)
            else formatFilmStripValue(value, d.minimum, d.decimals),
            isDefault = value == d.defaultValue
        )
    }
    val groups = TonemapCatalog.all.groupBy { it.group }.map { (group, items) ->
        FilmSimQuickSubsection(group.name, group.name, items.map { FilmStripItem.Numeric(it.jsonKey) })
    }
    return FilmSimQuickState("Tone", null, false, sections = listOf(FilmSimQuickSection("tone", "TONE", groups)), params = params)
}

private fun toneActions(state: CaptureUiState, dispatch: CaptureDispatch) = ConfigActions(
    set = { key, value ->
        val d = TonemapCatalog.all.first { it.jsonKey == key }
        if (d.param == null) dispatch(SetRenderExposureTenths(com.rawr.camera.model.renderExposureTenthsOf(value)))
        else {
            val delta = value.roundToInt() - TonemapCatalog.valueOf(state, d.param)
            if (delta != 0) dispatch(ScrubTone(d.param, delta))
        }
    },
    reset = { key ->
        val d = TonemapCatalog.all.first { it.jsonKey == key }
        if (d.param == null) dispatch(SetRenderExposureTenths(0))
        else {
            val delta = d.defaultValue.roundToInt() - TonemapCatalog.valueOf(state, d.param)
            if (delta != 0) dispatch(ScrubTone(d.param, delta))
        }
    }
)

private fun agxActions(dispatch: CaptureDispatch) = ConfigActions(
    set = { key, value -> dispatchAgx(dispatch, key, value) },
    reset = { key -> dispatchAgx(dispatch, key, null) }
)

private fun filmActions(onFilmEvent: (CaptureFilmEvent) -> Unit) = ConfigActions(
    set = { key, value ->
        onFilmEvent(CaptureFilmEvent.ScrubNumeric(com.rawr.camera.settings.model.FilmSimNumericParameter.valueOf(key), value))
    },
    reset = { key ->
        onFilmEvent(CaptureFilmEvent.ResetNumeric(com.rawr.camera.settings.model.FilmSimNumericParameter.valueOf(key)))
    },
    discrete = { key, index ->
        onFilmEvent(CaptureFilmEvent.ScrubDiscrete(com.rawr.camera.settings.model.FilmSimDiscreteField.valueOf(key), index))
    },
    flag = { key, enabled ->
        onFilmEvent(CaptureFilmEvent.SetFlag(com.rawr.camera.settings.model.FilmSimFlag.valueOf(key), enabled))
    }
)

private fun configCells(q: FilmSimQuickState): List<X14uCell> = buildList {
    q.sections.forEachIndexed { s, section ->
        section.items.forEachIndexed { i, item ->
            val start = i == 0 && (s > 0 || isNotEmpty())
            when (item) {
                is FilmStripItem.Numeric -> q.params[item.key]?.let {
                    add(X14uCell(item.key, it.displayValue, it.shortLabel, changed = !it.isDefault, groupStart = start))
                }
                is FilmStripItem.Discrete -> q.discretes[item.key]?.let {
                    add(X14uCell(item.key, it.displayValue, it.shortLabel, groupStart = start))
                }
                is FilmStripItem.Flag -> q.flags[item.key]?.let {
                    add(X14uCell(item.key, if (it.enabled) "On" else "Off", it.shortLabel, changed = it.enabled, groupStart = start))
                }
            }
        }
    }
}

private fun configEditor(key: String, q: FilmSimQuickState, actions: ConfigActions): X14uEditor {
    q.params[key]?.let { p ->
        val step = if (p.step > 0f) p.step else .01f
        val count = (((p.maximum - p.minimum) / step).roundToInt() + 1).coerceIn(2, 2001)
        val index = ((p.value - p.minimum) / step).roundToInt().coerceIn(0, count - 1)
        val every = when {
            count <= 21 -> 5
            count <= 61 -> 10
            count <= 201 -> 25
            else -> 100
        }
        return X14uEditor.Ruler(
            count = count,
            index = index,
            valueText = p.displayValue,
            label = { i -> if (i % every == 0) formatFilmStripValue(p.minimum + i * step, p.minimum, p.decimals) else null },
            major = { i -> i % every == 0 },
            spacing = 7.dp,
            onReset = { actions.reset(key) },
            onIndex = { new, _ -> actions.set(key, (p.minimum + new * step).coerceIn(p.minimum, p.maximum)) }
        )
    }
    q.discretes[key]?.let { d -> return X14uEditor.Chips(d.options, d.selectedIndex) { actions.discrete(key, it) } }
    q.flags[key]?.let { f -> return X14uEditor.Chips(listOf("Off", "On"), if (f.enabled) 1 else 0) { actions.flag(key, it == 1) } }
    return X14uEditor.Message("")
}

// ---- Editor views ----------------------------------------------------------------------------------------------

@Composable
private fun X14uEditorView(editor: X14uEditor, orientation: Orientation) {
    when (editor) {
        is X14uEditor.Ruler -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (editor.auto != null) {
                X14uAutoButton(active = editor.auto, enabled = true, orientation = orientation, onClick = editor.onAuto)
                Spacer(Modifier.width(6.dp))
            }
            X14uRuler(editor, orientation, Modifier.weight(1f))
        }
        is X14uEditor.Chips -> X14uChoiceChips(editor.labels, editor.selected, editor.enabled, orientation, Modifier.fillMaxWidth(), editor.onPick)
        is X14uEditor.Message -> Text(
            editor.text, fontFamily = X14uFont, fontSize = dpText(12f), color = CaptureColors.Muted,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun X14uAutoButton(active: Boolean, enabled: Boolean, orientation: Orientation, onClick: () -> Unit) {
    val haptics = LocalCaptureHaptics.current
    Box(
        Modifier
            .size(36.dp)
            .background(if (active) CaptureColors.Accent.copy(alpha = .22f) else Color.White.copy(alpha = .12f), CircleShape)
            .captureClickable(shape = CircleShape, enabled = enabled) {
                haptics.selection()
                onClick()
            }
            .semantics { contentDescription = "Auto" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "A",
            fontFamily = X14uFont,
            fontSize = dpText(15f),
            color = if (active) CaptureColors.Accent else Color.White,
            modifier = Modifier.uprightInLandscape(orientation)
        )
    }
}

@Composable
private fun X14uChoiceChips(
    labels: List<String>,
    selected: Int,
    enabled: Boolean,
    orientation: Orientation,
    modifier: Modifier,
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
            val shape = RoundedCornerShape(17.dp)
            Box(
                Modifier
                    .height(34.dp)
                    .widthIn(min = 44.dp)
                    .background(if (isSelected) CaptureColors.Accent.copy(alpha = .18f) else Color.White.copy(alpha = .07f), shape)
                    .border(1.dp, if (isSelected) CaptureColors.Accent else Color.White.copy(alpha = .12f), shape)
                    .captureClickable(shape = shape, enabled = enabled) {
                        haptics.selection()
                        onPick(index)
                    }
                    .semantics {
                        contentDescription = label
                        role = Role.RadioButton
                        this.selected = isSelected
                    }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontFamily = X14uFont,
                    fontSize = dpText(13f),
                    maxLines = 1,
                    color = when {
                        !enabled -> Color.White.copy(alpha = .3f)
                        isSelected -> CaptureColors.Accent
                        else -> Color.White.copy(alpha = .85f)
                    },
                    modifier = Modifier.uprightInLandscape(orientation)
                )
            }
        }
    }
}

/**
 * Stock-style ruler: the current value above the needle, faded labels on some ticks, ticks fading to the edges.
 * Drag left for higher values; a fast swipe moves several steps per step of travel. Double tap resets.
 */
@Composable
private fun X14uRuler(editor: X14uEditor.Ruler, orientation: Orientation, modifier: Modifier) {
    val count = editor.count
    if (count <= 0) return
    val haptics = LocalCaptureHaptics.current
    val measurer = rememberTextMeasurer()
    var dragging by remember { mutableStateOf(false) }
    var gestureIndex by remember { mutableStateOf(editor.index.coerceIn(0, count - 1)) }
    var residual by remember { mutableStateOf(0f) }
    val latest by rememberUpdatedState(editor)
    val shown = if (dragging) gestureIndex else editor.index.coerceIn(0, count - 1)
    val labelStyle = TextStyle(fontFamily = X14uFont, fontSize = dpText(10f))
    val enabled = editor.enabled
    Box(modifier.fillMaxHeight()) {
        Text(
            editor.valueText,
            fontFamily = X14uFont,
            fontSize = dpText(14f),
            color = if (enabled) Color.White else Color.White.copy(alpha = .4f),
            maxLines = 1,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp).uprightInLandscape(orientation)
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(onDoubleTap = {
                        haptics.selection()
                        latest.onReset()
                    })
                }
                .pointerInput(enabled, count) {
                    if (!enabled) return@pointerInput
                    var lastTime = 0L
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragging = true
                            gestureIndex = latest.index.coerceIn(0, count - 1)
                            residual = 0f
                            lastTime = 0L
                            latest.onDragStart()
                        },
                        onDragEnd = { dragging = false; residual = 0f },
                        onDragCancel = { dragging = false; residual = 0f }
                    ) { change, delta ->
                        change.consume()
                        val stepPx = latest.spacing.toPx()
                        val dt = if (lastTime == 0L) 16L else (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                        lastTime = change.uptimeMillis
                        // Fast swipes cover more values per pixel; slow drags move one tick at a time.
                        val speed = abs(delta) / dt / density
                        val boost = if (speed > .6f) (1f + (speed - .6f) * 2.5f).coerceAtMost(6f) else 1f
                        residual -= delta * boost
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
                            latest.onIndex(next, previous)
                            if (latest.major(next)) haptics.detent() else haptics.sliderTick()
                        }
                    }
                }
                .semantics { contentDescription = editor.valueText }
        ) {
            val stepPx = editor.spacing.toPx()
            val cx = size.width / 2f
            val labelY = 6.dp.toPx()
            val tickBottom = size.height - 10.dp.toPx()
            val offset = if (dragging) -residual.coerceIn(-stepPx, stepPx) else 0f
            val visible = (size.width / 2f / stepPx).toInt() + 2
            val clearOfValue = 34.dp.toPx()
            for (i in (shown - visible).coerceAtLeast(0)..(shown + visible).coerceAtMost(count - 1)) {
                val x = cx + (i - shown) * stepPx + offset
                if (x < 0f || x > size.width) continue
                val isMajor = editor.major(i)
                val h = if (isMajor) 15.dp.toPx() else 10.dp.toPx()
                val fade = 1f - (abs(x - cx) / (size.width / 2f)).coerceIn(0f, 1f) * .8f
                drawLine(
                    Color.White.copy(alpha = (if (enabled) (if (isMajor) .9f else .55f) else .2f) * fade),
                    Offset(x, tickBottom - h),
                    Offset(x, tickBottom),
                    strokeWidth = if (isMajor) 1.6.dp.toPx() else 1.dp.toPx()
                )
                if (abs(x - cx) > clearOfValue) editor.label(i)?.let { text ->
                    val layout = measurer.measure(text, labelStyle)
                    drawText(
                        layout,
                        color = Color.White.copy(alpha = (if (enabled) .5f else .2f) * fade),
                        topLeft = Offset(x - layout.size.width / 2f, labelY)
                    )
                }
            }
            if (editor.endIcons) {
                val iconY = tickBottom - 26.dp.toPx()
                drawFlower(Offset(10.dp.toPx(), iconY), Color.White.copy(alpha = .6f))
                drawMountain(Offset(size.width - 12.dp.toPx(), iconY), Color.White.copy(alpha = .6f))
            }
            drawLine(
                CaptureColors.Accent,
                Offset(cx, tickBottom - 25.dp.toPx()),
                Offset(cx, tickBottom + 2.dp.toPx()),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlower(center: Offset, color: Color) {
    val r = 2.4.dp.toPx()
    for (k in 0 until 5) {
        val a = Math.toRadians(k * 72.0 - 90.0)
        drawCircle(color, r, Offset(center.x + (r * 1.1f * Math.cos(a)).toFloat(), center.y + (r * 1.1f * Math.sin(a)).toFloat()))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMountain(center: Offset, color: Color) {
    val w = 7.dp.toPx()
    val h = 6.dp.toPx()
    val path = Path().apply {
        moveTo(center.x - w, center.y + h / 2)
        lineTo(center.x - w / 3, center.y - h / 2)
        lineTo(center.x + w / 6, center.y + h / 6)
        lineTo(center.x + w / 2, center.y - h / 4)
        lineTo(center.x + w, center.y + h / 2)
        close()
    }
    drawPath(path, color)
    drawPath(path, color, style = Stroke(1.dp.toPx()))
}

// ---- Idle control area -----------------------------------------------------------------------------------------

/** Idle control area: Photo / Video (tap or swipe, stock size), or the last take's summary. */
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
            Column(
                Modifier
                    .fillMaxSize()
                    .pointerInput(enabled, state.captureMode) {
                        if (!enabled) return@pointerInput
                        detectHorizontalDragGestures(
                            onDragStart = { travel = 0f },
                            onDragEnd = {
                                val threshold = 40.dp.toPx()
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
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(Modifier.size(20.dp, 3.dp).background(Color.White.copy(alpha = .3f), RoundedCornerShape(2.dp)))
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    CaptureMode.entries.forEach { mode ->
                        val isSelected = state.captureMode == mode
                        Text(
                            if (mode == CaptureMode.Photo) "Photo" else "Video",
                            fontFamily = X14uFont,
                            fontSize = dpText(15f),
                            color = when {
                                isSelected -> if (enabled) CaptureColors.Accent else CaptureColors.Accent.copy(alpha = .4f)
                                else -> Color.White.copy(alpha = if (enabled) .85f else .25f)
                            },
                            modifier = Modifier
                                .captureClickable(shape = CaptureTextRippleShape, enabled = enabled) {
                                    if (!isSelected) {
                                        haptics.selection()
                                        dispatch(SetCaptureMode(mode))
                                    }
                                }
                                .semantics {
                                    role = Role.Tab
                                    this.selected = isSelected
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }
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
        fontFamily = X14uFont,
        fontSize = dpText(12f),
        color = if ((timing?.dropped ?: 0) > 0) CaptureColors.Danger else Color.White.copy(alpha = .9f),
        maxLines = 2,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun X14uTakeSummary(take: VideoTakeSummary, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxSize().captureClickable(shape = RoundedCornerShape(8.dp)) { onDismiss() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val t = take.timing
        Text(
            if (take.hadProblems) "Last take: ${t.dropped} dropped" + (t.dropReason?.let { " ($it)" } ?: "") else "Last take: OK",
            fontFamily = X14uFont,
            fontWeight = FontWeight.Medium,
            fontSize = dpText(13f),
            color = if (take.hadProblems) CaptureColors.Danger else CaptureColors.Success
        )
        Text(
            "%d:%02d · %.1f of %d fps · GPU peak %.0f ms · enc %.0f ms".format(
                take.seconds / 60, take.seconds % 60, t.actualFps, take.targetFps, t.gpuPeakMs, t.encoderPeakMs
            ),
            fontFamily = X14uFont,
            fontSize = dpText(11f),
            color = Color.White.copy(alpha = .75f),
            maxLines = 1
        )
    }
}

@Composable
private fun X14uRecordTimer(video: VideoControlState, orientation: Orientation, onTap: () -> Unit, modifier: Modifier) {
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
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .uprightInLandscape(orientation),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Text(video.status, color = Color.White, fontFamily = X14uFont, fontWeight = FontWeight.Medium, fontSize = dpText(13f),
            maxLines = 1, style = ViewfinderTextStyle)
        if (dropped > 0) {
            Text("· $dropped dropped", color = CaptureColors.Danger, fontFamily = X14uFont, fontWeight = FontWeight.Medium,
                fontSize = dpText(12f), maxLines = 1, style = ViewfinderTextStyle)
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
    enabled: Boolean,
    modifier: Modifier
) {
    val isVideo = state.captureMode == CaptureMode.Video
    val extras = LocalCaptureExtras.current
    val orientation = state.orientation
    val turn = Modifier.uprightInLandscape(orientation)
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
    Row(modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(state = state, imageUri = latestImageUri, onClick = onOpenLatestImage, onLongClick = onOpenRenderer,
                modifier = turn)
            X14uRoundButton(
                X14uIcons.CameraSwitch,
                if (onFront) "Switch to the back camera" else "Switch to the front camera",
                orientation,
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
                orientation = orientation,
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
                        minWidth = SidePillWidth,
                        modifier = turn
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
                        minWidth = SidePillWidth,
                        modifier = turn
                    )
                    DcpSidePill(dcp, { if (enabled) onSelectDcp(it) }, modifier = turn, onOpenLensSettings = { onOpenLensSettings(state.selectedLensId) })
                }
            } else {
                PhotoLookToggles(
                    state, dispatch, onOpenFilmSimSettings, onOpenMultiframeSettings,
                    trailing = { DcpSidePill(dcp, onSelectDcp, modifier = turn, onOpenLensSettings = { onOpenLensSettings(state.selectedLensId) }) },
                    pillModifier = turn
                )
            }
        }
    }
}
