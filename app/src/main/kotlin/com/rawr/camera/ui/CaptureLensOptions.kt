package com.rawr.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.model.LensOptionsQuickState
import com.rawr.camera.model.Orientation

/** Test tags of the lens options controls (X14U build). */
internal object LensOptionTags {
    const val BUTTON = "capture_lens_options"
    const val SETTINGS = "capture_lens_options_settings"
    fun choice(row: Int, choice: Int) = "capture_lens_option_${row}_$choice"
}

/** The selected choices of the current lens ("f/2.8 · DCG"); tap opens the options panel. */
@Composable
internal fun LensOptionsButton(
    options: LensOptionsQuickState,
    orientation: Orientation,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    TextButton(
        onClick = { haptics.selection(); onClick() },
        enabled = enabled,
        modifier = Modifier.height(32.dp).widthIn(max = 132.dp)
            .testTag(LensOptionTags.BUTTON)
            .semantics { contentDescription = "Lens options: ${options.summary}. Tap to change." },
        contentPadding = PaddingValues(horizontal = 8.dp)
    ) {
        Text(
            options.summary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 10.sp,
            fontFamily = CaptureMono,
            fontWeight = FontWeight.Bold,
            color = if (enabled) CaptureColors.Accent else CaptureColors.Muted,
            modifier = Modifier.uprightInLandscape(orientation)
        )
    }
}

/**
 * One row per option (Aperture, Mode, ...) with its choices; picking one applies it right away (the camera
 * restarts with the new lens). The last item opens the lens's settings.
 */
@Composable
internal fun LensOptionsPanel(
    expanded: Boolean,
    options: LensOptionsQuickState?,
    onDismiss: () -> Unit,
    onSelect: (row: Int, choice: Int) -> Unit,
    onOpenLensSettings: () -> Unit
) {
    val haptics = LocalCaptureHaptics.current
    DropdownMenu(expanded = expanded && options != null, onDismissRequest = onDismiss) {
        if (options == null) return@DropdownMenu
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "LENS ${options.lensId}",
                fontFamily = CaptureMono,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = .6.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .5f)
            )
            options.options.forEachIndexed { row, option ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        option.name.uppercase(),
                        fontFamily = CaptureMono,
                        fontSize = 9.sp,
                        letterSpacing = .6.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)
                    )
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        option.choices.forEachIndexed { index, choice ->
                            val isSelected = index == option.selected
                            Box(
                                Modifier
                                    .height(30.dp)
                                    .widthIn(min = 44.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .background(
                                        if (isSelected) CaptureColors.Accent.copy(alpha = .18f)
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = .06f)
                                    )
                                    .testTag(LensOptionTags.choice(row, index))
                                    .captureClickable(shape = RoundedCornerShape(15.dp)) {
                                        if (!isSelected) {
                                            haptics.selection()
                                            onSelect(row, index)
                                        }
                                    }
                                    .semantics {
                                        contentDescription = "${option.name} ${choice.label}"
                                        role = Role.RadioButton
                                        selected = isSelected
                                    }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    choice.label,
                                    fontFamily = CaptureMono,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    color = if (isSelected) CaptureColors.Accent
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = .8f)
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
            Box(
                Modifier.fillMaxWidth().height(32.dp)
                    .testTag(LensOptionTags.SETTINGS)
                    .captureClickable(shape = RoundedCornerShape(6.dp)) {
                        haptics.selection()
                        onOpenLensSettings()
                        onDismiss()
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    "Lens settings…",
                    fontFamily = CaptureMono,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)
                )
            }
        }
    }
}
