# Dependencies Analysis

## 1. CrabNet
- **Role**: TES3MP Networking engine (Fork of RakNet).
- **Compatibility**: Android ARM64 (`arm64-v8a`) is fully supported via the NDK toolchain. Requires CMake 3.5+ and C++11.
- **Location**: `extern/CrabNet/`
- **Notes**: Must be linked as `RakNetLibStatic`. TES3MP is explicitly incompatible with vanilla RakNet, so CrabNet must be used.

## 2. OpenXR
- **Role**: VR rendering backend and headset communication for Meta Quest 2.
- **Compatibility**: Requires `openxr_loader.so` version **1.0.34+**. Earlier versions will crash on Quest OS v62 and newer.
- **Location**: Headers in `extern/openxr/include/`, binaries should be placed in `extern/openxr/libs/android/arm64-v8a/`.

## 3. Lua 5.3
- **Role**: Server scripting system for TES3MP.
- **Compatibility**: Compatible with both Linux and Android/Termux environments.
- **Location**: Scripts are located in `server/scripts/`.

## 4. OpenMW-VR (madsbuvi branch)
- **Role**: OpenXR swapchain and VR rendering pipeline.
- **Notes**: Merged into `Arawenz/VRtes3mp`. Employs `mwvr::` namespaces and intercepts traditional OpenMW rendering paths to render stereoscopically.
