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
