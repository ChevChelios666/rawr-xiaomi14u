package com.rawr.camera.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rawr.camera.settings.model.LensOverrideKind
import com.rawr.camera.settings.model.LensOverrideSpec
import com.rawr.camera.settings.model.LensOverrides
import com.rawr.camera.settings.model.LensProfile
import com.rawr.camera.settings.model.SettingsValues
import com.rawr.camera.ui.icons.Icons
import com.rawr.camera.ui.icons.rounded.Close

/**
 * Per-lens overrides (X14U build): settings this lens uses instead of the global ones. An override starts at the
 * global value; each row shows the global value; × removes it.
 */
@Composable
internal fun LensOverridesGroup(draft: LensProfile, global: SettingsValues, onChange: (LensProfile) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    val set: (String, String) -> Unit = { key, value -> onChange(draft.copy(overrides = draft.overrides + (key to value))) }
    val remove: (String) -> Unit = { key -> onChange(draft.copy(overrides = draft.overrides - key)) }
    SettingsGroup(
        title = "Overrides",
        description = "Settings this lens uses instead of the global ones (Rawr AE, tone, detail, multiframe). " +
            "Everything else follows Settings. Tone applies to the active colour profile."
    ) {
        LensOverrides.all.filter { it.key in draft.overrides }.forEach { spec ->
            OverrideRow(spec, draft.overrides.getValue(spec.key), spec.labelOf(global), { set(spec.key, it) }) {
                remove(spec.key)
            }
            SettingDivider()
        }
        SettingsRow(
            title = "Add override",
            value = if (draft.overrides.isEmpty()) "None" else "${draft.overrides.size} set",
            testTag = lensEditorTag("add_override"),
            onClick = { adding = true }
        )
    }
    if (adding) {
        AddOverrideDialog(
            available = LensOverrides.all.filter { it.key !in draft.overrides },
            global = global,
            onDismiss = { adding = false },
            onPick = { spec -> set(spec.key, spec.read(global)); adding = false }
        )
    }
}

@Composable
private fun OverrideRow(
    spec: LensOverrideSpec,
    value: String,
    globalLabel: String,
    onValue: (String) -> Unit,
    onRemove: () -> Unit
) {
    val global = "Global: $globalLabel"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            when (val kind = spec.kind) {
                is LensOverrideKind.Choice -> DropdownChoiceRow(
                    title = spec.title,
                    selected = value,
                    values = kind.values.map { it.first },
                    label = { id -> kind.values.firstOrNull { it.first == id }?.second ?: id },
                    supportingText = global,
                    onSelect = onValue
                )
                is LensOverrideKind.Number -> StandaloneNumericSliderRow(
                    identity = "lens_override_${spec.key}",
                    label = spec.title,
                    supportingText = global,
                    minimum = kind.min,
                    maximum = kind.max,
                    step = kind.step,
                    decimals = kind.decimals,
                    value = (value.toFloatOrNull() ?: kind.min).coerceIn(kind.min, kind.max),
                    unit = kind.unit
                ) { onValue(if (kind.decimals == 0) Math.round(it).toString() else it.toString()) }
                LensOverrideKind.Toggle -> SettingsSwitchRow(
                    title = spec.title,
                    checked = value == "true",
                    supportingText = global
                ) { onValue(it.toString()) }
            }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Rounded.Close, contentDescription = "Remove ${spec.title} override")
        }
    }
}

@Composable
private fun AddOverrideDialog(
    available: List<LensOverrideSpec>,
    global: SettingsValues,
    onDismiss: () -> Unit,
    onPick: (LensOverrideSpec) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add override") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (available.isEmpty()) Text("Every setting is already overridden.")
                LensOverrides.groups.forEach { group ->
                    val specs = available.filter { it.group == group }
                    if (specs.isEmpty()) return@forEach
                    Text(
                        group.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    specs.forEach { spec ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(spec) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // The title gets the room; a long value wraps under itself, never squeezing the title.
                            Text(spec.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(
                                spec.labelOf(global),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 12.dp).widthIn(max = 120.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
