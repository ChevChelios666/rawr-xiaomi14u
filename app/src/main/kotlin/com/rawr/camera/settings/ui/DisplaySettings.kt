package com.rawr.camera.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.rawr.camera.model.CaptureControlLayout
import com.rawr.camera.model.ControlSurfaceStyle
import com.rawr.camera.model.GridMode
import com.rawr.camera.settings.architecture.*
import com.rawr.camera.settings.model.*

@Composable
internal fun DisplayControlsSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("DisplayControls")) {
        SettingsGroup {
            // X14U: the grid lives in the capture screen's tools panel.
            SettingsSwitchRow(
                title = "DCP Quick Select",
                checked = state.values.showDcpQuickSelect,
                supportingText = "Show the DCP profile shortcut beside the lens selector on the capture screen.",
                testTag = SettingsTestTags.row("DisplayControls", "dcp_quick_select")
            ) { dispatch.invoke(SetShowDcpQuickSelect(it)) }
            SettingDivider()
            ViewfinderResolutionRow(state.values.viewfinderDivisor) { dispatch.invoke(SetViewfinderDivisor(it)) }
            SettingDivider()
            SettingsRow(
                "Monitoring",
                "Focus peaking behavior",
                testTag = SettingsTestTags.row("DisplayControls", "monitoring"),
                onClick = {
                    dispatch.invoke(OpenSection(SettingsSection.Monitoring))
                }
            )
            // X14U (Nov 18): the style of the capture screen: Classic (camera body) or Minimal (as before).
            SettingDivider()
            SettingsRow(
                "Control Style",
                when (state.values.controlSurfaceStyle) {
                    ControlSurfaceStyle.Classic -> "Classic"
                    ControlSurfaceStyle.Bronze -> "Bronze"
                    else -> "Minimal"
                },
                testTag = SettingsTestTags.row("DisplayControls", "control_style"),
                onClick = {
                    dispatch.invoke(OpenSection(SettingsSection.ControlStyle))
                }
            )
        }
        SettingsGroup(
            title = "Xiaomi 14 Ultra grip",
            description = "Reads the 14 Ultra grip over USB (asks permission) so the dial and record button work too. Off: only the shutter and zoom lever."
        ) {
            SettingsSwitchRow(
                title = "Read the grip over USB",
                checked = state.values.gripUsb,
                onCheckedChange = { dispatch.invoke(com.rawr.camera.settings.architecture.SetGripUsb(it)) }
            )
            SettingDivider()
            DropdownChoiceRow(
                title = "Dial",
                selected = state.values.gripDial,
                values = com.rawr.camera.settings.model.GripDial.entries.toList(),
                label = { it.label },
                onSelect = { dispatch.invoke(com.rawr.camera.settings.architecture.SetGripDial(it)) }
            )
            SettingDivider()
            SettingsSwitchRow(
                title = "Reverse the dial",
                checked = state.values.gripDialReverse,
                onCheckedChange = { dispatch.invoke(com.rawr.camera.settings.architecture.SetGripDialReverse(it)) }
            )
        }
    }
}

@Composable
internal fun MonitoringSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    SettingsPageContainer(testTag = SettingsTestTags.sectionRoot("Monitoring")) {
        SettingsGroup(description = "Monitoring overlays are activated from the Capture Screen.") {
            SelectorDropdownRow(
                state,
                ChoiceSelectorKind.PeakingSensitivity,
                dispatch,
                testTag = SettingsTestTags.row("Monitoring", "peaking_sensitivity")
            )
        }
        val loupe = state.values.focusLoupe
        val set: (FocusLoupeSettings) -> Unit = { dispatch.invoke(SetFocusLoupe(it)) }
        SettingsGroup(
            title = "Focus loupe",
            description = "A magnified window over the focus point, for checking focus. It never changes the photo."
        ) {
            DropdownChoiceRow(
                title = "Loupe zoom",
                selected = loupe.zoom.toString(),
                values = FocusLoupeSettings.ZOOMS.map { it.toString() },
                label = { "${it}×" },
                onSelect = { v -> set(loupe.copy(zoom = v.toInt())) }
            )
            SettingDivider()
            SettingsSwitchRow(
                title = "Open while focusing manually",
                checked = loupe.onManualFocus,
                supportingText = "Shows while you drag the MF ruler"
            ) { set(loupe.copy(onManualFocus = it)) }
            SettingDivider()
            SettingsSwitchRow(
                title = "Double-tap the viewfinder",
                checked = loupe.doubleTap,
                supportingText = "Opens or closes the loupe. Tap-to-focus then waits a moment for a second tap."
            ) { set(loupe.copy(doubleTap = it)) }
            SettingDivider()
            SettingsSwitchRow(
                title = "Focus meter",
                checked = loupe.meter,
                supportingText = "Sharpness bar with a peak-hold mark: sweep through focus, return to the mark"
            ) { set(loupe.copy(meter = it)) }
        }
        // X14U (Nov 17): what each overlay shows; tap ⓘ. They're turned on from the swipe-down panel.
        SettingsGroup(title = "Overlays", description = "Turned on in the swipe-down panel. Viewfinder only; photos are unchanged.") {
            SettingsRow(
                title = "Focus peaking",
                supportingText = "Edges in focus glow while you focus."
            )
            SettingDivider()
            SettingsRow(
                title = "RAW highlights",
                supportingText = "Marks where the sensor clips; lost even in the DNG. Lower EV until only lamps or the sun are marked."
            )
            SettingDivider()
            SettingsRow(
                title = "Tonemap shadows",
                supportingText = "Marks shadows the look crushes to black. The RAW still has them: lift Shadows to keep them."
            )
            SettingDivider()
            SettingsRow(
                title = "False colour",
                supportingText = "Colours the viewfinder by brightness of the final photo (not the RAW). Faces in green or yellow; red means clipped.",
                infoExtra = { FalseColourLegend() }
            )
        }
    }
}

/** The false colour zones (native/monitoring_overlays defaults: IRE bounds and colours). */
@Composable
private fun FalseColourLegend() {
    val zones = listOf(
        Triple(androidx.compose.ui.graphics.Color(0xFF2A1440), "0–3", "crushed black"),
        Triple(androidx.compose.ui.graphics.Color(0xFF1D3AA6), "3–10", "deep shadow"),
        Triple(androidx.compose.ui.graphics.Color(0xFF1FA0C8), "10–40", "shadows"),
        Triple(androidx.compose.ui.graphics.Color(0xFF2FB34D), "40–60", "mid-tones, grey card"),
        Triple(androidx.compose.ui.graphics.Color(0xFFF2C81A), "60–80", "bright: skin, sky"),
        Triple(androidx.compose.ui.graphics.Color(0xFFFF6A0D), "80–95", "near white"),
        Triple(androidx.compose.ui.graphics.Color(0xFFFF1A1A), "95–100", "clipped")
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        zones.forEach { (color, range, meaning) ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.size(22.dp, 12.dp).background(color, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(10.dp))
                Text(range, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, modifier = Modifier.width(56.dp))
                Text(meaning)
            }
        }
    }
}

@Composable
internal fun ControlStyleSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val haptics = LocalSettingsHaptics.current
    SettingsPageContainer {
        SettingsGroup(
            description = "How the capture screen looks. Controls work the same in both."
        ) {
            // X14U: Frosted only skinned upstream's previous capture screen; it stays in code, not offered.
            SettingsSelectionRow(
                title = "Classic",
                supportingText = "A camera body: brushed metal, leather, metal dials.",
                selected = state.values.controlSurfaceStyle == ControlSurfaceStyle.Classic,
                onClick = {
                    haptics.detent()
                    dispatch.invoke(SetControlSurfaceStyle(ControlSurfaceStyle.Classic))
                }
            )
            SettingDivider()
            SettingsSelectionRow(
                title = "Bronze",
                supportingText = "The camera body in bronze and brown leather.",
                selected = state.values.controlSurfaceStyle == ControlSurfaceStyle.Bronze,
                onClick = {
                    haptics.detent()
                    dispatch.invoke(SetControlSurfaceStyle(ControlSurfaceStyle.Bronze))
                }
            )
            SettingDivider()
            SettingsSelectionRow(
                title = "Minimal",
                supportingText = "Simply black.",
                selected = state.values.controlSurfaceStyle != ControlSurfaceStyle.Classic &&
                    state.values.controlSurfaceStyle != ControlSurfaceStyle.Bronze,
                onClick = {
                    haptics.detent()
                    dispatch.invoke(SetControlSurfaceStyle(ControlSurfaceStyle.Basic))
                }
            )
        }
        // X14U (Nov 18): the accent colour, any style; applies at once.
        val context = androidx.compose.ui.platform.LocalContext.current
        remember(context) { AccentSettings.init(context) }
        SettingsGroup(
            title = "Accent",
            description = "Selected values, the current mode and buttons that are on."
        ) {
            AccentSwatches(AccentSettings.choice) {
                haptics.detent()
                AccentSettings.select(it)
            }
        }
    }
}

/** The accent choices as colour dots (style default: a split dot), the chosen one ringed, its name under it. */
@Composable
private fun AccentSwatches(selected: AccentSettings.Choice, onSelect: (AccentSettings.Choice) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AccentSettings.Choice.entries.forEach { c ->
            val on = c == selected
            Column(
                Modifier.width(64.dp).clickable { onSelect(c) }.padding(vertical = 4.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(36.dp)
                        .border(if (on) 2.dp else 1.dp, if (on) androidx.compose.ui.graphics.Color.White
                            else androidx.compose.ui.graphics.Color.White.copy(alpha = .2f), androidx.compose.foundation.shape.CircleShape)
                        .padding(4.dp)
                        .background(
                            c.argb?.let { androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color(it)) }
                                ?: androidx.compose.ui.graphics.Brush.sweepGradient(
                                    listOf(androidx.compose.ui.graphics.Color(0xFFE06A61), androidx.compose.ui.graphics.Color(0xFFE8763E),
                                        androidx.compose.ui.graphics.Color(0xFFFFC846), androidx.compose.ui.graphics.Color(0xFFE06A61))
                                ),
                            androidx.compose.foundation.shape.CircleShape
                        )
                )
                Text(
                    c.label,
                    fontSize = 11.sp,
                    maxLines = 2,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ViewfinderResolutionRow(divisor: Int, onSelect: (Int) -> Unit) {
    val expanded = remember { mutableStateOf(false) }
    // Divisor of the preview size the viewfinder look renders at. Film sim
    // never renders above High, so Full and High look the same there.
    val labels = mapOf(1 to "Full", 2 to "High", 3 to "Balanced", 4 to "Fast")
    Box {
        SettingsRow(
            "Viewfinder Resolution",
            labels.getValue(divisor.coerceIn(1, 4)),
            supportingText = "Lower resolution reduces GPU load and heat. Photos and video are not affected.",
            testTag = SettingsTestTags.row("DisplayControls", "viewfinder_resolution"),
            onClick = { expanded.value = true }
        )
        DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
            labels.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded.value = false
                    }
                )
            }
        }
    }
}
