package com.rawr.camera.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** X14U capture-screen shortcuts that open Settings pages or flip a setting, provided by the activity. */
data class CaptureExtras(
    val onToggleAudioMuted: () -> Unit = {},
    val onOpenVideoSettings: () -> Unit = {},
    val onOpenExposureSettings: () -> Unit = {},
    /** Photo+ (the full-control stills mode) rather than Photo. */
    val proUi: Boolean = true,
    val onSetProUi: (Boolean) -> Unit = {},
    /** X14U tools panel (swipe down on the viewfinder). */
    val tools: com.rawr.camera.CaptureViewModel.ToolsState = com.rawr.camera.CaptureViewModel.ToolsState(),
    val onSetPhotoAspect: (com.rawr.camera.settings.model.PhotoAspect) -> Unit = {},
    val onToggleLevel: () -> Unit = {},
    /** Video horizon Lock / Follow (the Level tile); either shows the level too. */
    val onSetHorizonMode: (lock: Boolean, follow: Boolean) -> Unit = { _, _ -> },
    /** Video stabilisation (the Stabilise tile). */
    val onSetStabilize: (com.rawr.camera.settings.model.VideoStabilize) -> Unit = {},
    val onSetLocationTagging: (Boolean) -> Unit = {},
    val onToggleLensMirror: (String) -> Unit = {},
    /** Show the one-time "Swipe up for Photo+" hint (until switched once, at most twice). */
    val stillsHint: Boolean = false,
    val onStillsHintShown: () -> Unit = {},
    /** Digital zoom crop on top of the lens (1 = none). */
    val onDigitalZoom: (Float) -> Unit = {},
    /** Pano mode (after Video): stills run as Photo with the Pano guide. */
    val pano: Boolean = false,
    val onSetPano: (Boolean) -> Unit = {},
    /** Delete the files of a Pano frame by its name tag. */
    val onPanoUndo: (String) -> Unit = {},
    /** A Pano set ended: merge its frames (tag, frame count, start time) when the setting is on. */
    val onPanoMerge: (tag: String, frames: Int, startedAtMillis: Long) -> Unit = { _, _, _ -> },
    /** Pano merge progress / result for the viewfinder (null = nothing to show). */
    val panoStatus: String? = null
)

val LocalCaptureExtras = staticCompositionLocalOf { CaptureExtras() }
