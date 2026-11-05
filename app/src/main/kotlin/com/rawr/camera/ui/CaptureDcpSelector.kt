package com.rawr.camera.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.DcpQuickState
import com.rawr.camera.model.LensOptionsQuickState
import com.rawr.camera.architecture.SelectLens

/** A constant row height keeps the shutter steady when the optional DCP shortcut appears. */
@Composable
internal fun LensAndDcpSelector(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    modifier: Modifier,
    dcp: DcpQuickState?,
    onSelectDcp: (String?) -> Unit,
    enabled: Boolean = true,
    onOpenLensSettings: (String) -> Unit = {},
    lensOptions: List<LensOptionsQuickState> = emptyList(),
    onSelectLensOption: (lensId: String, row: Int, choice: Int) -> Unit = { _, _, _ -> }
) {
    // Lens options (X14U build): long press on a lens with options opens its options panel instead of settings.
    var optionsFor by remember(enabled) { mutableStateOf<String?>(null) }
    val selectedOptions = lensOptions.firstOrNull { it.lensId == state.selectedLensId }
    Box(modifier) {
    Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f, fill = false)) {
            LensSelector(state, dispatch, Modifier, enabled) { lensId ->
                if (lensOptions.any { it.lensId == lensId }) {
                    if (lensId != state.selectedLensId) dispatch(SelectLens(lensId))
                    optionsFor = lensId
                } else {
                    onOpenLensSettings(lensId)
                }
            }
        }
        if (selectedOptions != null) {
            LensOptionsButton(selectedOptions, state.orientation, enabled) { optionsFor = selectedOptions.lensId }
        }
        if (dcp != null && dcp.profiles.isNotEmpty()) {
            Box(Modifier.widthIn(max = 136.dp)) {
                var expanded by remember(dcp.lensId, enabled) { mutableStateOf(false) }
                val haptics = LocalCaptureHaptics.current
                val label = dcp.profiles.firstOrNull { it.file == dcp.selectedFile }?.name ?: "Default"
                Box {
                    TextButton(
                        onClick = { haptics.selection(); expanded = true },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth().height(32.dp)
                            .testTag(CaptureTestTags.DCP_PROFILE)
                            .semantics { contentDescription = "DCP profile: $label. Tap to choose." },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("DCP · $label", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontSize = 10.sp, fontFamily = CaptureMono, color = if (enabled) CaptureColors.Accent else CaptureColors.Muted,
                            modifier = Modifier.uprightInLandscape(state.orientation))
                    }
                    DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                        val options = listOf(null to "Camera default") + dcp.profiles.map { it.file to it.name }
                        options.forEach { (file, name) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        name,
                                        color = if (file == dcp.selectedFile) CaptureColors.Accent
                                            else MaterialTheme.colorScheme.onSurface,
                                        fontFamily = CaptureMono,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = .4.sp
                                    )
                                },
                                modifier = Modifier.testTag(CaptureTestTags.dcpOption(file))
                                    .semantics { selected = file == dcp.selectedFile },
                                onClick = { expanded = false; haptics.selection(); onSelectDcp(file) }
                            )
                        }
                    }
                }
            }
        }
    }
    LensOptionsPanel(
        expanded = optionsFor != null && enabled,
        options = lensOptions.firstOrNull { it.lensId == optionsFor },
        onDismiss = { optionsFor = null },
        onSelect = { row, choice -> optionsFor?.let { onSelectLensOption(it, row, choice) } },
        onOpenLensSettings = { optionsFor?.let(onOpenLensSettings) }
    )
    }
}
