#pragma once
#include <android/hardware_buffer.h>
#include <media/NdkImage.h>
#include <sys/resource.h>
#include <unistd.h>

#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <string>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <thread>

#include "capture/single/RawBufferPool.h"
#include "capture/single/SingleFrameCaptureTypes.h"
#include "imaging/Raw16CpuSnapshot.h"
#include "imaging/RawFrameLease.h"

namespace rawrcam::capture {

// X14U Burst pre-capture: while armed, keeps the latest viewfinder RAW frames (one every intervalMs, at most
// maxFrames) in memory, copied the same way a still is (claimed at submit, copied once the preview is done with the
// camera image). The shutter freezes the ring: live burst frames take the camera from then on, and after release
// each frozen frame is handed, oldest first, to a still request of its own (adopted instead of a camera frame).
class PrecaptureRing final {
   public:
    using Diagnostic = std::function<void(const std::string&)>;
    explicit PrecaptureRing(Diagnostic diagnostic) : diagnostic_(std::move(diagnostic)) {
        worker_ = std::thread([this] { run(); });
    }
    ~PrecaptureRing() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopped_ = true;
        }
        changed_.notify_all();
        if (worker_.joinable()) worker_.join();
        clearLocked();
    }
    PrecaptureRing(const PrecaptureRing&) = delete;
    PrecaptureRing& operator=(const PrecaptureRing&) = delete;

    // maxFrames 0 disarms and drops everything (frozen frames too).
    void arm(uint32_t maxFrames, uint32_t intervalMs) {
        std::lock_guard<std::mutex> lock(mutex_);
        maxFrames_ = maxFrames;
        intervalNs_ = uint64_t(intervalMs) * 1'000'000ull;
        if (maxFrames == 0) {
            clearLocked();
            return;
        }
        while (ring_.size() > maxFrames_) dropOldestLocked();
    }
    bool armed() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return maxFrames_ > 0 && !frozenActive_;
    }
    uint32_t held() const {
        std::lock_guard<std::mutex> lock(mutex_);
        return uint32_t(ring_.size());
    }

    // Submit side: takes this frame when one is due (returns true: the image will be offered to begin()).
    bool offer(const metadata::FrameMetadataSnapshot& metadata, const color::FrameColorTransform& color,
               const tonemap::TonemapParams& tone, float gain, bool film, const spektrafilm_native::FilmLook& look) {
        if (!metadata.cameraContext || metadata.timestampNs == 0) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (maxFrames_ == 0 || frozenActive_) return false;
        // A schedule (one frame per interval, 10 ms tolerance), so 20 fps from a 30 fps stream averages 20, not 15.
        const uint64_t ts = metadata.timestampNs;
        if (nextDueNs_ == 0 || ts > nextDueNs_ + 2 * intervalNs_ || ts + 2 * intervalNs_ < nextDueNs_) nextDueNs_ = ts;
        if (ts + 10'000'000ull < nextDueNs_) return false;
        const auto& geometry = metadata.cameraContext->geometry;
        if (geometry.rawBufferWidth == 0 || geometry.rawBufferHeight == 0) return false;
        // A camera switch: the old camera's frames go.
        if (generation_ != metadata.cameraContext->cameraContextGeneration) {
            while (!ring_.empty()) dropOldestLocked();
            generation_ = metadata.cameraContext->cameraContextGeneration;
        }
        auto frame = std::make_shared<imaging::RawSnapshot>();
        frame->timestampNs = metadata.timestampNs;
        frame->width = geometry.rawBufferWidth;
        frame->height = geometry.rawBufferHeight;
        frame->packedRowStrideBytes = frame->width * 2;
        frame->metadata = metadata;
        frame->colorState = color;
        SingleFrameSnapshot snapshot;
        snapshot.frame = std::move(frame);
        snapshot.tonemapParams = tone;
        snapshot.aePostGain = gain;
        snapshot.filmEnabled = film;
        snapshot.filmLook = look;
        pending_[metadata.timestampNs] = std::move(snapshot);
        while (pending_.size() > 8) pending_.erase(pending_.begin());
        lastOfferedNs_ = ts;
        nextDueNs_ += intervalNs_;
        return true;
    }

    // Retire side: copies the camera image of a frame offer() took (the ring owns the image when this returns true).
    bool begin(AImage* image, AHardwareBuffer* ahb, uint64_t timestampNs, int acquireFenceFd) {
        if (!image || !ahb) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = pending_.find(timestampNs);
        if (it == pending_.end()) return false;
        const int fence = acquireFenceFd >= 0 ? dup(acquireFenceFd) : -1;
        Job job;
        job.lease = imaging::RawFrameLease({image, ahb, fence, timestampNs, 0});
        job.snapshot = std::move(it->second);
        job.forFrozen = frozenActive_;
        jobs_.push_back(std::move(job));
        pending_.erase(it);
        ++copying_;
        changed_.notify_all();
        return true;
    }

    // Shutter: live frames take the camera from now on. Returns the frames that will be handed over (held plus
    // those still copying).
    uint32_t freeze() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (maxFrames_ == 0) return 0;
        frozenActive_ = true;
        pending_.clear();
        frozen_.clear();
        for (auto& f : ring_) frozen_.push_back(std::move(f));
        ring_.clear();
        emit("PRECAPTURE_FREEZE frames=" + std::to_string(frozen_.size()) + " copying=" + std::to_string(copying_));
        return uint32_t(frozen_.size()) + copying_;
    }

    // Release: waits for copies in flight, then each still request takes one frozen frame. Returns how many.
    uint32_t beginDrain() {
        std::unique_lock<std::mutex> lock(mutex_);
        changed_.wait_for(lock, std::chrono::milliseconds(400), [this] { return copying_ == 0; });
        draining_ = !frozen_.empty();
        emit("PRECAPTURE_DRAIN frames=" + std::to_string(frozen_.size()));
        return uint32_t(frozen_.size());
    }
    // A still request while draining: the oldest frozen frame instead of a camera frame.
    std::optional<SingleFrameSnapshot> takeFrozen() {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!draining_ || frozen_.empty()) return std::nullopt;
        SingleFrameSnapshot out = std::move(frozen_.front());
        frozen_.pop_front();
        if (frozen_.empty()) {
            draining_ = false;
            frozenActive_ = false;
        }
        return out;
    }
    // The run ended without a drain (or the drain was abandoned): the frozen frames go back to the pool.
    void endFrozen() {
        std::lock_guard<std::mutex> lock(mutex_);
        for (auto& f : frozen_) giveBack(f);
        frozen_.clear();
        draining_ = false;
        frozenActive_ = false;
    }

   private:
    struct Job {
        imaging::RawFrameLease lease{imaging::AcquiredRawFrame{}};
        SingleFrameSnapshot snapshot;
        bool forFrozen = false;
    };

    void run() {
        setpriority(PRIO_PROCESS, 0, 2);
        for (;;) {
            Job job;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                changed_.wait(lock, [this] { return stopped_ || !jobs_.empty(); });
                if (stopped_ && jobs_.empty()) return;
                job = std::move(jobs_.front());
                jobs_.pop_front();
            }
            auto& frame = *job.snapshot.frame;
            bool ok = false;
            try {
                frame.raw16 = RawBufferPool::take(std::size_t(frame.width) * frame.height * 2);
                const auto& camera = job.lease.get();
                job.snapshot.copyResult = imaging::copyCompletedRaw16AhbToPacked(
                    camera.ahb, frame.width, frame.height, frame.raw16, camera.acquireFenceFd, camera.image);
                ok = job.snapshot.copyResult.success;
                if (ok) {
                    frame.sourceRowStrideBytes = job.snapshot.copyResult.sourceRowStrideBytes;
                    frame.sourcePixelStrideBytes = job.snapshot.copyResult.sourcePixelStrideBytes;
                }
            } catch (...) {
                ok = false;
            }
            job.lease = imaging::RawFrameLease({});  // the camera image goes back
            std::lock_guard<std::mutex> lock(mutex_);
            --copying_;
            if (!ok) {
                giveBack(job.snapshot);
            } else if (frozenActive_) {
                // Taken before the shutter: it belongs to the frozen set.
                frozen_.push_back(std::move(job.snapshot));
            } else if (maxFrames_ == 0) {
                giveBack(job.snapshot);
            } else {
                ring_.push_back(std::move(job.snapshot));
                while (ring_.size() > maxFrames_) dropOldestLocked();
            }
            changed_.notify_all();
        }
    }
    static void giveBack(SingleFrameSnapshot& s) {
        if (s.frame) RawBufferPool::give(std::move(s.frame->raw16));
    }
    void dropOldestLocked() {
        giveBack(ring_.front());
        ring_.pop_front();
    }
    void clearLocked() {
        for (auto& f : ring_) giveBack(f);
        for (auto& f : frozen_) giveBack(f);
        ring_.clear();
        frozen_.clear();
        pending_.clear();
        frozenActive_ = false;
        draining_ = false;
        lastOfferedNs_ = 0;
        nextDueNs_ = 0;
    }
    void emit(const std::string& line) const {
        if (diagnostic_) diagnostic_(line);
    }

    Diagnostic diagnostic_;
    mutable std::mutex mutex_;
    std::condition_variable changed_;
    std::thread worker_;
    bool stopped_ = false;
    uint32_t maxFrames_ = 0;
    uint64_t intervalNs_ = 0;
    uint64_t lastOfferedNs_ = 0;
    uint64_t nextDueNs_ = 0;
    uint64_t generation_ = 0;
    uint32_t copying_ = 0;
    bool frozenActive_ = false;
    bool draining_ = false;
    std::map<uint64_t, SingleFrameSnapshot> pending_;
    std::deque<Job> jobs_;
    std::deque<SingleFrameSnapshot> ring_;
    std::deque<SingleFrameSnapshot> frozen_;
};

}  // namespace rawrcam::capture
