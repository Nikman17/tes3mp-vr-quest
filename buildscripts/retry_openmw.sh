#!/bin/bash
set -e

BUILD_DIR=/tmp/tes3mp-build/arm64
DIR=/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/buildscripts
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

echo "==> Clearing openmw configure stamp to force re-configure with RakNet_LIBRARY_DEBUG fix..."
rm -f "$BUILD_DIR/openmw-prefix/src/openmw-stamp/openmw-configure"

echo "==> Re-running cmake configure to pick up updated CMakeLists.txt..."
cd "$BUILD_DIR"

# Re-generate command_wrapper if missing
if [ ! -f "$BUILD_DIR/command_wrapper.sh" ]; then
    cat $DIR/include/command_wrapper_head.sh.in | \
        DIR=$DIR ARCH=$ARCH ENV_CFLAGS="$CFLAGS" ENV_CXXFLAGS="$CXXFLAGS" \
        NDK_TRIPLET=$NDK_TRIPLET ENV_LDFLAGS="" envsubst > $BUILD_DIR/command_wrapper.sh
    cat $DIR/include/command_wrapper_tail.sh.in >> $BUILD_DIR/command_wrapper.sh
    chmod +x $BUILD_DIR/command_wrapper.sh
fi

cmake "$DIR" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_INSTALL_PREFIX=$DIR/prefix/$ARCH/ \
    -DARCH=$ARCH \
    -DBUILD_TYPE=$BUILD_TYPE \
    -DNDK_TRIPLET=$NDK_TRIPLET \
    -DANDROID_API=$ANDROID_API \
    -DABI=$ABI \
    -DBOOST_ARCH=$BOOST_ARCH \
    -DBOOST_ADDRESS_MODEL=$BOOST_ADDRESS_MODEL \
    -DFFMPEG_CPU=$FFMPEG_CPU \
    -DALLOW_OPENMW_UPSTREAM_FALLBACK=OFF

echo "==> Faking stamps for all already-done deps..."
for dep in libjpeg-turbo libpng freetype2 openal boost ffmpeg sdl2 bullet gl4es mygui lz4 osg crabnet; do
    STAMP_DIR=$BUILD_DIR/${dep}-prefix/src/${dep}-stamp
    mkdir -p "$STAMP_DIR"
    for step in mkdir download verify extract update patch configure build install done; do
        touch "$STAMP_DIR/${dep}-${step}"
    done
done

echo "==> Building openmw only..."
make -j2 openmw 2>&1
