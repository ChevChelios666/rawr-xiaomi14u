package com.rawr.camera.settings.ui

import androidx.compose.foundation.layout.*
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
            SettingDivider()
            SettingsRow(
                "Control Style",
                "Capture control surface appearance",
                testTag = SettingsTestTags.row("DisplayControls", "control_style"),
                onClick = {
                    dispatch.invoke(OpenSection(SettingsSection.ControlStyle))
                }
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
    }
}

@Composable
internal fun ControlStyleSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val haptics = LocalSettingsHaptics.current
    SettingsPageContainer {
        SettingsGroup(
            description = "Changes only the visual material used by Capture Screen controls. Layout and interaction stay identical."
        ) {
            SettingsSelectionRow(
                title = "Frosted",
                selected = state.values.controlSurfaceStyle == ControlSurfaceStyle.Frosted,
                onClick = {
                    haptics.detent()
                    dispatch.invoke(SetControlSurfaceStyle(ControlSurfaceStyle.Frosted))
                }
            )
            SettingDivider()
            SettingsSelectionRow(
                title = "Basic",
                selected = state.values.controlSurfaceStyle == ControlSurfaceStyle.Basic,
                onClick = {
                    haptics.detent()
                    dispatch.invoke(SetControlSurfaceStyle(ControlSurfaceStyle.Basic))
                }
            )
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
