# TES3MP VR — Build Log

## Summary of Completed Steps (0-8)

### 0. Cloning Repositories
- Created `source_repos/` folder.
- Cloned the following repositories:
  - `Arawenz/VRtes3mp` (Base repo)
  - `mmry2940/openmw-vr-quest` (Android scaffold)
  - `Team-Beef-Studios/openmw` (Quest patches)
  - `TES3MP/TES3MP` (Reference)
  - `TES3MP/CrabNet` (Network library)
  - `madsbuvi/openmw` (VR base)
  - `xyzz/openmw-android` (Reference)
  - `tomups/openmw-vr-quest` (Reference)
  - `TES3MP/CoreScripts` (Server scripts)
  - `KhronosGroup/OpenXR-SDK` (OpenXR SDK for Android Loader)

### 1-4. Merging Strategy & CMake Configuration
- Documented findings in `docs/MERGE_STRATEGY.md`.
- Initialized `tes3mp-vr/` directory and copied all contents from `Arawenz/VRtes3mp`.
- Copied `mmry2940/openmw-vr-quest/app`, `gradle`, and related Android configurations to `tes3mp-vr/android`.
- Copied `CrabNet` source to `tes3mp-vr/extern/CrabNet`.
- Copied `OpenXR-SDK/include` to `tes3mp-vr/extern/openxr`.
- Injected Android, VR, and CrabNet specific configurations into `CMakeLists.txt` using a Python patch script to override standard OpenMW configurations with Android Quest parameters.

### 5-7. Android Application Layer
- Implemented `xyz.morrowind.tes3mpvr.CfgManager` in Kotlin to parse and generate `openmw.cfg` with categories for ESM, ESP, OMWADDON, GRASS, and BSA files.
- Implemented `ModListAdapter` to support Drag-and-Drop RecyclerView for load order sorting.
- Implemented `LauncherActivity` to manage mod configuration and serve as the main intent entrypoint.
- Added `MainActivity` overriding `GameActivity` (from the Android scaffold).
- Added UI XML layout for the Launcher.
- Generated Quest 2 specific `AndroidManifest.xml` utilizing the Khronos `OPENXR_SYSTEM` permission, headtracking feature requirements, and ensuring that `LauncherActivity` starts first.
- Modified `build.gradle` (using a Python patch script) targeting API level 34 for compilation, minimum SDK 26 (Quest 2 minimum), and enforcing `arm64-v8a` ABI. Also swapped `kotlin-android-extensions` for `kotlin-parcelize`.

### 8. Build Scripts
- Unignored `buildscripts/` folder inside `.gitignore` which was incorrectly matching `build*/`.
- Created C++ Cmake build script for ARM64 Android (`buildscripts/build-libs-android.sh`).
- Created Gradle APK build script (`buildscripts/build-apk.sh`).
- Created Termux local server build script (`buildscripts/build-server-termux.sh`).

### Next Steps / Missing Aspects
- Implement a detailed `docs/BUILD.md` outlining the requirements for Linux / WSL.
- Perform a dry run of the Android CMAKE compilation using `build-libs-android.sh` to track any C++ merge conflicts or missing dependencies in the Android JNI linkage.
- Ensure `openxr_loader.so` (v1.0.34+) is actively present in the NDK include path during compilation.
