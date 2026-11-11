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
            GridModeSettingsRow(state.values.gridMode) { dispatch.invoke(SetGridMode(it)) }
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

/** Grid families in menu order; a family with variants lists once and gets an Orientation row. */
private val gridFamilies: List<Pair<String, List<GridMode>>> = listOf(
    "Off" to listOf(GridMode.Off),
    "Rule of thirds" to listOf(GridMode.Thirds),
    "Thirds intersections" to listOf(GridMode.ThirdsPoints),
    "Golden ratio (phi grid)" to listOf(GridMode.GoldenRatio),
    "Golden spiral" to listOf(GridMode.SpiralBottomLeft, GridMode.SpiralBottomRight, GridMode.SpiralTopLeft, GridMode.SpiralTopRight),
    "Golden triangles" to listOf(GridMode.GoldenTriangles, GridMode.GoldenTrianglesMirrored),
    "Diagonals (45°)" to listOf(GridMode.Diagonals),
    "4 × 4" to listOf(GridMode.FourByFour),
    "Crosshair" to listOf(GridMode.Cross)
)

private fun gridVariantLabel(mode: GridMode): String = when (mode) {
    GridMode.SpiralBottomLeft -> "Eye bottom left"
    GridMode.SpiralBottomRight -> "Eye bottom right"
    GridMode.SpiralTopLeft -> "Eye top left"
    GridMode.SpiralTopRight -> "Eye top right"
    GridMode.GoldenTriangles -> "Diagonal top left to bottom right"
    GridMode.GoldenTrianglesMirrored -> "Diagonal top right to bottom left"
    else -> ""
}

@Composable
private fun GridModeSettingsRow(selected: GridMode, onSelect: (GridMode) -> Unit) {
    val expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val family = gridFamilies.firstOrNull { selected in it.second } ?: gridFamilies[1]
    Box {
        SettingsRow("Grid", family.first, onClick = { expanded.value = true })
        DropdownMenu(expanded = expanded.value, onDismissRequest = { expanded.value = false }) {
            gridFamilies.forEach { (label, modes) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        if (selected !in modes) onSelect(modes.first())
                        expanded.value = false
                    }
                )
            }
        }
    }
    if (family.second.size > 1) {
        SettingDivider()
        // Tap cycles the spiral's eye corner / the triangles' diagonal.
        SettingsRow("Orientation", gridVariantLabel(selected), onClick = {
            val modes = family.second
            onSelect(modes[(modes.indexOf(selected) + 1) % modes.size])
        })
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
