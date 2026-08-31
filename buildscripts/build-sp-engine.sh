#!/bin/bash
# Vanilla openmw-vr (no TES3MP) engine for TRUE singleplayer on Quest.
set -e
exec > >(tee -a /tmp/agent.log) 2>&1
echo "=== [SP engine: vanilla openmw-vr build] ==="

R=/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3
SRC=$R/source_repos/mmry-quest/buildscripts/openmw-vr
SP=$R/tes3mp-vr/buildscripts/openmw-vr-sp
QP=$R/tes3mp-vr/buildscripts/quest-patches
BUILD=~/tes3mp-build/arm64/openmw-sp-build
WRAP=~/tes3mp-build/arm64/command_wrapper.sh

echo "--- 1. source tree ---"
if [ ! -f "$SP/CMakeLists.txt" ]; then
    mkdir -p "$SP"
    rsync -a --exclude='.git' "$SRC/" "$SP/"
    echo "copied $(du -sh $SP | cut -f1)"
else
    echo "already present"
fi

# NOTE: the mmry tree is built PRISTINE — it already carries its own complete
# Quest support (JNI initOpenXRLoader/setOpenXrRuntimeJson in android_main,
# Touch controller mappings, GLES swapchain path). Do NOT overlay files from
# the TES3MP tree: its mwvr API generation differs (VRStageToWorldBinding vs
# VRTrackingToWorldBinding) and does not compile against this tree.

echo "--- 2. quest patch: request OpenXR 1.0 (Quest runtime rejects 1.1) ---"
XRP=$SP/apps/openmw/mwvr/openxrplatform.cpp
if grep -q 'apiVersion = XR_CURRENT_API_VERSION' "$XRP"; then
    sed -i 's/apiVersion = XR_CURRENT_API_VERSION/apiVersion = XR_API_VERSION_1_0/' "$XRP"
    echo "  patched: apiVersion -> XR_API_VERSION_1_0"
else
    grep -q 'XR_API_VERSION_1_0' "$XRP" && echo "  already patched"
fi

echo "--- 4. configure ---"
mkdir -p "$BUILD"
cd "$BUILD"
"$WRAP" cmake "$SP" \
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
    -DCMAKE_TOOLCHAIN_FILE=$R/tes3mp-vr/buildscripts/toolchain/ndk/build/cmake/android.toolchain.cmake \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-21 -DANDROID_STL=c++_shared \
    "-DANDROID_CPP_FEATURES=rtti exceptions" \
    "-DCMAKE_C_FLAGS=-DMYGUI_DONT_REPLACE_NULLPTR -I$R/tes3mp-vr/buildscripts/prefix/arm64/include/ -fPIC -O3" \
    "-DCMAKE_CXX_FLAGS=-DMYGUI_DONT_REPLACE_NULLPTR -I$R/tes3mp-vr/buildscripts/prefix/arm64/include/ -fPIC -frtti -fexceptions -O3" \
    -DCMAKE_BUILD_TYPE=release -DCMAKE_DEBUG_POSTFIX= \
    -DCMAKE_INSTALL_PREFIX=$R/tes3mp-vr/buildscripts/prefix/arm64 \
    -DCMAKE_FIND_ROOT_PATH=$R/tes3mp-vr/buildscripts/prefix/arm64 \
    -DBUILD_BSATOOL=0 -DBUILD_NIFTEST=0 -DBUILD_ESMTOOL=0 -DBUILD_LAUNCHER=0 \
    -DBUILD_MWINIIMPORTER=0 -DBUILD_ESSIMPORTER=0 -DBUILD_OPENCS=0 -DBUILD_WIZARD=0 \
    -DBUILD_MYGUI_PLUGIN=0 -DBUILD_BROWSER=0 -DBUILD_OPENMW_MP=0 \
    -DOPENAL_INCLUDE_DIR=$R/tes3mp-vr/buildscripts/prefix/arm64/include/AL/ \
    -DBullet_INCLUDE_DIR=$R/tes3mp-vr/buildscripts/prefix/arm64/include/bullet/ \
    -DOPENGL_ES=OFF -DOSG_STATIC=TRUE \
    -DMyGUI_LIBRARY=$R/tes3mp-vr/buildscripts/prefix/arm64/lib/libMyGUIEngineStatic.a \
    -DBUILD_OPENMW=ON 2>&1 | tail -4

echo "--- 5. build ---"
# NOTE: this tree has TWO game targets: 'openmw' (flatscreen!) and 'openmw_vr'
# (compiled with USE_OPENXR + GLES/Android platform switches). We want VR.
"$WRAP" make -j3 openmw_vr 2>&1 | tail -6

echo "--- result ---"
find "$BUILD" -maxdepth 2 -name 'libopenmw_vr.so' -exec ls -la {} \;
echo "SP ENGINE DONE"
