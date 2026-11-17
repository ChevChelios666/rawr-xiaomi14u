package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.Close

/**
 * X14U panel controls, one style for every panel over the viewfinder (video crop, presets, timed burst): a dark
 * rounded panel with a title and a close button, segmented choices (selected: accent outline) and tiles (on: accent
 * outline).
 */
internal object X14uPanelStyle {
    val Panel = Color(0xF2282421)
    val Track = Color(0xFF1C1A17)
    val Selected = Color(0xFF2F2A24)
}

@Composable
internal fun X14uSheet(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** Shown before the title (a preset's number). */
    lead: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .background(X14uPanelStyle.Panel, RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            if (lead != null) {
                Text(lead, color = CaptureColors.Accent, fontFamily = CaptureMono, fontSize = dpText(14f),
                    fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
            }
            Text(title, color = Color.White, fontFamily = X14uFont, fontSize = dpText(14f), fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(Modifier.size(36.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White.copy(alpha = .8f),
                    modifier = Modifier.size(20.dp))
            }
        }
        content()
    }
}

@Composable
internal fun X14uSheetLabel(text: String) {
    Text(text, color = Color.White.copy(alpha = .6f), fontFamily = X14uFont, fontSize = dpText(12f))
}

@Composable
internal fun X14uSheetDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .1f)))
}

/** Segmented choices; [selected] null = none highlighted (a row of buttons). */
@Composable
internal fun X14uSegments(
    labels: List<String>,
    selected: Int?,
    onSelect: (Int) -> Unit,
    enabled: (Int) -> Boolean = { true },
    /** Accent text without the outline (a highlighted button such as Save). */
    emphasis: Int? = null
) {
    val accent = CaptureColors.Accent
    Row(
        Modifier.fillMaxWidth().background(X14uPanelStyle.Track, RoundedCornerShape(50)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val usable = enabled(i)
            Box(
                Modifier.weight(1f).height(34.dp)
                    .then(if (on) Modifier.background(X14uPanelStyle.Selected, RoundedCornerShape(50))
                        .border(1.dp, accent, RoundedCornerShape(50)) else Modifier)
                    .clickable(enabled = usable) { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = when {
                        !usable -> Color.White.copy(alpha = .3f)
                        on || i == emphasis -> accent
                        else -> Color.White.copy(alpha = .75f)
                    },
                    fontFamily = X14uFont, fontSize = dpText(13f), maxLines = 1
                )
            }
        }
    }
}

/** Tiles in [columns]; each on (accent outline) or off, with an optional second line. */
@Composable
internal fun X14uTiles(
    count: Int,
    columns: Int,
    isOn: (Int) -> Boolean,
    title: (Int) -> String,
    subtitle: (Int) -> String?,
    onTap: ((Int) -> Unit)?,
    dim: (Int) -> Boolean = { false }
) {
    val accent = CaptureColors.Accent
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (0 until count).chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { i ->
                    val on = isOn(i)
                    val faint = dim(i)
                    Column(
                        Modifier.weight(1f)
                            .background(X14uPanelStyle.Track, RoundedCornerShape(12.dp))
                            .border(1.dp, if (on) accent else Color.White.copy(alpha = .12f), RoundedCornerShape(12.dp))
                            .then(if (onTap != null) Modifier.clickable { onTap(i) } else Modifier)
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(title(i), color = if (on) accent else Color.White.copy(alpha = if (faint) .35f else 1f),
                            fontFamily = X14uFont, fontSize = dpText(13f), textAlign = TextAlign.Center, maxLines = 1)
                        subtitle(i)?.let {
                            Text(it, color = Color.White.copy(alpha = if (faint) .25f else .5f), fontFamily = X14uFont,
                                fontSize = dpText(11f), textAlign = TextAlign.Center, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
