package com.rawr.camera.settings.model

/**
 * X14U: each capture mode (Photo, Pro, Video) keeps its own Look and Exposure-panel settings. The live settings
 * hold the active mode's set; switching mode saves the set into [SettingsValues.x14uModeLooks] under the mode being
 * left and loads the entered mode's set (a mode without one keeps the current values, so it starts as a copy).
 * Per-lens overrides still apply on top.
 */
data class ModeLook(
    val colorRenderProfile: ColorRenderProfile,
    val selectedUserLutProfileId: String?,
    val agxPreset: AgxPreset,
    val selectedAgxLookId: String?,
    val rawrBaseTone: ProfileTone,
    val srgbTone: ProfileTone,
    val rec709Tone: ProfileTone,
    val agxTone: ProfileTone,
    val agxValues: Map<AgxPreset, AgxValues>,
    val photoHighlightEnabled: Boolean,
    val photoHighlightMethod: Int,
    val photoHighlightThreshold: Float,
    val photoHighlightCompression: Float,
    val filmSimEnabled: Boolean,
    val filmSimLook: FilmSimLook,
    val selectedFilmPresetId: String?,
    val rawrAe: RawrAeSettings
)

object ModeLooks {
    const val PHOTO = "photo"
    const val PRO = "pro"
    const val VIDEO = "video"
}

/** The mode whose Look / Exposure set is live. */
fun SettingsValues.lookMode(): String = when {
    isVideo -> ModeLooks.VIDEO
    x14uProUi -> ModeLooks.PRO
    else -> ModeLooks.PHOTO
}

fun SettingsValues.modeLook(): ModeLook = ModeLook(
    colorRenderProfile = colorRenderProfile,
    selectedUserLutProfileId = selectedUserLutProfileId,
    agxPreset = agxPreset,
    selectedAgxLookId = selectedAgxLookId,
    rawrBaseTone = rawrBaseTone,
    srgbTone = srgbTone,
    rec709Tone = rec709Tone,
    agxTone = agxTone,
    agxValues = agxValues,
    photoHighlightEnabled = photoHighlightEnabled,
    photoHighlightMethod = photoHighlightMethod,
    photoHighlightThreshold = photoHighlightThreshold,
    photoHighlightCompression = photoHighlightCompression,
    filmSimEnabled = filmSimEnabled,
    filmSimLook = filmSimLook,
    selectedFilmPresetId = selectedFilmPresetId,
    rawrAe = rawrAe
)

fun SettingsValues.withModeLook(l: ModeLook): SettingsValues = copy(
    colorRenderProfile = l.colorRenderProfile,
    // A LUT or saved AgX look deleted since the set was saved falls back to the current choice.
    selectedUserLutProfileId = l.selectedUserLutProfileId?.takeIf { id -> userLutProfiles.any { it.id == id } },
    agxPreset = l.agxPreset,
    selectedAgxLookId = l.selectedAgxLookId?.takeIf { id -> agxLooks.any { it.id == id } },
    rawrBaseTone = l.rawrBaseTone,
    srgbTone = l.srgbTone,
    rec709Tone = l.rec709Tone,
    agxTone = l.agxTone,
    agxValues = l.agxValues,
    photoHighlightEnabled = l.photoHighlightEnabled,
    photoHighlightMethod = l.photoHighlightMethod,
    photoHighlightThreshold = l.photoHighlightThreshold,
    photoHighlightCompression = l.photoHighlightCompression,
    filmSimEnabled = l.filmSimEnabled,
    filmSimLook = l.filmSimLook,
    selectedFilmPresetId = l.selectedFilmPresetId,
    rawrAe = l.rawrAe
).let { s ->
    if (s.colorRenderProfile == ColorRenderProfile.UserLut && s.selectedUserLutProfileId == null)
        s.copy(colorRenderProfile = colorRenderProfile, selectedUserLutProfileId = selectedUserLutProfileId)
    else s
}

/**
 * After an edit that may have switched mode ([before] -> this): save the left mode's set and load the entered one's.
 */
fun SettingsValues.withModeLookSwitch(before: SettingsValues): SettingsValues {
    val from = before.lookMode()
    val to = lookMode()
    if (from == to) return this
    val saved = x14uModeLooks + (from to before.modeLook())
    val target = saved[to]
    return (if (target != null) withModeLook(target) else this).copy(x14uModeLooks = saved)
}
