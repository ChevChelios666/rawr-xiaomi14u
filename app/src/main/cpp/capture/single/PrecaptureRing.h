#pragma once
#include <android/hardware_buffer.h>
#include <media/NdkImage.h>
#include <poll.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
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
        if (maxFrames != maxFrames_)
            emit("PRECAPTURE_ARM frames=" + std::to_string(maxFrames) + " intervalMs=" + std::to_string(intervalMs));
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
            if (!ring_.empty()) ++stats_.generationDrops;
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
        // Taken at submit but never handed over at retire (begin()): counted, so the stats show it.
        while (pending_.size() > 8) {
            pending_.erase(pending_.begin());
            ++stats_.neverBegun;
        }
        lastOfferedNs_ = ts;
        nextDueNs_ += intervalNs_;
        ++stats_.offered;
        return true;
    }

    // Retire side: copies the camera image of a frame offer() took (the ring owns the image when this returns true).
    bool begin(AImage* image, AHardwareBuffer* ahb, uint64_t timestampNs, int acquireFenceFd) {
        if (!image || !ahb) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = pending_.find(timestampNs);
        if (it == pending_.end()) {
            ++stats_.notPending;
            return false;
        }
        // Nov 17 (diagnostics 20:36): the copies fell behind, every waiting copy held a camera image, and the RAW
        // reader (6 images, 3 of them the viewfinder's) ran dry: the camera dropped frames and the ring stopped. At
        // most kMaxCopying copies wait; a frame beyond that is skipped and its image goes straight back.
        if (copying_ >= kMaxCopying) {
            ++stats_.skippedBusy;
            pending_.erase(it);
            return false;
        }
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
                // Woken every 2 s while armed, so the stats line also shows a ring that gets no frames at all.
                while (!stopped_ && jobs_.empty()) {
                    if (changed_.wait_for(lock, std::chrono::seconds(2)) == std::cv_status::timeout) {
                        const std::string line = statsLineLocked();
                        if (!line.empty()) {
                            lock.unlock();
                            emit(line);
                            lock.lock();
                        }
                    }
                }
                if (stopped_ && jobs_.empty()) return;
                job = std::move(jobs_.front());
                jobs_.pop_front();
            }
            auto& frame = *job.snapshot.frame;
            bool ok = false;
            // A camera acquire fence that never signals must not hold this worker: wait at most 400 ms, then skip
            // the frame. (Frames handed over at retire come without a fence; the 20:36 diagnostics showed no timeout.)
            const int acquireFence = job.lease.get().acquireFenceFd;
            if (acquireFence >= 0) {
                pollfd pfd{acquireFence, POLLIN, 0};
                const auto waitStart = std::chrono::steady_clock::now();
                const int ready = ::poll(&pfd, 1, 400);
                if (ready <= 0) {
                    ++fenceTimeouts_;
                    emit("PRECAPTURE_FENCE_TIMEOUT ts=" + std::to_string(frame.timestampNs) +
                         " count=" + std::to_string(fenceTimeouts_));
                    job.lease = imaging::RawFrameLease({});
                    std::lock_guard<std::mutex> lock(mutex_);
                    --copying_;
                    giveBack(job.snapshot);
                    changed_.notify_all();
                    continue;
                }
                const auto waitedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - waitStart).count();
                if (waitedMs > 100) emit("PRECAPTURE_FENCE_SLOW ms=" + std::to_string(waitedMs));
            }
            const auto copyStart = std::chrono::steady_clock::now();
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
            const double copyMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - copyStart).count();
            std::string statsLine, failureLine;
            std::unique_lock<std::mutex> lock(mutex_);
            --copying_;
            stats_.copyMsTotal += copyMs;
            stats_.copyMsMax = std::max(stats_.copyMsMax, copyMs);
            if (ok) {
                ++stats_.copied;
            } else {
                ++stats_.failed;
                stats_.lastError = job.snapshot.copyResult.error.empty() ? "exception" : job.snapshot.copyResult.error;
                if (failureLogged_ != generation_) {
                    failureLogged_ = generation_;
                    const auto& r = job.snapshot.copyResult;
                    failureLine = "PRECAPTURE_COPY_FAILED gen=" + std::to_string(generation_) + " error=" +
                                  stats_.lastError + " size=" + std::to_string(frame.width) + "x" +
                                  std::to_string(frame.height) + " rowStride=" + std::to_string(r.sourceRowStrideBytes) +
                                  " pixelStride=" + std::to_string(r.sourcePixelStrideBytes) +
                                  " lockMs=" + std::to_string(int(r.lockMs));
                }
            }
            statsLine = statsLineLocked();
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
            lock.unlock();
            if (!failureLine.empty()) emit(failureLine);
            if (!statsLine.empty()) emit(statsLine);
        }
    }
    // Every 2 s while armed: what the ring took and why it didn't (PRECAPTURE_STATS), then the counts restart.
    std::string statsLineLocked() {
        if (maxFrames_ == 0 && stats_.offered == 0) return {};
        const auto now = std::chrono::steady_clock::now();
        if (now - statsAt_ < std::chrono::seconds(2)) return {};
        statsAt_ = now;
        const uint64_t copies = stats_.copied + stats_.failed;
        std::string line = "PRECAPTURE_STATS gen=" + std::to_string(generation_) + " held=" + std::to_string(ring_.size()) +
            "/" + std::to_string(maxFrames_) + " offered=" + std::to_string(stats_.offered) + " copied=" +
            std::to_string(stats_.copied) + " failed=" + std::to_string(stats_.failed) + " skippedBusy=" +
            std::to_string(stats_.skippedBusy) + " notPending=" + std::to_string(stats_.notPending) + " neverBegun=" + std::to_string(stats_.neverBegun) + " pending=" +
            std::to_string(pending_.size()) + " copying=" + std::to_string(copying_) + " genDrops=" +
            std::to_string(stats_.generationDrops) + " copyMsAvg=" +
            std::to_string(copies ? int(stats_.copyMsTotal / double(copies)) : 0) + " copyMsMax=" +
            std::to_string(int(stats_.copyMsMax)) + " frozen=" + (frozenActive_ ? "1" : "0");
        if (!stats_.lastError.empty()) line += " lastError=" + stats_.lastError;
        stats_ = Stats{};
        return line;
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
    uint32_t fenceTimeouts_ = 0;
    // Copies waiting or running hold a camera image each: the reader lends 6, the viewfinder keeps up to 3.
    static constexpr uint32_t kMaxCopying = 2;
    struct Stats {
        uint64_t offered = 0, copied = 0, failed = 0, skippedBusy = 0, notPending = 0, generationDrops = 0,
                 neverBegun = 0;
        double copyMsTotal = 0.0, copyMsMax = 0.0;
        std::string lastError;
    };
    Stats stats_;
    uint64_t failureLogged_ = ~uint64_t(0);
    std::chrono::steady_clock::time_point statsAt_{};
    bool frozenActive_ = false;
    bool draining_ = false;
    std::map<uint64_t, SingleFrameSnapshot> pending_;
    std::deque<Job> jobs_;
    std::deque<SingleFrameSnapshot> ring_;
    std::deque<SingleFrameSnapshot> frozen_;
};

}  // namespace rawrcam::capture
