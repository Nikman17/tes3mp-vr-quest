#!/bin/bash
set -e

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
BUILD_DIR=/tmp/tes3mp-build/$ARCH

echo "==> Creating native build dir..."
mkdir -p $BUILD_DIR
mkdir -p $DIR/prefix/$ARCH/lib
ln -sf lib $DIR/prefix/$ARCH/lib64 2>/dev/null || true

echo "==> Generating command_wrapper.sh..."
cat $DIR/include/command_wrapper_head.sh.in | \
    DIR=$DIR \
    ARCH=$ARCH \
    ENV_CFLAGS="$CFLAGS" \
    ENV_CXXFLAGS="$CXXFLAGS" \
    NDK_TRIPLET=$NDK_TRIPLET \
    ENV_LDFLAGS="" \
        envsubst > $BUILD_DIR/command_wrapper.sh
cat $DIR/include/command_wrapper_tail.sh.in >> $BUILD_DIR/command_wrapper.sh
chmod +x $BUILD_DIR/command_wrapper.sh
source $BUILD_DIR/command_wrapper.sh true

echo "==> Running cmake configure..."
cd $BUILD_DIR
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

echo "==> Faking stamps for already-installed deps (all step names)..."
# cmake 3.x ExternalProject step stamps
for dep in libjpeg-turbo libpng freetype2 openal boost ffmpeg sdl2 bullet gl4es mygui lz4 osg; do
    STAMP_DIR=$BUILD_DIR/${dep}-prefix/src/${dep}-stamp
    mkdir -p "$STAMP_DIR"
    # Use current time (must be newer than cmake-generated scripts)
    touch "$STAMP_DIR/${dep}-mkdir"
    touch "$STAMP_DIR/${dep}-download"
    touch "$STAMP_DIR/${dep}-verify"
    touch "$STAMP_DIR/${dep}-extract"
    touch "$STAMP_DIR/${dep}-update"
    touch "$STAMP_DIR/${dep}-patch"
    touch "$STAMP_DIR/${dep}-configure"
    touch "$STAMP_DIR/${dep}-build"
    touch "$STAMP_DIR/${dep}-install"
    touch "$STAMP_DIR/${dep}-done"
    echo "  Stamped: $dep"
done

# Also create fake cmake cache files for cmake-based deps to prevent re-configure
for dep in openal bullet mygui lz4 osg; do
    BUILD_SUBDIR=$BUILD_DIR/${dep}-prefix/src/${dep}-build
    mkdir -p "$BUILD_SUBDIR"
    echo "# fake cache" > "$BUILD_SUBDIR/CMakeCache.txt"
done

echo "==> Building only crabnet and openmw..."
make -j2 crabnet
echo "==> CrabNet done, building openmw..."
make -j2 openmw
