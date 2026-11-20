#pragma once
#include <atomic>

namespace rawrcam::video {

// X14U (Nov 20): the video mode's frame rate (0 outside video), set by the camera controller. 4K above 30 fps records
// through the one-pass fast path (the only one that fits 16.7 ms a frame at 4K).
inline std::atomic<int>& videoModeFps() noexcept {
    static std::atomic<int> value{0};
    return value;
}

// X14U (Nov 20): the real-time profile's DCP switch for the current video mode (Settings → Video Encoder → Real-time
// profiles), set from Kotlin with NativePreviewEngine.setVideoProfileDcp. The other profile switches reach native
// through the video settings and the horizon/stabiliser setters.
inline std::atomic<bool>& videoProfileDcp() noexcept {
    static std::atomic<bool> value{true};
    return value;
}

}  // namespace rawrcam::video
