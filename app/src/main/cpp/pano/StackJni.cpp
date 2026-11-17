// X14U Stack (Nov 17): a burst's or timed run's JPEGs into one photo.
//   Average  every frame's mean: a long exposure (smooth water, blurred clouds), and less noise.
//   Lighten  the brightest value per pixel: star trails, light trails.
//   Clean    the mean of each pixel without the frames where it strays (> 2.5 sigma in luma from its own mean):
//            people and cars that pass go, the empty scene stays. Two passes, memory independent of frame count.
// Align (handheld runs): each frame is matched to the middle one (ORB features, RANSAC homography on a ~1.5 MP
// copy) and warped onto it; a frame that can't be matched is left out. Built into librawrcam_pano (OpenCV).
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

#include <opencv2/calib3d.hpp>
#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

#define STACK_LOG(...) __android_log_print(ANDROID_LOG_INFO, "RawrStack", __VA_ARGS__)

namespace {

struct Aligner {
    cv::Ptr<cv::ORB> orb = cv::ORB::create(3000);
    cv::BFMatcher matcher{cv::NORM_HAMMING};
    double scale = 1.0;
    std::vector<cv::KeyPoint> refPoints;
    cv::Mat refDescriptors;
    cv::Size size;

    static cv::Mat small(const cv::Mat& bgr, double scale) {
        cv::Mat gray;
        cv::cvtColor(bgr, gray, cv::COLOR_BGR2GRAY);
        if (scale < 1.0) cv::resize(gray, gray, cv::Size(), scale, scale, cv::INTER_AREA);
        return gray;
    }

    void reference(const cv::Mat& bgr) {
        size = bgr.size();
        scale = std::min(1.0, std::sqrt(1.5e6 / double(bgr.total())));
        orb->detectAndCompute(small(bgr, scale), cv::noArray(), refPoints, refDescriptors);
    }

    // [bgr] warped onto the reference; false when it can't be matched well.
    bool align(cv::Mat& bgr) const {
        if (refDescriptors.empty() || bgr.size() != size) return false;
        std::vector<cv::KeyPoint> points;
        cv::Mat descriptors;
        orb->detectAndCompute(small(bgr, scale), cv::noArray(), points, descriptors);
        if (descriptors.empty()) return false;
        std::vector<std::vector<cv::DMatch>> knn;
        matcher.knnMatch(descriptors, refDescriptors, knn, 2);
        std::vector<cv::Point2f> from, to;
        for (const auto& m : knn) {
            if (m.size() == 2 && m[0].distance < 0.75f * m[1].distance) {
                from.push_back(points[m[0].queryIdx].pt);
                to.push_back(refPoints[m[0].trainIdx].pt);
            }
        }
        if (from.size() < 30) return false;
        std::vector<unsigned char> inliers;
        cv::Mat h = cv::findHomography(from, to, cv::RANSAC, 3.0, inliers);
        if (h.empty() || cv::countNonZero(inliers) < 25) return false;
        // To full size: S^-1 H S.
        cv::Mat s = (cv::Mat_<double>(3, 3) << scale, 0, 0, 0, scale, 0, 0, 0, 1);
        cv::Mat full = s.inv() * h * s;
        cv::Mat warped;
        cv::warpPerspective(bgr, warped, full, size, cv::INTER_LINEAR, cv::BORDER_REPLICATE);
        bgr = warped;
        return true;
    }
};

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

}  // namespace

// paths: the frames (JPEG); mode 0 Average, 1 Lighten, 2 Clean; progress.onProgress(done, total) false = stop.
// Returns "OK|<frames used> of <frames>[, <aligned> aligned]" or why not.
extern "C" JNIEXPORT jstring JNICALL Java_com_rawr_camera_burst_FrameStackNative_stack(
    JNIEnv* env, jobject, jobjectArray jpaths, jint mode, jboolean align, jstring jout, jint quality, jobject progress) {
    std::vector<std::string> paths;
    const jsize n = env->GetArrayLength(jpaths);
    for (jsize i = 0; i < n; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(jpaths, i));
        paths.push_back(jstr(env, s));
        env->DeleteLocalRef(s);
    }
    const std::string out = jstr(env, jout);
    if (paths.size() < 2) return env->NewStringUTF("Needs at least 2 frames");
    jmethodID onProgress = nullptr;
    if (progress) {
        jclass cls = env->GetObjectClass(progress);
        onProgress = env->GetMethodID(cls, "onProgress", "(II)Z");
        env->DeleteLocalRef(cls);
    }
    const int passes = mode == 2 ? 2 : 1;
    const int total = int(paths.size()) * passes;
    int done = 0;
    auto tick = [&]() -> bool {
        ++done;
        return !onProgress || env->CallBooleanMethod(progress, onProgress, done, total) == JNI_TRUE;
    };
    try {
        // The middle frame is the reference (alignment and the size every frame must have).
        cv::Mat reference = cv::imread(paths[paths.size() / 2], cv::IMREAD_COLOR);
        if (reference.empty()) return env->NewStringUTF("Can't read the frames");
        const cv::Size size = reference.size();
        Aligner aligner;
        if (align) aligner.reference(reference);
        int aligned = 0, skipped = 0;
        // Reads frame i (aligned when asked); empty when it can't be used.
        auto load = [&](size_t i, bool countAlign) -> cv::Mat {
            cv::Mat f = cv::imread(paths[i], cv::IMREAD_COLOR);
            if (f.empty() || f.size() != size) return {};
            if (align && i != paths.size() / 2) {
                if (!aligner.align(f)) return {};
                if (countAlign) ++aligned;
            }
            return f;
        };
        cv::Mat result;
        int used = 0;
        if (mode == 1) {
            // Lighten.
            result = cv::Mat::zeros(size, CV_8UC3);
            for (size_t i = 0; i < paths.size(); ++i) {
                cv::Mat f = load(i, true);
                if (f.empty()) ++skipped; else { cv::max(result, f, result); ++used; }
                if (!tick()) return env->NewStringUTF("Stopped");
            }
        } else {
            // Average (and Clean's first pass: the mean, and luma mean / variance per pixel).
            cv::Mat sum = cv::Mat::zeros(size, CV_32FC3);
            cv::Mat lumaSum, lumaSq;
            if (mode == 2) {
                lumaSum = cv::Mat::zeros(size, CV_32F);
                lumaSq = cv::Mat::zeros(size, CV_32F);
            }
            for (size_t i = 0; i < paths.size(); ++i) {
                cv::Mat f = load(i, true);
                if (f.empty()) {
                    ++skipped;
                } else {
                    cv::accumulate(f, sum);
                    if (mode == 2) {
                        cv::Mat gray;
                        cv::cvtColor(f, gray, cv::COLOR_BGR2GRAY);
                        cv::accumulate(gray, lumaSum);
                        cv::accumulateSquare(gray, lumaSq);
                    }
                    ++used;
                }
                if (!tick()) return env->NewStringUTF("Stopped");
            }
            if (used == 0) return env->NewStringUTF("No frame could be used");
            if (mode == 0) {
                sum.convertTo(result, CV_8UC3, 1.0 / used);
            } else {
                // Clean: per pixel, keep only the frames whose luma is within 2.5 sigma (at least 6 levels) of its mean.
                cv::Mat mean = lumaSum / used;
                cv::Mat var = lumaSq / used - mean.mul(mean);
                cv::Mat positive = cv::max(var, 0.0);
                cv::Mat sigma;
                cv::sqrt(positive, sigma);
                cv::Mat scaled = sigma * 2.5;
                cv::Mat limit = cv::max(scaled, 6.0);
                lumaSum.release();
                lumaSq.release();
                cv::Mat fallback;
                sum.convertTo(fallback, CV_8UC3, 1.0 / used);
                sum.setTo(cv::Scalar::all(0));
                cv::Mat count = cv::Mat::zeros(size, CV_32F);
                for (size_t i = 0; i < paths.size(); ++i) {
                    cv::Mat f = load(i, false);
                    if (!f.empty()) {
                        cv::Mat gray, diff, mask;
                        cv::cvtColor(f, gray, cv::COLOR_BGR2GRAY);
                        gray.convertTo(gray, CV_32F);
                        cv::absdiff(gray, mean, diff);
                        cv::compare(diff, limit, mask, cv::CMP_LE);
                        cv::accumulate(f, sum, mask);
                        cv::accumulate(cv::Mat::ones(size, CV_32F), count, mask);
                    }
                    if (!tick()) return env->NewStringUTF("Stopped");
                }
                cv::Mat counts3;
                cv::Mat channels[] = {count, count, count};
                cv::merge(channels, 3, counts3);
                cv::Mat safe = cv::max(counts3, 1.0);
                cv::Mat cleaned;
                cv::divide(sum, safe, cleaned);
                cleaned.convertTo(result, CV_8UC3);
                // A pixel no frame kept: the plain mean.
                cv::Mat none;
                cv::compare(count, 0.5, none, cv::CMP_LT);
                fallback.copyTo(result, none);
            }
        }
        if (used < 2) return env->NewStringUTF("Fewer than 2 frames could be used (they didn't line up)");
        std::vector<int> params = {cv::IMWRITE_JPEG_QUALITY, std::clamp(int(quality), 50, 100)};
        if (!cv::imwrite(out, result, params)) return env->NewStringUTF("Can't write the photo");
        std::string summary = "OK|" + std::to_string(used) + " of " + std::to_string(paths.size()) + " frames";
        if (align) summary += ", " + std::to_string(aligned) + " aligned";
        STACK_LOG("STACK mode=%d %s skipped=%d", int(mode), summary.c_str(), skipped);
        return env->NewStringUTF(summary.c_str());
    } catch (const cv::Exception& e) {
        STACK_LOG("STACK_FAIL %s", e.what());
        return env->NewStringUTF((std::string("Stack failed: ") + e.what()).c_str());
    } catch (const std::exception& e) {
        return env->NewStringUTF((std::string("Stack failed: ") + e.what()).c_str());
    }
}
