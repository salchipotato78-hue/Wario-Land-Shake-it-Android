plugins {
    id("com.android.application")
}

android {
    namespace = "com.wario.shakeit"
    compileSdk = 35
    // Same NDK as the CI workflow (.github/workflows/android.yml).
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.wario.shakeit"
        // Android 10. The engine needs a 64-bit CPU and a working Vulkan 1.1 driver, which in practice
        // means Snapdragon 630/660 or newer (Adreno 5xx late / 6xx+) or an equivalent Mali.
        //
        // The floor is 29 for the engine's speed, not the app's: below 29 the NDK emulates thread-local
        // storage, and the runtime reads thread-locals on nearly every translated guest call. Keep this in
        // step with the minimum API the native build targets (-DANDROID_PLATFORM comes from here).
        minSdk = 29
        targetSdk = 35
        // Android installs an update only over an older versionCode. CI sets WARIO_VERSION_CODE to the
        // run number, so every build can update the one before it.
        versionCode = System.getenv("WARIO_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("WARIO_VERSION_NAME")?.takeIf { it.isNotEmpty() } ?: "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // Translated shards compile in parallel; CI sets WARIO_JOBS for the runner size.
                // The runtime only builds in Release. AGP passes its own CMAKE_BUILD_TYPE first, so this later -D wins.
                // Aurora cross-compiles Dawn from google/dawn by default ("vendor"), and that tag 404s.
                // "package" downloads the prebuilt Dawn for android-aarch64 with a pinned SHA-256.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release",
                    "-DAURORA_DAWN_PROVIDER=package",
                    "-DMKW_TRANSLATED_COMPILE_JOBS=" + (System.getenv("WARIO_JOBS") ?: "2"))
                // CI sets WARIO_CCACHE=1 to cache C/C++ objects between runs. Local builds are unchanged.
                // CI sets WARIO_FETCH_DIR to a cached folder, so SDL, Dawn and the other dependencies are not
                // downloaded again on every run. Local builds keep the default _deps folder.
                System.getenv("WARIO_FETCH_DIR")?.takeIf { it.isNotEmpty() }?.let {
                    arguments += "-DFETCHCONTENT_BASE_DIR=$it"
                }
                if (System.getenv("WARIO_CCACHE") == "1") {
                    arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache",
                        "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
                }
            }
        }
    }

    // The lint "vital" check runs with assembleRelease and is not needed for the APK; turning it off saves time.
    lint {
        checkReleaseBuilds = false
    }

    externalNativeBuild {
        cmake {
            // The whole WarioRecomp runtime (and Aurora) builds from the repo's runtime/ folder.
            path = file("../../runtime/CMakeLists.txt")
            version = "3.31.6"
            // No "targets" here: CMake's default target builds WiiCompiled (the product, libmain.so).
        }
    }

    sourceSets {
        getByName("main") {
            // Runtime support files (DSP ROM, pipeline cache, Wii bootstrap NAND, CA bundle).
            assets.srcDirs("../../runtime/assets")
        }
    }

    // One stable key for every build: an update installs over the old app only when both are signed with
    // the same key. Set WARIO_KEYSTORE_PATH and the passwords (CI takes them from secrets). Without them the
    // debug key is used, which differs from machine to machine, so those builds can't update each other.
    val releaseKeystore = System.getenv("WARIO_KEYSTORE_PATH")?.takeIf { it.isNotEmpty() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("wario") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("WARIO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("WARIO_KEY_ALIAS")
                keyPassword = System.getenv("WARIO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("wario")
            } else {
                logger.warn("WARIO_KEYSTORE_PATH is not set: signing with the debug key, which can't update older builds.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
