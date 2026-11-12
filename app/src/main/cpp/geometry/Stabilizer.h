#pragma once

#include <array>
#include <atomic>
#include <cstdint>
#include <mutex>

namespace rawrcam::geometry {

// X14U video stabilisation: the shake to take out, from the gyroscope (JNI pushStabilizerSample), as a time track so
// each video frame gets the correction for the moment it was captured, not for when it reaches the GPU.
// Angles are small rotations of the camera (radians, image axes: x right, y down, z forward) from the smoothed path.
struct StabilizerSample {
    int64_t timeNs = 0;  // CLOCK_BOOTTIME (SensorEvent.timestamp).
    float pitch = 0.0f;  // about x
    float yaw = 0.0f;    // about y
    float roll = 0.0f;   // about z (0 while the horizon Lock / Follow handles the roll)
};

class StabilizerTrack {
public:
    void push(const StabilizerSample& s) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (count_ > 0 && s.timeNs <= ring_[(head_ + kSize - 1) % kSize].timeNs) return;
        ring_[head_] = s;
        head_ = (head_ + 1) % kSize;
        if (count_ < kSize) ++count_;
    }
    void clear() {
        std::lock_guard<std::mutex> lock(mutex_);
        count_ = 0;
        head_ = 0;
    }
    // The correction at [timeNs], interpolated; false with no samples near it (half a second).
    bool at(int64_t timeNs, StabilizerSample& out) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (count_ == 0) return false;
        const auto get = [&](size_t i) -> const StabilizerSample& { return ring_[(head_ + kSize - count_ + i) % kSize]; };
        const auto& first = get(0);
        const auto& last = get(count_ - 1);
        constexpr int64_t kNear = 500'000'000;
        if (timeNs >= last.timeNs) {
            if (timeNs - last.timeNs > kNear) return false;
            out = last;
            return true;
        }
        if (timeNs <= first.timeNs) {
            if (first.timeNs - timeNs > kNear) return false;
            out = first;
            return true;
        }
        size_t lo = 0, hi = count_ - 1;
        while (hi - lo > 1) {
            const size_t mid = (lo + hi) / 2;
            if (get(mid).timeNs <= timeNs) lo = mid; else hi = mid;
        }
        const auto& a = get(lo);
        const auto& b = get(hi);
        const float t = float(double(timeNs - a.timeNs) / double(b.timeNs - a.timeNs));
        out.timeNs = timeNs;
        out.pitch = a.pitch + t * (b.pitch - a.pitch);
        out.yaw = a.yaw + t * (b.yaw - a.yaw);
        out.roll = a.roll + t * (b.roll - a.roll);
        return true;
    }

private:
    static constexpr size_t kSize = 1024;  // about 2.5 s at the gyro's fastest rate
    mutable std::mutex mutex_;
    std::array<StabilizerSample, kSize> ring_{};
    size_t head_ = 0;
    size_t count_ = 0;
};

inline StabilizerTrack& stabilizerTrack() noexcept {
    static StabilizerTrack track;
    return track;
}
// On while the Stabilise tile is on (Video, back lenses).
inline std::atomic<bool>& stabilizerEnabled() noexcept {
    static std::atomic<bool> value{false};
    return value;
}
// The lens's focal length over the sensor width (pinhole fallback without a lens profile).
inline std::atomic<float>& stabilizerFocalNorm() noexcept {
    static std::atomic<float> value{0.7f};
    return value;
}
// Capture time (CLOCK_BOOTTIME, middle of the exposure) of the video frame being recorded now; set just before it.
inline std::atomic<int64_t>& stabilizerFrameTimeNs() noexcept {
    static std::atomic<int64_t> value{0};
    return value;
}

}  // namespace rawrcam::geometry
