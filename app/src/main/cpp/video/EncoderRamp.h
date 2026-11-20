#pragma once

#include <android/log.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <mutex>
#include <string>
#include <vector>

namespace rawrcam::video {

// X14U (Nov 19): the first 4 s of a take in 250 ms bins, logged once. Some 4K takes start with the encoder ~20 frames
// behind (102-114 frames encoded in the first 5 s instead of 122-128), fill its 16 input slots and then drop frames for
// the rest of the take; these lines show where the start loses time. Input side (VideoOutput::acquire, render thread):
// frames handed to the encoder, frames dropped because it had no free slot, longest wait. Output side
// (NativeAvRecorder::drainVideo): frames the encoder finished. Both lines carry their origin (CLOCK_MONOTONIC ms) so they
// line up.
class StartRamp final {
   public:
    static constexpr int kBins = 16;
    static constexpr int64_t kBinNs = 250'000'000;

    static int64_t monotonicNs() noexcept {
        timespec now{};
        clock_gettime(CLOCK_MONOTONIC, &now);
        return int64_t(now.tv_sec) * 1'000'000'000 + now.tv_nsec;
    }
    void begin(int64_t originNs) noexcept {
        *this = StartRamp{};
        originNs_ = originNs;
    }
    // Adds to bin of `now`; returns false once past the window.
    bool add(int64_t now, bool ok, double waitMs) noexcept {
        if (originNs_ == 0 || now < originNs_) return true;
        const int64_t bin = (now - originNs_) / kBinNs;
        if (bin >= kBins) return false;
        auto& b = bins_[size_t(bin)];
        (ok ? b.ok : b.miss) += 1;
        b.maxWaitMs = std::max(b.maxWaitMs, float(waitMs));
        if (ok && firstOkMs_ < 0) firstOkMs_ = double(now - originNs_) / 1e6;
        return true;
    }
    // Logs once, after the window (or at stop with what there is).
    void log(const char* what, bool withMisses) noexcept {
        if (logged_ || originNs_ == 0) return;
        logged_ = true;
        std::string ok, miss, wait;
        char cell[24];
        for (const auto& b : bins_) {
            std::snprintf(cell, sizeof cell, "%s%u", ok.empty() ? "" : ",", unsigned(b.ok));
            ok += cell;
            std::snprintf(cell, sizeof cell, "%s%u", miss.empty() ? "" : ",", unsigned(b.miss));
            miss += cell;
            std::snprintf(cell, sizeof cell, "%s%.1f", wait.empty() ? "" : ",", double(b.maxWaitMs));
            wait += cell;
        }
        if (withMisses)
            __android_log_print(ANDROID_LOG_INFO, "RawrNativeVideo",
                                "%s origin=%lld firstMs=%.0f ok=[%s] miss=[%s] waitMaxMs=[%s]", what,
                                static_cast<long long>(originNs_ / 1'000'000), firstOkMs_, ok.c_str(), miss.c_str(),
                                wait.c_str());
        else
            __android_log_print(ANDROID_LOG_INFO, "RawrNativeVideo", "%s origin=%lld firstMs=%.0f out=[%s]", what,
                                static_cast<long long>(originNs_ / 1'000'000), firstOkMs_, ok.c_str());
    }

   private:
    struct Bin {
        uint16_t ok = 0, miss = 0;
        float maxWaitMs = 0.0f;
    };
    int64_t originNs_ = 0;
    double firstOkMs_ = -1.0;
    bool logged_ = false;
    std::array<Bin, kBins> bins_{};
};

// X14U (Nov 20): the first 2 s of a take, event by event, to find the camera frame lost right after a YUV take starts
// (INGRESS_QUEUE_PRESSURE rawDropped=1 160-250 ms in; utalpt sees it on the 17U too). Logged once, one line per kind,
// each event as "ms-since-start:value(s)":
//   VIDEO_START_TRACE ingress  camera frames: queue wait / session-lock wait / handling, ms
//   VIDEO_START_TRACE drops    frames the camera queue dropped (count)
//   VIDEO_START_TRACE import   encoder buffers imported for the first time (ms)
//   VIDEO_START_TRACE dequeue  waits for a free encoder buffer from the writer (ms, over 2 ms only)
//   VIDEO_START_TRACE fence    GPU completion waits per encoded frame (ms)
//   VIDEO_START_TRACE miss     frames with no encoder buffer in hand
class StartTrace final {
   public:
    static StartTrace& get() noexcept {
        static StartTrace trace;
        return trace;
    }
    void begin(int64_t originNs) {
        std::lock_guard<std::mutex> lock(mutex_);
        events_.clear();
        events_.reserve(512);
        originNs_ = originNs;
        logged_ = false;
    }
    void add(char kind, int64_t nowNs, float a, float b = 0.0f, float c = 0.0f) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (originNs_ == 0 || logged_ || nowNs < originNs_) return;
        if (nowNs - originNs_ > kWindowNs) {
            logLocked();
            return;
        }
        if (events_.size() < 1024) events_.push_back({kind, float(double(nowNs - originNs_) / 1e6), a, b, c});
    }
    void flush() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (originNs_ != 0 && !logged_) logLocked();
    }

   private:
    static constexpr int64_t kWindowNs = 2'000'000'000;
    struct Event {
        char kind;
        float t, a, b, c;
    };
    void logLocked() {
        logged_ = true;
        static constexpr struct {
            char kind;
            const char* name;
            int values;
        } kinds[] = {{'i', "ingress", 3}, {'d', "drops", 1}, {'b', "import", 1},
                     {'q', "dequeue", 1}, {'f', "fence", 1},  {'m', "miss", 0}};
        for (const auto& k : kinds) {
            std::string line;
            char cell[64];
            for (const auto& e : events_) {
                if (e.kind != k.kind) continue;
                if (k.values == 3)
                    std::snprintf(cell, sizeof cell, " %.0f:%.1f/%.1f/%.1f", double(e.t), double(e.a), double(e.b),
                                  double(e.c));
                else if (k.values == 1)
                    std::snprintf(cell, sizeof cell, " %.0f:%.1f", double(e.t), double(e.a));
                else
                    std::snprintf(cell, sizeof cell, " %.0f", double(e.t));
                if (line.size() + std::strlen(cell) > 3500) {
                    __android_log_print(ANDROID_LOG_INFO, "RawrNativeVideo", "VIDEO_START_TRACE %s%s", k.name,
                                        line.c_str());
                    line.clear();
                }
                line += cell;
            }
            __android_log_print(ANDROID_LOG_INFO, "RawrNativeVideo", "VIDEO_START_TRACE %s%s", k.name,
                                line.empty() ? " -" : line.c_str());
        }
    }
    std::mutex mutex_;
    int64_t originNs_ = 0;
    bool logged_ = false;
    std::vector<Event> events_;
};

}  // namespace rawrcam::video
