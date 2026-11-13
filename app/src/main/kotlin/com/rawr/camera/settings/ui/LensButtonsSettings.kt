package com.rawr.camera.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rawr.camera.settings.architecture.SetLensButtons
import com.rawr.camera.settings.architecture.SettingsDispatch
import com.rawr.camera.settings.model.DigitalStop
import com.rawr.camera.settings.model.LensButtons
import com.rawr.camera.settings.model.SettingsUiState
import com.rawr.camera.settings.model.ZoomChain
import com.rawr.camera.settings.model.effectiveLensProfiles
import com.rawr.camera.settings.model.resolveDigitalStop
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.rounded.DeleteOutline
import java.util.Locale

/**
 * X14U lens pill (Settings → Lens): label style, and per mode which lenses show and which digital focal lengths are
 * added. A digital focal length crops the next wider lens.
 */
@Composable
internal fun LensButtonsGroup(state: SettingsUiState, dispatch: SettingsDispatch) {
    val hardware = LocalLensHardware.current
    val buttons = state.values.lensButtons
    var mode by remember { mutableStateOf(com.rawr.camera.settings.model.LensMode.Photo) }
    val pano = mode == com.rawr.camera.settings.model.LensMode.Pano
    val set = buttons.forMode(mode)
    val lenses = state.values.effectiveLensProfiles(hardware.deviceDefaults).filter { it.enabled }
    val chain = ZoomChain.of(lenses)
    val save: (LensButtons) -> Unit = { dispatch.invoke(SetLensButtons(it)) }
    val mpOf: (Float) -> String = { crop -> String.format(Locale.US, "%.1f MP", 12.58f / (crop * crop)) }

    SettingsGroup(
        title = "Lens buttons",
        description = "The lens pill on the capture screen. Photo, Video and Pano each keep their own set (Pano: " +
            "lenses only). A digital focal length is a crop of the next wider lens, saved at the crop's own resolution."
    ) {
        Column(Modifier.padding(horizontal = SettingsRowHorizontalPadding, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Labels", style = MaterialTheme.typography.bodyMedium)
            SegmentedOptionRow(listOf("1x", "23 mm"), if (buttons.mmLabels) 1 else 0, { save(buttons.copy(mmLabels = it == 1)) })
            Text("Set for", style = MaterialTheme.typography.bodyMedium)
            SegmentedOptionRow(com.rawr.camera.settings.model.LensMode.entries.map { it.label }, mode.ordinal,
                { mode = com.rawr.camera.settings.model.LensMode.entries[it] })
        }
        SettingDivider()
        chain.forEachIndexed { index, (name, factor) ->
            if (index > 0) SettingDivider()
            val shown = name !in set.hidden
            SettingsSwitchRow(
                title = "$name · ${LensButtons.mmOf(factor)} mm",
                checked = shown,
                supportingText = if (shown) "On the pill" else "Hidden in ${mode.label}",
                // At least one lens stays on the pill.
                enabled = !shown || chain.count { it.first !in set.hidden } > 1,
                onCheckedChange = { on ->
                    save(buttons.withSet(mode, set.copy(hidden = if (on) set.hidden - name else set.hidden + name)))
                }
            )
        }
        if (!pano) set.digital.forEach { stop ->
            SettingDivider()
            val resolved = resolveDigitalStop(stop.mm, chain)
            SettingsRow(
                title = "${stop.mm} mm",
                supportingText = resolved?.let {
                    String.format(Locale.US, "%.1fx · crop of %s · %s photo", it.zoom, it.lens, mpOf(it.crop))
                } ?: "No wider lens to crop",
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            save(buttons.withSet(mode, set.copy(digital = set.digital.filter { it.mm != stop.mm })))
                        }) { Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove ${stop.mm} mm") }
                        Switch(checked = stop.on, onCheckedChange = { on ->
                            save(buttons.withSet(mode, set.copy(digital = set.digital.map {
                                if (it.mm == stop.mm) it.copy(on = on) else it
                            })))
                        })
                    }
                }
            )
        }
        if (!pano) DigitalStopAdder(buttons, mode, set, chain, save)
    }
}

@Composable
private fun DigitalStopAdder(
    buttons: LensButtons,
    mode: com.rawr.camera.settings.model.LensMode,
    set: com.rawr.camera.settings.model.LensButtonSet,
    chain: List<Pair<String, Float>>,
    save: (LensButtons) -> Unit
) {
        SettingDivider()
        var text by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = SettingsRowHorizontalPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3); error = null },
                label = { Text("Focal length (mm)") },
                placeholder = { Text("50") },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                val mm = text.toIntOrNull()
                error = when {
                    mm == null -> "Enter a focal length first"
                    mm < LensButtons.MIN_MM || mm > LensButtons.MAX_MM -> "Pick between ${LensButtons.MIN_MM} and ${LensButtons.MAX_MM} mm"
                    chain.any { LensButtons.mmOf(it.second) == mm } -> "$mm mm is a lens already"
                    set.digital.any { it.mm == mm } -> "$mm mm is already there"
                    resolveDigitalStop(mm, chain) == null -> "No wider lens to crop for $mm mm"
                    else -> null
                }
                if (error == null && mm != null) {
                    save(buttons.withSet(mode, set.copy(digital = (set.digital + DigitalStop(mm)).sortedBy { it.mm })))
                    text = ""
                }
            }) { Text("Add") }
        }
}
