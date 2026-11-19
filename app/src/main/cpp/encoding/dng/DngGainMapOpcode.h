#pragma once
#include <cstdint>
#include <string>
#include <vector>

#include "encoding/dng/DngWriteParams.h"
#include "geometry/LensDistortion.h"
#include "metadata/FrameMetadataSnapshot.h"

namespace rawrcam::encoding::dng {

// Active-area rectangle in stored-image pixels plus the CFA code at the
// stored-image origin; the GainMap phase per channel derives from both.
struct DngGainMapArea {
    uint32_t top = 0, left = 0, height = 0, width = 0;
    uint32_t storedPattern = 0;
};

// Appends four GainMap opcodes (OpcodeList2, one per Camera2 lens-shading
// channel R/G_even/G_odd/B) built from metadata.lensShadingMap. Returns false
// with *error on an invalid map or unresolvable CFA phase.
bool appendLensShadingGainMaps(TinyDngWriteParams& params, const rawrcam::metadata::FrameMetadataSnapshot& metadata,
                               const DngGainMapArea& area, std::string* error);

// Appends a WarpRectilinear opcode (OpcodeList3, id 1, one plane) fitted to
// the lens profile calibration on a width x height active area. Returns
// false with *error when the calibration doesn't fit the area.
bool appendLensDistortionWarp(TinyDngWriteParams& params, const rawrcam::geometry::LensDistortionModel& model,
                              uint32_t width, uint32_t height, std::string* error);

// Appends a FixBadPixelsList opcode (OpcodeList1, id 5) listing [x0, y0, x1, y1, ...] stored-image photosites, so DNG
// readers hide the sensor defects the stored CFA still holds (the RAW data itself stays untouched). storedPattern is the
// CFA code at the stored-image origin. Nothing is appended for an empty list.
void appendFixBadPixels(TinyDngWriteParams& params, const std::vector<int32_t>& points, uint32_t storedPattern,
                        uint32_t width, uint32_t height);

}  // namespace rawrcam::encoding::dng
