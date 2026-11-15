#include "camera/HalStillSession.h"

#include <camera/NdkCameraCaptureSession.h>
#include <camera/NdkCameraDevice.h>
#include <camera/NdkCameraManager.h>
#include <camera/NdkCameraMetadataTags.h>
#include <media/NdkImageReader.h>
#include <android/hardware_buffer.h>
#include <turbojpeg.h>

#include <algorithm>
#include <chrono>
#include <optional>
#include <vector>
#include <cerrno>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>

#include "camera/CameraMetadataTags.h"

namespace rawrcam::camera {
namespace {

using Clock = std::chrono::steady_clock;

// Request tags (ACaptureRequest user context) for the callbacks.
void* const kPreviewTag = reinterpret_cast<void*>(uintptr_t(1));
void* const kTriggerTag = reinterpret_cast<void*>(uintptr_t(2));
void* const kStillTag = reinterpret_cast<void*>(uintptr_t(3));

// Shared with the camera callbacks; freed only once the session reported closed (else leaked: a late callback
// must never touch freed memory).
struct State {
    std::mutex mutex;
    std::condition_variable changed;
    int previewResults = 0;
    int afState = -1;
    int lensState = -1;
    int aeState = -1;
    bool stillResult = false;
    bool stillFailed = false;
    bool sessionClosed = false;
    bool deviceError = false;
    int deviceErrorCode = 0;
    std::vector<uint8_t> jpeg;
    bool jpegReady = false;
    // YUV still (gcamYuv): planar I420 copy, encoded after the session work.
    std::vector<uint8_t> i420;
    int yuvWidth = 0;
    int yuvHeight = 0;
    std::string resultLine;
    std::optional<uint32_t> modeTag;
};

void* contextOf(const ACaptureRequest* request) {
    void* context = nullptr;
    if (request) (void)ACaptureRequest_getUserContext(request, &context);
    return context;
}

std::string describeResult(State* s, const ACameraMetadata* result) {
    const auto i64 = [&](uint32_t tag) -> long long {
        ACameraMetadata_const_entry e{};
        return ACameraMetadata_getConstEntry(result, tag, &e) == ACAMERA_OK && e.count ? (long long)e.data.i64[0] : -1;
    };
    const auto i32 = [&](uint32_t tag) -> long long {
        ACameraMetadata_const_entry e{};
        return ACameraMetadata_getConstEntry(result, tag, &e) == ACAMERA_OK && e.count ? (long long)e.data.i32[0] : -1;
    };
    const auto u8 = [&](uint32_t tag) -> int {
        ACameraMetadata_const_entry e{};
        return ACameraMetadata_getConstEntry(result, tag, &e) == ACAMERA_OK && e.count ? int(e.data.u8[0]) : -1;
    };
    const auto f = [&](uint32_t tag) -> double {
        ACameraMetadata_const_entry e{};
        return ACameraMetadata_getConstEntry(result, tag, &e) == ACAMERA_OK && e.count ? double(e.data.f[0]) : -1.0;
    };
    long long mode = -1;
    if (s->modeTag) {
        ACameraMetadata_const_entry e{};
        if (ACameraMetadata_getConstEntry(result, *s->modeTag, &e) == ACAMERA_OK && e.count) mode = e.data.i32[0];
    }
    char line[320];
    std::snprintf(line, sizeof(line),
                  "exposureNs=%lld iso=%lld aperture=%.2f focus=%.3f afMode=%d afState=%d lensState=%d aeState=%d "
                  "sensorMode=%lld",
                  i64(ACAMERA_SENSOR_EXPOSURE_TIME), i32(ACAMERA_SENSOR_SENSITIVITY), f(ACAMERA_LENS_APERTURE),
                  f(ACAMERA_LENS_FOCUS_DISTANCE), u8(ACAMERA_CONTROL_AF_MODE), u8(ACAMERA_CONTROL_AF_STATE),
                  u8(ACAMERA_LENS_STATE), u8(ACAMERA_CONTROL_AE_STATE), mode);
    return line;
}

void onCompleted(void* context, ACameraCaptureSession*, ACaptureRequest* request, const ACameraMetadata* result) {
    auto* s = static_cast<State*>(context);
    const void* tag = contextOf(request);
    ACameraMetadata_const_entry e{};
    std::lock_guard<std::mutex> lock(s->mutex);
    if (ACameraMetadata_getConstEntry(result, ACAMERA_CONTROL_AF_STATE, &e) == ACAMERA_OK && e.count)
        s->afState = e.data.u8[0];
    if (ACameraMetadata_getConstEntry(result, ACAMERA_LENS_STATE, &e) == ACAMERA_OK && e.count)
        s->lensState = e.data.u8[0];
    if (ACameraMetadata_getConstEntry(result, ACAMERA_CONTROL_AE_STATE, &e) == ACAMERA_OK && e.count)
        s->aeState = e.data.u8[0];
    if (tag == kStillTag) {
        s->stillResult = true;
        s->resultLine = describeResult(s, result);
    } else {
        ++s->previewResults;
        if (s->previewResults == 1 || s->previewResults % 15 == 0) s->resultLine = describeResult(s, result);
    }
    s->changed.notify_all();
}

void onFailed(void* context, ACameraCaptureSession*, ACaptureRequest* request, ACameraCaptureFailure*) {
    auto* s = static_cast<State*>(context);
    if (contextOf(request) != kStillTag) return;
    std::lock_guard<std::mutex> lock(s->mutex);
    s->stillFailed = true;
    s->changed.notify_all();
}

void onSessionClosed(void* context, ACameraCaptureSession*) {
    auto* s = static_cast<State*>(context);
    std::lock_guard<std::mutex> lock(s->mutex);
    s->sessionClosed = true;
    s->changed.notify_all();
}
void onSessionNothing(void*, ACameraCaptureSession*) {}
void onDeviceDisconnected(void* context, ACameraDevice*) {
    auto* s = static_cast<State*>(context);
    std::lock_guard<std::mutex> lock(s->mutex);
    s->deviceError = true;
    s->changed.notify_all();
}
void onDeviceError(void* context, ACameraDevice*, int error) {
    auto* s = static_cast<State*>(context);
    std::lock_guard<std::mutex> lock(s->mutex);
    s->deviceError = true;
    s->deviceErrorCode = error;
    s->changed.notify_all();
}

void drainImages(void*, AImageReader* reader) {
    AImage* image = nullptr;
    while (AImageReader_acquireNextImage(reader, &image) == AMEDIA_OK && image) {
        AImage_delete(image);
        image = nullptr;
    }
}

void takeJpeg(void* context, AImageReader* reader) {
    auto* s = static_cast<State*>(context);
    AImage* image = nullptr;
    while (AImageReader_acquireNextImage(reader, &image) == AMEDIA_OK && image) {
        uint8_t* data = nullptr;
        int length = 0;
        if (AImage_getPlaneData(image, 0, &data, &length) == AMEDIA_OK && data && length > 4) {
            std::size_t size = std::size_t(length);
            for (std::size_t i = size; i >= 2 && i + 65536 >= size; --i)
                if (data[i - 2] == 0xFF && data[i - 1] == 0xD9) {
                    size = i;
                    break;
                }
            std::lock_guard<std::mutex> lock(s->mutex);
            if (!s->jpegReady) {
                s->jpeg.assign(data, data + size);
                s->jpegReady = true;
                s->changed.notify_all();
            }
        }
        AImage_delete(image);
        image = nullptr;
    }
}

// YUV still: the planes copied as I420 (the HAL's YUV_420_888 is usually NV21/NV12 with pixel stride 2).
void takeYuv(void* context, AImageReader* reader) {
    auto* s = static_cast<State*>(context);
    AImage* image = nullptr;
    while (AImageReader_acquireNextImage(reader, &image) == AMEDIA_OK && image) {
        int32_t w = 0, h = 0;
        (void)AImage_getWidth(image, &w);
        (void)AImage_getHeight(image, &h);
        bool ready;
        {
            std::lock_guard<std::mutex> lock(s->mutex);
            ready = s->jpegReady;
        }
        if (!ready && w > 0 && h > 0) {
            std::vector<uint8_t> out(std::size_t(w) * h * 3 / 2);
            bool ok = true;
            for (int plane = 0; plane < 3 && ok; ++plane) {
                uint8_t* data = nullptr;
                int length = 0;
                int32_t rowStride = 0, pixelStride = 0;
                ok = AImage_getPlaneData(image, plane, &data, &length) == AMEDIA_OK && data &&
                     AImage_getPlaneRowStride(image, plane, &rowStride) == AMEDIA_OK &&
                     AImage_getPlanePixelStride(image, plane, &pixelStride) == AMEDIA_OK;
                if (!ok) break;
                const int pw = plane ? w / 2 : w;
                const int ph = plane ? h / 2 : h;
                uint8_t* dst = out.data() + (plane == 0 ? 0 : std::size_t(w) * h + (plane - 1) * std::size_t(pw) * ph);
                for (int y = 0; y < ph; ++y) {
                    const uint8_t* row = data + std::size_t(y) * rowStride;
                    if (pixelStride == 1) {
                        std::copy(row, row + pw, dst + std::size_t(y) * pw);
                    } else {
                        for (int x = 0; x < pw; ++x) dst[std::size_t(y) * pw + x] = row[std::size_t(x) * pixelStride];
                    }
                }
            }
            if (ok) {
                std::lock_guard<std::mutex> lock(s->mutex);
                if (!s->jpegReady) {
                    s->i420 = std::move(out);
                    s->yuvWidth = w;
                    s->yuvHeight = h;
                    s->jpegReady = true;
                    s->changed.notify_all();
                }
            }
        }
        AImage_delete(image);
        image = nullptr;
    }
}

std::vector<uint8_t> encodeI420(const std::vector<uint8_t>& i420, int w, int h, std::string* error) {
    std::vector<uint8_t> out;
    tjhandle tj = tj3Init(TJINIT_COMPRESS);
    if (!tj) {
        *error = "tj3Init";
        return out;
    }
    (void)tj3Set(tj, TJPARAM_QUALITY, 97);
    (void)tj3Set(tj, TJPARAM_SUBSAMP, TJSAMP_420);
    unsigned char* jpeg = nullptr;
    size_t size = 0;
    if (tj3CompressFromYUV8(tj, i420.data(), w, 1, h, &jpeg, &size) == 0 && jpeg) out.assign(jpeg, jpeg + size);
    else *error = tj3GetErrorStr(tj);
    if (jpeg) tj3Free(jpeg);
    tj3Destroy(tj);
    return out;
}

// Frame size from the JPEG's SOF marker (what the camera really encoded).
std::pair<int, int> jpegSize(const std::vector<uint8_t>& d) {
    std::size_t i = 2;
    while (i + 9 < d.size() && d[i] == 0xFF) {
        const uint8_t marker = d[i + 1];
        const std::size_t len = (std::size_t(d[i + 2]) << 8) | d[i + 3];
        if (marker >= 0xC0 && marker <= 0xC2)
            return {int((d[i + 7] << 8) | d[i + 8]), int((d[i + 5] << 8) | d[i + 6])};
        if (marker == 0xDA) break;
        i += 2 + len;
    }
    return {0, 0};
}

// The lens's vendor keys of `scope` (session, request or still) onto a request.
int applyKeys(ACaptureRequest* request, const ACameraMetadata* chars, const std::string& cameraId,
              const std::vector<CameraKeySetting>& keys, CameraKeySetting::Scope scope,
              const std::function<void(const std::string&)>& diag) {
    int applied = 0;
    for (const auto& k : keys) {
        if (!k.enabled || k.values.empty() || !keyAppliesIn(k.scope, scope)) continue;
        const auto tag = resolveMetadataTagByNameCompat(chars, cameraId.c_str(), k.tag.c_str(), nullptr);
        if (!tag) {
            diag("HALS_KEY_UNKNOWN tag=" + k.tag);
            continue;
        }
        camera_status_t status = ACAMERA_ERROR_INVALID_PARAMETER;
        const uint32_t n = uint32_t(k.values.size());
        switch (k.type) {
            case CameraKeySetting::Type::Byte: {
                std::vector<uint8_t> v;
                for (double x : k.values) v.push_back(uint8_t(std::lround(x)));
                status = ACaptureRequest_setEntry_u8(request, *tag, n, v.data());
                break;
            }
            case CameraKeySetting::Type::Int32: {
                std::vector<int32_t> v;
                for (double x : k.values) v.push_back(int32_t(std::lround(x)));
                status = ACaptureRequest_setEntry_i32(request, *tag, n, v.data());
                break;
            }
            case CameraKeySetting::Type::Int64: {
                std::vector<int64_t> v;
                for (double x : k.values) v.push_back(int64_t(std::llround(x)));
                status = ACaptureRequest_setEntry_i64(request, *tag, n, v.data());
                break;
            }
            case CameraKeySetting::Type::Float: {
                std::vector<float> v;
                for (double x : k.values) v.push_back(float(x));
                status = ACaptureRequest_setEntry_float(request, *tag, n, v.data());
                break;
            }
            case CameraKeySetting::Type::Double: {
                std::vector<double> v(k.values.begin(), k.values.end());
                status = ACaptureRequest_setEntry_double(request, *tag, n, v.data());
                break;
            }
        }
        if (status == ACAMERA_OK) ++applied;
        else diag("HALS_KEY_FAILED tag=" + k.tag + " status=" + std::to_string(status));
    }
    return applied;
}

}  // namespace

bool runHalStillSession(const HalStillParams& p, const std::function<void(const std::string&)>& diag) {
    const auto begin = Clock::now();
    const auto ms = [&] { return std::chrono::duration<double, std::milli>(Clock::now() - begin).count(); };
    auto* state = new State();
    ACameraManager* manager = ACameraManager_create();
    ACameraMetadata* chars = nullptr;
    ACameraDevice* device = nullptr;
    AImageReader* previewReader = nullptr;
    AImageReader* analysisReader = nullptr;  // gcam layouts: GCam's 640x480 YUV stream
    AImageReader* stillReader = nullptr;
    ACaptureSessionOutputContainer* container = nullptr;
    ACaptureSessionOutput* previewOutput = nullptr;
    ACaptureSessionOutput* stillOutput = nullptr;
    ACaptureSessionOutput* analysisOutput = nullptr;
    ACameraOutputTarget* analysisTarget = nullptr;
    ACameraOutputTarget* previewTarget = nullptr;
    ACameraOutputTarget* stillTarget = nullptr;
    ACaptureRequest* sessionParams = nullptr;
    ACaptureRequest* preview = nullptr;
    ACaptureRequest* trigger = nullptr;
    ACaptureRequest* still = nullptr;
    ACameraCaptureSession* session = nullptr;
    bool written = false;
    std::string failure;
    int32_t orientation = p.jpegOrientation;  // < 0: the sensor's (upright when held as the sensor is mounted)

    ACameraDevice_StateCallbacks deviceCallbacks{state, onDeviceDisconnected, onDeviceError};
    ACameraCaptureSession_stateCallbacks sessionCallbacks{state, onSessionClosed, onSessionNothing, onSessionNothing};
    ACameraCaptureSession_captureCallbacks captureCallbacks{};
    captureCallbacks.context = state;
    captureCallbacks.onCaptureCompleted = onCompleted;
    captureCallbacks.onCaptureFailed = onFailed;

    const auto waitFor = [&](std::chrono::milliseconds timeout, const std::function<bool()>& ready) {
        std::unique_lock<std::mutex> lock(state->mutex);
        return state->changed.wait_for(lock, timeout, [&] { return ready() || state->deviceError; }) && !state->deviceError;
    };

    do {
        if (!manager) { failure = "manager"; break; }
        if (ACameraManager_getCameraCharacteristics(manager, p.cameraId.c_str(), &chars) != ACAMERA_OK || !chars) {
            failure = "characteristics";
            break;
        }
        if (orientation < 0) {
            ACameraMetadata_const_entry e{};
            orientation = ACameraMetadata_getConstEntry(chars, ACAMERA_SENSOR_ORIENTATION, &e) == ACAMERA_OK && e.count
                              ? e.data.i32[0]
                              : 0;
        }
        state->modeTag = resolveMetadataTagByNameCompat(chars, p.cameraId.c_str(),
                                                         "org.codeaurora.qcamera3.sensor_meta_data.current_mode", nullptr);
        // The normal session's device closes on its own thread: retry an "in use" refusal for a while.
        camera_status_t opened = ACAMERA_ERROR_UNKNOWN;
        for (int attempt = 0; attempt < 30; ++attempt) {
            opened = ACameraManager_openCamera(manager, p.cameraId.c_str(), &deviceCallbacks, &device);
            if (opened == ACAMERA_OK && device) break;
            device = nullptr;
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        if (!device) { failure = "open status=" + std::to_string(opened); break; }
        diag("HALS_OPENED camera=" + p.cameraId + " physical=" + (p.physicalId.empty() ? "none" : p.physicalId) +
             " ms=" + std::to_string(ms()));

        // Outputs. Own layout: a 4:3 processed YUV preview for AF/AE (not shown) and the full-size JPEG. GCam layout
        // (its live 50 MP sessions, dumpsys 15 Nov): PRIVATE 1440x1080 preview + 640x480 YUV + the full-size JPEG
        // (aux lenses) or YUV (main, physical 2 of logical 0). No RAW either way.
        const int previewW = p.gcamLayout ? 1440 : 1920;
        const int previewH = p.gcamLayout ? 1080 : 1440;
        const media_status_t previewMade =
            p.gcamLayout ? AImageReader_newWithUsage(previewW, previewH, AIMAGE_FORMAT_PRIVATE,
                                                     AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE, 4, &previewReader)
                         : AImageReader_new(previewW, previewH, AIMAGE_FORMAT_YUV_420_888, 4, &previewReader);
        if (previewMade != AMEDIA_OK ||
            (p.gcamLayout && AImageReader_new(640, 480, AIMAGE_FORMAT_YUV_420_888, 4, &analysisReader) != AMEDIA_OK) ||
            (p.stillUsage ? AImageReader_newWithUsage(p.width, p.height,
                                                      p.yuvStill ? AIMAGE_FORMAT_YUV_420_888 : AIMAGE_FORMAT_JPEG,
                                                      p.stillUsage, 2, &stillReader)
                          : AImageReader_new(p.width, p.height, p.yuvStill ? AIMAGE_FORMAT_YUV_420_888 : AIMAGE_FORMAT_JPEG,
                                             2, &stillReader)) != AMEDIA_OK) {
            failure = "readers";
            break;
        }
        AImageReader_ImageListener drain{state, drainImages};
        AImageReader_ImageListener take{state, p.yuvStill ? takeYuv : takeJpeg};
        (void)AImageReader_setImageListener(previewReader, &drain);
        if (analysisReader) (void)AImageReader_setImageListener(analysisReader, &drain);
        (void)AImageReader_setImageListener(stillReader, &take);
        ANativeWindow* previewWindow = nullptr;
        ANativeWindow* analysisWindow = nullptr;
        ANativeWindow* stillWindow = nullptr;
        (void)AImageReader_getWindow(previewReader, &previewWindow);
        if (analysisReader) (void)AImageReader_getWindow(analysisReader, &analysisWindow);
        (void)AImageReader_getWindow(stillReader, &stillWindow);
        if (!previewWindow || !stillWindow || (analysisReader && !analysisWindow)) { failure = "windows"; break; }
        const auto makeOutput = [&](ANativeWindow* window, ACaptureSessionOutput** out) {
            return p.physicalId.empty() ? ACaptureSessionOutput_create(window, out)
                                        : ACaptureSessionPhysicalOutput_create(window, p.physicalId.c_str(), out);
        };
        if (ACaptureSessionOutputContainer_create(&container) != ACAMERA_OK ||
            makeOutput(previewWindow, &previewOutput) != ACAMERA_OK || makeOutput(stillWindow, &stillOutput) != ACAMERA_OK ||
            ACaptureSessionOutputContainer_add(container, previewOutput) != ACAMERA_OK ||
            ACaptureSessionOutputContainer_add(container, stillOutput) != ACAMERA_OK ||
            ACameraOutputTarget_create(previewWindow, &previewTarget) != ACAMERA_OK ||
            ACameraOutputTarget_create(stillWindow, &stillTarget) != ACAMERA_OK) {
            failure = "outputs";
            break;
        }
        if (analysisWindow && (makeOutput(analysisWindow, &analysisOutput) != ACAMERA_OK ||
                               ACaptureSessionOutputContainer_add(container, analysisOutput) != ACAMERA_OK ||
                               ACameraOutputTarget_create(analysisWindow, &analysisTarget) != ACAMERA_OK)) {
            failure = "analysis_output";
            break;
        }

        // Session parameters: the lens's session keys (the stock camera's client name and the like).
        if (ACameraDevice_createCaptureRequest(device, TEMPLATE_PREVIEW, &sessionParams) != ACAMERA_OK) {
            failure = "session_params";
            break;
        }
        const int sessionKeys = applyKeys(sessionParams, chars, p.cameraId, p.keys, CameraKeySetting::Scope::Session, diag);
        if (const auto s = ACameraDevice_createCaptureSessionWithSessionParameters(device, container, sessionParams,
                                                                                     &sessionCallbacks, &session);
            s != ACAMERA_OK || !session) {
            failure = "session status=" + std::to_string(s);
            break;
        }
        diag("HALS_SESSION layout=" + std::string(p.gcamLayout ? "gcam" : "own") + " preview=" +
             std::to_string(previewW) + "x" + std::to_string(previewH) + (p.gcamLayout ? " private+640x480yuv" : " yuv") +
             " still=" + std::string(p.yuvStill ? "yuv " : "jpeg ") + std::to_string(p.width) + "x" + std::to_string(p.height) +
             " stillUsage=0x" + [&] { char b[20]; std::snprintf(b, sizeof(b), "%llx", (unsigned long long)p.stillUsage); return std::string(b); }() +
             " sessionKeys=" + std::to_string(sessionKeys) + " ms=" + std::to_string(ms()));

        // Viewfinder-like repeating request: AF / AE converge here, in the binned processed mode.
        if (ACameraDevice_createCaptureRequest(device, TEMPLATE_PREVIEW, &preview) != ACAMERA_OK) {
            failure = "preview_request";
            break;
        }
        (void)ACaptureRequest_addTarget(preview, previewTarget);
        if (analysisTarget) (void)ACaptureRequest_addTarget(preview, analysisTarget);
        (void)ACaptureRequest_setUserContext(preview, kPreviewTag);
        const auto common = [&](ACaptureRequest* r) {
            applyKeys(r, chars, p.cameraId, p.keys, CameraKeySetting::Scope::Request, diag);
            if (p.afRegions.size() == 5) (void)ACaptureRequest_setEntry_i32(r, ACAMERA_CONTROL_AF_REGIONS, 5, p.afRegions.data());
            if (p.aeRegions.size() == 5) (void)ACaptureRequest_setEntry_i32(r, ACAMERA_CONTROL_AE_REGIONS, 5, p.aeRegions.data());
            if (p.manualFocus) {
                const uint8_t off = ACAMERA_CONTROL_AF_MODE_OFF;
                (void)ACaptureRequest_setEntry_u8(r, ACAMERA_CONTROL_AF_MODE, 1, &off);
                (void)ACaptureRequest_setEntry_float(r, ACAMERA_LENS_FOCUS_DISTANCE, 1, &p.focusDiopters);
            } else {
                const uint8_t cont = ACAMERA_CONTROL_AF_MODE_CONTINUOUS_PICTURE;
                (void)ACaptureRequest_setEntry_u8(r, ACAMERA_CONTROL_AF_MODE, 1, &cont);
            }
            if (p.manualExposure && p.exposureTimeNs > 0 && p.sensitivity > 0) {
                const uint8_t aeOff = ACAMERA_CONTROL_AE_MODE_OFF;
                const int64_t frame = std::max<int64_t>(p.exposureTimeNs, 33'333'333);
                (void)ACaptureRequest_setEntry_u8(r, ACAMERA_CONTROL_AE_MODE, 1, &aeOff);
                (void)ACaptureRequest_setEntry_i64(r, ACAMERA_SENSOR_EXPOSURE_TIME, 1, &p.exposureTimeNs);
                (void)ACaptureRequest_setEntry_i32(r, ACAMERA_SENSOR_SENSITIVITY, 1, &p.sensitivity);
                (void)ACaptureRequest_setEntry_i64(r, ACAMERA_SENSOR_FRAME_DURATION, 1, &frame);
            } else {
                const uint8_t aeOn = ACAMERA_CONTROL_AE_MODE_ON;
                (void)ACaptureRequest_setEntry_u8(r, ACAMERA_CONTROL_AE_MODE, 1, &aeOn);
                (void)ACaptureRequest_setEntry_i32(r, ACAMERA_CONTROL_AE_EXPOSURE_COMPENSATION, 1, &p.evSteps);
            }
        };
        common(preview);
        int sequence = 0;
        if (ACameraCaptureSession_setRepeatingRequest(session, &captureCallbacks, 1, &preview, &sequence) != ACAMERA_OK) {
            failure = "repeating";
            break;
        }
        if (!waitFor(std::chrono::milliseconds(3000), [&] { return state->previewResults >= 8; })) {
            failure = "no_preview_results";
            break;
        }
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            diag("HALS_PREVIEW frames=" + std::to_string(state->previewResults) + " " + state->resultLine +
                 " ms=" + std::to_string(ms()));
        }

        // AF: lock focus at the point (the stock camera's tap-to-shoot sequence); manual focus keeps its distance.
        if (!p.manualFocus) {
            if (ACameraDevice_createCaptureRequest(device, TEMPLATE_PREVIEW, &trigger) == ACAMERA_OK) {
                (void)ACaptureRequest_addTarget(trigger, previewTarget);
                if (analysisTarget) (void)ACaptureRequest_addTarget(trigger, analysisTarget);
                (void)ACaptureRequest_setUserContext(trigger, kTriggerTag);
                common(trigger);
                const uint8_t start = ACAMERA_CONTROL_AF_TRIGGER_START;
                (void)ACaptureRequest_setEntry_u8(trigger, ACAMERA_CONTROL_AF_TRIGGER, 1, &start);
                int triggerSequence = 0;
                (void)ACameraCaptureSession_capture(session, &captureCallbacks, 1, &trigger, &triggerSequence);
                const bool locked = waitFor(std::chrono::milliseconds(2000), [&] {
                    return state->afState == ACAMERA_CONTROL_AF_STATE_FOCUSED_LOCKED ||
                           state->afState == ACAMERA_CONTROL_AF_STATE_NOT_FOCUSED_LOCKED;
                });
                std::lock_guard<std::mutex> lock(state->mutex);
                diag("HALS_AF locked=" + std::to_string(locked ? 1 : 0) + " afState=" + std::to_string(state->afState) +
                     " lensState=" + std::to_string(state->lensState) + " ms=" + std::to_string(ms()));
            }
        } else {
            // Let the lens reach the manual distance.
            (void)waitFor(std::chrono::milliseconds(800), [&] { return state->lensState == ACAMERA_LENS_STATE_STATIONARY; });
        }

        // The full-size still: only the JPEG output; the HAL switches its readout for it.
        if (ACameraDevice_createCaptureRequest(device, TEMPLATE_STILL_CAPTURE, &still) != ACAMERA_OK) {
            failure = "still_request";
            break;
        }
        (void)ACaptureRequest_addTarget(still, stillTarget);
        (void)ACaptureRequest_setUserContext(still, kStillTag);
        common(still);
        applyKeys(still, chars, p.cameraId, p.keys, CameraKeySetting::Scope::Still, diag);
        {
            const uint8_t quality = 98;
            (void)ACaptureRequest_setEntry_u8(still, ACAMERA_JPEG_QUALITY, 1, &quality);
            // A fresh full-size exposure, not a ZSL frame from the binned preview (GCam's full-res stills: Aurora84PeerOptions.zz).
            const uint8_t zslOff = ACAMERA_CONTROL_ENABLE_ZSL_FALSE;
            (void)ACaptureRequest_setEntry_u8(still, ACAMERA_CONTROL_ENABLE_ZSL, 1, &zslOff);
            (void)ACaptureRequest_setEntry_i32(still, ACAMERA_JPEG_ORIENTATION, 1, &orientation);
            if (!p.manualExposure) {
                const uint8_t lock = ACAMERA_CONTROL_AE_LOCK_ON;
                (void)ACaptureRequest_setEntry_u8(still, ACAMERA_CONTROL_AE_LOCK, 1, &lock);
            }
            if (p.plain) {
                const uint8_t nrOff = ACAMERA_NOISE_REDUCTION_MODE_OFF;
                const uint8_t edgeOff = ACAMERA_EDGE_MODE_OFF;
                const uint8_t presetCurve = ACAMERA_TONEMAP_MODE_PRESET_CURVE;
                const uint8_t srgb = ACAMERA_TONEMAP_PRESET_CURVE_SRGB;
                const uint8_t awbOff = ACAMERA_CONTROL_AWB_MODE_OFF;
                const uint8_t matrix = ACAMERA_COLOR_CORRECTION_MODE_TRANSFORM_MATRIX;
                const float gains[4] = {1.0f, 1.0f, 1.0f, 1.0f};
                const ACameraMetadata_rational identity[9] = {{1, 1}, {0, 1}, {0, 1}, {0, 1}, {1, 1},
                                                              {0, 1}, {0, 1}, {0, 1}, {1, 1}};
                (void)ACaptureRequest_setEntry_u8(still, ACAMERA_NOISE_REDUCTION_MODE, 1, &nrOff);
                (void)ACaptureRequest_setEntry_u8(still, ACAMERA_EDGE_MODE, 1, &edgeOff);
                (void)ACaptureRequest_setEntry_u8(still, ACAMERA_TONEMAP_MODE, 1, &presetCurve);
                (void)ACaptureRequest_setEntry_u8(still, ACAMERA_TONEMAP_PRESET_CURVE, 1, &srgb);
                (void)ACaptureRequest_setEntry_u8(still, ACAMERA_CONTROL_AWB_MODE, 1, &awbOff);
                (void)ACaptureRequest_setEntry_u8(still, ACAMERA_COLOR_CORRECTION_MODE, 1, &matrix);
                (void)ACaptureRequest_setEntry_float(still, ACAMERA_COLOR_CORRECTION_GAINS, 4, gains);
                (void)ACaptureRequest_setEntry_rational(still, ACAMERA_COLOR_CORRECTION_TRANSFORM, 9, identity);
            }
        }
        int stillSequence = 0;
        if (const auto s = ACameraCaptureSession_capture(session, &captureCallbacks, 1, &still, &stillSequence);
            s != ACAMERA_OK) {
            failure = "still_capture status=" + std::to_string(s);
            break;
        }
        const bool got = waitFor(std::chrono::milliseconds(8000),
                                 [&] { return (state->jpegReady && state->stillResult) || state->stillFailed; });
        std::vector<uint8_t> jpeg;
        std::vector<uint8_t> i420;
        int yuvW = 0, yuvH = 0;
        std::string result;
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            jpeg = state->jpeg;
            i420.swap(state->i420);
            yuvW = state->yuvWidth;
            yuvH = state->yuvHeight;
            result = state->stillResult ? state->resultLine : std::string("none");
            if (state->stillFailed) failure = "still_failed";
        }
        diag("HALS_RESULT " + result + " ms=" + std::to_string(ms()));
        if (got && p.yuvStill && !i420.empty()) {
            diag("HALS_YUV size=" + std::to_string(yuvW) + "x" + std::to_string(yuvH) + " ms=" + std::to_string(ms()));
            std::string error;
            jpeg = encodeI420(i420, yuvW, yuvH, &error);
            if (jpeg.empty()) diag("HALS_ENCODE_FAILED " + error);
        }
        if (!got || jpeg.empty()) {
            if (failure.empty()) failure = "no_jpeg";
            break;
        }
        const auto [jw, jh] = jpegSize(jpeg);
        FILE* file = std::fopen(p.path.c_str(), "wb");
        written = file && std::fwrite(jpeg.data(), 1, jpeg.size(), file) == jpeg.size();
        const int error = file ? 0 : errno;
        if (file) std::fclose(file);
        diag(std::string(written ? "HALS_SAVED" : "HALS_SAVE_FAILED") + " path=" + p.path + " bytes=" +
             std::to_string(jpeg.size()) + " jpeg=" + std::to_string(jw) + "x" + std::to_string(jh) +
             (written ? "" : " errno=" + std::to_string(error)) + " ms=" + std::to_string(ms()));
    } while (false);

    if (!failure.empty()) {
        std::lock_guard<std::mutex> lock(state->mutex);
        diag("HALS_FAILED reason=" + failure + " deviceError=" + std::to_string(state->deviceErrorCode) +
             " ms=" + std::to_string(ms()));
    }
    // Close the session (wait for it, bounded), then the device; free what the camera no longer uses.
    bool closed = !session;
    if (session) {
        (void)ACameraCaptureSession_stopRepeating(session);
        ACameraCaptureSession_close(session);
        closed = waitFor(std::chrono::milliseconds(1500), [&] { return state->sessionClosed; }) || state->sessionClosed;
    }
    if (device) ACameraDevice_close(device);
    for (auto* r : {sessionParams, preview, trigger, still})
        if (r) ACaptureRequest_free(r);
    if (previewTarget) ACameraOutputTarget_free(previewTarget);
    if (stillTarget) ACameraOutputTarget_free(stillTarget);
    if (previewOutput) ACaptureSessionOutput_free(previewOutput);
    if (analysisTarget) ACameraOutputTarget_free(analysisTarget);
    if (analysisOutput) ACaptureSessionOutput_free(analysisOutput);
    if (analysisReader) AImageReader_delete(analysisReader);
    if (stillOutput) ACaptureSessionOutput_free(stillOutput);
    if (container) ACaptureSessionOutputContainer_free(container);
    if (previewReader) AImageReader_delete(previewReader);
    if (stillReader) AImageReader_delete(stillReader);
    if (chars) ACameraMetadata_free(chars);
    if (manager) ACameraManager_delete(manager);
    if (closed) delete state;  // else leaked: a late callback may still arrive
    diag("HALS_DONE written=" + std::to_string(written ? 1 : 0) + " ms=" + std::to_string(ms()));
    return written;
}

}  // namespace rawrcam::camera
