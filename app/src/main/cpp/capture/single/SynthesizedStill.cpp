#include "capture/single/SynthesizedStill.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>

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

// Mean green per cell of a GX x GY grid, and whether the cell clipped. greenOdd: green sits where (x + y) is odd.
struct Grid {
    static constexpr int GX = 64, GY = 48;
    std::vector<double> sum = std::vector<double>(GX * GY, 0.0);
    std::vector<uint32_t> count = std::vector<uint32_t>(GX * GY, 0);
    std::vector<uint16_t> peak = std::vector<uint16_t>(GX * GY, 0);
};
template <typename Black>
Grid greens(const std::vector<uint8_t>& raw, uint32_t w, uint32_t h, bool greenOdd, Black black) {
    Grid g;
    for (uint32_t y = 0; y < h; ++y) {
        const int cy = int(uint64_t(y) * Grid::GY / h);
        const uint32_t x0 = ((y & 1u) ^ (greenOdd ? 1u : 0u));
        const size_t row = size_t(y) * w;
        for (uint32_t x = x0; x < w; x += 2) {
            const int c = cy * Grid::GX + int(uint64_t(x) * Grid::GX / w);
            const uint16_t v = at(raw, row + x);
            g.sum[c] += double(v) - black(x, y);
            g.count[c]++;
            if (v > g.peak[c]) g.peak[c] = v;
        }
    }
    return g;
}
}  // namespace

std::string matchTone(const imaging::RawSnapshot& ref, Pending& p) {
    char line[512];
    const auto& m = ref.metadata;
    const auto& ctx = m.cameraContext;
    if (ref.width < 64 || ref.height < 48 || ref.raw16.size() < size_t(ref.width) * ref.height * 2 || p.width < 64 ||
        p.raw16.size() < size_t(p.width) * p.height * 2)
        return "SYNTH_TONE skipped reason=no_ref";
    const uint32_t cfa = ctx ? ctx->rawPreviewCfa : 0u;
    const bool refGreenOdd = cfa == 0u || cfa == 3u;  // RGGB / BGGR: green where x + y is odd
    float white = m.effectiveWhiteLevel > 0.0f ? m.effectiveWhiteLevel : m.staticWhiteLevel;
    if (white <= 0.0f && ctx) white = ctx->baselineWhiteLevel;
    if (white <= 0.0f) return "SYNTH_TONE skipped reason=no_white";
    const auto& bl = m.blackLevelPhysicalRggb;
    const float blackMean = (bl[0] + bl[1] + bl[2] + bl[3]) / 4.0f;
    const double range = double(white) - blackMean;
    const Grid r = greens(ref.raw16, ref.width, ref.height, refGreenOdd,
                          [&](uint32_t x, uint32_t y) { return double(bl[(y & 1u) * 2 + (x & 1u)]); });
    const Grid s = greens(p.raw16, p.width, p.height, true, [](uint32_t, uint32_t) { return 0.0; });
    // The 12 MP RAW still carries lens shading; its map (R, G_even, G_odd, B per point) brings it level.
    const uint32_t mw = m.lensShadingMapWidth, mh = m.lensShadingMapHeight;
    const bool lsc = mw >= 2 && mh >= 2 && m.lensShadingMap.size() >= size_t(mw) * mh * 4;
    std::vector<std::pair<double, double>> pairs;  // (synth, ref in synth units)
    for (int cy = 0; cy < Grid::GY; ++cy) {
        for (int cx = 0; cx < Grid::GX; ++cx) {
            const int c = cy * Grid::GX + cx;
            if (!r.count[c] || !s.count[c]) continue;
            if (r.peak[c] >= white * 0.95f || s.peak[c] >= 64000) continue;
            double gain = 1.0;
            if (lsc) {
                const uint32_t gx = uint32_t(std::lround((cx + 0.5) / Grid::GX * (mw - 1)));
                const uint32_t gy = uint32_t(std::lround((cy + 0.5) / Grid::GY * (mh - 1)));
                const size_t k = (size_t(gy) * mw + gx) * 4;
                gain = 0.5 * (m.lensShadingMap[k + 1] + m.lensShadingMap[k + 2]);
            }
            const double rv = r.sum[c] / r.count[c] * gain / range * 65535.0;
            const double sv = s.sum[c] / s.count[c];
            if (rv > 40.0 && sv > 40.0) pairs.emplace_back(sv, rv);
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
    // Every 2x2 RGGB site by the gain for its green.
    auto& raw = p.raw16;
    const uint32_t w = p.width, h = p.height & ~1u;
    for (uint32_t y = 0; y < h; y += 2) {
        uint8_t* r0 = raw.data() + size_t(y) * w * 2;
        uint8_t* r1 = r0 + size_t(w) * 2;
        for (uint32_t x = 0; x + 1 < w; x += 2) {
            const uint32_t g = (uint32_t(r0[2 * x + 2] | (r0[2 * x + 3] << 8)) + uint32_t(r1[2 * x] | (r1[2 * x + 1] << 8))) >> 1;
            const float f = lut[g];
            for (uint8_t* q : {r0 + 2 * x, r0 + 2 * x + 2, r1 + 2 * x, r1 + 2 * x + 2}) {
                const uint32_t v = uint32_t(q[0] | (q[1] << 8));
                const uint32_t o = v >= 65535u ? outWhite : std::min(outWhite, uint32_t(v * f + 0.5f));
                q[0] = uint8_t(o & 0xFF);
                q[1] = uint8_t(o >> 8);
            }
        }
    }
    int len = std::snprintf(line, sizeof line, "SYNTH_TONE applied cells=%zu corr=%.3f lsc=%d ref=%ux%u cfa=%u white=%u gains=",
                            n, corr, lsc, ref.width, ref.height, cfa, outWhite);
    for (size_t i = 0; i < knotS.size() && len < int(sizeof line) - 24; ++i)
        len += std::snprintf(line + len, sizeof line - len, "%s%.0f:%.2f", i ? "," : "", knotS[i], knotF[i]);
    return line;
}

void apply(imaging::RawSnapshot& frame, Pending&& p) {
    auto& m = frame.metadata;
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
