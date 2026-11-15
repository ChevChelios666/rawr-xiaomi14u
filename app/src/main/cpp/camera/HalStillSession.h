#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include "camera/CameraRouting.h"

namespace rawrcam::camera {

// X14U HAL 50 MP shot in its own short session, laid out like the stock camera's: a small processed (YUV) preview
// for AF/AE plus the full-size output, no RAW stream. A RAW viewfinder stream pins the sensor to its binned mode
// (15 Nov: every HAL still beside the 12 MP RAW stream came back 1920x1440 from sensor mode 1). The caller closes
// the normal session first and reopens it afterwards; this opens the camera itself and closes it before returning.
struct HalStillParams {
    std::string cameraId;
    std::string physicalId;  // empty: outputs on cameraId itself
    int32_t width = 8192;
    int32_t height = 6144;
    bool plain = false;  // NR/edge off, sRGB curve, identity colour, unit gains, AWB off (Rawr develops it)
    // GCam's stream layout (its live 50 MP sessions): PRIVATE 1440x1080 preview + 640x480 YUV beside the still.
    bool gcamLayout = false;
    bool yuvStill = false;  // full-size YUV still (encoded here) instead of the HAL's JPEG
    // Consumer usage of the still's reader (0: the reader default). Xiaomi's camera service sizes an output it
    // can't find in its tables down to <= 1920 wide unless the consumer usage carries a "disallowed for rounding"
    // flag: GCam's 8192x6144 readers carry 0x100000 (gralloc RENDERSCRIPT), logcat 15 Nov.
    uint64_t stillUsage = 0;
    std::vector<CameraKeySetting> keys;  // the lens's vendor keys (session / request / still scopes)
    bool manualFocus = false;
    float focusDiopters = 0.0f;
    std::vector<int32_t> afRegions;  // 5 values or empty
    std::vector<int32_t> aeRegions;
    int32_t evSteps = 0;
    bool manualExposure = false;
    int64_t exposureTimeNs = 0;
    int32_t sensitivity = 0;
    int32_t jpegOrientation = -1;  // < 0: the sensor orientation
    std::string path;  // where the JPEG goes
};

// Runs the shot; true when the JPEG was written. Logs HALS_* lines.
bool runHalStillSession(const HalStillParams& params, const std::function<void(const std::string&)>& diag);

}  // namespace rawrcam::camera
