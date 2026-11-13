package com.rawr.camera.video

import kotlin.math.roundToInt

/**
 * X14U video crop: which part of the sensor a recording uses and how far it is scaled down.
 *
 * Aspect: a shape cut from the full sensor width (Open gate = the whole sensor). Pixel crop: a centred window at one
 * sensor pixel per pixel (a lossless digital zoom). Either is then divided by the downscale; 2x runs the 2x2 Bayer
 * reduction, other steps a resample on the GPU. The native recorder gets the window ([sizes].first, through
 * setVideoCropSource) and the recording size ([sizes].second).
 */
enum class VideoCropKind { Aspect, Pixel }

data class VideoCropShape(val label: String, val chip: String, val ratio: Float)

data class VideoCrop(
    val kind: VideoCropKind = VideoCropKind.Pixel,
    val aspect: Int = 1,
    val pixel: Int = 0,
    val downscale: Int = 3
) {
    val shape: VideoCropShape get() = ASPECTS[aspect.coerceIn(0, ASPECTS.lastIndex)]
    val window: VideoFrameSize get() = PIXELS[pixel.coerceIn(0, PIXELS.lastIndex)]
    val factor: Float get() = DOWNSCALES[downscale.coerceIn(0, DOWNSCALES.lastIndex)]
    val isOpenGate: Boolean get() = kind == VideoCropKind.Aspect && shape.ratio == 0f

    /** Top bar label: the shape, or the window's height for a pixel crop. */
    val chipLabel: String get() = when (kind) {
        VideoCropKind.Aspect -> shape.chip
        VideoCropKind.Pixel -> "${window.height}p"
    }

    /** Sensor window and recording size for a RAW frame of [rawWidth]×[rawHeight]. */
    fun sizes(rawWidth: Int = lastRawWidth, rawHeight: Int = lastRawHeight): Pair<VideoFrameSize, VideoFrameSize> {
        val rw = rawWidth.coerceAtLeast(2)
        val rh = rawHeight.coerceAtLeast(2)
        var sw: Float
        var sh: Float
        when (kind) {
            VideoCropKind.Aspect -> {
                val r = shape.ratio
                if (r <= 0f) { sw = rw.toFloat(); sh = rh.toFloat() } else {
                    sw = rw.toFloat(); sh = rw / r
                    if (sh > rh) { sh = rh.toFloat(); sw = rh * r }
                }
            }
            VideoCropKind.Pixel -> {
                sw = window.width.toFloat(); sh = window.height.toFloat()
                // A smaller sensor (front camera): the largest window of the same shape.
                val fit = minOf(1f, rw / sw, rh / sh)
                sw *= fit; sh *= fit
            }
        }
        val d = factor
        // Width on a 16-pixel step (what encoders handle best, rounded down so the window still fits), height even and on the window's shape.
        val outWidth = if (d == 1f && kind == VideoCropKind.Pixel || isOpenGate && d == 1f) even(sw)
            else ((sw / d / 16f).toInt() * 16).coerceAtLeast(16)
        val outHeight = even(outWidth * sh / sw).coerceAtLeast(2)
        // The window exactly d times the output, so both axes scale alike (2x lands on the Bayer reduction).
        val srcWidth = even(outWidth * d).coerceAtMost(rw and -2)
        val srcHeight = even(outHeight * d).coerceAtMost(rh and -2)
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

    /** 60 fps needs a small output and a light demosaic (the 2x reduction, or a small window). */
    fun allowsSixty(): Boolean {
        val (source, output) = sizes()
        val outPixels = output.width.toLong() * output.height
        val srcPixels = source.width.toLong() * source.height
        return outPixels <= 2_200_000L && (factor >= 2f || srcPixels <= 2_200_000L)
    }

    fun persistedId(): String = "crop:${if (kind == VideoCropKind.Aspect) "a" else "p"}:$aspect:$pixel:$downscale"

    companion object {
        val ASPECTS = listOf(
            VideoCropShape("Open gate", "OPEN", 0f),
            VideoCropShape("16:9", "16:9", 16f / 9f),
            VideoCropShape("1.85:1", "1.85", 1.85f),
            VideoCropShape("2:1", "2:1", 2f),
            VideoCropShape("2.39:1", "2.39", 2.39f),
            VideoCropShape("1:1", "1:1", 1f)
        )
        val PIXELS = listOf(
            VideoFrameSize(3840, 2160), VideoFrameSize(2560, 1440), VideoFrameSize(1920, 1080), VideoFrameSize(1280, 720)
        )
        val DOWNSCALES = listOf(1f, 1.25f, 1.5f, 2f, 3f)

        /** The former 1080p: the 3840×2160 window, 2x reduced (exactly as before). */
        val Default = VideoCrop(VideoCropKind.Pixel, aspect = 1, pixel = 0, downscale = 3)

        /** RAW size of the active camera, for the panel and the 60 fps rule (X14U back cameras: 4096×3072). */
        @Volatile var lastRawWidth: Int = 4096
        @Volatile var lastRawHeight: Int = 3072

        fun fromLegacy(mode: VideoResolutionMode): VideoCrop = when (mode) {
            VideoResolutionMode.HD1080 -> Default
            VideoResolutionMode.UHD4K -> VideoCrop(VideoCropKind.Pixel, pixel = 0, downscale = 0)
            VideoResolutionMode.OPEN_GATE -> VideoCrop(VideoCropKind.Aspect, aspect = 0, downscale = 0)
        }

        /** Saved id: "crop:a|p:aspect:pixel:downscale", or a former resolution id ("1080p", "4k", "open_gate"). */
        fun fromId(id: String?): VideoCrop {
            val parts = id?.split(':') ?: return Default
            if (parts.size != 5 || parts[0] != "crop") return when (id) {
                "4k" -> fromLegacy(VideoResolutionMode.UHD4K)
                "open_gate" -> fromLegacy(VideoResolutionMode.OPEN_GATE)
                else -> Default
            }
            return VideoCrop(
                kind = if (parts[1] == "a") VideoCropKind.Aspect else VideoCropKind.Pixel,
                aspect = parts[2].toIntOrNull()?.coerceIn(0, ASPECTS.lastIndex) ?: 1,
                pixel = parts[3].toIntOrNull()?.coerceIn(0, PIXELS.lastIndex) ?: 0,
                downscale = parts[4].toIntOrNull()?.coerceIn(0, DOWNSCALES.lastIndex) ?: 3
            )
        }

        private fun even(v: Float): Int = (v.roundToInt() / 2) * 2
    }
}
