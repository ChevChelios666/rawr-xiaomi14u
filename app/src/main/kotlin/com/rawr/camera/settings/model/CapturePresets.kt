package com.rawr.camera.settings.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * X14U presets (Nov 17): named sets of the current settings, like the stock camera's parameter presets. P0 is the
 * baseline every reset goes back to; P1, P2… follow in strip order (numbers go by position). A preset holds only
 * the groups chosen when it was saved; applying it leaves the rest as they are.
 */
enum class PresetGroup(val id: String, val label: String) {
    /** Exposure mode with its S / ISO / EV values, and the Rawr AE options. */
    Exposure("exposure", "Exposure"),
    WhiteBalance("wb", "White balance"),
    /** Lens, zoom (or the video crop) and aperture. */
    Lens("lens", "Lens and zoom"),
    /** Profile, tone (or AgX), highlights and tone mapping. */
    Look("look", "Look"),
    Film("film", "Film recipe"),
    /** HDR+ (multiframe) and its tuning, demosaic, denoise, sharpening, defringe, UltraHDR. */
    Processing("processing", "Merge and processing");

    companion object {
        fun fromId(id: String): PresetGroup? = entries.firstOrNull { it.id == id }
        /** Settings-only groups (no camera state): what P0 compares for its "changed" mark. */
        val settingsGroups = setOf(Look, Film, Processing)
    }
}

/** Exposure as the camera screen holds it: mode and the requested S / ISO / EV candidates. */
data class PresetExposure(
    val mode: String,
    val shutterId: String,
    val isoId: String,
    val evId: String,
    /** As shown when saved ("M · 1/15 · ISO 800"). */
    val label: String = ""
)

data class PresetWhiteBalance(val mode: String, val kelvin: Int, val tint: Int)

/** Lens, its total zoom (photo, chain units; 0 = the lens's own), video crop, and the aperture choice's label. */
data class PresetLens(val lensId: String, val zoom: Float, val videoCrop: Float, val aperture: String?)

/** The camera-screen part of a preset (not in SettingsValues). */
data class PresetCamera(
    val exposure: PresetExposure? = null,
    val whiteBalance: PresetWhiteBalance? = null,
    val lens: PresetLens? = null
)

data class CapturePreset(
    val id: String,
    val name: String,
    val groups: Set<PresetGroup>,
    /** The settings when saved, as backup-style preference JSON (see PresetSettingsCodec). */
    val settings: String,
    val camera: PresetCamera = PresetCamera()
) {
    val isBaseline: Boolean get() = id == BASELINE_ID

    companion object {
        const val BASELINE_ID = "p0"
        const val MAX_NAME = 16
        const val MAX_PRESETS = 20

        fun encodeList(list: List<CapturePreset>): String = JSONArray().apply {
            list.forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("groups", JSONArray(p.groups.map { it.id }))
                    put("settings", p.settings)
                    p.camera.exposure?.let { e ->
                        put("exposure", JSONObject().put("mode", e.mode).put("s", e.shutterId).put("iso", e.isoId).put("ev", e.evId)
                            .put("label", e.label))
                    }
                    p.camera.whiteBalance?.let { w ->
                        put("wb", JSONObject().put("mode", w.mode).put("k", w.kelvin).put("tint", w.tint))
                    }
                    p.camera.lens?.let { l ->
                        put("lens", JSONObject().put("id", l.lensId).put("zoom", l.zoom.toDouble())
                            .put("crop", l.videoCrop.toDouble()).apply { l.aperture?.let { put("f", it) } })
                    }
                })
            }
        }.toString()

        fun decodeList(raw: String?): List<CapturePreset> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                runCatching {
                    val o = array.getJSONObject(i)
                    val groups = o.optJSONArray("groups")?.let { g ->
                        (0 until g.length()).mapNotNull { PresetGroup.fromId(g.getString(it)) }.toSet()
                    }.orEmpty()
                    CapturePreset(
                        id = o.getString("id"),
                        name = o.optString("name").take(MAX_NAME),
                        groups = groups,
                        settings = o.optString("settings", "{}"),
                        camera = PresetCamera(
                            exposure = o.optJSONObject("exposure")?.let { e ->
                                PresetExposure(e.getString("mode"), e.getString("s"), e.getString("iso"), e.getString("ev"),
                                    e.optString("label"))
                            },
                            whiteBalance = o.optJSONObject("wb")?.let { w ->
                                PresetWhiteBalance(w.getString("mode"), w.getInt("k"), w.getInt("tint"))
                            },
                            lens = o.optJSONObject("lens")?.let { l ->
                                PresetLens(l.getString("id"), l.optDouble("zoom", 0.0).toFloat(),
                                    l.optDouble("crop", 1.0).toFloat(), l.optString("f").takeIf { it.isNotEmpty() })
                            }
                        )
                    )
                }.getOrNull()
            }.distinctBy { it.id }
        }
    }
}

/** P0 first (made from [fallback] when missing), then the others in order. */
fun List<CapturePreset>.withBaseline(fallback: () -> CapturePreset): List<CapturePreset> {
    val baseline = firstOrNull { it.isBaseline } ?: fallback()
    return listOf(baseline) + filterNot { it.isBaseline }
}

/** "P0", "P1"… by position. */
fun List<CapturePreset>.label(id: String): String = "P${indexOfFirst { it.id == id }.coerceAtLeast(0)}"

/**
 * The settings of [groups] taken from [snap] (a preset's saved settings, decoded) into these settings. A LUT, saved
 * AgX look or film preset deleted since the preset was saved is left as it is now.
 */
fun SettingsValues.withPresetGroups(snap: SettingsValues, groups: Set<PresetGroup>): SettingsValues {
    var v = this
    fun lut(id: String?, current: String?) = if (id == null || v.userLutProfiles.any { it.id == id }) id else current
    fun agxLook(id: String?, current: String?) = if (id == null || v.agxLooks.any { it.id == id }) id else current
    if (PresetGroup.Exposure in groups) v = v.copy(rawrAe = snap.rawrAe)
    if (PresetGroup.Look in groups) {
        val photoLut = lut(snap.selectedUserLutProfileId, v.selectedUserLutProfileId)
        val videoLut = lut(snap.videoUserLutProfileId, v.videoUserLutProfileId)
        v = v.copy(
            colorRenderProfile = if (snap.colorRenderProfile == ColorRenderProfile.UserLut && photoLut == null)
                v.colorRenderProfile else snap.colorRenderProfile,
            selectedUserLutProfileId = photoLut,
            agxPreset = snap.agxPreset,
            selectedAgxLookId = agxLook(snap.selectedAgxLookId, v.selectedAgxLookId),
            rawrBaseTone = snap.rawrBaseTone,
            srgbTone = snap.srgbTone,
            rec709Tone = snap.rec709Tone,
            agxTone = snap.agxTone,
            agxValues = snap.agxValues,
            imageTone = snap.imageTone,
            photoHighlightEnabled = snap.photoHighlightEnabled,
            photoHighlightMethod = snap.photoHighlightMethod,
            photoHighlightThreshold = snap.photoHighlightThreshold,
            photoHighlightCompression = snap.photoHighlightCompression,
            highlightProtection = snap.highlightProtection,
            photoToneMapping = snap.photoToneMapping,
            localToneShadowsEv = snap.localToneShadowsEv,
            localToneTuning = snap.localToneTuning,
            autoKeyExposure = snap.autoKeyExposure,
            videoColorRenderProfile = if (snap.videoColorRenderProfile == ColorRenderProfile.UserLut && videoLut == null)
                v.videoColorRenderProfile else snap.videoColorRenderProfile,
            videoUserLutProfileId = videoLut,
            videoAgxPreset = snap.videoAgxPreset,
            videoAgxLookId = agxLook(snap.videoAgxLookId, v.videoAgxLookId),
            videoLogEnabled = snap.videoLogEnabled,
            videoLogProfile = snap.videoLogProfile,
            videoAutoKeyExposure = snap.videoAutoKeyExposure,
            videoHighlightEnabled = snap.videoHighlightEnabled,
            videoHighlightMethod = snap.videoHighlightMethod,
            videoHighlightThreshold = snap.videoHighlightThreshold,
            videoHighlightCompression = snap.videoHighlightCompression
        )
    }
    if (PresetGroup.Film in groups) {
        val known = snap.selectedFilmPresetId == null || v.filmPresets.any { it.id == snap.selectedFilmPresetId } ||
            FilmFactoryPresets.all.any { it.id == snap.selectedFilmPresetId }
        v = v.copy(
            filmSimEnabled = snap.filmSimEnabled,
            filmSimLook = snap.filmSimLook,
            selectedFilmPresetId = if (known) snap.selectedFilmPresetId else v.selectedFilmPresetId
        )
    }
    if (PresetGroup.Processing in groups) v = v.copy(
        experimentalMultiframeEnabled = snap.experimentalMultiframeEnabled,
        multiframeBaseFrameMode = snap.multiframeBaseFrameMode,
        multiframeChromaDenoise = snap.multiframeChromaDenoise,
        multiframeRextureNativeRgb = snap.multiframeRextureNativeRgb,
        multiframeRextureDetailRestore = snap.multiframeRextureDetailRestore,
        multiframeTuning = snap.multiframeTuning,
        zoomMergeAlgorithm = snap.zoomMergeAlgorithm,
        demosaicAlgorithm = snap.demosaicAlgorithm,
        photoDenoise = snap.photoDenoise,
        photoCaptureSharpeningEnabled = snap.photoCaptureSharpeningEnabled,
        photoCaptureSharpeningLevel = snap.photoCaptureSharpeningLevel,
        photoFccSteps = snap.photoFccSteps,
        photoDefringeEnabled = snap.photoDefringeEnabled,
        photoDefringeStrength = snap.photoDefringeStrength,
        photoDefringeEdgeThreshold = snap.photoDefringeEdgeThreshold,
        photoDefringeLumaFloor = snap.photoDefringeLumaFloor,
        dualAutoContrast = snap.dualAutoContrast,
        dualContrastPercent = snap.dualContrastPercent,
        ultraHdrEnabled = snap.ultraHdrEnabled
    )
    return v
}

/** One line per group for the preset's details. */
fun SettingsValues.presetSummary(group: PresetGroup): String = when (group) {
    PresetGroup.Exposure -> if (rawrAe.enabled) "Rawr AE" else "Device AE"
    PresetGroup.WhiteBalance -> ""
    PresetGroup.Lens -> ""
    PresetGroup.Look -> activeProfileLabel()
    PresetGroup.Film -> if (!filmSimEnabled) "Off"
        else (filmPresets.firstOrNull { it.id == selectedFilmPresetId }?.name
            ?: FilmFactoryPresets.all.firstOrNull { it.id == selectedFilmPresetId }?.name ?: "On")
    PresetGroup.Processing -> buildList {
        add(if (experimentalMultiframeEnabled) "HDR+ on" else "HDR+ off")
        add(demosaicAlgorithm.label)
        if (photoDenoise !is DenoiseConfig.Off) add("denoise")
        if (photoCaptureSharpeningEnabled) add("sharpen ${photoCaptureSharpeningLevel.label.lowercase()}")
    }.joinToString(" · ")
}
