// X14U Pano: stitches a finished set's JPEG frames into one panorama with OpenCV's stitcher.
// Its own shared library (rawrcam_pano), so the camera never depends on OpenCV loading.

#include <android/log.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <string>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching.hpp>

namespace {

constexpr const char* kTag = "RawrPano";

bool readAll(int fd, std::vector<uchar>& out) {
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) return false;
    out.resize(static_cast<size_t>(st.st_size));
    size_t done = 0;
    while (done < out.size()) {
        const ssize_t n = read(fd, out.data() + done, out.size() - done);
        if (n <= 0) return false;
        done += static_cast<size_t>(n);
    }
    return true;
}

bool writeAll(int fd, const std::vector<uchar>& data) {
    size_t done = 0;
    while (done < data.size()) {
        const ssize_t n = write(fd, data.data() + done, data.size() - done);
        if (n <= 0) return false;
        done += static_cast<size_t>(n);
    }
    return true;
}

// Largest-ish rectangle inside the stitched area: shrink the bounding box from whichever side still touches the
// empty (black) border most, on a small copy of the mask.
cv::Rect innerRect(const cv::Mat& pano) {
    cv::Mat gray;
    cv::cvtColor(pano, gray, cv::COLOR_BGR2GRAY);
    const double scale = std::min(1.0, 1200.0 / std::max(gray.cols, gray.rows));
    cv::Mat small;
    cv::resize(gray, small, cv::Size(), scale, scale, cv::INTER_AREA);
    cv::Mat mask = small > 2;
    cv::erode(mask, mask, cv::Mat(), cv::Point(-1, -1), 2);
    cv::Rect r = cv::boundingRect(mask);
    if (r.width < 8 || r.height < 8) return {0, 0, pano.cols, pano.rows};
    for (int guard = 0; guard < 4000 && r.width > 8 && r.height > 8; ++guard) {
        const int top = r.width - cv::countNonZero(mask(cv::Rect(r.x, r.y, r.width, 1)));
        const int bottom = r.width - cv::countNonZero(mask(cv::Rect(r.x, r.y + r.height - 1, r.width, 1)));
        const int left = r.height - cv::countNonZero(mask(cv::Rect(r.x, r.y, 1, r.height)));
        const int right = r.height - cv::countNonZero(mask(cv::Rect(r.x + r.width - 1, r.y, 1, r.height)));
        // Compare as fractions of the side's length.
        const double ft = double(top) / r.width, fb = double(bottom) / r.width;
        const double fl = double(left) / r.height, fr = double(right) / r.height;
        const double worst = std::max({ft, fb, fl, fr});
        if (worst <= 0.0) break;
        if (worst == ft) { r.y += 1; r.height -= 1; }
        else if (worst == fb) { r.height -= 1; }
        else if (worst == fl) { r.x += 1; r.width -= 1; }
        else { r.width -= 1; }
    }
    const double inv = 1.0 / scale;
    cv::Rect full(int(std::ceil(r.x * inv)) + 1, int(std::ceil(r.y * inv)) + 1, int(std::floor(r.width * inv)) - 2,
                  int(std::floor(r.height * inv)) - 2);
    full &= cv::Rect(0, 0, pano.cols, pano.rows);
    if (full.width < pano.cols / 4 || full.height < pano.rows / 4) return {0, 0, pano.cols, pano.rows};
    return full;
}

std::string statusText(cv::Stitcher::Status status) {
    switch (status) {
        case cv::Stitcher::ERR_NEED_MORE_IMGS: return "frames didn't overlap enough to match";
        case cv::Stitcher::ERR_HOMOGRAPHY_EST_FAIL: return "couldn't line the frames up";
        case cv::Stitcher::ERR_CAMERA_PARAMS_ADJUST_FAIL: return "couldn't solve the camera turns";
        default: return "stitch failed";
    }
}

}  // namespace

// Frames as file descriptors (read and closed here) with each one's EXIF rotation; the panorama is written as JPEG
// to outFd (closed here). Returns "" on success, otherwise why it failed.
extern "C" JNIEXPORT jstring JNICALL Java_com_rawr_camera_pano_PanoStitcherNative_stitch(
    JNIEnv* env, jobject, jintArray fdsArray, jintArray rotationsArray, jfloat composeMegapixels, jint outFd,
    jint quality, jboolean crop) {
    std::vector<jint> fds(static_cast<size_t>(env->GetArrayLength(fdsArray)));
    std::vector<jint> rotations(static_cast<size_t>(env->GetArrayLength(rotationsArray)), 0);
    env->GetIntArrayRegion(fdsArray, 0, jsize(fds.size()), fds.data());
    if (!rotations.empty()) env->GetIntArrayRegion(rotationsArray, 0, jsize(rotations.size()), rotations.data());
    std::string error;
    try {
        std::vector<cv::Mat> frames;
        frames.reserve(fds.size());
        for (size_t i = 0; i < fds.size(); ++i) {
            std::vector<uchar> bytes;
            const bool ok = readAll(fds[i], bytes);
            close(fds[i]);
            fds[i] = -1;
            if (!ok) continue;
            cv::Mat image = cv::imdecode(bytes, cv::IMREAD_COLOR | cv::IMREAD_IGNORE_ORIENTATION);
            if (image.empty()) continue;
            const int rotation = i < rotations.size() ? rotations[i] : 0;
            if (rotation == 90) cv::rotate(image, image, cv::ROTATE_90_CLOCKWISE);
            else if (rotation == 180) cv::rotate(image, image, cv::ROTATE_180);
            else if (rotation == 270) cv::rotate(image, image, cv::ROTATE_90_COUNTERCLOCKWISE);
            frames.push_back(std::move(image));
        }
        if (frames.size() < 2) {
            error = "fewer than two frames could be read";
        } else {
            auto stitcher = cv::Stitcher::create(cv::Stitcher::PANORAMA);
            const double frameMp = double(frames[0].total()) / 1e6;
            if (composeMegapixels > 0.0f && composeMegapixels < frameMp) stitcher->setCompositingResol(composeMegapixels);
            cv::Mat pano;
            const auto status = stitcher->stitch(frames, pano);
            frames.clear();
            if (status != cv::Stitcher::OK || pano.empty()) {
                error = statusText(status);
            } else {
                if (crop == JNI_TRUE) pano = pano(innerRect(pano)).clone();
                std::vector<uchar> jpeg;
                if (!cv::imencode(".jpg", pano, jpeg, {cv::IMWRITE_JPEG_QUALITY, std::clamp(int(quality), 50, 100)}) ||
                    !writeAll(outFd, jpeg)) {
                    error = "couldn't write the panorama";
                } else {
                    __android_log_print(ANDROID_LOG_INFO, kTag, "PANO_STITCHED frames=%zu size=%dx%d bytes=%zu",
                                        fds.size(), pano.cols, pano.rows, jpeg.size());
                }
            }
        }
    } catch (const cv::Exception& e) {
        error = std::string("OpenCV: ") + e.what();
    } catch (const std::bad_alloc&) {
        error = "out of memory";
    } catch (...) {
        error = "stitch failed";
    }
    for (jint fd : fds)
        if (fd >= 0) close(fd);
    if (outFd >= 0) close(outFd);
    if (!error.empty()) __android_log_print(ANDROID_LOG_WARN, kTag, "PANO_STITCH_FAILED %s", error.c_str());
    return env->NewStringUTF(error.c_str());
}
