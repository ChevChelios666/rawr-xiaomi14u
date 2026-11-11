#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace rawrcam::encoding::jpeg {

// Resizes an RGBA8 image (rows `srcPitch` bytes apart) to dstW x dstH with a separable Lanczos-3 filter, on several
// threads. linearLight: the sRGB values are decoded to linear light, filtered there and encoded again, so edges keep
// their brightness (no dark halos, no thinned highlights). Alpha is written opaque. Used to bring a digital-zoom crop
// back to the uncropped size: it adds no detail, it only resamples cleanly.
std::vector<std::uint8_t> resizeRgba8Lanczos3(const std::uint8_t* src, std::uint32_t srcW, std::uint32_t srcH,
                                              std::size_t srcPitch, std::uint32_t dstW, std::uint32_t dstH,
                                              bool linearLight);

}  // namespace rawrcam::encoding::jpeg
