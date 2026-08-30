#!/bin/bash
set -e

NDK="${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/26.3.11579264}"
ABI="arm64-v8a"
PLATFORM="android-26"
INSTALL_DIR="$(pwd)/android/app/jni/prebuilt"

CMAKE_ANDROID_ARGS=(
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI="$ABI"
  -DANDROID_PLATFORM="$PLATFORM"
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_INSTALL_PREFIX="$INSTALL_DIR"
)

echo "=== Building CrabNet for Android ARM64 ==="
mkdir -p build-android/crabnet && cd build-android/crabnet
cmake ../../extern/CrabNet "${CMAKE_ANDROID_ARGS[@]}"
cmake --build . -j$(nproc) --target RakNetLibStatic
cd ../..

echo "=== Building OpenMW+VR+TES3MP for Android ARM64 ==="
mkdir -p build-android/openmw && cd build-android/openmw
cmake ../.. "${CMAKE_ANDROID_ARGS[@]}" \
  -DBUILD_ANDROID=ON \
  -DBUILD_MULTIPLAYER=ON \
  -DBUILD_VR=ON \
  -DBUILD_SERVER=OFF \
  -DBUILD_OPENCS=OFF
cmake --build . -j$(nproc)
cmake --install .
cd ../..

echo "=== Copying .so files to jniLibs ==="
mkdir -p android/app/src/main/jniLibs/$ABI
find build-android -name "*.so" -exec cp {} android/app/src/main/jniLibs/$ABI/ \;

echo "Done. Run: buildscripts/install_and_apk.sh"
