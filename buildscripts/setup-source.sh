#!/usr/bin/env bash
# setup-source.sh - Populate buildscripts/openmw-vr/ from arawenz-base and apply VR patches
# Run this ONCE from WSL before the first build:
#   cd /mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/buildscripts
#   bash setup-source.sh

set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
ROOT="$(dirname "$DIR")"
ARAWENZ="$ROOT/../source_repos/arawenz-base"
MMRY="$ROOT/../source_repos/mmry-quest/buildscripts"
OPENMW_VR="$DIR/openmw-vr"
PATCHES_DST="$DIR/patches"

echo "==> Checking source repos..."
if [ ! -d "$ARAWENZ/apps/openmw" ]; then
    echo "ERROR: arawenz-base not found at $ARAWENZ"
    exit 1
fi
if [ ! -d "$MMRY/patches" ]; then
    echo "ERROR: mmry-quest patches not found at $MMRY/patches"
    exit 1
fi

echo "==> Copying patches from mmry-quest (no-clobber: local fixes win)..."
mkdir -p "$PATCHES_DST"
cp -rn "$MMRY/patches/." "$PATCHES_DST/"
echo "    Done."

echo "==> Copying arawenz-base -> openmw-vr/ (this may take a minute)..."
mkdir -p "$OPENMW_VR"
rsync -a --exclude='.git' "$ARAWENZ/" "$OPENMW_VR/"
echo "    Done."

echo "==> Overwriting android_main.cpp with Quest VR version (local patches dir)..."
cp "$PATCHES_DST/openmw/android_main.cpp" "$OPENMW_VR/apps/openmw/android_main.cpp"
echo "    Done."

echo "==> Applying VR settings overrides (force shaders etc.)..."
if [ -f "$PATCHES_DST/openmw/settings-overrides-vr.cfg" ]; then
    cp "$PATCHES_DST/openmw/settings-overrides-vr.cfg" "$OPENMW_VR/files/settings-overrides-vr.cfg"
    echo "    Done."
fi

OPENMW_CMAKELISTS="$OPENMW_VR/apps/openmw/CMakeLists.txt"

echo "==> Patching apps/openmw/CMakeLists.txt for Android VR build..."

python3 - "$OPENMW_CMAKELISTS" <<'PYEOF'
import sys

cmake_path = sys.argv[1]
with open(cmake_path, 'r') as f:
    content = f.read()

# 1. Replace openmw_add_executable(tes3mp ...) with Android-aware add_library
old_exe = (
    '    openmw_add_executable(tes3mp\n'
    '        ${OPENMW_FILES}\n'
    '        ${OPENMW_VR_FILES}\n'
    '        ${GAME} ${GAME_HEADER}\n'
    '        ${APPLE_BUNDLE_RESOURCES}\n'
    '    )'
)
new_exe = (
    '    if (NOT ANDROID)\n'
    '        openmw_add_executable(tes3mp\n'
    '            ${OPENMW_FILES}\n'
    '            ${OPENMW_VR_FILES}\n'
    '            ${GAME} ${GAME_HEADER}\n'
    '            ${APPLE_BUNDLE_RESOURCES}\n'
    '        )\n'
    '    else()\n'
    '        add_library(tes3mp\n'
    '            SHARED\n'
    '            ${OPENMW_FILES}\n'
    '            ${OPENMW_VR_FILES}\n'
    '            ${GAME} ${GAME_HEADER}\n'
    '        )\n'
    '    endif()'
)
if old_exe in content:
    content = content.replace(old_exe, new_exe)
    print("  [OK] Android add_library block inserted")
else:
    print("  [WARN] openmw_add_executable(tes3mp ...) pattern not found; check indentation")

# 2. Update OpenXR GIT_TAG from release-1.0.15 to release-1.0.34 and add PATCH_COMMAND
if 'GIT_TAG release-1.0.15' in content:
    content = content.replace(
        '        GIT_TAG release-1.0.15\n    )',
        '        GIT_TAG release-1.0.34\n'
        '        PATCH_COMMAND ${CMAKE_SOURCE_DIR}/../patches/patch_openxr.sh\n'
        '    )'
    )
    print("  [OK] OpenXR updated to release-1.0.34 with Android patch command")
elif 'GIT_TAG release-1.0.34' in content:
    print("  [OK] OpenXR tag already at release-1.0.34")
else:
    print("  [WARN] GIT_TAG release-1.0.15 not found; OpenXR tag not changed")

# 3. Add Android case to compile options
old_unix = (
    '    elseif(UNIX)\n'
    '        target_compile_options(tes3mp PUBLIC -DUSE_OPENXR -DXR_USE_GRAPHICS_API_OPENGL -DXR_USE_PLATFORM_XLIB)\n'
    '        find_package(X11 REQUIRED)\n'
    '        target_link_libraries(tes3mp ${X11_LIBRARIES})\n'
    '    endif()'
)
new_unix = (
    '    elseif(ANDROID)\n'
    '        target_compile_options(tes3mp PUBLIC -DUSE_OPENXR -DXR_USE_GRAPHICS_API_OPENGL_ES -DXR_USE_PLATFORM_ANDROID)\n'
    '    elseif(UNIX)\n'
    '        target_compile_options(tes3mp PUBLIC -DUSE_OPENXR -DXR_USE_GRAPHICS_API_OPENGL -DXR_USE_PLATFORM_XLIB)\n'
    '        find_package(X11 REQUIRED)\n'
    '        target_link_libraries(tes3mp ${X11_LIBRARIES})\n'
    '    endif()'
)
if old_unix in content:
    content = content.replace(old_unix, new_unix)
    print("  [OK] Android OpenXR compile options added")
else:
    print("  [WARN] UNIX compile_options pattern not found; Android VR flags not added")

# 4. Add EGL/android/log/z link for Android (after OSG_STATIC block, before UNIX thread fix)
android_link = '\nif (ANDROID)\n    target_link_libraries(tes3mp EGL android log z)\nendif (ANDROID)\n'
marker = '\n# Fix for not visible pthreads functions'
if marker in content and android_link.strip() not in content:
    content = content.replace(marker, android_link + marker)
    print("  [OK] Android EGL/android/log/z link added")

with open(cmake_path, 'w') as f:
    f.write(content)
print("  CMakeLists patched successfully.")
PYEOF

echo "==> Verifying openmw-vr/CMakeLists.txt exists..."
if [ ! -f "$OPENMW_VR/CMakeLists.txt" ]; then
    echo "ERROR: openmw-vr/CMakeLists.txt missing after copy!"
    exit 1
fi

# TES3MP 0.8.1 protocol upgrade (upstream diff origin/0.8.0-vr..tes3mp-0.8.1-vr,
# client-relevant files). Applied via file check because the tree is not a git
# repo and its files may carry CRLF endings.
echo "==> Applying TES3MP 0.8.1 protocol upgrade..."
if grep -q 'TES3MP_PROTO_VERSION 10' "$OPENMW_VR/components/openmw-mp/Version.hpp"; then
    echo "  already at protocol 10"
else
    (cd "$OPENMW_VR" && patch -p1 --binary -N < "$DIR/quest-patches/tes3mp-0.8.1.patch") || {
        echo "ERROR: 0.8.1 patch failed to apply"; exit 1; }
    grep -q 'TES3MP_PROTO_VERSION 10' "$OPENMW_VR/components/openmw-mp/Version.hpp" || {
        echo "ERROR: protocol still not 10 after patch"; exit 1; }
    echo "  upgraded to TES3MP 0.8.1 / protocol 10"
fi

# Quest-specific patches live in the repo (buildscripts/quest-patches); each is idempotent
echo "==> Applying Quest patches..."
for p in patch_openxr_android_init patch_quest_swapchain_blit patch_vr_chat_buttons \
         patch_vr_chat_font patch_vr_chat_actions patch_vr_refresh_rate patch_fatal_log_order \
         patch_vr_vkb_and_autoname patch_vr_chat_ui_v2 \
         patch_vr_performance; do
    echo "  -> $p"
    python3 "$DIR/quest-patches/$p.py"
done

echo ""
echo "==> Setup complete!"
echo ""
echo "Next: run the Android build:"
echo "  cd $DIR"
echo "  ./build.sh --arch arm64"
