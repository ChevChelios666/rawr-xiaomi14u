package com.rawr.camera.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** X14U capture-screen shortcuts that open Settings pages or flip a setting, provided by the activity. */
data class CaptureExtras(
    val onToggleAudioMuted: () -> Unit = {},
    val onOpenVideoSettings: () -> Unit = {},
    val onOpenExposureSettings: () -> Unit = {},
    /** Photo mode on the new (Pro) screen rather than the classic Photo screen. */
    val proUi: Boolean = true,
    val onSetProUi: (Boolean) -> Unit = {},
    /** X14U tools panel (swipe down on the viewfinder). */
    val tools: com.rawr.camera.CaptureViewModel.ToolsState = com.rawr.camera.CaptureViewModel.ToolsState(),
    val onSetPhotoAspect: (com.rawr.camera.settings.model.PhotoAspect) -> Unit = {},
    val onToggleLevel: () -> Unit = {},
    val onSetLocationTagging: (Boolean) -> Unit = {},
    val onToggleLensMirror: (String) -> Unit = {}
)

val LocalCaptureExtras = staticCompositionLocalOf { CaptureExtras() }
