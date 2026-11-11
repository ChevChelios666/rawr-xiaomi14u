package com.rawr.camera.settings.model

/**
 * X14U photo aspect ratio, long side : short side (it follows how the phone is held). JPEGs are cut to it; DNGs keep
 * every pixel with it as their default crop. Names are persisted.
 */
enum class PhotoAspect(val label: String, val ratio: Float) {
    Native("Native", 0f),
    Square("1:1", 1f),
    R7x6("7:6", 7f / 6f),
    R5x4("5:4", 5f / 4f),
    R4x3("4:3", 4f / 3f),
    R7x5("7:5", 7f / 5f),
    R3x2("3:2", 3f / 2f),
    R16x9("16:9", 16f / 9f),
    Flat("1.85:1", 1.85f),
    R2x1("2:1", 2f),
    Scope("2.39:1", 2.39f),
    XPan("XPan", 65f / 24f),
    R17x6("17:6", 17f / 6f),
    R3x1("3:1", 3f);

    /** Long:short sent to the camera; 0 = uncropped. */
    val nativeRatio: Float get() = ratio
}
