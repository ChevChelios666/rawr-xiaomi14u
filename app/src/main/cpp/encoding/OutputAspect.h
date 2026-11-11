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

// X14U digital zoom: crop factor on top of the lens (1 = none) and whether JPEGs are upscaled back to full size.
inline std::atomic<float>& outputZoom() noexcept {
    static std::atomic<float> value{1.0f};
    return value;
}
inline std::atomic<bool>& outputUpscale() noexcept {
    static std::atomic<bool> value{true};
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

// The still's framing: the centred digital-zoom window (1 / zoom of each side), then the aspect ratio inside it.
inline CropRect stillCrop(std::uint32_t width, std::uint32_t height, float zoom, float aspect) noexcept {
    CropRect z{0, 0, width, height};
    if (std::isfinite(zoom) && zoom > 1.001f && width >= 4 && height >= 4) {
        z.width = std::max<std::uint32_t>(2u, std::uint32_t(double(width) / zoom / 2.0) * 2u);
        z.height = std::max<std::uint32_t>(2u, std::uint32_t(double(height) / zoom / 2.0) * 2u);
        z.x = ((width - z.width) / 2u) & ~1u;
        z.y = ((height - z.height) / 2u) & ~1u;
    }
    CropRect a = aspectCrop(z.width, z.height, aspect);
    a.x += z.x;
    a.y += z.y;
    return a;
}

// 35 mm equivalent of a digitally zoomed still: the lens's times the crop factor (EXIF FocalLengthIn35mmFilm).
inline std::uint16_t zoomedFocalLength35mm(std::uint16_t lens35mm, float zoom) noexcept {
    if (!(zoom > 1.001f) || !std::isfinite(zoom)) return lens35mm;
    return static_cast<std::uint16_t>(std::clamp(std::lround(double(lens35mm) * zoom), 1L, 65535L));
}

}  // namespace rawrcam::encoding
