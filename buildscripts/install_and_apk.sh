#!/bin/bash
set -e
LOG=/tmp/install_apk_$(date +%Y%m%d-%H%M%S).log
exec > >(tee "$LOG") 2>&1

BUILD_DIR="$HOME/tes3mp-build/arm64"
BUILDSCRIPTS="/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/buildscripts"
ANDROID="/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/android"
NDK_TRIPLET="aarch64-linux-android"
ABI="arm64-v8a"
NDK_HOME="$HOME/tes3mp-vr-ndk"
OPENMW_BUILD="$BUILD_DIR/openmw-prefix/src/openmw-build"

echo "=============================="
echo " Install libs + Build APK"
echo " Log: $LOG"
echo "=============================="

# ---- Install shared libraries ----
echo ""
echo "==> Installing shared libraries..."
JNIDIR="$ANDROID/app/src/main/jniLibs/$ABI"
rm -rf "$JNIDIR" && mkdir -p "$JNIDIR"

# Main openmw/tes3mp shared lib
OPENMW_SO=$(find "$BUILD_DIR/openmw-prefix/src/openmw-build" -name "libtes3mp.so" 2>/dev/null | head -n 1)
if [ -n "$OPENMW_SO" ]; then
    cp "$OPENMW_SO" "$JNIDIR/libopenmw.so"
    echo "    libtes3mp.so -> libopenmw.so"
else
    echo "ERROR: libtes3mp.so not found!"
    find "$BUILD_DIR/openmw-prefix/" -name "*.so" 2>/dev/null | head -10
    exit 1
fi

# OpenXR loader
OPENXR_LOADER=$(find "$OPENMW_BUILD" -name "libopenxr_loader.so" 2>/dev/null | head -n 1)
if [ -n "$OPENXR_LOADER" ]; then
    cp "$OPENXR_LOADER" "$JNIDIR/"
    echo "    libopenxr_loader.so"
fi

# Core deps (libGL.so = gl4es, required by GameActivity.loadLibraries)
for lib in libGL.so libopenal.so libSDL2.so libhidapi.so; do
    if [ -f "$BUILDSCRIPTS/prefix/arm64/lib/$lib" ]; then
        cp "$BUILDSCRIPTS/prefix/arm64/lib/$lib" "$JNIDIR/"
        echo "    $lib"
    fi
done

# libc++_shared
LIBCPP=$(find "$NDK_HOME/arm64/sysroot/usr/lib/$NDK_TRIPLET" -name "libc++_shared.so" 2>/dev/null | head -n 1)
if [ -n "$LIBCPP" ]; then
    cp "$LIBCPP" "$JNIDIR/"
    echo "    libc++_shared.so"
fi

# Strip
"$NDK_HOME/arm64/bin/$NDK_TRIPLET-strip" "$JNIDIR/"*.so 2>/dev/null || true
echo "    JNI libs installed: $JNIDIR"
ls -la "$JNIDIR/"

# ---- Deploy resources ----
echo ""
echo "==> Deploying resources..."
ASSETS_DST="$ANDROID/app/src/main/assets/libopenmw"
rm -rf "$ASSETS_DST" && mkdir -p "$ASSETS_DST"
cp -r "$OPENMW_BUILD/resources" "$ASSETS_DST"

# TES3MP wire-compat: RakNet connection password = TES3MP_VERSION + PROTO + commit hash
# taken from resources/version. Source tree has no .git, so cmake leaves the hash empty
# and clients get kicked with "Version mismatch!". Pin the official 0.8.0 hashes here.
printf '0.47.0\n000e8724cacaf0176f6220de111ca45098807e78\ne29e3248fcf57d6cfac6efd049955c133f2d9896\n' \
    > "$ASSETS_DST/resources/version"
echo "    resources/version pinned to tes3mp-0.8.0 (000e8724ca)"
mkdir -p "$ASSETS_DST/openmw"
cp "$OPENMW_BUILD/defaults.bin"          "$ASSETS_DST/openmw/" 2>/dev/null || true
cp "$OPENMW_BUILD/gamecontrollerdb.txt"  "$ASSETS_DST/openmw/" 2>/dev/null || true

if [ -f "$OPENMW_BUILD/openmw.cfg" ]; then
    grep -v "^data=" "$OPENMW_BUILD/openmw.cfg" | grep -v "^data-local=" \
        >> "$ASSETS_DST/openmw/openmw.base.cfg"
fi
[ -f "$ANDROID/app/openmw.base.cfg" ] && \
    cat "$ANDROID/app/openmw.base.cfg" >> "$ASSETS_DST/openmw/openmw.base.cfg"

for f in settings-overrides-vr.cfg xrcontrollersuggestions.xml; do
    [ -f "$OPENMW_BUILD/$f" ] && cp "$OPENMW_BUILD/$f" "$ASSETS_DST/openmw/"
    [ -f "$BUILDSCRIPTS/openmw-vr/files/$f" ] && cp "$BUILDSCRIPTS/openmw-vr/files/$f" "$ASSETS_DST/openmw/"
done
echo "    Resources deployed to: $ASSETS_DST"

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
