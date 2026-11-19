#pragma once

#include <algorithm>
#include <array>
#include <optional>

#include "camera/CameraControlTypes.h"

namespace rawrcam::camera {

// Bracket requests keep the recipe's cadence too; legacy lenses retain 30 fps.
inline int64_t cameraBracketFrameDuration(const CameraControlState& state, int64_t exposureNs) {
    const int64_t period = state.capabilities.frameRate > 0
                              ? 1'000'000'000LL / state.capabilities.frameRate : 33'333'333LL;
    const int64_t duration = std::max(period, exposureNs);
    return state.capabilities.maxFrameDurationNs > 0
               ? std::min(duration, state.capabilities.maxFrameDurationNs) : duration;
}

// Shared by session parameters, repeating requests and frozen provenance.
inline std::optional<std::array<int32_t, 2>> cameraTargetFpsRange(const CameraControlState& state,
                                                                  bool priorityRequested) {
    // A vendor sensor mode that runs at one rate (Xiaomi 75 mm 50 MP: 24 fps) keeps it on every request.
    if (state.capabilities.frameRate > 0)
        return std::array<int32_t, 2>{state.capabilities.frameRate, state.capabilities.frameRate};
    // X14U test: 60 fps is not in the advertised AE ranges, but the QTI stream-based FPS table lists
    // 4096x2304 @ 60. The HAL picks the sensor mode from the session's AE range at configure time, so
    // video mode at 60 asks for 60/60 on the session and on every request.
    if (state.capabilities.sixtyFpsCapable() &&
        (state.recordingFps == 60 || (state.recordingFps == 0 && state.videoMode && state.videoPreviewFps == 60)))
        return std::array<int32_t, 2>{60, 60};
    if (state.recordingFps > 0) return std::array<int32_t, 2>{state.recordingFps, state.recordingFps};
    const auto& caps = state.capabilities;
    // Video mode, not recording yet: already at the recording rate. The X14U HAL takes the AE target range as a
    // session parameter, so idle video at 24..30 and a take at 30..30 made the camera service rebuild the session
    // at every start and stop (a ~250 ms sensor pause = 7-8 frames lost at the start of each take; diagnostics
    // 20261119_142936). Same as the 60 fps rule above, at 30 only: idle video at a fixed 24/24 killed the camera
    // (device error 1 a second after choosing 24 fps, again on every restart; diagnostics 20261119_204908), so 24 keeps
    // the auto range while idle as before.
    if (state.videoMode && state.videoPreviewFps == 30 &&
        std::any_of(caps.aeTargetFpsRanges.begin(), caps.aeTargetFpsRanges.end(), [&](const auto& range) {
            return range[0] == state.videoPreviewFps && range[1] == state.videoPreviewFps;
        }))
        return std::array<int32_t, 2>{state.videoPreviewFps, state.videoPreviewFps};
    if (priorityRequested && caps.priorityAeTargetFpsMin > 0 &&
        caps.priorityAeTargetFpsMax >= caps.priorityAeTargetFpsMin) {
        int32_t floor = caps.priorityAeTargetFpsMin;
        if (state.autoMinFps >= 5 && state.autoMinFps < 30) floor = std::max(floor, state.autoMinFps);
        return std::array<int32_t, 2>{std::min(floor, caps.priorityAeTargetFpsMax), caps.priorityAeTargetFpsMax};
    }
    if ((state.exposureMode == ExposureControlMode::Auto || caps.targetFpsRangeRequired) && state.autoMinFps >= 5 &&
        state.autoMinFps < 30) {
        const auto maximum = caps.priorityAeTargetFpsMax > 0 ? caps.priorityAeTargetFpsMax : 30;
        return std::array<int32_t, 2>{std::min(state.autoMinFps, maximum), maximum};
    }
    if (caps.targetFpsRangeRequired) {
        const auto maximum = caps.priorityAeTargetFpsMax > 0 ? caps.priorityAeTargetFpsMax : 30;
        return std::array<int32_t, 2>{maximum, maximum};
    }
    return std::nullopt;
}

}  // namespace rawrcam::camera
