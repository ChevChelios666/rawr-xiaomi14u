#include "camera/HalRawSynthNative.h"

#include <turbojpeg.h>

#include <algorithm>
#include <cmath>
#include <functional>
#include <thread>
#include <vector>

namespace rawrcam::camera::halsynth {
namespace {

void parallelRows(int height, const std::function<void(int, int)>& work) {
    const int threads = int(std::clamp(std::thread::hardware_concurrency(), 1u, 8u));
    std::vector<std::thread> pool;
    for (int t = 1; t < threads; ++t) {
        pool.emplace_back([=, &work] { work(height * t / threads, height * (t + 1) / threads); });
    }
    work(0, height / threads);
    for (auto& t : pool) t.join();
}

}  // namespace

bool jpegSize(const uint8_t* jpeg, size_t size, int& width, int& height) {
    tjhandle h = tj3Init(TJINIT_DECOMPRESS);
    if (!h) return false;
    const bool ok = tj3DecompressHeader(h, jpeg, size) == 0;
    if (ok) {
        width = tj3Get(h, TJPARAM_JPEGWIDTH);
        height = tj3Get(h, TJPARAM_JPEGHEIGHT);
    }
    tj3Destroy(h);
    return ok && width > 0 && height > 0;
}

bool jpegToMosaic(const uint8_t* jpeg, size_t size, uint16_t* out, int width, int height, const int32_t* lut) {
    tjhandle h = tj3Init(TJINIT_DECOMPRESS);
    if (!h) return false;
    bool ok = tj3DecompressHeader(h, jpeg, size) == 0;
    const int jw = ok ? tj3Get(h, TJPARAM_JPEGWIDTH) : 0, jh = ok ? tj3Get(h, TJPARAM_JPEGHEIGHT) : 0;
    ok = ok && jw == width && jh >= height;
    std::vector<uint8_t> rgb;
    if (ok) {
        rgb.resize(size_t(jw) * jh * 3);
        ok = tj3Decompress8(h, jpeg, size, rgb.data(), jw * 3, TJPF_RGB) == 0;
    }
    tj3Destroy(h);
    if (!ok) return false;
    parallelRows(height, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const uint8_t* src = rgb.data() + size_t(y) * width * 3;
            uint16_t* dst = out + size_t(y) * width;
            const bool evenRow = (y & 1) == 0;
            for (int x = 0; x < width; ++x) {
                const bool evenCol = (x & 1) == 0;
                // RGGB: R at (even, even), B at (odd, odd), G elsewhere.
                const int ch = evenRow && evenCol ? 0 : (!evenRow && !evenCol ? 2 : 1);
                dst[x] = uint16_t(lut[src[x * 3 + ch]]);
            }
        }
    });
    return true;
}

bool nv21ToMosaic(const uint8_t* nv21, int width, int height, uint16_t* out, const int32_t* lut) {
    if (width <= 0 || height <= 0) return false;
    const size_t uvBase = size_t(width) * height;
    parallelRows(height, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const uint8_t* yRow = nv21 + size_t(y) * width;
            const uint8_t* uvRow = nv21 + uvBase + size_t(y >> 1) * width;
            uint16_t* dst = out + size_t(y) * width;
            const bool evenRow = (y & 1) == 0;
            for (int x = 0; x < width; ++x) {
                const float yy = yRow[x];
                const int uvi = x & ~1;
                const float v = float(uvRow[uvi]) - 128.0f;
                const float u = float(uvRow[uvi + 1]) - 128.0f;
                const bool evenCol = (x & 1) == 0;
                float c;
                if (evenRow && evenCol) c = yy + 1.402f * v;
                else if (!evenRow && !evenCol) c = yy + 1.772f * u;
                else c = yy - 0.344136f * u - 0.714136f * v;
                const int code = std::clamp(int(std::lround(c * 16.0f)), 0, 255 * 16);
                dst[x] = uint16_t(lut[code]);
            }
        }
    });
    return true;
}

}  // namespace rawrcam::camera::halsynth
