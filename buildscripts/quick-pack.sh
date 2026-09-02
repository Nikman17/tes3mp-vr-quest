#!/bin/bash
# QUICK repack: engines + RESOURCE SYNC + gradle. NOTE: engine binaries and
# their resources (mygui layouts, shaders) must never go out of sync — a
# layout the engine expects but the APK lacks = MyGUI exception = SIGSEGV
# in the WindowManager teardown. This script now syncs resources too; for
# anything bigger use install_and_apk.sh.
set -e
R=/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3
STRIP=$R/tes3mp-vr/buildscripts/toolchain/arm64/bin/aarch64-linux-android-strip
"$STRIP" -o $R/tes3mp-vr/android/app/src/main/jniLibs/arm64-v8a/libopenmw.so ~/tes3mp-build/arm64/openmw-prefix/src/openmw-build/libtes3mp.so
echo "MP engine stripped -> jniLibs"
if [ -f ~/tes3mp-build/arm64/openmw-sp-build/libopenmw_vr.so ]; then
    "$STRIP" -o $R/tes3mp-vr/android/app/src/main/jniLibs/arm64-v8a/libopenmw-sp.so ~/tes3mp-build/arm64/openmw-sp-build/libopenmw_vr.so
    echo "SP engine stripped -> jniLibs"
fi
rsync -a --delete ~/tes3mp-build/arm64/openmw-prefix/src/openmw-build/resources/ \
    $R/tes3mp-vr/android/app/src/main/assets/libopenmw/resources/
printf '0.47.0\n68954091c54d0596037c4fb54d2812313b7582a1\n68954091c54d0596037c4fb54d2812313b7582a1\n' \
    > $R/tes3mp-vr/android/app/src/main/assets/libopenmw/resources/version
rsync -a --delete ~/tes3mp-build/arm64/openmw-sp-build/resources/ \
    $R/tes3mp-vr/android/app/src/main/assets/libopenmw-sp/resources/ 2>/dev/null || true
echo "resources synced (version re-pinned to 0.8.1)"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME=/mnt/c/Android/sdk
cd $R/tes3mp-vr/android
./gradlew assembleMainlineRelease 2>&1 | grep -E 'BUILD' | tail -1
