package com.rawr.camera.video

import kotlin.math.roundToInt

/**
 * X14U video crop: a standard size and a shape, nothing in between (the in-between downscales and full-width windows
 * cost more GPU time than a recording has, Nov 2026).
 *  - 4K: the centred 3840×2160 window at one sensor pixel per pixel, 30 fps.
 *  - 1080p: the same window, 2x2 Bayer reduced to 1920×1080; the one size with 60 fps.
 *  - Open gate: the whole RAW frame, 30 fps.
 * 4K and 1080p take a shape: 16:9, or a wider one cut from its height (always lighter than 16:9).
 * The native recorder gets the window ([sizes].first, through setVideoCropSource) and the recording size
 * ([sizes].second); a digital focal length shrinks the window (the GPU resamples it to the recording size).
 */
enum class VideoCropSize(val label: String, val chip: String) {
    UHD("4K", "4K"),
    HD("1080p", "1080p"),
    OpenGate("Open gate", "OPEN")
}

data class VideoCropShape(val label: String, val chip: String, val ratio: Float, val name: String)

data class VideoCrop(
    val size: VideoCropSize = VideoCropSize.HD,
    val shape: Int = 0,
    /** Digital focal length on top of the lens (1 = none): the window shrinks, the recording size stays. */
    val digitalZoom: Float = 1f
) {
    val shapeInfo: VideoCropShape get() = SHAPES[shape.coerceIn(0, SHAPES.lastIndex)]
    val isOpenGate: Boolean get() = size == VideoCropSize.OpenGate
    /** Sensor pixels per recorded pixel: 2 for 1080p (the Bayer reduction), else 1. */
    val factor: Float get() = if (size == VideoCropSize.HD) 2f else 1f

    /** Top bar label: "4K", "1080p · 2.39", "OPEN". */
    val chipLabel: String get() = when {
        isOpenGate -> size.chip
        shape == 0 -> size.chip
        else -> "${size.chip} · ${shapeInfo.chip}"
    }

    /** Sensor window and recording size for a RAW frame of [rawWidth]×[rawHeight]. */
    fun sizes(rawWidth: Int = lastRawWidth, rawHeight: Int = lastRawHeight): Pair<VideoFrameSize, VideoFrameSize> {
        val rw = rawWidth.coerceAtLeast(2)
        val rh = rawHeight.coerceAtLeast(2)
        val zoom = digitalZoom.coerceIn(1f, 8f)
        if (isOpenGate) {
            val out = VideoFrameSize(rw and -2, rh and -2)
            return VideoFrameSize(even(out.width / zoom).coerceAtLeast(64), even(out.height / zoom).coerceAtLeast(64)) to out
        }
        // The 16:9 window; a smaller sensor (front camera) gets the largest one that fits.
        val fit = minOf(1f, rw / 3840f, rh / 2160f)
        val sw = 3840f * fit
        val sh = sw / shapeInfo.ratio
        val d = factor
        // Width on a 16-pixel step (what encoders handle best), height even and on the shape.
        val outWidth = if (d == 1f) even(sw) else ((sw / d / 16f).toInt() * 16).coerceAtLeast(16)
        val outHeight = even(outWidth * sh / sw).coerceAtLeast(2)
        // The window exactly d times the output, so both axes scale alike (2x lands on the Bayer reduction).
        val srcWidth = even(outWidth * d / zoom).coerceIn(64, rw and -2)
        val srcHeight = even(outHeight * d / zoom).coerceIn(64, rh and -2)
        return VideoFrameSize(srcWidth, srcHeight) to VideoFrameSize(outWidth, outHeight)
    }

    /** Bitrate class (Settings → Video encoder keeps one bitrate per class). */
    fun bitrateClass(output: VideoFrameSize = sizes().second): VideoResolutionMode {
        val pixels = output.width.toLong() * output.height
        return when {
            pixels <= 2_600_000L -> VideoResolutionMode.HD1080
            pixels <= 9_000_000L -> VideoResolutionMode.UHD4K
            else -> VideoResolutionMode.OPEN_GATE
        }
    }

    /** 60 fps: 1080p only (the 2x reduction is the one demosaic light enough, with the scopes on too). */
    fun allowsSixty(): Boolean = size == VideoCropSize.HD

    fun persistedId(): String = "crop2:${size.name}:$shape"

    companion object {
        val SHAPES = listOf(
            VideoCropShape("16:9", "16:9", 16f / 9f, "standard"),
            VideoCropShape("1.85:1", "1.85", 1.85f, "flat"),
            VideoCropShape("2:1", "2:1", 2f, "univisium"),
            VideoCropShape("2.39:1", "2.39", 2.39f, "scope")
        )

        val Default = VideoCrop(VideoCropSize.HD)

        /** RAW size of the active camera, for the panel (X14U back cameras: 4096×3072, 1xV video 4096×2304). */
        @Volatile var lastRawWidth: Int = 4096
        @Volatile var lastRawHeight: Int = 3072

        fun fromLegacy(mode: VideoResolutionMode): VideoCrop = when (mode) {
            VideoResolutionMode.HD1080 -> Default
            VideoResolutionMode.UHD4K -> VideoCrop(VideoCropSize.UHD)
            VideoResolutionMode.OPEN_GATE -> VideoCrop(VideoCropSize.OpenGate)
        }

        /**
         * Saved id: "crop2:size:shape"; the first crop panel's "crop:a|p:aspect:pixel:downscale" (to the nearest
         * standard size); or a former resolution id ("1080p", "4k", "open_gate").
         */
        fun fromId(id: String?): VideoCrop {
            val parts = id?.split(':') ?: return Default
            if (parts.size == 3 && parts[0] == "crop2") {
                return VideoCrop(
                    size = VideoCropSize.entries.firstOrNull { it.name == parts[1] } ?: VideoCropSize.HD,
                    shape = parts[2].toIntOrNull()?.coerceIn(0, SHAPES.lastIndex) ?: 0
                )
            }
            if (parts.size == 5 && parts[0] == "crop") {
                val aspectKind = parts[1] == "a"
                val aspect = parts[2].toIntOrNull() ?: 1
                val pixel = parts[3].toIntOrNull() ?: 0
                val downscale = listOf(1f, 1.25f, 1.5f, 2f, 3f).getOrElse(parts[4].toIntOrNull() ?: 3) { 2f }
                if (aspectKind && aspect == 0) return VideoCrop(VideoCropSize.OpenGate)
                val window = if (aspectKind) 4096 else listOf(3840, 2560, 1920, 1280).getOrElse(pixel) { 3840 }
                val size = if (window / downscale > 2600f) VideoCropSize.UHD else VideoCropSize.HD
                // Former shapes: Open gate, 16:9, 1.85, 2:1, 2.39, 1:1 (1:1 is gone: 16:9).
                val shape = if (aspectKind && aspect in 2..4) aspect - 1 else 0
                return VideoCrop(size, shape)
            }
            return when (id) {
                "4k" -> fromLegacy(VideoResolutionMode.UHD4K)
                "open_gate" -> fromLegacy(VideoResolutionMode.OPEN_GATE)
                else -> Default
            }
        }

        private fun even(v: Float): Int = (v.roundToInt() / 2) * 2
    }
}
