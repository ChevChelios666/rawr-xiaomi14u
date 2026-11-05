package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.model.DcpQuickState
import com.rawr.camera.model.LensOptionsQuickState
import com.rawr.camera.model.Orientation

/** Test tags of the lens option chips (X14U build). */
internal object LensOptionTags {
    fun chip(row: Int) = "capture_lens_option_$row"
    fun choice(row: Int, choice: Int) = "capture_lens_option_${row}_$choice"
}

internal val CaptureChipHeight = 32.dp
private val ChipShape = RoundedCornerShape(16.dp)
private val ChoiceShape = RoundedCornerShape(18.dp)

/** A pill on the second lens row: its value and a chevron; tap opens its popup. */
@Composable
private fun CaptureChip(
    label: String,
    orientation: Orientation,
    enabled: Boolean,
    open: Boolean,
    accent: Boolean,
    testTag: String,
    description: String,
    onClick: () -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    val textColor = when {
        !enabled -> Color.White.copy(alpha = .28f)
        accent -> CaptureColors.Accent
        else -> Color.White.copy(alpha = .72f)
    }
    Box(
        Modifier
            .height(CaptureChipHeight)
            .widthIn(min = 56.dp, max = 140.dp)
            .clip(ChipShape)
            .background(if (open) Color.White.copy(alpha = .10f) else Color.Transparent)
            .border(1.dp, if (open) CaptureColors.Accent else Color.White.copy(alpha = if (enabled) .22f else .10f), ChipShape)
            .testTag(testTag)
            .then(
                if (enabled) Modifier.captureClickable(shape = ChipShape, role = Role.DropdownList) { haptics.selection(); onClick() }
                else Modifier
            )
            .semantics {
                contentDescription = description
                if (!enabled) disabled()
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (enabled) "$label ▾" else label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            fontFamily = CaptureMono,
            fontWeight = FontWeight.Bold,
            color = textColor,
            modifier = Modifier.uprightInLandscape(orientation)
        )
    }
}

/** The choices of one popup as big buttons, two per line. */
@Composable
private fun ChoiceGrid(
    title: String,
    labels: List<String>,
    selected: Int,
    tag: (Int) -> String,
    onPick: (Int) -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            fontFamily = CaptureMono,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f),
            modifier = Modifier.padding(start = 2.dp)
        )
        labels.indices.chunked(2).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                line.forEach { index ->
                    val isSelected = index == selected
                    Box(
                        Modifier
                            .width(112.dp)
                            .height(36.dp)
                            .clip(ChoiceShape)
                            .background(
                                if (isSelected) CaptureColors.Accent.copy(alpha = .18f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
                            )
                            .then(if (isSelected) Modifier.border(1.dp, CaptureColors.Accent, ChoiceShape) else Modifier)
                            .testTag(tag(index))
                            .captureClickable(shape = ChoiceShape) { haptics.selection(); onPick(index) }
                            .semantics {
                                contentDescription = "$title ${labels[index]}"
                                role = Role.RadioButton
                                this.selected = isSelected
                            }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            labels[index],
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = CaptureMono,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (isSelected) CaptureColors.Accent
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = .85f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * One option row of the selected lens as a chip (e.g. Aperture "f/2"). A row locked by another choice (Aperture
 * while Mode is 50MP) shows its locked value, greyed, and doesn't open.
 */
@Composable
internal fun LensOptionChip(
    options: LensOptionsQuickState,
    row: Int,
    orientation: Orientation,
    enabled: Boolean,
    onSelect: (row: Int, choice: Int) -> Unit
) {
    val option = options.options[row]
    val pick = options.picks.getOrElse(row) { option.selected }
    val label = option.choices.getOrNull(pick)?.label.orEmpty()
    val locked = row in options.locked
    var open by remember(options.lensId, row, enabled, locked) { mutableStateOf(false) }
    Box {
        CaptureChip(
            label = label,
            orientation = orientation,
            enabled = enabled && !locked,
            open = open,
            accent = true,
            testTag = LensOptionTags.chip(row),
            description = "${option.name}: $label." + if (locked) " Fixed by the current mode." else " Tap to change.",
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ChoiceGrid(
                title = option.name,
                labels = option.choices.map { it.label },
                selected = option.selected,
                tag = { LensOptionTags.choice(row, it) },
                onPick = { choice ->
                    open = false
                    if (choice != option.selected) onSelect(row, choice)
                }
            )
        }
    }
}

/** Short DCP name for the chip: the last word ("X14Ultra Main" -> "Main", "Xiaomi-14Ultra-Tele122" -> "Tele122"). */
internal fun shortDcpName(name: String): String =
    name.trim().split(' ', '-', '_').lastOrNull { it.isNotBlank() } ?: name

/** The lens's camera profile as a chip; the popup lists full names and the camera's own colour. */
@Composable
internal fun DcpChip(
    dcp: DcpQuickState,
    orientation: Orientation,
    enabled: Boolean,
    onSelect: (String?) -> Unit
) {
    val selected = dcp.profiles.firstOrNull { it.file == dcp.selectedFile }
    val label = selected?.let { shortDcpName(it.name) } ?: "Default"
    var open by remember(dcp.lensId, enabled) { mutableStateOf(false) }
    val files = listOf<String?>(null) + dcp.profiles.map { it.file }
    val names = listOf("Default") + dcp.profiles.map { shortDcpName(it.name) }
    Box {
        CaptureChip(
            label = label,
            orientation = orientation,
            enabled = enabled,
            open = open,
            accent = false,
            testTag = CaptureTestTags.DCP_PROFILE,
            description = "DCP profile: ${selected?.name ?: "camera default"}. Tap to choose.",
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ChoiceGrid(
                title = "DCP",
                labels = names,
                selected = files.indexOf(dcp.selectedFile).coerceAtLeast(0),
                tag = { CaptureTestTags.dcpOption(files[it]) },
                onPick = { index ->
                    open = false
                    if (files[index] != dcp.selectedFile) onSelect(files[index])
                }
            )
        }
    }
}
