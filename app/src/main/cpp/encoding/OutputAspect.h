#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>

namespace rawrcam::encoding {

// X14U: photo aspect ratio (long side : short side; 0 = the sensor's own). The capture screen sets it; still
// contexts copy it at the shutter. JPEGs are cut to it; DNGs keep every pixel and carry it as their default crop.
inline std::atomic<float>& outputAspect() noexcept {
    static std::atomic<float> value{0.0f};
    return value;
}

struct CropRect {
    std::uint32_t x = 0, y = 0, width = 0, height = 0;
    [[nodiscard]] bool full(std::uint32_t w, std::uint32_t h) const noexcept { return width == w && height == h; }
};

// Centred crop of a width x height image to `aspect` (long:short, whichever way the image lies), even-aligned.
// The ratio follows the image's own long side, so it is the same however the phone was held.
inline CropRect aspectCrop(std::uint32_t width, std::uint32_t height, float aspect) noexcept {
    CropRect r{0, 0, width, height};
    if (!(aspect >= 1.0f) || !std::isfinite(aspect) || width < 2 || height < 2) return r;
    const bool wide = width >= height;
    const double longSide = wide ? width : height;
    const double shortSide = wide ? height : width;
    const double current = longSide / shortSide;
    if (std::abs(current - aspect) < 0.005) return r;
    double newLong = longSide, newShort = shortSide;
    if (aspect > current) newShort = longSide / aspect;
    else newLong = shortSide * aspect;
    const auto even = [](double v) { return std::max<std::uint32_t>(2u, std::uint32_t(v / 2.0) * 2u); };
    const std::uint32_t cw = std::min(wide ? even(newLong) : even(newShort), width);
    const std::uint32_t ch = std::min(wide ? even(newShort) : even(newLong), height);
    r.width = cw;
    r.height = ch;
    r.x = ((width - cw) / 2u) & ~1u;
    r.y = ((height - ch) / 2u) & ~1u;
    return r;
}

}  // namespace rawrcam::encoding
