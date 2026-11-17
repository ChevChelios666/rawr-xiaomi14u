#pragma once
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <thread>
#include <vector>

namespace rawrcam::capture {

// Reuses still RAW buffers (25 MB at 12 MP). Allocating and zero-filling a fresh one for every request took
// 70-150 ms, and each frame waited for it, which held Burst capture to ~7 fps (diagnostics 14 Nov 2026). A buffer
// returns here once its frame is journaled to disk; the next request takes it as is (every byte is overwritten by
// the RAW copy). Holds at most kMaxBytes, so large (50 / 200 MP) buffers are kept few or not at all.
class RawBufferPool final {
   public:
    static std::vector<uint8_t> take(std::size_t bytes) {
        {
            std::lock_guard<std::mutex> lock(mutex());
            auto& pool = buffers();
            for (auto it = pool.begin(); it != pool.end(); ++it) {
                if (it->capacity() < bytes) continue;
                std::vector<uint8_t> buffer = std::move(*it);
                pool.erase(it);
                pooledBytes() -= buffer.capacity();
                buffer.resize(bytes);
                return buffer;
            }
        }
        std::vector<uint8_t> fresh;
        fresh.resize(bytes);
        return fresh;
    }

    /** False when the pool is full (the buffer is freed). */
    static bool give(std::vector<uint8_t>&& buffer) {
        if (buffer.capacity() == 0) return false;
        std::vector<uint8_t> owned = std::move(buffer);
        std::lock_guard<std::mutex> lock(mutex());
        if (pooledBytes() + owned.capacity() > capLocked()) return false;  // freed as `owned` leaves scope
        pooledBytes() += owned.capacity();
        buffers().push_back(std::move(owned));
        return true;
    }

    // Burst mode entered: fill the pool ahead (on a worker), so the first frames don't wait either.
    static void prewarm(std::size_t bytes, std::size_t count) {
        if (!bytes) return;
        for (std::size_t i = 0; i < count; ++i) {
            {
                std::lock_guard<std::mutex> lock(mutex());
                std::size_t fitting = 0;
                for (const auto& b : buffers()) fitting += b.capacity() >= bytes ? 1 : 0;
                if (fitting >= count || pooledBytes() + bytes > capLocked()) return;
            }
            std::vector<uint8_t> fresh;
            fresh.resize(bytes);  // zero-fill also brings the pages in
            give(std::move(fresh));
        }
    }

    /** Buffers of at least [bytes] waiting here. */
    static std::size_t fitting(std::size_t bytes) {
        std::lock_guard<std::mutex> lock(mutex());
        std::size_t n = 0;
        for (const auto& b : buffers()) n += b.capacity() >= bytes ? 1 : 0;
        return n;
    }

    /**
     * X14U Burst pre-capture (Nov 17): while the ring is armed the pool may keep up to [bytes] (its frames plus a
     * few), so a lens switch or a run hands its buffers back here instead of freeing them; a new 25 MB buffer took
     * ~170 ms. 0 returns to the normal cap; what's over it is freed on a background thread.
     */
    static void setReserve(std::size_t bytes) {
        std::vector<std::vector<uint8_t>> extra;
        {
            std::lock_guard<std::mutex> lock(mutex());
            reserve() = bytes;
            auto& pool = buffers();
            while (!pool.empty() && pooledBytes() > capLocked()) {
                pooledBytes() -= pool.back().capacity();
                extra.push_back(std::move(pool.back()));
                pool.pop_back();
            }
        }
        if (!extra.empty()) std::thread([freed = std::move(extra)]() mutable { freed.clear(); }).detach();
    }

   private:
    static std::size_t capLocked() { return std::max(kMaxBytes, reserve()); }
    static std::size_t& reserve() {
        static std::size_t n = 0;
        return n;
    }
    // 8 frames at 12 MP: a burst's journal writes can lag a few frames behind capture (Nov 14: the pool ran dry
    // and frames waited 65-165 ms for fresh buffers).
    static constexpr std::size_t kMaxBytes = std::size_t(208) * 1024 * 1024;
    static std::mutex& mutex() {
        static std::mutex m;
        return m;
    }
    static std::vector<std::vector<uint8_t>>& buffers() {
        static std::vector<std::vector<uint8_t>> b;
        return b;
    }
    static std::size_t& pooledBytes() {
        static std::size_t n = 0;
        return n;
    }
};

// X14U Burst: background development waits while the shutter is held, so capture (RAW copies, journal writes,
// gallery entries) has the phone to itself; it resumes on release. A run that is never released resumes after 60 s.
class StillDevelopGate final {
   public:
    static void setPaused(bool paused) {
        pausedSince().store(paused ? std::chrono::steady_clock::now().time_since_epoch().count() : 0);
    }
    static bool paused() {
        const auto since = pausedSince().load();
        if (!since) return false;
        const auto now = std::chrono::steady_clock::now().time_since_epoch().count();
        return now - since < std::chrono::duration_cast<std::chrono::steady_clock::duration>(std::chrono::seconds(60)).count();
    }

   private:
    static std::atomic<std::chrono::steady_clock::rep>& pausedSince() {
        static std::atomic<std::chrono::steady_clock::rep> v{0};
        return v;
    }
};

}  // namespace rawrcam::capture
