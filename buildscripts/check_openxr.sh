#!/bin/bash
FILE="$HOME/tes3mp-build/arm64/openmw-prefix/src/openmw-build/_deps/openxr-src/include/openxr/openxr_platform.h"
echo "=== EGL/Android lines in openxr_platform.h ==="
grep -n "EGL\|egl\|android\|Android" "$FILE" | head -20
echo ""
echo "=== XrGraphicsBindingOpenGLESAndroidKHR ==="
grep -n "XrGraphicsBindingOpenGLESAndroidKHR" "$FILE"
