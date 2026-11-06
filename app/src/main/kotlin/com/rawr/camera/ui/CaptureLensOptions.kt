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
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.outlined.Palette
import com.rawr.camera.model.DcpQuickState
import com.rawr.camera.settings.model.LensOption
import com.rawr.camera.model.LensOptionsQuickState

/** Test tags of the lens option chips (X14U build). */
internal object LensOptionTags {
    fun chip(name: String) = "capture_lens_option_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
    fun choice(row: Int, choice: Int) = "capture_lens_option_${row}_$choice"
}

internal val CaptureChipHeight = 32.dp

/** Width of each of the three pills beside the shutter (HDR+, FILM, DCP). */
internal val SidePillWidth = 48.dp
private val ChipShape = RoundedCornerShape(16.dp)
private val ChoiceShape = RoundedCornerShape(18.dp)

/** A pill on the second lens row: its value and a chevron; tap opens its popup. */
@Composable
private fun CaptureChip(
    label: String,
    enabled: Boolean,
    open: Boolean,
    accent: Boolean,
    testTag: String,
    filled: Boolean = false,
    description: String,
    onClick: () -> Unit,
    chevron: Boolean = true
) {
    val haptics = LocalCaptureHaptics.current
    val textColor = when {
        !enabled -> Color.White.copy(alpha = .28f)
        filled -> CaptureColors.Accent
        accent -> CaptureColors.Accent
        else -> Color.White.copy(alpha = .72f)
    }
    Box(
        Modifier
            .height(CaptureChipHeight)
            .widthIn(min = 56.dp, max = 140.dp)
            .clip(ChipShape)
            .background(
                when {
                    filled -> CaptureColors.Accent.copy(alpha = .16f)
                    open -> Color.White.copy(alpha = .10f)
                    else -> Color.Transparent
                }
            )
            .border(
                1.dp,
                when {
                    filled || open -> CaptureColors.Accent.copy(alpha = if (enabled) .8f else .3f)
                    else -> Color.White.copy(alpha = if (enabled) .22f else .10f)
                },
                ChipShape
            )
            .testTag(testTag)
            .then(
                if (enabled) {
                    Modifier.captureClickable(shape = ChipShape, role = if (chevron) Role.DropdownList else Role.Switch) {
                        haptics.selection()
                        onClick()
                    }
                }
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
            if (enabled && chevron) "$label ▾" else label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            fontFamily = CaptureMono,
            fontWeight = FontWeight.Bold,
            // Always portrait: rotated, the wide labels were clipped by the chip.
            color = textColor
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
        val perLine = if (labels.size > 4) 3 else 2
        labels.indices.chunked(perLine).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                line.forEach { index ->
                    val isSelected = index == selected
                    Box(
                        Modifier
                            .width(if (perLine == 3) 80.dp else 112.dp)
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
 * The option slots shown on every lens: each option row name found on any lens, in the order first seen (1x gives
 * Aperture, DCG, 50 mp). Paired with the first row of that name, for its kind and its greyed label.
 */
internal fun lensOptionSlots(all: List<LensOptionsQuickState>): List<Pair<String, LensOption>> {
    val seen = linkedMapOf<String, Pair<String, LensOption>>()
    all.forEach { lens ->
        lens.options.forEach { option ->
            seen.getOrPut(option.name.trim().lowercase()) { option.name.trim() to option }
        }
    }
    return seen.values.toList()
}

/** Greyed label of a slot the lens doesn't have: "f/–" when every choice starts "f/", else the row name. */
private fun placeholderLabel(option: LensOption): String {
    if (option.toggle) return option.name
    val labels = option.choices.map { it.label.trim() }
    val prefix = labels.reduceOrNull { a, b -> a.commonPrefixWith(b) }.orEmpty().takeWhile { !it.isDigit() }
    return if (prefix.length >= 2) "$prefix–" else option.name
}

/**
 * One option slot as a chip. On a lens without the row: greyed. A select row (Aperture) opens a popup and is greyed
 * while another choice locks it (50 mp locks f/2). An on/off row (DCG, 50 mp) flips on tap; a locked one still
 * flips, switching the locking row off.
 */
@Composable
internal fun LensOptionSlotChip(
    name: String,
    template: LensOption,
    options: LensOptionsQuickState?,
    enabled: Boolean,
    onSelect: (row: Int, choice: Int) -> Unit
) {
    val row = options?.options?.indexOfFirst { it.name.trim().equals(name, ignoreCase = true) } ?: -1
    val option = options?.options?.getOrNull(row)
    val slotTag = LensOptionTags.chip(name)
    if (options == null || option == null) {
        CaptureChip(
            label = placeholderLabel(template),
            enabled = false,
            open = false,
            accent = false,
            testTag = slotTag,
            description = "$name: not available on this lens.",
            onClick = {},
            chevron = !template.toggle
        )
        return
    }
    val pick = options.picks.getOrElse(row) { option.selected }
    val locked = row in options.locked
    if (option.toggle) {
        val on = pick == 1
        CaptureChip(
            label = option.name,
            enabled = enabled,
            open = false,
            accent = false,
            filled = on,
            testTag = slotTag,
            description = "${option.name} ${if (on) "on" else "off"}. Tap to turn ${if (on) "off" else "on"}.",
            onClick = { onSelect(row, if (on) 0 else 1) },
            chevron = false
        )
        return
    }
    val label = option.choices.getOrNull(pick)?.label.orEmpty()
    var open by remember(options.lensId, row, enabled, locked) { mutableStateOf(false) }
    Box {
        CaptureChip(
            label = label,
            enabled = enabled && !locked,
            open = open,
            accent = true,
            testTag = slotTag,
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

/** Short DCP name: the last word ("X14Ultra Main" -> "Main", "Xiaomi-14Ultra-Tele122" -> "Tele122"). */
internal fun shortDcpName(name: String): String =
    name.trim().split(' ', '-', '_').lastOrNull { it.isNotBlank() } ?: name

/**
 * DCP button beside FILM (same pill style): accent while the lens uses a DCP; tap picks one, long press opens the
 * lens's settings. Dim and inert on a lens without DCPs.
 */
@Composable
internal fun DcpSidePill(
    dcp: DcpQuickState?,
    onSelect: (String?) -> Unit,
    onOpenLensSettings: () -> Unit
) {
    val profiles = dcp?.profiles.orEmpty()
    val selected = profiles.firstOrNull { it.file == dcp?.selectedFile }
    var open by remember(dcp?.lensId) { mutableStateOf(false) }
    val files = listOf<String?>(null) + profiles.map { it.file }
    val names = listOf("Default") + profiles.map { shortDcpName(it.name) }
    Box {
        CompactSidePill(
            icon = Icons.Outlined.Palette,
            label = "DCP",
            active = selected != null,
            testTag = CaptureTestTags.DCP_PROFILE,
            contentDescription = when {
                profiles.isEmpty() -> "No DCP profiles for this lens. Long press for lens settings."
                selected != null -> "DCP ${selected.name}. Tap to choose. Long press for lens settings."
                else -> "DCP: camera default. Tap to choose. Long press for lens settings."
            },
            onClick = { if (profiles.isNotEmpty()) open = true },
            onLongClick = onOpenLensSettings,
            onLongClickLabel = "Lens settings",
            minWidth = SidePillWidth
        )
        DropdownMenu(expanded = open && dcp != null, onDismissRequest = { open = false }) {
            ChoiceGrid(
                title = "DCP",
                labels = names,
                selected = files.indexOf(dcp?.selectedFile).coerceAtLeast(0),
                tag = { CaptureTestTags.dcpOption(files[it]) },
                onPick = { index ->
                    open = false
                    if (files[index] != dcp?.selectedFile) onSelect(files[index])
                }
            )
        }
    }
}
