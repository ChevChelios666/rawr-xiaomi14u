#pragma once
#include <cstddef>
#include <cstdint>
#include <mutex>
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

    static void give(std::vector<uint8_t>&& buffer) {
        if (buffer.capacity() == 0) return;
        std::vector<uint8_t> owned = std::move(buffer);
        std::lock_guard<std::mutex> lock(mutex());
        if (pooledBytes() + owned.capacity() > kMaxBytes) return;  // freed as `owned` leaves scope
        pooledBytes() += owned.capacity();
        buffers().push_back(std::move(owned));
    }

    // Burst mode entered: fill the pool ahead (on a worker), so the first frames don't wait either.
    static void prewarm(std::size_t bytes, std::size_t count) {
        if (!bytes) return;
        for (std::size_t i = 0; i < count; ++i) {
            {
                std::lock_guard<std::mutex> lock(mutex());
                std::size_t fitting = 0;
                for (const auto& b : buffers()) fitting += b.capacity() >= bytes ? 1 : 0;
                if (fitting >= count || pooledBytes() + bytes > kMaxBytes) return;
            }
            std::vector<uint8_t> fresh;
            fresh.resize(bytes);  // zero-fill also brings the pages in
            give(std::move(fresh));
        }
    }

   private:
    static constexpr std::size_t kMaxBytes = std::size_t(128) * 1024 * 1024;
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

}  // namespace rawrcam::capture
