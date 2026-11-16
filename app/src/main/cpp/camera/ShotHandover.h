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
    // sameMode: the carry only bridges a restart of the 12 MP view itself (the X14U H route's 50 MP still runs in a
    // separate Java session; the claimed 12 MP frame right after it must keep the view's white balance — its cold
    // start gave B gain 1.93 vs 2.72, 22:38 Nov 15 — and exposure), so no full-size gain or focus scale.
    void setCarry(bool on, bool sameMode = false) {
        std::lock_guard<std::mutex> lock(mutex_);
        carried_ = on ? last_ : std::nullopt;
        carriedExposure_ = on ? lastExposure_ : std::nullopt;
        sameMode_.store(on && sameMode, std::memory_order_relaxed);
        carrying_.store(on, std::memory_order_relaxed);
    }
    bool carrySameMode() const { return sameMode_.load(std::memory_order_relaxed); }
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
        // User's tape tests 14 Nov (photo sharpest; sent = slider diopters x 0.693 of that build): 3 m (slider 4.3 m),
        // 1.5 m (1.3 m), 0.7 m (0.6 m), 0.4 m (0.27 m). Beyond 3 m: infinity from ~5 m.
        static constexpr std::array<std::array<float, 2>, 4> kCam4 = {
            {{0.333f, 0.161f}, {0.667f, 0.533f}, {1.429f, 1.155f}, {2.500f, 2.567f}}};
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

    // Closed-loop focus for the full-size shot (3.2x: the open-loop table drifts with heat, 01:09 Nov 15). The shot
    // steps the lens through a few distances (diopters sent as is; < 0 = the usual carried focus) while every
    // full-size frame's sharpness at the focus point is measured, then shoots at the sharpest.
    void setFocusSweep(float diopters) {
        sweep_.store(diopters, std::memory_order_relaxed);
        sweepGeneration_.fetch_add(1, std::memory_order_relaxed);
    }
    float focusSweep() const { return sweep_.load(std::memory_order_relaxed); }
    uint64_t sweepGeneration() const { return sweepGeneration_.load(std::memory_order_relaxed); }
    // The lens distance the last carried request sent (diopters, -1 = none yet).
    void noteSentFocus(float diopters) { sent_.store(diopters, std::memory_order_relaxed); }
    float sentFocus() const { return sent_.load(std::memory_order_relaxed); }
    // The view's AF area (normalized x0, y0, x1, y1); the measure uses it, else the frame centre.
    void setFocusArea(const std::optional<std::array<float, 4>>& area) {
        std::lock_guard<std::mutex> lock(mutex_);
        focusArea_ = area;
    }
    std::optional<std::array<float, 4>> focusArea() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return focusArea_;
    }
    void setFocusProbe(bool on) {
        probeCount_.store(0, std::memory_order_relaxed);
        sharpness_.store(-1.0f, std::memory_order_relaxed);
        probe_.store(on, std::memory_order_relaxed);
    }
    bool focusProbe() const { return probe_.load(std::memory_order_relaxed); }
    void noteSharpness(float value) {
        sharpness_.store(value, std::memory_order_relaxed);
        probeCount_.fetch_add(1, std::memory_order_relaxed);
    }
    float sharpness() const { return sharpness_.load(std::memory_order_relaxed); }
    int64_t probeCount() const { return probeCount_.load(std::memory_order_relaxed); }

private:
    std::atomic<float> sweep_{-1.0f};
    std::atomic<uint64_t> sweepGeneration_{0};
    std::atomic<float> sent_{-1.0f};
    std::optional<std::array<float, 4>> focusArea_;
    std::atomic<bool> probe_{false};
    std::atomic<float> sharpness_{-1.0f};
    std::atomic<int64_t> probeCount_{0};
    mutable std::mutex mutex_;
    std::optional<AppliedWhiteBalance> last_;
    std::optional<AppliedWhiteBalance> carried_;
    std::optional<AppliedExposure> lastExposure_;
    std::optional<AppliedExposure> carriedExposure_;
    std::atomic<bool> hold_{false};
    std::atomic<bool> carrying_{false};
    std::atomic<bool> sameMode_{false};
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
