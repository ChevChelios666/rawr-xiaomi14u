package com.rawr.camera.model

import com.rawr.camera.settings.model.RawrMetering
import com.rawr.camera.settings.model.SettingsValues
import com.rawr.camera.settings.model.effectivePicks
import com.rawr.camera.settings.model.lockedRows
import com.rawr.camera.settings.model.overrideLens

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
    val options: List<com.rawr.camera.settings.model.LensOption>,
    /** Rows that don't apply now: in video mode, rows the lens's video lens lacks (DCG, 50 mp on 1x -> 1xV). */
    val unavailable: Set<Int> = emptySet()
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
    val locked: Boolean = false,
    /** X14U: every Rawr AE option, for the Exposure panel. */
    val rawrAe: com.rawr.camera.settings.model.RawrAeSettings = com.rawr.camera.settings.model.RawrAeSettings(),
    /** X14U: lenses with per-lens overrides (their own or their run-as lens's): a dot under the lens name. */
    val overrideLenses: Set<String> = emptySet(),
    /** X14U: the lens whose overrides apply now ("50MP" while 1x runs at 50 MP). */
    val overrideLens: String? = null,
    /** X14U: true in video mode (the DCP pill then edits the video DCP). */
    val video: Boolean = false,
    /** X14U: each lens's fixed f-number (LENS_INFO_AVAILABLE_APERTURES), for lenses without an Aperture row. */
    val nativeApertures: Map<String, Float> = emptyMap()
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
        dcps = (lensProfiles ?: deviceDefaults).map {
            DcpQuickState(it.name, it.image.availableDcps, (if (isVideo) it.image.videoDcp else it.image.dcp)?.file)
        },
        overrideLenses = (lensProfiles ?: deviceDefaults).let { lenses ->
            lenses.filter { lens ->
                lens.overrides.isNotEmpty() || lens.options.any { o ->
                    o.choices.any { c ->
                        val target = c.runAs?.trim()
                        target != null && lenses.any { it.name.trim().equals(target, ignoreCase = true) && it.overrides.isNotEmpty() }
                    }
                }
            }.map { it.name }.toSet()
        },
        overrideLens = overrideLens()?.name,
        video = isVideo,
        rawrAe = rawrAe,
        lensOptions = (lensProfiles ?: deviceDefaults).let { lenses ->
            lenses.filter { it.options.isNotEmpty() }.map { lens ->
                val video = lens.videoLens?.trim()?.takeIf { isVideo && it.isNotEmpty() }
                    ?.let { name -> lenses.firstOrNull { it.name.trim().equals(name, ignoreCase = true) } }
                val unavailable = video?.let { target ->
                    lens.options.indices.filter { row ->
                        target.options.none { it.name.trim().equals(lens.options[row].name.trim(), ignoreCase = true) }
                    }.toSet()
                }.orEmpty()
                LensOptionsQuickState(lens.name, lens.options, unavailable)
            }
        },
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
