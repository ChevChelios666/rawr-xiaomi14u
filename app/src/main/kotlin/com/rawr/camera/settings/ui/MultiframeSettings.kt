package com.rawr.camera.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import com.rawr.camera.settings.architecture.*
import com.rawr.camera.settings.model.*

@Composable
internal fun MultiframeSettings(state: SettingsUiState, dispatch: SettingsDispatch) {
    val enabled = state.values.experimentalMultiframeEnabled
    val tuning = state.values.multiframeTuning
    val spec = { parameter: MultiframeNumericParameter -> MultiframeSpecs.forParameter(parameter) }
    val rexture = tuning.mergeAlgorithm == MultiframeMergeAlgorithm.Rexture
    SettingsPageContainer {
        SettingsGroup(
            description = "Merges several frames into each photo (HDR+ / Rexture). Off: one frame per photo."
        ) {
            SettingsSwitchRow(
                title = "Multiframe",
                checked = enabled
            ) { dispatch.invoke(SetExperimentalMultiframeEnabled(it)) }
        }
        SettingsGroup {
            SettingsSwitchRow(
                title = "Save Base DNG",
                checked = state.values.saveBaseDng,
                supportingText = "Also saves the unmerged base frame as a DNG: a fallback if the merge ghosts.",
                enabled = enabled && state.values.dngEnabled
            ) { dispatch.invoke(SetSaveBaseDng(it)) }
        }
        SettingsGroup(
            title = "Merge Algorithm",
            description = "Both merge the burst onto its sharpest frame. HDR+ Quality: cleaner, moving subjects stay sharp. Rexture: more detail on still scenes; above ~56 MP, DNG only."
        ) {
            MultiframeMergeAlgorithm.entries.forEachIndexed { index, candidate ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = candidate.label,
                    selected = tuning.mergeAlgorithm == candidate,
                    enabled = enabled
                ) { dispatch.invoke(SetMultiframeMergeAlgorithm(candidate)) }
            }
        }
        SettingsGroup(
            title = "Merge When Zoomed",
            description = "The merge for digitally zoomed shots (e.g. 70 mm on the 3.2x)."
        ) {
            val options = listOf<MultiframeMergeAlgorithm?>(null) + MultiframeMergeAlgorithm.entries
            options.forEachIndexed { index, candidate ->
                if (index > 0) SettingDivider()
                SettingsSelectionRow(
                    title = candidate?.label ?: "Same as normal",
                    selected = state.values.zoomMergeAlgorithm == candidate,
                    enabled = enabled
                ) { dispatch.invoke(SetZoomMergeAlgorithm(candidate)) }
            }
        }
        SettingsGroup(
            title = "Output",
            description = "Double-tap any parameter label to restore its default."
        ) {
            // Needs the burst noise fit, which both merges run; JPEG only: the merged DNG is written before rendering.
            SettingsSwitchRow(
                title = "Chroma Denoise",
                checked = state.values.multiframeChromaDenoise,
                supportingText =
                    "JPEG only. Cleans leftover colour blotches after the merge, using the burst's measured noise.",
                enabled = enabled
            ) { dispatch.invoke(SetMultiframeChromaDenoise(it)) }
            SettingDivider()
            if (rexture) {
                SettingsSwitchRow(
                    title = "Rexture Native RGB",
                    checked = state.values.multiframeRextureNativeRgb,
                    supportingText = "JPEG only. Uses Rexture's own merged colour instead of the Demosaic setting. DNG unchanged.",
                    enabled = enabled
                ) { dispatch.invoke(SetMultiframeRextureNativeRgb(it)) }
                SettingDivider()
                SettingsSwitchRow(
                    title = "Rexturation",
                    checked = state.values.multiframeRextureDetailRestore,
                    supportingText = "Recovers fine detail the merge softens. Slightly more grain.",
                    enabled = enabled,
                    testTag = SettingsTestTags.row("Multiframe", "rexturation")
                ) { dispatch.invoke(SetMultiframeRextureDetailRestore(it)) }
            }
            SettingDivider()
            MultiframeSlider(spec(MultiframeNumericParameter.MaxFrames), tuning, enabled, dispatch)
        }
    }
}

@Composable
private fun MultiframeSlider(
    spec: MultiframeNumericSpec,
    tuning: MultiframeTuning,
    enabled: Boolean,
    dispatch: SettingsDispatch
) {
    StandaloneNumericSliderRow(
        identity = "mf_${spec.parameter.name}",
        label = spec.label,
        supportingText = spec.supportingText,
        minimum = spec.minimum,
        maximum = spec.maximum,
        step = spec.step,
        decimals = spec.decimals,
        value = tuning.value(spec.parameter).coerceIn(spec.minimum, spec.maximum),
        enabled = enabled,
        defaultValue = spec.defaultValue,
        unit = spec.unit,
        valueFormatter = null
    ) { dispatch.invoke(SetMultiframeNumericValue(spec.parameter, it)) }
}
