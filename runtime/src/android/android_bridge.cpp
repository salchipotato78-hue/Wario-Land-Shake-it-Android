// android_bridge.cpp - JNI entry points the Android app (com.wario.shakeit) calls into libmain.so.
#if defined(__ANDROID__)

#include "display_settings.h"
#include "graphics_info.h"
#include "runtime_config.h"
#include "settings_overlay.h"
#include "thermal_quality.h"
extern "C" uint32_t WarioHostSkipButtons();
extern "C" void WarioIntroButtons(uint32_t hold);
extern "C" void WarioSetAndroidDisplayClock(int64_t vblankNanos, double hz);  // hle/vi.cpp
#include "virtual_remote.h"

#include "netplay/net_socket.h"
#include "netplay_session.h"
#include "netplay_start.h"
#include "netplay_ui.h"
#include "runtime_log.h"

#include <SDL3/SDL_system.h>
#include <jni.h>

#include <android/api-level.h>
#include <sys/syscall.h>
#include <unistd.h>
#if __ANDROID_API__ >= 33
#include <android/performance_hint.h>
#endif

#include <chrono>
#include <cstdint>
#include <string>
#include <thread>

namespace AndroidBridge {

// Network play: ask the activity to start the game again in a fresh process (GameActivity
// .restartForSession), then park this thread until Android ends the process.
bool RestartGame() {
    auto* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
    auto activity = static_cast<jobject>(SDL_GetAndroidActivity());
    if (env == nullptr || activity == nullptr) {
        return false;
    }
    jclass type = env->GetObjectClass(activity);
    jmethodID restart = type != nullptr ? env->GetMethodID(type, "restartForSession", "()V") : nullptr;
    if (restart == nullptr) {
        env->ExceptionClear();
        return false;
    }
    env->CallVoidMethod(activity, restart);
    env->DeleteLocalRef(activity);
    for (;;) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
}

} // namespace AndroidBridge

extern "C" {

// GameActivity.nativeSetTouchState: the on-screen remote (VirtualRemote::TouchButton bits) and the
// phone's motion (shake, tilt -1..1) for player 1. Called from the UI thread on every change.
JNIEXPORT void JNICALL Java_com_wario_shakeit_GameActivity_nativeSetTouchState(
    JNIEnv*, jclass, jint buttons, jboolean shake, jfloat tilt)
{
    VirtualRemote::SetTouchState(static_cast<uint32_t>(buttons), shake == JNI_TRUE, tilt);
}

// GameActivity.nativeSetPhoneAccel: the phone's accelerometer in KPAD's frame (g). Tilting and
// shaking the phone is the Wii Remote's motion, so the game reads it instead of the shake button.
JNIEXPORT void JNICALL Java_com_wario_shakeit_GameActivity_nativeSetPhoneAccel(
    JNIEnv*, jclass, jfloat x, jfloat y, jfloat z, jboolean valid)
{
    VirtualRemote::SetPhoneAccel(x, y, z, valid == JNI_TRUE);
}

// GameActivity.nativeSetTouchAim: the finger's position in the view (pixels) and the view's size.
JNIEXPORT void JNICALL Java_com_wario_shakeit_GameActivity_nativeSetTouchAim(
    JNIEnv*, jclass, jfloat x, jfloat y, jfloat viewWidth, jfloat viewHeight)
{
    VirtualRemote::SetTouchAim(x, y, viewWidth, viewHeight);
}

// GameActivity.nativeApplyDisplaySettings: the Display menu. The aspect (0 original 4:3, 1 16:9,
// 2 fill the screen) and the render scale (480 lines per 1x) apply at the next frame; 4:3 versus a
// widescreen mode only at the next start, since the game reads it at boot.
JNIEXPORT void JNICALL Java_com_wario_shakeit_GameActivity_nativeApplyDisplaySettings(
    JNIEnv*, jclass, jint aspect, jfloat renderScale, jboolean showFps)
{
    // Through the settings overlay, so the game's own settings window shows the same choice. A scale
    // of 0 is "match the screen".
    settings_overlay::SetAspect(aspect < 0 || aspect > 3 ? DisplaySettings::kAspectFill : static_cast<int32_t>(aspect));
    settings_overlay::SetRenderScale(renderScale >= 0.0f ? renderScale : 1.0f);
    settings_overlay::SetShowFps(showFps == JNI_TRUE);
}

// GameActivity.nativeReportThermalHeadroom: PowerManager.getThermalHeadroom, about once a second.
// Around 1.0 means throttling is imminent; NaN means the device cannot tell (ignored by the runtime).
JNIEXPORT void JNICALL Java_com_wario_shakeit_GameActivity_nativeReportThermalHeadroom(
    JNIEnv*, jclass, jfloat headroom)
{
    ThermalQuality::ReportHeadroom(static_cast<float>(headroom));
}

// GameActivity.nativeSetDisplayClock: called on every display frame by the activity (Choreographer).
// Gives the game clock the real vblank time and refresh rate, so it can lock to the screen.
JNIEXPORT void JNICALL Java_com_wario_shakeit_GameActivity_nativeSetDisplayClock(
    JNIEnv*, jclass, jlong vblankNanos, jdouble hz)
{
    WarioSetAndroidDisplayClock(static_cast<int64_t>(vblankNanos), static_cast<double>(hz));
}

// GameActivity.nativeGraphicsInfo: the GPU the game is drawing with and whether the GPU
// compatibility fixes are on, as "<name>\n<1|0>"; empty until the renderer has started.
JNIEXPORT jstring JNICALL Java_com_wario_shakeit_GameActivity_nativeGraphicsInfo(JNIEnv* env, jclass)
{
    const std::string name = GraphicsInfo::Name();
    if (name.empty()) {
        return env->NewStringUTF("");
    }
    const std::string info = name + "\n" + (GraphicsInfo::CompatActive() ? "1" : "0");
    return env->NewStringUTF(info.c_str());
}

// GameActivity.nativeSetDeviceName: what this phone is called in room lists.
JNIEXPORT void JNICALL Java_com_wario_shakeit_GameActivity_nativeSetDeviceName(JNIEnv* env, jclass, jstring name)
{
    if (name == nullptr) {
        return;
    }
    const char* utf8 = env->GetStringUTFChars(name, nullptr);
    if (utf8 != nullptr) {
        Netplay::SetDeviceName(utf8);
        env->ReleaseStringUTFChars(name, utf8);
    }
}

// GameActivity.nativeSessionActive: whether this run is a LAN session (the menu offers to leave it).
JNIEXPORT jboolean JNICALL Java_com_wario_shakeit_GameActivity_nativeSessionActive(JNIEnv*, jclass)
{
    return NetplaySession::Active() ? JNI_TRUE : JNI_FALSE;
}

// GameActivity.nativePrepareToLeave: the menu's "Leave LAN game", before the app restarts the game.
JNIEXPORT void JNICALL Java_com_wario_shakeit_GameActivity_nativePrepareToLeave(JNIEnv*, jclass)
{
    NetplayStart::PrepareToLeave();
}

// GameActivity.nativeTap: a tap on the game's menus, as fractions of the screen (netplay_ui.h).
JNIEXPORT void JNICALL Java_com_wario_shakeit_GameActivity_nativeTap(JNIEnv*, jclass, jfloat x, jfloat y)
{
    NetplayUi::Tap(x, y);
}

// GameActivity.nativeTakeSettings: {render scale, show FPS 1/0, aspect, spinning coins 1/0, frame graph 1/0} when the game's settings
// window changed them since the last call, else null; the app saves them for the next start.
JNIEXPORT jfloatArray JNICALL Java_com_wario_shakeit_GameActivity_nativeTakeSettings(JNIEnv* env, jclass)
{
    float scale = 1.0f;
    bool showFps = false;
    int aspect = DisplaySettings::kAspectFill;
    bool spinningCoins = true;
    bool frameGraph = false;
    if (!NetplayUi::TakeSettingsChange(scale, showFps, aspect, spinningCoins, frameGraph)) {
        return nullptr;
    }
    jfloatArray result = env->NewFloatArray(5);
    const jfloat values[5] = {scale, showFps ? 1.0f : 0.0f, static_cast<jfloat>(aspect), spinningCoins ? 1.0f : 0.0f,
                              frameGraph ? 1.0f : 0.0f};
    env->SetFloatArrayRegion(result, 0, 5, values);
    return result;
}

// GameActivity.nativeTapScreen: whether a menu that taps drive is up (the app hides its remote).
JNIEXPORT jboolean JNICALL Java_com_wario_shakeit_GameActivity_nativeTapScreen(JNIEnv*, jclass)
{
    return NetplayUi::TapScreenUp() ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"

// ----------------------------------------------------------------------------
// Sustained performance for the game thread (ADPF, Android 13+).
//
// The guest game runs on one thread and is paced by the display clock. Without a hint, the
// scheduler does not know that this thread has a per-frame budget, so under load it can stay on
// a low clock or a little core and miss a vblank; the frame then shows up as 33 ms instead of
// 16.7 ms. The hint tells the system the thread's frame budget and how much work each frame took
// (the time spent before the vblank wait, reported by hle/vi.cpp). It only changes scheduling:
// the emulated timing and the output are unchanged. On older devices, or when the hint cannot be
// created, this does nothing.
namespace {
#if __ANDROID_API__ >= 33
APerformanceHintSession* g_guestHintSession = nullptr;
bool g_guestHintTried = false;
constexpr int64_t kGuestFrameBudgetNanos = 16666666;  // one frame at 60 Hz (the guest clock)
#endif
}  // namespace

extern "C" void WarioAndroidReportFrameWork(int64_t workNanos) {
#if __ANDROID_API__ >= 33
    // The first frame is presented from the game thread, so the session is bound to that thread.
    if (!g_guestHintTried) {
        g_guestHintTried = true;
        if (android_get_device_api_level() >= 33) {
            if (APerformanceHintManager* manager = APerformanceHint_getManager()) {
                const int32_t tid = static_cast<int32_t>(syscall(SYS_gettid));
                g_guestHintSession =
                    APerformanceHint_createSession(manager, &tid, 1, kGuestFrameBudgetNanos);
            }
        }
        RT_LOGF(RT_TAG_VI, "ADPF guest hint: api %d, session %s (tid %d)\n",
                android_get_device_api_level(), g_guestHintSession ? "created" : "not created",
                static_cast<int>(syscall(SYS_gettid)));
    }
    if (g_guestHintSession != nullptr && workNanos > 0) {
        APerformanceHint_reportActualWorkDuration(g_guestHintSession, workNanos);
    }
#else
    (void)workNanos;
#endif
}

#endif // __ANDROID__
