#!/bin/bash
FILE="$HOME/tes3mp-build/arm64/openmw-prefix/src/openmw-build/_deps/openxr-src/include/openxr/openxr_platform.h"
echo "=== Lines 160-200 ==="
sed -n '160,200p' "$FILE"
echo ""
echo "=== includes at top ==="
sed -n '1,25p' "$FILE"
