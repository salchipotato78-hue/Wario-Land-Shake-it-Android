#include "dynamic_resolution.h"

#include "display_settings.h"
#include "runtime_log.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>

namespace DynamicResolution {
namespace {

// The ladder. Each rung is about 15% fewer lines than the one above, so a step is roughly a
// quarter off the pixel count - enough to be worth a step, small enough that a scene which needs
// only a little help does not lose more than it has to. The bottom rung is kMinimumScale.
constexpr std::array<float, 6> kSteps{1.0f, 0.87f, 0.76f, 0.66f, 0.57f, kMinimumScale};

// Decisions are made on a window of frames rather than on single ones: one late frame is a hitch
// (a shader compiled, the disc was read) and lowering the resolution would not have prevented it.
// Half a second is long enough to tell a heavy scene from a hitch and short enough to react inside
// the scene rather than after it.
constexpr int kWindowFrames = 30;

// A frame is late when it overran its budget by more than a tenth. Below that it is the pacer's
// own jitter.
constexpr double kLateFactor = 1.1;

// A quarter of the window arriving late is a scene the GPU cannot keep up with, not noise.
constexpr int kLateFramesToStepDown = kWindowFrames / 4;

// Of the time that was not spent waiting, this much has to be the GPU's before lowering the
// resolution can help at all. Under that the frame is the game's own code and fewer pixels would
// not buy a thing.
constexpr double kGpuShareToStepDown = 0.35;

// Going back up asks for real headroom, not merely for frames that arrived on time: with the pacer
// holding every frame to its boundary, "on time" is what a frame at the very edge of the budget
// looks like too. Idle time is the honest measure, and a sixth of the budget - near 3 ms at 60 Hz -
// is enough to absorb the step.
constexpr double kIdleShareToStepUp = 1.0 / 6.0;

// And it asks for several clean windows in a row, so a scene that is only just finishing in time
// does not climb back up on its first quiet second and fall straight back down. Stepping down is
// deliberately the quicker of the two: frames are what the player notices.
constexpr int kCleanWindowsToStepUp = 4;

// After a change the next window is thrown away. Resizing recreates the render targets, which
// costs one frame of its own, and the frames right after it are measuring that rather than the
// scene.
bool g_settling = false;

// Off on desktop, on for a phone, where the headroom at 1080p is thin enough that heavy scenes are
// where the frames go.
#if defined(__ANDROID__)
constexpr bool kDefaultEnabled = true;
#else
constexpr bool kDefaultEnabled = false;
#endif
std::atomic<bool> g_enabled{kDefaultEnabled};
std::atomic<float> g_scale{1.0f};

int g_step = 0;
int g_frames = 0;
int g_lateFrames = 0;
int g_cleanWindows = 0;
double g_frameMsTotal = 0.0;
double g_endFrameMsTotal = 0.0;
double g_paceMsTotal = 0.0;
double g_budgetMsTotal = 0.0;
// Said once per direction, so a player's log shows what it decided without one line per window.
bool g_reportedCpuBound = false;

void ResetWindow() {
    g_frames = 0;
    g_lateFrames = 0;
    g_frameMsTotal = 0.0;
    g_endFrameMsTotal = 0.0;
    g_paceMsTotal = 0.0;
    g_budgetMsTotal = 0.0;
}

void ApplyStep(int step) {
    g_step = std::clamp(step, 0, static_cast<int>(kSteps.size()) - 1);
    g_scale.store(kSteps[static_cast<size_t>(g_step)], std::memory_order_release);
    DisplaySettings::SetDynamicScale(kSteps[static_cast<size_t>(g_step)]);
}

} // namespace

void SetEnabled(bool enabled) {
    const bool was = g_enabled.exchange(enabled, std::memory_order_acq_rel);
    if (was == enabled) {
        return;
    }
    RT_LOGF(RT_TAG_VI, "dynamic resolution %s\n", enabled ? "on" : "off");
    Reset();
}

bool Enabled() { return g_enabled.load(std::memory_order_acquire); }

float Scale() { return g_scale.load(std::memory_order_acquire); }

void Reset() {
    ResetWindow();
    g_cleanWindows = 0;
    g_settling = false;
    g_reportedCpuBound = false;
    if (g_step != 0) {
        ApplyStep(0);
    }
}

void OnFramePresented(double frameMs, double endFrameMs, double paceMs, double budgetMs) {
    if (!Enabled()) {
        return;
    }
    // A budget this far off is a frame produced outside the paced path (boot, a black VI frame) or
    // a timer that jumped; it says nothing about how heavy the scene is.
    if (!(budgetMs > 1.0) || !(frameMs > 0.0) || frameMs > budgetMs * 20.0) {
        return;
    }

    ++g_frames;
    g_frameMsTotal += frameMs;
    g_endFrameMsTotal += std::max(0.0, endFrameMs);
    g_paceMsTotal += std::max(0.0, paceMs);
    g_budgetMsTotal += budgetMs;
    if (frameMs > budgetMs * kLateFactor) {
        ++g_lateFrames;
    }
    if (g_frames < kWindowFrames) {
        return;
    }

    const double budget = g_budgetMsTotal / g_frames;
    const double endFrame = g_endFrameMsTotal / g_frames;
    const double pace = g_paceMsTotal / g_frames;
    const double frame = g_frameMsTotal / g_frames;
    const int late = g_lateFrames;
    ResetWindow();

    // The window after a resolution change measured the change itself.
    if (g_settling) {
        g_settling = false;
        return;
    }

    if (late >= kLateFramesToStepDown) {
        g_cleanWindows = 0;
        if (g_step + 1 >= static_cast<int>(kSteps.size())) {
            return;  // already at the floor; the scene is simply beyond this device
        }
        // What the frame cost apart from waiting: the GPU's part against the game's.
        const double working = std::max(0.1, frame - pace);
        const double gpuShare = std::min(1.0, endFrame / working);
        if (gpuShare < kGpuShareToStepDown) {
            if (!g_reportedCpuBound) {
                g_reportedCpuBound = true;
                RT_LOGF(RT_TAG_VI,
                        "dynamic resolution: %d of %d frames late but only %.0f%% of the work is the "
                        "GPU's (frame %.1f ms, end_frame %.1f ms); fewer pixels would not help, so the "
                        "resolution stays\n",
                        late, kWindowFrames, gpuShare * 100.0, frame, endFrame);
            }
            return;
        }
        ApplyStep(g_step + 1);
        g_settling = true;
        RT_LOGF(RT_TAG_VI,
                "dynamic resolution: %d of %d frames over %.1f ms and %.0f%% of the work is the GPU's; "
                "rendering at %.0f%% of the chosen resolution\n",
                late, kWindowFrames, budget, gpuShare * 100.0, kSteps[static_cast<size_t>(g_step)] * 100.0);
        return;
    }

    if (g_step == 0) {
        g_cleanWindows = 0;
        return;
    }
    // Lowered, and the frames are both on time and leaving the thread idle: there is room to give
    // some resolution back.
    if (late == 0 && pace >= budget * kIdleShareToStepUp) {
        if (++g_cleanWindows >= kCleanWindowsToStepUp) {
            g_cleanWindows = 0;
            ApplyStep(g_step - 1);
            g_settling = true;
            RT_LOGF(RT_TAG_VI,
                    "dynamic resolution: %.1f ms of each frame spare; back to %.0f%% of the chosen "
                    "resolution\n",
                    pace, kSteps[static_cast<size_t>(g_step)] * 100.0);
        }
    } else {
        g_cleanWindows = 0;
    }
}

} // namespace DynamicResolution
