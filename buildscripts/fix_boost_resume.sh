#!/bin/bash
# fix_boost_resume.sh - Fix boost user-config.jam issue and resume build
set -e

BUILD_DIR="$HOME/tes3mp-build/arm64"
BUILDSCRIPTS="/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/buildscripts"
NDK_TRIPLET="aarch64-linux-android"

echo "==> Step 1: Write user-config.jam directly into boost source..."
BOOST_SRC="$BUILD_DIR/boost-prefix/src/boost"
if [ -d "$BOOST_SRC" ]; then
    printf 'using clang-android : : %s-clang++ : <archiver>%s-ar <ranlib>%s-ranlib ;\n' \
        "$NDK_TRIPLET" "$NDK_TRIPLET" "$NDK_TRIPLET" > "$BOOST_SRC/user-config.jam"
    echo "    Written: $BOOST_SRC/user-config.jam"
    cat "$BOOST_SRC/user-config.jam"
else
    echo "ERROR: boost source not found at $BOOST_SRC"
    exit 1
fi

echo ""
echo "==> Step 2: Clear boost-configure, boost-build, boost-install, boost-done stamps..."
for stamp in boost-configure boost-build boost-install boost-done; do
    rm -f "$BUILD_DIR/boost-prefix/src/boost-stamp/$stamp"
    echo "    Removed: $stamp"
done

echo ""
echo "==> Step 3: Re-run cmake to regenerate build files..."
source "$BUILD_DIR/command_wrapper.sh" true
cmake "$BUILDSCRIPTS" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_INSTALL_PREFIX="$BUILDSCRIPTS/prefix/arm64/" \
    -DARCH=arm64 \
    -DBUILD_TYPE=release \
    -DNDK_TRIPLET=aarch64-linux-android \
    -DANDROID_API=21 \
    -DABI=arm64-v8a \
    -DBOOST_ARCH=arm \
    -DBOOST_ADDRESS_MODEL=64 \
    -DFFMPEG_CPU=armv8-a \
    -DALLOW_OPENMW_UPSTREAM_FALLBACK=OFF

echo ""
echo "==> Step 4: Resume build (boost + remaining deps)..."
make -j2 2>&1 | tee /tmp/tes3mp-vr-resume.log

echo ""
echo "==> BUILD COMPLETE - continuing to lib install..."
