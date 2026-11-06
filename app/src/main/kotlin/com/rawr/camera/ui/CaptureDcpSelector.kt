package com.rawr.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rawr.camera.architecture.CaptureDispatch
import com.rawr.camera.model.CaptureUiState
import com.rawr.camera.model.LensOptionsQuickState

internal val LensRowHeight = 38.dp
internal val LensRowGap = 4.dp

/** X14U build: extra space between the option chips and the thumbnail/shutter row. */
internal val ShutterRowDrop = 20.dp

/** Height of both lens rows; the shutter sits below it in every layout. */
internal val LensSelectorHeight = LensRowHeight + LensRowGap + CaptureChipHeight

/**
 * X14U build: two rows. The lenses on top; below, the option slots (Aperture, DCG, 50 mp) that stay put on every
 * lens, greyed where the lens doesn't have them. Both rows keep their height, so the shutter never moves. The DCP
 * moved to a button beside FILM (DcpSidePill).
 */
@Composable
internal fun LensAndDcpSelector(
    state: CaptureUiState,
    dispatch: CaptureDispatch,
    modifier: Modifier,
    enabled: Boolean = true,
    onOpenLensSettings: (String) -> Unit = {},
    lensOptions: List<LensOptionsQuickState> = emptyList(),
    onSelectLensOption: (lensId: String, row: Int, choice: Int) -> Unit = { _, _, _ -> }
) {
    val options = lensOptions.firstOrNull { it.lensId == state.selectedLensId }
    val slots = lensOptionSlots(lensOptions)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(LensRowHeight), contentAlignment = Alignment.Center) {
            LensSelector(state, dispatch, Modifier, enabled, onOpenLensSettings)
        }
        Spacer(Modifier.height(LensRowGap))
        Row(
            Modifier.height(CaptureChipHeight),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            slots.forEach { (name, template) ->
                LensOptionSlotChip(name, template, options, enabled) { row, choice ->
                    options?.let { onSelectLensOption(it.lensId, row, choice) }
                }
            }
        }
    }
}
