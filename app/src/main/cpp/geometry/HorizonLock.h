#pragma once

#include <atomic>
#include <cmath>

namespace rawrcam::geometry {

// X14U video horizon lock, set from the gravity sensor many times a second (JNI setHorizonLock).
// Roll: how far (radians) the camera is turned clockwise about its axis past the framing being kept level, as seen in
// the camera's own image. Scale: the fixed zoom-in (>= 1) that keeps the rotated frame filled; 1 = lock off.
inline std::atomic<float>& horizonRoll() noexcept {
    static std::atomic<float> value{0.0f};
    return value;
}
inline std::atomic<float>& horizonScale() noexcept {
    static std::atomic<float> value{1.0f};
    return value;
}

// Diagnostics (temporary): how the last viewfinder frame / recorded frame applied the lock.
// 0 not applied, 1 on the lens profile, 2 on a plain pinhole model; and the angle used (degrees x10).
inline std::atomic<int>& horizonViewfinderRoute() noexcept {
    static std::atomic<int> value{0};
    return value;
}
inline std::atomic<int>& horizonRecordRoute() noexcept {
    static std::atomic<int> value{0};
    return value;
}
inline std::atomic<int>& horizonRecordAngle() noexcept {
    static std::atomic<int> value{0};
    return value;
}

struct HorizonSampling {
    bool active = false;
    float cosA = 1.0f, sinA = 0.0f, scale = 1.0f;
};

// The output -> source sampling rotation that levels the horizon: a level output row samples the source along the
// tilted horizon, i.e. rotated by -roll in image coordinates (x right, y down). Mirroring after the remap keeps a level
// image level, so it needs no sign change.
inline HorizonSampling horizonSampling() noexcept {
    HorizonSampling s;
    const float scale = horizonScale().load(std::memory_order_relaxed);
    if (!(scale > 1.0001f) || !std::isfinite(scale)) return s;
    const float a = -horizonRoll().load(std::memory_order_relaxed);
    s.active = true;
    s.cosA = std::cos(a);
    s.sinA = std::sin(a);
    s.scale = scale;
    return s;
}

}  // namespace rawrcam::geometry
