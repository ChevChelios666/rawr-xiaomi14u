#pragma once

#include <android/log.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <string>

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

}  // namespace rawrcam::video
