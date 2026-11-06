#include "fifo.hpp"
#include "command_processor.hpp"
#include "../gfx/common.hpp"
#include "../internal.hpp"

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#include "tracy/Tracy.hpp"

namespace aurora::gx::fifo {
static Module Log("aurora::gx::fifo");

// A drain swaps the shared command buffer for a fresh one, and the buffer it just decoded is kept
// here for the next drain. Drains run on every texture copy and draw-done, and the live buffer
// keeps its peak capacity, so allocating a new one each time cost a full-size malloc/free per call.
namespace {
uint8_t* sSpareData = nullptr;
uint32_t sSpareCapacity = 0;
}  // namespace

namespace detail {
uint8_t* sBufferData = nullptr;
uint32_t sBufferSize = 0;
uint32_t sBufferCapacity = 0;
bool sInDisplayList = false;
uint8_t* sDlBuffer = nullptr;
uint32_t sDlSize = 0;
uint32_t sDlWritePos = 0;
} // namespace detail

void init() {
  constexpr uint32_t initialCapacity = 64 * 1024;
  reset_cp_register_cache();
  free(detail::sBufferData);
  free(sSpareData);
  sSpareData = nullptr;
  sSpareCapacity = 0;
  detail::sBufferData = static_cast<uint8_t*>(malloc(initialCapacity));
  detail::sBufferSize = 0;
  detail::sBufferCapacity = initialCapacity;
  detail::sInDisplayList = false;
  detail::sDlBuffer = nullptr;
  detail::sDlSize = 0;
  detail::sDlWritePos = 0;
}

void write_data_grow(const void* data, uint32_t length) {
  uint32_t needed = detail::sBufferSize + length;
  uint32_t newCap = std::max(detail::sBufferCapacity * 2, needed);
  detail::sBufferData = static_cast<uint8_t*>(realloc(detail::sBufferData, newCap));
  std::memcpy(detail::sBufferData + detail::sBufferSize, data, length);
  detail::sBufferSize = needed;
  detail::sBufferCapacity = newCap;
}

void begin_display_list(uint8_t* buf, uint32_t size) {
  detail::sInDisplayList = true;
  detail::sDlBuffer = buf;
  detail::sDlSize = size;
  detail::sDlWritePos = 0;
}

uint32_t end_display_list() {
  detail::sInDisplayList = false;
  uint32_t bytesWritten = detail::sDlWritePos;
  uint32_t padded = (bytesWritten + 31) & ~31u;
  while (detail::sDlWritePos < padded && detail::sDlWritePos < detail::sDlSize) {
    detail::sDlBuffer[detail::sDlWritePos++] = 0;
  }
  detail::sDlBuffer = nullptr;
  detail::sDlSize = 0;
  detail::sDlWritePos = 0;
  return padded;
}

bool in_display_list() { return detail::sInDisplayList; }

// How much of the producer's frame is spent blocked before it may decode the next batch of GX commands.
static void note_drain_wait(uint64_t nanos) noexcept {
  ZoneScopedN("FIFO drain wait");
  TracyPlot("aurora: fifoDrainWaitUs", static_cast<int64_t>(nanos / 1000));
}

void drain() {
  // SEALED, not DONE.
  const auto waited = aurora::wait_for_frame_worker_sealed();
  if (waited.count() > 0) UNLIKELY {
    note_drain_wait(static_cast<uint64_t>(waited.count()));
  }
  if (detail::sBufferSize == 0) {
    return;
  }

  // Take the pending commands out of the shared buffer before decoding them. Decoding can
  // re-enter GX: the frame-wait hook runs guest alarm and retrace handlers, and those may write
  // commands. Such writes must land in a fresh buffer. Growing the buffer that process() is
  // reading would leave it pointing at freed memory, and clearing the size at the end would
  // silently drop the commands written during the re-entry.
  uint8_t* pending = detail::sBufferData;
  const uint32_t pendingSize = detail::sBufferSize;
  const uint32_t pendingCapacity = detail::sBufferCapacity;
  // Reuse the spare buffer when it is big enough; otherwise allocate at the live capacity as before.
  if (sSpareData != nullptr && sSpareCapacity >= detail::sBufferCapacity) {
    detail::sBufferData = sSpareData;
    detail::sBufferCapacity = sSpareCapacity;
  } else {
    free(sSpareData);
    detail::sBufferData = static_cast<uint8_t*>(malloc(detail::sBufferCapacity));
  }
  sSpareData = nullptr;
  sSpareCapacity = 0;
  detail::sBufferSize = 0;

  uint32_t consumed = 0;
  bool retried = false;
  try {
    while (consumed < pendingSize) {
      const auto count = process(pending + consumed, pendingSize - consumed, true);
      if (count == 0 && retried)
        throw gfx::StagingCapacityError("FIFO draw does not fit after capacity submission");
      consumed += count;
      if (consumed == pendingSize) break;
      // process returned with its renderer lock released. No recursive drain.
      gfx::split_staging_batch();
      retried = true;
    }
  } catch (...) {
    free(pending);
    throw;
  }
  // Keep the decoded buffer as the spare. A nested drain may already have left one behind.
  free(sSpareData);
  sSpareData = pending;
  sSpareCapacity = pendingCapacity;
}

const uint8_t* get_buffer_data() { return detail::sBufferData; }
uint32_t get_buffer_size() { return detail::sBufferSize; }
void clear_buffer() {
  detail::sBufferSize = 0;
}

} // namespace aurora::gx::fifo
