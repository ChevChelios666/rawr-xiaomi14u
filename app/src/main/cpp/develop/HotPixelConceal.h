#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace rawrcam::develop {

// Median-conceals listed hot pixels in a tightly-packed RAW16 image.
// raw16 must be width*height*sizeof(uint16_t) bytes, row-major.
// hotPixelMap is flat [x0,y0,x1,y1,...] in stored-image pixels; out-of-bounds
// points are skipped. Only the JPEG/preview copy is concealed; DNG bytes stay
// untouched (a future FixBadPixels opcode can carry the same list).
// Returns the number of pixels actually replaced.
std::size_t concealHotPixelsInPackedRaw16(std::vector<uint8_t>& raw16, uint32_t width, uint32_t height,
                                          const std::vector<int32_t>& hotPixelMap) noexcept;

// Finds defective photosites the camera didn't report (X14U: no hot pixel map on any camera): a pixel far above
// (hot) or below (dead) all 8 same-colour neighbours at distance 2 whose 4 adjacent other-colour pixels don't
// share the peak (a real point of light spreads into them). Returns [x0,y0,...] for concealHotPixelsInPackedRaw16,
// at most maxPoints (none past that: the frame is not a defect pattern).
std::vector<int32_t> detectHotPixelsInPackedRaw16(const std::vector<uint8_t>& raw16, uint32_t width, uint32_t height,
                                                  float blackLevel, float whiteLevel,
                                                  std::size_t maxPoints = 200000) noexcept;

}  // namespace rawrcam::develop
