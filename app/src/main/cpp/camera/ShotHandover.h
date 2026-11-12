#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <optional>

namespace rawrcam::camera {

// X14U 50 MP shot: the viewfinder runs on the lens's 12 MP setup and the shot on its run-as lens (a separate camera
// session). These carry the 12 MP view's white balance into the shot's session and hold the viewfinder on its last
// 12 MP frame meanwhile, so neither the photo nor the screen shows the new session's cold start.

struct AppliedWhiteBalance {
    std::array<float, 4> gains{1, 1, 1, 1};
    std::array<float, 9> transform{1, 0, 0, 0, 1, 0, 0, 0, 1};
};

class ShotHandover {
public:
    // Every frame: the white balance the camera applied (any mode).
    void noteApplied(const std::array<float, 4>& gains, const std::array<float, 9>& transform) {
        std::lock_guard<std::mutex> lock(mutex_);
        last_ = AppliedWhiteBalance{gains, transform};
    }
    // On: requests use the last applied white balance until off.
    void setCarry(bool on) {
        std::lock_guard<std::mutex> lock(mutex_);
        carried_ = on ? last_ : std::nullopt;
    }
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
    std::atomic<bool> hold_{false};
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
