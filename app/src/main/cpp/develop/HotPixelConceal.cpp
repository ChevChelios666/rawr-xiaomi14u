#include "develop/HotPixelConceal.h"

#include <algorithm>
#include <array>
#include <thread>

namespace rawrcam::develop {

std::size_t concealHotPixelsInPackedRaw16(std::vector<uint8_t>& raw16, uint32_t width, uint32_t height,
                                          const std::vector<int32_t>& hotPixelMap) noexcept {
    if (width == 0 || height == 0 || hotPixelMap.size() < 2) return 0;
    const std::size_t expectBytes = static_cast<std::size_t>(width) * height * sizeof(uint16_t);
    if (raw16.size() != expectBytes) return 0;
    auto* pixels = reinterpret_cast<uint16_t*>(raw16.data());

    std::size_t concealed = 0;
    const int32_t w = static_cast<int32_t>(width);
    const int32_t h = static_cast<int32_t>(height);
    for (std::size_t i = 0; i + 1 < hotPixelMap.size(); i += 2) {
        const int32_t x = hotPixelMap[i];
        const int32_t y = hotPixelMap[i + 1];
        if (x < 0 || y < 0 || x >= w || y >= h) continue;
        // Same Bayer-phase neighbors at distance 2 so color plane is preserved.
        std::array<uint16_t, 4> candidates{};
        std::size_t n = 0;
        if (x - 2 >= 0) candidates[n++] = pixels[static_cast<std::size_t>(y) * width + (x - 2)];
        if (x + 2 < w) candidates[n++] = pixels[static_cast<std::size_t>(y) * width + (x + 2)];
        if (y - 2 >= 0) candidates[n++] = pixels[static_cast<std::size_t>(y - 2) * width + x];
        if (y + 2 < h) candidates[n++] = pixels[static_cast<std::size_t>(y + 2) * width + x];
        if (n == 0) continue;
        std::sort(candidates.begin(), candidates.begin() + n);
        const uint16_t median = (n % 2 == 1)
                                    ? candidates[n / 2]
                                    : static_cast<uint16_t>((candidates[n / 2 - 1] + candidates[n / 2] + 1) / 2);
        pixels[static_cast<std::size_t>(y) * width + x] = median;
        ++concealed;
    }
    return concealed;
}

std::vector<int32_t> detectHotPixelsInPackedRaw16(const std::vector<uint8_t>& raw16, uint32_t width, uint32_t height,
                                                  float blackLevel, float whiteLevel, std::size_t maxPoints) noexcept {
    std::vector<int32_t> out;
    if (width < 8 || height < 8 || raw16.size() != std::size_t(width) * height * sizeof(uint16_t) ||
        !(whiteLevel > blackLevel))
        return out;
    try {
        const auto* px = reinterpret_cast<const uint16_t*>(raw16.data());
        const float range = whiteLevel - blackLevel;
        // Floor in signal units: ~1.5% of the range (about 15 codes at 10 bits) keeps noise out.
        const float floorv = 0.015f * range;
        const unsigned threads = std::max(1u, std::min(8u, std::thread::hardware_concurrency()));
        std::vector<std::vector<int32_t>> found(threads);
        const uint32_t rows = height - 4;
        std::vector<std::thread> pool;
        for (unsigned t = 0; t < threads; ++t) {
            pool.emplace_back([&, t] {
                auto& list = found[t];
                const uint32_t y0 = 2 + rows * t / threads, y1 = 2 + rows * (t + 1) / threads;
                for (uint32_t y = y0; y < y1; ++y) {
                    const uint16_t* r = px + std::size_t(y) * width;
                    for (uint32_t x = 2; x + 2 < width; ++x) {
                        // Nov 19: the same test as before with early exits (most pixels fail at the first neighbour), so
                        // the DNG writer and the merges' CPU paths can afford it. Results are identical (host test on
                        // the 3.2x dark DNGs and on bright test frames with planted defects).
                        const float v = float(r[x]) - blackLevel;
                        const std::size_t w = width;
                        const uint16_t* n[8] = {r + x - 2, r + x + 2, r + x - 2 * w, r + x + 2 * w,
                                                r + x - 2 * w - 2, r + x - 2 * w + 2, r + x + 2 * w - 2, r + x + 2 * w + 2};
                        bool defect = false;
                        // Hot: v > 2 * max(hi, 0) + floor, i.e. every neighbour (black subtracted) below (v - floor) / 2,
                        // and v above floor (for hi < 0).
                        if (v > floorv) {
                            const float limit = 0.5f * (v - floorv) + blackLevel;
                            bool hot = true;
                            for (const uint16_t* k : n)
                                if (float(*k) >= limit) {
                                    hot = false;
                                    break;
                                }
                            if (hot) {
                                // The adjacent (other colour) pixels must not carry the peak too.
                                const float a = std::max(std::max(float(r[x - 1]), float(r[x + 1])),
                                                         std::max(float(r[x - w]), float(r[x + w]))) - blackLevel;
                                if (v > 2.0f * std::max(a, 0.0f) + floorv) defect = true;
                                else goto next;  // hot test passed, so the dead test is skipped (as before)
                            }
                        }
                        {
                            // Dead: lo > 4 floor and v < 0.35 lo: every neighbour above both.
                            const float need = std::max(4.0f * floorv, v / 0.35f) + blackLevel;
                            bool dead = !defect;
                            if (dead)
                                for (const uint16_t* k : n)
                                    if (!(float(*k) > need)) {
                                        dead = false;
                                        break;
                                    }
                            if (dead) {
                                const float a = std::min(std::min(float(r[x - 1]), float(r[x + 1])),
                                                         std::min(float(r[x - w]), float(r[x + w]))) - blackLevel;
                                defect = v < 0.35f * a;  // dead: its neighbours of every colour are lit
                            }
                        }
                        if (defect) {
                            list.push_back(int32_t(x));
                            list.push_back(int32_t(y));
                            if (list.size() / 2 > maxPoints) return;
                        }
                    next:;
                    }
                }
            });
        }
        for (auto& th : pool) th.join();
        std::size_t total = 0;
        for (const auto& l : found) total += l.size() / 2;
        if (total > maxPoints) return {};
        out.reserve(total * 2);
        for (const auto& l : found) out.insert(out.end(), l.begin(), l.end());
    } catch (...) {
        out.clear();
    }
    return out;
}

}  // namespace rawrcam::develop
