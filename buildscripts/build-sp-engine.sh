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

echo "--- 2. our android_main + VR overrides ---"
cp "$R/tes3mp-vr/buildscripts/patches/openmw/android_main.cpp" "$SP/apps/openmw/android_main.cpp"
cp "$R/tes3mp-vr/buildscripts/patches/openmw/settings-overrides-vr.cfg" "$SP/files/settings-overrides-vr.cfg" 2>/dev/null || \
cp "$R/tes3mp-vr/buildscripts/openmw-vr/files/settings-overrides-vr.cfg" "$SP/files/settings-overrides-vr.cfg"

echo "--- 3. quest-patched mwvr files from the TES3MP tree (same madsbuvi lineage, mwmp-free) ---"
MWVR_SRC=$R/tes3mp-vr/buildscripts/openmw-vr/apps/openmw/mwvr
MWVR_DST=$SP/apps/openmw/mwvr
for f in openxrplatform.cpp openxrswapchainimage.cpp vrframebuffer.cpp vrframebuffer.hpp vrviewer.cpp openxrmanagerimpl.cpp openxrmanagerimpl.hpp; do
    cp "$MWVR_SRC/$f" "$MWVR_DST/$f"
    echo "  $f"
done

echo "--- 3b. g_viewer hook for android_main (surface lifecycle) ---"
ENG=$SP/apps/openmw/engine.cpp
if ! grep -q 'g_viewer' "$ENG"; then
    python3 - "$ENG" << 'PYEOF'
import sys
p = sys.argv[1]
src = open(p, encoding="utf-8").read()
a1 = "#include <osgViewer/ViewerEventHandlers>\n"
assert src.count(a1) == 1
src = src.replace(a1, a1 + "#include <osgViewer/Viewer>\n\n#ifdef ANDROID\nosg::ref_ptr<osgViewer::Viewer> g_viewer;\n#endif\n")
a2 = "    mViewer = new osgViewer::Viewer;\n"
assert src.count(a2) == 1
src = src.replace(a2, a2 + "#ifdef ANDROID\n    g_viewer = mViewer;\n#endif\n")
open(p, "w", encoding="utf-8", newline="\n").write(src)
print("engine.cpp: g_viewer added")
PYEOF
else
    echo "  already present"
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
"$WRAP" make -j3 openmw 2>&1 | tail -6 || "$WRAP" make -j3 tes3mp 2>&1 | tail -6

echo "--- result ---"
find "$BUILD" -maxdepth 2 -name 'lib*.so' -newer "$BUILD/CMakeCache.txt" 2>/dev/null | head -3
find "$BUILD" -maxdepth 2 \( -name 'libopenmw.so' -o -name 'libtes3mp.so' \) -exec ls -la {} \;
echo "SP ENGINE DONE"
