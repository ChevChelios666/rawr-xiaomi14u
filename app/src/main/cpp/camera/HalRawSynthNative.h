#pragma once

#include <cstddef>
#include <cstdint>

// X14U 50 MP through the camera's own pipeline: the plain still (NV21 or JPEG, camera RGB through a known curve) laid
// out as a linear RGGB uint16 mosaic, natively and on several threads (the Kotlin loops took 1.8 s / 3.8 s).
namespace rawrcam::camera::halsynth {

bool jpegSize(const uint8_t* jpeg, size_t size, int& width, int& height);

// [lut]: 256 entries, 8-bit code -> linear 0..65535. [height] rows (even) of [width] go to [out].
bool jpegToMosaic(const uint8_t* jpeg, size_t size, uint16_t* out, int width, int height, const int32_t* lut);

// NV21 (full-range BT.601). [lut]: 255 * 16 + 1 entries at 1/16 code steps.
bool nv21ToMosaic(const uint8_t* nv21, int width, int height, uint16_t* out, const int32_t* lut);

}  // namespace rawrcam::camera::halsynth
