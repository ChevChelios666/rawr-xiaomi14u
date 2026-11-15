#include "capture/single/SynthesizedStill.h"

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
        ctx->baselineWhiteLevel = 65535.0f;
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
    m.staticWhiteLevel = 65535.0f;
    m.reportedDynamicWhiteLevel.reset();
    m.effectiveWhiteLevel = 65535.0f;
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
