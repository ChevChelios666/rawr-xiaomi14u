// X14U Pano: stitches a finished set's JPEG frames into one panorama with OpenCV's stitcher.
// Its own shared library (rawrcam_pano), so the camera never depends on OpenCV loading.

#include <android/log.h>
#include <jni.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching.hpp>
#include <opencv2/stitching/detail/util.hpp>
#include <opencv2/stitching/warpers.hpp>

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

// The canvas composePanorama() will allocate, from the stitcher's own estimate (same steps as OpenCV's
// Stitcher::composePanorama: compose scale from the first frame, median focal as the warp scale, each frame's
// warped ROI at compose scale). Width x height in pixels; 0 x 0 if it can't be worked out.
cv::Size plannedPanoCanvas(cv::Stitcher& st, const std::vector<cv::Mat>& frames) {
    const auto cameras = st.cameras();
    const auto component = st.component();
    if (cameras.empty() || cameras.size() != component.size() || frames.empty()) return {};
    const double area = double(frames[component[0]].size().area());
    const double reg = st.registrationResol(), comp = st.compositingResol();
    const double work = reg < 0 ? 1.0 : std::min(1.0, std::sqrt(reg * 1e6 / area));
    const double compose = comp < 0 ? 1.0 : std::min(1.0, std::sqrt(comp * 1e6 / area));
    const double aspect = compose / work;
    std::vector<double> focals;
    for (const auto& c : cameras) focals.push_back(c.focal);
    std::sort(focals.begin(), focals.end());
    const size_t n = focals.size();
    const double median = n % 2 ? focals[n / 2] : 0.5 * (focals[n / 2 - 1] + focals[n / 2]);
    const cv::Ptr<cv::detail::RotationWarper> warper = st.warper()->create(float(median * aspect));
    std::vector<cv::Point> corners;
    std::vector<cv::Size> sizes;
    for (size_t i = 0; i < cameras.size(); ++i) {
        cv::detail::CameraParams c = cameras[i];
        c.focal *= aspect;
        c.ppx *= aspect;
        c.ppy *= aspect;
        cv::Mat K, R;
        c.K().convertTo(K, CV_32F);
        c.R.convertTo(R, CV_32F);
        const cv::Size full = frames[component[i]].size();
        const cv::Size sz(cvRound(full.width * compose), cvRound(full.height * compose));
        const cv::Rect roi = warper->warpRoi(sz, K, R);
        corners.push_back(roi.tl());
        sizes.push_back(roi.size());
    }
    return cv::detail::resultRoi(corners, sizes).size();
}

// The phone's free memory (MemAvailable), 0 if unknown.
double availableBytes() {
    FILE* f = std::fopen("/proc/meminfo", "r");
    if (!f) return 0.0;
    char line[256];
    double kb = 0.0;
    while (std::fgets(line, sizeof line, f))
        if (std::strncmp(line, "MemAvailable:", 13) == 0) {
            kb = std::atof(line + 13);
            break;
        }
    std::fclose(f);
    return kb * 1024.0;
}

// Composing and multi-band blending take ~25-26 bytes per canvas pixel (measured with OpenCV 4.10, 3x3 sets of
// 4096x3072 frames: 58 MP canvas 1.44 GB, 29 MP 0.76 GB, 19 MP 0.50 GB); a little margin on top.
constexpr double kBytesPerCanvasPixel = 28.0;
constexpr double kMaxCanvasPixels = 150e6;

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
    jint quality, jboolean crop, jint projection, jfloat spanDegrees, jboolean vertical) {
    std::vector<jint> fds(static_cast<size_t>(env->GetArrayLength(fdsArray)));
    std::vector<jint> rotations(static_cast<size_t>(env->GetArrayLength(rotationsArray)), 0);
    env->GetIntArrayRegion(fdsArray, 0, jsize(fds.size()), fds.data());
    if (!rotations.empty()) env->GetIntArrayRegion(rotationsArray, 0, jsize(rotations.size()), rotations.data());
    std::string error;
    std::string summary;
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
            // Projection: 0 Auto (Rectilinear up to ~100° across, else Cylindrical), 1 Rectilinear, 2 Cylindrical,
            // 3 Panini, 4 Spherical (OpenCV's default).
            // Up / down sweeps (Nov 19): OpenCV's cylinder stands upright and Panini squeezes across, so along an
            // up / down sweep both stretch without limit (a 2-frame vertical set came out 2962x19809, another
            // overflowed remap's 32767 px). Those sets use Rectilinear up to ~100°, else Spherical.
            const bool upDown = vertical == JNI_TRUE;
            int p = projection;
            if (p == 0) p = (spanDegrees > 0.0f && spanDegrees <= 100.0f) ? 1 : (upDown ? 4 : 2);
            else if (upDown && (p == 2 || p == 3)) p = (spanDegrees > 0.0f && spanDegrees <= 100.0f) ? 1 : 4;
            __android_log_print(ANDROID_LOG_INFO, kTag, "PANO_PROJECTION chosen=%d asked=%d span=%.1f vertical=%d", p,
                                int(projection), double(spanDegrees), int(upDown));
            const double frameMp = double(frames[0].total()) / 1e6;
            double framesArea = 0.0;
            for (const auto& f : frames) framesArea += double(f.total());
            // Tries: ORB at 0.6 MP (OpenCV's default), SIFT at 1 MP with a looser match, looser still. Two-frame and
            // up / down sets start with SIFT (Nov 19: ORB scraped through on few matches in dim light and placed
            // the frames badly; SIFT's result was the right one). Every failure goes on to the next try, a thrown
            // OpenCV error included (one ended the whole merge), and so does a result far larger than its frames
            // (a bad estimate stretched the canvas).
            const bool siftFirst = frames.size() == 2 || upDown;
            const int order[3] = {siftFirst ? 1 : 0, siftFirst ? 2 : 1, siftFirst ? 0 : 2};
            cv::Mat pano;
            cv::Stitcher::Status status = cv::Stitcher::ERR_NEED_MORE_IMGS;
            int usedAttempt = 0;
            size_t usedFrames = 0;
            for (int attempt : order) {
                pano.release();
                try {
                    auto stitcher = cv::Stitcher::create(cv::Stitcher::PANORAMA);
                    if (p == 1) stitcher->setWarper(cv::makePtr<cv::PlaneWarper>());
                    else if (p == 2) stitcher->setWarper(cv::makePtr<cv::CylindricalWarper>());
                    else if (p == 3) stitcher->setWarper(cv::makePtr<cv::PaniniWarper>(1.5f, 1.0f));
                    else stitcher->setWarper(cv::makePtr<cv::SphericalWarper>());
                    // OpenCV levels a set as if it turned sideways; an up / down sweep turns about one sideways axis,
                    // so "up" is undefined there and the set ends up tilted (frames splayed, black canvas). Its
                    // frames keep frame 1's level instead.
                    stitcher->setWaveCorrection(!upDown);
                    if (composeMegapixels > 0.0f && composeMegapixels < frameMp) stitcher->setCompositingResol(composeMegapixels);
                    if (attempt > 0) {
                        stitcher->setFeaturesFinder(cv::SIFT::create(4000));
                        stitcher->setRegistrationResol(1.0);
                        stitcher->setFeaturesMatcher(cv::makePtr<cv::detail::BestOf2NearestMatcher>(false, attempt == 1 ? 0.25f : 0.2f));
                        stitcher->setPanoConfidenceThresh(attempt == 1 ? 0.6 : 0.3);
                    }
                    // Nov 19: a 3x3 set was killed for low memory (rss 9.1 GB) while composing; the size check only
                    // ran on the finished panorama. The canvas is now worked out from the estimate first (exactly as
                    // composePanorama will size it) and checked before anything is allocated: the same "bad
                    // estimate" rule as below, then a memory budget (a larger canvas is composed smaller).
                    status = stitcher->estimateTransform(frames);
                    if (status == cv::Stitcher::OK) {
                        const cv::Size planned = plannedPanoCanvas(*stitcher, frames);
                        const double canvas = double(planned.area());
                        const double avail = availableBytes();
                        // Half of the free memory (the camera keeps running beside the merge), at most 150 MP.
                        const double budget = std::min(kMaxCanvasPixels,
                                                       avail > 0.0 ? 0.5 * avail / kBytesPerCanvasPixel : kMaxCanvasPixels);
                        __android_log_print(ANDROID_LOG_INFO, kTag,
                                            "PANO_CANVAS attempt=%d planned=%dx%d (%.1f MP) frames=%.1f MP availMB=%.0f budget=%.1f MP",
                                            attempt, planned.width, planned.height, canvas / 1e6, framesArea / 1e6,
                                            avail / 1048576.0, budget / 1e6);
                        if (canvas <= 0.0 || canvas > 3.0 * framesArea) {
                            __android_log_print(ANDROID_LOG_WARN, kTag, "PANO_STITCH_ODD attempt=%d planned=%dx%d frames=%.1f MP",
                                                attempt, planned.width, planned.height, framesArea / 1e6);
                            status = cv::Stitcher::ERR_CAMERA_PARAMS_ADJUST_FAIL;
                        } else {
                            if (canvas > budget) {
                                // The canvas area follows the compose megapixels per frame.
                                const double resol = stitcher->compositingResol();
                                const double frameMpNow = resol > 0.0 ? std::min(resol, frameMp) : frameMp;
                                const double smaller = frameMpNow * budget / canvas;
                                __android_log_print(ANDROID_LOG_WARN, kTag, "PANO_CANVAS_REDUCED compose=%.1f->%.1f MP",
                                                    frameMpNow, smaller);
                                if (smaller < 1.0) status = cv::Stitcher::ERR_CAMERA_PARAMS_ADJUST_FAIL;
                                else stitcher->setCompositingResol(smaller);
                            }
                            if (status == cv::Stitcher::OK) status = stitcher->composePanorama(pano);
                        }
                    }
                    if (status == cv::Stitcher::OK && !pano.empty()) usedFrames = stitcher->component().size();
                } catch (const cv::Exception& e) {
                    __android_log_print(ANDROID_LOG_WARN, kTag, "PANO_STITCH_TRY attempt=%d threw %s", attempt, e.what());
                    status = cv::Stitcher::ERR_CAMERA_PARAMS_ADJUST_FAIL;
                    pano.release();
                } catch (const std::bad_alloc&) {
                    __android_log_print(ANDROID_LOG_WARN, kTag, "PANO_STITCH_TRY attempt=%d out of memory", attempt);
                    status = cv::Stitcher::ERR_CAMERA_PARAMS_ADJUST_FAIL;
                    pano.release();
                }
                __android_log_print(ANDROID_LOG_INFO, kTag, "PANO_STITCH_TRY attempt=%d frames=%zu status=%d size=%dx%d", attempt,
                                    frames.size(), int(status), pano.cols, pano.rows);
                if (status == cv::Stitcher::OK && !pano.empty()) {
                    // A panorama can't hold much more than its frames: three times their area means a bad estimate.
                    if (double(pano.total()) > 3.0 * framesArea) {
                        __android_log_print(ANDROID_LOG_WARN, kTag, "PANO_STITCH_ODD attempt=%d size=%dx%d frames=%.1f MP",
                                            attempt, pano.cols, pano.rows, framesArea / 1e6);
                        status = cv::Stitcher::ERR_CAMERA_PARAMS_ADJUST_FAIL;
                        pano.release();
                        continue;
                    }
                    usedAttempt = attempt;
                    break;
                }
            }
            frames.clear();
            if (status != cv::Stitcher::OK || pano.empty()) {
                error = statusText(status);
            } else {
                const int fullW = pano.cols, fullH = pano.rows;
                if (crop == JNI_TRUE) pano = pano(innerRect(pano)).clone();
                std::vector<uchar> jpeg;
                if (!cv::imencode(".jpg", pano, jpeg, {cv::IMWRITE_JPEG_QUALITY, std::clamp(int(quality), 50, 100)}) ||
                    !writeAll(outFd, jpeg)) {
                    error = "couldn't write the panorama";
                } else {
                    __android_log_print(ANDROID_LOG_INFO, kTag, "PANO_STITCHED frames=%zu size=%dx%d bytes=%zu",
                                        fds.size(), pano.cols, pano.rows, jpeg.size());
                    // How it was made, for the panorama's EXIF (Kotlin adds how the frames were taken).
                    static const char* kProjection[] = {"Auto", "Rectilinear", "Cylindrical", "Panini", "Spherical"};
                    static const char* kMatch[] = {"ORB features", "SIFT features", "SIFT features, loose match"};
                    const bool replaced = projection != 0 && p != projection;
                    summary = std::string(kProjection[std::clamp(p, 1, 4)]) + (projection == 0 ? " (Auto)" : "") +
                              (replaced ? std::string(" (") + kProjection[std::clamp(int(projection), 1, 4)] +
                                              " doesn't suit an up/down sweep)" : "") +
                              " projection, " + (upDown ? "up/down sweep, " : "") +
                              std::to_string(usedFrames) + " of " + std::to_string(fds.size()) + " frames stitched, " +
                              kMatch[usedAttempt] +
                              (pano.cols != fullW || pano.rows != fullH ? ", cropped" : "") + ", OpenCV " + CV_VERSION;
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
    // "OK|<how it was made>" on success, otherwise why it failed.
    return env->NewStringUTF(error.empty() ? ("OK|" + summary).c_str() : error.c_str());
}
