package com.rawr.camera.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** X14U capture-screen shortcuts that open Settings pages or flip a setting, provided by the activity. */
data class CaptureExtras(
    val onToggleAudioMuted: () -> Unit = {},
    val onOpenVideoSettings: () -> Unit = {},
    val onOpenExposureSettings: () -> Unit = {}
)

val LocalCaptureExtras = staticCompositionLocalOf { CaptureExtras() }
