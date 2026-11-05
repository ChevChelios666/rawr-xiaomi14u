package com.rawr.camera.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawr.camera.settings.model.LensOption
import com.rawr.camera.settings.model.LensProfile
import com.rawr.camera.settings.model.selectOption
import com.rawr.camera.settings.model.validateLensOptions
import com.rawr.camera.settings.preferences.LensOptionsCodec

/**
 * Lens options (X14U build): the rows of choices the capture screen shows on long press. Each row's choice here is
 * the one selected now (the capture screen changes the same selection). Rows are edited as JSON.
 */
@Composable
internal fun LensOptionsGroup(draft: LensProfile, onChange: (LensProfile) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    SettingsGroup(
        title = "Options",
        description = "Rows of choices (e.g. Aperture, Mode) shown when you long-press this lens on the capture " +
            "screen. A choice adds vendor keys and can patch any other lens setting."
    ) {
        draft.options.forEachIndexed { row, option ->
            DropdownChoiceRow(
                title = option.name,
                selected = option.selected.coerceIn(0, (option.choices.size - 1).coerceAtLeast(0)),
                values = option.choices.indices.toList(),
                label = { option.choices.getOrNull(it)?.label.orEmpty() },
                optionText = { index -> option.choices.getOrNull(index)?.let(::choiceDetail) },
                onSelect = { onChange(draft.selectOption(row, it)) }
            )
            SettingDivider()
        }
        SettingsRow(
            title = if (draft.options.isEmpty()) "Add options" else "Edit options",
            value = if (draft.options.isEmpty()) "None" else "${draft.options.size} rows",
            supportingText = "JSON: name, choices (label, vendorKeys, patch)",
            testTag = lensEditorTag("options"),
            onClick = { editing = true }
        )
    }
    if (editing) {
        LensOptionsJsonDialog(
            initial = draft.options,
            onDismiss = { editing = false },
            onSave = { onChange(draft.copy(options = it)); editing = false }
        )
    }
}

private fun choiceDetail(choice: com.rawr.camera.settings.model.LensOptionChoice): String? {
    val parts = buildList {
        if (choice.vendorKeys.isNotEmpty()) add("${choice.vendorKeys.size} key${if (choice.vendorKeys.size == 1) "" else "s"}")
        if (choice.patch != null) add("patch")
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

@Composable
private fun LensOptionsJsonDialog(
    initial: List<LensOption>,
    onDismiss: () -> Unit,
    onSave: (List<LensOption>) -> Unit
) {
    var text by remember { mutableStateOf(LensOptionsCodec.toEditorText(initial)) }
    val parsed = remember(text) { LensOptionsCodec.fromEditorText(text) }
    val error = parsed.exceptionOrNull()?.let { it.message ?: "Not valid JSON" }
        ?: parsed.getOrNull()?.let { validateLensOptions(it).firstOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lens options") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp)
                        .padding(bottom = 4.dp)
                )
                Text(
                    "A patch is merged into the lens profile (objects merge, values replace, null removes). " +
                        "Use [] for no options.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(enabled = error == null, onClick = { parsed.getOrNull()?.let(onSave) }) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
