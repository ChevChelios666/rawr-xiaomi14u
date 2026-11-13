package com.rawr.camera.settings.model

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
