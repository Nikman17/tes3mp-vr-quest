# Build Instructions

## Linux (Development)
```bash
sudo apt install build-essential cmake git
sudo apt install libboost-all-dev libsdl2-dev libopenscenegraph-dev
sudo apt install liblua5.3-dev libenet-dev
sudo apt install libopenxr-dev
```
*Note: You can also build OpenXR from source using https://github.com/KhronosGroup/OpenXR-SDK*

## Android / Quest 2
- **Android Studio** with NDK r26+ (Tested with `26.3.11579264`)
- **CMake** 3.22+
- **OpenXR loader for Android**: Khronos `openxr_loader.so` (must be version `1.0.34+` to avoid crashes on Quest OS v62+).
- **Java 17+**

### Android Build Steps:
1. Build C++ libraries (CrabNet + OpenMW-VR + TES3MP):
   ```bash
   ./buildscripts/build-libs-android.sh
   ```
2. Build the Quest 2 APK using Gradle:
   ```bash
   ./buildscripts/build-apk.sh
   ```

## Termux (Server only, ARM)
```bash
pkg install cmake make clang boost lua53 enet git
./buildscripts/build-server-termux.sh
```
