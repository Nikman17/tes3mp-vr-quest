#!/bin/bash
BDIR=/mnt/c/Users/Mykyta/CascadeProjects/windsurf-project-3/tes3mp-vr/buildscripts

echo "==> Removing old NTFS build dir..."
rm -rf "$BDIR/build/arm64"

echo "==> Creating native ext4 build dir..."
mkdir -p /tmp/tes3mp-build/arm64

echo "==> Symlinking build/arm64 -> /tmp/tes3mp-build/arm64 ..."
mkdir -p "$BDIR/build"
ln -sf /tmp/tes3mp-build/arm64 "$BDIR/build/arm64"

echo "==> Verifying symlink..."
ls -la "$BDIR/build/"
echo "Done."
