#pragma once
#include <atomic>

namespace rawrcam::video {

// X14U (Nov 20): the video mode's frame rate (0 outside video), set by the camera controller. 4K above 30 fps records
// through the one-pass fast path (the only one that fits 16.7 ms a frame at 4K).
inline std::atomic<int>& videoModeFps() noexcept {
    static std::atomic<int> value{0};
    return value;
}

}  // namespace rawrcam::video
