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

/** The real-time profile (Settings → Video Encoder → Real-time profiles) for the current video size and frame rate. */
fun SettingsValues.videoProfileMode(): VideoProfileMode =
    VideoProfileMode.of(VideoCrop.fromId(videoResolutionId).size, com.rawr.camera.settings.model.videoFpsOrDefault(videoFps))

/** Whether [feature] runs in the current video mode (its main Video switch is checked separately). */
fun SettingsValues.videoProfileAllows(feature: VideoProfileFeature): Boolean =
    VideoProfiles.decode(videoProfiles).allows(videoProfileMode(), feature)

fun SettingsValues.toVideoImageSettings(): VideoImageSettings {
    val profile = VideoProfiles.decode(videoProfiles)
    val mode = videoProfileMode()
    fun on(feature: VideoProfileFeature) = profile.allows(mode, feature)
    return VideoImageSettings(
        videoLensShadingEnabled,
        videoHighlightEnabled && on(VideoProfileFeature.Highlight),
        videoHighlightMethod,
        videoHighlightThreshold,
        videoHighlightCompression,
        // Video FCC is a single step when enabled.
        if (videoFccEnabled && on(VideoProfileFeature.Fcc)) 1 else 0,
        if (videoDefringeEnabled && on(VideoProfileFeature.Defringe)) videoDefringeStrength else 0f,
        if (videoDenoiseEnabled && on(VideoProfileFeature.Denoise)) videoDenoiseStrength else 0f,
        videoDenoiseDetail,
        videoDenoiseLuma,
        videoDenoiseScales,
        videoDistortionCorrectionEnabled && on(VideoProfileFeature.LensCorrection)
    )
}
