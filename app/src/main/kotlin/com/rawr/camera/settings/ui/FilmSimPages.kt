package com.rawr.camera.settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rawr.camera.settings.architecture.*
import com.rawr.camera.settings.model.*
import kotlin.math.roundToInt

@Composable
internal fun FilmSimSubPageScreen(state: SettingsUiState, section: FilmSimSection, dispatch: SettingsDispatch) {
    val look = state.values.filmSimLook
    SettingsPageContainer(testTag = SettingsTestTags.filmSubPage(section.name)) {
        when (section) {
            FilmSimSection.Film -> FilmSubPageFilm(look, dispatch)
            FilmSimSection.DirCouplers -> FilmSubPageDirCouplers(look, dispatch)
            FilmSimSection.Print -> FilmSubPagePrint(look, dispatch)
            FilmSimSection.Filters -> FilmSubPageFilters(look, dispatch)
            FilmSimSection.Diffusion -> FilmSubPageDiffusion(look, dispatch)
            FilmSimSection.Grain -> FilmSubPageGrain(look, dispatch)
            FilmSimSection.Halation -> FilmSubPageHalation(look, dispatch)
            FilmSimSection.Scanner -> FilmSubPageScanner(state, dispatch)
            FilmSimSection.Output -> FilmSubPageOutput(state, dispatch)
            FilmSimSection.Presets -> FilmSimPresetGroup(state, dispatch)
        }
    }
}

@Composable
internal fun FilmSimDetailScreen(state: SettingsUiState, detail: FilmSimDetail, dispatch: SettingsDispatch) {
    val look = state.values.filmSimLook
    SettingsPageContainer(testTag = SettingsTestTags.filmDetail(detail.name)) {
        when (detail) {
            FilmSimDetail.GrainEmulsion -> GrainEmulsionPage(look, dispatch)
            FilmSimDetail.GrainTexture -> GrainTexturePage(look, dispatch)
            FilmSimDetail.HalationCoupling -> HalationCouplingPage(look, dispatch)
            FilmSimDetail.GlareShaping -> GlareShapingPage(look, dispatch)
        }
    }
}

@Composable
private fun FilmSubPageFilm(look: FilmSimLook, dispatch: SettingsDispatch) {
    SettingsGroup(title = "Stock", description = "The film: its colour, contrast and grain. Negatives are printed on the Print page's paper; slides aren't.") {
        FilmStockGrid(FilmSimDiscreteField.Film.options(), FilmStocks.positiveFilmIndices, look.film) {
            dispatch.invoke(SetFilmSimDiscreteValue(FilmSimDiscreteField.Film, it))
        }
    }
    SettingsGroup(title = "Format") {
        FilmChipFlowRow(FilmSimDiscreteField.FilmFormat.options(), look.filmFormat) {
            dispatch.invoke(SetFilmSimDiscreteValue(FilmSimDiscreteField.FilmFormat, it))
        }
    }
    SettingsGroup {
        FilmSegmentedRow("Workflow", FilmSimDiscreteField.Process.options(), look.process) {
            dispatch.invoke(SetFilmSimDiscreteValue(FilmSimDiscreteField.Process, it))
        }
        SettingDivider()
        SettingsSwitchRow(
            title = "Scan invert",
            checked = look.scanNegativeInvert
        ) {
            dispatch.invoke(SetFilmSimFlag(FilmSimFlag.ScanNegativeInvert, it))
        }
    }
    SettingsGroup(title = "Exposure", description = "Film exposure in stops. Over: dense, soft highlights. Under: thin and grainy.") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Film, "exposure"), look, dispatch)
    }
    FilmSegmentedRow("Push / pull mode", FilmSimDiscreteField.PushPullMode.options(), look.filmPushPullMode) {
        dispatch.invoke(SetFilmSimDiscreteValue(FilmSimDiscreteField.PushPullMode, it))
    }
    SettingsGroup(title = "Chemistry", description = "Development. Push: more contrast and grain. Pull: softer.") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Film, "chemistry"), look, dispatch)
    }
    SettingsGroup(title = "Spectral model", description = "How RGB becomes light for the film. Differs mainly in saturated colours.") {
        FilmChipFlowRow(FilmSimDiscreteField.SpectralMethod.options(), look.rgbToRawMethod) {
            dispatch.invoke(SetFilmSimDiscreteValue(FilmSimDiscreteField.SpectralMethod, it))
        }
    }
}

@Composable
private fun FilmSubPageDirCouplers(look: FilmSimLook, dispatch: SettingsDispatch) {
    SettingsGroup {
        SettingsSwitchRow(
            title = "DIR couplers",
            checked = look.dirCouplersAmount > 0f,
            supportingText = "Film chemistry that adds colour saturation and edge sharpness. Off: plainer colour."
        ) {
            dispatch.invoke(
                SetFilmSimNumericValue(
                    FilmSimNumericParameter.DirCouplersAmount,
                    if (it) 1f else 0f
                )
            )
        }
    }
    SettingsGroup(title = "Couplers") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.DirCouplers, "couplers"), look, dispatch)
    }
    SettingsGroup(title = "Calibration") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.DirCouplers, "calib"), look, dispatch)
    }
}

@Composable
private fun FilmSubPageFilters(look: FilmSimLook, dispatch: SettingsDispatch) {
    SettingsGroup(title = "Filtration", description = "Lens filters. UV / IR cut haze and odd foliage colours; sliders warm or cool.") {
        SettingsSwitchRow(
            title = "UV filter",
            checked = look.cameraUvFilterEnabled
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.CameraUvEnabled, it)) }
        SettingDivider()
        SettingsSwitchRow(
            title = "IR filter",
            checked = look.cameraIrFilterEnabled
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.CameraIrEnabled, it)) }
        SettingDivider()
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Filters, "filtration"), look, dispatch)
    }
}

@Composable
private fun FilmSubPageDiffusion(look: FilmSimLook, dispatch: SettingsDispatch) {
    SettingsGroup {
        SettingsSwitchRow(
            title = "Filter diffusion",
            checked = look.cameraDiffusionEnabled,
            supportingText = "A mist filter on the lens: glow around highlights, softer contrast."
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.CameraDiffusionEnabled, it)) }
    }
    SettingsGroup(title = "Family") {
        FilmChipFlowRow(FilmSimDiscreteField.CameraDiffusionFamily.options(), look.cameraDiffusionFamily) {
            dispatch.invoke(SetFilmSimDiscreteValue(FilmSimDiscreteField.CameraDiffusionFamily, it))
        }
    }
    SettingsGroup(title = "Diffusion") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Diffusion, "camera-main"), look, dispatch)
    }
    SettingsGroup(title = "Shaping") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Diffusion, "camera-shaping"), look, dispatch)
    }
    SettingsGroup {
        SettingsSwitchRow(
            title = "Print diffusion",
            checked = look.printDiffusionEnabled,
            supportingText = "Diffusion while printing: shadows bleed softly into highlights."
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.PrintDiffusionEnabled, it)) }
    }
    SettingsGroup(title = "Print family") {
        FilmChipFlowRow(FilmSimDiscreteField.PrintDiffusionFamily.options(), look.printDiffusionFamily) {
            dispatch.invoke(SetFilmSimDiscreteValue(FilmSimDiscreteField.PrintDiffusionFamily, it))
        }
    }
    SettingsGroup(title = "Print diffusion") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Diffusion, "print-main"), look, dispatch)
    }
    SettingsGroup(title = "Print shaping") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Diffusion, "print-shaping"), look, dispatch)
    }
}

@Composable
private fun FilmSubPageOutput(state: SettingsUiState, dispatch: SettingsDispatch) {
    val look = state.values.filmSimLook
    SettingsGroup(title = "Output") {
        SelectorDropdownRow(state, ChoiceSelectorKind.FilmOutputSpace, dispatch, title = "Output color space")
    }
}

@Composable
private fun FilmSubPagePrint(look: FilmSimLook, dispatch: SettingsDispatch) {
    SettingsGroup(title = "Paper", description = "Print paper: its contrast and colour. Negatives only.") {
        FilmChipFlowRow(FilmSimDiscreteField.Paper.options(), look.paper) {
            dispatch.invoke(SetFilmSimDiscreteValue(FilmSimDiscreteField.Paper, it))
        }
    }
    SettingsGroup(title = "Exposure", description = "Print exposure: lighter or darker print.") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Print, "exposure"), look, dispatch)
    }
    SettingsGroup(title = "Chemistry") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Print, "chemistry"), look, dispatch)
    }
    SettingsGroup(title = "Filtration", description = "Enlarger colour (C / M / Y): the print's colour balance.") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Print, "filtration"), look, dispatch)
    }
    SettingsGroup(title = "Lab", description = "Per-colour print exposure. Gang: move together. Calibration: grey prints grey.") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Print, "lab"), look, dispatch)
        SettingDivider()
        SettingsSwitchRow(
            title = "Printer lights gang",
            checked = look.printerLightsGang
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.PrinterLightsGang, it)) }
        SettingDivider()
        SettingsSwitchRow(
            title = "Printer light calibration",
            checked = look.printerLightCalibration
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.PrinterLightCalibration, it)) }
    }
    SettingsGroup(title = "Preflash", description = "Pre-exposes the paper: lower contrast, more highlight detail.") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Print, "preflash"), look, dispatch)
    }
    SettingsGroup(title = "Enlarger geometry", description = "Print size: how much grain, blur and halation grow.") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Print, "geometry"), look, dispatch)
    }
}

@Composable
private fun FilmSubPageGrain(look: FilmSimLook, dispatch: SettingsDispatch) {
    SettingsGroup {
        SettingsSwitchRow(
            title = "Grain",
            checked = look.grainEnabled,
            supportingText = "Film grain sized to the format. Sublayers: more natural. Animate: new grain each video frame."
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.GrainEnabled, it)) }
    }
    SettingsGroup(title = "Model") {
        FilmChipFlowRow(FilmSimDiscreteField.GrainModel.options(), look.grainModel) {
            dispatch.invoke(SetFilmSimDiscreteValue(FilmSimDiscreteField.GrainModel, it))
        }
    }
    SettingsGroup {
        SettingsSwitchRow(
            title = "Sublayers",
            checked = look.grainSublayersEnabled
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.GrainSublayersEnabled, it)) }
        SettingDivider()
        SettingsSwitchRow(
            title = "Animate",
            checked = look.grainAnimate
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.GrainAnimate, it)) }
        SettingDivider()
        FilmIntStepper(
            title = "Sub-layers",
            value = look.grainSubLayerCount,
            range = 1..8
        ) { dispatch.invoke(SetFilmSimDiscreteValue(FilmSimDiscreteField.GrainSubLayerCount, it)) }
    }
    SettingsGroup {
        SettingsRow(
            title = "Emulsion",
            value = "Strength, granularity, uniformity",
            onClick = { dispatch.invoke(OpenFilmSimDetail(FilmSimDetail.GrainEmulsion)) }
        )
        SettingDivider()
        SettingsRow(
            title = "Texture",
            value = "Blur and dye-cloud size",
            onClick = { dispatch.invoke(OpenFilmSimDetail(FilmSimDetail.GrainTexture)) }
        )
    }
}

@Composable
private fun GrainEmulsionPage(look: FilmSimLook, dispatch: SettingsDispatch) {
    SettingsGroup(title = "Emulsion") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Grain, "emulsion"), look, dispatch)
    }
}

@Composable
private fun GrainTexturePage(look: FilmSimLook, dispatch: SettingsDispatch) {
    SettingsGroup(title = "Texture") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Grain, "texture"), look, dispatch)
    }
}

@Composable
private fun FilmSubPageHalation(look: FilmSimLook, dispatch: SettingsDispatch) {
    SettingsGroup {
        SettingsSwitchRow(
            title = "Halation",
            checked = look.halationEnabled,
            supportingText = "Red glow around bright lights, from light bouncing back through the film. Threshold: how bright a light must be."
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.HalationEnabled, it)) }
    }
    SettingsGroup(title = "Scatter") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Halation, "scatter"), look, dispatch)
    }
    SettingsGroup(title = "Halation") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Halation, "halation"), look, dispatch)
    }
    SettingsGroup(title = "Threshold") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Halation, "threshold"), look, dispatch)
    }
    SettingsGroup {
        SettingsRow(
            title = "Coupling",
            value = "Per-channel bounce coupling",
            onClick = { dispatch.invoke(OpenFilmSimDetail(FilmSimDetail.HalationCoupling)) }
        )
    }
}

@Composable
private fun HalationCouplingPage(look: FilmSimLook, dispatch: SettingsDispatch) {
    SettingsGroup(title = "Coupling") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Halation, "coupling"), look, dispatch)
    }
}

@Composable
private fun FilmSubPageScanner(state: SettingsUiState, dispatch: SettingsDispatch) {
    val look = state.values.filmSimLook
    SettingsGroup {
        SettingsSwitchRow(
            title = "Scanner",
            checked = look.scannerEnabled,
            supportingText = "A lab scan of the film: levels, sharpening and scanner flare."
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.ScannerEnabled, it)) }
        SettingDivider()
        SettingsSwitchRow(
            title = "White correction",
            checked = look.scannerWhiteCorrection
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.ScannerWhiteCorrection, it)) }
        SettingDivider()
        SettingsSwitchRow(
            title = "Black correction",
            checked = look.scannerBlackCorrection
        ) { dispatch.invoke(SetFilmSimFlag(FilmSimFlag.ScannerBlackCorrection, it)) }
    }
    SettingsGroup(title = "Levels") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Scanner, "levels"), look, dispatch)
    }
    SettingsGroup(title = "Sharpness") {
        FilmSpecSliders(FilmSimCatalog.numerics(FilmSimSection.Scanner, "sharpness"), look, dispatch)
    }
    SettingsGroup(title = "Glare") {
        FilmSpecSliders(
            listOf(FilmSimSpecs.forParameter(FilmSimNumericParameter.GlarePercent)),
            look,
            dispatch
        )
    }
    SettingsGroup {
        SettingsRow(
            title = "Glare shaping",
            value = "Variation and spread",
            onClick = { dispatch.invoke(OpenFilmSimDetail(FilmSimDetail.GlareShaping)) }
        )
    }
}

@Composable
private fun GlareShapingPage(look: FilmSimLook, dispatch: SettingsDispatch) {
    SettingsGroup(title = "Shaping") {
        FilmSpecSliders(
            FilmSimCatalog.numerics(FilmSimSection.Scanner, "glare")
                .filter { it.parameter != FilmSimNumericParameter.GlarePercent },
            look,
            dispatch
        )
    }
}

@Composable
private fun FilmIntStepper(
    title: String,
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    SettingsRow(title = title, onClick = {}) {
        androidx.compose.material3.TextButton(
            onClick = { onValueChange((value - 1).coerceIn(range)) },
            enabled = value > range.first
        ) { androidx.compose.material3.Text("−") }
        androidx.compose.material3.Text(
            value.toString(),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        androidx.compose.material3.TextButton(
            onClick = { onValueChange((value + 1).coerceIn(range)) },
            enabled = value < range.last
        ) { androidx.compose.material3.Text("+") }
    }
}

@Composable
private fun FilmSpecSliders(specs: List<FilmSimNumericSpec>, look: FilmSimLook, dispatch: SettingsDispatch) {
    specs.forEachIndexed { index, spec ->
        FilmSimSlider(spec, look.numericValue(spec.parameter)) {
            dispatch.invoke(SetFilmSimNumericValue(spec.parameter, it))
        }
        if (index < specs.lastIndex) SettingDivider()
    }
}

@Composable
internal fun FilmSimSlider(spec: FilmSimNumericSpec, value: Float, onValueChange: (Float) -> Unit) {
    StandaloneNumericSliderRow(
        identity = spec.parameter.name,
        label = spec.label,
        supportingText = spec.supportingText.takeIf { it.isNotEmpty() },
        minimum = spec.minimum,
        maximum = spec.maximum,
        step = spec.step,
        decimals = spec.decimals,
        value = value,
        defaultValue = spec.defaultValue,
        valueFormatter = { formatFilmSimValue(spec, it) },
        onValueChange = onValueChange
    )
}

internal fun formatFilmSimValue(spec: FilmSimNumericSpec, value: Float): String {
    val number =
        if (spec.decimals == 0) {
            value.roundToInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.${spec.decimals}f", value)
        }
    val prefix = if (spec.minimum < 0f && value > 0f) "+" else ""
    return prefix + number + spec.unit
}
