package com.rawr.camera.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** X14U capture-screen shortcuts that open Settings pages or flip a setting, provided by the activity. */
data class CaptureExtras(
    val onToggleAudioMuted: () -> Unit = {},
    val onOpenVideoSettings: () -> Unit = {},
    val onOpenExposureSettings: () -> Unit = {},
    /** Photo mode on the new (Pro) screen rather than the classic Photo screen. */
    val proUi: Boolean = true,
    val onSetProUi: (Boolean) -> Unit = {}
)

val LocalCaptureExtras = staticCompositionLocalOf { CaptureExtras() }
