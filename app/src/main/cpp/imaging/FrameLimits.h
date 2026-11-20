#pragma once

#if defined(__ANDROID__)
#include <sys/system_properties.h>
#endif

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

// X14U (Nov 20): up to 3 camera frames may wait for the pipeline (upstream: 1), and the RAW reader gets 2 more images
// (8, ~38 MB more at 4K) to keep its headroom. Why: 160-320 ms after a 4K30 take starts one frame's handling takes
// 45-80 ms and the next ~8 take ~30 ms while the backlog drains (5 ms of slack per frame); a 1-deep queue dropped a
// frame in 9 of 9 takes, 2-deep in 3 of 9, 3-deep in 0 of 4 (VIDEO_START_TRACE). Test switch:
// `adb shell setprop debug.rawr.ingress_images 1|2|3` (then reopen the app). Read once per app run.
inline std::size_t ingressImages() noexcept {
    static const std::size_t value = [] {
        int wanted = 0;
#if defined(__ANDROID__)
        char text[92] = {};
        if (__system_property_get("debug.rawr.ingress_images", text) > 0) wanted = std::atoi(text);
#endif
        return wanted >= 1 && wanted <= 3 ? std::size_t(wanted) : std::size_t(3);
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
