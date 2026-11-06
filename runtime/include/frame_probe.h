#pragma once

// Cumulative counters for the three waits that can stall a guest frame. They are read
// by the "slow frame" log in hle/vi.cpp, so a capture of a stutter shows which of them moved.
// Relaxed atomics: the counts are diagnostics, not synchronization.
#include <atomic>
#include <cstdint>

namespace FrameProbe {
// GXSetDrawSync calls, and how many of them found a sync callback to fire.
inline std::atomic<uint32_t> drawSyncCalls{0};
inline std::atomic<uint32_t> drawSyncCallbacks{0};
// GKI_delay calls from the guest, and the total time they asked the thread to sleep.
inline std::atomic<uint32_t> gkiDelayCalls{0};
inline std::atomic<uint64_t> gkiDelayMicros{0};
// Retries waiting for the frame worker to accept a new frame, and retries that gave up.
inline std::atomic<uint32_t> beginRetries{0};
inline std::atomic<uint32_t> beginExpired{0};
}  // namespace FrameProbe
