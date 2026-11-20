package com.rawr.camera.video

import com.rawr.camera.settings.model.SettingsValues
import org.json.JSONObject

/** Value passed through the UI/native boundary. Video can later persist its own copy. */
data class VideoImageSettings(
    val lensShadingEnabled: Boolean,
    val highlightEnabled: Boolean,
    val highlightMethod: Int,
    val highlightThreshold: Float,
    val highlightCompression: Float,
    val fccSteps: Int,
    val defringeStrength: Float,
    val waveletDenoiseStrength: Float,
    val waveletDenoiseDetail: Float,
    val waveletDenoiseLuma: Float,
    val waveletDenoiseScales: Int,
    /** Undistort with the lens profile calibration (lenses that have one). */
    val distortionEnabled: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("lensShadingEnabled", lensShadingEnabled)
        .put("highlightEnabled", highlightEnabled)
        .put("highlightMethod", highlightMethod)
        .put("highlightThreshold", highlightThreshold)
        .put("highlightCompression", highlightCompression)
        .put("fccSteps", fccSteps)
        .put("defringeStrength", defringeStrength)
        .put("waveletDenoiseStrength", waveletDenoiseStrength)
        .put("waveletDenoiseDetail", waveletDenoiseDetail)
        .put("waveletDenoiseLuma", waveletDenoiseLuma)
        .put("waveletDenoiseScales", waveletDenoiseScales)
        .put("distortionEnabled", distortionEnabled)
}

/**
 * X14U (Nov 20) real-time video profiles, from 4K30 / 1080p30 / 1080p60 takes on the phone: which of the video
 * processing features fit the frame time. The user's settings stay as set; a feature that doesn't fit the current
 * size and frame rate is silently left out (Settings says where each applies).
 * - 1080p 30 fps: highlight reconstruction (either method), FCC and defringe
 * - 1080p 60 fps: highlight reconstruction only (~13 of 16.7 ms with it; with defringe the encoder dropped frames;
 *   FCC isn't measured at 60, so it stays off to keep a margin for a warm phone)
 * - 4K and Open Gate: none (each alone dropped frames at 4K30; base processing is ~28 of 33 ms). 4K60 also skips DCP,
 *   lens correction, horizon lock and stabilisation (native, VideoRecorder: it records through the fast path)
 * - denoise: never in video (~110 ms a frame even at 1080p)
 */
data class VideoFeatureFit(val highlight: Boolean, val defringe: Boolean, val denoise: Boolean, val fcc: Boolean)

fun videoFeatureFit(size: VideoCropSize, fps: Int): VideoFeatureFit = when {
    size != VideoCropSize.HD -> VideoFeatureFit(highlight = false, defringe = false, denoise = false, fcc = false)
    fps > 30 -> VideoFeatureFit(highlight = true, defringe = false, denoise = false, fcc = false)
    else -> VideoFeatureFit(highlight = true, defringe = true, denoise = false, fcc = true)
}

fun SettingsValues.videoFeatureFit(): VideoFeatureFit =
    videoFeatureFit(VideoCrop.fromId(videoResolutionId).size, videoFpsOrDefaultValue())

private fun SettingsValues.videoFpsOrDefaultValue(): Int =
    com.rawr.camera.settings.model.videoFpsOrDefault(videoFps)

fun SettingsValues.toVideoImageSettings(): VideoImageSettings {
    val fit = videoFeatureFit()
    return VideoImageSettings(
        videoLensShadingEnabled,
        videoHighlightEnabled && fit.highlight,
        videoHighlightMethod,
        videoHighlightThreshold,
        videoHighlightCompression,
        // Video FCC is a single step when enabled.
        if (videoFccEnabled && fit.fcc) 1 else 0,
        if (videoDefringeEnabled && fit.defringe) videoDefringeStrength else 0f,
        if (videoDenoiseEnabled && fit.denoise) videoDenoiseStrength else 0f,
        videoDenoiseDetail,
        videoDenoiseLuma,
        videoDenoiseScales,
        videoDistortionCorrectionEnabled
    )
}
