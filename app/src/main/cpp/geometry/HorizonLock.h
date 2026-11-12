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

// Lock (true): the viewfinder stays natural and shows the recorded frame only as an outline. Follow (false): the
// viewfinder shows the recorded frame itself, turning with it.
inline std::atomic<bool>& horizonNaturalView() noexcept {
    static std::atomic<bool> value{true};
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
