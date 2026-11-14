#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>
#include <optional>
#include <string>

namespace rawrcam::camera {

// X14U 50 MP shot: the viewfinder runs on the lens's 12 MP setup and the shot on its run-as lens (a separate camera
// session). These carry the 12 MP view's white balance into the shot's session and hold the viewfinder on its last
// 12 MP frame meanwhile, so neither the photo nor the screen shows the new session's cold start.

struct AppliedWhiteBalance {
    std::array<float, 4> gains{1, 1, 1, 1};
    std::array<float, 9> transform{1, 0, 0, 0, 1, 0, 0, 0, 1};
};

// What the 12 MP view's frames were exposed and focused with (capture results).
struct AppliedExposure {
    int64_t exposureTimeNs = 0;
    int32_t sensitivity = 0;
    std::optional<float> focusDiopters;
};

class ShotHandover {
public:
    // Every frame: the white balance the camera applied (any mode).
    void noteApplied(const std::array<float, 4>& gains, const std::array<float, 9>& transform) {
        std::lock_guard<std::mutex> lock(mutex_);
        last_ = AppliedWhiteBalance{gains, transform};
    }
    // Every viewfinder frame: the exposure and lens position the camera reported.
    void noteExposure(int64_t exposureTimeNs, int32_t sensitivity, std::optional<float> focusDiopters) {
        if (exposureTimeNs <= 0 || sensitivity <= 0) return;
        std::lock_guard<std::mutex> lock(mutex_);
        if (carrying_.load(std::memory_order_relaxed)) return;  // the shot's own frames never replace the 12 MP view's
        lastExposure_ = AppliedExposure{exposureTimeNs, sensitivity,
                                        focusDiopters && std::isfinite(*focusDiopters) && *focusDiopters >= 0
                                            ? focusDiopters : std::nullopt};
    }
    // On: requests use the last applied white balance, exposure and lens position until off. The shot's session
    // otherwise starts auto exposure cold (darker, a different shutter: diagnostics 14 Nov, 3.2x) and its full-size
    // mode may not focus where the 12 MP view did.
    void setCarry(bool on) {
        std::lock_guard<std::mutex> lock(mutex_);
        carried_ = on ? last_ : std::nullopt;
        carriedExposure_ = on ? lastExposure_ : std::nullopt;
        carrying_.store(on, std::memory_order_relaxed);
    }
    // Gain the shot's camera needs at the carried exposure: the 0.5x (camera 3) and 3.2x (camera 4) full-size
    // readouts give 0.70x the binned mode's raw signal at the same shutter and ISO (diagnostics 14 Nov:
    // 47.5/68.2, 64.1/91.4, 66.3/93.3); the 1x (camera 2) matches (62.5/61.2).
    static float fullSizeGain(const std::string& cameraId) {
        return cameraId == "3" || cameraId == "4" ? 1.0f / 0.70f : 1.0f;
    }
    // Focus distance (diopters) the shot's camera needs for a subject the 12 MP view has in focus at `diopters`. The
    // 3.2x (camera 4) full-size mode doesn't follow the binned mode's distance scale: measured 50 MP photos (not the
    // viewfinder) were sharp at these settings (real diopters -> sent diopters), linear between them and beyond
    // the ends along the nearest segment; nothing goes past infinity (0). Others: the view's distance as is.
    static float fullSizeFocus(const std::string& cameraId, float diopters) {
        if (cameraId != "4" || !(diopters > 0.0f)) return diopters;
        // 14 Nov: ~1.1 m ruler test (0.905 -> 0.627), 0.40 m tape (2.50 -> 2.57).
        static constexpr std::array<std::array<float, 2>, 2> kCam4 = {{{0.905f, 0.627f}, {2.50f, 2.57f}}};
        std::size_t i = 1;
        while (i + 1 < kCam4.size() && diopters > kCam4[i][0]) ++i;
        const auto& a = kCam4[i - 1];
        const auto& b = kCam4[i];
        const float t = (diopters - a[0]) / (b[0] - a[0]);
        return std::max(0.0f, a[1] + t * (b[1] - a[1]));
    }
    std::optional<AppliedExposure> carriedExposure() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return carriedExposure_;
    }
    // A 50 MP shot's camera switches are under way: the new session keeps the exposure and focus intent even on a
    // different camera (the ultra-wide's 50 MP runs on camera 3, its viewfinder on camera 0).
    bool carrying() const { return carrying_.load(std::memory_order_relaxed); }
    std::optional<AppliedWhiteBalance> carried() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return carried_;
    }

    // Viewfinder hold: frames are developed but not shown; counts the frames of sessions started since the hold.
    void setHold(bool on) {
        holdStartGeneration_.store(lastGeneration_.load(std::memory_order_relaxed), std::memory_order_relaxed);
        newFrames_.store(0, std::memory_order_relaxed);
        newFullSizeFrames_.store(0, std::memory_order_relaxed);
        hold_.store(on, std::memory_order_relaxed);
    }
    bool hold() const { return hold_.load(std::memory_order_relaxed); }
    // Every frame; true when it must not be shown.
    bool noteFrame(uint64_t generation, uint64_t rawPixels) {
        lastGeneration_.store(generation, std::memory_order_relaxed);
        if (!hold_.load(std::memory_order_relaxed)) return false;
        if (generation != holdStartGeneration_.load(std::memory_order_relaxed)) {
            newFrames_.fetch_add(1, std::memory_order_relaxed);
            // Full size: well above the 12.5 MP viewfinder modes.
            if (rawPixels > 30'000'000ull) newFullSizeFrames_.fetch_add(1, std::memory_order_relaxed);
        }
        return true;
    }
    int64_t newFrames() const { return newFrames_.load(std::memory_order_relaxed); }
    int64_t newFullSizeFrames() const { return newFullSizeFrames_.load(std::memory_order_relaxed); }

private:
    mutable std::mutex mutex_;
    std::optional<AppliedWhiteBalance> last_;
    std::optional<AppliedWhiteBalance> carried_;
    std::optional<AppliedExposure> lastExposure_;
    std::optional<AppliedExposure> carriedExposure_;
    std::atomic<bool> hold_{false};
    std::atomic<bool> carrying_{false};
    std::atomic<uint64_t> lastGeneration_{0};
    std::atomic<uint64_t> holdStartGeneration_{0};
    std::atomic<int64_t> newFrames_{0};
    std::atomic<int64_t> newFullSizeFrames_{0};
};

inline ShotHandover& shotHandover() noexcept {
    static ShotHandover handover;
    return handover;
}

}  // namespace rawrcam::camera
