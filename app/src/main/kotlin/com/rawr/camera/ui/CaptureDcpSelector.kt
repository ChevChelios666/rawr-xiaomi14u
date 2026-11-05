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
import com.rawr.camera.model.DcpQuickState
import com.rawr.camera.model.LensOptionsQuickState

internal val LensRowHeight = 38.dp
internal val LensRowGap = 4.dp

/** Height of both lens rows; the shutter sits below it in every layout. */
internal val LensSelectorHeight = LensRowHeight + LensRowGap + CaptureChipHeight

/**
 * X14U build: two rows. The lenses on top; below, one chip per option row of the selected lens (Aperture, Mode)
 * and its DCP. Both rows keep their height whatever the lens has, so the shutter never moves.
 */
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
    val options = lensOptions.firstOrNull { it.lensId == state.selectedLensId }
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
            options?.options?.indices?.forEach { row ->
                LensOptionChip(options, row, state.orientation, enabled) { r, choice ->
                    onSelectLensOption(options.lensId, r, choice)
                }
            }
            if (dcp != null && dcp.profiles.isNotEmpty()) {
                DcpChip(dcp, state.orientation, enabled, onSelectDcp)
            }
        }
    }
}
