#!/bin/bash
# wsl_build_vr.sh - Build TES3MP VR APK from WSL
# Handles NDK exec-bit issue by extracting NDK to WSL ext4 filesystem
# Usage: bash wsl_build_vr.sh [--debug] [--jobs N]
#
# Prerequisites: WSL2, cmake, python3, unzip, gradle (via JAVA_HOME or sdk)

set -e

BUILDSCRIPTS=/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/buildscripts
ANDROID=/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/android

ARCH=arm64
ABI=arm64-v8a
NDK_TRIPLET=aarch64-linux-android
ANDROID_API=21
BOOST_ARCH=arm
BOOST_ADDRESS_MODEL=64
FFMPEG_CPU=armv8-a
BUILD_TYPE=release
CFLAGS="-fPIC -O3"
CXXFLAGS="-fPIC -frtti -fexceptions -O3"
LDFLAGS=""
NCPU=$(nproc)

# Parse args
while [[ $# -gt 0 ]]; do
    case "$1" in
        --debug)
            BUILD_TYPE=debug
            CFLAGS="-fPIC -O0 -g"
            CXXFLAGS="-fPIC -frtti -fexceptions -O0 -g"
            shift ;;
        --jobs)
            NCPU="$2"; shift 2 ;;
        *) echo "Unknown arg: $1"; exit 1 ;;
    esac
done

NDK_HOME="$HOME/tes3mp-vr-ndk"
BUILD_DIR="$HOME/tes3mp-build/$ARCH"
LOG="/tmp/tes3mp-vr-build-$(date +%Y%m%d-%H%M%S).log"

exec > >(tee "$LOG") 2>&1

echo "========================================"
echo " TES3MP VR APK Build"
echo " Arch:       $ARCH ($ABI)"
echo " Build type: $BUILD_TYPE"
echo " CPUs:       $NCPU"
echo " NDK home:   $NDK_HOME"
echo " Build dir:  $BUILD_DIR"
echo " Log:        $LOG"
echo "========================================"
echo ""

# ----------------------------------------------------------------
# 1. NDK: extract to WSL ext4 (preserves exec bits)
# ----------------------------------------------------------------
echo "==> [1/6] Setting up NDK r21e in ext4..."
if [ ! -d "$NDK_HOME/ndk/build" ]; then
    NDK_ZIP="$BUILDSCRIPTS/downloads/ndk-r21e.zip"
    if [ ! -f "$NDK_ZIP" ]; then
        echo "ERROR: NDK zip not found: $NDK_ZIP"
        exit 1
    fi
    mkdir -p "$NDK_HOME"
    echo "    Extracting (this takes a minute)..."
    unzip -q "$NDK_ZIP" -d "$NDK_HOME/"
    mv "$NDK_HOME"/android-ndk-* "$NDK_HOME/ndk"
    # Workaround: https://github.com/android-ndk/ndk/issues/721
    sed -i 's/Oz/O2/g' "$NDK_HOME/ndk/build/cmake/android.toolchain.cmake"
    echo "    NDK extracted: $NDK_HOME/ndk"
else
    echo "    NDK already extracted: $NDK_HOME/ndk"
fi

# Create standalone toolchain in ext4
if [ ! -d "$NDK_HOME/$ARCH/bin" ]; then
    echo "    Creating standalone toolchain for $ARCH..."
    "$NDK_HOME/ndk/build/tools/make_standalone_toolchain.py" \
        --arch "$ARCH" \
        --api "$ANDROID_API" \
        --stl libc++ \
        --install-dir "$NDK_HOME/$ARCH"

    rm -f "$NDK_HOME/$ARCH/bin/$NDK_TRIPLET-gcc"
    rm -f "$NDK_HOME/$ARCH/bin/$NDK_TRIPLET-g++"
    ln -s "$NDK_TRIPLET-clang"   "$NDK_HOME/$ARCH/bin/$NDK_TRIPLET-gcc"
    ln -s "$NDK_TRIPLET-clang++" "$NDK_HOME/$ARCH/bin/$NDK_TRIPLET-g++"
    cp "$BUILDSCRIPTS/patches/gas-preprocessor.pl" "$NDK_HOME/$ARCH/bin/"
    chmod +x "$NDK_HOME/$ARCH/bin/gas-preprocessor.pl"
    echo "    Standalone toolchain ready: $NDK_HOME/$ARCH"
else
    echo "    Standalone toolchain already exists: $NDK_HOME/$ARCH"
fi

# Symlink from Windows-FS toolchain/ dirs to ext4 NDK
echo "    Symlinking toolchain/ -> ext4 NDK..."
for entry in ndk "$ARCH"; do
    TARGET="$BUILDSCRIPTS/toolchain/$entry"
    if [ -d "$TARGET" ] && [ ! -L "$TARGET" ]; then
        rmdir "$TARGET" 2>/dev/null || true
    fi
    if [ ! -L "$TARGET" ]; then
        ln -sf "$NDK_HOME/$entry" "$TARGET"
    fi
done
echo "    toolchain/ndk   -> $NDK_HOME/ndk"
echo "    toolchain/$ARCH -> $NDK_HOME/$ARCH"

# ----------------------------------------------------------------
# 2. Prepare prefix dirs and build dir
# ----------------------------------------------------------------
echo ""
echo "==> [2/6] Preparing directories..."
mkdir -p "$BUILDSCRIPTS/prefix/$ARCH/lib"
ln -sf lib "$BUILDSCRIPTS/prefix/$ARCH/lib64" 2>/dev/null || true
mkdir -p "$BUILDSCRIPTS/prefix/$ARCH/osg/lib"
ln -sf lib "$BUILDSCRIPTS/prefix/$ARCH/osg/lib64" 2>/dev/null || true
mkdir -p "$BUILD_DIR"

# ----------------------------------------------------------------
# 3. Generate command_wrapper.sh
# ----------------------------------------------------------------
echo "==> [3/6] Generating command_wrapper.sh..."
cat "$BUILDSCRIPTS/include/command_wrapper_head.sh.in" | \
    DIR=$BUILDSCRIPTS \
    ARCH=$ARCH \
    ENV_CFLAGS="$CFLAGS" \
    ENV_CXXFLAGS="$CXXFLAGS" \
    NDK_TRIPLET=$NDK_TRIPLET \
    ENV_LDFLAGS="$LDFLAGS" \
    envsubst > "$BUILD_DIR/command_wrapper.sh"
cat "$BUILDSCRIPTS/include/command_wrapper_tail.sh.in" >> "$BUILD_DIR/command_wrapper.sh"
chmod +x "$BUILD_DIR/command_wrapper.sh"

# ----------------------------------------------------------------
# 4. CMake configure + make
# ----------------------------------------------------------------
echo ""
echo "==> [4/6] CMake configure..."
cd "$BUILD_DIR"
cmake "$BUILDSCRIPTS" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_INSTALL_PREFIX="$BUILDSCRIPTS/prefix/$ARCH/" \
    -DARCH="$ARCH" \
    -DBUILD_TYPE="$BUILD_TYPE" \
    -DNDK_TRIPLET="$NDK_TRIPLET" \
    -DANDROID_API="$ANDROID_API" \
    -DABI="$ABI" \
    -DBOOST_ARCH="$BOOST_ARCH" \
    -DBOOST_ADDRESS_MODEL="$BOOST_ADDRESS_MODEL" \
    -DFFMPEG_CPU="$FFMPEG_CPU" \
    -DALLOW_OPENMW_UPSTREAM_FALLBACK=OFF

echo ""
echo "==> [4/6] Building ($NCPU jobs) - this takes several hours..."
make -j"$NCPU"

# ----------------------------------------------------------------
# 5. Install shared libraries + resources
# ----------------------------------------------------------------
echo ""
echo "==> [5/6] Installing shared libraries and resources..."

JNIDIR="$ANDROID/app/src/main/jniLibs/$ABI"
rm -rf "$JNIDIR"
mkdir -p "$JNIDIR"

# libopenmw.so: prefer VR variant
OPENMW_VR_SO=$(find "$BUILD_DIR/openmw-prefix/" \( -iname "libopenmw_vr.so" -o -iname "libtes3mp.so" \) 2>/dev/null | head -n 1)
if [ -n "$OPENMW_VR_SO" ]; then
    cp "$OPENMW_VR_SO" "$JNIDIR/libopenmw.so"
    echo "    VR lib: $(basename $OPENMW_VR_SO) -> libopenmw.so"
else
    find "$BUILD_DIR/openmw-prefix/" -iname "libopenmw.so" -exec cp "{}" "$JNIDIR/libopenmw.so" \;
    echo "    Fallback: libopenmw.so (non-VR)"
fi

# Core runtime libs
cp "$BUILDSCRIPTS/prefix/$ARCH/lib/libopenal.so"  "$JNIDIR/"
cp "$BUILDSCRIPTS/prefix/$ARCH/lib/libSDL2.so"    "$JNIDIR/"
cp "$BUILDSCRIPTS/prefix/$ARCH/lib/libhidapi.so"  "$JNIDIR/"
cp "$BUILDSCRIPTS/prefix/$ARCH/lib/libGL.so"       "$JNIDIR/"

# OpenXR loader (if present)
OPENXR_LOADER=$(find "$BUILD_DIR/openmw-prefix/" -iname "libopenxr_loader.so" 2>/dev/null | head -n 1)
if [ -n "$OPENXR_LOADER" ]; then
    cp "$OPENXR_LOADER" "$JNIDIR/"
    echo "    OpenXR loader included"
fi

# libc++_shared
find "$NDK_HOME/$ARCH/sysroot/usr/lib/$NDK_TRIPLET" -iname "libc++_shared.so" -exec cp "{}" "$JNIDIR/" \;

# Strip
"$NDK_HOME/$ARCH/bin/$NDK_TRIPLET-strip" "$JNIDIR/"*.so 2>/dev/null || true
echo "    JNI libs installed to: $JNIDIR"

# Resources
ASSETS_DST="$ANDROID/app/src/main/assets/libopenmw"
OPENMW_BUILD="$BUILD_DIR/openmw-prefix/src/openmw-build"
rm -rf "$ASSETS_DST" && mkdir -p "$ASSETS_DST"
cp -r "$OPENMW_BUILD/resources" "$ASSETS_DST"
mkdir -p "$ASSETS_DST/openmw"
cp "$OPENMW_BUILD/defaults.bin"          "$ASSETS_DST/openmw/"
cp "$OPENMW_BUILD/gamecontrollerdb.txt"  "$ASSETS_DST/openmw/"
cat "$OPENMW_BUILD/openmw.cfg" | grep -v "data=" | grep -v "data-local=" \
    >> "$ASSETS_DST/openmw/openmw.base.cfg"
[ -f "$ANDROID/app/openmw.base.cfg" ] && \
    cat "$ANDROID/app/openmw.base.cfg" >> "$ASSETS_DST/openmw/openmw.base.cfg"

for f in settings-overrides-vr.cfg xrcontrollersuggestions.xml; do
    if [ -f "$OPENMW_BUILD/$f" ]; then
        cp "$OPENMW_BUILD/$f" "$ASSETS_DST/openmw/"
    elif [ -f "$BUILDSCRIPTS/openmw-vr/files/$f" ]; then
        cp "$BUILDSCRIPTS/openmw-vr/files/$f" "$ASSETS_DST/openmw/"
    fi
done
echo "    Resources deployed to: $ASSETS_DST"

# ----------------------------------------------------------------
# 6. Gradle APK
# ----------------------------------------------------------------
echo ""
echo "==> [6/6] Building APK with Gradle..."
cd "$ANDROID"
chmod +x gradlew
./gradlew assembleRelease

APK=$(find "$ANDROID/app/build/outputs/apk" -name "*.apk" 2>/dev/null | head -n 1)
echo ""
echo "========================================"
echo " BUILD COMPLETE"
echo " APK: $APK"
echo " Log: $LOG"
echo "========================================"
echo ""
echo "Install on device:  adb install -r \"$APK\""
echo "Or sideload via SideQuest."
