package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rawr.camera.integration.ButtonTest
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.Close

/** X14U button test (Tools → Button test): the latest key and dial events, newest first. */
@Composable
internal fun X14uButtonTestOverlay(modifier: Modifier = Modifier) {
    val lines by ButtonTest.lines.collectAsState()
    val usb by com.rawr.camera.integration.GripUsbReader.running.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier
            .background(Color(0xF2282421), RoundedCornerShape(14.dp))
            .padding(14.dp)
            .testTag("x14u_button_test"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BUTTON TEST", color = Color.White, fontFamily = X14uFont, fontSize = dpText(14f),
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Box(Modifier.size(36.dp).clickable { ButtonTest.setEnabled(false) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White.copy(alpha = .8f),
                    modifier = Modifier.size(20.dp))
            }
        }
        Text(
            "Press, half-press, hold, turn and slide each grip control once, then send a screenshot. " +
                "While this is open the buttons only show here.",
            color = Color.White.copy(alpha = .6f), fontFamily = X14uFont, fontSize = dpText(12f)
        )
        // Raw USB: the dial's direction isn't in its key events, so read the grip's own reports.
        Box(
            Modifier.background(Color(0xFF2F2A24), RoundedCornerShape(50))
                .clickable {
                    if (usb) com.rawr.camera.integration.GripUsbReader.stop()
                    else com.rawr.camera.integration.GripUsbReader.start(context)
                }
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("x14u_button_test_usb")
        ) {
            Text(if (usb) "Stop reading USB" else "Read the grip's USB data", color = CaptureColors.Accent,
                fontFamily = X14uFont, fontSize = dpText(13f))
        }
        if (lines.isEmpty()) {
            Text("Waiting for a button…", color = CaptureColors.Accent, fontFamily = X14uFont, fontSize = dpText(13f))
        }
        lines.forEachIndexed { i, line ->
            Text(line, color = if (i == 0) CaptureColors.Accent else Color.White.copy(alpha = .85f),
                fontFamily = CaptureMono, fontSize = dpText(11f))
        }
    }
}
