#!/bin/bash
# resume_vr_build.sh - Properly resume the VR APK build after boost fix
set -e
LOG=/tmp/tes3mp-vr-resume-$(date +%Y%m%d-%H%M%S).log
exec > >(tee "$LOG") 2>&1

BUILD_DIR="$HOME/tes3mp-build/arm64"
BUILDSCRIPTS="/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/buildscripts"
ANDROID="/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/android"
WORKSPACE="/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3"
NDK_TRIPLET="aarch64-linux-android"
ABI="arm64-v8a"
NDK_HOME="$HOME/tes3mp-vr-ndk"

echo "=============================="
echo " TES3MP VR Build Resume"
echo " Log: $LOG"
echo "=============================="

# ---- Cleanup: remove CMakeCache/files accidentally placed at workspace root ----
echo ""
echo "==> Cleaning up any misplaced cmake files at workspace root..."
rm -f  "$WORKSPACE/CMakeCache.txt" "$WORKSPACE/Makefile"
rm -rf "$WORKSPACE/CMakeFiles"
rm -rf "$WORKSPACE/libjpeg-turbo-prefix" "$WORKSPACE/libpng-prefix"
rm -rf "$WORKSPACE/freetype2-prefix" "$WORKSPACE/openal-prefix" "$WORKSPACE/boost-prefix"
echo "    Workspace root cleaned."

# ---- Write user-config.jam directly into boost source (bypass cmake quoting issues) ----
echo ""
echo "==> Writing user-config.jam for clang-android toolset..."
BOOST_SRC="$BUILD_DIR/boost-prefix/src/boost"
if [ ! -d "$BOOST_SRC" ]; then
    echo "    ERROR: boost source dir not found: $BOOST_SRC"
    exit 1
fi
cat > "$BOOST_SRC/user-config.jam" << 'JAMEOF'
using clang-android : : aarch64-linux-android-clang++ : <archiver>aarch64-linux-android-ar <ranlib>aarch64-linux-android-ranlib ;
JAMEOF
echo "    OK: $BOOST_SRC/user-config.jam"
cat "$BOOST_SRC/user-config.jam"

# ---- Clear ONLY boost-install/boost-done (keep boost-configure to skip re-bootstrap) ----
echo ""
echo "==> Clearing boost-install and boost-done stamps..."
BOOST_STAMP="$BUILD_DIR/boost-prefix/src/boost-stamp"
rm -f "$BOOST_STAMP/boost-install" "$BOOST_STAMP/boost-done"
echo "    boost-install and boost-done removed (boost-configure kept)"

# ---- Resume make from the CORRECT build directory (no cmake reconfigure needed) ----
echo ""
echo "==> Resuming build from: $BUILD_DIR..."
source "$BUILD_DIR/command_wrapper.sh" true
cd "$BUILD_DIR"
NCPU=$(nproc)
make -j"$NCPU"

# ---- Install shared libraries ----
echo ""
echo "==> Installing shared libraries..."
JNIDIR="$ANDROID/app/src/main/jniLibs/$ABI"
rm -rf "$JNIDIR" && mkdir -p "$JNIDIR"

OPENMW_VR_SO=$(find "$BUILD_DIR/openmw-prefix/" \( -iname "libopenmw_vr.so" -o -iname "libtes3mp.so" \) 2>/dev/null | head -n 1)
if [ -n "$OPENMW_VR_SO" ]; then
    cp "$OPENMW_VR_SO" "$JNIDIR/libopenmw.so"
    echo "    VR lib -> libopenmw.so"
else
    find "$BUILD_DIR/openmw-prefix/" -iname "libopenmw.so" -exec cp "{}" "$JNIDIR/libopenmw.so" \;
fi

cp "$BUILDSCRIPTS/prefix/arm64/lib/libopenal.so"  "$JNIDIR/"
cp "$BUILDSCRIPTS/prefix/arm64/lib/libSDL2.so"    "$JNIDIR/"
cp "$BUILDSCRIPTS/prefix/arm64/lib/libhidapi.so"  "$JNIDIR/"
cp "$BUILDSCRIPTS/prefix/arm64/lib/libGL.so"       "$JNIDIR/"

OPENXR_LOADER=$(find "$BUILD_DIR/openmw-prefix/" -iname "libopenxr_loader.so" 2>/dev/null | head -n 1)
[ -n "$OPENXR_LOADER" ] && cp "$OPENXR_LOADER" "$JNIDIR/"

find "$NDK_HOME/arm64/sysroot/usr/lib/$NDK_TRIPLET" -iname "libc++_shared.so" \
    -exec cp "{}" "$JNIDIR/" \;

"$NDK_HOME/arm64/bin/$NDK_TRIPLET-strip" "$JNIDIR/"*.so 2>/dev/null || true
echo "    JNI libs installed: $JNIDIR"

# ---- Deploy resources ----
echo ""
echo "==> Deploying resources..."
ASSETS_DST="$ANDROID/app/src/main/assets/libopenmw"
OPENMW_BUILD="$BUILD_DIR/openmw-prefix/src/openmw-build"
rm -rf "$ASSETS_DST" && mkdir -p "$ASSETS_DST"
cp -r "$OPENMW_BUILD/resources" "$ASSETS_DST"
mkdir -p "$ASSETS_DST/openmw"
cp "$OPENMW_BUILD/defaults.bin"         "$ASSETS_DST/openmw/"
cp "$OPENMW_BUILD/gamecontrollerdb.txt" "$ASSETS_DST/openmw/"
cat "$OPENMW_BUILD/openmw.cfg" | grep -v "data=" | grep -v "data-local=" \
    >> "$ASSETS_DST/openmw/openmw.base.cfg"
[ -f "$ANDROID/app/openmw.base.cfg" ] && \
    cat "$ANDROID/app/openmw.base.cfg" >> "$ASSETS_DST/openmw/openmw.base.cfg"
for f in settings-overrides-vr.cfg xrcontrollersuggestions.xml; do
    [ -f "$OPENMW_BUILD/$f" ] && cp "$OPENMW_BUILD/$f" "$ASSETS_DST/openmw/"
    [ -f "$BUILDSCRIPTS/openmw-vr/files/$f" ] && cp "$BUILDSCRIPTS/openmw-vr/files/$f" "$ASSETS_DST/openmw/"
done

# ---- Gradle APK ----
echo ""
echo "==> Building APK with Gradle..."
cd "$ANDROID"
chmod +x gradlew
./gradlew assembleRelease

APK=$(find "$ANDROID/app/build/outputs/apk" -name "*.apk" 2>/dev/null | head -n 1)
echo ""
echo "=============================="
echo " BUILD COMPLETE"
echo " APK: $APK"
echo " Log: $LOG"
echo "=============================="
echo ""
echo "Install: adb install -r \"$APK\""
