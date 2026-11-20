#pragma once

#include <sys/system_properties.h>

#include <cstddef>
#include <cstdint>
#include <cstdlib>

namespace rawrcam::imaging {

// Application-wide realtime buffering contract. Every per-frame-slot subsystem
// must size and validate its resources from this single owner so preview,
// statistics, monitoring, diagnostics, and presentation cannot drift apart.
inline constexpr std::uint32_t kRealtimeFramesInFlight = 3;

inline constexpr std::size_t kRawReaderMaxImages = 6;
inline constexpr std::size_t kMaxIngressImages = 1;

// X14U (Nov 20) test switch: `adb shell setprop debug.rawr.ingress_images 2` (then reopen the app) lets 2 camera frames
// wait for the pipeline instead of 1, and gives the RAW reader one more image to keep its headroom (~19 MB at 4K).
// Why: 160-185 ms after a 4K30 take starts, one frame's handling takes 50-60 ms, the next ~8 take ~30 ms while the
// backlog drains (5 ms of slack per frame), and the 1-deep queue drops one frame at ~230 ms (VIDEO_START_TRACE,
// 5 of 5 takes). Read once per app run. Unset: 1, as before.
inline std::size_t ingressImages() noexcept {
    static const std::size_t value = [] {
        char text[92] = {};
        const int length = __system_property_get("debug.rawr.ingress_images", text);
        const int wanted = length > 0 ? std::atoi(text) : 0;
        return wanted >= 2 && wanted <= 3 ? std::size_t(wanted) : kMaxIngressImages;
    }();
    return value;
}
inline std::size_t rawReaderImages() noexcept { return kRawReaderMaxImages + (ingressImages() - kMaxIngressImages); }
inline constexpr std::size_t kMaxIngressMetadata = 8;
inline constexpr std::size_t kMaxPairerImages = 1;
inline constexpr std::size_t kMaxPairerMetadata = 3;
static_assert(kRealtimeFramesInFlight + kMaxIngressImages + kMaxPairerImages < kRawReaderMaxImages,
              "RAW transport must retain one reader lease of headroom");

}  // namespace rawrcam::imaging
