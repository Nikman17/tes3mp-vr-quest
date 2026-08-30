# Conflict Resolutions

## 1. Base Merge (TES3MP 0.8.0 + OpenMW-VR 0.47)
- **Status**: Resolved
- **Details**: Instead of performing a 3-way merge manually from scratch, we utilized `Arawenz/VRtes3mp` as the primary merge base. This repository already successfully resolves the massive C++ conflicts between TES3MP's networking hooks (`apps/tes3mp-client`) and the OpenMW-VR rendering pipeline (`components/vr`, `madsbuvi`). 

## 2. Android Lifecycle & OpenXR Initialization
- **Status**: Resolved
- **Conflict**: Standard OpenMW-Android uses a generic SDL GLES surface. Meta Quest VR requires an OpenXR swapchain initialization via JNI before the SDL layer fully takes over.
- **Resolution**: Integrated the `mmry2940/openmw-vr-quest` Android app structure. Replaced the generic `NativeActivity` with a custom `MainActivity` inheriting from `GameActivity` (the SDL wrapper). CMake was patched to ensure `OPENXR_LOADER` is actively linked during the Android build.

## 3. CrabNet Integration (Networking)
- **Status**: Resolved
- **Conflict**: Vanilla OpenMW doesn't use CrabNet. TES3MP requires it over RakNet, and the Android build needed specific paths.
- **Resolution**: Modified the root `CMakeLists.txt` using a Python script to explicitly include `add_subdirectory(extern/CrabNet)` when `BUILD_MULTIPLAYER` is ON. Forced definitions `-DTES3MP -DCRABNET` to propagate to the rest of the C++ codebase.

## 4. `openmw.cfg` Management & UI
- **Status**: Resolved
- **Conflict**: The original Quest OpenMW implementations use a hardcoded `openmw.cfg` file pushed via ADB. TES3MP requires heavy modding (ESM/ESP, groundcover `.omwaddon`), meaning dynamic configuration is needed.
- **Resolution**: Built a full native Android UI layer:
  - `CfgManager.kt` reads/writes dynamically to `/sdcard/tes3mp-vr/openmw.cfg`.
  - `LauncherActivity.kt` acts as the primary Intent filter to capture the user before launching the native VR library.
  - Implemented a RecyclerView with `ItemTouchHelper` to allow drag-to-reorder sorting of plugins.
