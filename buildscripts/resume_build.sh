#!/bin/bash
# Delete crabnet build stamp so cmake re-runs only crabnet + openmw
BUILD_DIR=/tmp/tes3mp-build/arm64
rm -f "$BUILD_DIR/crabnet-prefix/src/crabnet-stamp/crabnet-build"
rm -f "$BUILD_DIR/crabnet-prefix/src/crabnet-stamp/crabnet-install"
rm -f "$BUILD_DIR/crabnet-prefix/src/crabnet-stamp/crabnet-done"
echo "Stamps cleared, resuming build..."

DIR=/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/buildscripts
cd "$BUILD_DIR"
make -j2
