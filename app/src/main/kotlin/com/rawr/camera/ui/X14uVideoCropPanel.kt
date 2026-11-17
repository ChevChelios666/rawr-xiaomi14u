package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.Close
import com.rawr.camera.ui.icons.outlined.Crop
import com.rawr.camera.video.VideoCrop
import com.rawr.camera.video.VideoCropSize

/**
 * X14U video crop panel (top bar crop chip): a standard size (4K, 1080p, Open gate), a shape for 4K and 1080p, and
 * the recording size it gives.
 */
@Composable
internal fun X14uVideoCropPanel(
    crop: VideoCrop,
    onChange: (VideoCrop) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** Landscape: the panel is turned, so it is wide and low (one row of shapes). */
    wide: Boolean = false
) {
    val accent = CaptureColors.Accent
    val (_, output) = crop.sizes()
    Column(
        modifier
            .background(Color(0xF2282421), RoundedCornerShape(14.dp))
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(14.dp)
            .testTag("x14u_video_crop_panel"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Crop, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("VIDEO CROP", color = Color.White, fontFamily = X14uFont, fontSize = dpText(14f),
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(
                Modifier.size(36.dp).clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White.copy(alpha = .8f),
                    modifier = Modifier.size(20.dp))
            }
        }
        Text("Size", color = Color.White.copy(alpha = .6f), fontFamily = X14uFont, fontSize = dpText(12f))
        CropSegments(
            labels = VideoCropSize.entries.map { it.label },
            selected = crop.size.ordinal,
            accent = accent
        ) { onChange(crop.copy(size = VideoCropSize.entries[it])) }
        if (crop.isOpenGate) {
            Text(
                "The whole sensor (${VideoCrop.lastRawWidth} × ${VideoCrop.lastRawHeight}), for reframing later. " +
                    "The heaviest size, 30 fps only.",
                color = Color.White.copy(alpha = .6f), fontFamily = X14uFont, fontSize = dpText(12f),
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1C1A17), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        } else {
            Text("Shape", color = Color.White.copy(alpha = .6f), fontFamily = X14uFont, fontSize = dpText(12f))
            CropTiles(
                count = VideoCrop.SHAPES.size,
                columns = if (wide) VideoCrop.SHAPES.size else 2,
                selected = crop.shape,
                accent = accent,
                title = { VideoCrop.SHAPES[it].label },
                subtitle = { VideoCrop.SHAPES[it].name }
            ) { onChange(crop.copy(shape = it)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .1f)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Recording size · " + (if (crop.factor == 2f) "binned · " else "") +
                    (if (crop.allowsSixty()) "30 / 60 fps" else "30 fps"),
                color = Color.White.copy(alpha = .6f), fontFamily = X14uFont, fontSize = dpText(12f),
                modifier = Modifier.weight(1f)
            )
            Text("${output.width} × ${output.height}", color = accent, fontFamily = CaptureMono,
                fontSize = dpText(17f), fontWeight = FontWeight.Medium)
        }
    }
}

// The shared panel controls (X14uPanelKit): every panel looks the same.
@Suppress("UNUSED_PARAMETER")
@Composable
private fun CropSegments(labels: List<String>, selected: Int, accent: Color, onSelect: (Int) -> Unit) =
    X14uSegments(labels, selected, onSelect)

@Suppress("UNUSED_PARAMETER")
@Composable
private fun CropTiles(
    count: Int,
    columns: Int,
    selected: Int,
    accent: Color,
    title: (Int) -> String,
    subtitle: (Int) -> String?,
    onSelect: (Int) -> Unit
) = X14uTiles(count, columns, { it == selected }, title, subtitle, onSelect)
