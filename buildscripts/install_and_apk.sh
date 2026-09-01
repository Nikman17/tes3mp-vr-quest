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

STRIP="$NDK_HOME/arm64/bin/$NDK_TRIPLET-strip"

# Main openmw/tes3mp shared lib.
# strip -o: the unstripped engines are 450-530 MB; copying them through the
# /mnt/c 9p mount OOMs the RAM-capped WSL VM, so strip directly to destination.
OPENMW_SO=$(find "$BUILD_DIR/openmw-prefix/src/openmw-build" -name "libtes3mp.so" 2>/dev/null | head -n 1)
if [ -n "$OPENMW_SO" ]; then
    "$STRIP" -o "$JNIDIR/libopenmw.so" "$OPENMW_SO"
    echo "    libtes3mp.so -> libopenmw.so (stripped)"
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

# Vanilla singleplayer engine (built via build-sp-engine.sh -> openmw-sp-build).
# IMPORTANT: the vanilla tree has two game targets; only openmw_vr is a VR build.
SP_BUILD="$BUILD_DIR/openmw-sp-build"
SP_SO=$(find "$SP_BUILD" -maxdepth 2 -name 'libopenmw_vr.so' 2>/dev/null | head -n 1)
if [ -n "$SP_SO" ]; then
    "$STRIP" -o "$JNIDIR/libopenmw-sp.so" "$SP_SO"
    echo "    $(basename "$SP_SO") -> libopenmw-sp.so (stripped)"
else
    echo "    WARNING: vanilla SP engine (libopenmw_vr.so) not found — singleplayer will refuse to launch"
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
# and clients get kicked with "Version mismatch!". Pin the official 0.8.1 hashes here
# (commit = tag tes3mp-0.8.1; the engine sources carry the matching 0.8.1/proto-10
# defines via quest-patches/tes3mp-0.8.1.patch).
printf '0.47.0\n68954091c54d0596037c4fb54d2812313b7582a1\n68954091c54d0596037c4fb54d2812313b7582a1\n' \
    > "$ASSETS_DST/resources/version"
echo "    resources/version pinned to tes3mp-0.8.1 (68954091c5)"
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

# ---- Deploy SP (vanilla openmw-vr) resources ----
# The SP engine is a different OpenMW generation: its shaders/mygui/defaults are
# NOT interchangeable with the TES3MP 0.47 ones, so it gets its own asset space
# (assets/libopenmw-sp), extracted by the app when Singleplayer mode is active.
SP_ASSETS="$ANDROID/app/src/main/assets/libopenmw-sp"
if [ -n "$SP_SO" ] && [ -d "$SP_BUILD/resources" ]; then
    echo ""
    echo "==> Deploying SP engine resources..."
    rm -rf "$SP_ASSETS" && mkdir -p "$SP_ASSETS/openmw"
    cp -r "$SP_BUILD/resources" "$SP_ASSETS/"
    cp "$SP_BUILD/defaults.bin"         "$SP_ASSETS/openmw/" 2>/dev/null || true
    cp "$SP_BUILD/gamecontrollerdb.txt" "$SP_ASSETS/openmw/" 2>/dev/null || true
    if [ -f "$SP_BUILD/openmw.cfg" ]; then
        grep -v "^data=" "$SP_BUILD/openmw.cfg" | grep -v "^data-local=" \
            > "$SP_ASSETS/openmw/openmw.base.cfg"
    fi
    [ -f "$ANDROID/app/openmw.base.cfg" ] && \
        cat "$ANDROID/app/openmw.base.cfg" >> "$SP_ASSETS/openmw/openmw.base.cfg"
    SP_TREE="$BUILDSCRIPTS/openmw-vr-sp"
    for f in settings-overrides-vr.cfg xrcontrollersuggestions.xml; do
        [ -f "$SP_BUILD/$f" ] && cp "$SP_BUILD/$f" "$SP_ASSETS/openmw/"
        [ -f "$SP_TREE/files/$f" ] && cp "$SP_TREE/files/$f" "$SP_ASSETS/openmw/"
    done
    echo "    SP resources deployed to: $SP_ASSETS"
else
    rm -rf "$SP_ASSETS"
    echo "    (SP engine absent — skipping SP resources)"
fi

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
