#!/bin/bash
set -e
cd android
./gradlew assembleRelease
APK="app/build/outputs/apk/release/app-release-unsigned.apk"
echo "APK: android/$APK"
echo ""
echo "Встановити на Quest 2:"
echo "  adb install -r $APK"
echo "Або через SideQuest."
