package com.rawr.camera.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.architecture.ToggleScopeExpanded
import com.rawr.camera.model.*

@Composable
internal fun ScopeLayer(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    preview: CapturePreview,
    /** X14U: stock-sized wide boxes without a title, [topInset] from the top of the viewfinder. */
    x14u: Boolean = false,
    topInset: androidx.compose.ui.unit.Dp = 16.dp,
    /** X14U: an 80 x 29 dp box (the sound meter) on the compact histogram's top edge as the phone is held. */
    header: (@Composable () -> Unit)? = null
) {
    val haptics = LocalCaptureHaptics.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val landscape = state.orientation == Orientation.Landscape
        // Landscape turns the boxes a quarter clockwise (or anticlockwise at 270°): their top edge faces +x (or -x).
        val clockwise = preview.deviceRotationDegrees != 270
        // Stock proportions: 80 x 29 dp boxes, 8 dp apart.
        val headerSpace = if (x14u && header != null) 37.dp else 0.dp
        fun geometry(index: Int, expanded: Boolean, count: Int = state.activeScopes.size): ScopeGeometry {
            val g = calculateScopeGeometry(
                maxWidth, maxHeight, landscape,
                count, index, expanded, preview.deviceRotationDegrees,
                compactWidth = if (x14u) 80.dp else CaptureDimens.ScopeCompactWidth,
                compactHeight = if (x14u) 29.dp else CaptureDimens.ScopeCompactHeight,
                topInset = if (x14u) topInset + (if (landscape) 0.dp else headerSpace) else 16.dp
            )
            // Room for the header on the side the boxes' top faces (landscape, clockwise: the right).
            return if (!expanded && landscape && clockwise && headerSpace > 0.dp && g.x > maxWidth / 2) g.copy(x = g.x - headerSpace) else g
        }
        if (header != null) {
            val g = geometry(0, false, state.activeScopes.size.coerceAtLeast(1))
            val (cx, cy) = when {
                !landscape -> (g.x + g.physicalWidth / 2) to (g.y - 8.dp - 14.5.dp)
                clockwise -> (g.x + g.physicalWidth + 8.dp + 14.5.dp) to (g.y + g.physicalHeight / 2)
                else -> (g.x - 8.dp - 14.5.dp) to (g.y + g.physicalHeight / 2)
            }
            Box(
                Modifier.offset(x = cx - 40.dp, y = cy - 14.5.dp).size(80.dp, 29.dp)
                    .graphicsLayer { rotationZ = if (!landscape) 0f else if (clockwise) 90f else 270f },
                contentAlignment = Alignment.Center
            ) { header() }
        }
        val geometries = state.activeScopes.mapIndexed { index, scope -> geometry(index, state.expandedScope == scope) }
        val presentation = state.activeScopes.take(3).mapIndexed { index, scope ->
            val g = geometries[index]
            ScopePresentation(
                type = scope,
                mode = state.waveformMode,
                quarterTurns = g.presentationQuarterTurns,
                x = g.x.value / maxWidth.value,
                y = g.y.value / maxHeight.value,
                width = g.physicalWidth.value / maxWidth.value,
                height = g.physicalHeight.value / maxHeight.value,
                cornerFraction = ((if (x14u) 4.dp else 9.dp).value / g.physicalHeight.value).coerceIn(0f, .5f)
            )
        }
        val onPresentation = androidx.compose.runtime.rememberUpdatedState(preview.onScopePresentation)
        androidx.compose.runtime.LaunchedEffect(presentation) {
            onPresentation.value(presentation)
        }
        state.activeScopes.forEachIndexed { index, scope ->
            val expanded = state.expandedScope == scope
            val g = geometries[index]
            ScopeCard(
                type = scope,
                expanded = expanded,
                nativeContent = preview.nativeContent,
                variantLabel =
                    when (scope) {
                        ScopeType.Waveform -> when (state.waveformMode) {
                            WaveformMode.RgbOverlay -> "RGB"
                            WaveformMode.Histogram -> null
                            WaveformMode.HistogramRgb -> "RGB"
                            WaveformMode.Luma -> "LUMA"
                        }
                        ScopeType.Vectorscope -> null
                    },
                title = if (scope == ScopeType.Waveform &&
                    (state.waveformMode == WaveformMode.Histogram || state.waveformMode == WaveformMode.HistogramRgb)) "HISTOGRAM" else null,
                showTitle = !x14u || expanded,
                x14uCard = x14u,
                orientation = state.orientation,
                logicalWidth = g.logicalWidth,
                logicalHeight = g.logicalHeight,
                modifier =
                    Modifier
                        .offset(x = g.x, y = g.y)
                        .size(g.physicalWidth, g.physicalHeight)
            ) {
                haptics.selection()
                dispatch(ToggleScopeExpanded(scope))
            }
        }
    }
}

@Composable
private fun ScopeCard(
    type: ScopeType,
    expanded: Boolean,
    nativeContent: Boolean,
    variantLabel: String?,
    /** Heading instead of the scope type's name (the luma histogram is drawn from the waveform measurement). */
    title: String? = null,
    showTitle: Boolean = true,
    /** Stock-style card: small corners, a light outline. */
    x14uCard: Boolean = false,
    orientation: Orientation,
    logicalWidth: androidx.compose.ui.unit.Dp,
    logicalHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val scopeShape = RoundedCornerShape(if (x14uCard) 4.dp else 9.dp)
    Surface(
        onClick = onClick,
        modifier =
            modifier
                .testTag(CaptureTestTags.scopeCard(type.name))
                .then(
                    if (nativeContent) {
                        Modifier
                    } else {
                        Modifier.captureControlSurface(
                            shape = scopeShape,
                            textureStrength = .45f
                        )
                    }
                ).semantics {
                    contentDescription =
                        "${type.name} scope, ${if (expanded) "expanded" else "compact"}. Tap to ${if (expanded) "collapse" else "expand"}."
                },
        shape = scopeShape,
        color = Color.Transparent,
        border = if (nativeContent) BorderStroke(1.dp, Color.White.copy(alpha = if (x14uCard) .35f else .10f)) else null
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .requiredSize(logicalWidth, logicalHeight)
                    .uprightInLandscape(orientation)
                    .padding(if (expanded) 10.dp else if (showTitle) 7.dp else 3.dp)
            ) {
                if (showTitle) Text(
                    (title ?: type.name.uppercase()).let { if (variantLabel == null) it else "$it · $variantLabel" },
                    color = Color.White.copy(alpha = .62f),
                    fontFamily = CaptureMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (expanded) 9.sp else 8.sp,
                    letterSpacing = 1.sp
                )
                if (!nativeContent) {
                    when (type) {
                        ScopeType.Waveform -> Waveform(expanded, Modifier.fillMaxWidth().weight(1f))
                        ScopeType.Vectorscope -> Vectorscope(expanded, Modifier.fillMaxWidth().weight(1f))
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Waveform(expanded: Boolean, modifier: Modifier) {
    Canvas(modifier.padding(top = if (expanded) 12.dp else 5.dp)) {
        drawLine(Color.White.copy(alpha = .15f), Offset(0f, size.height), Offset(size.width, size.height))
        val path =
            Path().apply {
                moveTo(0f, size.height * .70f)
                lineTo(size.width * .2f, size.height * .58f)
                lineTo(size.width * .38f, size.height * .42f)
                lineTo(size.width * .56f, size.height * .55f)
                lineTo(size.width * .76f, size.height * .25f)
                lineTo(size.width, size.height * .38f)
            }
        drawPath(
            path,
            Color.White.copy(alpha = .72f),
            style = Stroke(if (expanded) 2.5f else 1.5f, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun Vectorscope(expanded: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension * if (expanded) .42f else .36f
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(Color.White.copy(alpha = .25f), radius, center, style = Stroke(1f))
        drawLine(
            Color.White.copy(alpha = .12f),
            Offset(center.x - radius, center.y),
            Offset(center.x + radius, center.y)
        )
        drawLine(
            Color.White.copy(alpha = .12f),
            Offset(center.x, center.y - radius),
            Offset(center.x, center.y + radius)
        )
        drawCircle(
            Color.White.copy(alpha = .68f),
            if (expanded) 6.dp.toPx() else 3.5.dp.toPx(),
            Offset(center.x + radius * .32f, center.y - radius * .28f)
        )
    }
}
