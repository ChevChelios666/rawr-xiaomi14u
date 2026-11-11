package com.rawr.camera.settings.model

/** X14U focus loupe (Settings → Display & Control → Monitoring). */
data class FocusLoupeSettings(
    /** Magnification: 3, 5 or 8. */
    val zoom: Int = 5,
    /** Opens while the MF ruler is dragged, closes about a second after release. */
    val onManualFocus: Boolean = true,
    /** Double-tap on the viewfinder opens / closes it (taps then wait a moment for a second tap). */
    val doubleTap: Boolean = true,
    /** Focus quality bar with peak hold. */
    val meter: Boolean = true
) {
    companion object {
        val ZOOMS = listOf(3, 5, 8)
    }
}
