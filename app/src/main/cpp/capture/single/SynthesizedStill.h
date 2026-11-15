#pragma once

#include <cstdint>
#include <mutex>
#include <optional>
#include <vector>

#include "imaging/RawSnapshot.h"

namespace rawrcam::capture::synth {

// X14U 50 MP through the camera's own pipeline (Kotlin HalJavaStill + HalRawSynth): the full-size plain still,
// linearised and laid out as an RGGB RAW16 mosaic, waits here until the next still request's viewfinder frame is
// claimed; that frame's copy is then replaced by this one (with its metadata scaled to the full size), so the
// shot runs through Rawr's own single-frame develop, JPEG and DNG like any photo.
struct Pending {
    std::vector<uint8_t> raw16;  // little-endian uint16, width x height, black 0, white 65535, RGGB
    uint32_t width = 0;
    uint32_t height = 0;
    int64_t exposureTimeNs = 0;
    int32_t sensitivity = 0;
    float aperture = 0.0f;
    float focalLengthMm = 0.0f;
    float focusDiopters = -1.0f;
};

void set(Pending pending);
bool pending();
void clear();
// The still request claimed the viewfinder frame at timestampNs while a synthesized frame waited.
void claim(uint64_t timestampNs);
// The synthesized frame for the claimed timestamp (once).
std::optional<Pending> take(uint64_t timestampNs);
// Puts the synthesized frame into the claimed viewfinder snapshot: pixels, size and full-size metadata.
void apply(imaging::RawSnapshot& frame, Pending&& pending);

}  // namespace rawrcam::capture::synth
