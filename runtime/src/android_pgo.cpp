// Profile recording for the Android profile-guided build (cmake -DMKW_PGO=generate).
//
// An Android app never returns from main, so the compiler runtime's exit-time profile write never
// happens. Write the counters on a timer instead, into the app's data directory, from where a
// debuggable build's profile can be pulled. Each write replaces the file with the run's cumulative
// counts, so the last one before the app closes is the whole session. Pull it just after a write
// (the size stops changing), not during one.

#if defined(__ANDROID__) && defined(MKW_PGO_GENERATE)

#include "runtime_config.h"
#include "runtime_log.h"

#include <chrono>
#include <cstdio>
#include <filesystem>
#include <string>
#include <thread>

extern "C" void __llvm_profile_set_filename(const char* name);
extern "C" int __llvm_profile_write_file(void);

void StartAndroidPgoRecording() {
    static std::string path;
    if (!path.empty()) {
        return;
    }
    const std::filesystem::path dir = RuntimeConfigFile::ApplicationDataDirectory() / "pgo";
    std::error_code ec;
    std::filesystem::create_directories(dir, ec);
    path = (dir / "game.profraw").string();
    __llvm_profile_set_filename(path.c_str());
    RT_LOGF(RT_TAG_RUNTIME, "PGO recording build: profile written every 20 s to %s\n", path.c_str());
    std::thread([] {
        for (;;) {
            std::this_thread::sleep_for(std::chrono::seconds(20));
            // The runtime appends to an existing file, and llvm-profdata would then add every
            // cumulative snapshot together, counting the start of the session many times over.
            std::remove(path.c_str());
            __llvm_profile_write_file();
        }
    }).detach();
}

#endif
