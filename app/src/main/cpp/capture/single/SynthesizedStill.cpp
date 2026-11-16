#include "capture/single/SynthesizedStill.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>
#include <thread>

#include "metadata/CameraContextMetadata.h"

namespace rawrcam::capture::synth {
namespace {
std::mutex gMutex;
std::optional<Pending> gPending;
std::optional<uint64_t> gClaimed;

metadata::RectI scaled(const metadata::RectI& r, double sx, double sy) {
    if (!r.valid) return r;
    metadata::RectI out = r;
    out.left = int32_t(r.left * sx);
    out.top = int32_t(r.top * sy);
    out.right = int32_t(r.right * sx);
    out.bottom = int32_t(r.bottom * sy);
    return out;
}
}  // namespace

void set(Pending pending) {
    std::lock_guard<std::mutex> lock(gMutex);
    gPending = std::move(pending);
    gClaimed.reset();
}
bool pending() {
    std::lock_guard<std::mutex> lock(gMutex);
    return gPending.has_value() && !gClaimed;
}
void clear() {
    std::lock_guard<std::mutex> lock(gMutex);
    gPending.reset();
    gClaimed.reset();
}
void claim(uint64_t timestampNs) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gPending && !gClaimed) gClaimed = timestampNs;
}
std::optional<Pending> take(uint64_t timestampNs) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gPending || !gClaimed || *gClaimed != timestampNs) return std::nullopt;
    auto out = std::move(gPending);
    gPending.reset();
    gClaimed.reset();
    return out;
}

namespace {
inline uint16_t at(const std::vector<uint8_t>& v, size_t i) { return uint16_t(v[2 * i] | (v[2 * i + 1] << 8)); }

// Mean R, G, B per cell of a GX x GY grid, and each cell's peak sample (clipping).
struct Grid {
    static constexpr int GX = 64, GY = 48;
    std::vector<double> sum[3] = {std::vector<double>(GX * GY, 0.0), std::vector<double>(GX * GY, 0.0),
                                  std::vector<double>(GX * GY, 0.0)};
    std::vector<uint32_t> count[3] = {std::vector<uint32_t>(GX * GY, 0), std::vector<uint32_t>(GX * GY, 0),
                                      std::vector<uint32_t>(GX * GY, 0)};
    std::vector<uint16_t> peak = std::vector<uint16_t>(GX * GY, 0);
    double mean(int ch, int c) const { return count[ch][c] ? sum[ch][c] / count[ch][c] : 0.0; }
};
// Channel (0 R, 1 G, 2 B) at (x & 1, y & 1) for Camera2's CFA codes RGGB / GRBG / GBRG / BGGR.
constexpr int kCfaChannel[4][4] = {{0, 1, 1, 2}, {1, 0, 2, 1}, {1, 2, 0, 1}, {2, 1, 1, 0}};
// Runs [work](y0, y1) over row bands on up to 8 threads.
template <typename Work>
void parallelRows(uint32_t h, Work work) {
    const uint32_t threads = std::clamp(std::thread::hardware_concurrency(), 1u, 8u);
    std::vector<std::thread> pool;
    for (uint32_t t = 1; t < threads; ++t) pool.emplace_back([=, &work] { work(t, h * t / threads, h * (t + 1) / threads); });
    work(0u, 0u, h / threads);
    for (auto& t : pool) t.join();
}
// [black]: per 2x2 site (row-major).
Grid channels(const std::vector<uint8_t>& raw, uint32_t w, uint32_t h, uint32_t cfa, const double black[4]) {
    const int* map = kCfaChannel[cfa & 3u];
    const auto* data = reinterpret_cast<const uint16_t*>(raw.data());  // little-endian, as the device
    std::vector<int> colCell(w);
    for (uint32_t x = 0; x < w; ++x) colCell[x] = int(uint64_t(x) * Grid::GX / w);
    const uint32_t threads = std::clamp(std::thread::hardware_concurrency(), 1u, 8u);
    std::vector<Grid> parts(threads);
    parallelRows(h, [&](uint32_t part, uint32_t y0, uint32_t y1) {
        Grid& g = parts[part];
        std::vector<uint64_t> sums[3] = {std::vector<uint64_t>(Grid::GX), std::vector<uint64_t>(Grid::GX),
                                         std::vector<uint64_t>(Grid::GX)};
        std::vector<uint32_t> counts[3] = {std::vector<uint32_t>(Grid::GX), std::vector<uint32_t>(Grid::GX),
                                           std::vector<uint32_t>(Grid::GX)};
        for (uint32_t y = y0; y < y1; ++y) {
            const int cy = int(uint64_t(y) * Grid::GY / h);
            const uint16_t* row = data + size_t(y) * w;
            const int ch0 = map[(y & 1u) * 2], ch1 = map[(y & 1u) * 2 + 1];
            uint16_t* peak = g.peak.data() + cy * Grid::GX;
            for (int ch = 0; ch < 3; ++ch) {
                std::fill(sums[ch].begin(), sums[ch].end(), 0);
                std::fill(counts[ch].begin(), counts[ch].end(), 0);
            }
            for (uint32_t x = 0; x + 1 < w; x += 2) {
                const int c0 = colCell[x], c1 = colCell[x + 1];
                const uint16_t v0 = row[x], v1 = row[x + 1];
                sums[ch0][c0] += v0; counts[ch0][c0]++;
                sums[ch1][c1] += v1; counts[ch1][c1]++;
                if (v0 > peak[c0]) peak[c0] = v0;
                if (v1 > peak[c1]) peak[c1] = v1;
            }
            const double b0 = black[(y & 1u) * 2], b1 = black[(y & 1u) * 2 + 1];
            for (int cx = 0; cx < Grid::GX; ++cx) {
                for (int ch = 0; ch < 3; ++ch) {
                    if (!counts[ch][cx]) continue;
                    const double b = ch == ch0 && ch == ch1 ? (b0 + b1) / 2 : (ch == ch0 ? b0 : b1);
                    g.sum[ch][cy * Grid::GX + cx] += double(sums[ch][cx]) - b * counts[ch][cx];
                    g.count[ch][cy * Grid::GX + cx] += counts[ch][cx];
                }
            }
        }
    });
    Grid out;
    for (const Grid& g : parts) {
        for (int c = 0; c < Grid::GX * Grid::GY; ++c) {
            for (int ch = 0; ch < 3; ++ch) {
                out.sum[ch][c] += g.sum[ch][c];
                out.count[ch][c] += g.count[ch][c];
            }
            out.peak[c] = std::max(out.peak[c], g.peak[c]);
        }
    }
    return out;
}
double median(std::vector<double> v) {
    if (v.empty()) return 1.0;
    std::nth_element(v.begin(), v.begin() + v.size() / 2, v.end());
    return v[v.size() / 2];
}
}  // namespace

uint32_t removeHotPixels(Pending& p) {
    const uint32_t w = p.width, h = p.height;
    if (w < 8 || h < 8 || p.raw16.size() < size_t(w) * h * 2) return 0;
    auto* data = reinterpret_cast<uint16_t*>(p.raw16.data());  // little-endian, as the device
    struct Fix { size_t index; uint16_t value; };
    const uint32_t threads = std::clamp(std::thread::hardware_concurrency(), 1u, 8u);
    std::vector<std::vector<Fix>> found(threads);
    auto scan = [&](uint32_t part) {
        const uint32_t y0 = std::max(2u, h * part / threads), y1 = std::min(h - 2, h * (part + 1) / threads);
        auto& out = found[part];
        uint16_t n[8];
        for (uint32_t y = y0; y < y1; ++y) {
            const uint16_t* row = data + size_t(y) * w;
            for (uint32_t x = 2; x + 2 < w; ++x) {
                const uint16_t v = row[x];
                if (v < 1024) continue;
                // Same-colour neighbours of an RGGB site are two away; most sites fail on the first two.
                if (uint32_t(v) <= uint32_t(row[x - 2]) + 512u || uint32_t(v) <= uint32_t(row[x + 2]) + 512u) continue;
                n[0] = row[x - 2]; n[1] = row[x + 2];
                n[2] = row[x - 2 * size_t(w)]; n[3] = row[x + 2 * size_t(w)];
                n[4] = row[x - 2 * size_t(w) - 2]; n[5] = row[x - 2 * size_t(w) + 2];
                n[6] = row[x + 2 * size_t(w) - 2]; n[7] = row[x + 2 * size_t(w) + 2];
                uint16_t top = 0;
                for (uint16_t q : n) top = std::max(top, q);
                const uint32_t margin = std::max<uint32_t>(512u, top / 2u);
                if (uint32_t(v) <= uint32_t(top) + margin) continue;
                std::nth_element(n, n + 4, n + 8);
                out.push_back({size_t(y) * w + x, n[4]});
            }
        }
    };
    std::vector<std::thread> pool;
    for (uint32_t t = 1; t < threads; ++t) pool.emplace_back(scan, t);
    scan(0);
    for (auto& t : pool) t.join();
    uint32_t count = 0;
    for (auto& part : found) {
        for (const auto& f : part) data[f.index] = f.value;
        count += uint32_t(part.size());
    }
    return count;
}

std::string matchTone(const imaging::RawSnapshot& ref, Pending& p) {
    char line[640];
    const auto& m = ref.metadata;
    const auto& ctx = m.cameraContext;
    if (ref.width < 64 || ref.height < 48 || ref.raw16.size() < size_t(ref.width) * ref.height * 2 || p.width < 64 ||
        p.raw16.size() < size_t(p.width) * p.height * 2)
        return "SYNTH_TONE skipped reason=no_ref";
    const uint32_t cfa = ctx ? ctx->rawPreviewCfa : 0u;
    float white = m.effectiveWhiteLevel > 0.0f ? m.effectiveWhiteLevel : m.staticWhiteLevel;
    if (white <= 0.0f && ctx) white = ctx->baselineWhiteLevel;
    if (white <= 0.0f) return "SYNTH_TONE skipped reason=no_white";
    const auto& bl = m.blackLevelPhysicalRggb;
    const float blackMean = (bl[0] + bl[1] + bl[2] + bl[3]) / 4.0f;
    const double range = double(white) - blackMean;
    const double refBlack[4] = {bl[0], bl[1], bl[2], bl[3]}, noBlack[4] = {0, 0, 0, 0};
    const Grid r = channels(ref.raw16, ref.width, ref.height, cfa, refBlack);
    const Grid s = channels(p.raw16, p.width, p.height, 0u, noBlack);
    // The 12 MP RAW still carries lens shading; its map (R, G_even, G_odd, B per point) brings it level.
    const uint32_t mw = m.lensShadingMapWidth, mh = m.lensShadingMapHeight;
    const bool lsc = mw >= 2 && mh >= 2 && m.lensShadingMap.size() >= size_t(mw) * mh * 4;
    std::vector<std::pair<double, double>> pairs;  // green: (synth, ref in synth units)
    std::vector<double> redRatio, blueRatio;        // per cell (synth R/G) / (ref R/G), same for B (bright cells)
    std::vector<double> looseRed, looseBlue;        // the same down to near black (a dark scene's fallback)
    for (int cy = 0; cy < Grid::GY; ++cy) {
        for (int cx = 0; cx < Grid::GX; ++cx) {
            const int c = cy * Grid::GX + cx;
            if (!r.count[1][c] || !s.count[1][c]) continue;
            if (r.peak[c] >= white * 0.95f || s.peak[c] >= 64000) continue;
            double gain[3] = {1.0, 1.0, 1.0};
            if (lsc) {
                const uint32_t gx = uint32_t(std::lround((cx + 0.5) / Grid::GX * (mw - 1)));
                const uint32_t gy = uint32_t(std::lround((cy + 0.5) / Grid::GY * (mh - 1)));
                const size_t k = (size_t(gy) * mw + gx) * 4;
                gain[0] = m.lensShadingMap[k];
                gain[1] = 0.5 * (m.lensShadingMap[k + 1] + m.lensShadingMap[k + 2]);
                gain[2] = m.lensShadingMap[k + 3];
            }
            double rv[3], sv[3];
            for (int ch = 0; ch < 3; ++ch) {
                rv[ch] = r.mean(ch, c) * gain[ch] / range * 65535.0;
                sv[ch] = s.mean(ch, c);
            }
            if (rv[1] > 40.0 && sv[1] > 40.0) pairs.emplace_back(sv[1], rv[1]);
            // Colour only where R and B are well above black (~1%): in dim warm light the camera's 8-bit still clips
            // blue's noise at zero, which biased the blue factor ~4% high indoors (00:22 Nov 16).
            if (rv[1] > 40.0 && sv[1] > 40.0 && rv[0] > 20.0 && sv[0] > 20.0 && rv[2] > 20.0 && sv[2] > 20.0) {
                const double qr = (sv[0] / sv[1]) / (rv[0] / rv[1]), qb = (sv[2] / sv[1]) / (rv[2] / rv[1]);
                looseRed.push_back(qr);
                looseBlue.push_back(qb);
                if (rv[0] > 640.0 && sv[0] > 640.0 && rv[2] > 640.0 && sv[2] > 640.0) {
                    redRatio.push_back(qr);
                    blueRatio.push_back(qb);
                }
            }
        }
    }
    const size_t n = pairs.size();
    if (n < 300) {
        std::snprintf(line, sizeof line, "SYNTH_TONE skipped reason=few_cells cells=%zu", n);
        return line;
    }
    double ma = 0, mb = 0;
    for (auto& [a, b] : pairs) { ma += std::log(a); mb += std::log(b); }
    ma /= n; mb /= n;
    double sab = 0, saa = 0, sbb = 0;
    for (auto& [a, b] : pairs) {
        const double da = std::log(a) - ma, db = std::log(b) - mb;
        sab += da * db; saa += da * da; sbb += db * db;
    }
    const double corr = sab / std::sqrt(std::max(saa * sbb, 1e-12));
    if (corr < 0.6) {
        std::snprintf(line, sizeof line, "SYNTH_TONE skipped reason=no_match cells=%zu corr=%.3f lsc=%d", n, corr, lsc);
        return line;
    }
    // Colour: the 50 MP still's R/G and B/G against the 12 MP RAW's, cell by cell (the 3.2x / 5x plain stills
    // carry less blue than their sensor: 0.84x at 21:05, and Rawr's develop halved it in the JPEG at 23:41 Nov 15).
    double kR = 1.0, kB = 1.0;
    if (redRatio.size() < 300 && looseRed.size() >= 300) {
        redRatio.swap(looseRed);
        blueRatio.swap(looseBlue);
    }
    if (redRatio.size() >= 300) {
        kR = std::clamp(1.0 / median(redRatio), 0.5, 2.0);
        kB = std::clamp(1.0 / median(blueRatio), 0.5, 2.0);
    }
    // Quantile matching (the cells' synth and ref levels sorted separately, so a slight shift between the two
    // frames doesn't bias the gains the way a per-cell ratio binned by either side does): at each of NB quantiles,
    // the synth level and the ref/synth gain there.
    std::vector<double> sv, rv;
    sv.reserve(n);
    rv.reserve(n);
    for (auto& [a, b] : pairs) { sv.push_back(a); rv.push_back(b); }
    std::sort(sv.begin(), sv.end());
    std::sort(rv.begin(), rv.end());
    constexpr int NB = 10;
    std::vector<double> knotS, knotF;
    for (int b = 0; b < NB; ++b) {
        const size_t i = n * (2 * b + 1) / (2 * NB);
        const double sm = sv[i];
        double f = std::clamp(rv[i] / sv[i], 1.0 / 32.0, 32.0);
        // Output levels must keep rising.
        if (!knotS.empty()) {
            if (sm <= knotS.back()) continue;
            const double prev = knotS.back() * knotF.back();
            if (sm * f <= prev) f = prev * 1.001 / sm;
        }
        knotS.push_back(sm);
        knotF.push_back(f);
    }
    if (knotS.size() < 3) return "SYNTH_TONE skipped reason=few_bins";
    // Above the brightest bin: the same gain up to full scale, and the white level goes with it (an overexposed
    // still's clipped areas stay marked clipped instead of being stretched back to full scale: H1L 23:04 Nov 15
    // came out ~5x too bright and its highlights blew).
    const double endF = knotF.back();
    if (knotS.back() < 65535.0) { knotS.push_back(65535.0); knotF.push_back(endF); }
    const uint32_t outWhite = uint32_t(std::clamp(std::lround(65535.0 * endF), 1024L, 65535L));
    p.whiteLevel = float(outWhite);
    std::vector<float> lut(65536);
    size_t k = 0;
    for (int v = 0; v < 65536; ++v) {
        const double x = std::max(v, 1);
        while (k + 1 < knotS.size() && knotS[k + 1] < x) ++k;
        double f;
        if (x <= knotS.front()) f = knotF.front();
        else if (k + 1 >= knotS.size()) f = knotF.back();
        else {
            const double t = (std::log(x) - std::log(knotS[k])) / (std::log(knotS[k + 1]) - std::log(knotS[k]));
            f = knotF[k] + (knotF[k + 1] - knotF[k]) * std::clamp(t, 0.0, 1.0);
        }
        lut[v] = float(f);
    }
    // Every 2x2 RGGB site by the gain for its green, red and blue also by the colour factors.
    const float fR = float(kR), fB = float(kB);
    auto& raw = p.raw16;
    const uint32_t w = p.width, h = p.height & ~1u;
    parallelRows(h / 2, [&](uint32_t, uint32_t pair0, uint32_t pair1) {
    for (uint32_t y = pair0 * 2; y < pair1 * 2; y += 2) {
        uint8_t* r0 = raw.data() + size_t(y) * w * 2;
        uint8_t* r1 = r0 + size_t(w) * 2;
        for (uint32_t x = 0; x + 1 < w; x += 2) {
            const uint32_t g = (uint32_t(r0[2 * x + 2] | (r0[2 * x + 3] << 8)) + uint32_t(r1[2 * x] | (r1[2 * x + 1] << 8))) >> 1;
            const float f = lut[g];
            uint8_t* sites[4] = {r0 + 2 * x, r0 + 2 * x + 2, r1 + 2 * x, r1 + 2 * x + 2};  // R G G B
            const float k[4] = {f * fR, f, f, f * fB};
            for (int i = 0; i < 4; ++i) {
                uint8_t* q = sites[i];
                const uint32_t v = uint32_t(q[0] | (q[1] << 8));
                const uint32_t o = v >= 65535u ? outWhite : std::min(outWhite, uint32_t(v * k[i] + 0.5f));
                q[0] = uint8_t(o & 0xFF);
                q[1] = uint8_t(o >> 8);
            }
        }
    }
    });
    int len = std::snprintf(line, sizeof line, "SYNTH_TONE applied cells=%zu corr=%.3f lsc=%d ref=%ux%u cfa=%u white=%u "
                            "colour=%zu kR=%.3f kB=%.3f gains=",
                            n, corr, lsc, ref.width, ref.height, cfa, outWhite, redRatio.size(), kR, kB);
    for (size_t i = 0; i < knotS.size() && len < int(sizeof line) - 24; ++i)
        len += std::snprintf(line + len, sizeof line - len, "%s%.0f:%.2f", i ? "," : "", knotS[i], knotF[i]);
    return line;
}

void apply(imaging::RawSnapshot& frame, Pending&& p) {
    auto& m = frame.metadata;
    // The noise profile (S, O per CFA site, in the frame's raw units) belongs to the 12 MP RAW's levels: carried
    // over unchanged it read as ~70x less noise on the 0..65535 frame, so the wavelet denoise did next to nothing
    // (02:15 Nov 16: "denoise override not working"). Re-expressed per colour in the new levels, with the full-size
    // readout's own pixels carrying ~4x the binned mode's variance at the same normalized level.
    if (m.sensorNoiseProfile.size() >= 8 && m.cameraContext) {
        const uint32_t oldCfa = m.cameraContext->rawPreviewCfa & 3u;
        const float oldWhite = m.effectiveWhiteLevel > 0.0f ? m.effectiveWhiteLevel : m.staticWhiteLevel;
        constexpr double kFullSizeVariance = 4.0;
        double a[3] = {0, 0, 0}, b[3] = {0, 0, 0};
        int n[3] = {0, 0, 0};
        for (int site = 0; site < 4; ++site) {
            const double black = m.blackLevelPhysicalRggb[site];
            const double range = double(oldWhite) - black;
            if (!(range > 0.0)) continue;
            const int ch = kCfaChannel[oldCfa][site];
            const double S = m.sensorNoiseProfile[2 * site], O = m.sensorNoiseProfile[2 * site + 1];
            a[ch] += S / range;
            b[ch] += (S * black + O) / (range * range);
            n[ch]++;
        }
        if (n[0] && n[1] && n[2]) {
            const double W = p.whiteLevel;
            std::vector<double> profile(8);
            for (int site = 0; site < 4; ++site) {
                const int ch = kCfaChannel[0][site];  // the synthesized frame is RGGB
                profile[2 * site] = kFullSizeVariance * a[ch] / n[ch] * W;
                profile[2 * site + 1] = kFullSizeVariance * b[ch] / n[ch] * W * W;
            }
            m.sensorNoiseProfile = std::move(profile);
        } else {
            m.sensorNoiseProfile.clear();
        }
    }
    const double sx = frame.width ? double(p.width) / frame.width : 1.0;
    const double sy = frame.height ? double(p.height) / frame.height : 1.0;
    if (m.cameraContext) {
        auto ctx = std::make_shared<metadata::CameraContextMetadata>(*m.cameraContext);
        auto& g = ctx->geometry;
        g.rawBufferWidth = p.width;
        g.rawBufferHeight = p.height;
        g.pixelArrayWidth = p.width;
        g.pixelArrayHeight = p.height;
        g.activeArray = {0, 0, int32_t(p.width), int32_t(p.height), true};
        g.preCorrectionActiveArray = g.activeArray;
        ctx->rawFormat = geometry::RawPixelFormat::Raw16;
        ctx->rawPreviewCfa = 0;  // RGGB (HalRawSynth)
        ctx->camera2Cfa = 0;
        ctx->cfaBlock = 1;
        ctx->photoCfaBlock = 1;
        ctx->binnedFallbackContext = nullptr;
        ctx->baselineBlackLevelPhysicalRggb = {0.0f, 0.0f, 0.0f, 0.0f};
        ctx->baselineWhiteLevel = p.whiteLevel;
        // The camera's own pipeline applied lens shading, and nothing else of the sensor's layout is left.
        ctx->lensShadingMapWidth = 0;
        ctx->lensShadingMapHeight = 0;
        ctx->hasLensShadingApplied = true;
        ctx->lensShadingApplied = true;
        ctx->hasGreenSplit = false;
        ctx->opticalBlackRegions.clear();
        ctx->lensDistortion.clear();
        ctx->lensIntrinsicCalibration.clear();
        m.cameraContext = std::move(ctx);
    }
    m.blackLevelPhysicalRggb = {0.0f, 0.0f, 0.0f, 0.0f};
    m.staticWhiteLevel = p.whiteLevel;
    m.reportedDynamicWhiteLevel.reset();
    m.effectiveWhiteLevel = p.whiteLevel;
    m.forcedSensorModeId.reset();
    m.lensShadingMap.clear();
    m.lensShadingMapWidth = 0;
    m.lensShadingMapHeight = 0;
    m.hotPixelMap.clear();
    m.scalerCropRegion = scaled(m.scalerCropRegion, sx, sy);
    m.rawCropRegion = scaled(m.rawCropRegion, sx, sy);
    if (p.exposureTimeNs > 0) m.exposureTimeNs = p.exposureTimeNs;
    if (p.sensitivity > 0) m.sensitivity = p.sensitivity;
    if (p.aperture > 0.0f) m.aperture = p.aperture;
    if (p.focalLengthMm > 0.0f) m.focalLengthMm = p.focalLengthMm;
    if (p.focusDiopters >= 0.0f) m.focusDistanceDiopters = p.focusDiopters;
    frame.width = p.width;
    frame.height = p.height;
    frame.packedRowStrideBytes = p.width * 2;
    frame.sourceRowStrideBytes = int32_t(p.width * 2);
    frame.sourcePixelStrideBytes = 2;
    frame.raw16 = std::move(p.raw16);
}

}  // namespace rawrcam::capture::synth
