package com.rawr.camera.model

import com.rawr.camera.settings.model.RawrMetering
import com.rawr.camera.settings.model.SettingsValues
import com.rawr.camera.settings.model.effectivePicks
import com.rawr.camera.settings.model.lockedRows

/** Highlight reconstruction as the viewfinder rail presents it: off, or one of the two methods. */
enum class HighlightQuickMode { Off, Propagation, Opposed }

/** Photo or video highlight reconstruction, whichever the current capture mode renders with. */
data class HighlightQuickState(
    val mode: HighlightQuickMode = HighlightQuickMode.Off,
    val threshold: Float = HIGHLIGHT_THRESHOLD_DEFAULT,
    val compression: Float = PHOTO_HIGHLIGHT_COMPRESSION_DEFAULT,
    val compressionDefault: Float = PHOTO_HIGHLIGHT_COMPRESSION_DEFAULT
)

data class DcpQuickState(
    val lensId: String,
    val profiles: List<com.rawr.camera.settings.model.LensDcp>,
    val selectedFile: String?
)

/** A lens's option rows as the capture screen shows them (X14U build). */
data class LensOptionsQuickState(
    val lensId: String,
    val options: List<com.rawr.camera.settings.model.LensOption>
) {
    /** The choice each row runs with (a locked row shows its locked choice). */
    val picks: List<Int> get() = options.effectivePicks()

    /** Rows another chosen choice locks (e.g. Aperture while Mode is 50MP). */
    val locked: Set<Int> get() = options.lockedRows(options.map { it.selected }).keys

    val summary: String get() =
        picks.mapIndexedNotNull { row, pick -> options[row].choices.getOrNull(pick)?.label }.joinToString(" · ")
}

/** Capture-screen projection of the settings the compact viewfinder rail edits. */
data class ViewfinderQuickState(
    val rawrAeEnabled: Boolean = false,
    val metering: RawrMetering = RawrMetering.Matrix,
    val highlight: HighlightQuickState = HighlightQuickState(),
    val dcps: List<DcpQuickState> = emptyList(),
    /** Lenses that have option rows. */
    val lensOptions: List<LensOptionsQuickState> = emptyList(),
    /** Recording locks the rail like it locks the render profile. */
    val locked: Boolean = false
)

internal const val HIGHLIGHT_THRESHOLD_DEFAULT = 1f
internal const val PHOTO_HIGHLIGHT_COMPRESSION_DEFAULT = 45f
internal const val VIDEO_HIGHLIGHT_COMPRESSION_DEFAULT = 100f

internal fun SettingsValues.toViewfinderQuickState(
    deviceDefaults: List<com.rawr.camera.settings.model.LensProfile> = emptyList()
): ViewfinderQuickState {
    val enabled = if (isVideo) videoHighlightEnabled else photoHighlightEnabled
    val method = if (isVideo) videoHighlightMethod else photoHighlightMethod
    val compressionDefault =
        if (isVideo) VIDEO_HIGHLIGHT_COMPRESSION_DEFAULT else PHOTO_HIGHLIGHT_COMPRESSION_DEFAULT
    return ViewfinderQuickState(
        dcps = (lensProfiles ?: deviceDefaults).map { DcpQuickState(it.name, it.image.availableDcps, it.image.dcp?.file) },
        lensOptions = (lensProfiles ?: deviceDefaults).filter { it.options.isNotEmpty() }
            .map { LensOptionsQuickState(it.name, it.options) },
        rawrAeEnabled = rawrAe.enabled,
        metering = rawrAe.metering,
        highlight = HighlightQuickState(
            mode = when {
                !enabled -> HighlightQuickMode.Off
                method == 1 -> HighlightQuickMode.Opposed
                else -> HighlightQuickMode.Propagation
            },
            threshold = if (isVideo) videoHighlightThreshold else photoHighlightThreshold,
            compression = if (isVideo) videoHighlightCompression else photoHighlightCompression,
            compressionDefault = compressionDefault
        )
    )
}
