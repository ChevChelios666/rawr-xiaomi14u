#include "encoding/jpeg/ImageResample.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <thread>

namespace rawrcam::encoding::jpeg {
namespace {

constexpr double kPi = 3.14159265358979323846;

double lanczos3(double x) {
    x = std::abs(x);
    if (x < 1e-8) return 1.0;
    if (x >= 3.0) return 0.0;
    const double px = kPi * x;
    return 3.0 * std::sin(px) * std::sin(px / 3.0) / (px * px);
}

// Per output position: the first source index and normalized weights.
struct Taps {
    std::vector<int> first;
    std::vector<int> count;
    std::vector<float> weights;  // count[i] entries per output, stride `stride`
    int stride = 0;
};

Taps makeTaps(std::uint32_t srcN, std::uint32_t dstN) {
    Taps t;
    const double scale = double(srcN) / double(dstN);
    // Downscaling widens the filter (anti-aliasing); upscaling keeps radius 3 source pixels.
    const double support = 3.0 * std::max(1.0, scale);
    const double filterScale = std::max(1.0, scale);
    t.stride = int(std::ceil(support * 2.0)) + 2;
    t.first.resize(dstN);
    t.count.resize(dstN);
    t.weights.assign(std::size_t(dstN) * t.stride, 0.0f);
    for (std::uint32_t i = 0; i < dstN; ++i) {
        const double center = (double(i) + 0.5) * scale - 0.5;
        int lo = int(std::floor(center - support)) + 1;
        int hi = int(std::floor(center + support));
        lo = std::max(lo, 0);
        hi = std::min(hi, int(srcN) - 1);
        const int n = std::max(1, std::min(hi - lo + 1, t.stride));
        double sum = 0.0;
        std::array<double, 64> w{};
        for (int k = 0; k < n && k < 64; ++k) {
            w[k] = lanczos3((double(lo + k) - center) / filterScale);
            sum += w[k];
        }
        if (std::abs(sum) < 1e-12) sum = 1.0;
        t.first[i] = lo;
        t.count[i] = n;
        for (int k = 0; k < n && k < 64; ++k) t.weights[std::size_t(i) * t.stride + k] = float(w[k] / sum);
    }
    return t;
}

template <typename Fn>
void parallelRows(std::uint32_t rows, Fn&& fn) {
    const unsigned threads = std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
    if (threads == 1 || rows < 64) {
        fn(0u, rows);
        return;
    }
    std::vector<std::thread> pool;
    const std::uint32_t chunk = (rows + threads - 1) / threads;
    for (unsigned t = 0; t < threads; ++t) {
        const std::uint32_t a = t * chunk, b = std::min(rows, a + chunk);
        if (a >= b) break;
        pool.emplace_back([&, a, b] { fn(a, b); });
    }
    for (auto& th : pool) th.join();
}

}  // namespace

std::vector<std::uint8_t> resizeRgba8Lanczos3(const std::uint8_t* src, std::uint32_t srcW, std::uint32_t srcH,
                                              std::size_t srcPitch, std::uint32_t dstW, std::uint32_t dstH,
                                              bool linearLight) {
    std::vector<std::uint8_t> out;
    if (!src || srcW == 0 || srcH == 0 || dstW == 0 || dstH == 0) return out;
    // sRGB <-> linear tables (decode exact per byte; encode through 4096 linear steps).
    std::array<float, 256> toLinear{};
    for (int i = 0; i < 256; ++i) {
        const double c = i / 255.0;
        toLinear[i] = linearLight ? float(c <= 0.04045 ? c / 12.92 : std::pow((c + 0.055) / 1.055, 2.4)) : float(c);
    }
    std::vector<std::uint8_t> fromLinear(4097);
    for (int i = 0; i <= 4096; ++i) {
        const double l = i / 4096.0;
        const double c = linearLight ? (l <= 0.0031308 ? l * 12.92 : 1.055 * std::pow(l, 1.0 / 2.4) - 0.055) : l;
        fromLinear[i] = std::uint8_t(std::clamp(std::lround(c * 255.0), 0l, 255l));
    }
    const Taps tx = makeTaps(srcW, dstW);
    const Taps ty = makeTaps(srcH, dstH);
    // Horizontal pass: srcH rows x dstW, 3 floats per pixel.
    std::vector<float> mid(std::size_t(srcH) * dstW * 3u);
    parallelRows(srcH, [&](std::uint32_t y0, std::uint32_t y1) {
        for (std::uint32_t y = y0; y < y1; ++y) {
            const std::uint8_t* row = src + std::size_t(y) * srcPitch;
            float* m = mid.data() + std::size_t(y) * dstW * 3u;
            for (std::uint32_t x = 0; x < dstW; ++x) {
                float r = 0, g = 0, b = 0;
                const int f = tx.first[x];
                const float* w = tx.weights.data() + std::size_t(x) * tx.stride;
                for (int k = 0; k < tx.count[x]; ++k) {
                    const std::uint8_t* p = row + std::size_t(f + k) * 4u;
                    r += w[k] * toLinear[p[0]];
                    g += w[k] * toLinear[p[1]];
                    b += w[k] * toLinear[p[2]];
                }
                m[x * 3u] = r;
                m[x * 3u + 1] = g;
                m[x * 3u + 2] = b;
            }
        }
    });
    out.resize(std::size_t(dstW) * dstH * 4u);
    parallelRows(dstH, [&](std::uint32_t y0, std::uint32_t y1) {
        for (std::uint32_t y = y0; y < y1; ++y) {
            std::uint8_t* o = out.data() + std::size_t(y) * dstW * 4u;
            const int f = ty.first[y];
            const float* w = ty.weights.data() + std::size_t(y) * ty.stride;
            for (std::uint32_t x = 0; x < dstW; ++x) {
                float r = 0, g = 0, b = 0;
                for (int k = 0; k < ty.count[y]; ++k) {
                    const float* m = mid.data() + (std::size_t(f + k) * dstW + x) * 3u;
                    r += w[k] * m[0];
                    g += w[k] * m[1];
                    b += w[k] * m[2];
                }
                const auto enc = [&](float v) { return fromLinear[std::size_t(std::clamp(v, 0.0f, 1.0f) * 4096.0f + 0.5f)]; };
                o[x * 4u] = enc(r);
                o[x * 4u + 1] = enc(g);
                o[x * 4u + 2] = enc(b);
                o[x * 4u + 3] = 255;
            }
        }
    });
    return out;
}

}  // namespace rawrcam::encoding::jpeg
