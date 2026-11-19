#pragma once

// Dynamic resolution: keeps 60 frames a second through the heavy moments by rendering fewer pixels
// for as long as they last, instead of dropping frames.
//
// The player picks a render resolution (DisplaySettings::Settings::renderScale) and that stays the
// resolution the game runs at. What this adds is a factor underneath it, between 1 and a half,
// applied only while the frames are actually arriving late - a boss shaking the screen, a room
// full of effects - and taken back off as soon as they are not. The player's setting is never
// written to, so nothing here is saved and nothing here is lost when the scene ends.
//
// It lowers the resolution only when the time is going to the GPU. A frame that is late because
// the game's own code took too long would be exactly as late at half the pixels, so in that case
// this stays out of the way and says so in the log rather than quietly making the picture worse
// for nothing.
//
// Off by default on desktop, on by default on a phone, where the headroom at 1080p is thin enough
// that heavy scenes are where the frames go.
namespace DynamicResolution {

// On by default on Android (see dynamic_resolution.cpp). A settings toggle can call this; turning it
// off restores the player's full resolution at the next frame.
void SetEnabled(bool enabled);
bool Enabled();

// Once per presented frame, on the game thread, with where that frame's time went:
//   frameMs     wall clock since the previous presented frame
//   endFrameMs  aurora_end_frame: encoding, submission, and waiting on a GPU that is behind
//   paceMs      time spent idle waiting for the retrace boundary, which is the headroom there was
//   budgetMs    the frame's own budget (16.67 at 60 Hz, 20 at 50)
void OnFramePresented(double frameMs, double endFrameMs, double paceMs, double budgetMs);

// The factor the renderer currently applies to the player's render scale: 1 when there is headroom,
// down to kMinimumScale while a scene is too heavy for the GPU to finish in time.
float Scale();

// Back to the player's full resolution, and forget what has been measured. For a scene change, and
// for the moment the setting is turned off.
void Reset();

// The floor: a quarter of the pixels of the player's setting, which is the point past which the
// picture costs more than the frames are worth.
inline constexpr float kMinimumScale = 0.5f;

} // namespace DynamicResolution
