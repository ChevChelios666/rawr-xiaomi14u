package com.rawr.camera.architecture

import com.rawr.camera.model.HighlightQuickMode
import com.rawr.camera.settings.model.RawrMetering

/**
 * Typed commands for the compact viewfinder rail. Highlight events edit the
 * photo or video set, whichever the current capture mode renders with.
 */
sealed interface CaptureQuickEvent {
    data class SelectDcp(val lensId: String, val file: String?) : CaptureQuickEvent
    /** Lens options (X14U build): choice [choice] in option row [row] of lens [lensId]. */
    data class SelectLensOption(val lensId: String, val row: Int, val choice: Int) : CaptureQuickEvent
    data object ToggleRawrAe : CaptureQuickEvent
    data class SetMetering(val metering: RawrMetering) : CaptureQuickEvent
    data class SetHighlightMode(val mode: HighlightQuickMode) : CaptureQuickEvent
    data class SetHighlightThreshold(val value: Float) : CaptureQuickEvent
    data class SetHighlightCompression(val value: Float) : CaptureQuickEvent
    /** X14U Exposure panel: one Rawr AE option (Night Look, balance, slowest shutter, digital gain). */
    data class SetRawrAeOption(val edit: (com.rawr.camera.settings.model.RawrAeSettings) -> com.rawr.camera.settings.model.RawrAeSettings) :
        CaptureQuickEvent
}
