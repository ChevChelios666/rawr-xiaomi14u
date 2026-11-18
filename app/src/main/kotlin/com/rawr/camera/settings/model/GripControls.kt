package com.rawr.camera.settings.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Xiaomi 14 Ultra Photography Kit: what the grip's dial turns (read from its raw USB reports). */
enum class GripDial(val label: String, val key: String) {
    Ev("EV", "ev"),
    Shutter("Shutter", "ss"),
    Iso("ISO", "iso"),
    WhiteBalance("White balance", "wb"),
    Aperture("Aperture", "f"),
    Focus("Focus", "af"),
    Zoom("Zoom", "zoom")
}

/**
 * X14U (Nov 19): what the grip's zoom lever does in Photo and Pro: step along the lens pill (as tapping it), or zoom
 * smoothly while held (as the long-press zoom ruler). Video and Pano always step. Kept across launches.
 */
object GripLeverSettings {
    enum class Mode(val label: String) { Lenses("Lens steps"), Zoom("Smooth zoom") }

    private var prefs: android.content.SharedPreferences? = null

    var mode by androidx.compose.runtime.mutableStateOf(Mode.Lenses)
        private set

    fun init(context: android.content.Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("x14u_grip_lever", android.content.Context.MODE_PRIVATE)
        prefs = p
        mode = Mode.entries.firstOrNull { it.name == p.getString("mode", null) } ?: Mode.Lenses
    }

    fun select(next: Mode) {
        mode = next
        prefs?.edit()?.putString("mode", next.name)?.apply()
    }
}
